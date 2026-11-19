package com.deihs.mms.service;

/**
 * A rule the user can fix, such as a missing field. The message is written
 * to be shown directly in the form.
 */
public class ValidationException extends Exception {

    public ValidationException(String message) {
        super(message);
    }
}
