package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.Schedule;
import com.deihs.mms.model.ScheduleDraft;

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
 * Preventive maintenance schedule queries.
 */
public class ScheduleDao {

    private static final String BASE_SELECT = """
            SELECT s.schedule_id, s.facility_id, f.name AS facility_name,
                   s.equipment_id, e.name AS equipment_name,
                   s.title, s.description, s.frequency, s.next_due_date, s.last_done_date,
                   s.assigned_to, CONCAT(u.first_name, ' ', u.last_name) AS assigned_name,
                   s.is_active
            FROM maintenance_schedules s
            LEFT JOIN facilities f ON f.facility_id = s.facility_id
            LEFT JOIN equipment e ON e.equipment_id = s.equipment_id
            LEFT JOIN users u ON u.user_id = s.assigned_to
            WHERE 1 = 1
            """;

    public List<Schedule> search(String keyword, boolean includeInactive, Boolean dueOnly)
            throws SQLException {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> params = new ArrayList<>();

        if (!includeInactive) {
            sql.append(" AND s.is_active = 1");
        }
        if (Boolean.TRUE.equals(dueOnly)) {
            sql.append(" AND s.next_due_date <= CURDATE()");
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            sql.append("""
                     AND (s.title LIKE ?
                       OR s.description LIKE ?
                       OR f.name LIKE ?
                       OR e.name LIKE ?
                       OR CONCAT(u.first_name, ' ', u.last_name) LIKE ?)
                    """);
            for (int i = 0; i < 5; i++) params.add(like);
        }

        sql.append(" ORDER BY s.is_active DESC, s.next_due_date ASC, s.schedule_id DESC");

        List<Schedule> rows = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(map(rs));
            }
        }
        return rows;
    }

    private Schedule map(ResultSet rs) throws SQLException {
        Date next = rs.getDate("next_due_date");
        Date last = rs.getDate("last_done_date");
        int fid = rs.getInt("facility_id");
        Integer facilityId = rs.wasNull() ? null : fid;
        int eid = rs.getInt("equipment_id");
        Integer equipmentId = rs.wasNull() ? null : eid;
        int aid = rs.getInt("assigned_to");
        Integer assignedTo = rs.wasNull() ? null : aid;
        return new Schedule(
                rs.getInt("schedule_id"),
                facilityId,
                rs.getString("facility_name"),
                equipmentId,
                rs.getString("equipment_name"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("frequency"),
                next == null ? null : next.toLocalDate(),
                last == null ? null : last.toLocalDate(),
                assignedTo,
                rs.getString("assigned_name"),
                rs.getInt("is_active") == 1
        );
    }

    public int create(ScheduleDraft d, int createdBy) throws SQLException {
        String sql = """
                INSERT INTO maintenance_schedules
                    (facility_id, equipment_id, title, description, frequency,
                     next_due_date, assigned_to, is_active, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, d, 1);
            ps.setInt(8, createdBy);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : 0;
            }
        }
    }

    public void update(int id, ScheduleDraft d) throws SQLException {
        String sql = """
                UPDATE maintenance_schedules
                   SET facility_id = ?, equipment_id = ?, title = ?, description = ?,
                       frequency = ?, next_due_date = ?, assigned_to = ?
                 WHERE schedule_id = ?
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int next = bind(ps, d, 1);
            ps.setInt(next, id);
            ps.executeUpdate();
        }
    }

    public void setActive(int id, boolean active) throws SQLException {
        String sql = "UPDATE maintenance_schedules SET is_active = ? WHERE schedule_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, active ? 1 : 0);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public void markDone(int id, LocalDate doneDate, LocalDate nextDueDate) throws SQLException {
        String sql = """
                UPDATE maintenance_schedules
                   SET last_done_date = ?, next_due_date = ?
                 WHERE schedule_id = ?
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(doneDate));
            ps.setDate(2, Date.valueOf(nextDueDate));
            ps.setInt(3, id);
            ps.executeUpdate();
        }
    }

    /**
     * Active schedules that are due and either have no linked request or their
     * linked request is already terminal/cancelled/rejected - meaning a new
     * preventive work order should be created.
     */
    public List<Schedule> findDueForWorkOrderGeneration() throws SQLException {
        String sql = BASE_SELECT + """
                 AND s.is_active = 1
                 AND s.next_due_date <= CURDATE()
                 AND (
                      s.last_request_id IS NULL
                      OR EXISTS (
                          SELECT 1 FROM maintenance_requests mr
                           WHERE mr.request_id = s.last_request_id
                             AND mr.status IN ('COMPLETED','CLOSED','REJECTED','CANCELLED')
                      )
                 )
                 ORDER BY s.next_due_date ASC, s.schedule_id ASC
                """;
        List<Schedule> rows = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(map(rs));
            }
        }
        return rows;
    }

    public void setLastRequestId(int scheduleId, int requestId) throws SQLException {
        String sql = "UPDATE maintenance_schedules SET last_request_id = ? WHERE schedule_id = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            ps.setInt(2, scheduleId);
            ps.executeUpdate();
        }
    }

    private static int bind(PreparedStatement ps, ScheduleDraft d, int start) throws SQLException {
        int i = start;
        if (d.facilityId() == null || d.facilityId() <= 0) ps.setNull(i++, Types.INTEGER);
        else ps.setInt(i++, d.facilityId());
        if (d.equipmentId() == null || d.equipmentId() <= 0) ps.setNull(i++, Types.INTEGER);
        else ps.setInt(i++, d.equipmentId());
        ps.setString(i++, d.title());
        ps.setString(i++, d.description());
        ps.setString(i++, d.frequency());
        ps.setDate(i++, Date.valueOf(d.nextDueDate()));
        if (d.assignedTo() == null || d.assignedTo() <= 0) ps.setNull(i++, Types.INTEGER);
        else ps.setInt(i++, d.assignedTo());
        return i; // returns index after 7th param (assigned_to)
    }
}
