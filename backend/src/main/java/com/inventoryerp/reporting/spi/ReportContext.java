package com.inventoryerp.reporting.spi;

import java.time.ZoneId;
import java.util.List;

/**
 * What a provider receives: the request with the branch data scope applied, the current column period and the
 * comparative periods in request order (variance columns are flags, not periods).
 */
public record ReportContext(String organizationId, ReportDefinition definition, ReportRequest request, ZoneId zone,
        ReportPeriod current, List<ReportPeriod> comparatives, boolean variance, boolean variancePct) {}
