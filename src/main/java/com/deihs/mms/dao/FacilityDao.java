package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.Facility;
import com.deihs.mms.model.FacilityDraft;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Facility registry queries. Counters are computed with correlated subqueries
 * so the list needs a single round trip per refresh.
 */
public class FacilityDao {

    private static final String BASE_SELECT = """
            SELECT f.facility_id, f.facility_code, f.name, f.building, f.floor,
                   f.room_number, f.facility_type, f.condition_status, f.notes,
                   f.is_active,
                   (SELECT COUNT(*) FROM equipment e
                     WHERE e.facility_id = f.facility_id AND e.is_active = 1)
                       AS equipment_count,
                   (SELECT COUNT(*) FROM maintenance_requests r
                     WHERE r.facility_id = f.facility_id
                       AND r.status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD'))
                       AS open_requests
            FROM facilities f
            WHERE 1 = 1
            """;

    /**
     * @param keyword        matched against code, name, building, room, type
     * @param condition      exact condition, or null for any
     * @param includeArchived false hides facilities where is_active = 0
     */
    public List<Facility> search(String keyword, String condition,
                                 boolean includeArchived) throws SQLException {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> params = new ArrayList<>();

        if (!includeArchived) {
            sql.append(" AND f.is_active = 1");
        }
        if (condition != null && !condition.isBlank()) {
            sql.append(" AND f.condition_status = ?");
            params.add(condition);
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            sql.append("""
                     AND (f.facility_code LIKE ?
                       OR f.name LIKE ?
                       OR f.building LIKE ?
                       OR f.room_number LIKE ?
                       OR f.facility_type LIKE ?)
                    """);
            for (int i = 0; i < 5; i++) {
                params.add(like);
            }
        }

        sql.append(" ORDER BY f.is_active DESC, f.facility_code");

        List<Facility> rows = new ArrayList<>();
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

    private Facility map(ResultSet rs) throws SQLException {
        return new Facility(
                rs.getInt("facility_id"),
                rs.getString("facility_code"),
                rs.getString("name"),
                rs.getString("building"),
                rs.getString("floor"),
                rs.getString("room_number"),
                rs.getString("facility_type"),
                rs.getString("condition_status"),
                rs.getString("notes"),
                rs.getInt("is_active") == 1,
                rs.getInt("equipment_count"),
                rs.getInt("open_requests")
        );
    }

    /**
     * True when the code is already used by a different facility. The caller
     * passes 0 (or the row being edited) as {@code excludeId}.
     */
    public boolean existsCode(String code, int excludeId) throws SQLException {
        String sql = "SELECT 1 FROM facilities WHERE facility_code = ? AND facility_id <> ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setInt(2, excludeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public int create(FacilityDraft draft) throws SQLException {
        String sql = """
                INSERT INTO facilities
                    (facility_code, name, building, floor, room_number,
                     facility_type, condition_status, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
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

    public void update(int id, FacilityDraft draft) throws SQLException {
        String sql = """
                UPDATE facilities
                   SET facility_code = ?, name = ?, building = ?, floor = ?,
                       room_number = ?, facility_type = ?, condition_status = ?,
                       notes = ?
                 WHERE facility_id = ?
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int next = bind(ps, draft, 1);
            ps.setInt(next, id);
            ps.executeUpdate();
        }
    }

    public void setActive(int id, boolean active) throws SQLException {
        String sql = "UPDATE facilities SET is_active = ? WHERE facility_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, active ? 1 : 0);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    /** Number of active equipment items installed in a facility. */
    public int equipmentCount(int facilityId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM equipment WHERE facility_id = ? AND is_active = 1";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, facilityId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static int bind(PreparedStatement ps, FacilityDraft d, int start)
            throws SQLException {
        int i = start;
        ps.setString(i++, d.code());
        ps.setString(i++, d.name());
        ps.setString(i++, d.building());
        ps.setString(i++, d.floor());
        ps.setString(i++, d.roomNumber());
        ps.setString(i++, d.type());
        ps.setString(i++, d.condition());
        ps.setString(i++, d.notes());
        return i;
    }
}
