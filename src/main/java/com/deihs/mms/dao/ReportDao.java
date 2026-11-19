package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.ReportSummary;
import com.deihs.mms.model.ReportSummary.CountRow;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated maintenance reporting queries over a date range (reported_at).
 */
public class ReportDao {

    public ReportSummary summarize(LocalDate from, LocalDate to) throws SQLException {
        LocalDate start = from == null ? LocalDate.of(2000, 1, 1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;

        try (Connection c = Database.getConnection()) {
            int total = scalarInt(c,
                    "SELECT COUNT(*) FROM maintenance_requests WHERE DATE(reported_at) BETWEEN ? AND ?",
                    start, end);
            int open = scalarInt(c,
                    "SELECT COUNT(*) FROM maintenance_requests WHERE DATE(reported_at) BETWEEN ? AND ? " +
                            "AND status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')",
                    start, end);
            int completed = scalarInt(c,
                    "SELECT COUNT(*) FROM maintenance_requests WHERE DATE(reported_at) BETWEEN ? AND ? " +
                            "AND status IN ('COMPLETED','CLOSED')",
                    start, end);
            int overdue = scalarInt(c,
                    "SELECT COUNT(*) FROM maintenance_requests WHERE DATE(reported_at) BETWEEN ? AND ? " +
                            "AND status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD') " +
                            "AND target_date IS NOT NULL AND target_date < CURDATE()",
                    start, end);

            BigDecimal totalCost = scalarDecimal(c,
                    "SELECT COALESCE(SUM(mr.cost),0) FROM maintenance_records mr " +
                            "JOIN maintenance_requests r ON r.request_id = mr.request_id " +
                            "WHERE DATE(r.reported_at) BETWEEN ? AND ?",
                    start, end);
            BigDecimal downtime = scalarDecimal(c,
                    "SELECT COALESCE(SUM(mr.downtime_hours),0) FROM maintenance_records mr " +
                            "JOIN maintenance_requests r ON r.request_id = mr.request_id " +
                            "WHERE DATE(r.reported_at) BETWEEN ? AND ?",
                    start, end);

            List<CountRow> byStatus = grouped(c,
                    "SELECT status AS label, COUNT(*) AS cnt FROM maintenance_requests " +
                            "WHERE DATE(reported_at) BETWEEN ? AND ? GROUP BY status ORDER BY cnt DESC",
                    start, end);
            List<CountRow> byPriority = grouped(c,
                    "SELECT priority AS label, COUNT(*) AS cnt FROM maintenance_requests " +
                            "WHERE DATE(reported_at) BETWEEN ? AND ? GROUP BY priority " +
                            "ORDER BY FIELD(priority,'CRITICAL','HIGH','MEDIUM','LOW')",
                    start, end);
            List<CountRow> byCategory = grouped(c,
                    "SELECT c.name AS label, COUNT(*) AS cnt FROM maintenance_requests r " +
                            "JOIN maintenance_categories c ON c.category_id = r.category_id " +
                            "WHERE DATE(r.reported_at) BETWEEN ? AND ? GROUP BY c.category_id, c.name ORDER BY cnt DESC",
                    start, end);
            List<CountRow> byFacility = grouped(c,
                    "SELECT COALESCE(f.name,'Unspecified') AS label, COUNT(*) AS cnt FROM maintenance_requests r " +
                            "LEFT JOIN facilities f ON f.facility_id = r.facility_id " +
                            "WHERE DATE(r.reported_at) BETWEEN ? AND ? GROUP BY label ORDER BY cnt DESC LIMIT 10",
                    start, end);

            return new ReportSummary(start, end, total, open, completed, overdue,
                    totalCost, downtime == null ? 0.0 : downtime.doubleValue(),
                    byStatus, byPriority, byCategory, byFacility);
        }
    }

    private int scalarInt(Connection c, String sql, LocalDate from, LocalDate to) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(from));
            ps.setDate(2, Date.valueOf(to));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private BigDecimal scalarDecimal(Connection c, String sql, LocalDate from, LocalDate to) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(from));
            ps.setDate(2, Date.valueOf(to));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal v = rs.getBigDecimal(1);
                    return v == null ? BigDecimal.ZERO : v;
                }
                return BigDecimal.ZERO;
            }
        }
    }

    private List<CountRow> grouped(Connection c, String sql, LocalDate from, LocalDate to) throws SQLException {
        List<CountRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(from));
            ps.setDate(2, Date.valueOf(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new CountRow(rs.getString("label"), rs.getInt("cnt")));
                }
            }
        }
        return rows;
    }
}
