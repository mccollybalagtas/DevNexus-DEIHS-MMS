package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.RequestRow;
import com.deihs.mms.model.WorkloadSummary;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class WorkloadDao {

    public List<WorkloadSummary> workloadSummary(Integer assignedTo) throws SQLException {
        String sql = """
                SELECT u.user_id,
                       CONCAT(u.first_name, ' ', u.last_name) AS name,
                       SUM(CASE WHEN r.status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD') THEN 1 ELSE 0 END) AS open_requests,
                       SUM(CASE WHEN r.status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')
                                 AND r.target_date IS NOT NULL AND r.target_date < CURDATE() THEN 1 ELSE 0 END) AS overdue,
                       SUM(CASE WHEN r.status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')
                                 AND r.target_date = CURDATE() THEN 1 ELSE 0 END) AS due_today,
                       SUM(CASE WHEN r.status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')
                                 AND r.target_date BETWEEN CURDATE() AND DATE_ADD(CURDATE(), INTERVAL 7 DAY) THEN 1 ELSE 0 END) AS due_soon,
                       SUM(CASE WHEN r.status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')
                                 AND ((r.sla_breached_at IS NOT NULL) OR (r.sla_due_at IS NOT NULL AND r.sla_due_at < NOW())) THEN 1 ELSE 0 END) AS sla_breached
                FROM users u
                LEFT JOIN maintenance_requests r ON r.assigned_to = u.user_id
                WHERE u.role = 'PERSONNEL' AND u.is_active = 1
                """ + (assignedTo == null ? "" : " AND u.user_id = ? ") + """
                GROUP BY u.user_id, u.first_name, u.last_name
                ORDER BY open_requests DESC, overdue DESC, name
                """;
        List<WorkloadSummary> out = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (assignedTo != null) ps.setInt(1, assignedTo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new WorkloadSummary(
                            rs.getInt("user_id"), rs.getString("name"),
                            rs.getInt("open_requests"), rs.getInt("overdue"),
                            rs.getInt("due_today"), rs.getInt("due_soon"), rs.getInt("sla_breached")));
                }
            }
        }
        return out;
    }

    public List<RequestRow> agenda(LocalDate from, LocalDate to, Integer assignedTo) throws SQLException {
        StringBuilder sql = new StringBuilder("""
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
                WHERE r.status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')
                """);
        List<Object> params = new ArrayList<>();
        if (assignedTo != null) { sql.append(" AND r.assigned_to = ?"); params.add(assignedTo); }
        if (from != null) { sql.append(" AND (r.target_date >= ? OR DATE(r.sla_due_at) >= ?)"); params.add(Date.valueOf(from)); params.add(Date.valueOf(from)); }
        if (to != null) { sql.append(" AND (r.target_date <= ? OR DATE(r.sla_due_at) <= ?)"); params.add(Date.valueOf(to)); params.add(Date.valueOf(to)); }
        sql.append(" ORDER BY COALESCE(r.target_date, DATE(r.sla_due_at), '9999-12-31'), r.priority='CRITICAL' DESC, r.reported_at DESC");
        List<RequestRow> out = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp rep = rs.getTimestamp("reported_at");
                    Date td = rs.getDate("target_date");
                    Timestamp sla = rs.getTimestamp("sla_due_at");
                    out.add(new RequestRow(rs.getInt("request_id"), rs.getString("ticket_no"),
                            rs.getString("requester_name"), rs.getString("asset_name"), rs.getString("title"),
                            rs.getString("priority"), rs.getString("status"), rs.getString("assignee_name"),
                            rep == null ? null : rep.toLocalDateTime(), td == null ? null : td.toLocalDate(),
                            rs.getInt("is_overdue") == 1, sla == null ? null : sla.toLocalDateTime(),
                            rs.getInt("is_sla_breached") == 1));
                }
            }
        }
        return out;
    }
}
