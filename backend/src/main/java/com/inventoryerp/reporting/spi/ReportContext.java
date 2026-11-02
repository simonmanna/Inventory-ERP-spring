package com.inventoryerp.reporting.spi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * What a provider receives: the request with the branch data scope applied and option defaults filled, the current
 * column period and the comparative periods in request order (budget and variance columns are flags, not periods).
 */
public record ReportContext(String organizationId, ReportDefinition definition, ReportRequest request, ZoneId zone,
        ReportPeriod current, List<ReportPeriod> comparatives, boolean variance, boolean variancePct, boolean budget,
        LocalDate today, LocalDate fiscalStart) {

    /** First instant of a business date in the organization's zone. */
    public Instant start(LocalDate d) {
        return d.atStartOfDay(zone).toInstant();
    }

    /** First instant after a business date (exclusive upper bound). */
    public Instant endExclusive(LocalDate d) {
        return d.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** The as-of date of the current column (point-in-time reports, or the end of a range). */
    public LocalDate asOf() {
        return current.to();
    }
}
