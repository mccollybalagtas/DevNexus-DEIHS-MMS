package com.deihs.mms.model;

import java.time.LocalDateTime;

/**
 * Authenticated user. Never carries the password hash.
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
        LocalDateTime lastLogin
) {

    public String fullName() {
        return firstName + " " + lastName;
    }

    public String initials() {
        String a = firstName == null || firstName.isBlank() ? "" : firstName.substring(0, 1);
        String b = lastName == null || lastName.isBlank() ? "" : lastName.substring(0, 1);
        return (a + b).toUpperCase();
    }

    public String roleName() {
        return role.displayName();
    }
}
