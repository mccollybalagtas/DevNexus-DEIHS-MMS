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
        boolean overdue,
        LocalDateTime slaDueAt,
        LocalDateTime slaBreachedAt,
        LocalDateTime escalatedAt,
        String approvalStatus,
        LocalDateTime approvalRequestedAt,
        Integer approvedById,
        String approvedByName,
        LocalDateTime approvedAt,
        String approvalNotes,
        java.math.BigDecimal approvalCost
) {

    public RequestDetail(int id, String ticketNo, String title, String description, String priority, String status,
                         int requesterId, String requester, Integer assigneeId, String assignee,
                         int categoryId, String category, Integer facilityId, String facility,
                         Integer equipmentId, String equipment, LocalDateTime reportedAt, LocalDate targetDate,
                         LocalDateTime completedAt, LocalDateTime closedAt, String resolutionNotes, boolean overdue,
                         LocalDateTime slaDueAt, LocalDateTime slaBreachedAt, LocalDateTime escalatedAt) {
        this(id, ticketNo, title, description, priority, status, requesterId, requester, assigneeId, assignee,
                categoryId, category, facilityId, facility, equipmentId, equipment, reportedAt, targetDate,
                completedAt, closedAt, resolutionNotes, overdue, slaDueAt, slaBreachedAt, escalatedAt,
                "NOT_REQUIRED", null, null, null, null, null, null);
    }

    public boolean isTerminal() {
        return "CLOSED".equals(status) || "REJECTED".equals(status)
                || "CANCELLED".equals(status);
    }

    public boolean slaBreached() {
        if (slaBreachedAt != null) return true;
        return slaDueAt != null && !isTerminal()
                && slaDueAt.isBefore(java.time.LocalDateTime.now());
    }

    public String normalizedApprovalStatus() {
        return approvalStatus == null || approvalStatus.isBlank() ? "NOT_REQUIRED" : approvalStatus.trim().toUpperCase();
    }

    public boolean approvalPending() { return "PENDING".equals(normalizedApprovalStatus()); }
    public boolean approvalRejected() { return "REJECTED".equals(normalizedApprovalStatus()); }
    public boolean approvalApproved() { return "APPROVED".equals(normalizedApprovalStatus()); }
}
