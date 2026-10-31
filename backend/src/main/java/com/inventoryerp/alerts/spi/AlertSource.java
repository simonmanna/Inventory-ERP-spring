package com.inventoryerp.alerts.spi;

import java.util.List;

/**
 * One module's contribution to an alert type. Called once per scan in the organization's tenant scope, so
 * {@code TenantContext.organizationId()} is set; an implementation must not write, and must not notify anyone itself.
 * A type may have several sources (expiring documents come from fleet, quarry and hr). A source that throws loses its
 * own items for that scan, never the other sources.
 */
public interface AlertSource {

    /** One of the {@link AlertTypes} constants. */
    String alertType();

    /** What is true right now, at the rule's threshold. An empty list is the normal case. */
    List<AlertItem> collect(AlertScan scan);
}
