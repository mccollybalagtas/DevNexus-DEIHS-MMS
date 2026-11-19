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

    public static Role fromCode(String code) {
        if (code != null) {
            for (Role r : values()) {
                if (r.code.equalsIgnoreCase(code)) {
                    return r;
                }
            }
        }
        throw new IllegalArgumentException("Unknown role code: " + code);
    }
}
