package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.inventory.application.LineageFollower;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The two inputs the landed-cost profitability reports share, read the way the books carry them so the report ties
 * out to the GL:
 *
 * <ul>
 *   <li><b>net revenue</b> per product in base currency — POS receipts ({@code Invoice}, posted / paid / refunded, at
 *       their issue date) less POS refunds (at the refund date), plus back-office sales invoices less credit notes
 *       ({@code Document}, posted / paid, at their issue date). Line subtotal (net of discount, excluding tax) × the
 *       document's exchange rate. The two ledgers are disjoint; a back-office document raised from a POS receipt
 *       ({@code sourceId} = the receipt) is skipped so the sale counts once;</li>
 *   <li><b>post-sale adjustments</b> per product and layer — the cost-value adjustment lines that reached units already
 *       issued (landed cost posted after the sale, conversion, distribution, …). They are not in the issue row's value,
 *       so cost of sales = issue value + these lines. Each is dated as posted (its journal entry, else its landed cost
 *       voucher's date, else when it was recorded) and taken back at its reversal date, as the GL does;</li>
 *   <li><b>carried landed cost</b> — landed cost capitalised before the sale rides inside the running average of the
 *       units sold after it (already in the issue value), and the <b>reversal reclass</b>: reversing such a voucher
 *       takes the share consumed since back out of COGS on the reversal date ("consumed since stays consumed"
 *       reclass). Both follow the same lineage the reversal uses.</li>
 * </ul>
 */
@Component
class ProfitabilitySources {

    private final JdbcClient jdbc;
    private final LineageFollower follower;

    ProfitabilitySources(JdbcClient jdbc, LineageFollower follower) {
        this.jdbc = jdbc;
        this.follower = follower;
    }

    /** Net quantity and revenue of one product. */
    record Revenue(BigDecimal quantity, BigDecimal amount) {}

    /** Net revenue per product over {@code [start, end)} (dates or timestamps, as the caller's period uses). */
    Map<String, Revenue> revenue(Object start, Object end) {
        Map<String, Revenue> out = new LinkedHashMap<>();
        String org = org();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"productId\", sum(qty) AS qty, sum(revenue) AS revenue"
                + " FROM (SELECT i.\"productId\", i.quantity AS qty, i.subtotal * v.\"exchangeRate\" AS revenue FROM"
                + " \"InvoiceItem\" i JOIN \"Invoice\" v ON v.id = i.\"invoiceId\" WHERE v.\"organizationId\" = ? AND"
                + " v.status::text IN ('posted', 'paid', 'refunded') AND i.\"productId\" IS NOT NULL AND v.\"issueDate\""
                + " >= ? AND v.\"issueDate\" < ?"
                + " UNION ALL SELECT i.\"productId\", -coalesce((e->>'quantity')::numeric, 0),"
                + " -coalesce((e->>'subtotal')::numeric, 0) * v.\"exchangeRate\" FROM \"PosRefund\" r JOIN \"Invoice\" v"
                + " ON v.id = r.\"invoiceId\" CROSS JOIN LATERAL jsonb_array_elements(r.items::jsonb) e JOIN"
                + " \"InvoiceItem\" i ON i.id = e->>'lineId' WHERE r.\"organizationId\" = ? AND v.status::text IN"
                + " ('posted', 'paid', 'refunded') AND i.\"productId\" IS NOT NULL AND r.\"createdAt\" >= ? AND"
                + " r.\"createdAt\" < ?"
                + " UNION ALL SELECT l.\"productId\", s.s * l.quantity * coalesce(l.\"uomFactor\", 1), s.s * l.subtotal"
                + " * d.\"exchangeRate\" FROM \"DocumentLine\" l JOIN \"Document\" d ON d.id = l.\"documentId\" CROSS JOIN"
                + " LATERAL (SELECT CASE WHEN d.\"documentType\"::text = 'credit_note' THEN -1 ELSE 1 END AS s) s WHERE"
                + " d.\"organizationId\" = ? AND d.\"documentType\"::text IN ('sales_invoice', 'credit_note') AND"
                + " d.status::text IN ('posted', 'paid') AND l.\"productId\" IS NOT NULL AND d.\"issueDate\" >= ? AND"
                + " d.\"issueDate\" < ? AND NOT EXISTS (SELECT 1 FROM \"Invoice\" pi WHERE pi.\"organizationId\" ="
                + " d.\"organizationId\" AND pi.id = d.\"sourceId\")) x GROUP BY 1")
                .params(org, start, end, org, start, end, org, start, end))) {
            out.put((String) r.get("productId"), new Revenue(SqlRows.dec(r, "qty"), SqlRows.dec(r, "revenue")));
        }
        return out;
    }

    /**
     * Post-sale adjustments per product and layer ({@code landed}, {@code conversion}, {@code distribution},
     * {@code other}) over {@code [start, end)}: + at posting, − at reversal.
     */
    Map<String, Map<String, BigDecimal>> postSale(Object start, Object end) {
        Map<String, Map<String, BigDecimal>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT a.\"productId\", a.\"adjustmentType\","
                + " sum(s.sign * cl.amount) AS amount FROM \"CostValueAdjustmentLine\" cl JOIN \"CostValueAdjustment\" a"
                + " ON a.id = cl.\"adjustmentId\" JOIN \"InventoryLedger\" il ON il.id = cl.\"consumptionLedgerId\" AND"
                + " il.type = 'issue' LEFT JOIN \"JournalEntry\" je ON je.id = a.\"journalEntryId\" LEFT JOIN LATERAL"
                + " (SELECT lc.date FROM \"LandedCost\" lc WHERE lc.\"organizationId\" = a.\"organizationId\" AND lc.code ="
                + " a.\"sourceId\" AND a.\"sourceType\" IN ('landed_cost', 'landed_cost_voucher') ORDER BY lc.date LIMIT 1)"
                + " lc ON true CROSS JOIN LATERAL (VALUES (1, coalesce(je.\"postingDate\", lc.date, a.\"createdAt\")),"
                + " (-1, a.\"reversedAt\")) s(sign, at) WHERE a.\"organizationId\" = ? AND s.at >= ? AND s.at < ?"
                + " GROUP BY 1, 2").params(org(), start, end))) {
            out.computeIfAbsent((String) r.get("productId"), k -> new LinkedHashMap<>()).merge(layer((String) r.get(
                    "adjustmentType")), SqlRows.dec(r, "amount"), BigDecimal::add);
        }
        return out;
    }

    /**
     * Landed cost carried inside the issue value of units sold after the charge, and the reversal reclass.
     *
     * @param inLayer per product: the share carried by issues in the period (consumed before any reversal)
     * @param reclass per product: what reversals in the period took back out of COGS (positive = credit to COGS)
     */
    record Carried(Map<String, BigDecimal> inLayer, Map<String, BigDecimal> reclass) {}

    /**
     * {@link Carried} over {@code [start, end)}. Each landed value row is followed through its pool (the lineage the
     * reversal itself uses, {@code LandedCostLayers.reverseLineage}); consumptions after a reversal carry nothing. The
     * reclass mirrors that reversal (a voucher reversed through its lineage) for non-lot rows: the consumed issue
     * placements, or the whole carried amount when the anchor pool was already empty. Lots (unwound per batch) and STANDARD adjuncts are not modelled.
     */
    Carried carried(Object start, Object end) {
        Map<String, BigDecimal> inLayer = new LinkedHashMap<>();
        Map<String, BigDecimal> reclass = new LinkedHashMap<>();
        for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT a.id AS \"adjustmentId\", a.\"productId\" AS"
                + " \"adjustedProductId\", a.\"onHandAmount\", a.\"reversedAt\" IS NOT NULL AS reversed,"
                + " coalesce(a.\"reversedAt\" >= ? AND a.\"reversedAt\" < ?, false) AND EXISTS (SELECT 1 FROM"
                + " \"LandedCost\" lc WHERE lc.\"organizationId\" = a.\"organizationId\" AND lc.code = a.\"sourceId\" AND"
                + " lc.status = 'reversed' AND EXISTS (SELECT 1 FROM \"LandedCostAllocation\" x WHERE x.\"landedCostId\" ="
                + " lc.id AND x.disposition IS NOT NULL)) AS \"reversedInPeriod\", g.* FROM"
                + " \"CostValueAdjustment\" a JOIN \"InventoryLedger\" g ON g.id = a.\"anchorLedgerId\" WHERE"
                + " a.\"organizationId\" = ? AND a.\"valuationComponent\" IS NULL AND a.\"adjustmentType\" IN"
                + " ('landed_cost', 'landed_accrual') AND a.\"onHandAmount\" <> 0 AND g.\"createdAt\" < ? AND"
                + " (a.\"reversedAt\" IS NULL OR (a.\"batchId\" IS NULL AND a.\"reversedAt\" >= ?))")
                .params(start, end, org(), end, start))) {
            boolean reversed = Boolean.TRUE.equals(a.get("reversed"));
            boolean reclassHere = Boolean.TRUE.equals(a.get("reversedInPeriod"));
            BigDecimal onHand = SqlRows.dec(a, "onHandAmount");
            BigDecimal pool = SqlRows.dec(a, "balanceAfter");
            if (pool.signum() <= 0) {
                if (reclassHere) {
                    reclass.merge((String) a.get("adjustedProductId"), onHand, BigDecimal::add);
                }
                continue;
            }
            for (var placement : follower.follow(a, pool, BigDecimal.ZERO, onHand).placements()) {
                if (placement.consumptionLedgerId() == null || !"issue".equals(placement.moveType())) {
                    continue;
                }
                Map<String, Object> issue = SqlRows.one(jdbc.sql("SELECT l.\"productId\", l.\"createdAt\" >= ? AND"
                        + " l.\"createdAt\" < ? AS \"inPeriod\", (a.\"reversedAt\" IS NULL OR l.\"createdAt\" <"
                        + " a.\"reversedAt\") AS \"beforeReversal\" FROM \"InventoryLedger\" l, \"CostValueAdjustment\" a"
                        + " WHERE l.id = ? AND a.id = ?").params(start, end, placement.consumptionLedgerId(),
                        a.get("adjustmentId")));
                if (issue == null || !Boolean.TRUE.equals(issue.get("beforeReversal"))) {
                    continue;
                }
                String product = (String) issue.get("productId");
                if (Boolean.TRUE.equals(issue.get("inPeriod"))) {
                    inLayer.merge(product, placement.amount(), BigDecimal::add);
                }
                if (reversed && reclassHere) {
                    reclass.merge((String) a.get("adjustedProductId"), placement.amount(), BigDecimal::add);
                }
            }
        }
        return new Carried(inLayer, reclass);
    }

    /** The cost layer an adjustment type belongs to. */
    static String layer(String adjustmentType) {
        return switch (adjustmentType) {
            case "landed_cost", "std_price_difference", "landed_accrual" -> "landed";
            case "overhead_settlement", "cost_adjustment", "stripping_amortisation", "rehab_production" -> "conversion";
            case "transfer_freight" -> "distribution";
            default -> "other";
        };
    }

    /** Σ of every layer. */
    static BigDecimal total(Map<String, BigDecimal> layers) {
        return layers.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
