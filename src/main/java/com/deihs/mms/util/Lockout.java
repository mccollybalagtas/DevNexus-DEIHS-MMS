package com.deihs.mms.util;

/**
 * A small, UI-agnostic throttle for the sign-in and registration forms.
 *
 * <p>It counts consecutive failures and, once the limit is reached, refuses
 * further attempts for a fixed number of seconds. The caller owns the timer
 * that repaints a countdown; this class only answers "may I try?" and "how
 * long is left?".</p>
 *
 * <p>Kept per screen on purpose: a lockout is a speed bump against a person
 * typing at one window, not a server-side account ban (which would let one
 * person lock another out of their account).</p>
 */
public final class Lockout {

    public static final int DEFAULT_MAX_FAILURES = 5;
    public static final int DEFAULT_LOCK_SECONDS = 20;

    private final int maxFailures;
    private final long lockMillis;

    private int failures;
    private long lockedUntil;

    public Lockout() {
        this(DEFAULT_MAX_FAILURES, DEFAULT_LOCK_SECONDS);
    }

    public Lockout(int maxFailures, int lockSeconds) {
        this.maxFailures = Math.max(1, maxFailures);
        this.lockMillis = Math.max(1, lockSeconds) * 1000L;
    }

    public boolean isLocked() {
        return System.currentTimeMillis() < lockedUntil;
    }

    /** Whole seconds still to wait, rounded up; 0 when unlocked. */
    public long remainingSeconds() {
        long remaining = lockedUntil - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }

    /** Records one failed attempt and starts the lockout when the limit is hit. */
    public void registerFailure() {
        if (isLocked()) {
            return;
        }
        failures++;
        if (failures >= maxFailures) {
            lockNow();
        }
    }

    /** Starts the lockout immediately (used for an over-length password). */
    public void lockNow() {
        failures = 0;
        lockedUntil = System.currentTimeMillis() + lockMillis;
    }

    /** Clears both the failure count and any active lockout. */
    public void reset() {
        failures = 0;
        lockedUntil = 0;
    }

    public int failures() {
        return failures;
    }

    public int maxFailures() {
        return maxFailures;
    }
}
