package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.AttachmentDraft;
import com.deihs.mms.model.CreatedRequest;
import com.deihs.mms.model.RequestDraft;
import com.deihs.mms.model.RequestRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Request queries. The list can be narrowed by keyword, status, priority,
 * and by owner (assigned personnel or the staff member who reported it).
 */
public class RequestDao {

    private static final String ACTIVE = "('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')";

    private static final String BASE_SELECT = """
            SELECT r.request_id, r.ticket_no, r.title, r.priority, r.status,
                   r.reported_at, r.target_date,
                   CONCAT(req.first_name, ' ', req.last_name) AS requester_name,
                   COALESCE(f.name, e.name, '—') AS asset_name,
                   CASE WHEN asg.user_id IS NULL THEN NULL
                        ELSE CONCAT(asg.first_name, ' ', asg.last_name) END AS assignee_name,
                   CASE WHEN r.status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')
                             AND r.target_date IS NOT NULL
                             AND r.target_date < CURDATE()
                        THEN 1 ELSE 0 END AS is_overdue,
                   r.sla_due_at,
                   CASE WHEN r.sla_breached_at IS NOT NULL THEN 1
                        WHEN r.sla_due_at IS NOT NULL
                         AND r.status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')
                         AND r.sla_due_at < NOW() THEN 1
                        ELSE 0 END AS is_sla_breached
            FROM maintenance_requests r
            JOIN users req ON req.user_id = r.requester_id
            LEFT JOIN users asg ON asg.user_id = r.assigned_to
            LEFT JOIN facilities f ON f.facility_id = r.facility_id
            LEFT JOIN equipment e ON e.equipment_id = r.equipment_id
            WHERE 1 = 1
            """;

    /**
     * @param keyword     matched against ticket, issue, requester, facility, equipment
     * @param status      exact status, or null for any
     * @param priority    exact priority, or null for any
     * @param assignedTo  only this person's tasks, or null
     * @param requesterId only requests filed by this person, or null
     * @param orderByDue  true for the personnel task list (soonest due first)
     */
    public List<RequestRow> search(String keyword, String status, String priority,
                                   Integer assignedTo, Integer requesterId,
                                   boolean orderByDue) throws SQLException {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> params = new ArrayList<>();

        if (assignedTo != null) {
            sql.append(" AND r.assigned_to = ?");
            params.add(assignedTo);
        }
        if (requesterId != null) {
            sql.append(" AND r.requester_id = ?");
            params.add(requesterId);
        }
        if (status != null && !status.isBlank()) {
            if ("OVERDUE".equalsIgnoreCase(status)) {
                sql.append(" AND r.status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')")
                   .append(" AND r.target_date IS NOT NULL AND r.target_date < CURDATE()");
            } else {
                sql.append(" AND r.status = ?");
                params.add(status);
            }
        }
        if (priority != null && !priority.isBlank()) {
            sql.append(" AND r.priority = ?");
            params.add(priority);
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            sql.append("""
                     AND (r.ticket_no LIKE ?
                       OR r.title LIKE ?
                       OR CONCAT(req.first_name, ' ', req.last_name) LIKE ?
                       OR f.name LIKE ?
                       OR e.name LIKE ?)
                    """);
            for (int i = 0; i < 5; i++) {
                params.add(like);
            }
        }

        if (orderByDue) {
            sql.append(" ORDER BY (r.target_date IS NULL), r.target_date, r.reported_at DESC");
        } else {
            sql.append(" ORDER BY r.reported_at DESC, r.request_id DESC");
        }

        List<RequestRow> rows = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new RequestRow(
                            rs.getInt("request_id"),
                            rs.getString("ticket_no"),
                            rs.getString("requester_name"),
                            rs.getString("asset_name"),
                            rs.getString("title"),
                            rs.getString("priority"),
                            rs.getString("status"),
                            rs.getString("assignee_name"),
                            toLocal(rs.getTimestamp("reported_at")),
                            toLocalDate(rs.getDate("target_date")),
                            rs.getInt("is_overdue") == 1,
                            toLocal(rs.getTimestamp("sla_due_at")),
                            rs.getInt("is_sla_breached") == 1
                    ));
                }
            }
        }
        return rows;
    }

    /** Open requests only, used by the dashboard "View all" shortcut. */
    public int countActive(Integer assignedTo, Integer requesterId) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM maintenance_requests WHERE status IN " + ACTIVE);
        List<Object> params = new ArrayList<>();
        if (assignedTo != null) {
            sql.append(" AND assigned_to = ?");
            params.add(assignedTo);
        }
        if (requesterId != null) {
            sql.append(" AND requester_id = ?");
            params.add(requesterId);
        }
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Inserts a request, its first history entry, and an optional photo in a
     * single transaction. The ticket number is derived from the generated id,
     * so two PCs submitting at the same moment can never collide.
     */
    public CreatedRequest create(RequestDraft draft, int requesterId,
                                 AttachmentDraft attachment) throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                int requestId = insertRequest(c, draft, requesterId);
                String ticketNo = String.format("T-%04d", requestId);

                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE maintenance_requests SET ticket_no = ? WHERE request_id = ?")) {
                    ps.setString(1, ticketNo);
                    ps.setInt(2, requestId);
                    ps.executeUpdate();
                }

                insertHistory(c, requestId, requesterId);
                if (attachment != null) {
                    insertAttachment(c, requestId, attachment, requesterId);
                }
                applySlaDueAt(c, requestId, draft.priority());

                c.commit();
                return new CreatedRequest(requestId, ticketNo);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private int insertRequest(Connection c, RequestDraft draft, int requesterId)
            throws SQLException {
        String sql = """
                INSERT INTO maintenance_requests
                    (ticket_no, requester_id, facility_id, equipment_id, category_id,
                     title, description, priority, status, reported_at, target_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', NOW(), ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            // Placeholder must fit ticket_no VARCHAR(20); the real number is set below.
            ps.setString(1, "TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 15));
            ps.setInt(2, requesterId);
            setNullableInt(ps, 3, draft.facilityId());
            setNullableInt(ps, 4, draft.equipmentId());
            ps.setInt(5, draft.categoryId());
            ps.setString(6, draft.title().trim());
            ps.setString(7, draft.description() == null ? null : draft.description().trim());
            ps.setString(8, draft.priority());
            if (draft.targetDate() == null) {
                ps.setNull(9, Types.DATE);
            } else {
                ps.setDate(9, java.sql.Date.valueOf(draft.targetDate()));
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The database did not return a request id.");
                }
                return keys.getInt(1);
            }
        }
    }


    private void applySlaDueAt(Connection c, int requestId, String priority) throws SQLException {
        String sql = """
                UPDATE maintenance_requests r
                SET r.sla_due_at = DATE_ADD(r.reported_at, INTERVAL (
                    SELECT p.target_hours FROM sla_policies p
                    WHERE p.priority = ? AND p.is_active = 1
                    ORDER BY p.policy_id LIMIT 1
                ) HOUR)
                WHERE r.request_id = ?
                  AND EXISTS (
                    SELECT 1 FROM sla_policies p2
                    WHERE p2.priority = ? AND p2.is_active = 1
                  )
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, priority);
            ps.setInt(2, requestId);
            ps.setString(3, priority);
            ps.executeUpdate();
        }
    }
    private void insertHistory(Connection c, int requestId, int userId) throws SQLException {
        String sql = """
                INSERT INTO request_status_history
                    (request_id, from_status, to_status, changed_by, remarks, changed_at)
                VALUES (?, NULL, 'PENDING', ?, 'Request submitted', NOW())
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    private void insertAttachment(Connection c, int requestId, AttachmentDraft a, int userId)
            throws SQLException {
        String sql = """
                INSERT INTO attachments
                    (request_id, file_name, file_path, file_size, mime_type, uploaded_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            ps.setString(2, a.fileName());
            ps.setString(3, a.filePath());
            ps.setLong(4, a.fileSize());
            ps.setString(5, a.mimeType());
            ps.setInt(6, userId);
            ps.executeUpdate();
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static LocalDateTime toLocal(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static LocalDate toLocalDate(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
