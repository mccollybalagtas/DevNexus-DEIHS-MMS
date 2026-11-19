package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.DashboardData;
import com.deihs.mms.model.DashboardData.MonthPoint;
import com.deihs.mms.model.DashboardData.StatusSlice;
import com.deihs.mms.model.RequestRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only aggregate queries for the dashboard.
 *
 * <p>When {@code assignedTo} is not null every figure is scoped to that
 * person's tasks, which is what maintenance personnel see.</p>
 */
public class DashboardDao {

    private static final String ACTIVE = "('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')";

    private static final String C_IN_PROGRESS = "#1A47B8";
    private static final String C_COMPLETED = "#2E9E5B";
    private static final String C_PENDING = "#E8A200";
    private static final String C_OTHER = "#9AA6B2";

    public DashboardData load(Integer assignedTo) throws SQLException {
        try (Connection c = Database.getConnection()) {
            int pending = countStatuses(c, assignedTo, "PENDING");
            int newToday = countNewToday(c, assignedTo);
            int inProgress = countStatuses(c, assignedTo, "ASSIGNED", "IN_PROGRESS");
            int dueThisWeek = countDueThisWeek(c, assignedTo);
            int overdue = countOverdue(c, assignedTo);
            int completed = countCompletedThisMonth(c, assignedTo);

            Map<String, Integer> byStatus = countsByStatus(c, assignedTo);
            int total = byStatus.values().stream().mapToInt(Integer::intValue).sum();

            List<StatusSlice> slices = List.of(
                    new StatusSlice("In Progress", sum(byStatus, "ASSIGNED", "IN_PROGRESS"), C_IN_PROGRESS),
                    new StatusSlice("Completed", sum(byStatus, "COMPLETED", "CLOSED"), C_COMPLETED),
                    new StatusSlice("Pending", sum(byStatus, "PENDING"), C_PENDING),
                    new StatusSlice("On Hold / Other", sum(byStatus, "ON_HOLD", "REJECTED", "CANCELLED"), C_OTHER)
            );

            return new DashboardData(
                    pending, newToday,
                    inProgress, dueThisWeek,
                    overdue,
                    completed,
                    total,
                    requestsPerMonth(c, assignedTo, 6),
                    slices,
                    recentRequests(c, assignedTo, 6)
            );
        }
    }

    // ------------------------------------------------------------------
    // Counters
    // ------------------------------------------------------------------

    private int countStatuses(Connection c, Integer assignedTo, String... statuses) throws SQLException {
        String in = placeholders(statuses.length);
        String sql = "SELECT COUNT(*) FROM maintenance_requests WHERE status IN (" + in + ")"
                + scope(assignedTo);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (String s : statuses) {
                ps.setString(i++, s);
            }
            bindScope(ps, i, assignedTo);
            return scalar(ps);
        }
    }

    private int countNewToday(Connection c, Integer assignedTo) throws SQLException {
        String sql = "SELECT COUNT(*) FROM maintenance_requests"
                + " WHERE status = 'PENDING' AND DATE(reported_at) = CURDATE()"
                + scope(assignedTo);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bindScope(ps, 1, assignedTo);
            return scalar(ps);
        }
    }

    private int countDueThisWeek(Connection c, Integer assignedTo) throws SQLException {
        String sql = "SELECT COUNT(*) FROM maintenance_requests"
                + " WHERE status IN " + ACTIVE
                + " AND target_date IS NOT NULL"
                + " AND target_date BETWEEN CURDATE() AND DATE_ADD(CURDATE(), INTERVAL 7 DAY)"
                + scope(assignedTo);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bindScope(ps, 1, assignedTo);
            return scalar(ps);
        }
    }

    private int countOverdue(Connection c, Integer assignedTo) throws SQLException {
        String sql = "SELECT COUNT(*) FROM maintenance_requests"
                + " WHERE status IN " + ACTIVE
                + " AND target_date IS NOT NULL AND target_date < CURDATE()"
                + scope(assignedTo);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bindScope(ps, 1, assignedTo);
            return scalar(ps);
        }
    }

    private int countCompletedThisMonth(Connection c, Integer assignedTo) throws SQLException {
        String sql = "SELECT COUNT(*) FROM maintenance_requests"
                + " WHERE status IN ('COMPLETED','CLOSED')"
                + " AND completed_at IS NOT NULL"
                + " AND YEAR(completed_at) = YEAR(CURDATE())"
                + " AND MONTH(completed_at) = MONTH(CURDATE())"
                + scope(assignedTo);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bindScope(ps, 1, assignedTo);
            return scalar(ps);
        }
    }

    private Map<String, Integer> countsByStatus(Connection c, Integer assignedTo) throws SQLException {
        String sql = "SELECT status, COUNT(*) AS c FROM maintenance_requests WHERE 1 = 1"
                + scope(assignedTo)
                + " GROUP BY status";
        Map<String, Integer> map = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bindScope(ps, 1, assignedTo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("status"), rs.getInt("c"));
                }
            }
        }
        return map;
    }

    // ------------------------------------------------------------------
    // Charts
    // ------------------------------------------------------------------

    private List<MonthPoint> requestsPerMonth(Connection c, Integer assignedTo, int months)
            throws SQLException {
        YearMonth current = YearMonth.now();
        YearMonth first = current.minusMonths(months - 1L);
        LocalDate from = first.atDay(1);

        String sql = "SELECT DATE_FORMAT(reported_at, '%Y-%m') AS ym, COUNT(*) AS c"
                + " FROM maintenance_requests WHERE reported_at >= ?"
                + scope(assignedTo)
                + " GROUP BY ym";
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, java.sql.Date.valueOf(from));
            bindScope(ps, 2, assignedTo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("ym"), rs.getInt("c"));
                }
            }
        }

        List<MonthPoint> points = new ArrayList<>(months);
        for (int i = months - 1; i >= 0; i--) {
            YearMonth ym = current.minusMonths(i);
            String key = String.format("%04d-%02d", ym.getYear(), ym.getMonthValue());
            String label = ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            points.add(new MonthPoint(label, counts.getOrDefault(key, 0)));
        }
        return points;
    }

    // ------------------------------------------------------------------
    // Recent requests
    // ------------------------------------------------------------------

    private List<RequestRow> recentRequests(Connection c, Integer assignedTo, int limit)
            throws SQLException {
        String sql = "SELECT r.request_id, r.ticket_no, r.title, r.priority, r.status, r.reported_at,"
                + " CONCAT(req.first_name, ' ', req.last_name) AS requester_name,"
                + " COALESCE(f.name, e.name, '—') AS asset_name,"
                + " CASE WHEN r.status IN " + ACTIVE
                + "      AND r.target_date IS NOT NULL AND r.target_date < CURDATE()"
                + "      THEN 1 ELSE 0 END AS is_overdue"
                + " FROM maintenance_requests r"
                + " JOIN users req ON req.user_id = r.requester_id"
                + " LEFT JOIN facilities f ON f.facility_id = r.facility_id"
                + " LEFT JOIN equipment e ON e.equipment_id = r.equipment_id"
                + (assignedTo == null ? "" : " WHERE r.assigned_to = ?")
                + " ORDER BY r.reported_at DESC, r.request_id DESC"
                + " LIMIT ?";

        List<RequestRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (assignedTo != null) {
                ps.setInt(i++, assignedTo);
            }
            ps.setInt(i, limit);
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
                            toLocal(rs.getTimestamp("reported_at")),
                            rs.getInt("is_overdue") == 1
                    ));
                }
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String scope(Integer assignedTo) {
        return assignedTo == null ? "" : " AND assigned_to = ?";
    }

    private static void bindScope(PreparedStatement ps, int index, Integer assignedTo)
            throws SQLException {
        if (assignedTo != null) {
            ps.setInt(index, assignedTo);
        }
    }

    private static int scalar(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static int sum(Map<String, Integer> map, String... keys) {
        int total = 0;
        for (String key : keys) {
            total += map.getOrDefault(key, 0);
        }
        return total;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static LocalDateTime toLocal(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
