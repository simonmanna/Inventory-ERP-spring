package com.inventoryerp.license.domain;

/** Whether an activation still occupies one of the licence's instance slots. */
public enum ActivationStatus {

    ACTIVE("active"),
    DEACTIVATED("deactivated");

    private final String value;

    ActivationStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ActivationStatus of(String value) {
        for (ActivationStatus s : values()) {
            if (s.value.equalsIgnoreCase(value) || s.name().equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown activation status '" + value + "'");
    }
}
