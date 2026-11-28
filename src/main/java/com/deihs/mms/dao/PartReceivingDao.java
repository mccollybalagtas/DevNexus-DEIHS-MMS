package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.PartReceiving;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class PartReceivingDao {

    public List<PartReceiving> listRecent(int limit) throws SQLException {
        String sql = """
                SELECT pr.receiving_id, pr.part_id, p.name AS part_name, pr.quantity, pr.unit_cost,
                       pr.supplier, pr.reference_no, pr.notes,
                       COALESCE(CONCAT(u.first_name, ' ', u.last_name), u.username, '—') AS received_by,
                       pr.received_at
                FROM part_receivings pr
                JOIN parts p ON p.part_id = pr.part_id
                LEFT JOIN users u ON u.user_id = pr.received_by
                ORDER BY pr.received_at DESC, pr.receiving_id DESC
                LIMIT ?
                """;
        List<PartReceiving> out = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    /** Records a receiving and increments stock in one transaction. */
    public int receive(int partId, int quantity, BigDecimal unitCost, String supplier,
                       String referenceNo, String notes, int actorId) throws SQLException {
        if (quantity <= 0) throw new SQLException("Quantity must be greater than zero.");
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement lock = c.prepareStatement(
                        "SELECT part_id FROM parts WHERE part_id = ? FOR UPDATE")) {
                    lock.setInt(1, partId);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Part not found.");
                    }
                }
                int id;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO part_receivings (part_id, quantity, unit_cost, supplier, reference_no, notes, received_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, partId);
                    ps.setInt(2, quantity);
                    ps.setBigDecimal(3, unitCost == null ? BigDecimal.ZERO : unitCost);
                    ps.setString(4, blankToNull(supplier));
                    ps.setString(5, blankToNull(referenceNo));
                    ps.setString(6, blankToNull(notes));
                    ps.setInt(7, actorId);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        id = keys.next() ? keys.getInt(1) : 0;
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE parts SET quantity = quantity + ?, unit_cost = CASE WHEN ? > 0 THEN ? ELSE unit_cost END WHERE part_id = ?")) {
                    ps.setInt(1, quantity);
                    ps.setBigDecimal(2, unitCost == null ? BigDecimal.ZERO : unitCost);
                    ps.setBigDecimal(3, unitCost == null ? BigDecimal.ZERO : unitCost);
                    ps.setInt(4, partId);
                    ps.executeUpdate();
                }
                c.commit();
                return id;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private static PartReceiving map(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("received_at");
        return new PartReceiving(
                rs.getInt("receiving_id"),
                rs.getInt("part_id"),
                rs.getString("part_name"),
                rs.getInt("quantity"),
                rs.getBigDecimal("unit_cost"),
                rs.getString("supplier"),
                rs.getString("reference_no"),
                rs.getString("notes"),
                rs.getString("received_by"),
                ts == null ? null : ts.toLocalDateTime()
        );
    }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
}
