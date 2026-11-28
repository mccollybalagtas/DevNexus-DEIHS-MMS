package com.deihs.mms.service;

import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.dao.EquipmentDao;
import com.deihs.mms.model.Equipment;
import com.deihs.mms.model.EquipmentDraft;
import com.deihs.mms.model.User;
import com.deihs.mms.util.AuditDiff;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

public class EquipmentService {

    private static final Set<String> CONDITIONS =
            Set.of("GOOD", "FAIR", "POOR", "NEEDS_REPAIR", "OUT_OF_ORDER");

    private final EquipmentDao equipmentDao = new EquipmentDao();
    private final AuditDao auditDao = new AuditDao();

    public int create(EquipmentDraft draft, User user) throws ValidationException, SQLException {
        requireManage(user);
        EquipmentDraft clean = clean(draft);
        validate(clean);
        if (equipmentDao.existsPropertyNo(clean.propertyNo(), 0)) {
            throw new ValidationException("Property number " + clean.propertyNo() + " is already in use.");
        }
        int id = equipmentDao.create(clean);
        auditDao.log(user.id(), "CREATE_EQUIPMENT", "equipment", clean.propertyNo(), clean.name());
        return id;
    }

    public void update(int id, EquipmentDraft draft, User user) throws ValidationException, SQLException {
        requireManage(user);
        EquipmentDraft clean = clean(draft);
        validate(clean);
        if (equipmentDao.existsPropertyNo(clean.propertyNo(), id)) {
            throw new ValidationException("Property number " + clean.propertyNo() + " is already in use.");
        }
        Equipment old = equipmentDao.findById(id);
        equipmentDao.update(id, clean);
        AuditDiff diff = AuditDiff.builder();
        if (old != null) {
            diff.put("propertyNo", old.propertyNo(), clean.propertyNo())
                .put("name", old.name(), clean.name())
                .put("category", old.category(), clean.category())
                .put("brand", old.brand(), clean.brand())
                .put("model", old.model(), clean.model())
                .put("serialNo", old.serialNo(), clean.serialNo())
                .put("facilityId", old.facilityId(), clean.facilityId())
                .put("condition", old.condition(), clean.condition())
                .put("acquisitionDate", old.acquisitionDate(), clean.acquisitionDate())
                .put("notes", old.notes(), clean.notes());
        }
        auditDao.log(user.id(), "UPDATE_EQUIPMENT", "equipment", clean.propertyNo(), clean.name(),
                diff.oldValues(), diff.newValues());
    }

    public void setActive(int id, String propertyNo, boolean active, User user)
            throws ValidationException, SQLException {
        requireManage(user);
        Equipment old = equipmentDao.findById(id);
        equipmentDao.setActive(id, active);
        AuditDiff diff = AuditDiff.builder().put("active", old != null && old.active(), active);
        auditDao.log(user.id(), active ? "RESTORE_EQUIPMENT" : "ARCHIVE_EQUIPMENT",
                "equipment", propertyNo, active ? "Restored" : "Archived", diff.oldValues(), diff.newValues());
    }

    private void requireManage(User user) throws ValidationException {
        if (!Permissions.canManageEquipment(user)) {
            throw new ValidationException("Your role cannot change the equipment inventory.");
        }
    }

    private EquipmentDraft clean(EquipmentDraft d) {
        if (d == null) return null;
        Integer fid = d.facilityId();
        if (fid != null && fid <= 0) fid = null;
        return new EquipmentDraft(
                upper(d.propertyNo()),
                text(d.name()),
                text(d.category()),
                text(d.brand()),
                text(d.model()),
                text(d.serialNo()),
                fid,
                d.condition() == null ? "GOOD" : d.condition().trim().toUpperCase(Locale.ROOT),
                d.acquisitionDate(),
                text(d.notes())
        );
    }

    private void validate(EquipmentDraft d) throws ValidationException {
        if (d == null) throw new ValidationException("Nothing to save.");
        if (d.propertyNo() == null || d.propertyNo().isEmpty()) {
            throw new ValidationException("Enter the property number.");
        }
        if (d.propertyNo().length() > 40) {
            throw new ValidationException("Property number is too long.");
        }
        if (d.name() == null || d.name().length() < 2) {
            throw new ValidationException("Enter the equipment name (at least 2 characters).");
        }
        if (d.name().length() > 120) {
            throw new ValidationException("Equipment name is too long.");
        }
        if (!CONDITIONS.contains(d.condition())) {
            throw new ValidationException("Choose a valid condition.");
        }
        if (d.category() != null && d.category().length() > 60) throw new ValidationException("Category is too long.");
        if (d.brand() != null && d.brand().length() > 60) throw new ValidationException("Brand is too long.");
        if (d.model() != null && d.model().length() > 60) throw new ValidationException("Model is too long.");
        if (d.serialNo() != null && d.serialNo().length() > 60) throw new ValidationException("Serial number is too long.");
    }

    private static String text(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String upper(String v) {
        String t = text(v);
        return t == null ? null : t.toUpperCase(Locale.ROOT);
    }
}
