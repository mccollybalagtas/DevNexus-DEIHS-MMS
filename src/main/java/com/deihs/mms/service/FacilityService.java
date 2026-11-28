package com.deihs.mms.service;

import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.dao.FacilityDao;
import com.deihs.mms.model.Facility;
import com.deihs.mms.model.FacilityDraft;
import com.deihs.mms.model.User;
import com.deihs.mms.util.AuditDiff;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/**
 * Rules for the facility registry. The controller collects the form; this
 * class normalises it, checks permissions and uniqueness, and writes the
 * audit trail.
 */
public class FacilityService {

    private static final Set<String> CONDITIONS =
            Set.of("GOOD", "FAIR", "POOR", "NEEDS_REPAIR", "OUT_OF_ORDER");

    private final FacilityDao facilityDao = new FacilityDao();
    private final AuditDao auditDao = new AuditDao();

    public int create(FacilityDraft draft, User user)
            throws ValidationException, SQLException {
        requireManage(user);
        FacilityDraft clean = clean(draft);
        validate(clean);

        if (facilityDao.existsCode(clean.code(), 0)) {
            throw new ValidationException(
                    "Facility code " + clean.code() + " is already in use. Choose another.");
        }

        int id = facilityDao.create(clean);
        auditDao.log(user.id(), "CREATE_FACILITY", "facilities", clean.code(),
                clean.name());
        return id;
    }

    public void update(int id, FacilityDraft draft, User user)
            throws ValidationException, SQLException {
        requireManage(user);
        FacilityDraft clean = clean(draft);
        validate(clean);

        if (facilityDao.existsCode(clean.code(), id)) {
            throw new ValidationException(
                    "Facility code " + clean.code() + " is already in use. Choose another.");
        }

        Facility old = facilityDao.findById(id);
        facilityDao.update(id, clean);
        AuditDiff diff = AuditDiff.builder();
        if (old != null) {
            diff.put("code", old.code(), clean.code())
                .put("name", old.name(), clean.name())
                .put("building", old.building(), clean.building())
                .put("floor", old.floor(), clean.floor())
                .put("roomNumber", old.roomNumber(), clean.roomNumber())
                .put("type", old.type(), clean.type())
                .put("condition", old.condition(), clean.condition())
                .put("notes", old.notes(), clean.notes());
        }
        auditDao.log(user.id(), "UPDATE_FACILITY", "facilities", clean.code(),
                clean.name(), diff.oldValues(), diff.newValues());
    }

    public void setActive(int id, String code, boolean active, User user)
            throws ValidationException, SQLException {
        requireManage(user);
        Facility old = facilityDao.findById(id);
        facilityDao.setActive(id, active);
        AuditDiff diff = AuditDiff.builder().put("active", old != null && old.active(), active);
        auditDao.log(user.id(), active ? "RESTORE_FACILITY" : "ARCHIVE_FACILITY",
                "facilities", code, active ? "Restored" : "Archived", diff.oldValues(), diff.newValues());
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    private void requireManage(User user) throws ValidationException {
        if (!Permissions.canManageFacilities(user)) {
            throw new ValidationException(
                    "Your role cannot change the facility registry.");
        }
    }

    /** Trims every field, upper-cases the code, and turns blanks into null. */
    private FacilityDraft clean(FacilityDraft draft) {
        if (draft == null) {
            return null;
        }
        return new FacilityDraft(
                upper(draft.code()),
                text(draft.name()),
                text(draft.building()),
                text(draft.floor()),
                text(draft.roomNumber()),
                text(draft.type()),
                draft.condition() == null
                        ? "GOOD" : draft.condition().trim().toUpperCase(Locale.ROOT),
                text(draft.notes())
        );
    }

    private void validate(FacilityDraft draft) throws ValidationException {
        if (draft == null) {
            throw new ValidationException("Nothing to save.");
        }

        if (draft.code() == null || draft.code().isEmpty()) {
            throw new ValidationException("Enter a facility code, for example RM-101.");
        }
        if (draft.code().length() > 20) {
            throw new ValidationException("The facility code is too long. Keep it under 20 characters.");
        }
        if (draft.name() == null || draft.name().length() < 3) {
            throw new ValidationException("Enter the facility name (at least 3 characters).");
        }
        if (draft.name().length() > 120) {
            throw new ValidationException("The facility name is too long. Keep it under 120 characters.");
        }
        if (!CONDITIONS.contains(draft.condition())) {
            throw new ValidationException("Choose a valid condition.");
        }
        if (draft.building() != null && draft.building().length() > 80) {
            throw new ValidationException("The building name is too long.");
        }
        if (draft.floor() != null && draft.floor().length() > 20) {
            throw new ValidationException("The floor is too long.");
        }
        if (draft.roomNumber() != null && draft.roomNumber().length() > 20) {
            throw new ValidationException("The room number is too long.");
        }
        if (draft.type() != null && draft.type().length() > 60) {
            throw new ValidationException("The facility type is too long.");
        }
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String upper(String value) {
        String trimmed = text(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
