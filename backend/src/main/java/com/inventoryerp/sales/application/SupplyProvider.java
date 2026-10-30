package com.inventoryerp.sales.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Expected supply of a product for available-to-promise (ADR-S011 S4). Purchase supply ships with sales
 * ({@link PurchaseSupplyProvider}); production supply is added by the manufacturing integration as another bean —
 * until then ATP reports production as "not connected" and never promises from it.
 */
public interface SupplyProvider {

    /** Supply type key, e.g. {@code purchase_order_line}, {@code production}. */
    String type();

    /** Open supply in base units, earliest first. Already-received quantities are on hand, not supply. */
    List<Supply> supply(String productId);

    /** One expected receipt: base quantity still open, expected date (null = unknown), document id / number. */
    record Supply(String type, String id, String number, BigDecimal quantity, LocalDate expectedDate) {}
}
