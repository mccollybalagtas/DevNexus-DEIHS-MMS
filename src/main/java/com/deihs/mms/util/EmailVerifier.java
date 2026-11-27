package com.deihs.mms.util;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Owns the one-time e-mail code used by the registration form.
 *
 * <p>The code lives in memory only: it is a proof that the person filling in
 * the form can read the mailbox, not a durable credential. It expires after ten
 * minutes, tolerates five wrong guesses, and cannot be re-sent within thirty
 * seconds so the button cannot be used to spam an address.</p>
 *
 * <p>When SMTP is not configured the code is returned to the caller and shown
 * on screen, labelled as a development code. That keeps the feature usable in
 * a classroom or demo without silently pretending a message was sent.</p>
 */
public final class EmailVerifier {

    public static final int CODE_LENGTH = 6;
    public static final int MAX_ATTEMPTS = 5;
    public static final int RESEND_SECONDS = 30;
    public static final int VALID_MINUTES = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Raised for an expired, missing or exhausted code. */
    public static class VerificationException extends Exception {
        public VerificationException(String message) {
            super(message);
        }
    }

    /** What happened when a code was requested. */
    public record Challenge(boolean emailed, String devCode) {
        public boolean isDevMode() {
            return !emailed;
        }
    }

    private final SmtpMailer mailer = new SmtpMailer();

    private String email;
    private String code;
    private long expiresAt;
    private long sentAt;
    private int attempts;

    public boolean isMailConfigured() {
        return mailer.isConfigured();
    }

    /**
     * Generates a fresh code and tries to e-mail it. On an SMTP failure the
     * code is still generated so the caller can decide whether to fall back to
     * development mode or ask the user to try again.
     */
    public Challenge send(String address) throws SmtpMailer.MailException {
        String target = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) {
            throw new SmtpMailer.MailException("Enter an e-mail address first.");
        }
        long now = System.currentTimeMillis();
        if (sentAt > 0 && now - sentAt < RESEND_SECONDS * 1000L) {
            long wait = (RESEND_SECONDS * 1000L - (now - sentAt) + 999) / 1000;
            throw new SmtpMailer.MailException(
                    "Please wait " + wait + "s before requesting another code.");
        }

        this.email = target;
        this.code = generateCode();
        this.expiresAt = now + VALID_MINUTES * 60_000L;
        this.attempts = 0;
        this.sentAt = now;

        if (!mailer.isConfigured()) {
            return new Challenge(false, code);
        }
        mailer.send(target, "Your DEIHS MMS verification code", body(code));
        return new Challenge(true, null);
    }

    /** Seconds until another code may be requested; 0 when allowed. */
    public long resendRemainingSeconds() {
        long elapsed = System.currentTimeMillis() - sentAt;
        long remaining = RESEND_SECONDS * 1000L - elapsed;
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }

    public boolean hasPendingCode() {
        return code != null && System.currentTimeMillis() < expiresAt;
    }

    public String pendingEmail() {
        return email;
    }

    /**
     * Checks a code typed by the user. A wrong guess consumes one attempt; the
     * fifth wrong guess discards the code so a new one must be requested.
     */
    public void verify(String address, String input) throws VerificationException {
        String target = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (code == null || email == null) {
            throw new VerificationException("Request a verification code first.");
        }
        if (!email.equals(target)) {
            throw new VerificationException("The e-mail address changed. Send a new code.");
        }
        if (System.currentTimeMillis() >= expiresAt) {
            clear();
            throw new VerificationException("That code has expired. Request a new one.");
        }
        String typed = input == null ? "" : input.trim();
        if (!typed.matches("\\d{" + CODE_LENGTH + "}")) {
            throw new VerificationException(
                    "Enter the " + CODE_LENGTH + "-digit code from the e-mail.");
        }
        if (!typed.equals(code)) {
            attempts++;
            if (attempts >= MAX_ATTEMPTS) {
                clear();
                throw new VerificationException(
                        "Too many incorrect codes. Request a new one.");
            }
            int left = MAX_ATTEMPTS - attempts;
            throw new VerificationException("Incorrect code. " + left
                    + (left == 1 ? " attempt" : " attempts") + " left.");
        }
        clear();
    }

    /** Drops the pending code, e.g. when the address field is edited. */
    public void clear() {
        code = null;
        email = null;
        expiresAt = 0;
        attempts = 0;
    }

    private static String generateCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }

    private static String body(String code) {
        return "<div style=\"font-family:Segoe UI,Arial,sans-serif;color:#1f2933\">"
                + "<h2 style=\"color:#0E6B34;margin:0 0 8px\">DEIHS Maintenance Management System</h2>"
                + "<p>Use the code below to confirm this e-mail address.</p>"
                + "<p style=\"font-size:30px;letter-spacing:8px;font-weight:bold;"
                + "background:#F4F7F4;padding:14px 20px;border-radius:10px;"
                + "display:inline-block;color:#0A4A24\">" + code + "</p>"
                + "<p style=\"color:#6B7785;font-size:12px\">The code expires in "
                + VALID_MINUTES + " minutes. If you did not request it, you can ignore this message.</p>"
                + "</div>";
    }
}
