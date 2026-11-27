package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Procurement dashboard (2026-11-27): one read-only call for the Procurement → Analytics page, in the shape of the
 * sales dashboard. Amounts are in the functional currency (documents in another currency at their own stored rate),
 * net of tax for orders and gross for bills and payments, as each document states them.
 * <ul>
 *   <li><b>kpis</b> for the chosen period, with the previous period of the same length for comparison;</li>
 *   <li><b>trend</b>: ordered and billed per month for the 12 months ending in the period's last month;</li>
 *   <li><b>pipeline</b>: where purchasing stands right now (request → order → receive → bill → pay);</li>
 *   <li><b>attention</b>: counts that need someone today;</li>
 *   <li>spend by supplier, category, branch and product, and the supplier scorecard summary.</li>
 * </ul>
 * The branch filter applies to orders, receipts, bills and payments by their own branch.
 */
@Service
public class ProcurementDashboardService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JdbcClient jdbc;
    private final ProcurementAnalyticsService analytics;

    public ProcurementDashboardService(JdbcClient jdbc, ProcurementAnalyticsService analytics) {
        this.jdbc = jdbc;
        this.analytics = analytics;
    }

    /** The period window: {from, to (inclusive)}. */
    public static LocalDate[] window(String period, LocalDate today) {
        return switch (period) {
            case "this_month" -> new LocalDate[] {today.withDayOfMonth(1), today};
            case "this_quarter" -> new LocalDate[] {
                today.withMonth(((today.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1), today};
            case "this_year" -> new LocalDate[] {today.withDayOfYear(1), today};
            case "last_12_months" -> new LocalDate[] {today.minusMonths(12).plusDays(1), today};
            case "last_year" -> new LocalDate[] {
                today.minusYears(1).withDayOfYear(1), today.minusYears(1).withMonth(12).withDayOfMonth(31)};
            default -> throw ApiException.badRequest(
                    "period must be one of this_month, this_quarter, this_year, last_12_months, last_year");
        };
    }

    /** The period of the same length that ends the day before {@code w[0]}. */
    public static LocalDate[] previous(LocalDate[] w) {
        long days = ChronoUnit.DAYS.between(w[0], w[1]) + 1;
        return new LocalDate[] {w[0].minusDays(days), w[0].minusDays(1)};
    }

    private static final String PO_FX = "CASE WHEN o.\"currencyCode\" = org.\"currencyCode\" THEN 1"
            + " ELSE COALESCE(NULLIF(o.\"exchangeRate\", 0), 1) END";
    private static final String DOC_FX = "CASE WHEN d.\"currencyId\" IS NULL OR upper(d.\"currencyId\") ="
            + " org.\"currencyCode\" THEN 1 ELSE COALESCE(NULLIF(d.\"exchangeRate\", 0), 1) END";
    private static final String GRN_FX = "CASE WHEN g.\"currencyCode\" IS NULL OR g.\"currencyCode\" ="
            + " org.\"currencyCode\" THEN 1 ELSE COALESCE(NULLIF(g.\"exchangeRate\", 0), 1) END";
    private static final String PAY_FX = "CASE WHEN p.\"currencyId\" IS NULL OR upper(p.\"currencyId\") ="
            + " org.\"currencyCode\" THEN 1 ELSE COALESCE(NULLIF(p.\"exchangeRate\", 0), 1) END";
    private static final String LIVE_PO = " o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL AND o.status::text NOT"
            + " IN ('draft', 'cancelled') AND (?::text IS NULL OR o.\"branchId\" = ?)";
    private static final String BILL = " d.\"organizationId\" = ? AND d.\"documentType\"::text = 'vendor_bill' AND"
            + " d.status::text IN ('posted', 'paid') AND (?::text IS NULL OR d.\"branchId\" = ?)";

    /** The dashboard for {@code period} (default this_year), optionally one branch. */
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(String period, String branchId) {
        String p = period == null || period.isBlank() ? "this_year" : period;
        String branch = branchId == null || branchId.isBlank() ? null : branchId;
        String orgId = org();
        LocalDate today = LocalDate.now();
        LocalDate[] w = window(p, today);
        LocalDate[] prev = previous(w);

        Map<String, Object> cur = periodFigures(orgId, branch, w);
        Map<String, Object> before = periodFigures(orgId, branch, prev);
        BigDecimal spend = (BigDecimal) cur.get("spend");
        BigDecimal prevSpend = (BigDecimal) before.get("spend");
        long orders = (Long) cur.get("orders");

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("spend", money(spend));
        kpis.put("previousSpend", money(prevSpend));
        kpis.put("spendChangePct", prevSpend.signum() == 0 ? null
                : JsNumbers.dec(spend.subtract(prevSpend).multiply(HUNDRED).divide(prevSpend, 1, RoundingMode.HALF_UP)));
        kpis.put("orders", orders);
        kpis.put("previousOrders", before.get("orders"));
        kpis.put("averageOrder", orders == 0 ? "0" : money(spend.divide(BigDecimal.valueOf(orders), 6, RoundingMode.HALF_UP)));
        kpis.put("suppliers", cur.get("suppliers"));
        kpis.put("received", money((BigDecimal) cur.get("received")));
        kpis.put("billed", money((BigDecimal) cur.get("billed")));
        kpis.put("paid", money((BigDecimal) cur.get("paid")));
        kpis.putAll(delivery(orgId, branch, w));

        return Obj.of("period", p, "from", w[0].toString(), "to", w[1].toString(), "previousFrom", prev[0].toString(),
                "previousTo", prev[1].toString(), "kpis", kpis, "trend", trend(orgId, branch, w[1]), "pipeline",
                pipeline(orgId, branch, today), "attention", attention(orgId, branch, today), "bySupplier",
                breakdown(orgId, branch, w, "supplier", 8), "byCategory", breakdown(orgId, branch, w, "category", 12),
                "byBranch", breakdown(orgId, branch, w, "branch", 10), "topProducts",
                breakdown(orgId, branch, w, "product", 8), "suppliers", supplierSummary(w), "alerts",
                analytics.alerts());
    }

    private Map<String, Object> periodFigures(String orgId, String branch, LocalDate[] w) {
        Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT COALESCE(SUM(o.subtotal * " + PO_FX + "), 0) AS spend,"
                        + " count(*) AS orders, count(DISTINCT o.\"partnerId\") AS suppliers FROM \"PurchaseOrder\" o JOIN"
                        + " \"Organization\" org ON org.id = o.\"organizationId\" WHERE" + LIVE_PO
                        + " AND o.\"orderDate\" >= ? AND o.\"orderDate\" < ?")
                .params(orgId, branch, branch, w[0], w[1].plusDays(1)));
        BigDecimal received = jdbc.sql("SELECT COALESCE(SUM(l.quantity * l.\"unitCost\" * " + GRN_FX + "), 0) FROM"
                        + " \"GoodsReceiptLine\" l JOIN \"GoodsReceiptNote\" g ON g.id = l.\"goodsReceiptId\" JOIN"
                        + " \"Organization\" org ON org.id = g.\"organizationId\" WHERE g.\"organizationId\" = ? AND"
                        + " g.status = 'posted' AND g.\"deletedAt\" IS NULL AND (?::text IS NULL OR g.\"branchId\" = ?)"
                        + " AND g.\"receivedAt\" >= ? AND g.\"receivedAt\" < ?")
                .params(orgId, branch, branch, w[0], w[1].plusDays(1)).query(BigDecimal.class).single();
        BigDecimal billed = jdbc.sql("SELECT COALESCE(SUM(d.\"totalAmount\" * " + DOC_FX + "), 0) FROM \"Document\" d"
                        + " JOIN \"Organization\" org ON org.id = d.\"organizationId\" WHERE" + BILL
                        + " AND d.\"issueDate\" >= ? AND d.\"issueDate\" < ?")
                .params(orgId, branch, branch, w[0], w[1].plusDays(1)).query(BigDecimal.class).single();
        BigDecimal paid = jdbc.sql("SELECT COALESCE(SUM(p.amount * " + PAY_FX + "), 0) FROM \"Payment\" p JOIN"
                        + " \"Organization\" org ON org.id = p.\"organizationId\" JOIN \"Partner\" s ON s.id = p.\"partnerId\""
                        + " WHERE p.\"organizationId\" = ? AND p.direction::text = 'outbound' AND p.status::text = 'posted'"
                        + " AND p.\"refundOfId\" IS NULL AND s.\"isSupplier\" AND (?::text IS NULL OR p.\"branchId\" = ?)"
                        + " AND p.\"paymentDate\" >= ? AND p.\"paymentDate\" < ?")
                .params(orgId, branch, branch, w[0], w[1].plusDays(1)).query(BigDecimal.class).single();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spend", Money.dec(po.get("spend")));
        out.put("orders", ((Number) po.get("orders")).longValue());
        out.put("suppliers", ((Number) po.get("suppliers")).longValue());
        out.put("received", received);
        out.put("billed", billed);
        out.put("paid", paid);
        return out;
    }

    /** On-time (first promised date) and in-full delivery, and lead time, of the period's orders. */
    private Map<String, Object> delivery(String orgId, String branch, LocalDate[] w) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT count(*) FILTER (WHERE g.first IS NOT NULL) AS delivered,"
                        + " count(*) FILTER (WHERE g.first IS NOT NULL AND g.first::date <= COALESCE(o.\"firstPromisedDate\","
                        + " o.\"promisedDate\", o.\"expectedDeliveryDate\"::date, g.first::date)) AS \"onTime\","
                        + " count(*) FILTER (WHERE o.status::text IN ('received', 'closed', 'billed')) AS finished,"
                        + " count(*) FILTER (WHERE o.status::text IN ('received', 'closed', 'billed') AND NOT EXISTS (SELECT 1"
                        + " FROM \"PurchaseOrderLine\" x WHERE x.\"purchaseOrderId\" = o.id AND x.\"receivedQuantity\" <"
                        + " x.quantity - x.\"cancelledQuantity\")) AS \"inFull\","
                        + " AVG(EXTRACT(epoch FROM g.first - COALESCE(o.\"approvedAt\", o.\"orderDate\")) / 86400) AS lead"
                        + " FROM \"PurchaseOrder\" o LEFT JOIN LATERAL (SELECT MIN(n.\"receivedAt\") AS first FROM"
                        + " \"GoodsReceiptNote\" n WHERE n.\"purchaseOrderId\" = o.id AND n.status = 'posted' AND"
                        + " n.\"deletedAt\" IS NULL) g ON true WHERE" + LIVE_PO
                        + " AND o.\"orderDate\" >= ? AND o.\"orderDate\" < ?")
                .params(orgId, branch, branch, w[0], w[1].plusDays(1)));
        long delivered = ((Number) r.get("delivered")).longValue();
        long finished = ((Number) r.get("finished")).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("onTimePct", pct(((Number) r.get("onTime")).longValue(), delivered));
        out.put("inFullPct", pct(((Number) r.get("inFull")).longValue(), finished));
        out.put("deliveredOrders", delivered);
        out.put("averageLeadDays", r.get("lead") == null ? null
                : JsNumbers.dec(Money.dec(r.get("lead")).setScale(1, RoundingMode.HALF_UP)));
        return out;
    }

    /** Ordered (net) and billed (gross) per month, 12 months ending in {@code end}'s month. */
    private List<Map<String, Object>> trend(String orgId, String branch, LocalDate end) {
        YearMonth last = YearMonth.from(end);
        YearMonth first = last.minusMonths(11);
        Map<String, BigDecimal[]> months = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            months.put(first.plusMonths(i).toString(), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        }
        LocalDate from = first.atDay(1);
        LocalDate to = last.plusMonths(1).atDay(1);
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT to_char(o.\"orderDate\", 'YYYY-MM') AS m,"
                        + " SUM(o.subtotal * " + PO_FX + ") AS v FROM \"PurchaseOrder\" o JOIN \"Organization\" org ON org.id ="
                        + " o.\"organizationId\" WHERE" + LIVE_PO + " AND o.\"orderDate\" >= ? AND o.\"orderDate\" < ? GROUP BY 1")
                .params(orgId, branch, branch, from, to))) {
            BigDecimal[] slot = months.get((String) r.get("m"));
            if (slot != null) {
                slot[0] = Money.dec(r.get("v"));
            }
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT to_char(d.\"issueDate\", 'YYYY-MM') AS m,"
                        + " SUM(d.\"totalAmount\" * " + DOC_FX + ") AS v FROM \"Document\" d JOIN \"Organization\" org ON org.id"
                        + " = d.\"organizationId\" WHERE" + BILL + " AND d.\"issueDate\" >= ? AND d.\"issueDate\" < ? GROUP BY 1")
                .params(orgId, branch, branch, from, to))) {
            BigDecimal[] slot = months.get((String) r.get("m"));
            if (slot != null) {
                slot[1] = Money.dec(r.get("v"));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        months.forEach((m, v) -> out.add(Obj.of("label", m, "ordered", Money.round(v[0]).doubleValue(), "billed",
                Money.round(v[1]).doubleValue())));
        return out;
    }

    /** Where purchasing stands right now: request → order → receive → bill → pay. */
    private Map<String, Object> pipeline(String orgId, String branch, LocalDate today) {
        Map<String, Object> req = SqlRows.one(jdbc.sql("SELECT count(DISTINCT r.id) AS n, COALESCE(SUM(GREATEST(l.quantity"
                        + " - l.\"orderedQuantity\", 0) * COALESCE(l.\"estimatedUnitPrice\", 0)), 0) AS v FROM"
                        + " \"PurchaseRequest\" r LEFT JOIN \"PurchaseRequestLine\" l ON l.\"purchaseRequestId\" = r.id WHERE"
                        + " r.\"organizationId\" = ? AND r.\"deletedAt\" IS NULL AND r.status::text IN ('submitted', 'approved')"
                        + " AND (?::text IS NULL OR r.\"branchId\" = ?)")
                .params(orgId, branch, branch));
        Map<String, Object> drafts = SqlRows.one(jdbc.sql("SELECT count(*) AS n, COALESCE(SUM(o.subtotal * " + PO_FX
                        + "), 0) AS v FROM \"PurchaseOrder\" o JOIN \"Organization\" org ON org.id = o.\"organizationId\" WHERE"
                        + " o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL AND o.status::text = 'draft' AND"
                        + " (?::text IS NULL OR o.\"branchId\" = ?)")
                .params(orgId, branch, branch));
        Map<String, Object> open = SqlRows.one(jdbc.sql("SELECT count(DISTINCT o.id) AS n, COALESCE(SUM(GREATEST(l.quantity"
                        + " - l.\"receivedQuantity\" - l.\"cancelledQuantity\", 0) * l.\"unitPrice\" * " + PO_FX + "), 0) AS v,"
                        + " count(DISTINCT o.id) FILTER (WHERE COALESCE(o.\"promisedDate\", o.\"expectedDeliveryDate\"::date) <"
                        + " ? AND l.quantity > l.\"receivedQuantity\" + l.\"cancelledQuantity\") AS late FROM \"PurchaseOrder\" o"
                        + " JOIN \"Organization\" org ON org.id = o.\"organizationId\" JOIN \"PurchaseOrderLine\" l ON"
                        + " l.\"purchaseOrderId\" = o.id WHERE o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL AND"
                        + " o.status::text IN ('active', 'partially_received') AND (?::text IS NULL OR o.\"branchId\" = ?)")
                .params(today, orgId, branch, branch));
        Map<String, Object> unbilled = SqlRows.one(jdbc.sql("SELECT count(DISTINCT g.id) AS n,"
                        + " COALESCE(SUM((l.quantity - l.\"billedQuantity\") * l.\"unitCost\" * " + GRN_FX + "), 0) AS v FROM"
                        + " \"GoodsReceiptLine\" l JOIN \"GoodsReceiptNote\" g ON g.id = l.\"goodsReceiptId\" JOIN"
                        + " \"Organization\" org ON org.id = g.\"organizationId\" WHERE g.\"organizationId\" = ? AND"
                        + " g.status = 'posted' AND g.\"deletedAt\" IS NULL AND l.\"productId\" IS NOT NULL AND l.quantity >"
                        + " l.\"billedQuantity\" AND (?::text IS NULL OR g.\"branchId\" = ?)")
                .params(orgId, branch, branch));
        Map<String, Object> toPay = SqlRows.one(jdbc.sql("SELECT count(*) AS n, COALESCE(SUM(d.\"amountResidual\" * "
                        + DOC_FX + "), 0) AS v, count(*) FILTER (WHERE d.\"dueDate\" < ?) AS \"overdueN\","
                        + " COALESCE(SUM(d.\"amountResidual\" * " + DOC_FX + ") FILTER (WHERE d.\"dueDate\" < ?), 0) AS overdue,"
                        + " COALESCE(SUM(d.\"amountResidual\" * " + DOC_FX + ") FILTER (WHERE d.\"dueDate\" >= ? AND"
                        + " d.\"dueDate\" < ?), 0) AS \"dueSoon\" FROM \"Document\" d JOIN \"Organization\" org ON org.id ="
                        + " d.\"organizationId\" WHERE d.\"organizationId\" = ? AND d.\"documentType\"::text = 'vendor_bill' AND"
                        + " d.status::text = 'posted' AND d.\"amountResidual\" > 0 AND (?::text IS NULL OR d.\"branchId\" = ?)")
                .params(today, today, today, today.plusDays(8), orgId, branch, branch));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", stage(req));
        out.put("drafts", stage(drafts));
        out.put("toReceive", Obj.of("count", ((Number) open.get("n")).longValue(), "value", money(Money.dec(open.get("v"))),
                "late", ((Number) open.get("late")).longValue()));
        out.put("toBill", stage(unbilled));
        out.put("toPay", Obj.of("count", ((Number) toPay.get("n")).longValue(), "value",
                money(Money.dec(toPay.get("v"))), "overdueCount", ((Number) toPay.get("overdueN")).longValue(), "overdue",
                money(Money.dec(toPay.get("overdue"))), "dueNext7Days", money(Money.dec(toPay.get("dueSoon")))));
        return out;
    }

    private Map<String, Object> attention(String orgId, String branch, LocalDate today) {
        long late = jdbc.sql("SELECT count(DISTINCT o.id) FROM \"PurchaseOrder\" o JOIN \"PurchaseOrderLine\" l ON"
                        + " l.\"purchaseOrderId\" = o.id WHERE o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL AND"
                        + " o.status::text IN ('active', 'partially_received') AND COALESCE(o.\"promisedDate\","
                        + " o.\"expectedDeliveryDate\"::date) < ? AND l.quantity > l.\"receivedQuantity\" +"
                        + " l.\"cancelledQuantity\" AND (?::text IS NULL OR o.\"branchId\" = ?)")
                .params(orgId, today, branch, branch).query(Long.class).single();
        long overdue = jdbc.sql("SELECT count(*) FROM \"Document\" d WHERE d.\"organizationId\" = ? AND"
                        + " d.\"documentType\"::text = 'vendor_bill' AND d.status::text = 'posted' AND d.\"amountResidual\" > 0"
                        + " AND d.\"dueDate\" < ? AND (?::text IS NULL OR d.\"branchId\" = ?)")
                .params(orgId, today, branch, branch).query(Long.class).single();
        long drafts = jdbc.sql("SELECT count(*) FROM \"Document\" d WHERE d.\"organizationId\" = ? AND"
                        + " d.\"documentType\"::text = 'vendor_bill' AND d.status::text = 'draft' AND (?::text IS NULL OR"
                        + " d.\"branchId\" = ?)")
                .params(orgId, branch, branch).query(Long.class).single();
        long inspections = jdbc.sql("SELECT count(*) FROM \"IncomingInspection\" WHERE \"organizationId\" = ? AND status ="
                + " 'pending'").param(orgId).query(Long.class).single();
        long approvals = jdbc.sql("SELECT count(*) FROM \"ApprovalRequest\" WHERE \"organizationId\" = ? AND status ="
                        + " 'pending' AND \"entityType\" IN ('purchase_order', 'purchase_request', 'vendor_bill',"
                        + " 'supplier_payment', 'goods_receipt', 'po_change', 'rfq_award', 'debit_note')")
                .param(orgId).query(Long.class).single();
        return Obj.of("lateDeliveries", late, "overdueBills", overdue, "draftBills", drafts, "pendingInspections",
                inspections, "pendingApprovals", approvals);
    }

    /** Spend of the period's orders (net, functional) by supplier / category / branch / product, biggest first. */
    private List<Map<String, Object>> breakdown(String orgId, String branch, LocalDate[] w, String by, int limit) {
        String[] key = switch (by) {
            case "supplier" -> new String[] {"p.id", "COALESCE(p.name, '(none)')"};
            case "category" -> new String[] {"c.id", "COALESCE(c.name, '(none)')"};
            case "branch" -> new String[] {"b.id", "COALESCE(b.name, '(none)')"};
            default -> new String[] {"pr.id", "COALESCE(pr.name, l.description, '(none)')"};
        };
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT " + key[0] + " AS id, " + key[1] + " AS label,"
                        + " count(DISTINCT o.id) AS orders, SUM(l.quantity * l.\"unitPrice\" * " + PO_FX + ") AS spend,"
                        + " SUM(l.quantity) AS quantity FROM \"PurchaseOrderLine\" l JOIN \"PurchaseOrder\" o ON o.id ="
                        + " l.\"purchaseOrderId\" JOIN \"Organization\" org ON org.id = o.\"organizationId\" LEFT JOIN"
                        + " \"Partner\" p ON p.id = o.\"partnerId\" LEFT JOIN \"Product\" pr ON pr.id = l.\"productId\" LEFT"
                        + " JOIN \"ProductCategory\" c ON c.id = pr.\"categoryId\" LEFT JOIN \"Branch\" b ON b.id ="
                        + " o.\"branchId\" WHERE" + LIVE_PO + " AND o.\"orderDate\" >= ? AND o.\"orderDate\" < ? GROUP BY 1, 2"
                        + " ORDER BY 4 DESC NULLS LAST LIMIT " + limit)
                .params(orgId, branch, branch, w[0], w[1].plusDays(1)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(Obj.of("id", r.get("id"), "label", r.get("label"), "orders", ((Number) r.get("orders")).longValue(),
                    "spend", Money.round(Money.dec(r.get("spend"))).doubleValue(), "quantity",
                    JsNumbers.dec(Money.round(Money.dec(r.get("quantity")), 4))));
        }
        return out;
    }

    /** Scorecards of the period: grade counts and the five best and five weakest suppliers. */
    private Map<String, Object> supplierSummary(LocalDate[] w) {
        List<Map<String, Object>> cards = analytics.scorecards(w[0].toString(), w[1].toString());
        Map<String, Long> grades = new LinkedHashMap<>();
        for (String g : List.of("A", "B", "C", "D")) {
            grades.put(g, cards.stream().filter(c -> g.equals(c.get("grade"))).count());
        }
        double avg = cards.stream().mapToDouble(c -> ((Number) c.get("score")).doubleValue()).average().orElse(Double.NaN);
        List<Map<String, Object>> weakest = new ArrayList<>(cards.subList(Math.max(0, cards.size() - 5), cards.size()));
        java.util.Collections.reverse(weakest);
        return Obj.of("count", cards.size(), "averageScore", Double.isNaN(avg) ? null : Math.round(avg * 10) / 10.0,
                "grades", grades, "best", cards.subList(0, Math.min(5, cards.size())), "weakest",
                cards.size() > 5 ? weakest : List.of());
    }

    private static Map<String, Object> stage(Map<String, Object> r) {
        return Obj.of("count", ((Number) r.get("n")).longValue(), "value", money(Money.dec(r.get("v"))));
    }

    private static String money(BigDecimal v) {
        return JsNumbers.dec(Money.round(v));
    }

    private static String pct(long part, long whole) {
        return whole == 0 ? null
                : JsNumbers.dec(BigDecimal.valueOf(part * 100).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP));
    }
}
