package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.Notification;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the in-app notification inbox.
 */
public class NotificationDao {

    public int insert(int userId, String title, String message,
                      String linkType, Integer linkId) throws SQLException {
        String sql = """
                INSERT INTO notifications (user_id, title, message, link_type, link_id)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, userId);
            ps.setString(2, title);
            ps.setString(3, message);
            if (linkType == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, linkType);
            }
            if (linkId == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setInt(5, linkId);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : 0;
            }
        }
    }

    public int unreadCount(int userId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = 0";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public List<Notification> recent(int userId, int limit) throws SQLException {
        String sql = """
                SELECT notification_id, user_id, title, message, link_type, link_id,
                       is_read, created_at
                  FROM notifications
                 WHERE user_id = ?
                 ORDER BY created_at DESC, notification_id DESC
                 LIMIT ?
                """;
        List<Notification> list = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Notification(
                            rs.getInt("notification_id"),
                            rs.getInt("user_id"),
                            rs.getString("title"),
                            rs.getString("message"),
                            rs.getString("link_type"),
                            (Integer) rs.getObject("link_id"),
                            rs.getBoolean("is_read"),
                            rs.getObject("created_at", LocalDateTime.class)));
                }
            }
        }
        return list;
    }

    public void markRead(int notificationId, int userId) throws SQLException {
        String sql = "UPDATE notifications SET is_read = 1 WHERE notification_id = ? AND user_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, notificationId);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    public void markAllRead(int userId) throws SQLException {
        String sql = "UPDATE notifications SET is_read = 1 WHERE user_id = ? AND is_read = 0";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        }
    }
}
