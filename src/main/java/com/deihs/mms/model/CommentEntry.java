package com.deihs.mms.model;

import java.time.LocalDateTime;

/**
 * A message on a request. Anyone who can see the request can post one.
 */
public record CommentEntry(
        int id,
        int userId,
        String author,
        String text,
        LocalDateTime createdAt
) {
}
