package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.LookupOption;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference lists for combo boxes: categories, facilities, and equipment.
 * All queries are read-only and return active records only.
 */
public class LookupDao {

    public List<LookupOption> categories() throws SQLException {
        String sql = "SELECT category_id, name FROM maintenance_categories"
                + " WHERE is_active = 1 ORDER BY name";
        List<LookupOption> list = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new LookupOption(rs.getInt("category_id"), rs.getString("name")));
            }
        }
        return list;
    }

    /** The default priority configured for a category, or MEDIUM. */
    public String defaultPriority(int categoryId) throws SQLException {
        String sql = "SELECT default_priority FROM maintenance_categories WHERE category_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "MEDIUM";
            }
        }
    }

    public List<LookupOption> facilities() throws SQLException {
        String sql = "SELECT facility_id, name, building FROM facilities"
                + " WHERE is_active = 1 ORDER BY name";
        List<LookupOption> list = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String building = rs.getString("building");
                String label = rs.getString("name")
                        + (building == null || building.isBlank() ? "" : " · " + building);
                list.add(new LookupOption(rs.getInt("facility_id"), label));
            }
        }
        return list;
    }

    /** Equipment installed in a facility, or the whole inventory when null. */
    public List<LookupOption> equipment(Integer facilityId) throws SQLException {
        String sql = "SELECT equipment_id, property_no, name FROM equipment"
                + " WHERE is_active = 1"
                + (facilityId == null ? "" : " AND facility_id = ?")
                + " ORDER BY name, property_no";
        List<LookupOption> list = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (facilityId != null) {
                ps.setInt(1, facilityId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new LookupOption(rs.getInt("equipment_id"),
                            rs.getString("name") + " · " + rs.getString("property_no")));
                }
            }
        }
        return list;
    }
}
