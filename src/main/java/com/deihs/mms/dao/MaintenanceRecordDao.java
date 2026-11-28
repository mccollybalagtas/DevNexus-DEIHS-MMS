package com.deihs.mms.dao;

import com.deihs.mms.db.Database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class MaintenanceRecordDao {

    public BigDecimal findTotalCostByRequest(int requestId) throws SQLException {
        String sql = "SELECT COALESCE(SUM(cost),0) FROM maintenance_records WHERE request_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal v = rs.getBigDecimal(1);
                    return v == null ? BigDecimal.ZERO : v;
                }
            }
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal findPartsCostByRequest(int requestId) throws SQLException {
        String sql = "SELECT COALESCE(SUM(parts_cost),0) FROM maintenance_records WHERE request_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal v = rs.getBigDecimal(1);
                    return v == null ? BigDecimal.ZERO : v;
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Adds (or subtracts) the cost of parts consumed by a request. Runs on the
     * caller's transaction so stock and cost stay in step.
     */
    public void addPartsCost(Connection c, int requestId, int actorId, BigDecimal delta)
            throws SQLException {
        Integer recordId = findLatestRecordId(c, requestId);
        if (recordId != null) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE maintenance_records SET parts_cost = parts_cost + ? WHERE record_id = ?")) {
                ps.setBigDecimal(1, delta);
                ps.setInt(2, recordId);
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO maintenance_records (request_id, personnel_id, actions_taken, parts_cost) "
                            + "VALUES (?, ?, ?, ?)")) {
                ps.setInt(1, requestId);
                ps.setInt(2, actorId);
                ps.setString(3, "Parts recorded");
                ps.setBigDecimal(4, delta);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Sets repair cost for a request. If a record exists, updates the most recent one;
     * otherwise creates a minimal record attributed to the actor.
     */
    public void upsertCost(int requestId, int actorId, BigDecimal cost) throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                Integer recordId = findLatestRecordId(c, requestId);
                if (recordId != null) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE maintenance_records SET cost = ? WHERE record_id = ?")) {
                        ps.setBigDecimal(1, cost);
                        ps.setInt(2, recordId);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO maintenance_records (request_id, personnel_id, actions_taken, cost) "
                                    + "VALUES (?, ?, ?, ?)")) {
                        ps.setInt(1, requestId);
                        ps.setInt(2, actorId);
                        ps.setString(3, "Repair cost recorded");
                        ps.setBigDecimal(4, cost);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private Integer findLatestRecordId(Connection c, int requestId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT record_id FROM maintenance_records WHERE request_id = ? ORDER BY record_id DESC LIMIT 1")) {
            ps.setInt(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return null;
    }
}
