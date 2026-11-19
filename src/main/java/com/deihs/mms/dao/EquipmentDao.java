package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.Equipment;
import com.deihs.mms.model.EquipmentDraft;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Equipment inventory queries with joined facility name for display.
 */
public class EquipmentDao {

    private static final String BASE_SELECT = """
            SELECT e.equipment_id, e.property_no, e.name, e.category, e.brand, e.model,
                   e.serial_no, e.facility_id, f.name AS facility_name,
                   e.condition_status, e.acquisition_date, e.notes, e.is_active
            FROM equipment e
            LEFT JOIN facilities f ON f.facility_id = e.facility_id
            WHERE 1 = 1
            """;

    public List<Equipment> search(String keyword, String condition, Integer facilityId,
                                  boolean includeArchived) throws SQLException {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> params = new ArrayList<>();

        if (!includeArchived) {
            sql.append(" AND e.is_active = 1");
        }
        if (condition != null && !condition.isBlank()) {
            sql.append(" AND e.condition_status = ?");
            params.add(condition);
        }
        if (facilityId != null && facilityId > 0) {
            sql.append(" AND e.facility_id = ?");
            params.add(facilityId);
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            sql.append("""
                     AND (e.property_no LIKE ?
                       OR e.name LIKE ?
                       OR e.category LIKE ?
                       OR e.brand LIKE ?
                       OR e.model LIKE ?
                       OR e.serial_no LIKE ?
                       OR f.name LIKE ?)
                    """);
            for (int i = 0; i < 7; i++) params.add(like);
        }

        sql.append(" ORDER BY e.is_active DESC, e.property_no");

        List<Equipment> rows = new ArrayList<>();
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

    private Equipment map(ResultSet rs) throws SQLException {
        Date acq = rs.getDate("acquisition_date");
        int fid = rs.getInt("facility_id");
        Integer facilityId = rs.wasNull() ? null : fid;
        return new Equipment(
                rs.getInt("equipment_id"),
                rs.getString("property_no"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getString("brand"),
                rs.getString("model"),
                rs.getString("serial_no"),
                facilityId,
                rs.getString("facility_name"),
                rs.getString("condition_status"),
                acq == null ? null : acq.toLocalDate(),
                rs.getString("notes"),
                rs.getInt("is_active") == 1
        );
    }

    public boolean existsPropertyNo(String propertyNo, int excludeId) throws SQLException {
        String sql = "SELECT 1 FROM equipment WHERE property_no = ? AND equipment_id <> ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, propertyNo);
            ps.setInt(2, excludeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public int create(EquipmentDraft d) throws SQLException {
        String sql = """
                INSERT INTO equipment
                    (property_no, name, category, brand, model, serial_no,
                     facility_id, condition_status, acquisition_date, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, d, 1);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : 0;
            }
        }
    }

    public void update(int id, EquipmentDraft d) throws SQLException {
        String sql = """
                UPDATE equipment
                   SET property_no = ?, name = ?, category = ?, brand = ?, model = ?,
                       serial_no = ?, facility_id = ?, condition_status = ?,
                       acquisition_date = ?, notes = ?
                 WHERE equipment_id = ?
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int next = bind(ps, d, 1);
            ps.setInt(next, id);
            ps.executeUpdate();
        }
    }

    public void setActive(int id, boolean active) throws SQLException {
        String sql = "UPDATE equipment SET is_active = ? WHERE equipment_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, active ? 1 : 0);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    private static int bind(PreparedStatement ps, EquipmentDraft d, int start) throws SQLException {
        int i = start;
        ps.setString(i++, d.propertyNo());
        ps.setString(i++, d.name());
        ps.setString(i++, d.category());
        ps.setString(i++, d.brand());
        ps.setString(i++, d.model());
        ps.setString(i++, d.serialNo());
        if (d.facilityId() == null || d.facilityId() <= 0) ps.setNull(i++, Types.INTEGER);
        else ps.setInt(i++, d.facilityId());
        ps.setString(i++, d.condition());
        LocalDate acq = d.acquisitionDate();
        if (acq == null) ps.setNull(i++, Types.DATE);
        else ps.setDate(i++, Date.valueOf(acq));
        ps.setString(i++, d.notes());
        return i;
    }
}
