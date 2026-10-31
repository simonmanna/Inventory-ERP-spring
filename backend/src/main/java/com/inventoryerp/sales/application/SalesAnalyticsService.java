package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales analytics (ADR-S011 phase S10): the sales dashboard, revenue / margin analysis by dimension, customer
 * profitability and a three-source forecast. Revenue is net invoiced (invoices − credit notes, product lines, document
 * currency, net of tax) from {@link SalesRevenueQuery}; cost is the product's current cost price.
 *
 * <p>Customer profitability = revenue − cost − freight (transporter freight of the trips of the customer's sales-order
 * deliveries dispatched in the period; own-fleet running cost waits for fleet trip costing, roadmap 1.9) − rebates
 * accrued for the customer in the period − commissions computed on the customer's lines.
 */
@Service
public class SalesAnalyticsService {

    static final Set<String> DIMENSIONS = Set.of("product", "productCategory", "customer", "customerCategory", "branch",
            "salesperson", "month");

    private final SalesDb db;
    private final SalesRevenueQuery revenue;
    private final SalesCommissionService commissions;

    public SalesAnalyticsService(SalesDb db, SalesRevenueQuery revenue, SalesCommissionService commissions) {
        this.db = db;
        this.revenue = revenue;
        this.commissions = commissions;
    }

    private static LocalDate date(String v, LocalDate fallback) {
        return blank(v) ? fallback : SalesDb.date(v);
    }

    private static BigDecimal sum(List<Map<String, Object>> lines, String key) {
        return lines.stream().map(l -> dec(l.get(key))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Object money(BigDecimal v) {
        return JsNumbers.js(v.setScale(2, RoundingMode.HALF_UP));
    }

    private static Object pct(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? null
                : JsNumbers.js(part.multiply(SalesPricingEngine.HUNDRED).divide(whole, 1, RoundingMode.HALF_UP));
    }

    // ------------------------------------------------------------------ dashboard

    /** Odoo-style tiles: this month vs last, pipeline, work queues, overdue receivables and a 12-month chart. */
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard() {
        LocalDate first = LocalDate.now().withDayOfMonth(1);
        LocalDate from12 = first.minusMonths(11);
        List<Map<String, Object>> lines = revenue.invoiced(from12, LocalDate.now());
        Map<String, BigDecimal[]> byMonth = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            byMonth.put(from12.plusMonths(i).toString().substring(0, 7), new BigDecimal[] {BigDecimal.ZERO,
                    BigDecimal.ZERO});
        }
        for (Map<String, Object> l : lines) {
            BigDecimal[] m = byMonth.get(String.valueOf(l.get("date")).substring(0, 7));
            if (m != null) {
                m[0] = m[0].add(dec(l.get("revenue")));
                m[1] = m[1].add(dec(l.get("revenue")).subtract(dec(l.get("cost"))));
            }
        }
        List<Map<String, Object>> chart = new ArrayList<>();
        byMonth.forEach((k, v) -> chart.add(Obj.of("month", k, "revenue", money(v[0]), "margin", money(v[1]))));
        BigDecimal thisMonth = byMonth.get(first.toString().substring(0, 7))[0];
        BigDecimal lastMonth = byMonth.get(first.minusMonths(1).toString().substring(0, 7))[0];
        Map<String, Object> quotes = db.row("SELECT count(*) AS count, COALESCE(sum(total), 0) AS total FROM"
                + " \"SalesQuotation\" WHERE \"organizationId\" = ? AND status IN ('approved', 'sent') AND \"deletedAt\""
                + " IS NULL", org());
        Map<String, Object> toDeliver = db.row("SELECT count(*) AS count, COALESCE(sum(total), 0) AS total FROM"
                + " \"SalesOrder\" WHERE \"organizationId\" = ? AND status IN ('confirmed', 'in_fulfilment') AND"
                + " \"deliveryStatus\" <> 'full' AND \"deletedAt\" IS NULL", org());
        Map<String, Object> toInvoice = db.row("SELECT count(*) AS count FROM \"SalesOrder\" WHERE \"organizationId\" = ?"
                + " AND \"invoiceStatus\" IN ('to_invoice', 'partial') AND status <> 'cancelled' AND \"deletedAt\" IS"
                + " NULL", org());
        Map<String, Object> overdue = db.row("SELECT count(DISTINCT \"partnerId\") AS customers,"
                + " COALESCE(sum(\"amountResidual\"), 0) AS total FROM \"Document\" WHERE \"organizationId\" = ? AND"
                + " \"documentType\" = 'sales_invoice' AND status = 'posted' AND \"amountResidual\" > 0 AND \"dueDate\" <"
                + " CURRENT_DATE", org());
        Map<String, BigDecimal> top = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            if (String.valueOf(l.get("date")).compareTo(first.toString()) >= 0) {
                top.merge(str(l.get("partnerId")), dec(l.get("revenue")), BigDecimal::add);
            }
        }
        if (!top.isEmpty()) {
            for (Map<String, Object> p : db.rows("SELECT id, name FROM \"Partner\" WHERE \"organizationId\" = ? AND id IN ("
                    + SalesDb.marks(top.size()) + ")", concat(org(), top.keySet()))) {
                names.put(str(p.get("id")), str(p.get("name")));
            }
        }
        List<Map<String, Object>> topCustomers = top.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed()).limit(5)
                .map(e -> Obj.of("partnerId", e.getKey(), "name", names.get(e.getKey()), "revenue", money(e.getValue())))
                .toList();
        return Obj.of("revenueThisMonth", money(thisMonth), "revenueLastMonth", money(lastMonth), "growthPercent",
                lastMonth.signum() == 0 ? null : pct(thisMonth.subtract(lastMonth), lastMonth), "quotationsPending",
                quotes, "ordersToDeliver", toDeliver, "ordersToInvoice", toInvoice.get("count"), "overdueReceivables",
                overdue, "chart", chart, "topCustomers", topCustomers);
    }

    private static List<Object> concat(Object first, java.util.Collection<?> rest) {
        List<Object> out = new ArrayList<>();
        out.add(first);
        out.addAll(rest);
        return out;
    }

    // ------------------------------------------------------------------ analysis

    /** Revenue, quantity, cost and margin of {@code [from, to]} grouped by {@code groupBy}. */
    @Transactional(readOnly = true)
    public Map<String, Object> analysis(String groupBy, String fromRaw, String toRaw) {
        String dim = blank(groupBy) ? "product" : groupBy;
        if (!DIMENSIONS.contains(dim)) {
            throw ApiException.badRequest("groupBy must be one of: " + String.join(", ", new java.util.TreeSet<>(
                    DIMENSIONS)));
        }
        LocalDate to = date(toRaw, LocalDate.now());
        LocalDate from = date(fromRaw, to.withDayOfMonth(1).minusMonths(2));
        List<Map<String, Object>> lines = revenue.invoiced(from, to);
        Function<Map<String, Object>, String> key = switch (dim) {
            case "productCategory" -> l -> str(l.get("productCategoryId"));
            case "customer" -> l -> str(l.get("partnerId"));
            case "customerCategory" -> l -> str(l.get("partnerCategoryId"));
            case "branch" -> l -> str(l.get("branchId"));
            case "salesperson" -> l -> str(l.get("salespersonId"));
            case "month" -> l -> String.valueOf(l.get("date")).substring(0, 7);
            default -> l -> str(l.get("productId"));
        };
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            groups.computeIfAbsent(key.apply(l), k -> new ArrayList<>()).add(l);
        }
        Map<String, String> labels = labels(dim, groups.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> g : groups.entrySet()) {
            BigDecimal rev = sum(g.getValue(), "revenue");
            BigDecimal cost = sum(g.getValue(), "cost");
            Map<String, Object> r = Obj.of("key", g.getKey(), "label", g.getKey() == null ? "—"
                    : labels.getOrDefault(g.getKey(), g.getKey()), "quantity", JsNumbers.js(sum(g.getValue(), "quantity")),
                    "revenue", money(rev), "cost", money(cost), "margin", money(rev.subtract(cost)));
            r.put("marginPercent", pct(rev.subtract(cost), rev));
            r.put("documents", g.getValue().stream().map(l -> l.get("documentId")).distinct().count());
            rows.add(r);
        }
        rows.sort("month".equals(dim) ? Comparator.comparing(r -> String.valueOf(r.get("key")))
                : Comparator.comparing((Map<String, Object> r) -> dec(r.get("revenue"))).reversed());
        BigDecimal rev = sum(lines, "revenue");
        BigDecimal cost = sum(lines, "cost");
        return Obj.of("groupBy", dim, "from", from, "to", to, "rows", rows, "totals", Obj.of("revenue", money(rev),
                "cost", money(cost), "margin", money(rev.subtract(cost)), "marginPercent", pct(rev.subtract(cost), rev)));
    }

    /** Display names of dimension keys. */
    private Map<String, String> labels(String dim, java.util.Collection<String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> ids = keys.stream().filter(k -> k != null).toList();
        if (ids.isEmpty() || "month".equals(dim)) {
            return out;
        }
        String sql = switch (dim) {
            case "product" -> "SELECT id, name FROM \"Product\"";
            case "productCategory" -> "SELECT id, name FROM \"ProductCategory\"";
            case "customer" -> "SELECT id, name FROM \"Partner\"";
            case "customerCategory" -> "SELECT id, name FROM \"PartnerCategory\"";
            case "branch" -> "SELECT id, name FROM \"Branch\"";
            default -> "SELECT id, trim(\"firstName\" || ' ' || COALESCE(\"lastName\", '')) AS name FROM \"User\"";
        };
        for (Map<String, Object> r : db.rows(sql + " WHERE \"organizationId\" = ? AND id IN (" + SalesDb.marks(ids.size())
                + ")", concat(org(), ids))) {
            out.put(str(r.get("id")), str(r.get("name")));
        }
        return out;
    }

    // ------------------------------------------------------------------ profitability

    /** Per customer: revenue − cost − freight − rebates − commissions. */
    @Transactional(readOnly = true)
    public Map<String, Object> profitability(String fromRaw, String toRaw) {
        LocalDate to = date(toRaw, LocalDate.now());
        LocalDate from = date(fromRaw, to.withDayOfMonth(1).minusMonths(2));
        Map<String, BigDecimal[]> acc = new LinkedHashMap<>();
        for (Map<String, Object> l : revenue.invoiced(from, to)) {
            BigDecimal[] a = acc.computeIfAbsent(str(l.get("partnerId")), k -> zeros());
            a[0] = a[0].add(dec(l.get("revenue")));
            a[1] = a[1].add(dec(l.get("cost")));
        }
        for (Map<String, Object> r : db.rows("SELECT \"partnerId\", sum(amount) AS amount FROM \"RebateAccrual\" WHERE"
                + " \"organizationId\" = ? AND \"periodStart\" <= ? AND \"periodEnd\" >= ? GROUP BY \"partnerId\"", org(), to,
                from)) {
            acc.computeIfAbsent(str(r.get("partnerId")), k -> zeros())[2] = dec(r.get("amount"));
        }
        for (Map<String, Object> r : db.rows("SELECT o.\"partnerId\", sum(t.\"freightAmount\") AS freight FROM \"FleetLoad\" t"
                + " JOIN \"FleetDeliveryOrder\" d ON d.id = t.\"deliveryOrderId\" JOIN \"SalesOrder\" o ON d.\"sourceType\" ="
                + " 'sales_order' AND o.id = d.\"sourceId\" WHERE t.\"organizationId\" = ? AND t.\"dispatchedAt\" >= ? AND"
                + " t.\"dispatchedAt\" < ? AND t.\"cancelledAt\" IS NULL AND t.\"freightAmount\" IS NOT NULL GROUP BY"
                + " o.\"partnerId\"", org(), from, to.plusDays(1))) {
            acc.computeIfAbsent(str(r.get("partnerId")), k -> zeros())[4] = dec(r.get("freight"));
        }
        for (Map<String, Object> c : commissions.compute(null, from, to)) {
            BigDecimal[] a = acc.computeIfAbsent(str(c.get("partnerId")), k -> zeros());
            a[3] = a[3].add(dec(c.get("amount")));
        }
        Map<String, String> names = labels("customer", acc.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : acc.entrySet()) {
            BigDecimal[] a = e.getValue();
            BigDecimal gross = a[0].subtract(a[1]);
            BigDecimal net = gross.subtract(a[4]).subtract(a[2]).subtract(a[3]);
            Map<String, Object> r = Obj.of("partnerId", e.getKey(), "name", names.get(e.getKey()), "revenue", money(a[0]),
                    "cost", money(a[1]), "grossMargin", money(gross), "rebates", money(a[2]), "commissions", money(a[3]));
            r.put("freight", money(a[4]));
            r.put("netContribution", money(net));
            r.put("netPercent", pct(net, a[0]));
            rows.add(r);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> dec(r.get("netContribution"))).reversed());
        return Obj.of("from", from, "to", to, "rows", rows);
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    // ------------------------------------------------------------------ forecast

    /**
     * Revenue forecast for the next {@code months}: run rate (average net revenue of the last three full months),
     * confirmed order value not yet invoiced (by commitment / requested date, else the first month), open quotations
     * weighted by the 90-day conversion rate (by validity date) and agreement balances spread evenly to their end
     * date. The components are reported separately; {@code expected} = max(run rate, orders + quotations +
     * agreements).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> forecast(int monthsRaw) {
        int months = Math.max(1, Math.min(12, monthsRaw));
        LocalDate first = LocalDate.now().withDayOfMonth(1);
        BigDecimal history = sum(revenue.invoiced(first.minusMonths(3), first.minusDays(1)), "revenue");
        BigDecimal runRate = history.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        List<LocalDate> buckets = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            buckets.add(first.plusMonths(i));
        }
        LocalDate horizon = first.plusMonths(months).minusDays(1);
        Map<LocalDate, BigDecimal[]> f = new LinkedHashMap<>();
        buckets.forEach(b -> f.put(b, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}));

        for (Map<String, Object> o : db.rows("""
                SELECT COALESCE(o."commitmentDate", o."requestedDate") AS due,
                       sum((l.quantity - l."qtyCancelled" - (l."qtyInvoiced" - l."qtyCredited")) * l."unitPrice"
                           * (1 - l."discountPercent" / 100)) AS open
                  FROM "SalesOrder" o JOIN "SalesOrderLine" l ON l."orderId" = o.id
                 WHERE o."organizationId" = ? AND o.status IN ('confirmed', 'in_fulfilment') AND o."deletedAt" IS NULL
                 GROUP BY 1""", org())) {
            add(f, buckets, (LocalDate) o.get("due"), 0, dec(o.get("open")).max(BigDecimal.ZERO));
        }
        Map<String, Object> conv = db.row("SELECT count(*) FILTER (WHERE status NOT IN ('draft', 'cancelled')) AS quotes,"
                + " count(*) FILTER (WHERE status = 'converted') AS converted FROM \"SalesQuotation\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"issueDate\" >= CURRENT_DATE - 90", org());
        long q = ((Number) conv.get("quotes")).longValue();
        BigDecimal rate = q == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(((Number) conv.get("converted")).longValue())
                .divide(BigDecimal.valueOf(q), 4, RoundingMode.HALF_UP);
        for (Map<String, Object> r : db.rows("SELECT \"validUntil\" AS due, subtotal FROM \"SalesQuotation\" WHERE"
                + " \"organizationId\" = ? AND status IN ('approved', 'sent', 'accepted') AND \"deletedAt\" IS NULL AND"
                + " (\"validUntil\" IS NULL OR \"validUntil\" >= CURRENT_DATE)", org())) {
            add(f, buckets, (LocalDate) r.get("due"), 1, dec(r.get("subtotal")).multiply(rate));
        }
        for (Map<String, Object> a : db.rows("SELECT a.id, a.\"endDate\" FROM \"SalesAgreement\" a WHERE"
                + " a.\"organizationId\" = ? AND a.status = 'active' AND a.\"endDate\" >= CURRENT_DATE", org())) {
            BigDecimal balance = agreementBalance(str(a.get("id")));
            if (balance.signum() <= 0) {
                continue;
            }
            LocalDate end = (LocalDate) a.get("endDate");
            List<LocalDate> span = buckets.stream().filter(b -> !b.isAfter(end)).toList();
            long total = Math.max(1, java.time.temporal.ChronoUnit.MONTHS.between(first, end.withDayOfMonth(1)) + 1);
            BigDecimal perMonth = balance.divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            span.forEach(b -> f.get(b)[2] = f.get(b)[2].add(perMonth));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<LocalDate, BigDecimal[]> e : f.entrySet()) {
            BigDecimal[] v = e.getValue();
            BigDecimal committed = v[0].add(v[1]).add(v[2]);
            rows.add(Obj.of("month", e.getKey().toString().substring(0, 7), "runRate", money(runRate), "openOrders",
                    money(v[0]), "weightedQuotations", money(v[1]), "agreements", money(v[2]), "expected",
                    money(committed.max(runRate))));
        }
        return Obj.of("horizon", horizon, "conversionRate", JsNumbers.js(rate.multiply(SalesPricingEngine.HUNDRED)
                .setScale(1, RoundingMode.HALF_UP)), "months", rows);
    }

    private static void add(Map<LocalDate, BigDecimal[]> f, List<LocalDate> buckets, LocalDate due, int i,
            BigDecimal v) {
        LocalDate b = due == null || due.isBefore(buckets.getFirst()) ? buckets.getFirst() : due.withDayOfMonth(1);
        BigDecimal[] slot = f.get(b);
        if (slot != null) {
            slot[i] = slot[i].add(v);
        }
    }

    /** Unreleased value of an agreement (balance × agreed net price). */
    private BigDecimal agreementBalance(String agreementId) {
        Map<String, Object> r = db.row("""
                SELECT COALESCE(sum(GREATEST(al."contractQty" - COALESCE(rel.qty, 0), 0) * al."unitPrice"
                           * (1 - al."discountPercent" / 100)), 0) AS balance
                  FROM "SalesAgreementLine" al
                  LEFT JOIN (SELECT l."productId", sum((l.quantity - l."qtyCancelled") * l."uomFactor") AS qty
                               FROM "SalesOrderLine" l JOIN "SalesOrder" o ON o.id = l."orderId"
                              WHERE o."agreementId" = ? AND o."deletedAt" IS NULL
                                AND o.status IN ('confirmed', 'in_fulfilment', 'done')
                              GROUP BY l."productId") rel ON rel."productId" = al."productId"
                 WHERE al."agreementId" = ?""", agreementId, agreementId);
        return dec(r.get("balance"));
    }
}
