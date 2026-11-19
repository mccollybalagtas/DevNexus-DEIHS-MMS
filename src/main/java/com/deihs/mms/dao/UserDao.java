package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.Role;
import com.deihs.mms.model.User;
import com.deihs.mms.model.UserCredentials;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class UserDao {

    private static final String BASE_SELECT = """
            SELECT u.user_id, u.employee_no, u.username, u.password_hash,
                   u.first_name, u.last_name, u.email, u.phone,
                   u.department, u.is_active, u.last_login,
                   r.role_code
            FROM users u
            JOIN roles r ON r.role_id = u.role_id
            """;

    public Optional<UserCredentials> findByUsername(String username) throws SQLException {
        String sql = BASE_SELECT + " WHERE u.username = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new UserCredentials(mapUser(rs), rs.getString("password_hash")));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<User> findById(int id) throws SQLException {
        String sql = BASE_SELECT + " WHERE u.user_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapUser(rs));
                }
            }
        }
        return Optional.empty();
    }

    /** Find active user by email (case-insensitive). */
    public Optional<User> findActiveByEmail(String email) throws SQLException {
        if (email == null || email.isBlank()) return Optional.empty();
        String sql = BASE_SELECT + " WHERE LOWER(u.email) = LOWER(?) AND u.is_active = 1";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapUser(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<User> findByEmail(String email) throws SQLException {
        if (email == null || email.isBlank()) return Optional.empty();
        String sql = BASE_SELECT + " WHERE LOWER(u.email) = LOWER(?)";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapUser(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<User> findAll() throws SQLException {
        String sql = BASE_SELECT + " ORDER BY u.last_name, u.first_name";
        List<User> users = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(mapUser(rs));
            }
        }
        return users;
    }

    public List<User> findByRole(Role role) throws SQLException {
        String sql = BASE_SELECT + " WHERE r.role_code = ? AND u.is_active = 1"
                + " ORDER BY u.last_name, u.first_name";
        List<User> users = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, role.code());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    users.add(mapUser(rs));
                }
            }
        }
        return users;
    }

    public void touchLastLogin(int userId) throws SQLException {
        String sql = "UPDATE users SET last_login = NOW() WHERE user_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        }
    }

    public boolean usernameExists(String username) throws SQLException {
        return exists("SELECT 1 FROM users WHERE username = ?", username, 0);
    }

    public boolean usernameExists(String username, int excludeId) throws SQLException {
        return exists("SELECT 1 FROM users WHERE username = ? AND user_id <> ?", username, excludeId);
    }

    public boolean employeeNoExists(String employeeNo) throws SQLException {
        return exists("SELECT 1 FROM users WHERE employee_no = ?", employeeNo, 0);
    }

    public boolean employeeNoExists(String employeeNo, int excludeId) throws SQLException {
        return exists("SELECT 1 FROM users WHERE employee_no = ? AND user_id <> ?", employeeNo, excludeId);
    }

    private boolean exists(String sql, String value, int excludeId) throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            if (excludeId > 0 || sql.contains("<>")) {
                // only set when placeholder exists; safe for both forms above
                if (sql.contains("<>")) ps.setInt(2, excludeId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public int create(String employeeNo, String username, String passwordHash,
                      String firstName, String lastName, String email, String phone,
                      String department, String roleCode, boolean active) throws SQLException {
        String sql = """
                INSERT INTO users
                    (employee_no, username, password_hash, first_name, last_name,
                     email, phone, department, role_id, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, (SELECT role_id FROM roles WHERE role_code = ?), ?)
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            ps.setString(i++, employeeNo);
            ps.setString(i++, username);
            ps.setString(i++, passwordHash);
            ps.setString(i++, firstName);
            ps.setString(i++, lastName);
            ps.setString(i++, email);
            ps.setString(i++, phone);
            ps.setString(i++, department);
            ps.setString(i++, roleCode);
            ps.setInt(i++, active ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : 0;
            }
        }
    }

    public void update(int userId, String employeeNo, String username,
                       String firstName, String lastName, String email, String phone,
                       String department, String roleCode) throws SQLException {
        String sql = """
                UPDATE users
                   SET employee_no = ?, username = ?, first_name = ?, last_name = ?,
                       email = ?, phone = ?, department = ?,
                       role_id = (SELECT role_id FROM roles WHERE role_code = ?)
                 WHERE user_id = ?
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, employeeNo);
            ps.setString(i++, username);
            ps.setString(i++, firstName);
            ps.setString(i++, lastName);
            ps.setString(i++, email);
            ps.setString(i++, phone);
            ps.setString(i++, department);
            ps.setString(i++, roleCode);
            ps.setInt(i++, userId);
            ps.executeUpdate();
        }
    }

    public void setActive(int userId, boolean active) throws SQLException {
        String sql = "UPDATE users SET is_active = ? WHERE user_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, active ? 1 : 0);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    public void updatePassword(int userId, String passwordHash) throws SQLException {
        String sql = "UPDATE users SET password_hash = ? WHERE user_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, passwordHash);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    private User mapUser(ResultSet rs) throws SQLException {
        Timestamp lastLogin = rs.getTimestamp("last_login");
        return new User(
                rs.getInt("user_id"),
                rs.getString("employee_no"),
                rs.getString("username"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("email"),
                rs.getString("phone"),
                Role.fromCode(rs.getString("role_code")),
                rs.getString("department"),
                rs.getBoolean("is_active"),
                lastLogin == null ? null : lastLogin.toLocalDateTime()
        );
    }
}
