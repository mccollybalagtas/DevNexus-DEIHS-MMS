package com.deihs.mms.service;

import com.deihs.mms.dao.AccountRequestDao;
import com.deihs.mms.dao.UserDao;
import com.deihs.mms.model.RegistrationDraft;
import com.deihs.mms.util.PasswordPolicy;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Handles self-registration submissions. Accounts are created as pending
 * requests for administrator review; no user row is inserted here.
 *
 * <p>The rules here mirror the form so a request can never be accepted by a
 * crafted client that bypasses the UI checks.</p>
 */
public class RegistrationService {

    private static final Set<String> ALLOWED_ROLES =
            Set.of("STAFF", "PERSONNEL", "MAINT_HEAD");
    private static final Pattern EMAIL_RE =
            Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    private final AccountRequestDao requestDao = new AccountRequestDao();
    private final UserDao userDao = new UserDao();

    public void submit(RegistrationDraft draft) throws ValidationException, SQLException {
        RegistrationDraft clean = clean(draft);
        validate(clean);

        if (userDao.usernameExists(clean.username()) || requestDao.pendingUsernameExists(clean.username())) {
            throw new ValidationException("That username is already taken or has a pending request.");
        }
        if (userDao.employeeNoExists(clean.employeeNo()) || requestDao.pendingEmployeeNoExists(clean.employeeNo())) {
            throw new ValidationException("That employee number is already registered or has a pending request.");
        }
        if (userDao.emailExists(clean.email(), 0)) {
            throw new ValidationException("An account already uses that email address.");
        }

        String hash = AuthService.hashPassword(clean.password());
        requestDao.create(clean, hash);
    }

    private RegistrationDraft clean(RegistrationDraft d) {
        if (d == null) return null;
        String role = text(d.requestedRole());
        if (role != null) role = role.trim().toUpperCase(Locale.ROOT);
        return new RegistrationDraft(
                upper(d.employeeNo()),
                lower(d.username()),
                d.password(),
                d.confirmPassword(),
                text(d.firstName()),
                text(d.lastName()),
                lower(text(d.email())),
                null,
                text(d.department()),
                role,
                d.emailVerified()
        );
    }

    private void validate(RegistrationDraft d) throws ValidationException {
        if (d == null) throw new ValidationException("Nothing to submit.");

        if (d.employeeNo() == null || d.employeeNo().isEmpty()) {
            throw new ValidationException("Enter your employee number.");
        }
        if (d.employeeNo().length() > 30) {
            throw new ValidationException("Employee number is too long.");
        }

        String usernameError = PasswordPolicy.usernameError(d.username());
        if (usernameError != null) {
            throw new ValidationException(usernameError);
        }

        if (d.firstName() == null || d.firstName().length() < 2) {
            throw new ValidationException("Enter your first name.");
        }
        if (d.lastName() == null || d.lastName().length() < 2) {
            throw new ValidationException("Enter your last name.");
        }
        if (d.requestedRole() == null || !ALLOWED_ROLES.contains(d.requestedRole())) {
            throw new ValidationException("Choose a valid role to request.");
        }

        String passwordError = PasswordPolicy.passwordError(d.password());
        if (passwordError != null) {
            throw new ValidationException(passwordError);
        }
        if (!d.password().equals(d.confirmPassword())) {
            throw new ValidationException("Passwords do not match.");
        }

        if (d.email() == null || d.email().isEmpty()) {
            throw new ValidationException("Enter your email address so we can verify your account.");
        }
        if (d.email().length() > 120) {
            throw new ValidationException("Email address is too long.");
        }
        if (!EMAIL_RE.matcher(d.email()).matches()) {
            throw new ValidationException("Enter a valid email address, for example name@gmail.com.");
        }
        if (!d.emailVerified()) {
            throw new ValidationException("Verify your email address before submitting the request.");
        }
        if (d.department() != null && d.department().length() > 80) {
            throw new ValidationException("Department is too long.");
        }
    }

    /** Shared with the registration form so the hint matches the service rule. */
    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_RE.matcher(email.trim()).matches();
    }

    private static String text(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String upper(String v) {
        String t = text(v);
        return t == null ? null : t.toUpperCase(Locale.ROOT);
    }

    private static String lower(String v) {
        String t = text(v);
        return t == null ? null : t.toLowerCase(Locale.ROOT);
    }
}
