package com.inventoryerp.license.domain;

/**
 * Who may do what in the admin API.
 *
 * <p>{@code support} can see everything and move an activation (the day-to-day of helping a
 * customer onto new hardware), but may not change what is sold or who holds the keys: issuing,
 * revoking, editing plans and rotating signing keys are {@code owner} only.
 */
public enum AdminRole {

    OWNER("owner"),
    SUPPORT("support");

    private final String value;

    AdminRole(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Spring Security authority name. */
    public String authority() {
        return "ROLE_" + name();
    }

    public static AdminRole of(String value) {
        for (AdminRole r : values()) {
            if (r.value.equalsIgnoreCase(value) || r.name().equalsIgnoreCase(value)) {
                return r;
            }
        }
        throw new IllegalArgumentException("Unknown admin role '" + value + "'");
    }
}
