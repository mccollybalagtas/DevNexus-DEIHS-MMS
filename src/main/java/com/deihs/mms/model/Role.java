package com.deihs.mms.model;

/**
 * The four system roles. The code matches roles.role_code in the database.
 */
public enum Role {

    ADMIN("ADMIN", "School Administrator"),
    MAINT_HEAD("MAINT_HEAD", "Maintenance Head"),
    PERSONNEL("PERSONNEL", "Maintenance Personnel"),
    STAFF("STAFF", "Teacher / School Staff");

    private final String code;
    private final String displayName;

    Role(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Maps a database role code to a Role. Unknown or blank codes fall back to
     * STAFF (least privilege) rather than throwing, so one malformed row can
     * never break the whole user list.
     */
    public static Role fromCode(String code) {
        if (code != null) {
            String trimmed = code.trim();
            for (Role r : values()) {
                if (r.code.equalsIgnoreCase(trimmed)) {
                    return r;
                }
            }
        }
        return STAFF;
    }
}
