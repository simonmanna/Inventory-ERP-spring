package com.inventoryerp.fleet.application;

/** A weighbridge indicator could not deliver a stable weight (timeout, motion, overload, unreachable, bad frames). */
public class WeighbridgeReadException extends RuntimeException {

    /** Why the read failed: {@code timeout}, {@code unstable}, {@code overload}, {@code unreachable}, {@code format}. */
    private final String reason;

    public WeighbridgeReadException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public WeighbridgeReadException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
