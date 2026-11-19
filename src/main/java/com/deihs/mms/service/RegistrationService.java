package com.deihs.mms.service;

import com.deihs.mms.dao.AccountRequestDao;
import com.deihs.mms.dao.UserDao;
import com.deihs.mms.model.RegistrationDraft;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Handles self-registration submissions. Accounts are created as pending
 * requests for administrator review; no user row is inserted here.
 */
public class RegistrationService {

    private static final Set<String> ALLOWED_ROLES =
            Set.of("STAFF", "PERSONNEL", "MAINT_HEAD");
    private static final Pattern USERNAME_RE = Pattern.compile("^[a-zA-Z0-9._-]{3,50}$");

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
                text(d.phone()),
                text(d.department()),
                role
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
        if (d.username() == null || !USERNAME_RE.matcher(d.username()).matches()) {
            throw new ValidationException("Username must be 3-50 characters (letters, numbers, dot, underscore, dash).");
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
        if (d.password() == null || d.password().length() < 6) {
            throw new ValidationException("Password must be at least 6 characters.");
        }
        if (!d.password().equals(d.confirmPassword())) {
            throw new ValidationException("Passwords do not match.");
        }
        if (d.email() != null && d.email().length() > 120) {
            throw new ValidationException("Email address is too long.");
        }
        if (d.department() != null && d.department().length() > 80) {
            throw new ValidationException("Department is too long.");
        }
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
