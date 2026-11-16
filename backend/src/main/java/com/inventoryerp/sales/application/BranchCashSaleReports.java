package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesReportKit.bar;
import static com.inventoryerp.sales.application.SalesReportKit.drill;
import static com.inventoryerp.sales.application.SalesReportKit.money;
import static com.inventoryerp.sales.application.SalesReportKit.num;
import static com.inventoryerp.sales.application.SalesReportKit.pct;
import static com.inventoryerp.sales.application.SalesReportKit.route;
import static com.inventoryerp.sales.application.SalesReportKit.table;
import static com.inventoryerp.sales.application.SalesReportSet.DOC;
import static com.inventoryerp.sales.application.SalesReportSet.def;
import static com.inventoryerp.sales.application.SalesReportSet.with;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.sales.application.SalesReportKit.Bucket;
import com.inventoryerp.sales.application.SalesReportKit.Chart;
import com.inventoryerp.sales.application.SalesReportKit.Def;
import com.inventoryerp.sales.application.SalesReportKit.Filter;
import com.inventoryerp.sales.application.SalesReportKit.Report;
import com.inventoryerp.sales.application.SalesReportKit.Scope;
import com.inventoryerp.sales.application.SalesReportKit.Series;
import com.inventoryerp.sales.application.SalesReportKit.Sql;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Branch cash sales reports of the report center (ADR-S056 / ADR-S057): takings per branch, per day, per payment mode,
 * the cash sale register, products and salespeople per branch, head-office processing and exceptions. They read the
 * cash sale submissions themselves, so a sale counts from the moment the showroom confirms it (stock issued, money
 * banked to the branch tender account, revenue accrued), not only once head office has invoiced it. Takings are
 * confirmed sales; drafts, cancelled and voided sales never count, and voids are shown separately. Amounts are in the
 * functional currency ({@code amount × exchangeRate}).
 */
@Component
class BranchCashSaleReports implements SalesReportSet {

    static final String CAT = "branch-cash";

    /** Statuses of the register's {@code status} filter: execution stage or head-office processing stage. */
    static final Set<String> STATUSES = Set.of("draft", "voided", "not_submitted", "submitted", "under_review",
            "returned_for_correction", "approved", "invoiced", "rejected");

    private static final String RATE = "COALESCE(s.\"exchangeRate\", 1)";

    private static final String SALES = """
            SELECT s.id, s.number, s."saleDate" AS date, s."branchId", COALESCE(b.name, '(no branch)') AS branch,
                   s."partnerId", COALESCE(NULLIF(trim(s."customerName"), ''), pt.name, 'Walk-in customer') AS customer,
                   s."salespersonId",
                   COALESCE(NULLIF(trim(concat_ws(' ', u."firstName", u."lastName")), ''), u.email, '(none)') AS salesperson,
                   ROUND(s.subtotal * %1$s, 6) AS net, ROUND(s."discountTotal" * %1$s, 6) AS discount,
                   ROUND(s."taxTotal" * %1$s, 6) AS tax, ROUND(s."grandTotal" * %1$s, 6) AS total,
                   ROUND(s."paidTotal" * %1$s, 6) AS paid, s."executionStatus" AS execution,
                   s."processingStatus" AS processing, d."documentNumber" AS invoice, s."rejectionReason",
                   s."voidReason", s."deliveryStatus",
                   (SELECT string_agg(DISTINCT CASE WHEN x.method = 'mobile_money' AND x.provider = 'mtn' THEN 'MTN MoMo'
                                                    WHEN x.method = 'mobile_money' AND x.provider = 'airtel' THEN 'Airtel Money'
                                                    WHEN x.method = 'mobile_money' THEN 'Mobile money'
                                                    WHEN x.method = 'bank' THEN 'Bank' ELSE 'Cash' END, ' + ')
                      FROM "SalesSubmissionPayment" x WHERE x."submissionId" = s.id) AS tender,
                   (current_date - s."saleDate") AS "ageDays",
                   (s."invoicedAt"::date - s."saleDate") AS "daysToInvoice",
                   COALESCE(s."voidedAt", s."rejectedAt", s."updatedAt")::date AS "exceptionDate"
              FROM "SalesSubmission" s
              LEFT JOIN "Branch" b ON b.id = s."branchId"
              LEFT JOIN "Partner" pt ON pt.id = s."partnerId"
              LEFT JOIN "User" u ON u.id = s."salespersonId"
              LEFT JOIN "Document" d ON d.id = s."currentInvoiceId"
             WHERE s."organizationId" = ?
            """.formatted(RATE);

    private static final Scope SCOPE = new Scope("s.\"saleDate\"", "s.\"branchId\"", "s.\"salespersonId\"",
            "s.\"partnerId\"",
            "EXISTS (SELECT 1 FROM \"SalesSubmissionLine\" x WHERE x.\"submissionId\" = s.id AND x.\"productId\" = ?)",
            "EXISTS (SELECT 1 FROM \"SalesSubmissionLine\" x JOIN \"Product\" xp ON xp.id = x.\"productId\" WHERE"
                    + " x.\"submissionId\" = s.id AND xp.\"categoryId\" = ?)",
            "pt.\"categoryId\"");

    /** Line scope: product and category filter the lines themselves. */
    private static final Scope LINE_SCOPE = new Scope("s.\"saleDate\"", "s.\"branchId\"", "s.\"salespersonId\"",
            "s.\"partnerId\"", "l.\"productId\" = ?", "pr.\"categoryId\" = ?", "pt.\"categoryId\"");

    private static final String CONFIRMED = " AND s.\"executionStatus\" = 'confirmed'";

    private final SalesDb db;

    BranchCashSaleReports(SalesDb db) {
        this.db = db;
    }

    @Override
    public List<Def> definitions() {
        List<Def> d = new ArrayList<>();
        d.add(def("branch-cash-summary", CAT, "Branch Cash Sales Summary",
                "Takings per branch: sales, gross, discount, net, VAT, total and the cash / mobile money / bank split,"
                        + " with each branch's share and voided sales.", DOC, this::summary));
        d.add(def("branch-cash-daily", CAT, "Daily Cash Takings by Branch",
                "Cash sale takings per day (or week / month) with one column per branch.", with(DOC, "groupBy"),
                this::daily));
        d.add(def("branch-cash-tenders", CAT, "Takings by Payment Mode and Branch",
                "Verified money per branch by tender — cash, MTN MoMo, Airtel Money and bank — and the account it was"
                        + " banked to.", DOC, this::tenders));
        d.add(def("branch-cash-register", CAT, "Branch Cash Sale Register",
                "Every branch cash sale with customer, salesperson, totals, payment, head-office stage and invoice.",
                with(DOC, "status", "limit"), this::register));
        d.add(def("branch-cash-products", CAT, "Products Sold through Branch Cash Sales",
                "Quantity, value, average price and gross profit per product and branch.", with(DOC, "limit"),
                this::products));
        d.add(def("branch-cash-salespeople", CAT, "Branch Cash Sales by Salesperson",
                "Sales, takings and average sale of each salesperson in each branch.", DOC, this::salespeople));
        d.add(def("branch-cash-processing", CAT, "Head-Office Processing by Branch",
                "Confirmed cash sales by head-office stage — not submitted, submitted, under review, returned,"
                        + " approved, invoiced, rejected — with the value awaiting invoice and its age.", DOC,
                this::processing));
        d.add(def("branch-cash-exceptions", CAT, "Voided, Rejected and Returned Cash Sales",
                "Branch cash sales that were voided, rejected or returned for correction, with the reason.", DOC,
                this::exceptions));
        return d;
    }

    // ------------------------------------------------------------------ data

    private List<Map<String, Object>> sales(Filter f, String where) {
        Sql s = new Sql(SALES, org()).add(where);
        SalesReportKit.apply(s, f, SCOPE, true);
        s.add(" ORDER BY s.\"saleDate\", s.number");
        return db.rows(s.toString(), s.params);
    }

    /** Verified payments of confirmed sales grouped by branch, tender and account. */
    private List<Map<String, Object>> tenderRows(Filter f) {
        Sql s = new Sql("""
                SELECT s."branchId", COALESCE(b.name, '(no branch)') AS branch,
                       CASE WHEN pm.method = 'mobile_money' AND pm.provider = 'mtn' THEN 'MTN MoMo'
                            WHEN pm.method = 'mobile_money' AND pm.provider = 'airtel' THEN 'Airtel Money'
                            WHEN pm.method = 'mobile_money' THEN 'Mobile money'
                            WHEN pm.method = 'bank' THEN 'Bank' ELSE 'Cash' END AS tender,
                       COALESCE(a.code || ' ' || a.name, '—') AS account, count(*) AS payments,
                       sum(ROUND(pm.amount * %s, 6)) AS amount
                  FROM "SalesSubmissionPayment" pm
                  JOIN "SalesSubmission" s ON s.id = pm."submissionId"
                  LEFT JOIN "Branch" b ON b.id = s."branchId"
                  LEFT JOIN "Partner" pt ON pt.id = s."partnerId"
                  LEFT JOIN "Account" a ON a.id = pm."accountId"
                 WHERE s."organizationId" = ? AND pm."verificationStatus" = 'verified'
                """.formatted(RATE), org()).add(CONFIRMED);
        SalesReportKit.apply(s, f, SCOPE, true);
        s.add(" GROUP BY 1, 2, 3, 4 ORDER BY 2, 3");
        return db.rows(s.toString(), s.params);
    }

    static final List<String> TENDERS = List.of("Cash", "MTN MoMo", "Airtel Money", "Mobile money", "Bank");

    private static String tenderKey(String tender) {
        return "t_" + tender.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
    }

    // ------------------------------------------------------------------ reports

    Report summary(Filter f) {
        List<Map<String, Object>> rows = sales(f, CONFIRMED);
        List<Map<String, Object>> voided = sales(f, " AND s.\"executionStatus\" = 'voided'");
        Map<String, Map<String, Object>> by = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> g = branchRow(by, r);
            g.put("sales", ((Number) g.get("sales")).intValue() + 1);
            for (String k : List.of("net", "discount", "tax", "total")) {
                g.put(k, dec(g.get(k)).add(dec(r.get(k))));
            }
        }
        for (Map<String, Object> r : voided) {
            Map<String, Object> g = branchRow(by, r);
            g.put("voided", ((Number) g.get("voided")).intValue() + 1);
            g.put("voidedValue", dec(g.get("voidedValue")).add(dec(r.get("total"))));
        }
        List<String> used = new ArrayList<>();
        for (Map<String, Object> t : tenderRows(f)) {
            Map<String, Object> g = branchRow(by, t);
            String key = tenderKey(str(t.get("tender")));
            g.put(key, dec(g.get(key)).add(dec(t.get("amount"))));
            if (!used.contains(str(t.get("tender")))) {
                used.add(str(t.get("tender")));
            }
        }
        BigDecimal all = by.values().stream().map(g -> dec(g.get("total"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> tenders = TENDERS.stream().filter(used::contains).toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : by.values()) {
            BigDecimal total = dec(g.get("total"));
            int n = ((Number) g.get("sales")).intValue();
            Map<String, Object> row = Obj.of("label", g.get("label"), "sales", n,
                    "gross", money(dec(g.get("net")).add(dec(g.get("discount")))), "discount", money(dec(g.get("discount"))),
                    "net", money(dec(g.get("net"))), "tax", money(dec(g.get("tax"))), "total", money(total));
            for (String t : tenders) {
                row.put(tenderKey(t), money(dec(g.get(tenderKey(t)))));
            }
            row.put("avgSale", n == 0 ? 0 : money(total.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP)));
            row.put("share", pct(total, all));
            row.put("voided", g.get("voided"));
            row.put("voidedValue", money(dec(g.get("voidedValue"))));
            if (g.get("branchId") != null) {
                drill(row, "branch-cash-daily", "branchId", g.get("branchId"));
            }
            out.add(row);
        }
        out.sort(SalesReportKit.byDesc("total"));
        Report rep = new Report().col("label", "Branch", "text").col("sales", "Sales", "count")
                .col("gross", "Gross", "money").col("discount", "Discount", "money").col("net", "Net sales", "money")
                .col("tax", "VAT", "money").col("total", "Takings", "money");
        List<String> sums = new ArrayList<>(List.of("sales", "gross", "discount", "net", "tax", "total"));
        for (String t : tenders) {
            rep.col(tenderKey(t), t, "money");
            sums.add(tenderKey(t));
        }
        rep.col("avgSale", "Average sale", "money").col("share", "Share %", "percent").col("voided", "Voided", "count")
                .col("voidedValue", "Voided value", "money").rows(out);
        sums.addAll(List.of("voided", "voidedValue"));
        rep.sumTotals(sums.toArray(String[]::new));
        rep.total("label", "All branches");
        int count = rows.size();
        rep.kpi("total", "Cash sales takings", money(all), "money")
                .kpi("sales", "Cash sales", count, "count")
                .kpi("avgSale", "Average sale", count == 0 ? 0 : money(all.divide(BigDecimal.valueOf(count), 2,
                        RoundingMode.HALF_UP)), "money")
                .kpi("branches", "Branches selling", out.stream().filter(r -> ((Number) r.get("sales")).intValue() > 0)
                        .count(), "count");
        if (!out.isEmpty() && dec(out.getFirst().get("total")).signum() > 0) {
            rep.kpi("top", "Top branch", out.getFirst().get("share"), "percent", str(out.getFirst().get("label")));
        }
        rep.chart("bar", "label", bar("total", "Takings"), bar("net", "Net sales"));
        rep.note("Takings are confirmed branch cash sales (goods handed over and money verified), whether or not head"
                + " office has invoiced them yet. Voided sales are excluded from takings and shown separately.");
        return rep;
    }

    private static Map<String, Object> branchRow(Map<String, Map<String, Object>> by, Map<String, Object> r) {
        String id = r.get("branchId") == null ? "-" : str(r.get("branchId"));
        return by.computeIfAbsent(id, k -> Obj.of("branchId", r.get("branchId"), "label", r.get("branch"), "sales", 0,
                "net", BigDecimal.ZERO, "discount", BigDecimal.ZERO, "tax", BigDecimal.ZERO, "total", BigDecimal.ZERO,
                "voided", 0, "voidedValue", BigDecimal.ZERO));
    }

    Report daily(Filter f) {
        Bucket b = Bucket.of(f.groupBy(), Bucket.DAY);
        List<Map<String, Object>> rows = sales(f, CONFIRMED);
        Map<String, String> branches = new LinkedHashMap<>();
        rows.stream().sorted(java.util.Comparator.comparing(r -> str(r.get("branch")))).forEach(r -> branches
                .putIfAbsent(r.get("branchId") == null ? "-" : str(r.get("branchId")), str(r.get("branch"))));
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String k : b.keys(f.from(), f.to())) {
            Map<String, Object> x = Obj.of("label", b.label(k), "sales", 0);
            branches.keySet().forEach(id -> x.put("b_" + id, BigDecimal.ZERO));
            x.put("total", BigDecimal.ZERO);
            m.put(k, x);
        }
        for (Map<String, Object> r : rows) {
            Map<String, Object> x = m.get(b.key(SalesReportKit.asDate(r.get("date"))));
            if (x == null) {
                continue;
            }
            String k = "b_" + (r.get("branchId") == null ? "-" : str(r.get("branchId")));
            x.put(k, dec(x.get(k)).add(dec(r.get("total"))));
            x.put("total", dec(x.get("total")).add(dec(r.get("total"))));
            x.put("sales", ((Number) x.get("sales")).intValue() + 1);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal prev = null;
        for (Map<String, Object> x : m.values()) {
            BigDecimal total = dec(x.get("total"));
            x.replaceAll((k, v) -> v instanceof BigDecimal d ? money(d) : v);
            x.put("change", prev == null ? null : pct(total.subtract(prev), prev));
            prev = total;
            out.add(x);
        }
        Report rep = new Report().col("label", "Period", "text").col("sales", "Sales", "count");
        List<Series> series = new ArrayList<>();
        List<String> sums = new ArrayList<>(List.of("sales"));
        branches.forEach((id, name) -> {
            rep.col("b_" + id, name, "money");
            series.add(bar("b_" + id, name));
            sums.add("b_" + id);
        });
        rep.col("total", "Total takings", "money").col("change", "Change %", "percent").rows(out);
        sums.add("total");
        rep.sumTotals(sums.toArray(String[]::new));
        rep.total("label", "Total");
        BigDecimal total = dec(rep.totals.get("total"));
        long active = out.stream().filter(x -> ((Number) x.get("sales")).intValue() > 0).count();
        rep.kpi("total", "Takings", rep.totals.get("total"), "money")
                .kpi("sales", "Cash sales", rep.totals.get("sales"), "count")
                .kpi("avg", "Average per " + b.name().toLowerCase(java.util.Locale.ROOT) + " with sales", active == 0 ? 0
                        : money(total.divide(BigDecimal.valueOf(active), 2, RoundingMode.HALF_UP)), "money");
        out.stream().max(java.util.Comparator.comparing(x -> dec(x.get("total")))).filter(x -> dec(x.get("total"))
                .signum() > 0).ifPresent(x -> rep.kpi("best", "Best " + b.name().toLowerCase(java.util.Locale.ROOT),
                        x.get("total"), "money", str(x.get("label"))));
        rep.chart = new Chart("bar", "label", series, true);
        return rep;
    }

    Report tenders(Filter f) {
        List<Map<String, Object>> rows = tenderRows(f);
        Map<String, BigDecimal> perBranch = new LinkedHashMap<>();
        rows.forEach(r -> perBranch.merge(str(r.get("branch")), dec(r.get("amount")), BigDecimal::add));
        BigDecimal all = perBranch.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Map<String, Object>> chart = new LinkedHashMap<>();
        Map<String, BigDecimal> perTender = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            BigDecimal amount = dec(r.get("amount"));
            String tender = str(r.get("tender"));
            out.add(Obj.of("branch", r.get("branch"), "tender", tender, "account", r.get("account"), "payments",
                    ((Number) r.get("payments")).intValue(), "amount", money(amount), "branchShare",
                    pct(amount, perBranch.get(str(r.get("branch")))), "share", pct(amount, all)));
            Map<String, Object> c = chart.computeIfAbsent(str(r.get("branch")), k -> Obj.of("label", k));
            c.put(tenderKey(tender), money(dec(c.get(tenderKey(tender))).add(amount)));
            perTender.merge(tender, amount, BigDecimal::add);
        }
        Report rep = table(out, "branch|Branch|text", "tender|Payment mode|text", "account|Banked to|text",
                "payments|Payments|count", "amount|Amount|money", "branchShare|Share of branch %|percent",
                "share|Share of all %|percent").sumTotals("payments", "amount");
        rep.total("branch", "Total");
        rep.kpi("amount", "Verified takings", money(all), "money");
        for (String t : TENDERS) {
            if (perTender.containsKey(t)) {
                rep.kpi(tenderKey(t), t, money(perTender.get(t)), "money", String.valueOf(pct(perTender.get(t), all))
                        + "% of takings");
            }
        }
        List<Series> series = TENDERS.stream().filter(perTender::containsKey).map(t -> bar(tenderKey(t), t)).toList();
        rep.chart = new Chart("bar", "label", series, true);
        rep.chartData(new ArrayList<>(chart.values()));
        return rep;
    }

    Report register(Filter f) {
        String where;
        if (f.status() != null) {
            if (!STATUSES.contains(f.status())) {
                throw ApiException.badRequest("status must be one of: draft, not_submitted, submitted, under_review,"
                        + " returned_for_correction, approved, invoiced, rejected, voided");
            }
            switch (f.status()) {
                case "draft", "voided" -> where = " AND s.\"executionStatus\" = '" + f.status() + "'";
                default -> where = CONFIRMED + " AND s.\"processingStatus\" = '" + f.status() + "'";
            }
        } else {
            where = " AND s.\"executionStatus\" <> 'cancelled'";
        }
        List<Map<String, Object>> rows = sales(f, where);
        rows = new ArrayList<>(rows.reversed());
        int limit = f.limit(500);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows.subList(0, Math.min(limit, rows.size()))) {
            String stage = "confirmed".equals(r.get("execution")) ? str(r.get("processing")) : str(r.get("execution"));
            out.add(route(Obj.of("number", r.get("number"), "date", r.get("date"), "branch", r.get("branch"),
                    "customer", r.get("customer"), "salesperson", r.get("salesperson"), "net", money(dec(r.get("net"))),
                    "tax", money(dec(r.get("tax"))), "total", money(dec(r.get("total"))), "paid",
                    money(dec(r.get("paid"))), "tender", r.get("tender"), "stage", stage, "invoice",
                    r.get("invoice")), "/sales/cash-sales/" + r.get("id")));
        }
        Report rep = table(out, "number|Number|text", "date|Date|date", "branch|Branch|text", "customer|Customer|text",
                "salesperson|Salesperson|text", "net|Net|money", "tax|VAT|money", "total|Total|money",
                "paid|Paid|money", "tender|Paid by|text", "stage|Stage|status", "invoice|Invoice|text");
        rep.sumTotals("net", "tax", "total", "paid");
        rep.total("number", "Total");
        long invoiced = rows.stream().filter(r -> "invoiced".equals(r.get("processing"))).count();
        BigDecimal awaiting = rows.stream().filter(r -> "confirmed".equals(r.get("execution"))
                && !"invoiced".equals(r.get("processing"))).map(r -> dec(r.get("total"))).reduce(BigDecimal.ZERO,
                        BigDecimal::add);
        rep.kpi("count", "Sales", rows.size(), "count").kpi("total", "Value", rep.totals.get("total"), "money")
                .kpi("invoiced", "Invoiced", invoiced, "count")
                .kpi("awaiting", "Awaiting invoice", money(awaiting), "money");
        if (rows.size() > limit) {
            rep.note("Showing the latest " + limit + " of " + rows.size() + " sales; narrow the period or raise the"
                    + " limit.");
        }
        return rep;
    }

    Report products(Filter f) {
        Sql s = new Sql("""
                SELECT l."productId", COALESCE(pr.name, l.description) AS product, COALESCE(b.name, '(no branch)') AS branch,
                       s."branchId", COALESCE(uu.code, pu.code) AS unit, count(DISTINCT s.id) AS sales,
                       sum(l.quantity) AS quantity, sum(ROUND(l."lineSubtotal" * %1$s, 6)) AS net,
                       sum(ROUND(COALESCE(l."costSnapshot", 0) * l.quantity * %1$s, 6)) AS cost
                  FROM "SalesSubmissionLine" l
                  JOIN "SalesSubmission" s ON s.id = l."submissionId"
                  LEFT JOIN "Product" pr ON pr.id = l."productId"
                  LEFT JOIN "UnitOfMeasure" uu ON uu.id = l."uomId"
                  LEFT JOIN "UnitOfMeasure" pu ON pu.id = pr."uomId"
                  LEFT JOIN "Branch" b ON b.id = s."branchId"
                  LEFT JOIN "Partner" pt ON pt.id = s."partnerId"
                 WHERE s."organizationId" = ?
                """.formatted(RATE), org()).add(CONFIRMED);
        SalesReportKit.apply(s, f, LINE_SCOPE, true);
        s.add(" GROUP BY 1, 2, 3, 4, 5 ORDER BY net DESC");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        BigDecimal all = SalesReportKit.sum(rows, "net");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows.subList(0, Math.min(f.limit(200), rows.size()))) {
            BigDecimal qty = dec(r.get("quantity"));
            BigDecimal net = dec(r.get("net"));
            BigDecimal cost = dec(r.get("cost"));
            out.add(Obj.of("product", r.get("product"), "branch", r.get("branch"), "unit", r.get("unit"), "sales",
                    ((Number) r.get("sales")).intValue(), "quantity", num(qty), "net", money(net), "avgPrice",
                    qty.signum() == 0 ? null : money(net.divide(qty, 2, RoundingMode.HALF_UP)), "cost", money(cost),
                    "grossProfit", money(net.subtract(cost)), "marginPct", pct(net.subtract(cost), net), "share",
                    pct(net, all)));
        }
        Report rep = table(out, "product|Product|text", "branch|Branch|text", "unit|Unit|text", "sales|Sales|count",
                "quantity|Quantity|qty", "net|Net sales|money", "avgPrice|Average price|money", "cost|Cost|money",
                "grossProfit|Gross profit|money", "marginPct|Margin %|percent", "share|Share %|percent")
                .sumTotals("quantity", "net", "cost", "grossProfit");
        rep.total("product", "Total");
        rep.total("marginPct", pct(dec(rep.totals.get("grossProfit")), dec(rep.totals.get("net"))));
        rep.kpi("net", "Net sales", rep.totals.get("net"), "money")
                .kpi("products", "Products sold", rows.stream().map(r -> r.get("productId")).distinct().count(),
                        "count")
                .kpi("grossProfit", "Gross profit", rep.totals.get("grossProfit"), "money")
                .kpi("marginPct", "Gross margin", rep.totals.get("marginPct"), "percent");
        rep.chart("hbar", "product", bar("net", "Net sales"));
        rep.note("Cost is the issue cost recorded when the sale was confirmed (moving average at the branch location).");
        return rep;
    }

    Report salespeople(Filter f) {
        List<Map<String, Object>> rows = sales(f, CONFIRMED);
        Map<String, Map<String, Object>> by = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String k = r.get("salespersonId") + "|" + r.get("branchId");
            Map<String, Object> g = by.computeIfAbsent(k, x -> Obj.of("salesperson", r.get("salesperson"), "branch",
                    r.get("branch"), "sales", 0, "net", BigDecimal.ZERO, "total", BigDecimal.ZERO, "customers",
                    new java.util.HashSet<>()));
            g.put("sales", ((Number) g.get("sales")).intValue() + 1);
            g.put("net", dec(g.get("net")).add(dec(r.get("net"))));
            g.put("total", dec(g.get("total")).add(dec(r.get("total"))));
            @SuppressWarnings("unchecked")
            Set<Object> c = (Set<Object>) g.get("customers");
            c.add(r.get("partnerId") == null ? r.get("customer") : r.get("partnerId"));
        }
        BigDecimal all = SalesReportKit.sum(rows, "total");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : by.values()) {
            int n = ((Number) g.get("sales")).intValue();
            BigDecimal total = dec(g.get("total"));
            out.add(Obj.of("salesperson", g.get("salesperson"), "branch", g.get("branch"), "sales", n, "customers",
                    ((Set<?>) g.get("customers")).size(), "net", money(dec(g.get("net"))), "total", money(total),
                    "avgSale", money(total.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP)), "share",
                    pct(total, all)));
        }
        out.sort(SalesReportKit.byDesc("total"));
        Report rep = table(out, "salesperson|Salesperson|text", "branch|Branch|text", "sales|Sales|count",
                "customers|Customers|count", "net|Net sales|money", "total|Takings|money",
                "avgSale|Average sale|money", "share|Share %|percent").sumTotals("sales", "net", "total");
        rep.total("salesperson", "Total");
        rep.kpi("total", "Takings", rep.totals.get("total"), "money").kpi("people", "Salespeople",
                out.stream().map(r -> r.get("salesperson")).distinct().count(), "count");
        if (!out.isEmpty()) {
            rep.kpi("top", "Top salesperson", out.getFirst().get("total"), "money",
                    out.getFirst().get("salesperson") + " · " + out.getFirst().get("branch"));
        }
        rep.chart("hbar", "salesperson", bar("total", "Takings"));
        return rep;
    }

    static final List<String> STAGES = List.of("not_submitted", "submitted", "under_review", "returned_for_correction",
            "approved", "invoiced", "rejected");

    Report processing(Filter f) {
        List<Map<String, Object>> rows = sales(f, CONFIRMED);
        Map<String, Map<String, Object>> by = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> g = by.computeIfAbsent(r.get("branchId") == null ? "-" : str(r.get("branchId")),
                    k -> {
                        Map<String, Object> n = Obj.of("label", r.get("branch"), "confirmed", 0);
                        STAGES.forEach(st -> n.put(st, 0));
                        n.put("awaiting", BigDecimal.ZERO);
                        n.put("oldestDays", null);
                        n.put("invoiceDays", new ArrayList<Integer>());
                        return n;
                    });
            g.put("confirmed", ((Number) g.get("confirmed")).intValue() + 1);
            String st = str(r.get("processing"));
            if (g.containsKey(st)) {
                g.put(st, ((Number) g.get(st)).intValue() + 1);
            }
            if ("invoiced".equals(st)) {
                if (r.get("daysToInvoice") instanceof Number d) {
                    @SuppressWarnings("unchecked")
                    List<Integer> days = (List<Integer>) g.get("invoiceDays");
                    days.add(d.intValue());
                }
            } else {
                g.put("awaiting", dec(g.get("awaiting")).add(dec(r.get("total"))));
                if (r.get("ageDays") instanceof Number age && (g.get("oldestDays") == null
                        || age.intValue() > ((Number) g.get("oldestDays")).intValue())) {
                    g.put("oldestDays", age.intValue());
                }
            }
        }
        List<Integer> allDays = new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : by.values()) {
            @SuppressWarnings("unchecked")
            List<Integer> days = (List<Integer>) g.remove("invoiceDays");
            allDays.addAll(days);
            g.put("avgDaysToInvoice", days.isEmpty() ? null : num(BigDecimal.valueOf(days.stream().mapToInt(i -> i)
                    .average().orElse(0)).setScale(1, RoundingMode.HALF_UP)));
            g.put("awaiting", money(dec(g.get("awaiting"))));
            out.add(g);
        }
        Report rep = new Report().col("label", "Branch", "text").col("confirmed", "Confirmed", "count")
                .col("not_submitted", "Not submitted", "count").col("submitted", "Submitted", "count")
                .col("under_review", "Under review", "count").col("returned_for_correction", "Returned", "count")
                .col("approved", "Approved", "count").col("invoiced", "Invoiced", "count")
                .col("rejected", "Rejected", "count").col("awaiting", "Awaiting invoice", "money")
                .col("oldestDays", "Oldest pending (days since sale)", "days").col("avgDaysToInvoice", "Avg days sale to invoice", "number")
                .rows(out);
        List<String> sums = new ArrayList<>(List.of("confirmed", "awaiting"));
        sums.addAll(STAGES);
        rep.sumTotals(sums.toArray(String[]::new));
        rep.total("label", "All branches");
        long pending = rows.stream().filter(r -> !"invoiced".equals(r.get("processing"))).count();
        rep.kpi("awaiting", "Awaiting invoice", rep.totals.get("awaiting"), "money", pending + " sales")
                .kpi("invoiced", "Invoiced", rep.totals.get("invoiced"), "count")
                .kpi("rejected", "Rejected", rep.totals.get("rejected"), "count")
                .kpi("avgDays", "Average days sale to invoice", allDays.isEmpty() ? null : num(BigDecimal.valueOf(allDays
                        .stream().mapToInt(i -> i).average().orElse(0)).setScale(1, RoundingMode.HALF_UP)), "number");
        rep.stacked("bar", "label", bar("not_submitted", "Not submitted"), bar("submitted", "Submitted"),
                bar("under_review", "Under review"), bar("returned_for_correction", "Returned"),
                bar("approved", "Approved"), bar("invoiced", "Invoiced"), bar("rejected", "Rejected"));
        return rep;
    }

    Report exceptions(Filter f) {
        List<Map<String, Object>> rows = sales(f, " AND (s.\"executionStatus\" = 'voided' OR (s.\"executionStatus\" ="
                + " 'confirmed' AND s.\"processingStatus\" IN ('rejected', 'returned_for_correction')))");
        Map<String, String> returnReasons = new LinkedHashMap<>();
        List<String> returned = rows.stream().filter(r -> "returned_for_correction".equals(r.get("processing")))
                .map(r -> str(r.get("id"))).toList();
        if (!returned.isEmpty()) {
            for (Map<String, Object> e : db.rows("SELECT DISTINCT ON (\"submissionId\") \"submissionId\", reason FROM"
                    + " \"SalesSubmissionEvent\" WHERE \"organizationId\" = ? AND action = 'return' AND \"submissionId\" IN ("
                    + SalesDb.marks(returned.size()) + ") ORDER BY \"submissionId\", \"createdAt\" DESC", concat(org(), returned))) {
                returnReasons.put(str(e.get("submissionId")), str(e.get("reason")));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            boolean voided = "voided".equals(r.get("execution"));
            String status = voided ? "voided" : str(r.get("processing"));
            String reason = voided ? str(r.get("voidReason")) : "rejected".equals(status) ? str(r.get("rejectionReason"))
                    : returnReasons.get(str(r.get("id")));
            out.add(route(Obj.of("number", r.get("number"), "date", r.get("date"), "branch", r.get("branch"),
                    "customer", r.get("customer"), "salesperson", r.get("salesperson"), "total",
                    money(dec(r.get("total"))), "status", status, "reason", reason, "on", r.get("exceptionDate")),
                    "/sales/cash-sales/" + r.get("id")));
        }
        Report rep = table(out, "number|Number|text", "date|Sale date|date", "branch|Branch|text",
                "customer|Customer|text", "salesperson|Salesperson|text", "total|Total|money", "status|Status|status",
                "reason|Reason|text", "on|Since|date").sumTotals("total");
        rep.total("number", "Total");
        Map<String, Long> byStatus = new LinkedHashMap<>();
        out.forEach(r -> byStatus.merge(str(r.get("status")), 1L, Long::sum));
        rep.kpi("voided", "Voided", byStatus.getOrDefault("voided", 0L), "count")
                .kpi("rejected", "Rejected", byStatus.getOrDefault("rejected", 0L), "count")
                .kpi("returned", "Returned for correction", byStatus.getOrDefault("returned_for_correction", 0L),
                        "count")
                .kpi("total", "Value", rep.totals.get("total"), "money");
        List<Map<String, Object>> chart = new ArrayList<>();
        byStatus.forEach((k, v) -> chart.add(Obj.of("label", SalesReportKit.label(k), "value", v)));
        rep.chart("pie", "label", bar("value", "Sales")).chartData(chart);
        return rep;
    }

    private static List<Object> concat(Object first, List<String> rest) {
        List<Object> out = new ArrayList<>();
        out.add(first);
        out.addAll(rest);
        return out;
    }
}
