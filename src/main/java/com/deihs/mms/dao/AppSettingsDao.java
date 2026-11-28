package com.deihs.mms.dao;

import com.deihs.mms.db.Database;

import java.sql.*;
import java.util.Optional;

public class AppSettingsDao {

    public Optional<String> get(String key) throws SQLException {
        String sql = "SELECT setting_value FROM app_settings WHERE setting_key = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.ofNullable(rs.getString(1));
            }
        }
        return Optional.empty();
    }

    public void set(String key, String value, Integer userId) throws SQLException {
        String sql = """
                INSERT INTO app_settings (setting_key, setting_value, updated_by, updated_at)
                VALUES (?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value),
                                        updated_by = VALUES(updated_by),
                                        updated_at = NOW()
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            if (value == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, value);
            if (userId == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, userId);
            ps.executeUpdate();
        }
    }

    public int getInt(String key, int defaultValue) throws SQLException {
        return get(key).map(v -> {
            try { return Integer.parseInt(v.trim()); } catch (Exception e) { return defaultValue; }
        }).orElse(defaultValue);
    }
}
