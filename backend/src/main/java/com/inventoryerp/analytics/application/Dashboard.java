package com.inventoryerp.analytics.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One dashboard on the wire. The numbers come from the server; keys name the labels the web catalog holds
 * ({@code web/src/pages/analytics/labels.ts}), and {@code format} says how to show a value: tonnes, qty, money, rate
 * (money per unit), percent, count, hours or text. {@code supportedFilters} are the filters this dashboard honours;
 * {@code ignoredFilters} are those sent that it did not apply; {@code notes} are catalog keys of caveats.
 */
public record Dashboard(String domain, LocalDate from, LocalDate to, List<String> supportedFilters,
        List<String> ignoredFilters, List<Tile> tiles, List<Chart> charts, List<Table> tables, List<String> notes) {

    /** A headline figure; {@code drill} is the document list behind it, or null. */
    public record Tile(String key, Object value, String format, String drill) {}

    /** A plotted series; {@code label} is set for data-driven series, else the key names a catalog label. */
    public record Series(String key, String label) {

        public static Series of(String key) {
            return new Series(key, null);
        }
    }

    /** A bar or line chart with one value axis: every series shares {@code format}. */
    public record Chart(String key, String type, String xKey, String format, List<Series> series,
            List<Map<String, Object>> rows) {}

    /** A table column. */
    public record Column(String key, String format) {}

    /** A table; a row opens the {@code drill} list with {@code drillParam} set to the row's {@code rowKey} value. */
    public record Table(String key, List<Column> columns, List<Map<String, Object>> rows, String drill,
            String drillParam, String rowKey) {}

    /** A table without drill-down. */
    public static Table table(String key, List<Column> columns, List<Map<String, Object>> rows) {
        return new Table(key, columns, rows, null, null, null);
    }

    /** Column shorthand: {@code col("netTonnes", "tonnes")}. */
    public static Column col(String key, String format) {
        return new Column(key, format);
    }
}
