package com.deihs.mms.service;

/**
 * Raised for user-facing authentication problems. The message is safe to
 * show in the UI.
 */
public class AuthException extends Exception {
    public AuthException(String message) {
        super(message);
    }
}
