package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Open purchase order lines as supply (ordered − received, in base units through the line unit factor), dated by
 * the order's expected delivery date. Same open-status set as the MRP planner.
 */
@Component
public class PurchaseSupplyProvider implements SupplyProvider {

    private final SalesDb db;

    public PurchaseSupplyProvider(SalesDb db) {
        this.db = db;
    }

    @Override
    public String type() {
        return "purchase_order_line";
    }

    @Override
    public List<Supply> supply(String productId) {
        List<Supply> out = new ArrayList<>();
        for (Map<String, Object> r : db.rows("""
                SELECT l.id, o."orderNumber" AS number, GREATEST(l.quantity - l."receivedQuantity", 0)
                       * COALESCE(l."uomFactor", 1) AS qty, o."expectedDeliveryDate"::date AS expected
                  FROM "PurchaseOrderLine" l JOIN "PurchaseOrder" o ON o.id = l."purchaseOrderId"
                 WHERE l."organizationId" = ? AND l."productId" = ? AND o."deletedAt" IS NULL
                   AND o.status IN ('draft', 'submitted', 'approved', 'active', 'sent', 'acknowledged', 'partially_received')
                   AND l.quantity > l."receivedQuantity"
                 ORDER BY o."expectedDeliveryDate" NULLS LAST, o."createdAt\"""", org(), productId)) {
            out.add(new Supply(type(), str(r.get("id")), str(r.get("number")), dec(r.get("qty")),
                    r.get("expected") instanceof LocalDate d ? d : null));
        }
        return out;
    }
}
