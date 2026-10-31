package com.inventoryerp.fleet.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A document fleet can deliver, provided by the module that owns it (ADR-S011 S5: sales orders). Fleet owns the
 * port, the source module implements it — fleet never depends on the source module.
 */
public interface DeliverySourcePort {

    /** Does this provider own {@code sourceId} of {@code sourceType}? */
    boolean supports(String sourceType, String sourceId);

    /** The source as fleet needs it: header and the open quantity of each line (line unit + factor to base). */
    Source load(String sourceId);

    /** Refuse delivery when the source is not deliverable now (on hold, cancelled, closed). */
    void assertDeliverable(String sourceId);

    record Source(String number, String partnerId, String branchId, String locationId, String siteId,
            LocalDate requestedDate, List<SourceLine> lines) {}

    record SourceLine(String sourceLineId, String productId, String description, String uomId, BigDecimal uomFactor,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPercent, String taxId) {}
}
