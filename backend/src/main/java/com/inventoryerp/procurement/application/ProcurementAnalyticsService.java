package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Purchasing analytics with built-in statistics only (ADR-PROCUREMENT-001 wave 7; no external service):
 * <ul>
 *   <li><b>Supplier scorecards</b> — on-time and in-full delivery, quality rejects, claims, lead time, spend, and a
 *       0–100 score (on-time 35, in-full 25, quality 25, claims 15).</li>
 *   <li><b>Price check</b> — z-score of a price against the supplier price history of the last 12 months (flagged
 *       when |z| &gt; 2 with at least 5 observations).</li>
 *   <li><b>Spend</b> — approved order value by supplier, category, branch or month.</li>
 *   <li><b>Demand forecast</b> — simple exponential smoothing (α = 0.3) of monthly consumption.</li>
 *   <li><b>Risk alerts</b> — late deliveries, high reject rates, single-source spend, expiring certificates.</li>
 * </ul>
 */
@Service
public class ProcurementAnalyticsService {

    private static final Set<String> GROUPS = Set.of("supplier", "category", "branch", "month");
    private static final BigDecimal ALPHA = new BigDecimal("0.3");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JdbcClient jdbc;

    public ProcurementAnalyticsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Scorecards of every supplier with orders in the window (default: last 12 months), best first. */
    public List<Map<String, Object>> scorecards(String from, String to) {
        LocalDate[] w = window(from, to);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(SCORE_SQL + " GROUP BY p.id, p.name ORDER BY p.name")
                .params(org(), w[0], w[1].plusDays(1)))) {
            out.add(card(r));
        }
        out.sort((a, b) -> Double.compare(((Number) b.get("score")).doubleValue(), ((Number) a.get("score")).doubleValue()));
        return out;
    }

    /** One supplier's scorecard. */
    public Map<String, Object> scorecard(String partnerId, String from, String to) {
        LocalDate[] w = window(from, to);
        Map<String, Object> r = SqlRows.one(jdbc.sql(SCORE_SQL + " AND p.id = ? GROUP BY p.id, p.name")
                .params(org(), w[0], w[1].plusDays(1), partnerId));
        if (r == null) {
            throw ApiException.notFound("No orders for this supplier in the period");
        }
        return card(r);
    }

    // Per supplier: orders, spend, lines delivered on time / in full (posted receipts vs promised or expected date),
    // rejected vs inspected quantity, claims, average days from approval to first receipt.
    private static final String SCORE_SQL = "SELECT p.id AS \"partnerId\", p.name AS \"partnerName\","
            + " count(DISTINCT o.id) AS orders, COALESCE(SUM(o.\"totalAmount\"), 0) AS spend,"
            + " count(DISTINCT o.id) FILTER (WHERE g.first IS NOT NULL) AS delivered,"
            + " count(DISTINCT o.id) FILTER (WHERE g.first IS NOT NULL AND g.first::date <= COALESCE(o.\"promisedDate\","
            + " o.\"expectedDeliveryDate\"::date, g.first::date)) AS \"onTime\","
            + " count(DISTINCT o.id) FILTER (WHERE o.status::text IN ('received', 'closed', 'billed')"
            + " AND NOT EXISTS (SELECT 1 FROM \"PurchaseOrderLine\" x WHERE x.\"purchaseOrderId\" = o.id AND"
            + " x.\"receivedQuantity\" < x.quantity)) AS \"inFull\","
            + " count(DISTINCT o.id) FILTER (WHERE o.status::text IN ('received', 'closed', 'billed')) AS finished,"
            + " AVG(EXTRACT(epoch FROM g.first - COALESCE(o.\"approvedAt\", o.\"orderDate\")) / 86400) AS \"leadDays\","
            + " (SELECT COALESCE(SUM(il.\"receivedQuantity\"), 0) FROM \"IncomingInspectionLine\" il JOIN"
            + " \"IncomingInspection\" ii ON ii.id = il.\"inspectionId\" WHERE ii.\"partnerId\" = p.id AND ii.status IN"
            + " ('passed', 'partial', 'failed') AND ii.\"organizationId\" = p.\"organizationId\") AS inspected,"
            + " (SELECT COALESCE(SUM(il.\"rejectedQuantity\"), 0) FROM \"IncomingInspectionLine\" il JOIN"
            + " \"IncomingInspection\" ii ON ii.id = il.\"inspectionId\" WHERE ii.\"partnerId\" = p.id AND ii.status IN"
            + " ('passed', 'partial', 'failed') AND ii.\"organizationId\" = p.\"organizationId\") AS rejected,"
            + " (SELECT count(*) FROM \"SupplierClaim\" c WHERE c.\"partnerId\" = p.id AND c.status <> 'cancelled' AND"
            + " c.\"organizationId\" = p.\"organizationId\") AS claims,"
            + " (SELECT COALESCE(SUM(c.amount), 0) FROM \"SupplierClaim\" c WHERE c.\"partnerId\" = p.id AND c.status IN"
            + " ('accepted', 'settled') AND c.\"organizationId\" = p.\"organizationId\") AS \"claimValue\""
            + " FROM \"PurchaseOrder\" o JOIN \"Partner\" p ON p.id = o.\"partnerId\" LEFT JOIN LATERAL (SELECT"
            + " MIN(n.\"receivedAt\") AS first FROM \"GoodsReceiptNote\" n WHERE n.\"purchaseOrderId\" = o.id AND n.status ="
            + " 'posted' AND n.\"deletedAt\" IS NULL) g ON true WHERE o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL"
            + " AND o.status::text NOT IN ('draft', 'cancelled') AND o.\"orderDate\" >= ? AND o.\"orderDate\" < ?";

    private static Map<String, Object> card(Map<String, Object> r) {
        long orders = ((Number) r.get("orders")).longValue();
        long delivered = ((Number) r.get("delivered")).longValue();
        long finished = ((Number) r.get("finished")).longValue();
        BigDecimal onTime = pct(((Number) r.get("onTime")).longValue(), delivered);
        BigDecimal inFull = pct(((Number) r.get("inFull")).longValue(), finished);
        BigDecimal inspected = Money.dec(r.get("inspected"));
        BigDecimal rejectPct = inspected.signum() == 0 ? null
                : Money.dec(r.get("rejected")).multiply(HUNDRED).divide(inspected, 2, RoundingMode.HALF_UP);
        long claims = ((Number) r.get("claims")).longValue();
        // Missing measures count as neutral (full marks) so a new supplier is not punished for lack of data.
        double score = 0.35 * num(onTime, 100) + 0.25 * num(inFull, 100) + 0.25 * (100 - num(rejectPct, 0))
                + 0.15 * Math.max(0, 100 - 100.0 * claims / Math.max(1, orders));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("partnerId", r.get("partnerId"));
        m.put("partnerName", r.get("partnerName"));
        m.put("orders", orders);
        m.put("spend", JsNumbers.dec(Money.round(Money.dec(r.get("spend")))));
        m.put("onTimePct", onTime == null ? null : JsNumbers.dec(onTime));
        m.put("inFullPct", inFull == null ? null : JsNumbers.dec(inFull));
        m.put("rejectPct", rejectPct == null ? null : JsNumbers.dec(rejectPct));
        m.put("claims", claims);
        m.put("claimValue", JsNumbers.dec(Money.round(Money.dec(r.get("claimValue")))));
        m.put("averageLeadDays", r.get("leadDays") == null ? null
                : JsNumbers.dec(Money.dec(r.get("leadDays")).setScale(1, RoundingMode.HALF_UP)));
        m.put("score", Math.round(score * 10) / 10.0);
        m.put("grade", score >= 85 ? "A" : score >= 70 ? "B" : score >= 50 ? "C" : "D");
        return m;
    }

    /** z-score of {@code unitPrice} against the last 12 months of recorded prices (supplier first, else any supplier). */
    public Map<String, Object> priceCheck(String productId, String partnerId, BigDecimal unitPrice) {
        if (productId == null || productId.isBlank() || unitPrice == null) {
            throw ApiException.badRequest("productId and unitPrice are required");
        }
        String orgId = org();
        String scope = "supplier";
        List<BigDecimal> prices = history(orgId, productId, partnerId);
        if (prices.size() < 5) {
            scope = "all_suppliers";
            prices = history(orgId, productId, null);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("productId", productId);
        out.put("unitPrice", JsNumbers.dec(unitPrice));
        out.put("observations", prices.size());
        out.put("scope", scope);
        if (prices.size() < 2) {
            out.put("anomaly", false);
            out.put("message", "Not enough price history to judge this price");
            return out;
        }
        BigDecimal n = BigDecimal.valueOf(prices.size());
        BigDecimal mean = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(n, 6, RoundingMode.HALF_UP);
        BigDecimal var = prices.stream().map(p -> p.subtract(mean).pow(2)).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(prices.size() - 1L), 6, RoundingMode.HALF_UP);
        BigDecimal sd = var.sqrt(MathContext.DECIMAL64).setScale(6, RoundingMode.HALF_UP);
        BigDecimal z = sd.signum() == 0 ? (unitPrice.compareTo(mean) == 0 ? BigDecimal.ZERO : null)
                : unitPrice.subtract(mean).divide(sd, 2, RoundingMode.HALF_UP);
        boolean anomaly = prices.size() >= 5 && (z == null || z.abs().compareTo(BigDecimal.valueOf(2)) > 0);
        out.put("mean", JsNumbers.dec(mean.setScale(2, RoundingMode.HALF_UP)));
        out.put("standardDeviation", JsNumbers.dec(sd.setScale(2, RoundingMode.HALF_UP)));
        out.put("min", JsNumbers.dec(prices.stream().min(BigDecimal::compareTo).orElseThrow()));
        out.put("max", JsNumbers.dec(prices.stream().max(BigDecimal::compareTo).orElseThrow()));
        out.put("zScore", z == null ? null : JsNumbers.dec(z));
        out.put("deviationPct", mean.signum() == 0 ? null : JsNumbers.dec(unitPrice.subtract(mean).multiply(HUNDRED)
                .divide(mean, 1, RoundingMode.HALF_UP)));
        out.put("anomaly", anomaly);
        out.put("message", anomaly ? (unitPrice.compareTo(mean) > 0 ? "Unusually high" : "Unusually low")
                + " compared with the last 12 months (average " + JsNumbers.dec(mean.setScale(2, RoundingMode.HALF_UP))
                + ")" : "Within the usual range");
        return out;
    }

    private List<BigDecimal> history(String orgId, String productId, String partnerId) {
        return jdbc.sql("SELECT \"unitPrice\" FROM \"SupplierPrice\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                        + " AND (?::text IS NULL OR \"partnerId\" = ?) AND \"validFrom\" >= current_date - 365 ORDER BY"
                        + " \"validFrom\" DESC LIMIT 200")
                .params(orgId, productId, partnerId, partnerId).query(BigDecimal.class).list();
    }

    /** Approved order value (functional currency) grouped by supplier / category / branch / month. */
    public Map<String, Object> spend(String groupBy, String from, String to) {
        String g = groupBy == null || groupBy.isBlank() ? "supplier" : groupBy;
        if (!GROUPS.contains(g)) {
            throw ApiException.badRequest("groupBy must be one of supplier, category, branch, month");
        }
        LocalDate[] w = window(from, to);
        String key = switch (g) {
            case "supplier" -> "COALESCE(p.name, '—')";
            case "category" -> "COALESCE(c.name, 'Uncategorised')";
            case "branch" -> "COALESCE(b.name, 'No branch')";
            default -> "to_char(o.\"orderDate\", 'YYYY-MM')";
        };
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT " + key + " AS label, count(DISTINCT o.id) AS"
                + " orders, SUM(l.quantity * l.\"unitPrice\" * CASE WHEN o.\"currencyCode\" = org.\"currencyCode\" THEN 1"
                + " ELSE COALESCE(NULLIF(o.\"exchangeRate\", 0), 1) END) AS value, SUM(l.\"receivedQuantity\" * l.\"unitPrice\""
                + " * CASE WHEN o.\"currencyCode\" = org.\"currencyCode\" THEN 1 ELSE COALESCE(NULLIF(o.\"exchangeRate\", 0), 1)"
                + " END) AS received FROM \"PurchaseOrderLine\" l JOIN \"PurchaseOrder\" o ON o.id = l.\"purchaseOrderId\""
                + " JOIN \"Organization\" org ON org.id = o.\"organizationId\" LEFT JOIN \"Partner\" p ON p.id = o.\"partnerId\""
                + " LEFT JOIN \"Product\" pr ON pr.id = l.\"productId\" LEFT JOIN \"ProductCategory\" c ON c.id ="
                + " pr.\"categoryId\" LEFT JOIN \"Branch\" b ON b.id = o.\"branchId\" WHERE o.\"organizationId\" = ? AND"
                + " o.\"deletedAt\" IS NULL AND o.status::text NOT IN ('draft', 'cancelled') AND o.\"orderDate\" >= ? AND"
                + " o.\"orderDate\" < ? GROUP BY 1 ORDER BY " + ("month".equals(g) ? "1" : "3 DESC") + " LIMIT 100")
                .params(org(), w[0], w[1].plusDays(1)));
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            total = total.add(Money.dec(r.get("value")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal v = Money.dec(r.get("value"));
            out.add(Obj.of("label", r.get("label"), "orders", r.get("orders"), "value", JsNumbers.dec(Money.round(v)),
                    "received", JsNumbers.dec(Money.round(Money.dec(r.get("received")))), "sharePct",
                    total.signum() == 0 ? null : JsNumbers.dec(v.multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP))));
        }
        return Obj.of("groupBy", g, "from", w[0].toString(), "to", w[1].toString(), "total",
                JsNumbers.dec(Money.round(total)), "rows", out);
    }

    /** Monthly consumption of the last 12 months and the next month's forecast (exponential smoothing). */
    public Map<String, Object> forecast(String productId, String locationId) {
        if (productId == null || productId.isBlank()) {
            throw ApiException.badRequest("productId is required");
        }
        Map<String, BigDecimal> byMonth = new LinkedHashMap<>();
        YearMonth start = YearMonth.now().minusMonths(11);
        for (int i = 0; i < 12; i++) {
            byMonth.put(start.plusMonths(i).toString(), BigDecimal.ZERO);
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT to_char(\"createdAt\", 'YYYY-MM') AS m,"
                        + " -SUM(\"quantityChange\") AS q FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\""
                        + " = ? AND (?::text IS NULL OR \"locationId\" = ?) AND \"quantityChange\" < 0 AND type::text NOT IN"
                        + " ('transfer_out', 'reversal_out', 'return_to_supplier') AND \"createdAt\" >= ? GROUP BY 1")
                .params(org(), productId, locationId, locationId, start.atDay(1)))) {
            byMonth.computeIfPresent((String) r.get("m"), (k, v) -> Money.dec(r.get("q")));
        }
        BigDecimal level = null;
        List<Map<String, Object>> series = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : byMonth.entrySet()) {
            level = level == null ? e.getValue()
                    : ALPHA.multiply(e.getValue()).add(BigDecimal.ONE.subtract(ALPHA).multiply(level));
            series.add(Obj.of("period", e.getKey(), "quantity", JsNumbers.dec(Money.round(e.getValue(), 4)), "smoothed",
                    JsNumbers.dec(Money.round(level, 4))));
        }
        BigDecimal total = byMonth.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return Obj.of("productId", productId, "method", "exponential_smoothing", "alpha", "0.3", "history", series,
                "nextPeriod", YearMonth.now().plusMonths(1).toString(), "forecast",
                JsNumbers.dec(Money.round(level == null ? BigDecimal.ZERO : level, 4)), "averageMonthly",
                JsNumbers.dec(Money.round(total.divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP), 4)));
    }

    /** Risk alerts for the purchasing dashboard. */
    public List<Map<String, Object>> alerts() {
        String orgId = org();
        List<Map<String, Object>> out = new ArrayList<>();
        long late = jdbc.sql("SELECT count(DISTINCT o.id) FROM \"PurchaseOrder\" o JOIN \"PurchaseOrderLine\" l ON"
                + " l.\"purchaseOrderId\" = o.id WHERE o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL AND"
                + " o.status::text IN ('active', 'partially_received') AND COALESCE(o.\"promisedDate\","
                + " o.\"expectedDeliveryDate\"::date) < current_date AND l.quantity > l.\"receivedQuantity\" +"
                + " l.\"cancelledQuantity\"").param(orgId).query(Long.class).single();
        if (late > 0) {
            out.add(alert("late_deliveries", "warning", late + " order(s) are past their delivery date",
                    "/procurement/reports/open-orders"));
        }
        for (Map<String, Object> s : scorecards(null, null)) {
            if (s.get("rejectPct") != null && new BigDecimal((String) s.get("rejectPct")).compareTo(BigDecimal.TEN) > 0) {
                out.add(alert("high_rejects", "danger", s.get("partnerName") + " rejects " + s.get("rejectPct")
                        + " % at inspection", "/procurement/analytics"));
            }
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT pr.name, SUM(l.quantity * l.\"unitPrice\") AS v FROM"
                + " \"PurchaseOrderLine\" l JOIN \"PurchaseOrder\" o ON o.id = l.\"purchaseOrderId\" JOIN \"Product\" pr ON"
                + " pr.id = l.\"productId\" WHERE o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL AND o.status::text NOT"
                + " IN ('draft', 'cancelled') AND o.\"orderDate\" >= current_date - 365 GROUP BY pr.id, pr.name HAVING"
                + " count(DISTINCT o.\"partnerId\") = 1 AND count(DISTINCT o.id) >= 3 ORDER BY 2 DESC LIMIT 5").param(orgId))) {
            out.add(alert("single_source", "info", r.get("name") + " is bought from a single supplier — consider a second"
                    + " source", "/procurement/rfqs"));
        }
        long expiring = jdbc.sql("SELECT count(*) FROM \"SupplierCertificate\" WHERE \"organizationId\" = ? AND \"validTo\""
                + " BETWEEN current_date AND current_date + 30 AND \"deletedAt\" IS NULL").param(orgId).query(Long.class).single();
        if (expiring > 0) {
            out.add(alert("certificates_expiring", "warning", expiring + " supplier certificate(s) expire within 30 days",
                    "/procurement/suppliers"));
        }
        return out;
    }

    private static Map<String, Object> alert(String type, String severity, String message, String link) {
        return Obj.of("type", type, "severity", severity, "message", message, "link", link);
    }

    private static LocalDate[] window(String from, String to) {
        LocalDate t = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to.substring(0, 10));
        LocalDate f = from == null || from.isBlank() ? t.minusMonths(12) : LocalDate.parse(from.substring(0, 10));
        if (f.isAfter(t)) {
            throw ApiException.badRequest("from must be on or before to");
        }
        return new LocalDate[] {f, t};
    }

    private static BigDecimal pct(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    private static double num(BigDecimal v, double fallback) {
        return v == null ? fallback : v.doubleValue();
    }
}
