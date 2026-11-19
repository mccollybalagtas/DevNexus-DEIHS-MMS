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
        LocalDateTime uploadedAt
) {
}
