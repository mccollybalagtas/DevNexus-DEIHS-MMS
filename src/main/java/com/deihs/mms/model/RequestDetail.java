package com.deihs.mms.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Everything the detail screen shows about one request. Nullable ids and
 * names are kept as wrappers so the UI can tell "unset" from "zero".
 */
public record RequestDetail(
        int id,
        String ticketNo,
        String title,
        String description,
        String priority,
        String status,
        int requesterId,
        String requester,
        Integer assigneeId,
        String assignee,
        int categoryId,
        String category,
        Integer facilityId,
        String facility,
        Integer equipmentId,
        String equipment,
        LocalDateTime reportedAt,
        LocalDate targetDate,
        LocalDateTime completedAt,
        LocalDateTime closedAt,
        String resolutionNotes,
        boolean overdue
) {

    public boolean isTerminal() {
        return "CLOSED".equals(status) || "REJECTED".equals(status)
                || "CANCELLED".equals(status);
    }
}
