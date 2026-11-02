package com.inventoryerp.reporting.spi;

import java.util.List;

/**
 * One report in the library.
 *
 * @param id stable id ({@code fs.profit-and-loss})
 * @param level {@code FINANCIAL} (posted GL), {@code OPERATIONAL} (documents) or {@code MANAGEMENT} (measures)
 * @param permission permission the caller must hold to see and run it
 * @param engine true when {@code POST /reports/run/{id}} runs it; false for a catalogue link to its own screen
 * @param webRoute the report's own screen (links) or {@code null} for the generic viewer
 * @param apiRoute the report's own API route (links), for reference
 * @param options report-specific parameters
 */
public record ReportDefinition(String id, ReportCategory category, String title, String description, String level,
        String permission, List<FilterKey> filters, boolean engine, String webRoute, String apiRoute,
        List<OptionSpec> options) {

    public ReportDefinition {
        filters = filters == null ? List.of() : List.copyOf(filters);
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** An engine report. */
    public static ReportDefinition of(String id, ReportCategory category, String title, String description,
            String level, String permission, List<FilterKey> filters, OptionSpec... options) {
        return new ReportDefinition(id, category, title, description, level, permission, filters, true, null, null,
                List.of(options));
    }

    public boolean accepts(FilterKey key) {
        return filters.contains(key);
    }
}
