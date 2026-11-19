package com.deihs.mms.dao;

import com.deihs.mms.db.Database;

import com.deihs.mms.model.AuditLogEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class AuditDao {

    public void log(Integer userId, String action, String entityType, String entityId, String details) {
        String sql = """
                INSERT INTO audit_logs (user_id, action, entity_type, entity_id, details)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (userId == null) {
                ps.setNull(1, java.sql.Types.INTEGER);
            } else {
                ps.setInt(1, userId);
            }
            ps.setString(2, action);
            ps.setString(3, entityType);
            ps.setString(4, entityId);
            ps.setString(5, details);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Auditing must never break the user's action.
            System.err.println("Audit log failed: " + e.getMessage());
        }
    }

    public List<AuditLogEntry> search(String keyword, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT a.log_id,
                       COALESCE(CONCAT(u.first_name, ' ', u.last_name), u.username, 'System') AS actor,
                       a.action, a.entity_type, a.entity_id, a.details, a.ip_address, a.created_at
                FROM audit_logs a
                LEFT JOIN users u ON u.user_id = a.user_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            sql.append("""
                     AND (a.action LIKE ?
                       OR a.entity_type LIKE ?
                       OR a.entity_id LIKE ?
                       OR a.details LIKE ?
                       OR a.ip_address LIKE ?
                       OR u.username LIKE ?
                       OR CONCAT(u.first_name, ' ', u.last_name) LIKE ?)
                    """);
            for (int i = 0; i < 7; i++) params.add(like);
        }
        sql.append(" ORDER BY a.created_at DESC, a.log_id DESC LIMIT ?");
        params.add(Math.max(1, Math.min(limit, 1000)));

        List<AuditLogEntry> rows = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("created_at");
                    rows.add(new AuditLogEntry(
                            rs.getLong("log_id"),
                            rs.getString("actor"),
                            rs.getString("action"),
                            rs.getString("entity_type"),
                            rs.getString("entity_id"),
                            rs.getString("details"),
                            rs.getString("ip_address"),
                            ts == null ? null : ts.toLocalDateTime()
                    ));
                }
            }
        }
        return rows;
    }
}
