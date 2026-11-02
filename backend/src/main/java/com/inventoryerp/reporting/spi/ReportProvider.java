package com.inventoryerp.reporting.spi;

import java.util.List;

/**
 * A module's engine reports. Called in the caller's tenant scope with the permission already checked; an
 * implementation reads only and filters every query by {@link ReportContext#organizationId()} and the branches in
 * {@link ReportContext#request()}.
 */
public interface ReportProvider {

    List<ReportDefinition> definitions();

    ReportBody run(ReportContext context);
}
