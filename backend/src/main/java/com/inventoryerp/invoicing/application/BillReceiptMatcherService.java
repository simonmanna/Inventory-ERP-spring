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
        BigDecimal wanted = Money.dec(quantity);
        if (wanted.signum() <= 0 || partnerId == null) {
            return new BillLineMatch(Money.ZERO, Money.ZERO, wanted, List.of(), Money.ZERO, null);
        }
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT l.id, l.\"purchaseOrderLineId\", l.quantity,"
                        + " l.\"billedQuantity\", l.\"unitCost\", r.\"purchaseOrderId\", r.\"warehouseId\" FROM \"GoodsReceiptLine\" l"
                        + " JOIN \"GoodsReceiptNote\" r ON r.id = l.\"goodsReceiptId\" WHERE l.\"organizationId\" = ?"
                        + " AND l.\"productId\" = ? AND r.\"organizationId\" = ? AND r.status = 'posted' AND r.\"partnerId\" = ?"
                        + " ORDER BY r.\"receivedAt\" ASC, l.\"lineNumber\" ASC")
                .params(organizationId, productId, organizationId, partnerId));
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
            BigDecimal take = Money.min(open, remaining);
            boolean isVouchered = line.get("purchaseOrderId") != null;
            BigDecimal unitCost = Money.dec(line.get("unitCost"));
            consumptions.add(new Consumption((String) line.get("id"), (String) line.get("purchaseOrderLineId"), take,
                    isVouchered, unitCost));
            if (isVouchered) {
                vouchered = vouchered.add(take);
            } else {
                accrued = accrued.add(take);
                accruedValue = accruedValue.add(unitCost.multiply(take));
            }
            if (locationId == null && line.get("warehouseId") != null) {
                locationId = (String) line.get("warehouseId");
            }
            remaining = remaining.subtract(take);
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
