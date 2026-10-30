package com.inventoryerp.invoicing.application;

import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Matches vendor-bill lines against open (received-but-unbilled) goods receipts (port of
 * {@code bill-receipt-matcher.service.ts}). The cursor is {@code GoodsReceiptLine.billedQuantity}: each posted bill
 * consumes open receipt quantity oldest-first and stamps what it took.
 */
@Service
public class BillReceiptMatcherService {

    /** One consumed receipt line. */
    public record Consumption(String goodsReceiptLineId, String purchaseOrderLineId, BigDecimal quantity,
            boolean vouchered, BigDecimal receiptUnitCost) {}

    /** How a bill line splits across vouchered / accrued / unmatched quantity. */
    public record BillLineMatch(BigDecimal voucheredQuantity, BigDecimal accruedQuantity, BigDecimal unmatchedQuantity,
            List<Consumption> consumptions, BigDecimal accruedReceiptValue, String locationId) {}

    private final JdbcClient jdbc;

    public BillReceiptMatcherService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Read-only split of a bill line against posted receipts of the same supplier + product. */
    public BillLineMatch matchLine(String organizationId, String partnerId, String productId, BigDecimal quantity) {
        return matchLine(organizationId, partnerId, productId, quantity, null);
    }

    /**
     * Unit-aware split (ADR-S003). When the bill line and a receipt line both carry a unit snapshot, quantities are
     * compared in base units: billing 1 Box (24) consumes 24 Piece of an open receipt. Consumed quantities are
     * stamped in each receipt line's own unit; the returned vouchered/accrued/unmatched quantities are in bill-line
     * units. Lines without a snapshot compare 1:1 like before V5.
     */
    public BillLineMatch matchLine(String organizationId, String partnerId, String productId, BigDecimal quantity,
            BigDecimal billUomFactor) {
        BigDecimal wanted = Money.dec(quantity);
        if (wanted.signum() <= 0 || partnerId == null) {
            return new BillLineMatch(Money.ZERO, Money.ZERO, wanted, List.of(), Money.ZERO, null);
        }
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT l.id, l.\"purchaseOrderLineId\", l.quantity,"
                        + " l.\"billedQuantity\", l.\"unitCost\", l.\"uomFactor\", r.\"purchaseOrderId\", r.\"warehouseId\" FROM \"GoodsReceiptLine\" l"
                        + " JOIN \"GoodsReceiptNote\" r ON r.id = l.\"goodsReceiptId\" WHERE l.\"organizationId\" = ?"
                        + " AND l.\"productId\" = ? AND r.\"organizationId\" = ? AND r.status = 'posted' AND r.\"partnerId\" = ?"
                        + " ORDER BY r.\"receivedAt\" ASC, l.\"lineNumber\" ASC, l.id ASC FOR UPDATE OF l")
                .params(organizationId, productId, organizationId, partnerId));
        // P1-7: the open receipt lines are locked, so two bills posting at once cannot both consume the same quantity.
        BigDecimal remaining = wanted;
        BigDecimal vouchered = Money.ZERO;
        BigDecimal accrued = Money.ZERO;
        BigDecimal accruedValue = Money.ZERO;
        List<Consumption> consumptions = new ArrayList<>();
        String locationId = null;
        for (Map<String, Object> line : lines) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal open = Money.dec(line.get("quantity")).subtract(Money.dec(line.get("billedQuantity")));
            if (open.signum() <= 0) {
                continue;
            }
            // ratio = receipt-line units per bill-line unit (1 when either side predates unit snapshots).
            BigDecimal receiptFactor = line.get("uomFactor") == null ? null : Money.dec(line.get("uomFactor"));
            BigDecimal ratio = billUomFactor == null || receiptFactor == null || receiptFactor.signum() <= 0
                    ? BigDecimal.ONE : Money.div(billUomFactor, receiptFactor);
            BigDecimal openInBill = Money.div(open, ratio);
            BigDecimal takeInBill = Money.min(openInBill, remaining);
            BigDecimal take = takeInBill.compareTo(openInBill) == 0 ? open : takeInBill.multiply(ratio);
            boolean isVouchered = line.get("purchaseOrderId") != null;
            BigDecimal unitCost = Money.dec(line.get("unitCost"));
            consumptions.add(new Consumption((String) line.get("id"), (String) line.get("purchaseOrderLineId"), take,
                    isVouchered, unitCost));
            if (isVouchered) {
                vouchered = vouchered.add(takeInBill);
            } else {
                accrued = accrued.add(takeInBill);
                accruedValue = accruedValue.add(unitCost.multiply(take));
            }
            if (locationId == null && line.get("warehouseId") != null) {
                locationId = (String) line.get("warehouseId");
            }
            remaining = remaining.subtract(takeInBill);
        }
        return new BillLineMatch(vouchered, accrued, remaining.signum() <= 0 ? Money.ZERO : remaining, consumptions,
                accruedValue, locationId);
    }

    /** Stamp consumed quantity onto receipt (and PO) lines and record the claim. */
    public void consume(String organizationId, String vendorBillId, String documentLineId, BillLineMatch match) {
        for (Consumption c : match.consumptions()) {
            jdbc.sql("UPDATE \"GoodsReceiptLine\" SET \"billedQuantity\" = \"billedQuantity\" + ? WHERE id = ?")
                    .params(c.quantity(), c.goodsReceiptLineId()).update();
            if (c.purchaseOrderLineId() != null) {
                jdbc.sql("UPDATE \"PurchaseOrderLine\" SET \"billedQuantity\" = \"billedQuantity\" + ? WHERE id = ?")
                        .params(c.quantity(), c.purchaseOrderLineId()).update();
            }
            jdbc.sql("INSERT INTO \"VendorBillReceiptMatch\" (id, \"organizationId\", \"vendorBillId\", \"documentLineId\","
                            + " \"goodsReceiptLineId\", quantity, vouchered) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), organizationId, vendorBillId, documentLineId,
                            c.goodsReceiptLineId(), c.quantity(), c.vouchered())
                    .update();
        }
    }

    /** Give back everything a bill claimed (bill cancel). */
    public void releaseForBill(String organizationId, String vendorBillId) {
        for (Map<String, Object> c : SqlRows.list(jdbc.sql("SELECT m.\"goodsReceiptLineId\", m.quantity,"
                        + " l.\"purchaseOrderLineId\" FROM \"VendorBillReceiptMatch\" m LEFT JOIN \"GoodsReceiptLine\" l"
                        + " ON l.id = m.\"goodsReceiptLineId\" WHERE m.\"organizationId\" = ? AND m.\"vendorBillId\" = ?")
                .params(organizationId, vendorBillId))) {
            jdbc.sql("UPDATE \"GoodsReceiptLine\" SET \"billedQuantity\" = \"billedQuantity\" - ? WHERE id = ?")
                    .params(c.get("quantity"), c.get("goodsReceiptLineId")).update();
            if (c.get("purchaseOrderLineId") != null) {
                jdbc.sql("UPDATE \"PurchaseOrderLine\" SET \"billedQuantity\" = \"billedQuantity\" - ? WHERE id = ?")
                        .params(c.get("quantity"), c.get("purchaseOrderLineId")).update();
            }
        }
        jdbc.sql("DELETE FROM \"VendorBillReceiptMatch\" WHERE \"organizationId\" = ? AND \"vendorBillId\" = ?")
                .params(organizationId, vendorBillId).update();
    }
}
