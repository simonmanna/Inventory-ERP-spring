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
 */
public record ReportDefinition(String id, ReportCategory category, String title, String description, String level,
        String permission, List<FilterKey> filters, boolean engine, String webRoute, String apiRoute) {

    public ReportDefinition {
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    public boolean accepts(FilterKey key) {
        return filters.contains(key);
    }
}
