package com.deihs.mms.service;

import com.deihs.mms.dao.AppSettingsDao;
import com.deihs.mms.dao.UserDao;
import com.deihs.mms.db.Database;
import com.deihs.mms.model.Role;
import com.deihs.mms.model.User;
import com.deihs.mms.util.SmtpMailer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends a scheduled email digest of maintenance request stats.
 * Configuration is stored in app_settings; last-sent guard prevents duplicates.
 */
public final class DigestService {

    public static final String KEY_ENABLED = "digest.enabled";
    public static final String KEY_HOUR = "digest.hour";
    public static final String KEY_MINUTE = "digest.minute";
    public static final String KEY_ROLES = "digest.roles";
    public static final String KEY_LAST_SENT = "digest.last_sent";
    public static final String DEFAULT_ROLES = "ADMIN,MAINT_HEAD";

    private static final DateTimeFormatter LAST_SENT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static volatile ScheduledExecutorService scheduler;

    private final AppSettingsDao settingsDao = new AppSettingsDao();
    private final UserDao userDao = new UserDao();

    public static synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "deihs-digest-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                new DigestService().sendIfDue();
            } catch (Exception e) {
                System.err.println("Digest scheduler error: " + e.getMessage());
            }
        }, 30, 60, TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public boolean isEnabled() {
        try { return "1".equals(settingsDao.get(KEY_ENABLED).orElse("0")); }
        catch (Exception e) { return false; }
    }

    public void setEnabled(boolean enabled, Integer userId) throws SQLException {
        settingsDao.set(KEY_ENABLED, enabled ? "1" : "0", userId);
    }

    public int getHour() {
        try { return Math.max(0, Math.min(23, settingsDao.getInt(KEY_HOUR, 7))); }
        catch (Exception e) { return 7; }
    }

    public int getMinute() {
        try { return Math.max(0, Math.min(59, settingsDao.getInt(KEY_MINUTE, 0))); }
        catch (Exception e) { return 0; }
    }

    public void setTime(int hour, int minute, Integer userId) throws SQLException {
        settingsDao.set(KEY_HOUR, String.valueOf(Math.max(0, Math.min(23, hour))), userId);
        settingsDao.set(KEY_MINUTE, String.valueOf(Math.max(0, Math.min(59, minute))), userId);
    }

    public String getRoles() {
        try { return settingsDao.get(KEY_ROLES).orElse(DEFAULT_ROLES); }
        catch (Exception e) { return DEFAULT_ROLES; }
    }

    public void setRoles(String roles, Integer userId) throws SQLException {
        settingsDao.set(KEY_ROLES, roles == null || roles.isBlank() ? DEFAULT_ROLES : roles.trim(), userId);
    }

    public String getLastSent() {
        try { return settingsDao.get(KEY_LAST_SENT).orElse(""); }
        catch (Exception e) { return ""; }
    }

    public synchronized void sendIfDue() {
        try {
            if (!isEnabled()) return;
            LocalDateTime now = LocalDateTime.now();
            LocalTime due = LocalTime.of(getHour(), getMinute());
            if (now.toLocalTime().isBefore(due)) return;
            String last = getLastSent();
            if (last != null && !last.isBlank()) {
                try {
                    LocalDateTime lastDt = LocalDateTime.parse(last.trim(), LAST_SENT_FMT);
                    if (lastDt.toLocalDate().equals(now.toLocalDate())) return;
                } catch (Exception ignored) {
                    if (last.trim().startsWith(now.toLocalDate().toString())) return;
                }
            }
            sendDigestNow(null);
        } catch (Exception e) {
            System.err.println("Digest check failed: " + e.getMessage());
        }
    }

    public synchronized int sendDigestNow(Integer actorUserId) throws Exception {
        SmtpMailer mailer = new SmtpMailer();
        if (!mailer.isConfigured()) {
            throw new IllegalStateException("SMTP is not configured. Configure smtp.* in db.properties before sending email digests.");
        }
        DigestStats stats = loadStats();
        List<User> recipients = recipients();
        if (recipients.isEmpty()) {
            throw new IllegalStateException("No active digest recipients with email addresses were found for the selected roles.");
        }
        String subject = "DEIHS MMS Daily Digest - " + LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
        String html = html(stats);
        int sent = 0;
        List<String> failures = new ArrayList<>();
        for (User u : recipients) {
            String email = u.email();
            if (email == null || email.isBlank()) continue;
            try {
                mailer.send(email, subject, html);
                sent++;
            } catch (Exception e) {
                failures.add(u.username() + ": " + e.getMessage());
            }
        }
        settingsDao.set(KEY_LAST_SENT, LocalDateTime.now().format(LAST_SENT_FMT), actorUserId);
        if (sent == 0) {
            throw new IllegalStateException("Digest could not be sent. " + String.join("; ", failures));
        }
        if (!failures.isEmpty()) {
            System.err.println("Digest sent with failures: " + String.join("; ", failures));
        }
        return sent;
    }

    public List<User> recipients() throws SQLException {
        Set<String> roleCodes = new LinkedHashSet<>();
        for (String part : getRoles().split(",")) {
            String c = part.trim().toUpperCase(Locale.ROOT);
            if (!c.isEmpty()) roleCodes.add(c);
        }
        List<User> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (String code : roleCodes) {
            Role role;
            try { role = Role.valueOf(code); } catch (Exception e) { role = Role.fromCode(code); }
            for (User u : userDao.findByRole(role)) {
                if (u.active() && u.email() != null && !u.email().isBlank() && seen.add(u.id())) out.add(u);
            }
        }
        return out;
    }

    public DigestStats loadStats() throws SQLException {
        try (Connection c = Database.getConnection()) {
            int total = scalar(c, "SELECT COUNT(*) FROM maintenance_requests");
            int open = scalar(c, "SELECT COUNT(*) FROM maintenance_requests WHERE status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')");
            int pending = scalar(c, "SELECT COUNT(*) FROM maintenance_requests WHERE status='PENDING'");
            int inProgress = scalar(c, "SELECT COUNT(*) FROM maintenance_requests WHERE status IN ('ASSIGNED','IN_PROGRESS')");
            int overdue = scalar(c, "SELECT COUNT(*) FROM maintenance_requests WHERE status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD') AND target_date IS NOT NULL AND target_date < CURDATE()");
            int slaBreached = scalar(c, "SELECT COUNT(*) FROM maintenance_requests WHERE status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD') AND ((sla_breached_at IS NOT NULL) OR (sla_due_at IS NOT NULL AND sla_due_at < NOW()))");
            int completedThisMonth = scalar(c, "SELECT COUNT(*) FROM maintenance_requests WHERE status IN ('COMPLETED','CLOSED') AND completed_at IS NOT NULL AND YEAR(completed_at)=YEAR(CURDATE()) AND MONTH(completed_at)=MONTH(CURDATE())");
            int newToday = scalar(c, "SELECT COUNT(*) FROM maintenance_requests WHERE DATE(reported_at)=CURDATE()");
            List<String> recent = new ArrayList<>();
            String recentSql = """
                    SELECT ticket_no, title, status, priority
                    FROM maintenance_requests
                    ORDER BY reported_at DESC, request_id DESC
                    LIMIT 8
                    """;
            try (PreparedStatement ps = c.prepareStatement(recentSql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    recent.add(rs.getString("ticket_no") + " - " + rs.getString("title")
                            + " [" + rs.getString("status") + "/" + rs.getString("priority") + "]");
                }
            }
            return new DigestStats(total, open, pending, inProgress, overdue, slaBreached, completedThisMonth, newToday, recent);
        }
    }

    private static int scalar(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private String html(DigestStats s) {
        StringBuilder recent = new StringBuilder();
        if (s.recent().isEmpty()) {
            recent.append("<li>No requests recorded.</li>");
        } else {
            for (String r : s.recent()) recent.append("<li>").append(esc(r)).append("</li>");
        }
        return "<html><body style='margin:0;padding:0;background:#f2f5f2;font-family:Arial,Helvetica,sans-serif;'>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='background:#f2f5f2;padding:24px 0;'><tr><td align='center'>"
                + "<table role='presentation' width='640' cellpadding='0' cellspacing='0' style='background:#fff;border-radius:12px;overflow:hidden;border:1px solid #dfe6df;'>"
                + "<tr><td style='background:#1E7A43;padding:20px 28px;color:#fff;'><div style='font-size:20px;font-weight:bold;'>DEIHS MMS Daily Digest</div>"
                + "<div style='font-size:12px;opacity:.9;'>Dasmariñas East Integrated High School Maintenance Management System</div></td></tr>"
                + "<tr><td style='padding:24px 28px;color:#22302a;'>"
                + "<p style='margin:0 0 14px;font-size:14px;'>Scheduled maintenance summary for " + esc(LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))) + ".</p>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='font-size:14px;'>"
                + statRow("Open requests", s.open()) + statRow("Pending", s.pending()) + statRow("Assigned/In Progress", s.inProgress())
                + statRow("Overdue", s.overdue()) + statRow("SLA breached", s.slaBreached()) + statRow("Completed this month", s.completedThisMonth())
                + statRow("New today", s.newToday()) + statRow("Total requests", s.total())
                + "</table>"
                + "<h3 style='margin:20px 0 8px;color:#1E7A43;font-size:15px;'>Recent requests</h3><ul style='margin:0;padding-left:20px;font-size:13px;line-height:1.6;'>"
                + recent + "</ul>"
                + "<p style='margin:18px 0 0;font-size:12px;color:#6B7785;'>Sign in to the desktop app for full details, assignments, and actions.</p>"
                + "</td></tr><tr><td style='padding:14px 28px;background:#f7faf7;color:#6B7785;font-size:11px;'>This is an automated scheduled digest. Configure it in DEIHS MMS Settings.</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    private static String statRow(String label, int value) {
        String color = value > 0 && ("Overdue".equals(label) || "SLA breached".equals(label)) ? "#b91c1c" : "#22302a";
        return "<tr><td style='padding:8px 0;border-bottom:1px solid #edf2ee;'>" + esc(label) + "</td>"
                + "<td align='right' style='padding:8px 0;border-bottom:1px solid #edf2ee;font-weight:bold;color:" + color + ";'>" + value + "</td></tr>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public record DigestStats(int total, int open, int pending, int inProgress, int overdue,
                              int slaBreached, int completedThisMonth, int newToday, List<String> recent) {}
}
