package com.deihs.mms.model;

/**
 * A file that travels with a request. The path is stored so the detail
 * screen can open it again later.
 */
public record AttachmentDraft(
        String fileName,
        String filePath,
        long fileSize,
        String mimeType,
        String kind
) {
    public AttachmentDraft(String fileName, String filePath, long fileSize, String mimeType) {
        this(fileName, filePath, fileSize, mimeType, "GENERAL");
    }

    public String normalizedKind() {
        return kind == null || kind.isBlank() ? "GENERAL" : kind.trim().toUpperCase();
    }
}
