package com.deihs.mms.model;

import java.time.LocalDateTime;

/**
 * A pending/processed self-registration request awaiting admin review.
 */
public record AccountRequest(
        int id,
        String employeeNo,
        String username,
        String firstName,
        String lastName,
        String email,
        String phone,
        String department,
        String requestedRole,
        String status,
        LocalDateTime createdAt,
        String reviewNotes
) {

    public String fullName() {
        return firstName + " " + lastName;
    }
}
