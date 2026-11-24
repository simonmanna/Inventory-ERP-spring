package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;

/**
 * Evaluates a measured value against the specification limits of a check snapshot (ADR-S087 §1): {@code toleranceMin}
 * is the lower and {@code toleranceMax} the upper specification limit (absolute values, inclusive). A value with more
 * decimals than the point allows is refused (400), not failed.
 */
public final class MeasureEvaluator {

    private MeasureEvaluator() {}

    /** Outcome of one measured value. */
    public record Outcome(boolean passed, String reason) {}

    public static Outcome evaluate(BigDecimal value, BigDecimal min, BigDecimal max, Integer decimals, String label) {
        if (value == null) {
            throw ApiException.badRequest(label + " is required.");
        }
        if (decimals != null && Math.max(0, value.stripTrailingZeros().scale()) > decimals) {
            throw ApiException.badRequest(label + " has more than " + decimals + " decimals.");
        }
        if (min != null && value.compareTo(min) < 0) {
            return new Outcome(false, "below minimum " + min.stripTrailingZeros().toPlainString());
        }
        if (max != null && value.compareTo(max) > 0) {
            return new Outcome(false, "above maximum " + max.stripTrailingZeros().toPlainString());
        }
        return new Outcome(true, null);
    }
}
