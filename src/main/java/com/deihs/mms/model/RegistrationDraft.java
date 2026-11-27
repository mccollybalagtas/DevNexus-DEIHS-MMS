package com.deihs.mms.model;

/**
 * Values submitted from the public registration form. Password arrives in
 * plain text and is hashed by {@code RegistrationService} before storage.
 *
 * <p>{@code emailVerified} is set only after the user has proved ownership of
 * the address with the one-time code. It is stored with the request so an
 * administrator can see the address was checked before approving the account.</p>
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
        String requestedRole,
        boolean emailVerified
) {
}
