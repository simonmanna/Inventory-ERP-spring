package com.inventoryerp.procurement.application;

import java.util.Set;

/**
 * Proof that goods on a receipt were weighed (ADR-PROCUREMENT-001 wave 6). Declared here and implemented by the fleet
 * module (weighbridge tickets), so purchasing never depends on fleet. Used by the {@code receiving.weighbridgeRequired}
 * policy when a goods receipt is posted.
 */
public interface ReceiptEvidenceProvider {

    /** Lines of the goods receipt (current tenant) that carry a completed weighbridge ticket. */
    Set<String> weighedLines(String goodsReceiptId);
}
