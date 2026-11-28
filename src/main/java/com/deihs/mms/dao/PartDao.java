package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.Part;
import com.deihs.mms.model.PartDraft;
import com.deihs.mms.model.RequestPart;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parts inventory and request consumption queries.
 *
 * <p>Stock changes always run inside a transaction and lock the part row, so
 * two technicians using the same part at once can never drive the quantity
 * below zero. The cost of consumed parts is mirrored into
 * {@code maintenance_records.parts_cost} for reporting.</p>
 */
public class PartDao {

    private static final String BASE_SELECT = """
            SELECT part_id, name, sku, unit, quantity, reorder_level,
                   unit_cost, is_active
              FROM parts
             WHERE 1 = 1
            """;

    /** Result of consuming stock, used for audit and low-stock alerts. */
    public record Consumption(String partName, String unit, int remaining,
                              int reorderLevel, BigDecimal unitCost) {
    }

    /** Result of removing a used part, used for audit and stock restore. */
    public record Removal(int requestId, int partId, String partName,
                          int quantity, BigDecimal unitCost) {
    }

    // ------------------------------------------------------------------
    // Inventory
    // ------------------------------------------------------------------

    public List<Part> search(String keyword, boolean includeArchived,
                             boolean lowOnly) throws SQLException {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> params = new ArrayList<>();

        if (!includeArchived) {
            sql.append(" AND is_active = 1");
        }
        if (lowOnly) {
            sql.append(" AND quantity <= reorder_level");
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            sql.append(" AND (name LIKE ? OR sku LIKE ? OR unit LIKE ?)");
            params.add(like);
            params.add(like);
            params.add(like);
        }
        sql.append(" ORDER BY is_active DESC, (quantity <= reorder_level) DESC, name");

        List<Part> rows = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(map(rs));
                }
            }
        }
        return rows;
    }

    public Optional<Part> findById(int id) throws SQLException {
        String sql = BASE_SELECT + " AND part_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public boolean existsName(String name, int excludeId) throws SQLException {
        String sql = "SELECT 1 FROM parts WHERE name = ? AND part_id <> ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setInt(2, excludeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Part findByName(String name) throws SQLException {
        String sql = BASE_SELECT + " AND name = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public int create(PartDraft draft) throws SQLException {
        String sql = """
                INSERT INTO parts (name, sku, unit, quantity, reorder_level, unit_cost)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, draft, 1);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : 0;
            }
        }
    }

    public void update(int id, PartDraft draft) throws SQLException {
        String sql = """
                UPDATE parts
                   SET name = ?, sku = ?, unit = ?, quantity = ?,
                       reorder_level = ?, unit_cost = ?
                 WHERE part_id = ?
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int next = bind(ps, draft, 1);
            ps.setInt(next, id);
            ps.executeUpdate();
        }
    }

    public void setActive(int id, boolean active) throws SQLException {
        String sql = "UPDATE parts SET is_active = ? WHERE part_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, active ? 1 : 0);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public int lowStockCount() throws SQLException {
        String sql = "SELECT COUNT(*) FROM parts WHERE is_active = 1 AND quantity <= reorder_level";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ------------------------------------------------------------------
    // Request consumption
    // ------------------------------------------------------------------

    public List<RequestPart> findByRequest(int requestId) throws SQLException {
        String sql = """
                SELECT rp.id, rp.request_id, rp.part_id, rp.quantity, rp.unit_cost,
                       p.name, p.unit
                  FROM request_parts rp
                  JOIN parts p ON p.part_id = rp.part_id
                 WHERE rp.request_id = ?
                 ORDER BY rp.id
                """;
        List<RequestPart> rows = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new RequestPart(
                            rs.getInt("id"),
                            rs.getInt("request_id"),
                            rs.getInt("part_id"),
                            rs.getString("name"),
                            rs.getString("unit"),
                            rs.getInt("quantity"),
                            rs.getBigDecimal("unit_cost")));
                }
            }
        }
        return rows;
    }

    /**
     * Consumes stock for a request. Locks the part row, verifies stock,
     * records the usage, decrements the quantity, and adds the cost to the
     * request's maintenance record.
     */
    public Consumption consume(int requestId, int partId, int quantity, int actorId)
            throws SQLException {
        if (quantity <= 0) {
            throw new SQLException("Quantity must be greater than zero.");
        }
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                String name;
                String unit;
                int available;
                int reorder;
                BigDecimal cost;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT name, unit, quantity, reorder_level, unit_cost "
                                + "FROM parts WHERE part_id = ? AND is_active = 1 FOR UPDATE")) {
                    ps.setInt(1, partId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("That part is no longer available.");
                        }
                        name = rs.getString("name");
                        unit = rs.getString("unit");
                        available = rs.getInt("quantity");
                        reorder = rs.getInt("reorder_level");
                        cost = rs.getBigDecimal("unit_cost");
                        if (cost == null) {
                            cost = BigDecimal.ZERO;
                        }
                    }
                }
                if (quantity > available) {
                    throw new SQLException("Only " + available + " " + unit
                            + " of " + name + " in stock.");
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO request_parts (request_id, part_id, quantity, unit_cost) "
                                + "VALUES (?, ?, ?, ?)")) {
                    ps.setInt(1, requestId);
                    ps.setInt(2, partId);
                    ps.setInt(3, quantity);
                    ps.setBigDecimal(4, cost);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE parts SET quantity = quantity - ? WHERE part_id = ?")) {
                    ps.setInt(1, quantity);
                    ps.setInt(2, partId);
                    ps.executeUpdate();
                }
                new MaintenanceRecordDao().addPartsCost(c, requestId, actorId,
                        cost.multiply(BigDecimal.valueOf(quantity)));

                c.commit();
                return new Consumption(name, unit, available - quantity, reorder, cost);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /**
     * Removes a used part from a request and returns the stock. The cost is
     * subtracted from the request's maintenance record.
     */
    public Removal removeRequestPart(int requestPartId, int actorId) throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                int requestId;
                int partId;
                int quantity;
                BigDecimal unitCost;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT request_id, part_id, quantity, unit_cost "
                                + "FROM request_parts WHERE id = ? FOR UPDATE")) {
                    ps.setInt(1, requestPartId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("That part usage no longer exists.");
                        }
                        requestId = rs.getInt("request_id");
                        partId = rs.getInt("part_id");
                        quantity = rs.getInt("quantity");
                        unitCost = rs.getBigDecimal("unit_cost");
                        if (unitCost == null) {
                            unitCost = BigDecimal.ZERO;
                        }
                    }
                }
                String name;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT name FROM parts WHERE part_id = ?")) {
                    ps.setInt(1, partId);
                    try (ResultSet rs = ps.executeQuery()) {
                        name = rs.next() ? rs.getString("name") : "Part";
                    }
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM request_parts WHERE id = ?")) {
                    ps.setInt(1, requestPartId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE parts SET quantity = quantity + ? WHERE part_id = ?")) {
                    ps.setInt(1, quantity);
                    ps.setInt(2, partId);
                    ps.executeUpdate();
                }
                new MaintenanceRecordDao().addPartsCost(c, requestId, actorId,
                        unitCost.multiply(BigDecimal.valueOf(quantity)).negate());

                c.commit();
                return new Removal(requestId, partId, name, quantity, unitCost);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private Part map(ResultSet rs) throws SQLException {
        int quantity = rs.getInt("quantity");
        int reorder = rs.getInt("reorder_level");
        BigDecimal cost = rs.getBigDecimal("unit_cost");
        return new Part(
                rs.getInt("part_id"),
                rs.getString("name"),
                rs.getString("sku"),
                rs.getString("unit"),
                quantity,
                reorder,
                cost == null ? BigDecimal.ZERO : cost,
                rs.getInt("is_active") == 1,
                quantity <= reorder
        );
    }

    private static int bind(PreparedStatement ps, PartDraft d, int start)
            throws SQLException {
        int i = start;
        ps.setString(i++, d.name());
        ps.setString(i++, d.sku());
        ps.setString(i++, d.unit());
        ps.setInt(i++, d.quantity() == null ? 0 : d.quantity());
        ps.setInt(i++, d.reorderLevel() == null ? 0 : d.reorderLevel());
        ps.setBigDecimal(i++, d.unitCost() == null ? BigDecimal.ZERO : d.unitCost());
        return i;
    }
}
