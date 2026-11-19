package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.AttachmentEntry;
import com.deihs.mms.model.CommentEntry;
import com.deihs.mms.model.HistoryEntry;
import com.deihs.mms.model.RequestDetail;

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

/**
 * Reads and writes everything behind one request's detail screen.
 *
 * <p>Status changes lock the request row first and refuse to write when the
 * status is not the one the caller last saw. Two people on different PCs can
 * therefore not silently overwrite each other's update.</p>
 */
public class RequestDetailDao {

    private static final String DETAIL_SELECT = """
            SELECT r.request_id, r.ticket_no, r.title, r.description,
                   r.priority, r.status,
                   r.requester_id,
                   CONCAT(req.first_name, ' ', req.last_name) AS requester_name,
                   r.assigned_to,
                   CASE WHEN asg.user_id IS NULL THEN NULL
                        ELSE CONCAT(asg.first_name, ' ', asg.last_name) END AS assignee_name,
                   r.category_id,
                   cat.name AS category_name,
                   r.facility_id,
                   f.name AS facility_name,
                   r.equipment_id,
                   e.name AS equipment_name,
                   r.reported_at, r.target_date, r.completed_at, r.closed_at,
                   r.resolution_notes,
                   CASE WHEN r.status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')
                             AND r.target_date IS NOT NULL
                             AND r.target_date < CURDATE()
                        THEN 1 ELSE 0 END AS is_overdue
            FROM maintenance_requests r
            JOIN users req ON req.user_id = r.requester_id
            LEFT JOIN users asg ON asg.user_id = r.assigned_to
            JOIN maintenance_categories cat ON cat.category_id = r.category_id
            LEFT JOIN facilities f ON f.facility_id = r.facility_id
            LEFT JOIN equipment e ON e.equipment_id = r.equipment_id
            WHERE r.request_id = ?
            """;

    public RequestDetail load(int requestId) throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(DETAIL_SELECT)) {
            ps.setInt(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new RequestDetail(
                        rs.getInt("request_id"),
                        rs.getString("ticket_no"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("priority"),
                        rs.getString("status"),
                        rs.getInt("requester_id"),
                        rs.getString("requester_name"),
                        nullableInt(rs, "assigned_to"),
                        rs.getString("assignee_name"),
                        rs.getInt("category_id"),
                        rs.getString("category_name"),
                        nullableInt(rs, "facility_id"),
                        rs.getString("facility_name"),
                        nullableInt(rs, "equipment_id"),
                        rs.getString("equipment_name"),
                        toLocal(rs.getTimestamp("reported_at")),
                        toLocalDate(rs.getDate("target_date")),
                        toLocal(rs.getTimestamp("completed_at")),
                        toLocal(rs.getTimestamp("closed_at")),
                        rs.getString("resolution_notes"),
                        rs.getInt("is_overdue") == 1
                );
            }
        }
    }

    public List<HistoryEntry> history(int requestId) throws SQLException {
        String sql = """
                SELECT h.history_id, h.from_status, h.to_status, h.remarks, h.changed_at,
                       CONCAT(u.first_name, ' ', u.last_name) AS changed_by_name
                FROM request_status_history h
                JOIN users u ON u.user_id = h.changed_by
                WHERE h.request_id = ?
                ORDER BY h.changed_at, h.history_id
                """;
        List<HistoryEntry> entries = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new HistoryEntry(
                            rs.getInt("history_id"),
                            rs.getString("from_status"),
                            rs.getString("to_status"),
                            rs.getString("changed_by_name"),
                            toLocal(rs.getTimestamp("changed_at")),
                            rs.getString("remarks")
                    ));
                }
            }
        }
        return entries;
    }

    public List<CommentEntry> comments(int requestId) throws SQLException {
        String sql = """
                SELECT c.comment_id, c.user_id, c.comment_text, c.created_at,
                       CONCAT(u.first_name, ' ', u.last_name) AS author_name
                FROM request_comments c
                JOIN users u ON u.user_id = c.user_id
                WHERE c.request_id = ?
                ORDER BY c.created_at, c.comment_id
                """;
        List<CommentEntry> entries = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new CommentEntry(
                            rs.getInt("comment_id"),
                            rs.getInt("user_id"),
                            rs.getString("author_name"),
                            rs.getString("comment_text"),
                            toLocal(rs.getTimestamp("created_at"))
                    ));
                }
            }
        }
        return entries;
    }

    public List<AttachmentEntry> attachments(int requestId) throws SQLException {
        String sql = """
                SELECT a.attachment_id, a.file_name, a.file_path, a.file_size,
                       a.mime_type, a.uploaded_at,
                       CONCAT(u.first_name, ' ', u.last_name) AS uploaded_by_name
                FROM attachments a
                JOIN users u ON u.user_id = a.uploaded_by
                WHERE a.request_id = ?
                ORDER BY a.uploaded_at, a.attachment_id
                """;
        List<AttachmentEntry> entries = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new AttachmentEntry(
                            rs.getInt("attachment_id"),
                            rs.getString("file_name"),
                            rs.getString("file_path"),
                            rs.getLong("file_size"),
                            rs.getString("mime_type"),
                            rs.getString("uploaded_by_name"),
                            toLocal(rs.getTimestamp("uploaded_at"))
                    ));
                }
            }
        }
        return entries;
    }

    /**
     * Assigns (or reassigns) a request. Only a PENDING or ASSIGNED request can
     * be assigned; the operation also moves PENDING requests to ASSIGNED.
     *
     * @param expectedStatus the status the caller was looking at
     * @return false when the row is gone or changed underneath the caller
     */
    public boolean assign(int requestId, int assigneeId, int actorId,
                          String expectedStatus, String remarks) throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                String current = lockStatus(c, requestId);
                if (current == null || !current.equals(expectedStatus)
                        || !("PENDING".equals(current) || "ASSIGNED".equals(current))) {
                    c.rollback();
                    return false;
                }

                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE maintenance_requests
                        SET assigned_to = ?, status = 'ASSIGNED'
                        WHERE request_id = ?
                        """)) {
                    ps.setInt(1, assigneeId);
                    ps.setInt(2, requestId);
                    ps.executeUpdate();
                }

                insertHistory(c, requestId, current, "ASSIGNED", actorId, remarks);
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /**
     * Moves a request to another status, stamping completion and closure
     * times and keeping the resolution notes on completion.
     *
     * @param expectedStatus the status the caller was looking at
     * @return false when the row is gone or changed underneath the caller
     */
    public boolean changeStatus(int requestId, int actorId, String expectedStatus,
                                String toStatus, String remarks) throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                String current = lockStatus(c, requestId);
                if (current == null || !current.equals(expectedStatus)) {
                    c.rollback();
                    return false;
                }

                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE maintenance_requests
                        SET status = ?,
                            completed_at = CASE
                                WHEN ? = 'COMPLETED' AND completed_at IS NULL
                                THEN NOW() ELSE completed_at END,
                            closed_at = CASE
                                WHEN ? = 'CLOSED' AND closed_at IS NULL
                                THEN NOW() ELSE closed_at END,
                            resolution_notes = CASE
                                WHEN ? = 'COMPLETED' AND ? IS NOT NULL
                                THEN ? ELSE resolution_notes END
                        WHERE request_id = ?
                        """)) {
                    ps.setString(1, toStatus);
                    ps.setString(2, toStatus);
                    ps.setString(3, toStatus);
                    ps.setString(4, toStatus);
                    ps.setString(5, remarks);
                    ps.setString(6, remarks);
                    ps.setInt(7, requestId);
                    ps.executeUpdate();
                }

                insertHistory(c, requestId, current, toStatus, actorId, remarks);
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** Inserts a comment and returns it with the author's name resolved. */
    public CommentEntry addComment(int requestId, int userId, String text) throws SQLException {
        try (Connection c = Database.getConnection()) {
            int commentId;
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO request_comments (request_id, user_id, comment_text)
                    VALUES (?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, requestId);
                ps.setInt(2, userId);
                ps.setString(3, text);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("The database did not return a comment id.");
                    }
                    commentId = keys.getInt(1);
                }
            }

            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT c.comment_id, c.user_id, c.comment_text, c.created_at,
                           CONCAT(u.first_name, ' ', u.last_name) AS author_name
                    FROM request_comments c
                    JOIN users u ON u.user_id = c.user_id
                    WHERE c.comment_id = ?
                    """)) {
                ps.setInt(1, commentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("The saved comment could not be read back.");
                    }
                    return new CommentEntry(
                            rs.getInt("comment_id"),
                            rs.getInt("user_id"),
                            rs.getString("author_name"),
                            rs.getString("comment_text"),
                            toLocal(rs.getTimestamp("created_at"))
                    );
                }
            }
        }
    }

    private static String lockStatus(Connection c, int requestId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT status FROM maintenance_requests WHERE request_id = ? FOR UPDATE")) {
            ps.setInt(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("status") : null;
            }
        }
    }

    private static void insertHistory(Connection c, int requestId, String fromStatus,
                                      String toStatus, int actorId, String remarks)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO request_status_history
                    (request_id, from_status, to_status, changed_by, remarks, changed_at)
                VALUES (?, ?, ?, ?, ?, NOW())
                """)) {
            ps.setInt(1, requestId);
            ps.setString(2, fromStatus);
            ps.setString(3, toStatus);
            ps.setInt(4, actorId);
            if (remarks == null || remarks.isBlank()) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, remarks);
            }
            ps.executeUpdate();
        }
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime toLocal(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static LocalDate toLocalDate(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
