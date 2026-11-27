package com.deihs.mms.model;

import java.time.LocalDateTime;

/**
 * Authenticated user. Never carries the password hash.
 *
 * <p>The last four fields hold personalisation data added after the original
 * schema. They are defaulted by the database, so an older row still maps to a
 * usable {@code User} without any migration of the Java side.</p>
 */
public record User(
        int id,
        String employeeNo,
        String username,
        String firstName,
        String lastName,
        String email,
        String phone,
        Role role,
        String department,
        boolean active,
        LocalDateTime lastLogin,
        String photoPath,
        String theme,
        String uiDensity,
        boolean emailVerified
) {

    public String fullName() {
        return firstName + " " + lastName;
    }

    public String initials() {
        String a = firstName == null || firstName.isBlank() ? "" : firstName.substring(0, 1);
        String b = lastName == null || lastName.isBlank() ? "" : lastName.substring(0, 1);
        String value = (a + b).toUpperCase();
        return value.isBlank() ? "?" : value;
    }

    public String roleName() {
        return role.displayName();
    }

    /** Theme key understood by the stylesheet; falls back to the green brand. */
    public String themeOrDefault() {
        if (theme == null || theme.isBlank()) {
            return "green";
        }
        String value = theme.trim().toLowerCase();
        return switch (value) {
            case "navy", "dark", "green" -> value;
            default -> "green";
        };
    }

    /** "comfortable" or "compact". */
    public String densityOrDefault() {
        return "compact".equalsIgnoreCase(uiDensity) ? "compact" : "comfortable";
    }
}
