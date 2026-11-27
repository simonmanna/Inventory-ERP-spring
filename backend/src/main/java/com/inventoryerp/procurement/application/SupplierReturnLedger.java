package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Purchase readiness fixes (owner-approved 2026-11-27, findings H3 / H4): how much of a received goods-receipt line is
 * already given back to the supplier, so a return to vendor (debit note) and a supplier claim cannot both credit the
 * same received quantity, and a return against a bill cannot exceed what that bill billed.
 *
 * <p>Per receipt line (in its own unit): verified or settled stock-adjustment claim quantities, quantities taken by
 * returns that named no bill ({@code DebitNoteReceiptMatch}), and the share of returns against a bill that the line
 * must carry. A bill's returns are attributed to the bill's matched receipt lines of the product only as far as the
 * other matched lines cannot hold them (the least each line must carry), so a bill covering two receipts of one product
 * does not block claims on both.
 */
@Component
public class SupplierReturnLedger {

    /**
     * Claim quantity lines that hold receipt quantity against a return: verified lines of a live claim, and lines of a
     * claim settled as a stock adjustment (the goods left stock or were written off and the supplier credited them).
     */
    static final String RESERVING_CLAIM = "((c.status IN ('open', 'submitted', 'accepted') AND l.\"verifiedAt\" IS NOT"
            + " NULL) OR (c.status = 'settled' AND c.classification = 'stock_adjustment'))";

    /** One goods-receipt line: quantity in its unit, base units per unit. */
    public record ReceiptLine(String id, String productId, String variantId, BigDecimal quantity, BigDecimal factor) {}

    /** A receipt line a bill matched, with the quantity it matched (receipt-line unit). */
    public record PoolLine(ReceiptLine line, BigDecimal matched) {}

    private static final int SCALE = 6;

    private final JdbcClient jdbc;
    private final UomConversionService units;

    public SupplierReturnLedger(JdbcClient jdbc, UomConversionService units) {
        this.jdbc = jdbc;
        this.units = units;
    }

    /** Base units per one unit of a line: its frozen factor, else its unit's live factor, else the purchase unit's. */
    public BigDecimal factor(String productId, String uomId, Object stored) {
        BigDecimal f = stored == null ? null : Money.dec(stored);
        if (f != null && f.signum() > 0) {
            return f;
        }
        if (productId == null) {
            return BigDecimal.ONE;
        }
        if (uomId != null && !uomId.isBlank()) {
            return units.factorToBase(productId, uomId);
        }
        String purchaseUomId = jdbc.sql("SELECT \"purchaseUomId\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(productId, org()).query(String.class).optional().orElse(null);
        return units.factorToBase(productId, purchaseUomId);
    }

    /** Quantities compared at six decimals (the receipt quantity scale). */
    public static BigDecimal q(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Quantity reserved by claims per receipt line (see {@link #RESERVING_CLAIM}). */
    public Map<String, BigDecimal> reservedByClaims(Collection<String> receiptLineIds) {
        return sumBy("SELECT l.\"goodsReceiptLineId\" AS k, COALESCE(sum(l.quantity), 0) AS v FROM \"SupplierClaimLine\" l"
                + " JOIN \"SupplierClaim\" c ON c.id = l.\"claimId\" WHERE l.\"organizationId\" = ? AND"
                + " l.\"goodsReceiptLineId\" = ANY(?::text[]) AND " + RESERVING_CLAIM + " GROUP BY 1", receiptLineIds);
    }

    /** Quantity taken per receipt line by posted returns that named no bill. */
    public Map<String, BigDecimal> returnedByMatch(Collection<String> receiptLineIds) {
        return sumBy("SELECT m.\"goodsReceiptLineId\" AS k, COALESCE(sum(m.quantity), 0) AS v FROM"
                + " \"DebitNoteReceiptMatch\" m JOIN \"DebitNote\" n ON n.id = m.\"debitNoteId\" WHERE"
                + " m.\"organizationId\" = ? AND m.\"goodsReceiptLineId\" = ANY(?::text[]) AND m.\"releasedAt\" IS NULL"
                + " AND n.status = 'posted' GROUP BY 1", receiptLineIds);
    }

    private Map<String, BigDecimal> sumBy(String sql, Collection<String> ids) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(sql).params(org(), ids.toArray(String[]::new)))) {
            out.put((String) r.get("k"), Money.dec(r.get("v")));
        }
        return out;
    }

    /** Base quantity of a product (and variant) the bill billed on its product lines. */
    public BigDecimal billedBase(String billId, String productId, String variantId) {
        BigDecimal total = Money.ZERO;
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT quantity, \"uomId\", \"uomFactor\" FROM \"DocumentLine\""
                + " WHERE \"documentId\" = ? AND \"organizationId\" = ? AND \"productId\" = ? AND \"variantId\" IS NOT"
                + " DISTINCT FROM ? AND COALESCE(\"lineType\", 'product') = 'product'")
                .params(billId, org(), productId, variantId))) {
            total = total.add(Money.dec(l.get("quantity")).multiply(factor(productId, (String) l.get("uomId"),
                    l.get("uomFactor"))));
        }
        return total;
    }

    /** Base quantity of a product (and variant) returned by posted return notes against the bill. */
    public BigDecimal returnedOnBillBase(String billId, String productId, String variantId, String excludeNoteId) {
        BigDecimal total = Money.ZERO;
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT l.quantity, l.\"uomId\", l.\"uomFactor\" FROM"
                + " \"DebitNoteLine\" l JOIN \"DebitNote\" n ON n.id = l.\"debitNoteId\" WHERE n.\"organizationId\" = ?"
                + " AND n.\"documentId\" = ? AND n.direction = 'inbound' AND n.reason = 'returned_goods' AND"
                + " n.status = 'posted' AND n.\"deletedAt\" IS NULL AND n.id IS DISTINCT FROM ? AND l.\"productId\" = ?"
                + " AND l.\"variantId\" IS NOT DISTINCT FROM ?")
                .params(org(), billId, excludeNoteId, productId, variantId))) {
            total = total.add(Money.dec(l.get("quantity")).multiply(factor(productId, (String) l.get("uomId"),
                    l.get("uomFactor"))));
        }
        return total;
    }

    /** The receipt lines of a product (and variant) the bill matched, oldest first; locked when asked. */
    public List<PoolLine> pool(String billId, String productId, String variantId, boolean lock) {
        List<PoolLine> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT gl.id, gl.\"productId\", gl.\"variantId\","
                + " gl.quantity, gl.\"uomId\", gl.\"uomFactor\", m.matched FROM (SELECT \"goodsReceiptLineId\","
                + " sum(quantity) AS matched FROM \"VendorBillReceiptMatch\" WHERE \"organizationId\" = ? AND"
                + " \"vendorBillId\" = ? GROUP BY 1) m JOIN \"GoodsReceiptLine\" gl ON gl.id = m.\"goodsReceiptLineId\""
                + " JOIN \"GoodsReceiptNote\" g ON g.id = gl.\"goodsReceiptId\" WHERE gl.\"organizationId\" = ? AND"
                + " gl.\"productId\" = ? AND gl.\"variantId\" IS NOT DISTINCT FROM ? ORDER BY g.\"receivedAt\" ASC,"
                + " gl.\"lineNumber\" ASC, gl.id ASC" + (lock ? " FOR UPDATE OF gl" : ""))
                .params(org(), billId, org(), productId, variantId))) {
            out.add(new PoolLine(receiptLine(r), Money.dec(r.get("matched"))));
        }
        return out;
    }

    private ReceiptLine receiptLine(Map<String, Object> r) {
        String productId = (String) r.get("productId");
        return new ReceiptLine((String) r.get("id"), productId, (String) r.get("variantId"), Money.dec(r.get("quantity")),
                factor(productId, (String) r.get("uomId"), r.get("uomFactor")));
    }

    /**
     * Base quantity of a bill's matched receipt lines still physically open to a return against that bill: per line the
     * matched quantity, at most what claims and bill-less returns left of the line.
     */
    public BigDecimal capacityBase(List<PoolLine> pool) {
        List<String> ids = pool.stream().map(p -> p.line().id()).toList();
        Map<String, BigDecimal> claimed = reservedByClaims(ids);
        Map<String, BigDecimal> matched = returnedByMatch(ids);
        BigDecimal total = Money.ZERO;
        for (PoolLine p : pool) {
            total = total.add(capBase(p, claimed, matched));
        }
        return total;
    }

    private static BigDecimal capBase(PoolLine p, Map<String, BigDecimal> claimed, Map<String, BigDecimal> matched) {
        String id = p.line().id();
        BigDecimal free = p.line().quantity().subtract(claimed.getOrDefault(id, Money.ZERO))
                .subtract(matched.getOrDefault(id, Money.ZERO));
        return Money.min(p.matched(), free).max(Money.ZERO).multiply(p.line().factor());
    }

    /**
     * The quantity (receipt-line unit) of a receipt line that returns against the bills which matched it must carry:
     * per bill, what the bill's returns of the product exceed on the bill's other matched lines, at most the line's
     * matched quantity.
     */
    public BigDecimal requiredByBillReturns(String receiptLineId) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT id, \"productId\", \"variantId\", quantity, \"uomId\","
                + " \"uomFactor\" FROM \"GoodsReceiptLine\" WHERE id = ? AND \"organizationId\" = ?")
                .params(receiptLineId, org()));
        if (r == null || r.get("productId") == null) {
            return Money.ZERO;
        }
        ReceiptLine line = receiptLine(r);
        BigDecimal required = Money.ZERO;
        for (String billId : jdbc.sql("SELECT DISTINCT \"vendorBillId\" FROM \"VendorBillReceiptMatch\" WHERE"
                + " \"organizationId\" = ? AND \"goodsReceiptLineId\" = ? ORDER BY 1").params(org(), receiptLineId)
                .query(String.class).list()) {
            BigDecimal returned = returnedOnBillBase(billId, line.productId(), line.variantId(), null);
            if (returned.signum() <= 0) {
                continue;
            }
            List<PoolLine> pool = pool(billId, line.productId(), line.variantId(), false);
            List<String> ids = pool.stream().map(p -> p.line().id()).toList();
            Map<String, BigDecimal> claimed = reservedByClaims(ids);
            Map<String, BigDecimal> matched = returnedByMatch(ids);
            BigDecimal others = Money.ZERO;
            BigDecimal mine = Money.ZERO;
            for (PoolLine p : pool) {
                if (Objects.equals(p.line().id(), receiptLineId)) {
                    mine = p.matched().multiply(p.line().factor());
                } else {
                    others = others.add(capBase(p, claimed, matched));
                }
            }
            BigDecimal carry = Money.min(returned.subtract(others).max(Money.ZERO), mine);
            required = required.add(Money.div(carry, line.factor()));
        }
        return q(required);
    }

    /** Everything returned from a receipt line (receipt-line unit): bill-less returns plus its share of bill returns. */
    public BigDecimal returnedFrom(String receiptLineId) {
        return returnedByMatch(List.of(receiptLineId)).getOrDefault(receiptLineId, Money.ZERO)
                .add(requiredByBillReturns(receiptLineId));
    }
}
