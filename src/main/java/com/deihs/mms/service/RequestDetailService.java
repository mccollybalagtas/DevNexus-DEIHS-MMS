package com.deihs.mms.service;

import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.dao.MaintenanceRecordDao;
import com.deihs.mms.dao.RequestDetailDao;
import com.deihs.mms.dao.UserDao;
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

    private final RequestDetailDao detailDao = new RequestDetailDao();
    private final UserDao userDao = new UserDao();
    private final AuditDao auditDao = new AuditDao();
    private final MaintenanceRecordDao recordDao = new MaintenanceRecordDao();
    private final NotificationService notifications = new NotificationService();

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
        auditDao.log(actor.id(), "ASSIGN_REQUEST", "maintenance_requests",
                detail.ticketNo(), details);
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

        boolean ok = detailDao.changeStatus(requestId, actor.id(), detail.status(), toStatus, note);
        if (!ok) {
            throw new ValidationException(
                    "This request was updated by someone else. Reopen it and try again.");
        }

        String details = pretty(detail.status()) + " -> " + pretty(toStatus)
                + (note == null ? "" : " - " + note);
        auditDao.log(actor.id(), "UPDATE_REQUEST_STATUS", "maintenance_requests",
                detail.ticketNo(), details);
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
        recordDao.upsertCost(requestId, actor.id(), cost);
        auditDao.log(actor.id(), "UPDATE_REPAIR_COST", "maintenance_requests",
                detail.ticketNo(), "Repair cost set to " + cost.toPlainString());
    }

    private static String pretty(String status) {
        if (status == null || status.isBlank()) {
            return "unknown";
        }
        return status.charAt(0) + status.substring(1).toLowerCase().replace('_', ' ');
    }
}
