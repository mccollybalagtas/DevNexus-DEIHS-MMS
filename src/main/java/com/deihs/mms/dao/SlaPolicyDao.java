package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.SlaPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SlaPolicyDao {

    public List<SlaPolicy> findAll() throws SQLException {
        String sql = "SELECT policy_id, priority, target_hours, is_active FROM sla_policies ORDER BY FIELD(priority,'CRITICAL','HIGH','MEDIUM','LOW'), priority";
        List<SlaPolicy> out = new ArrayList<>();
        try (Connection c = Database.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(map(rs));
        }
        return out;
    }

    public Optional<SlaPolicy> findByPriority(String priority) throws SQLException {
        String sql = "SELECT policy_id, priority, target_hours, is_active FROM sla_policies WHERE priority = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, priority);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    public void update(int policyId, int targetHours, boolean active) throws SQLException {
        String sql = "UPDATE sla_policies SET target_hours = ?, is_active = ? WHERE policy_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, targetHours);
            ps.setBoolean(2, active);
            ps.setInt(3, policyId);
            ps.executeUpdate();
        }
    }

    public void insert(String priority, int targetHours, boolean active) throws SQLException {
        String sql = "INSERT INTO sla_policies (priority, target_hours, is_active) VALUES (?, ?, ?)";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, priority);
            ps.setInt(2, targetHours);
            ps.setBoolean(3, active);
            ps.executeUpdate();
        }
    }

    private static SlaPolicy map(ResultSet rs) throws SQLException {
        return new SlaPolicy(
                rs.getInt("policy_id"),
                rs.getString("priority"),
                rs.getInt("target_hours"),
                rs.getBoolean("is_active")
        );
    }
}
