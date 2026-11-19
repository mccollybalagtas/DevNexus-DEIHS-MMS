package com.deihs.mms.service;

import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.dao.UserDao;
import com.deihs.mms.model.User;
import com.deihs.mms.model.UserCredentials;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Authentication is the only place a password is ever compared. The role is
 * read from the database — never chosen by the user at login.
 */
public class AuthService {

    private final UserDao userDao = new UserDao();
    private final AuditDao auditDao = new AuditDao();

    public User login(String username, String password) throws AuthException, SQLException {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty() || password == null || password.isEmpty()) {
            throw new AuthException("Enter your username and password.");
        }

        Optional<UserCredentials> found = userDao.findByUsername(name);
        if (found.isEmpty()) {
            // Same message for unknown user and wrong password: do not leak which accounts exist.
            throw new AuthException("Invalid username or password.");
        }

        UserCredentials creds = found.get();
        if (!creds.user().active()) {
            throw new AuthException("This account has been deactivated. Contact the administrator.");
        }

        if (!BCrypt.checkpw(password, creds.passwordHash())) {
            auditDao.log(creds.user().id(), "LOGIN_FAILED", "USER",
                    String.valueOf(creds.user().id()), "Incorrect password");
            throw new AuthException("Invalid username or password.");
        }

        userDao.touchLastLogin(creds.user().id());
        auditDao.log(creds.user().id(), "LOGIN", "USER",
                String.valueOf(creds.user().id()), "Signed in");

        return creds.user();
    }

    public static String hashPassword(String plain) {
        return BCrypt.hashpw(plain, BCrypt.gensalt(10));
    }
}
