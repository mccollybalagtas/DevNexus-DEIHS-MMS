package com.deihs.mms.service;

import com.deihs.mms.model.Role;
import com.deihs.mms.model.User;

/**
 * Single source of truth for role permissions. Controllers hide what a role
 * cannot use; services must re-check before writing.
 */
public final class Permissions {

    private Permissions() {
    }

    public static boolean canManageUsers(User u) {
        return u.role() == Role.ADMIN;
    }

    public static boolean canManageFacilities(User u) {
        return u.role() == Role.ADMIN || u.role() == Role.MAINT_HEAD;
    }

    public static boolean canManageEquipment(User u) {
        return u.role() == Role.ADMIN || u.role() == Role.MAINT_HEAD;
    }

    public static boolean canManageSchedules(User u) {
        return u.role() == Role.ADMIN || u.role() == Role.MAINT_HEAD;
    }

    /** Add, edit, archive parts and adjust stock levels. */
    public static boolean canManageParts(User u) {
        return u.role() == Role.ADMIN || u.role() == Role.MAINT_HEAD;
    }

    /** Assign requests to personnel. */
    public static boolean canAssignRequests(User u) {
        return u.role() == Role.ADMIN || u.role() == Role.MAINT_HEAD;
    }

    /** Change status of any request (not only assigned ones). */
    public static boolean canUpdateAnyRequestStatus(User u) {
        return u.role() == Role.ADMIN || u.role() == Role.MAINT_HEAD;
    }

    public static boolean canViewAllRequests(User u) {
        return u.role() == Role.ADMIN || u.role() == Role.MAINT_HEAD;
    }

    public static boolean canViewAuditLog(User u) {
        return u.role() == Role.ADMIN;
    }

    public static boolean canViewReports(User u) {
        return u.role() == Role.ADMIN || u.role() == Role.MAINT_HEAD;
    }

    /** Teachers and staff may submit requests. */
    public static boolean canCreateRequest(User u) {
        return u.role() == Role.STAFF || u.role() == Role.ADMIN;
    }

    public static boolean canFileRepairRecord(User u) {
        return u.role() == Role.PERSONNEL || u.role() == Role.MAINT_HEAD;
    }
}
