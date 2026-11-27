package com.deihs.mms.util;

import java.util.regex.Pattern;

/**
 * One place for the account rules so the sign-in screen, the registration
 * form and the profile page all behave the same way.
 *
 * <p>Usernames must start with a letter: a name made only of digits is not a
 * username, and starting with a letter keeps the value unambiguous when it is
 * printed in a ticket or a report.</p>
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;

    private static final Pattern USERNAME =
            Pattern.compile("^[A-Za-z][A-Za-z0-9._-]{2,49}$");

    public enum Strength {
        WEAK("Weak"),
        GOOD("Good"),
        STRONG("Strong");

        private final String label;

        Strength(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public String styleClass() {
            return "strength-" + name().toLowerCase();
        }
    }

    private PasswordPolicy() {
    }

    /** Returns a user-facing problem, or null when the username is acceptable. */
    public static String usernameError(String username) {
        String value = username == null ? "" : username.trim();
        if (value.isEmpty()) {
            return "Enter a username.";
        }
        if (value.length() < 3) {
            return "Username must be at least 3 characters.";
        }
        if (value.length() > 50) {
            return "Username must be 50 characters or fewer.";
        }
        if (!USERNAME.matcher(value).matches()) {
            return "Username must start with a letter and use only letters, numbers, dot, underscore or dash.";
        }
        return null;
    }

    /** Returns a user-facing problem, or null when the password is acceptable. */
    public static String passwordError(String password) {
        String value = password == null ? "" : password;
        if (value.isEmpty()) {
            return "Enter a password.";
        }
        if (value.length() < MIN_LENGTH) {
            return "Password must be at least " + MIN_LENGTH + " characters.";
        }
        if (value.length() > MAX_LENGTH) {
            return "Password must be " + MAX_LENGTH + " characters or fewer.";
        }
        if (!value.matches(".*[A-Za-z].*")) {
            return "Password must include at least one letter.";
        }
        if (!value.matches(".*[0-9].*")) {
            return "Password must include at least one number.";
        }
        return null;
    }

    /** Weak / Good / Strong label for the meter shown while typing. */
    public static Strength strength(String password) {
        int score = score(password);
        if (score >= 4) {
            return Strength.STRONG;
        }
        if (score >= 2) {
            return Strength.GOOD;
        }
        return Strength.WEAK;
    }

    /** 0..5 score used by the three-bar meter. */
    public static int score(String password) {
        String value = password == null ? "" : password;
        int score = 0;
        if (value.length() >= MIN_LENGTH) score++;
        if (value.length() >= 12) score++;
        boolean lower = value.matches(".*[a-z].*");
        boolean upper = value.matches(".*[A-Z].*");
        if (lower && upper) score++;
        if (value.matches(".*[0-9].*")) score++;
        if (value.matches(".*[^A-Za-z0-9].*")) score++;
        return score;
    }

    /** Convenience for callers that only need a boolean. */
    public static boolean isAcceptable(String password) {
        return passwordError(password) == null;
    }
}
