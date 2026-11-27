package com.inventoryerp.invoicing.application;

import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.core.application.UomConversionService;
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
    private final UomConversionService units;

    public BillReceiptMatcherService(JdbcClient jdbc, UomConversionService units) {
        this.jdbc = jdbc;
        this.units = units;
    }

    /** Read-only split of a bill line against posted receipts of the same supplier + product. */
    public BillLineMatch matchLine(String organizationId, String partnerId, String productId, BigDecimal quantity) {
        return matchLine(organizationId, partnerId, productId, null, quantity, null);
    }

    /**
     * Unit-aware split (ADR-S003). When the bill line and a receipt line both carry a unit snapshot, quantities are
     * compared in base units: billing 1 Box (24) consumes 24 Piece of an open receipt. Consumed quantities are
     * stamped in each receipt line's own unit; the returned vouchered/accrued/unmatched quantities are in bill-line
     * units. Phase I1b (audit U-2): a side without a snapshot (a pre-V5 bill or receipt line) is converted with the
     * live factor of its unit — the receipt line's unit, else the product's purchase unit, which is how pre-V5 lines
     * were received and billed — instead of being compared 1:1. Phase I2 review N4: only receipt lines of the bill
     * line's variant are consumed (a line without a variant matches receipt lines without one), so a bill for one
     * variant never vouchers another variant's receipt; the unmatched remainder is auto-received as that variant.
     */
    public BillLineMatch matchLine(String organizationId, String partnerId, String productId, String variantId,
            BigDecimal quantity, BigDecimal billUomFactor) {
        return matchLine(organizationId, partnerId, productId, variantId, quantity, billUomFactor, null);
    }

    /**
     * Purchase readiness B3 (2026-11-27): {@code claimed} carries the receipt quantity (receipt-line units, by receipt
     * line id) that earlier lines of the same bill already matched but have not stamped yet. It is subtracted from
     * each receipt line's open quantity, and this line's consumptions are added to it, so two lines of one bill for the
     * same product never consume the same open receipt quantity twice.
     */
    public BillLineMatch matchLine(String organizationId, String partnerId, String productId, String variantId,
            BigDecimal quantity, BigDecimal billUomFactor, Map<String, BigDecimal> claimed) {
        BigDecimal wanted = Money.dec(quantity);
        if (wanted.signum() <= 0 || partnerId == null) {
            return new BillLineMatch(Money.ZERO, Money.ZERO, wanted, List.of(), Money.ZERO, null);
        }
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT l.id, l.\"purchaseOrderLineId\", l.quantity,"
                        + " l.\"billedQuantity\", l.\"unitCost\", l.\"uomFactor\", l.\"uomId\", r.\"purchaseOrderId\", r.\"warehouseId\" FROM \"GoodsReceiptLine\" l"
                        + " JOIN \"GoodsReceiptNote\" r ON r.id = l.\"goodsReceiptId\" WHERE l.\"organizationId\" = ?"
                        + " AND l.\"productId\" = ? AND l.\"variantId\" IS NOT DISTINCT FROM ?"
                        + " AND r.\"organizationId\" = ? AND r.status = 'posted' AND r.\"partnerId\" = ?"
                        + " ORDER BY r.\"receivedAt\" ASC, l.\"lineNumber\" ASC, l.id ASC FOR UPDATE OF l")
                .params(organizationId, productId, variantId, organizationId, partnerId));
        // P1-7: the open receipt lines are locked, so two bills posting at once cannot both consume the same quantity.
        BigDecimal billFactor = billUomFactor != null && billUomFactor.signum() > 0 ? billUomFactor
                : legacyFactor(organizationId, productId);
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
            if (claimed != null) {
                open = open.subtract(claimed.getOrDefault((String) line.get("id"), Money.ZERO));
            }
            if (open.signum() <= 0) {
                continue;
            }
            // ratio = receipt-line units per bill-line unit, in base units on both sides.
            BigDecimal receiptFactor = line.get("uomFactor") != null && Money.dec(line.get("uomFactor")).signum() > 0
                    ? Money.dec(line.get("uomFactor"))
                    : line.get("uomId") != null ? units.factorToBase(productId, (String) line.get("uomId"))
                            : legacyFactor(organizationId, productId);
            // Multiply before dividing so whole units stay exact (24 Piece of a Box-24 line is exactly 1 Box).
            BigDecimal openInBill = Money.div(open.multiply(receiptFactor), billFactor);
            BigDecimal takeInBill = Money.min(openInBill, remaining);
            BigDecimal take = takeInBill.compareTo(openInBill) == 0 ? open
                    : Money.div(takeInBill.multiply(billFactor), receiptFactor);
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
        if (claimed != null) {
            for (Consumption c : consumptions) {
                claimed.merge(c.goodsReceiptLineId(), c.quantity(), BigDecimal::add);
            }
        }
        return new BillLineMatch(vouchered, accrued, remaining.signum() <= 0 ? Money.ZERO : remaining, consumptions,
                accruedValue, locationId);
    }

    /** Base units per 1 purchase unit of {@code productId} (1 when it has none): the unit pre-V5 lines used. */
    private BigDecimal legacyFactor(String organizationId, String productId) {
        String purchaseUomId = jdbc.sql("SELECT \"purchaseUomId\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(productId, organizationId).query(String.class).optional().orElse(null);
        return units.factorToBase(productId, purchaseUomId);
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

    /**
     * Purchase readiness H1 (2026-11-27): serialise bill posts and goods-receipt posts of one supplier, so a bill that
     * auto-receives stock and a receipt that would absorb it never pass each other unseen. Transaction-scoped.
     */
    public void lockSupplier(String organizationId, String partnerId) {
        if (partnerId == null) {
            return;
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("bill-receipt:" + organizationId + ":"
                + partnerId).query().listOfRows();
    }

    /**
     * Purchase readiness H1: record what a bill line received into stock itself (auto-receive), in base units, so a
     * later goods receipt of the same supplier, product and variant absorbs it instead of receiving it again.
     */
    public void recordAutoReceipt(String organizationId, String vendorBillId, String documentLineId, String partnerId,
            String productId, String variantId, BigDecimal quantity, BigDecimal billUomFactor) {
        if (partnerId == null || quantity == null || quantity.signum() <= 0) {
            return;
        }
        BigDecimal factor = billUomFactor != null && billUomFactor.signum() > 0 ? billUomFactor
                : legacyFactor(organizationId, productId);
        BigDecimal base = quantity.multiply(factor).setScale(6, java.math.RoundingMode.HALF_UP);
        if (base.signum() <= 0) {
            return;
        }
        jdbc.sql("INSERT INTO \"VendorBillAutoReceipt\" (id, \"organizationId\", \"vendorBillId\", \"documentLineId\","
                        + " \"partnerId\", \"productId\", \"variantId\", \"baseQuantity\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), organizationId, vendorBillId, documentLineId, partnerId, productId,
                        variantId, base)
                .update();
    }

    /**
     * Purchase readiness H1: a goods-receipt line being posted absorbs what posted bills of its supplier already
     * received into stock for the same product and variant (oldest first). The absorbed quantity is billed on the
     * receipt line (and its PO line) and recorded as a bill-receipt match, so it is neither stocked nor vouchered a
     * second time and later bills do not match it again. Call {@link #lockSupplier} first.
     *
     * @return the absorbed quantity in the receipt line's own unit (0 when nothing was auto-received)
     */
    public BigDecimal absorbAutoReceived(String organizationId, String partnerId, Map<String, Object> receiptLine) {
        String productId = (String) receiptLine.get("productId");
        BigDecimal qty = Money.dec(receiptLine.get("quantity"));
        if (partnerId == null || productId == null || qty.signum() <= 0) {
            return Money.ZERO;
        }
        List<Map<String, Object>> open = SqlRows.list(jdbc.sql("SELECT a.id, a.\"vendorBillId\", a.\"documentLineId\","
                        + " a.\"baseQuantity\" - a.\"absorbedBaseQuantity\" AS open FROM \"VendorBillAutoReceipt\" a JOIN"
                        + " \"Document\" d ON d.id = a.\"vendorBillId\" WHERE a.\"organizationId\" = ? AND a.\"partnerId\" = ?"
                        + " AND a.\"productId\" = ? AND a.\"variantId\" IS NOT DISTINCT FROM ? AND a.\"absorbedBaseQuantity\""
                        + " < a.\"baseQuantity\" AND d.status::text IN ('posted', 'paid') ORDER BY a.\"createdAt\", a.id"
                        + " FOR UPDATE OF a")
                .params(organizationId, partnerId, productId, receiptLine.get("variantId")));
        if (open.isEmpty()) {
            return Money.ZERO;
        }
        BigDecimal factor = receiptLine.get("uomFactor") != null && Money.dec(receiptLine.get("uomFactor")).signum() > 0
                ? Money.dec(receiptLine.get("uomFactor"))
                : receiptLine.get("uomId") != null ? units.factorToBase(productId, (String) receiptLine.get("uomId"))
                        : legacyFactor(organizationId, productId);
        BigDecimal remainingBase = qty.multiply(factor);
        BigDecimal absorbed = Money.ZERO;
        for (Map<String, Object> a : open) {
            if (remainingBase.signum() <= 0) {
                break;
            }
            BigDecimal takeBase = Money.min(Money.dec(a.get("open")), remainingBase);
            if (takeBase.signum() <= 0) {
                continue;
            }
            BigDecimal take = takeBase.compareTo(remainingBase) == 0 ? qty.subtract(absorbed)
                    : Money.div(takeBase, factor).setScale(6, java.math.RoundingMode.HALF_UP);
            jdbc.sql("UPDATE \"VendorBillAutoReceipt\" SET \"absorbedBaseQuantity\" = \"absorbedBaseQuantity\" + ? WHERE"
                    + " id = ? AND \"organizationId\" = ?").params(takeBase, a.get("id"), organizationId).update();
            jdbc.sql("UPDATE \"GoodsReceiptLine\" SET \"billedQuantity\" = \"billedQuantity\" + ? WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(take, receiptLine.get("id"), organizationId).update();
            if (receiptLine.get("purchaseOrderLineId") != null) {
                jdbc.sql("UPDATE \"PurchaseOrderLine\" SET \"billedQuantity\" = \"billedQuantity\" + ? WHERE id = ? AND"
                        + " \"organizationId\" = ?").params(take, receiptLine.get("purchaseOrderLineId"), organizationId)
                        .update();
            }
            jdbc.sql("INSERT INTO \"VendorBillReceiptMatch\" (id, \"organizationId\", \"vendorBillId\", \"documentLineId\","
                            + " \"goodsReceiptLineId\", quantity, vouchered) VALUES (?, ?, ?, ?, ?, ?, false)")
                    .params(UUID.randomUUID().toString(), organizationId, a.get("vendorBillId"), a.get("documentLineId"),
                            receiptLine.get("id"), take)
                    .update();
            absorbed = absorbed.add(take);
            remainingBase = remainingBase.subtract(takeBase);
        }
        return absorbed.min(qty);
    }

    /** Purchase readiness H1: the number of a goods receipt that absorbed stock this bill auto-received, or null. */
    public String absorbingReceipt(String organizationId, String vendorBillId) {
        return jdbc.sql("SELECT g.\"receiptNumber\" FROM \"VendorBillAutoReceipt\" a JOIN \"VendorBillReceiptMatch\" m ON"
                        + " m.\"vendorBillId\" = a.\"vendorBillId\" AND m.\"documentLineId\" = a.\"documentLineId\" JOIN"
                        + " \"GoodsReceiptLine\" l ON l.id = m.\"goodsReceiptLineId\" JOIN \"GoodsReceiptNote\" g ON g.id ="
                        + " l.\"goodsReceiptId\" WHERE a.\"organizationId\" = ? AND a.\"vendorBillId\" = ? AND"
                        + " a.\"absorbedBaseQuantity\" > 0 ORDER BY m.\"createdAt\" LIMIT 1")
                .params(organizationId, vendorBillId).query(String.class).optional().orElse(null);
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
