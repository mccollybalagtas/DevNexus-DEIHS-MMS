package com.deihs.mms.model;

import java.time.LocalDateTime;

/**
 * One step in a request's status history.
 */
public record HistoryEntry(
        int id,
        String fromStatus,
        String toStatus,
        String changedBy,
        LocalDateTime changedAt,
        String remarks
) {
}
