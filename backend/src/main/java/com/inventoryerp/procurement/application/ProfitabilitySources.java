package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.org;

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
 *       voucher's date, else when it was recorded) and taken back at its reversal date, as the GL does.</li>
 * </ul>
 */
@Component
class ProfitabilitySources {

    private final JdbcClient jdbc;

    ProfitabilitySources(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
