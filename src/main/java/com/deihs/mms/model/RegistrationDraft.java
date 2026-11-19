package com.deihs.mms.model;

/**
 * Values submitted from the public registration form. Password arrives in
 * plain text and is hashed by {@code RegistrationService} before storage.
 */
public record RegistrationDraft(
        String employeeNo,
        String username,
        String password,
        String confirmPassword,
        String firstName,
        String lastName,
        String email,
        String phone,
        String department,
        String requestedRole
) {
}
