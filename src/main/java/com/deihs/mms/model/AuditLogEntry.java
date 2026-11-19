package com.deihs.mms.model;

import java.time.LocalDateTime;

public record AuditLogEntry(
        long id,
        String actor,
        String action,
        String entityType,
        String entityId,
        String details,
        String ipAddress,
        LocalDateTime createdAt
) {
}
