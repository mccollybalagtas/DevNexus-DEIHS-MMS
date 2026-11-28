package com.deihs.mms.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row in a request table. Deliberately flat so it can be shown
 * without extra lookups.
 */
public record RequestRow(
        int id,
        String ticketNo,
        String requester,
        String asset,
        String title,
        String priority,
        String status,
        String assignee,
        LocalDateTime reportedAt,
        LocalDate targetDate,
        boolean overdue,
        LocalDateTime slaDueAt,
        boolean slaBreached
) {

    /** Short form used by the dashboard, which does not show the assignee. */
    public RequestRow(int id, String ticketNo, String requester, String asset, String title,
                      String priority, String status, LocalDateTime reportedAt, boolean overdue) {
        this(id, ticketNo, requester, asset, title, priority, status, null, reportedAt, null, overdue, null, false);
    }

    public RequestRow(int id, String ticketNo, String requester, String asset, String title,
                      String priority, String status, String assignee, LocalDateTime reportedAt,
                      LocalDate targetDate, boolean overdue) {
        this(id, ticketNo, requester, asset, title, priority, status, assignee, reportedAt, targetDate, overdue, null, false);
    }
}
