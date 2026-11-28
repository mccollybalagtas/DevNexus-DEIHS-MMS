package com.deihs.mms.service;

import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.db.Database;
import com.deihs.mms.model.Role;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks SLA deadlines for active requests and escalates breached ones.
 * Escalation is idempotent: a request is escalated once (escalated_at).
 */
public class SlaService {

    public record Escalation(int requestId, String ticketNo, String title, String oldPriority, String newPriority) {}

    private final AuditDao auditDao = new AuditDao();
    private final NotificationService notifications = new NotificationService();

    /**
     * Marks overdue SLA requests as breached and escalates them once.
     * Returns escalations performed in this run.
     */
    public List<Escalation> checkAndEscalate() throws SQLException {
        List<Escalation> escalations = new ArrayList<>();
        String findSql = """
                SELECT request_id, ticket_no, title, priority, requester_id, assigned_to
                FROM maintenance_requests
                WHERE status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')
                  AND sla_due_at IS NOT NULL
                  AND sla_due_at < NOW()
                  AND escalated_at IS NULL
                ORDER BY sla_due_at ASC
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(findSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                escalations.add(new Escalation(
                        rs.getInt("request_id"),
                        rs.getString("ticket_no"),
                        rs.getString("title"),
                        rs.getString("priority"),
                        bumpPriority(rs.getString("priority"))
                ));
                // store ids for update; we'll handle row-by-row below
            }
        }

        if (escalations.isEmpty()) {
            // Still mark breached_at for any that passed due but were already escalated or missed.
            markBreachedOnly();
            return List.of();
        }

        // Re-query with ids for safe update; simpler to iterate and update individually.
        List<Escalation> performed = new ArrayList<>();
        for (Escalation e : escalations) {
            boolean ok = escalateOne(e);
            if (ok) performed.add(e);
        }
        markBreachedOnly();
        return performed;
    }

    private boolean escalateOne(Escalation e) throws SQLException {
        String sql = """
                UPDATE maintenance_requests
                SET sla_breached_at = COALESCE(sla_breached_at, NOW()),
                    escalated_at = NOW(),
                    priority = ?
                WHERE request_id = ?
                  AND escalated_at IS NULL
                  AND status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, e.newPriority());
            ps.setInt(2, e.requestId());
            int updated = ps.executeUpdate();
            if (updated == 0) return false;
        }
        auditDao.log(null, "SLA_ESCALATION", "maintenance_requests", e.ticketNo(),
                "SLA breached; priority " + e.oldPriority() + " -> " + e.newPriority(),
                "priority=" + e.oldPriority() + "; escalated_at=",
                "priority=" + e.newPriority() + "; escalated_at=" + Timestamp.valueOf(LocalDateTime.now()));
        notifications.notifyRoles(List.of(Role.ADMIN, Role.MAINT_HEAD),
                "SLA breached: " + e.ticketNo(),
                "Request \"" + e.title() + "\" breached its SLA and was escalated from "
                        + e.oldPriority() + " to " + e.newPriority() + ".",
                "request", e.requestId(), null);
        return true;
    }

    /** Mark breached_at for overdue active requests that missed escalation marking. */
    private void markBreachedOnly() throws SQLException {
        String sql = """
                UPDATE maintenance_requests
                SET sla_breached_at = NOW()
                WHERE status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')
                  AND sla_due_at IS NOT NULL
                  AND sla_due_at < NOW()
                  AND sla_breached_at IS NULL
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    public int countBreachedActive() throws SQLException {
        String sql = """
                SELECT COUNT(*) FROM maintenance_requests
                WHERE status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')
                  AND sla_due_at IS NOT NULL
                  AND sla_due_at < NOW()
                """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public static String bumpPriority(String priority) {
        if (priority == null) return "MEDIUM";
        return switch (priority.toUpperCase()) {
            case "LOW" -> "MEDIUM";
            case "MEDIUM" -> "HIGH";
            case "HIGH", "CRITICAL" -> "CRITICAL";
            default -> "HIGH";
        };
    }
}
