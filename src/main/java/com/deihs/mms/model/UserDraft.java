package com.deihs.mms.model;

/**
 * Admin-entered user fields for create/edit. Password is only used on create.
 */
public record UserDraft(
        String employeeNo,
        String username,
        String firstName,
        String lastName,
        String email,
        String phone,
        String department,
        String roleCode,
        String password
) {
}
