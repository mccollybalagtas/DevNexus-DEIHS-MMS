package com.deihs.mms.service;

import com.deihs.mms.dao.AccountRequestDao;
import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.dao.UserDao;
import com.deihs.mms.model.Role;
import com.deihs.mms.model.User;
import com.deihs.mms.model.UserDraft;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Admin user management and account-request approval.
 */
public class UserService {

    private static final Set<String> ROLE_CODES =
            Set.of("ADMIN", "MAINT_HEAD", "PERSONNEL", "STAFF");
    private static final Pattern USERNAME_RE = Pattern.compile("^[a-zA-Z0-9._-]{3,50}$");

    private final UserDao userDao = new UserDao();
    private final AccountRequestDao requestDao = new AccountRequestDao();
    private final AuditDao auditDao = new AuditDao();

    public int create(UserDraft draft, User actor) throws ValidationException, SQLException {
        requireAdmin(actor);
        UserDraft clean = clean(draft, false);
        validate(clean, 0, true);
        ensureUnique(clean, 0);

        String hash = AuthService.hashPassword(clean.password());
        int id = userDao.create(clean.employeeNo(), clean.username(), hash,
                clean.firstName(), clean.lastName(), clean.email(), clean.phone(),
                clean.department(), clean.roleCode(), true);
        auditDao.log(actor.id(), "CREATE_USER", "users", String.valueOf(id),
                clean.username() + " (" + clean.roleCode() + ")");
        return id;
    }

    public void update(int userId, UserDraft draft, User actor) throws ValidationException, SQLException {
        requireAdmin(actor);
        UserDraft clean = clean(draft, true);
        validate(clean, userId, false);
        ensureUnique(clean, userId);

        userDao.update(userId, clean.employeeNo(), clean.username(),
                clean.firstName(), clean.lastName(), clean.email(), clean.phone(),
                clean.department(), clean.roleCode());
        auditDao.log(actor.id(), "UPDATE_USER", "users", String.valueOf(userId),
                clean.username() + " (" + clean.roleCode() + ")");
    }

    public void setActive(int userId, String username, boolean active, User actor)
            throws ValidationException, SQLException {
        requireAdmin(actor);
        if (!active && actor.id() == userId) {
            throw new ValidationException("You cannot deactivate your own account.");
        }
        userDao.setActive(userId, active);
        auditDao.log(actor.id(), active ? "ACTIVATE_USER" : "DEACTIVATE_USER",
                "users", String.valueOf(userId), username);
    }

    public void resetPassword(int userId, String username, String newPassword, User actor)
            throws ValidationException, SQLException {
        requireAdmin(actor);
        if (newPassword == null || newPassword.length() < 6) {
            throw new ValidationException("Password must be at least 6 characters.");
        }
        userDao.updatePassword(userId, AuthService.hashPassword(newPassword));
        auditDao.log(actor.id(), "RESET_PASSWORD", "users", String.valueOf(userId), username);
    }

    public void approveRequest(int requestId, String roleCode, User actor)
            throws ValidationException, SQLException {
        requireAdmin(actor);
        String role = roleCode == null ? null : roleCode.trim().toUpperCase(Locale.ROOT);
        if (role == null || !ROLE_CODES.contains(role)) {
            throw new ValidationException("Choose a valid role for the new account.");
        }

        var dataOpt = requestDao.findForApproval(requestId);
        if (dataOpt.isEmpty()) {
            throw new ValidationException("Account request not found.");
        }
        var data = dataOpt.get();
        if (!"PENDING".equals(data.status())) {
            throw new ValidationException("This request has already been reviewed.");
        }

        if (userDao.usernameExists(data.username(), 0)) {
            throw new ValidationException("Username " + data.username() + " is already in use.");
        }
        if (userDao.employeeNoExists(data.employeeNo(), 0)) {
            throw new ValidationException("Employee number " + data.employeeNo() + " is already in use.");
        }

        int userId = userDao.create(data.employeeNo(), data.username(), data.passwordHash(),
                data.firstName(), data.lastName(), data.email(), data.phone(),
                data.department(), role, true);
        if (data.emailVerified()) {
            userDao.setEmailVerified(userId, true);
        }
        requestDao.markReviewed(requestId, "APPROVED", actor.id(), "Approved as " + role);
        auditDao.log(actor.id(), "APPROVE_ACCOUNT_REQUEST", "account_requests",
                String.valueOf(requestId), data.username() + " -> user " + userId + " (" + role + ")");
        auditDao.log(actor.id(), "CREATE_USER", "users", String.valueOf(userId),
                data.username() + " (from registration)");
    }

    public void rejectRequest(int requestId, String notes, User actor)
            throws ValidationException, SQLException {
        requireAdmin(actor);
        var dataOpt = requestDao.findForApproval(requestId);
        if (dataOpt.isEmpty()) {
            throw new ValidationException("Account request not found.");
        }
        if (!"PENDING".equals(dataOpt.get().status())) {
            throw new ValidationException("This request has already been reviewed.");
        }
        String reason = notes == null || notes.trim().isEmpty() ? "Rejected" : notes.trim();
        requestDao.markReviewed(requestId, "REJECTED", actor.id(), reason);
        auditDao.log(actor.id(), "REJECT_ACCOUNT_REQUEST", "account_requests",
                String.valueOf(requestId), reason);
    }

    private void requireAdmin(User actor) throws ValidationException {
        if (!Permissions.canManageUsers(actor)) {
            throw new ValidationException("Only administrators can manage user accounts.");
        }
    }

    private UserDraft clean(UserDraft d, boolean forUpdate) {
        if (d == null) return null;
        String role = text(d.roleCode());
        if (role != null) role = role.trim().toUpperCase(Locale.ROOT);
        return new UserDraft(
                upper(d.employeeNo()),
                lower(d.username()),
                text(d.firstName()),
                text(d.lastName()),
                lower(text(d.email())),
                text(d.phone()),
                text(d.department()),
                role,
                forUpdate ? null : d.password()
        );
    }

    private void validate(UserDraft d, int excludeId, boolean requirePassword) throws ValidationException {
        if (d == null) throw new ValidationException("Nothing to save.");
        if (d.employeeNo() == null || d.employeeNo().isEmpty()) {
            throw new ValidationException("Employee number is required.");
        }
        if (d.username() == null || !USERNAME_RE.matcher(d.username()).matches()) {
            throw new ValidationException("Username must be 3-50 characters (letters, numbers, dot, underscore, dash).");
        }
        if (d.firstName() == null || d.firstName().length() < 2) {
            throw new ValidationException("First name is required.");
        }
        if (d.lastName() == null || d.lastName().length() < 2) {
            throw new ValidationException("Last name is required.");
        }
        if (d.roleCode() == null || !ROLE_CODES.contains(d.roleCode())) {
            throw new ValidationException("Choose a valid role.");
        }
        if (requirePassword && (d.password() == null || d.password().length() < 6)) {
            throw new ValidationException("Password must be at least 6 characters.");
        }
    }

    private void ensureUnique(UserDraft d, int excludeId) throws ValidationException, SQLException {
        if (userDao.usernameExists(d.username(), excludeId)) {
            throw new ValidationException("Username is already in use.");
        }
        if (userDao.employeeNoExists(d.employeeNo(), excludeId)) {
            throw new ValidationException("Employee number is already in use.");
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
