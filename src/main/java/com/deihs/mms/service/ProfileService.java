package com.deihs.mms.service;

import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.dao.UserDao;
import com.deihs.mms.model.User;
import com.deihs.mms.util.PasswordPolicy;
import org.mindrot.jbcrypt.BCrypt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;

/**
 * Self-service profile changes. Unlike {@link UserService}, every method here
 * acts on the signed-in user and never accepts a role or an account id from
 * the caller, so the profile page cannot be used to edit somebody else.
 */
public class ProfileService {

    private static final long MAX_PHOTO_BYTES = 6L * 1024 * 1024;

    private final UserDao userDao = new UserDao();
    private final AuditDao auditDao = new AuditDao();

    /** Folder that holds local avatar copies, created on demand. */
    public static Path avatarDirectory() throws IOException {
        Path dir = Paths.get(System.getProperty("user.home"), ".deihs-mms", "avatars");
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Saves the text fields and returns a refreshed user so the session and
     * the top bar show the new values immediately.
     */
    public User updateDetails(User current, String firstName, String lastName,
                              String username, String email, String department)
            throws ValidationException, SQLException {
        requireUser(current);

        String first = text(firstName);
        String last = text(lastName);
        String name = text(username);
        String mail = text(email);
        String dept = text(department);

        if (first == null || first.length() < 2) {
            throw new ValidationException("Enter your first name.");
        }
        if (last == null || last.length() < 2) {
            throw new ValidationException("Enter your last name.");
        }
        String usernameError = PasswordPolicy.usernameError(name);
        if (usernameError != null) {
            throw new ValidationException(usernameError);
        }
        if (mail == null || !RegistrationService.isValidEmail(mail)) {
            throw new ValidationException("Enter a valid email address, for example name@gmail.com.");
        }
        if (dept != null && dept.length() > 80) {
            throw new ValidationException("Department is too long.");
        }

        if (!name.equalsIgnoreCase(current.username())
                && userDao.usernameExists(name, current.id())) {
            throw new ValidationException("That username is already taken.");
        }
        if (!mail.equalsIgnoreCase(current.email() == null ? "" : current.email())
                && userDao.emailExists(mail, current.id())) {
            throw new ValidationException("Another account already uses that email address.");
        }

        String normalisedMail = mail.toLowerCase(Locale.ROOT);
        userDao.updateProfile(current.id(), first, last, normalisedMail, dept);
        if (!name.equals(current.username())) {
            userDao.updateUsername(current.id(), name);
        }

        // A new address has not been proven yet. Dropping the flag keeps the
        // verification badge honest instead of carrying trust across a change.
        boolean emailChanged = current.email() == null
                || !normalisedMail.equalsIgnoreCase(current.email());
        if (emailChanged && current.emailVerified()) {
            userDao.setEmailVerified(current.id(), false);
        }

        auditDao.log(current.id(), "UPDATE_PROFILE", "users",
                String.valueOf(current.id()), "Profile details changed");

        return reload(current);
    }

    /** Verifies the current password, applies the policy, then stores a new hash. */
    public void changePassword(User current, String currentPassword,
                               String newPassword, String confirmPassword)
            throws ValidationException, SQLException {
        requireUser(current);

        String existing = userDao.findPasswordHash(current.id())
                .orElseThrow(() -> new ValidationException(
                        "This account has no local password to change."));
        if (currentPassword == null || currentPassword.isEmpty()) {
            throw new ValidationException("Enter your current password.");
        }
        if (!BCrypt.checkpw(currentPassword, existing)) {
            throw new ValidationException("Your current password is incorrect.");
        }
        String error = PasswordPolicy.passwordError(newPassword);
        if (error != null) {
            throw new ValidationException(error);
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new ValidationException("The new passwords do not match.");
        }
        if (newPassword.equals(currentPassword)) {
            throw new ValidationException("Choose a password different from the current one.");
        }

        userDao.updatePassword(current.id(), AuthService.hashPassword(newPassword));
        auditDao.log(current.id(), "CHANGE_PASSWORD", "users",
                String.valueOf(current.id()), "Changed own password");
    }

    /**
     * Copies the chosen image into the per-user avatar folder and stores that
     * path. Copying (rather than referencing the original) means the avatar
     * survives the user moving or deleting the source file.
     */
    public User savePhoto(User current, Path source) throws ValidationException, SQLException {
        requireUser(current);
        if (source == null || !Files.isReadable(source)) {
            throw new ValidationException("Choose an image file first.");
        }
        try {
            if (Files.size(source) > MAX_PHOTO_BYTES) {
                throw new ValidationException("Choose an image smaller than 6 MB.");
            }
            String extension = extension(source);
            if (!extension.matches("png|jpg|jpeg|gif|bmp")) {
                throw new ValidationException("Use a PNG, JPG, GIF or BMP image.");
            }
            Path target = avatarDirectory()
                    .resolve("user-" + current.id() + "." + extension)
                    .toAbsolutePath();
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

            // Remove a previous copy that used a different extension so the
            // folder does not accumulate stale avatars.
            try (var stream = Files.list(avatarDirectory())) {
                stream.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith("user-" + current.id() + ".")
                            && !p.equals(target);
                }).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // A locked old file is harmless.
                    }
                });
            }

            userDao.updatePhoto(current.id(), target.toString());
            auditDao.log(current.id(), "UPDATE_PHOTO", "users",
                    String.valueOf(current.id()), "Profile photo changed");
            return reload(current);
        } catch (IOException e) {
            throw new ValidationException("Could not save the photo: " + e.getMessage());
        }
    }

    public User clearPhoto(User current) throws SQLException {
        requireUser(current);
        userDao.updatePhoto(current.id(), null);
        return reload(current);
    }

    public User savePreferences(User current, String theme, String density)
            throws SQLException {
        requireUser(current);
        String safeTheme = switch (theme == null ? "" : theme) {
            case "navy" -> "navy";
            case "dark" -> "dark";
            default -> "green";
        };
        String safeDensity = "compact".equals(density) ? "compact" : "comfortable";
        userDao.updatePreferences(current.id(), safeTheme, safeDensity);
        return reload(current);
    }

    private User reload(User current) throws SQLException {
        Optional<User> fresh = userDao.findById(current.id());
        User updated = fresh.orElse(current);
        Session.set(updated);
        return updated;
    }

    private static void requireUser(User user) {
        if (user == null) {
            throw new IllegalStateException("No signed-in user.");
        }
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
