package com.inventoryerp.reporting.spi;

import java.util.List;
import java.util.Map;

/**
 * What a provider returns; the engine adds the header (company, period, generated at / by, run id and hash).
 *
 * @param rows tree flattened in display order; {@link Row#depth()} gives the level
 * @param checks integrity checks (statement balances, ties to the GL, unmapped accounts)
 */
public record ReportBody(List<Column> columns, List<Row> rows, Map<String, Object> totals, List<Check> checks,
        Map<String, Object> meta) {

    /**
     * @param type {@code text}, {@code date}, {@code money}, {@code number} or {@code percent}
     */
    public record Column(String key, String label, String type) {}

    /**
     * @param kind {@code header}, {@code line}, {@code account}, {@code subtotal}, {@code total} or {@code kpi}
     * @param drill column key → where a click on that cell goes; {@code _row} for the whole row
     */
    public record Row(String id, String kind, int depth, boolean bold, Map<String, Object> values,
            Map<String, Drill> drill) {}

    /** Either another engine report with parameters (drill-down) or a web route (drill-through to a document). */
    public record Drill(String reportId, Map<String, Object> params, String route) {

        public static Drill report(String reportId, Map<String, Object> params) {
            return new Drill(reportId, params, null);
        }

        public static Drill route(String route) {
            return new Drill(null, null, route);
        }
    }

    public record Check(String code, String label, boolean ok, String detail) {}
}
