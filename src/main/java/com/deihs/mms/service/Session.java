package com.deihs.mms.service;

import com.deihs.mms.model.User;

/**
 * Holds the signed-in user for the lifetime of the desktop client.
 */
public final class Session {

    private static User current;

    private Session() {
    }

    public static void set(User user) {
        current = user;
    }

    public static User get() {
        if (current == null) {
            throw new IllegalStateException("No active session");
        }
        return current;
    }

    public static boolean isLoggedIn() {
        return current != null;
    }

    public static void clear() {
        current = null;
    }
}
