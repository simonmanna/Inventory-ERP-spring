package com.inventoryerp.kernel.licensing;

/**
 * What this installation is allowed to do right now.
 *
 * <p>Derived from the licence's dates on every request (cheaply, from a cached snapshot) rather than
 * stored as the truth: a licence expires by the passage of time, with nobody there to write a row.
 *
 * @see LicenseStateMachine
 */
public enum LicenseMode {

    /** No licence has ever been activated and the trial window has run out. Read-only. */
    UNLICENSED(false),

    /** No licence yet, but still inside the trial window. Fully usable. */
    TRIAL(true),

    /** Licensed and inside the term. */
    ACTIVE(true),

    /** Past the term but inside the grace window: everything still works, loudly. */
    GRACE(true),

    /** Grace is over. Reads succeed, writes are refused with 402. */
    READ_ONLY(false),

    /** The authority ended this licence. Read-only immediately, with no grace. */
    REVOKED(false);

    private final boolean writable;

    LicenseMode(boolean writable) {
        this.writable = writable;
    }

    /** May this installation accept a write? */
    public boolean allowsWrites() {
        return writable;
    }

    /** Should the UI be warning about the licence, whether or not writes still work? */
    public boolean warrantsWarning() {
        return this != ACTIVE;
    }
}
