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

    /**
     * Sales readiness R4d (DEL-7): how far (percent) a dispatch may go over a source line's quantity (weighed or
     * loaded goods). Default none.
     */
    /**
     * Sales readiness DEL-8: refuse to issue stock that is held by a firm reservation of another source line. Called
     * inside the issuing transaction, before the stock leaves; {@code baseQty} is in the product's base unit.
     */
    default void assertIssuable(String sourceLineId, String productId, String variantId, String locationId,
            BigDecimal baseQty) {}

    /**
     * Sales readiness DEL-9b: where goods the customer rejected at delivery come back. Default: the dispatch location
     * (back into saleable stock).
     */
    default String rejectedGoodsLocation(String dispatchLocationId) {
        return dispatchLocationId;
    }

    /**
     * Sales readiness DEL-9a: the account the cost of goods damaged or missing at delivery moves to (out of cost of
     * goods sold), or null to leave it in cost of goods sold.
     */
    default String deliveryLossAccount() {
        return null;
    }

    default BigDecimal overDeliveryTolerancePct(String sourceLineId) {
        return BigDecimal.ZERO;
    }

    record Source(String number, String partnerId, String branchId, String locationId, String siteId,
            LocalDate requestedDate, List<SourceLine> lines) {}

    record SourceLine(String sourceLineId, String productId, String description, String uomId, BigDecimal uomFactor,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPercent, String taxId, String variantId) {

        public SourceLine(String sourceLineId, String productId, String description, String uomId, BigDecimal uomFactor,
                BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPercent, String taxId) {
            this(sourceLineId, productId, description, uomId, uomFactor, quantity, unitPrice, discountPercent, taxId,
                    null);
        }
    }
}
