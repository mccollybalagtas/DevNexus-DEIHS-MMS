package com.deihs.mms.model;

/**
 * A file that travels with a request. The path is stored so the detail
 * screen can open it again later.
 */
public record AttachmentDraft(
        String fileName,
        String filePath,
        long fileSize,
        String mimeType
) {
}
