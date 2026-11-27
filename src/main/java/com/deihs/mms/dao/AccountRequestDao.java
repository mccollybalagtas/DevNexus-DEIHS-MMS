package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.AccountRequest;
import com.deihs.mms.model.RegistrationDraft;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for self-registration requests. Approval creates a real user in
 * a separate flow (service/Users module); this DAO only tracks the request.
 */
public class AccountRequestDao {

    public void create(RegistrationDraft draft, String passwordHash) throws SQLException {
        String sql = """
                INSERT INTO account_requests
                    (employee_no, username, password_hash, first_name, last_name,
                     email, phone, department, requested_role, email_verified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, draft.employeeNo());
            ps.setString(2, draft.username());
            ps.setString(3, passwordHash);
            ps.setString(4, draft.firstName());
            ps.setString(5, draft.lastName());
            ps.setString(6, draft.email());
            ps.setString(7, draft.phone());
            ps.setString(8, draft.department());
            ps.setString(9, draft.requestedRole());
            ps.setInt(10, draft.emailVerified() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public boolean pendingUsernameExists(String username) throws SQLException {
        String sql = "SELECT 1 FROM account_requests WHERE username = ? AND status = 'PENDING'";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean pendingEmployeeNoExists(String employeeNo) throws SQLException {
        String sql = "SELECT 1 FROM account_requests WHERE employee_no = ? AND status = 'PENDING'";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, employeeNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public List<AccountRequest> findPending() throws SQLException {
        String sql = """
                SELECT request_id, employee_no, username, first_name, last_name,
                       email, phone, department, requested_role, status,
                       created_at, review_notes
                FROM account_requests
                WHERE status = 'PENDING'
                ORDER BY created_at DESC
                """;
        List<AccountRequest> list = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        }
        return list;
    }

    public Optional<AccountRequest> findById(int id) throws SQLException {
        String sql = """
                SELECT request_id, employee_no, username, first_name, last_name,
                       email, phone, department, requested_role, status,
                       created_at, review_notes
                FROM account_requests
                WHERE request_id = ?
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        }
        return Optional.empty();
    }

    public void markReviewed(int requestId, String status, int reviewerId, String notes)
            throws SQLException {
        String sql = """
                UPDATE account_requests
                   SET status = ?, reviewed_by = ?, reviewed_at = NOW(), review_notes = ?
                 WHERE request_id = ? AND status = 'PENDING'
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setInt(2, reviewerId);
            ps.setString(3, notes);
            ps.setInt(4, requestId);
            ps.executeUpdate();
        }
    }

    /** Full details needed to create a user during approval (includes hash). */
    public Optional<ApprovalData> findForApproval(int requestId) throws SQLException {
        String sql = """
                SELECT request_id, employee_no, username, password_hash, first_name, last_name,
                       email, phone, department, requested_role, status, email_verified
                FROM account_requests
                WHERE request_id = ?
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ApprovalData(
                            rs.getInt("request_id"),
                            rs.getString("employee_no"),
                            rs.getString("username"),
                            rs.getString("password_hash"),
                            rs.getString("first_name"),
                            rs.getString("last_name"),
                            rs.getString("email"),
                            rs.getString("phone"),
                            rs.getString("department"),
                            rs.getString("requested_role"),
                            rs.getString("status"),
                            rs.getBoolean("email_verified")
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public record ApprovalData(
            int id,
            String employeeNo,
            String username,
            String passwordHash,
            String firstName,
            String lastName,
            String email,
            String phone,
            String department,
            String requestedRole,
            String status,
            boolean emailVerified
    ) {}

    private AccountRequest map(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new AccountRequest(
                rs.getInt("request_id"),
                rs.getString("employee_no"),
                rs.getString("username"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("department"),
                rs.getString("requested_role"),
                rs.getString("status"),
                created == null ? null : created.toLocalDateTime(),
                rs.getString("review_notes")
        );
    }
}
