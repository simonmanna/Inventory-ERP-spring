package com.inventoryerp.reporting.spi;

import java.util.List;

/**
 * A report-specific parameter beyond the shared filters (aging basis, bucket days, budget, density, loss rates...).
 * Sent as {@code options.<key>} in the request; the engine fills the default and refuses unknown keys.
 *
 * @param type {@code text}, {@code number}, {@code select}, {@code date} or {@code budget} (a budget id)
 * @param choices allowed values for {@code select}
 */
public record OptionSpec(String key, String label, String type, List<String> choices, String defaultValue) {

    public OptionSpec {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public static OptionSpec number(String key, String label, String defaultValue) {
        return new OptionSpec(key, label, "number", null, defaultValue);
    }

    public static OptionSpec select(String key, String label, String defaultValue, String... choices) {
        return new OptionSpec(key, label, "select", List.of(choices), defaultValue);
    }

    public static OptionSpec text(String key, String label, String defaultValue) {
        return new OptionSpec(key, label, "text", null, defaultValue);
    }
}
