package com.deihs.mms.service;

import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.dao.RequestDao;
import com.deihs.mms.model.AttachmentDraft;
import com.deihs.mms.model.CreatedRequest;
import com.deihs.mms.model.RequestDraft;
import com.deihs.mms.model.User;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Business rules for filing a request. The controller collects the form;
 * this class decides whether it is valid and owns the transaction.
 */
public class RequestService {

    private static final Set<String> PRIORITIES =
            Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final RequestDao requestDao = new RequestDao();
    private final AuditDao auditDao = new AuditDao();
    private final NotificationService notifications = new NotificationService();

    public CreatedRequest submit(RequestDraft draft, User user)
            throws ValidationException, SQLException {
        validate(draft);

        AttachmentDraft attachment = storePhoto(draft.photo());
        try {
            CreatedRequest created = requestDao.create(draft, user.id(), attachment);
            auditDao.log(user.id(), "CREATE_REQUEST", "maintenance_requests",
                    created.ticketNo(), draft.title().trim());
            notifications.requestCreated(created.id(), created.ticketNo(),
                    draft.title().trim(), user.fullName());
            return created;
        } catch (SQLException e) {
            deleteQuietly(attachment);
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    public void validate(RequestDraft draft) throws ValidationException {
        if (draft == null) {
            throw new ValidationException("Nothing to submit.");
        }

        String title = draft.title() == null ? "" : draft.title().trim();
        if (title.length() < 5) {
            throw new ValidationException("Give the issue a short title (at least 5 characters).");
        }
        if (title.length() > 150) {
            throw new ValidationException("The title is too long. Keep it under 150 characters.");
        }

        if (draft.categoryId() == null) {
            throw new ValidationException("Choose the type of maintenance work.");
        }

        if (draft.facilityId() == null && draft.equipmentId() == null) {
            throw new ValidationException("Choose the facility or the equipment that needs attention.");
        }

        String description = draft.description() == null ? "" : draft.description().trim();
        if (description.length() < 10) {
            throw new ValidationException("Describe the problem in at least one full sentence.");
        }

        if (draft.priority() == null || !PRIORITIES.contains(draft.priority())) {
            throw new ValidationException("Choose a priority.");
        }

        if (draft.targetDate() != null && draft.targetDate().isBefore(LocalDate.now())) {
            throw new ValidationException("The target date cannot be in the past.");
        }

        if (draft.photo() != null && !draft.photo().isFile()) {
            throw new ValidationException("The selected photo could not be read.");
        }
    }

    // ------------------------------------------------------------------
    // Photo storage
    // ------------------------------------------------------------------

    /**
     * Copies the photo next to the user profile so the record survives the
     * original being moved. Returns null when no photo was chosen.
     */
    private AttachmentDraft storePhoto(File photo) throws ValidationException {
        if (photo == null) {
            return null;
        }
        try {
            Path dir = Path.of(System.getProperty("user.home"), "DEIHS-MMS", "attachments");
            Files.createDirectories(dir);

            String original = photo.getName();
            String safe = original.replaceAll("[^A-Za-z0-9._-]", "_");
            String stored = UUID.randomUUID().toString().substring(0, 8) + "-" + safe;
            Path target = dir.resolve(stored);

            Files.copy(photo.toPath(), target, StandardCopyOption.REPLACE_EXISTING);

            String mime = Files.probeContentType(target);
            if (mime == null) {
                mime = guessMime(original);
            }
            return new AttachmentDraft(original, target.toString(), Files.size(target), mime);
        } catch (IOException e) {
            throw new ValidationException("The photo could not be saved: " + e.getMessage());
        }
    }

    private void deleteQuietly(AttachmentDraft attachment) {
        if (attachment == null) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(attachment.filePath()));
        } catch (IOException ignored) {
            // The orphaned file is harmless; the request itself failed.
        }
    }

    private static String guessMime(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "application/octet-stream";
    }
}
