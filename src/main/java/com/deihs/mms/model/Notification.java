package com.deihs.mms.model;

import java.time.LocalDateTime;

/**
 * One entry in a user's notification inbox.
 *
 * <p>{@code linkType} and {@code linkId} tell the UI what to open when the
 * row is clicked; a request notification carries {@code "request"} and the
 * request id. Other link types are reserved for schedules and parts.</p>
 */
public record Notification(
        int id,
        int userId,
        String title,
        String message,
        String linkType,
        Integer linkId,
        boolean read,
        LocalDateTime createdAt
) {

    public boolean isRequestLink() {
        return "request".equals(linkType) && linkId != null;
    }
}
