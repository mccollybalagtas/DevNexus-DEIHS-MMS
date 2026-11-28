package com.deihs.mms.service;

import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.dao.MaintenanceRecordDao;
import com.deihs.mms.dao.RequestDetailDao;
import com.deihs.mms.dao.UserDao;
import com.deihs.mms.util.AuditDiff;
import com.deihs.mms.model.AttachmentEntry;
import com.deihs.mms.model.CommentEntry;
import com.deihs.mms.model.HistoryEntry;
import com.deihs.mms.model.RequestDetail;
import com.deihs.mms.model.Role;
import com.deihs.mms.model.User;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Business rules around one maintenance request: who may see it, who may
 * assign it, and which status moves are legal for the signed-in role.
 *
 * <p>Controllers use {@link #nextStatuses(RequestDetail, User)} to build the
 * status menu, but every write re-checks the same rules here. Hiding a button
 * is convenience; refusing the write is the control.</p>
 */
public class RequestDetailService {

    public static final String HIGH_COST_THRESHOLD_KEY = "high_cost_approval_threshold";

    private final RequestDetailDao detailDao = new RequestDetailDao();
    private final UserDao userDao = new UserDao();
    private final AuditDao auditDao = new AuditDao();
    private final MaintenanceRecordDao recordDao = new MaintenanceRecordDao();
    private final NotificationService notifications = new NotificationService();
    private final com.deihs.mms.dao.AppSettingsDao appSettingsDao = new com.deihs.mms.dao.AppSettingsDao();

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * Loads a request the user is allowed to see.
     *
     * @throws ValidationException when the request does not exist or the
     *                             signed-in user is not allowed to open it
     */
    public RequestDetail load(int requestId, User user) throws SQLException, ValidationException {
        RequestDetail detail = detailDao.load(requestId);
        if (detail == null) {
            throw new ValidationException("That request no longer exists.");
        }
        if (!canView(detail, user)) {
            throw new ValidationException("You do not have access to this request.");
        }
        return detail;
    }

    public List<HistoryEntry> history(int requestId) throws SQLException {
        return detailDao.history(requestId);
    }

    public List<CommentEntry> comments(int requestId) throws SQLException {
        return detailDao.comments(requestId);
    }

    public List<AttachmentEntry> attachments(int requestId) throws SQLException {
        return detailDao.attachments(requestId);
    }

    /** Administrators and the head see everything; everyone else sees their own. */
    public boolean canView(RequestDetail detail, User user) {
        if (detail == null || user == null) {
            return false;
        }
        return switch (user.role()) {
            case ADMIN, MAINT_HEAD -> true;
            case PERSONNEL -> detail.assigneeId() != null && detail.assigneeId() == user.id();
            case STAFF -> detail.requesterId() == user.id();
        };
    }

    // ------------------------------------------------------------------
    // Assignment
    // ------------------------------------------------------------------

    /**
     * Hands the request to a maintenance person. PENDING requests become
     * ASSIGNED; an ASSIGNED request can be reassigned.
     */
    public void assign(int requestId, int assigneeId, String remarks, User actor)
            throws SQLException, ValidationException {
        if (!Permissions.canAssignRequests(actor)) {
            throw new ValidationException("Only the maintenance head can assign requests.");
        }

        RequestDetail detail = load(requestId, actor);
        if (!StatusFlow.PENDING.equals(detail.status())
                && !StatusFlow.ASSIGNED.equals(detail.status())) {
            throw new ValidationException(
                    "Only pending or assigned requests can be reassigned.");
        }

        User assignee = userDao.findById(assigneeId)
                .orElseThrow(() -> new ValidationException("That maintenance person was not found."));
        if (assignee.role() != Role.PERSONNEL) {
            throw new ValidationException("Requests can only be assigned to maintenance personnel.");
        }
        if (!assignee.active()) {
            throw new ValidationException("That account is inactive and cannot receive work.");
        }

        String note = clean(remarks);
        boolean ok = detailDao.assign(requestId, assigneeId, actor.id(),
                detail.status(), note);
        if (!ok) {
            throw new ValidationException(
                    "This request was updated by someone else. Reopen it and try again.");
        }

        String details = "Assigned to " + assignee.fullName()
                + (note == null ? "" : " - " + note);
        AuditDiff diff = AuditDiff.builder()
                .put("assignee", detail.assignee(), assignee.fullName())
                .put("status", detail.status(), "ASSIGNED");
        auditDao.log(actor.id(), "ASSIGN_REQUEST", "maintenance_requests",
                detail.ticketNo(), details, diff.oldValues(), diff.newValues());
        notifications.requestAssigned(requestId, detail.ticketNo(), assigneeId,
                actor.fullName(), detail.title());
    }

    // ------------------------------------------------------------------
    // Status changes
    // ------------------------------------------------------------------

    /**
     * The statuses the signed-in user may move this request to. Empty when the
     * request is finished or the user may not update it at all.
     */
    public List<String> nextStatuses(RequestDetail detail, User user) {
        if (detail == null || user == null || detail.isTerminal()) {
            return List.of();
        }
        boolean personnel = user.role() == Role.PERSONNEL;
        if (personnel) {
            if (detail.assigneeId() == null || detail.assigneeId() != user.id()) {
                return List.of();
            }
        } else if (!Permissions.canUpdateAnyRequestStatus(user)) {
            return List.of();
        }
        return new ArrayList<>(StatusFlow.allowed(detail.status(), personnel));
    }

    /** Moves a request to a new status after re-checking role and workflow. */
    public void changeStatus(int requestId, String toStatus, String remarks, User actor)
            throws SQLException, ValidationException {
        RequestDetail detail = load(requestId, actor);

        boolean personnel = actor.role() == Role.PERSONNEL;
        if (!personnel && !Permissions.canUpdateAnyRequestStatus(actor)) {
            throw new ValidationException("You are not allowed to change a request's status.");
        }
        if (personnel && (detail.assigneeId() == null || detail.assigneeId() != actor.id())) {
            throw new ValidationException("You can only update work assigned to you.");
        }
        if (detail.isTerminal()) {
            throw new ValidationException(
                    "This request is " + detail.status().toLowerCase() + " and can no longer change.");
        }
        if (!StatusFlow.canMove(detail.status(), toStatus, personnel)) {
            throw new ValidationException("A " + pretty(detail.status())
                    + " request cannot move to " + pretty(toStatus) + ".");
        }

        String note = clean(remarks);
        if (StatusFlow.requiresRemarks(toStatus) && note == null) {
            throw new ValidationException(switch (toStatus) {
                case StatusFlow.REJECTED -> "Say why the request is being rejected.";
                case StatusFlow.ON_HOLD -> "Say why the work is being put on hold.";
                default -> "Say why the request is being cancelled.";
            });
        }

        if (StatusFlow.COMPLETED.equals(toStatus) || StatusFlow.CLOSED.equals(toStatus)) {
            enforceHighCostApproval(detail);
        }

        boolean ok = detailDao.changeStatus(requestId, actor.id(), detail.status(), toStatus, note);
        if (!ok) {
            throw new ValidationException(
                    "This request was updated by someone else. Reopen it and try again.");
        }

        String details = pretty(detail.status()) + " -> " + pretty(toStatus)
                + (note == null ? "" : " - " + note);
        AuditDiff diff = AuditDiff.builder()
                .put("status", detail.status(), toStatus)
                .put("remarks", detail.resolutionNotes(), note);
        auditDao.log(actor.id(), "UPDATE_REQUEST_STATUS", "maintenance_requests",
                detail.ticketNo(), details, diff.oldValues(), diff.newValues());
        notifications.requestStatusChanged(requestId, detail.ticketNo(), detail.title(),
                toStatus, detail.requesterId(), detail.assigneeId(), actor.id());
    }

    // ------------------------------------------------------------------
    // Comments
    // ------------------------------------------------------------------

    /** Adds a comment to a request the user can see. */
    public CommentEntry addComment(int requestId, String text, User actor)
            throws SQLException, ValidationException {
        String body = clean(text);
        if (body == null) {
            throw new ValidationException("Write a comment first.");
        }
        if (body.length() > 1000) {
            throw new ValidationException("Keep the comment under 1000 characters.");
        }

        RequestDetail detail = load(requestId, actor);
        CommentEntry saved = detailDao.addComment(requestId, actor.id(), body);
        auditDao.log(actor.id(), "ADD_COMMENT", "maintenance_requests",
                detail.ticketNo(), "Comment #" + saved.id());
        notifications.requestComment(requestId, detail.ticketNo(), actor.fullName(),
                detail.requesterId(), detail.assigneeId(), actor.id());
        return saved;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------
    // Repair cost
    // ------------------------------------------------------------------

    public java.math.BigDecimal repairCost(int requestId) throws SQLException {
        return recordDao.findTotalCostByRequest(requestId);
    }

    public boolean canEditCost(RequestDetail detail, User user) {
        if (detail == null || user == null) return false;
        return switch (user.role()) {
            case ADMIN, MAINT_HEAD -> true;
            case PERSONNEL -> detail.assigneeId() != null && detail.assigneeId() == user.id();
            case STAFF -> false;
        };
    }

    public void updateRepairCost(int requestId, java.math.BigDecimal cost, User actor)
            throws SQLException, ValidationException {
        RequestDetail detail = load(requestId, actor);
        if (!canEditCost(detail, actor)) {
            throw new ValidationException("You are not allowed to edit repair cost for this request.");
        }
        if (cost == null || cost.compareTo(java.math.BigDecimal.ZERO) < 0) {
            throw new ValidationException("Repair cost must be zero or greater.");
        }
        if (cost.scale() > 2) {
            cost = cost.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        java.math.BigDecimal oldCost = recordDao.findTotalCostByRequest(requestId);
        recordDao.upsertCost(requestId, actor.id(), cost);
        AuditDiff diff = AuditDiff.builder().put("repairCost", oldCost, cost);
        auditDao.log(actor.id(), "UPDATE_REPAIR_COST", "maintenance_requests",
                detail.ticketNo(), "Repair cost set to " + cost.toPlainString(), diff.oldValues(), diff.newValues());
        evaluateApprovalForCost(detail, cost, actor);
    }

    // ------------------------------------------------------------------
    // High-cost approval workflow
    // ------------------------------------------------------------------

    public int highCostThreshold() throws SQLException {
        return Math.max(0, appSettingsDao.getInt(HIGH_COST_THRESHOLD_KEY, 0));
    }

    public void setHighCostThreshold(int threshold, User actor) throws SQLException, ValidationException {
        if (actor == null || actor.role() != Role.ADMIN) {
            throw new ValidationException("Only administrators can change the high-cost approval threshold.");
        }
        if (threshold < 0 || threshold > 1_000_000_000) {
            throw new ValidationException("Threshold must be between 0 and 1,000,000,000.");
        }
        appSettingsDao.set(HIGH_COST_THRESHOLD_KEY, String.valueOf(threshold), actor.id());
        auditDao.log(actor.id(), "UPDATE_HIGH_COST_THRESHOLD", "app_settings", HIGH_COST_THRESHOLD_KEY,
                "Threshold set to " + threshold, null, "threshold=" + threshold);
    }

    private void evaluateApprovalForCost(RequestDetail detail, java.math.BigDecimal cost, User actor)
            throws SQLException {
        int threshold = highCostThreshold();
        java.math.BigDecimal c = cost == null ? java.math.BigDecimal.ZERO : cost;
        String current = detail.normalizedApprovalStatus();
        if (threshold <= 0) {
            if (!"NOT_REQUIRED".equals(current)) {
                detailDao.clearApproval(detail.id());
                auditDao.log(actor.id(), "CLEAR_COST_APPROVAL", "maintenance_requests", detail.ticketNo(),
                        "Approval cleared because threshold is disabled", "approvalStatus=" + current, "approvalStatus=NOT_REQUIRED");
            }
            return;
        }
        if (c.compareTo(java.math.BigDecimal.valueOf(threshold)) >= 0) {
            boolean needsRequest = !"APPROVED".equals(current) || detail.approvalCost() == null
                    || c.compareTo(detail.approvalCost()) > 0;
            if (needsRequest) {
                detailDao.updateApproval(detail.id(), "PENDING", null,
                        "Repair cost " + c.toPlainString() + " meets/exceeds threshold " + threshold, c);
                auditDao.log(actor.id(), "REQUEST_COST_APPROVAL", "maintenance_requests", detail.ticketNo(),
                        "High-cost approval requested at " + c.toPlainString(),
                        "approvalStatus=" + current, "approvalStatus=PENDING,cost=" + c.toPlainString());
                notifications.approvalRequested(detail.id(), detail.ticketNo(), detail.title(), c, actor.id());
            }
        } else if (!"NOT_REQUIRED".equals(current)) {
            detailDao.clearApproval(detail.id());
            auditDao.log(actor.id(), "CLEAR_COST_APPROVAL", "maintenance_requests", detail.ticketNo(),
                    "Approval cleared because cost is below threshold",
                    "approvalStatus=" + current, "approvalStatus=NOT_REQUIRED");
        }
    }

    private void enforceHighCostApproval(RequestDetail detail) throws SQLException, ValidationException {
        int threshold = highCostThreshold();
        if (threshold <= 0) return;
        java.math.BigDecimal cost = recordDao.findTotalCostByRequest(detail.id());
        if (cost.compareTo(java.math.BigDecimal.valueOf(threshold)) < 0) return;
        String st = detail.normalizedApprovalStatus();
        if ("APPROVED".equals(st)) return;
        if ("PENDING".equals(st)) {
            throw new ValidationException("This high-cost repair needs approval before it can be completed or closed.");
        }
        if ("REJECTED".equals(st)) {
            throw new ValidationException("This high-cost repair was rejected. Update the cost or get approval before completing/closing.");
        }
        // Threshold reached but no approval row yet - require it.
        detailDao.updateApproval(detail.id(), "PENDING", null,
                "Repair cost " + cost.toPlainString() + " meets/exceeds threshold " + threshold, cost);
        notifications.approvalRequested(detail.id(), detail.ticketNo(), detail.title(), cost, 0);
        throw new ValidationException("This repair cost requires approval. An approval request has been created.");
    }

    public void decideApproval(int requestId, boolean approve, String notes, User actor)
            throws SQLException, ValidationException {
        if (!Permissions.canApproveHighCost(actor)) {
            throw new ValidationException("You are not allowed to approve high-cost repairs.");
        }
        RequestDetail detail = load(requestId, actor);
        if (!"PENDING".equals(detail.normalizedApprovalStatus())) {
            throw new ValidationException("This request does not have a pending high-cost approval.");
        }
        String note = clean(notes);
        if (!approve && note == null) {
            throw new ValidationException("Provide a reason when rejecting a high-cost repair.");
        }
        String newStatus = approve ? "APPROVED" : "REJECTED";
        detailDao.updateApproval(requestId, newStatus, actor.id(), note, detail.approvalCost());
        AuditDiff diff = AuditDiff.builder()
                .put("approvalStatus", detail.normalizedApprovalStatus(), newStatus)
                .put("approvalNotes", detail.approvalNotes(), note);
        auditDao.log(actor.id(), approve ? "APPROVE_HIGH_COST" : "REJECT_HIGH_COST", "maintenance_requests",
                detail.ticketNo(), (approve ? "Approved" : "Rejected") + " high-cost repair" + (note == null ? "" : ": " + note),
                diff.oldValues(), diff.newValues());
        notifications.approvalDecided(requestId, detail.ticketNo(), detail.title(), approve, actor.fullName(),
                detail.requesterId(), detail.assigneeId(), actor.id());
    }

    // ------------------------------------------------------------------
    // Attachments
    // ------------------------------------------------------------------

    public void addAttachment(int requestId, java.io.File file, String kind, User actor)
            throws SQLException, ValidationException {
        RequestDetail detail = load(requestId, actor);
        if (file == null || !file.isFile()) throw new ValidationException("Choose a valid file to attach.");
        String k = kind == null ? "GENERAL" : kind.trim().toUpperCase();
        if (!java.util.Set.of("GENERAL","BEFORE","AFTER").contains(k)) {
            throw new ValidationException("Invalid attachment type.");
        }
        com.deihs.mms.model.AttachmentDraft draft = storeAttachment(file, k);
        try {
            detailDao.insertAttachment(requestId, draft, actor.id());
            auditDao.log(actor.id(), "ADD_ATTACHMENT", "maintenance_requests", detail.ticketNo(),
                    "Added " + k.toLowerCase() + " attachment: " + draft.fileName());
        } catch (SQLException e) {
            try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(draft.filePath())); } catch (Exception ignored) {}
            throw e;
        }
    }

    private com.deihs.mms.model.AttachmentDraft storeAttachment(java.io.File file, String kind) throws ValidationException {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.home"), "DEIHS-MMS", "attachments");
            java.nio.file.Files.createDirectories(dir);
            String original = file.getName();
            String safe = original.replaceAll("[^A-Za-z0-9._-]", "_");
            String stored = java.util.UUID.randomUUID().toString().substring(0, 8) + "-" + safe;
            java.nio.file.Path target = dir.resolve(stored);
            java.nio.file.Files.copy(file.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            String mime = java.nio.file.Files.probeContentType(target);
            if (mime == null) {
                String lower = original.toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".png")) mime = "image/png";
                else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) mime = "image/jpeg";
                else if (lower.endsWith(".pdf")) mime = "application/pdf";
                else mime = "application/octet-stream";
            }
            return new com.deihs.mms.model.AttachmentDraft(original, target.toString(), java.nio.file.Files.size(target), mime, kind);
        } catch (Exception e) {
            throw new ValidationException("The file could not be saved: " + e.getMessage());
        }
    }

    private static String pretty(String status) {
        if (status == null || status.isBlank()) {
            return "unknown";
        }
        return status.charAt(0) + status.substring(1).toLowerCase().replace('_', ' ');
    }
}
