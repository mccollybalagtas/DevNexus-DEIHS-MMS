package com.deihs.mms.model;

import java.time.LocalDateTime;

/**
 * A photo or file attached to a request. The path is absolute on the PC that
 * uploaded it, which is why the client shows the file name rather than
 * assuming the file is reachable from every workstation.
 */
public record AttachmentEntry(
        int id,
        String fileName,
        String filePath,
        long fileSize,
        String mimeType,
        String uploadedBy,
        LocalDateTime uploadedAt,
        String kind
) {
    public AttachmentEntry(int id, String fileName, String filePath, long fileSize,
                           String mimeType, String uploadedBy, LocalDateTime uploadedAt) {
        this(id, fileName, filePath, fileSize, mimeType, uploadedBy, uploadedAt, "GENERAL");
    }

    public String normalizedKind() {
        return kind == null || kind.isBlank() ? "GENERAL" : kind.trim().toUpperCase();
    }
}
