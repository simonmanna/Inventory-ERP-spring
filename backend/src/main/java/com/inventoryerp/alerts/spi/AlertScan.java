package com.inventoryerp.alerts.spi;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One scan of one alert type.
 *
 * @param organizationId the organization being scanned (also the tenant in scope)
 * @param asOf today in the business time zone
 * @param threshold the rule's threshold, or the catalog default; null for types without one
 */
public record AlertScan(String organizationId, LocalDate asOf, BigDecimal threshold) {}
