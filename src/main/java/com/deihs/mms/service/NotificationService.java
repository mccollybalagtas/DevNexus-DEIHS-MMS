package com.deihs.mms.service;

import com.deihs.mms.dao.NotificationDao;
import com.deihs.mms.dao.UserDao;
import com.deihs.mms.model.Notification;
import com.deihs.mms.model.Role;
import com.deihs.mms.model.User;
import com.deihs.mms.util.SmtpMailer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Writes notifications to the inbox and, when SMTP is configured, mirrors the
 * important ones to email.
 *
 * <p>Database writes are synchronous so the caller knows the inbox entry
 * exists before it reports success. Email is best-effort: it runs on a daemon
 * thread and a mail failure is logged rather than allowed to roll back the
 * work that triggered it.</p>
 */
public class NotificationService {

    private final NotificationDao dao = new NotificationDao();
    private final UserDao userDao = new UserDao();

    public void notifyUser(int userId, String title, String message,
                           String linkType, Integer linkId) {
        try {
            dao.insert(userId, title, message, linkType, linkId);
        } catch (SQLException e) {
            System.err.println("Could not store notification: " + e.getMessage());
            return;
        }
        try {
            userDao.findById(userId).ifPresent(user ->
                    sendEmailAsync(user, title, message, linkType, linkId));
        } catch (SQLException e) {
            // The inbox entry is already stored; email is best-effort.
            System.err.println("Could not load notification recipient: " + e.getMessage());
        }
    }

    public void notifyRoles(Collection<Role> roles, String title, String message,
                            String linkType, Integer linkId, Integer excludeUserId) {
        for (Role role : roles) {
            List<User> users;
            try {
                users = userDao.findByRole(role);
            } catch (SQLException e) {
                System.err.println("Could not load users for notification: " + e.getMessage());
                continue;
            }
            for (User user : users) {
                if (!user.active()) {
                    continue;
                }
                if (excludeUserId != null && user.id() == excludeUserId) {
                    continue;
                }
                notifyUser(user.id(), title, message, linkType, linkId);
            }
        }
    }

    // ------------------------------------------------------------------
    // Domain events
    // ------------------------------------------------------------------

    public void requestCreated(int requestId, String ticketNo, String title, String requester) {
        notifyRoles(List.of(Role.ADMIN, Role.MAINT_HEAD),
                "New maintenance request " + ticketNo,
                requester + " filed \"" + title + "\". Assign it to a maintenance person.",
                "request", requestId, null);
    }

    public void requestAssigned(int requestId, String ticketNo, int assigneeId,
                                String actor, String title) {
        notifyUser(assigneeId, "Work assigned: " + ticketNo,
                actor + " assigned \"" + title + "\" to you.",
                "request", requestId);
    }

    public void requestStatusChanged(int requestId, String ticketNo, String title,
                                     String status, Integer requesterId, Integer assigneeId,
                                     int actorId) {
        String pretty = pretty(status);
        String message = "Status is now " + pretty + ".";
        if (requesterId != null && requesterId != actorId) {
            notifyUser(requesterId, ticketNo + " " + pretty, message, "request", requestId);
        }
        if (assigneeId != null && assigneeId != actorId && !assigneeId.equals(requesterId)) {
            notifyUser(assigneeId, ticketNo + " " + pretty, message, "request", requestId);
        }
    }

    public void requestComment(int requestId, String ticketNo, String author,
                               Integer requesterId, Integer assigneeId, int actorId) {
        String message = author + " commented on " + ticketNo + ".";
        if (requesterId != null && requesterId != actorId) {
            notifyUser(requesterId, "New comment on " + ticketNo, message,
                    "request", requestId);
        }
        if (assigneeId != null && assigneeId != actorId && !assigneeId.equals(requesterId)) {
            notifyUser(assigneeId, "New comment on " + ticketNo, message,
                    "request", requestId);
        }
    }

    public void scheduleWorkOrder(int requestId, String ticketNo, String title,
                                  Integer assigneeId, int actorId) {
        if (assigneeId != null && assigneeId != actorId) {
            notifyUser(assigneeId, "Preventive maintenance due: " + ticketNo,
                    "A work order was created for \"" + title + "\".",
                    "request", requestId);
        }
        notifyRoles(List.of(Role.ADMIN, Role.MAINT_HEAD),
                "Preventive work order " + ticketNo,
                "A scheduled task generated work order \"" + title + "\".",
                "request", requestId, assigneeId);
    }

    public void lowStock(String partName, int quantity, int reorderLevel) {
        notifyRoles(List.of(Role.ADMIN, Role.MAINT_HEAD),
                "Low stock: " + partName,
                "Only " + quantity + " left (reorder level " + reorderLevel + ").",
                "parts", null, null);
    }

    public void partsUsed(int requestId, String ticketNo, String partName, int quantity,
                          Integer requesterId) {
        if (requesterId != null) {
            notifyUser(requesterId, "Parts used on " + ticketNo,
                    quantity + " x " + partName + " was recorded against your request.",
                    "request", requestId);
        }
    }

    // ------------------------------------------------------------------
    // Inbox reads
    // ------------------------------------------------------------------

    public int unreadCount(int userId) {
        try {
            return dao.unreadCount(userId);
        } catch (SQLException e) {
            return 0;
        }
    }

    public List<Notification> recent(int userId, int limit) {
        try {
            return dao.recent(userId, limit);
        } catch (SQLException e) {
            return new ArrayList<>();
        }
    }

    public void markRead(int notificationId, int userId) {
        try {
            dao.markRead(notificationId, userId);
        } catch (SQLException e) {
            System.err.println("Could not mark notification read: " + e.getMessage());
        }
    }

    public void markAllRead(int userId) {
        try {
            dao.markAllRead(userId);
        } catch (SQLException e) {
            System.err.println("Could not mark notifications read: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Email
    // ------------------------------------------------------------------

    private void sendEmailAsync(User user, String title, String message,
                                String linkType, Integer linkId) {
        String address = user.email();
        if (address == null || address.isBlank()) {
            return;
        }
        SmtpMailer mailer = new SmtpMailer();
        if (!mailer.isConfigured()) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                mailer.send(address, title, html(user.fullName(), title, message));
            } catch (SmtpMailer.MailException e) {
                System.err.println("Notification email failed: " + e.getMessage());
            }
        }, "deihs-notify-mail");
        thread.setDaemon(true);
        thread.start();
    }

    private static String html(String name, String title, String message) {
        String safeName = escape(name);
        String safeTitle = escape(title);
        String safeMessage = escape(message);
        return """
                <html><body style="margin:0;padding:0;background:#f2f5f2;font-family:Arial,Helvetica,sans-serif;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                       style="background:#f2f5f2;padding:24px 0;">
                  <tr><td align="center">
                    <table role="presentation" width="560" cellpadding="0" cellspacing="0"
                           style="background:#ffffff;border-radius:12px;overflow:hidden;
                                  border:1px solid #dfe6df;">
                      <tr><td style="background:#1E7A43;padding:20px 28px;color:#ffffff;">
                        <div style="font-size:18px;font-weight:bold;">DEIHS Maintenance System</div>
                        <div style="font-size:12px;opacity:.85;">Dasmarinas East Integrated High School</div>
                      </td></tr>
                      <tr><td style="padding:26px 28px;color:#22302a;">
                        <p style="margin:0 0 12px;font-size:14px;">Hello %s,</p>
                        <h2 style="margin:0 0 10px;font-size:18px;color:#1E7A43;">%s</h2>
                        <p style="margin:0 0 18px;font-size:14px;line-height:1.5;">%s</p>
                        <p style="margin:0;font-size:12px;color:#6B7785;">
                          Sign in to the maintenance system to view the full record.
                        </p>
                      </td></tr>
                      <tr><td style="padding:14px 28px;background:#f7faf7;color:#6B7785;
                                     font-size:11px;">
                        This is an automated message. Please do not reply.
                      </td></tr>
                    </table>
                  </td></tr>
                </table>
                </body></html>
                """.formatted(safeName, safeTitle, safeMessage);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String pretty(String status) {
        if (status == null || status.isBlank()) {
            return "updated";
        }
        String lower = status.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
