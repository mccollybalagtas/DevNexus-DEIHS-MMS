package com.deihs.mms.service;

import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.dao.MaintenanceRecordDao;
import com.deihs.mms.dao.PartDao;
import com.deihs.mms.model.PartDraft;
import com.deihs.mms.model.RequestDetail;
import com.deihs.mms.model.RequestPart;
import com.deihs.mms.model.User;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Rules for the parts inventory and for recording parts used on a request.
 * Stock changes are transactional in {@link PartDao}; this class adds
 * validation, permissions, audit entries, and notifications.
 */
public class PartService {

    private final PartDao partDao = new PartDao();
    private final MaintenanceRecordDao recordDao = new MaintenanceRecordDao();
    private final AuditDao auditDao = new AuditDao();
    private final NotificationService notifications = new NotificationService();
    private final RequestDetailService detailService = new RequestDetailService();

    // ------------------------------------------------------------------
    // Inventory
    // ------------------------------------------------------------------

    public int create(PartDraft draft, User user)
            throws ValidationException, SQLException {
        requireManage(user);
        PartDraft clean = clean(draft);
        validate(clean);
        if (partDao.existsName(clean.name(), 0)) {
            throw new ValidationException("A part named \"" + clean.name() + "\" already exists.");
        }
        int id = partDao.create(clean);
        auditDao.log(user.id(), "CREATE_PART", "parts", clean.name(),
                "Stock " + clean.quantity() + " " + clean.unit());
        return id;
    }

    public void update(int id, PartDraft draft, User user)
            throws ValidationException, SQLException {
        requireManage(user);
        PartDraft clean = clean(draft);
        validate(clean);
        if (partDao.existsName(clean.name(), id)) {
            throw new ValidationException("A part named \"" + clean.name() + "\" already exists.");
        }
        partDao.update(id, clean);
        auditDao.log(user.id(), "UPDATE_PART", "parts", clean.name(),
                "Stock " + clean.quantity() + " " + clean.unit());
    }

    public void setActive(int id, String name, boolean active, User user)
            throws ValidationException, SQLException {
        requireManage(user);
        partDao.setActive(id, active);
        auditDao.log(user.id(), active ? "RESTORE_PART" : "ARCHIVE_PART",
                "parts", name, active ? "Restored" : "Archived");
    }

    public int lowStockCount() throws SQLException {
        return partDao.lowStockCount();
    }

    // ------------------------------------------------------------------
    // Parts used on a request
    // ------------------------------------------------------------------

    public PartDao.Consumption consumeForRequest(int requestId, int partId, int quantity,
                                                 User user)
            throws ValidationException, SQLException {
        RequestDetail detail = detailService.load(requestId, user);
        requireRecordParts(detail, user);
        if (quantity <= 0) {
            throw new ValidationException("Quantity must be greater than zero.");
        }

        PartDao.Consumption result = partDao.consume(requestId, partId, quantity, user.id());
        auditDao.log(user.id(), "USE_PART", "maintenance_requests", detail.ticketNo(),
                quantity + " x " + result.partName());
        notifications.partsUsed(requestId, detail.ticketNo(), result.partName(),
                quantity, detail.requesterId());
        if (result.remaining() <= result.reorderLevel()) {
            notifications.lowStock(result.partName(), result.remaining(), result.reorderLevel());
        }
        return result;
    }

    public void removePart(int requestPartId, User user)
            throws ValidationException, SQLException {
        int requestId = requestIdFor(requestPartId);
        if (requestId == 0) {
            throw new ValidationException("That part usage no longer exists.");
        }
        RequestDetail detail = detailService.load(requestId, user);
        requireRecordParts(detail, user);

        PartDao.Removal removal = partDao.removeRequestPart(requestPartId, user.id());
        auditDao.log(user.id(), "REMOVE_PART", "maintenance_requests", detail.ticketNo(),
                removal.quantity() + " x " + removal.partName() + " returned to stock");
    }

    public List<RequestPart> partsForRequest(int requestId) throws SQLException {
        return partDao.findByRequest(requestId);
    }

    public BigDecimal partsCost(int requestId) throws SQLException {
        return recordDao.findPartsCostByRequest(requestId);
    }

    public boolean canRecordParts(RequestDetail detail, User user) {
        return detailService.canEditCost(detail, user);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void requireRecordParts(RequestDetail detail, User user)
            throws ValidationException {
        if (!detailService.canEditCost(detail, user)) {
            throw new ValidationException(
                    "You are not allowed to record parts for this request.");
        }
    }

    private int requestIdFor(int requestPartId) throws SQLException {
        try (Connection c = com.deihs.mms.db.Database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT request_id FROM request_parts WHERE id = ?")) {
            ps.setInt(1, requestPartId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void requireManage(User user) throws ValidationException {
        if (!Permissions.canManageParts(user)) {
            throw new ValidationException("Your role cannot change the parts inventory.");
        }
    }

    private PartDraft clean(PartDraft draft) {
        if (draft == null) {
            return null;
        }
        return new PartDraft(
                text(draft.name()),
                text(draft.sku()),
                draft.unit() == null || draft.unit().isBlank()
                        ? "pc" : draft.unit().trim().toLowerCase(Locale.ROOT),
                draft.quantity() == null ? 0 : draft.quantity(),
                draft.reorderLevel() == null ? 0 : draft.reorderLevel(),
                draft.unitCost() == null ? BigDecimal.ZERO
                        : draft.unitCost().setScale(2, RoundingMode.HALF_UP)
        );
    }

    private void validate(PartDraft draft) throws ValidationException {
        if (draft == null) {
            throw new ValidationException("Nothing to save.");
        }
        if (draft.name() == null || draft.name().length() < 2) {
            throw new ValidationException("Enter a part name (at least 2 characters).");
        }
        if (draft.name().length() > 120) {
            throw new ValidationException("The part name is too long. Keep it under 120 characters.");
        }
        if (draft.sku() != null && draft.sku().length() > 60) {
            throw new ValidationException("The SKU is too long. Keep it under 60 characters.");
        }
        if (draft.unit() == null || draft.unit().isEmpty() || draft.unit().length() > 20) {
            throw new ValidationException("Enter a unit (for example pc, box, or meter).");
        }
        if (draft.quantity() < 0) {
            throw new ValidationException("Quantity cannot be negative.");
        }
        if (draft.quantity() > 1_000_000) {
            throw new ValidationException("That quantity is too large.");
        }
        if (draft.reorderLevel() < 0) {
            throw new ValidationException("Reorder level cannot be negative.");
        }
        if (draft.unitCost().compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException("Unit cost cannot be negative.");
        }
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
