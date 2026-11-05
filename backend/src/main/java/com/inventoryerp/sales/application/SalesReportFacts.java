package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesReportKit.bar;
import static com.inventoryerp.sales.application.SalesReportKit.line;
import static com.inventoryerp.sales.application.SalesReportKit.money;
import static com.inventoryerp.sales.application.SalesReportKit.num;
import static com.inventoryerp.sales.application.SalesReportKit.pct;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.sales.application.SalesReportKit.Bucket;
import com.inventoryerp.sales.application.SalesReportKit.Filter;
import com.inventoryerp.sales.application.SalesReportKit.Report;
import com.inventoryerp.sales.application.SalesReportKit.Sql;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The sales fact of the report center (ADR-S057): one row per product line of a posted sales invoice or credit note
 * (credit notes negative), with its customer, branch, salesperson, team, channel, product, category and unit, and
 * gross / discount / net revenue / tax / cost. Cost is the actual issue cost from the inventory ledger — the
 * invoice's own issue, the branch cash sale's issue or the delivery trips of the sales order — and falls back to the
 * product's cost price only where no issue exists ({@code actualCost = false}). Every report that shows revenue reads
 * this one query, so the dashboard and the detailed reports always agree.
 */
@Component
public class SalesReportFacts {

    /** Grouping dimensions of the fact. */
    enum Dim {
        CUSTOMER("\"partnerId\""), PRODUCT("\"productId\""), CATEGORY("\"categoryId\""),
        CUSTOMER_CATEGORY("\"customerCategoryId\""), BRANCH("\"branchId\""), SALESPERSON("\"salespersonId\""),
        TEAM("\"teamId\""), CHANNEL("channel"), UOM("\"uomId\""), DOCUMENT("\"documentId\""), DAY(null), WEEK(null),
        MONTH(null), QUARTER(null), YEAR(null);

        final String column;

        Dim(String column) {
            this.column = column;
        }

        Bucket bucket() {
            return column == null ? Bucket.valueOf(name()) : null;
        }

        String sql() {
            return column == null ? bucket().sql("f.date") : "f." + column;
        }

        static Dim of(String v, Dim fallback) {
            if (v == null) {
                return fallback;
            }
            String n = v.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
            try {
                return valueOf(n);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("dimension must be one of: customer, product, category, customerCategory,"
                        + " branch, salesperson, team, channel, uom, day, week, month, quarter, year");
            }
        }

        String title() {
            return switch (this) {
                case CUSTOMER -> "Customer";
                case PRODUCT -> "Product";
                case CATEGORY -> "Product category";
                case CUSTOMER_CATEGORY -> "Customer type";
                case BRANCH -> "Branch";
                case SALESPERSON -> "Salesperson";
                case TEAM -> "Sales team";
                case CHANNEL -> "Sales channel";
                case UOM -> "Unit";
                case DOCUMENT -> "Invoice";
                default -> "Period";
            };
        }
    }

    static final Map<String, String> CHANNELS = new LinkedHashMap<>();

    static {
        CHANNELS.put("sales_order", "Sales order");
        CHANNELS.put("branch_cash", "Branch cash sale");
        CHANNELS.put("pos", "Point of sale");
        CHANNELS.put("direct", "Direct invoice");
    }

    private static final String COST_CTES = """
            WITH ic AS (SELECT "referenceId" AS ref, "productId", sum("totalValue") / NULLIF(-sum("quantityChange"), 0)
                          AS unit FROM "InventoryLedger" WHERE "organizationId" = ? AND "referenceType" = 'sales_invoice'
                           AND "quantityChange" < 0 GROUP BY 1, 2),
                 cc AS (SELECT "referenceId" AS ref, "productId", sum("totalValue") / NULLIF(-sum("quantityChange"), 0)
                          AS unit FROM "InventoryLedger" WHERE "organizationId" = ? AND "referenceType" = 'cash_sale'
                           AND "quantityChange" < 0 GROUP BY 1, 2),
                 tc AS (SELECT o."sourceId" AS ref, il."productId",
                               sum(il."totalValue") / NULLIF(-sum(il."quantityChange"), 0) AS unit
                          FROM "InventoryLedger" il JOIN "FleetLoad" t ON t.id = il."referenceId"
                          JOIN "FleetDeliveryOrder" o ON o.id = t."deliveryOrderId" AND o."sourceType" = 'sales_order'
                         WHERE il."organizationId" = ? AND il."referenceType" = 'fleet_trip' AND il."quantityChange" < 0
                         GROUP BY 1, 2),
            """;

    private static final String FACT = """
                 fact AS (
            SELECT d.id AS "documentId", d."documentNumber" AS number, d."documentType"::text AS type,
                   d."issueDate"::date AS date, d."partnerId", p."categoryId" AS "customerCategoryId", d."branchId",
                   COALESCE(d."salespersonId", so."ownerId", rso."ownerId", ss."salespersonId", rss."salespersonId",
                            p."salespersonId") AS "salespersonId",
                   COALESCE(so."teamId", rso."teamId", p."salesTeamId") AS "teamId", COALESCE(so.id, rso.id) AS "orderId",
                   l."productId", pr."categoryId", COALESCE(l."uomId", pr."salesUomId", pr."uomId") AS "uomId",
                   CASE WHEN ss.id IS NOT NULL OR rss.id IS NOT NULL THEN 'branch_cash'
                        WHEN so.id IS NOT NULL OR rso.id IS NOT NULL THEN 'sales_order'
                        WHEN d."tableId" IS NOT NULL OR d."sourceType" LIKE 'pos%' THEN 'pos' ELSE 'direct' END AS channel,
                   d."paymentMode"::text AS "paymentMode",
                   sg.s * l.quantity AS quantity,
                   sg.s * l.quantity * COALESCE(l."uomFactor", 1) AS "baseQty",
                   sg.s * l.subtotal AS revenue,
                   sg.s * CASE WHEN l."discountPercent" > 0 AND l."discountPercent" < 100
                               THEN l.subtotal * l."discountPercent" / (100 - l."discountPercent") ELSE 0 END AS discount,
                   sg.s * COALESCE(l."taxAmount", 0) AS tax,
                   sg.s * l.quantity * COALESCE(l."uomFactor", 1)
                        * COALESCE(ic.unit, cc.unit, rcc.unit, tc.unit, pr."costPrice", 0) AS cost,
                   (ic.unit IS NOT NULL OR cc.unit IS NOT NULL OR rcc.unit IS NOT NULL OR tc.unit IS NOT NULL)
                        AS "actualCost",
                   COALESCE(l."discountPercent", 0) AS "discountPercent", l."unitPrice", pr."salesPrice" AS "listPrice"
              FROM "DocumentLine" l JOIN "Document" d ON d.id = l."documentId"
             CROSS JOIN LATERAL (SELECT CASE WHEN d."documentType" = 'credit_note' THEN -1 ELSE 1 END AS s) sg
              LEFT JOIN "Partner" p ON p.id = d."partnerId"
              LEFT JOIN "Product" pr ON pr.id = l."productId"
              LEFT JOIN "SalesOrder" so ON d."sourceType" = 'sales_order' AND so.id = d."sourceId"
              LEFT JOIN "Document" rd ON rd.id = d."reversedDocumentId"
              LEFT JOIN "SalesOrder" rso ON rd."sourceType" = 'sales_order' AND rso.id = rd."sourceId"
              LEFT JOIN "SalesSubmission" ss ON ss."organizationId" = d."organizationId" AND ss."currentInvoiceId" = d.id
              LEFT JOIN "SalesSubmission" rss ON rss."organizationId" = d."organizationId" AND rss."currentInvoiceId" = rd.id
              LEFT JOIN ic ON ic.ref = COALESCE(rd.id, d.id) AND ic."productId" = l."productId"
              LEFT JOIN cc ON cc.ref = ss.id AND cc."productId" = l."productId"
              LEFT JOIN cc rcc ON rcc.ref = rss.id AND rcc."productId" = l."productId"
              LEFT JOIN tc ON tc.ref = COALESCE(so.id, rso.id) AND tc."productId" = l."productId"
             WHERE d."organizationId" = ? AND d."documentType" IN ('sales_invoice', 'credit_note')
               AND d.status IN ('posted', 'paid') AND l."productId" IS NOT NULL
               AND d."issueDate" >= ? AND d."issueDate" < ?)
            """;

    private static final String MEASURES = """
             sum(f.quantity) AS quantity, sum(f."baseQty") AS "baseQty", sum(f.revenue) AS revenue,
             sum(f.discount) AS discount, sum(f.tax) AS tax, sum(f.cost) AS cost,
             count(DISTINCT f."documentId") FILTER (WHERE f.type = 'sales_invoice') AS documents,
             count(DISTINCT f."documentId") FILTER (WHERE f.type = 'credit_note') AS "creditNotes",
             COALESCE(-sum(f.revenue) FILTER (WHERE f.type = 'credit_note'), 0) AS returns,
             count(DISTINCT f."partnerId") AS customers, count(*) AS lines,
             count(*) FILTER (WHERE f."actualCost") AS "actualLines", min(f.date) AS "firstDate", max(f.date) AS "lastDate",
             max(f."discountPercent") AS "maxDiscountPercent",
             count(*) FILTER (WHERE f."discountPercent" > 0) AS "discountedLines"
            """;

    private final SalesDb db;

    public SalesReportFacts(SalesDb db) {
        this.db = db;
    }

    /** {@code WITH … fact AS (…)} for {@code [from, to]} with the shared filter in the outer WHERE. */
    Sql base(Filter f, String select) {
        Sql s = new Sql(COST_CTES + FACT + select + " FROM fact f WHERE 1 = 1", org(), org(), org(), org(), f.from(),
                f.to().plusDays(1));
        s.in("f.\"branchId\"", f.branchIds());
        s.eq("f.\"salespersonId\"", f.salespersonId());
        s.eq("f.\"partnerId\"", f.partnerId());
        s.eq("f.\"productId\"", f.productId());
        s.eq("f.\"categoryId\"", f.categoryId());
        s.eq("f.\"customerCategoryId\"", f.customerCategoryId());
        s.eq("f.channel", f.channel());
        return s;
    }

    /** Raw fact lines (detail reports). */
    List<Map<String, Object>> lines(Filter f, String extraWhere, String order) {
        Sql s = base(f, "SELECT f.*").add(extraWhere == null ? "" : extraWhere).add(" ORDER BY " + order);
        return db.rows(s.toString(), s.params);
    }

    /** Measures grouped by up to two dimensions ({@code k0}, {@code k1}); no dimension = one total row. */
    List<Map<String, Object>> aggregate(Filter f, Dim... dims) {
        return aggregateWhere(f, null, dims);
    }

    /** {@link #aggregate} restricted by an extra predicate on the fact ({@code " AND f.type = 'credit_note'"}). */
    List<Map<String, Object>> aggregateWhere(Filter f, String where, Dim... dims) {
        StringBuilder sel = new StringBuilder("SELECT ");
        for (int i = 0; i < dims.length; i++) {
            sel.append(dims[i].sql()).append(" AS k").append(i).append(", ");
        }
        sel.append(MEASURES);
        Sql s = base(f, sel.toString());
        if (where != null) {
            s.add(where);
        }
        if (dims.length > 0) {
            s.add(" GROUP BY ");
            for (int i = 0; i < dims.length; i++) {
                s.add((i > 0 ? ", " : "") + (i + 1));
            }
        }
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        for (Map<String, Object> r : rows) {
            enrich(r);
        }
        for (int i = 0; i < dims.length; i++) {
            label(rows, dims[i], "k" + i, "label" + (i == 0 ? "" : String.valueOf(i)));
        }
        return rows;
    }

    /** The totals row of a filter (zeros when there are no lines). */
    Map<String, Object> totals(Filter f) {
        List<Map<String, Object>> r = aggregate(f);
        return r.isEmpty() ? enrich(new LinkedHashMap<>()) : r.getFirst();
    }

    /** Adds gross, margin, margin %, average order value and cost coverage to a measures row. */
    static Map<String, Object> enrich(Map<String, Object> r) {
        BigDecimal rev = dec(r.get("revenue"));
        BigDecimal disc = dec(r.get("discount"));
        BigDecimal cost = dec(r.get("cost"));
        BigDecimal ret = dec(r.get("returns"));
        long docs = dec(r.get("documents")).longValue();
        r.put("revenue", money(rev));
        r.put("discount", money(disc));
        r.put("cost", money(cost));
        r.put("tax", money(dec(r.get("tax"))));
        r.put("returns", money(ret));
        r.put("quantity", num(dec(r.get("quantity"))));
        r.put("baseQty", num(dec(r.get("baseQty"))));
        r.put("gross", money(rev.add(disc).add(ret)));
        r.put("margin", money(rev.subtract(cost)));
        r.put("marginPercent", pct(rev.subtract(cost), rev));
        r.put("discountPercent", pct(disc, rev.add(disc)));
        r.put("aov", docs == 0 ? null : money(rev.divide(BigDecimal.valueOf(docs), 2, java.math.RoundingMode.HALF_UP)));
        r.put("documents", docs);
        r.putIfAbsent("customers", 0);
        r.putIfAbsent("lines", 0);
        r.put("costCoverage", pct(dec(r.get("actualLines")), dec(r.get("lines"))));
        return r;
    }

    /** Fills {@code target} from the display names of the {@code source} keys. */
    void label(List<Map<String, Object>> rows, Dim dim, String source, String target) {
        Map<String, String> names = names(dim, rows.stream().map(r -> str(r.get(source))).toList());
        for (Map<String, Object> r : rows) {
            String k = str(r.get(source));
            String n = k == null ? null : names.get(k);
            r.put(target, n != null ? n : k == null ? "(none)" : dim.bucket() != null ? dim.bucket().label(k) : k);
        }
    }

    /** Display names of dimension keys. */
    Map<String, String> names(Dim dim, Collection<String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> ids = keys.stream().filter(k -> k != null).distinct().toList();
        if (ids.isEmpty() || dim.bucket() != null) {
            return out;
        }
        if (dim == Dim.CHANNEL) {
            ids.forEach(k -> out.put(k, CHANNELS.getOrDefault(k, k)));
            return out;
        }
        String sql = switch (dim) {
            case PRODUCT -> "SELECT id, COALESCE(NULLIF(code, '') || ' · ', '') || name AS name FROM \"Product\"";
            case CATEGORY -> "SELECT id, name FROM \"ProductCategory\"";
            case CUSTOMER -> "SELECT id, name FROM \"Partner\"";
            case CUSTOMER_CATEGORY -> "SELECT id, name FROM \"PartnerCategory\"";
            case BRANCH -> "SELECT id, name FROM \"Branch\"";
            case TEAM -> "SELECT id, name FROM \"CrmTeam\"";
            case UOM -> "SELECT id, COALESCE(symbol, name) AS name FROM \"UnitOfMeasure\"";
            case DOCUMENT -> "SELECT id, \"documentNumber\" AS name FROM \"Document\"";
            default -> "SELECT id, trim(\"firstName\" || ' ' || COALESCE(\"lastName\", '')) AS name FROM \"User\"";
        };
        List<Object> p = new ArrayList<>();
        p.add(org());
        p.addAll(ids);
        for (Map<String, Object> r : db.rows(sql + " WHERE \"organizationId\" = ? AND id IN (" + SalesDb.marks(ids.size())
                + ")", p)) {
            out.put(str(r.get("id")), str(r.get("name")));
        }
        return out;
    }

    String name(Dim dim, String id) {
        return id == null ? null : names(dim, List.of(id)).get(id);
    }

    // ------------------------------------------------------------------ standard report shapes

    /**
     * Revenue by one dimension: label, invoices, quantity, gross, discount, net revenue, share and (with
     * {@code profit}) cost and margin. Time dimensions are filled bucket by bucket and keep date order; others are
     * ranked by revenue, top {@code limit}.
     */
    Report breakdown(Filter f, Dim dim, boolean profit, Integer limit) {
        List<Map<String, Object>> rows = aggregate(f, dim);
        Bucket b = dim.bucket();
        if (b != null) {
            rows = fill(rows, b, f.from(), f.to());
        } else {
            rows.sort(SalesReportKit.byDesc("revenue"));
            if (limit != null && rows.size() > limit) {
                rows = new ArrayList<>(rows.subList(0, limit));
            }
            for (Map<String, Object> r : rows) {
                drillFor(r, dim, str(r.get("k0")));
            }
        }
        BigDecimal total = SalesReportKit.sum(rows, "revenue");
        rows.forEach(r -> r.put("share", pct(dec(r.get("revenue")), total)));
        Report rep = new Report().col("label", dim.title(), "text").col("documents", "Invoices", "count")
                .col("customers", "Customers", "count").col("quantity", "Quantity", "qty")
                .col("gross", "Gross sales", "money").col("discount", "Discounts", "money")
                .col("returns", "Returns", "money").col("revenue", "Net sales", "money");
        if (profit) {
            rep.col("cost", "Cost of sales", "money").col("margin", "Gross profit", "money")
                    .col("marginPercent", "Margin %", "percent");
        }
        rep.col("share", "Share %", "percent").rows(rows)
                .sumTotals("documents", "quantity", "gross", "discount", "returns", "revenue", "cost", "margin");
        rep.total("marginPercent", pct(dec(rep.totals.get("margin")), dec(rep.totals.get("revenue"))));
        rep.total("label", "Total");
        if (b != null) {
            rep.chart("combo", "label", bar("revenue", "Net sales"), profit ? line("margin", "Gross profit")
                    : line("discount", "Discounts"));
        } else if (rows.size() <= 8 && (dim == Dim.CHANNEL || dim == Dim.BRANCH || dim == Dim.CUSTOMER_CATEGORY)) {
            rep.chart("pie", "label", bar("revenue", "Net sales"));
        } else {
            rep.chart("hbar", "label", bar("revenue", "Net sales"), profit ? bar("margin", "Gross profit")
                    : bar("discount", "Discounts"));
        }
        return rep;
    }

    /**
     * Pivot of {@code measure} with {@code rowDim} down and {@code colDim} across (time buckets in order, other
     * dimensions top {@code maxCols} by the measure plus "Other"); rows ranked by their total, top {@code limit}.
     */
    Report matrix(Filter f, Dim rowDim, Dim colDim, String measure, int maxCols, Integer limit) {
        List<Map<String, Object>> cells = aggregate(f, rowDim, colDim);
        List<String> cols;
        Map<String, String> colLabels = new LinkedHashMap<>();
        if (colDim.bucket() != null) {
            cols = colDim.bucket().keys(f.from(), f.to());
            cols.forEach(c -> colLabels.put(c, colDim.bucket().label(c)));
        } else {
            Map<String, BigDecimal> tot = new LinkedHashMap<>();
            for (Map<String, Object> c : cells) {
                tot.merge(String.valueOf(c.get("k1")), dec(c.get(measure)), BigDecimal::add);
                colLabels.put(String.valueOf(c.get("k1")), str(c.get("label1")));
            }
            cols = tot.entrySet().stream().sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                    .map(Map.Entry::getKey).limit(maxCols).collect(java.util.stream.Collectors.toCollection(
                            ArrayList::new));
            if (tot.size() > maxCols) {
                cols.add("__other");
                colLabels.put("__other", "Other");
            }
        }
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> c : cells) {
            String rk = String.valueOf(c.get("k0"));
            Map<String, Object> r = rows.computeIfAbsent(rk, k -> {
                Map<String, Object> n = Obj.of("k0", c.get("k0"), "label", c.get("label"));
                cols.forEach(col -> n.put("c_" + col, 0));
                n.put("total", 0);
                return n;
            });
            String ck = String.valueOf(c.get("k1"));
            String target = cols.contains(ck) ? ck : "__other";
            BigDecimal v = dec(c.get(measure));
            if (r.containsKey("c_" + target)) {
                r.put("c_" + target, num(dec(r.get("c_" + target)).add(v)));
            }
            r.put("total", num(dec(r.get("total")).add(v)));
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.values());
        out.sort(SalesReportKit.byDesc("total"));
        if (limit != null && out.size() > limit) {
            out = new ArrayList<>(out.subList(0, limit));
        }
        out.forEach(r -> drillFor(r, rowDim, str(r.get("k0"))));
        String type = "revenue".equals(measure) || "margin".equals(measure) || "discount".equals(measure) ? "money"
                : "qty";
        Report rep = new Report().col("label", rowDim.title(), "text");
        List<SalesReportKit.Series> series = new ArrayList<>();
        for (String c : cols) {
            rep.col("c_" + c, colLabels.get(c), type);
        }
        rep.col("total", "Total", type).rows(out);
        String[] keys = new String[cols.size() + 1];
        for (int i = 0; i < cols.size(); i++) {
            keys[i] = "c_" + cols.get(i);
        }
        keys[cols.size()] = "total";
        rep.sumTotals(keys);
        rep.total("label", "Total");
        if (colDim.bucket() != null) {
            // one line per top row across the periods
            List<Map<String, Object>> data = new ArrayList<>();
            List<Map<String, Object>> top = out.subList(0, Math.min(6, out.size()));
            for (String c : cols) {
                Map<String, Object> point = Obj.of("label", colLabels.get(c));
                for (int i = 0; i < top.size(); i++) {
                    point.put("s" + i, top.get(i).get("c_" + c));
                }
                data.add(point);
            }
            for (int i = 0; i < top.size(); i++) {
                series.add(line("s" + i, str(top.get(i).get("label"))));
            }
            rep.chart = new SalesReportKit.Chart("line", "label", series, false);
            rep.chartData = data;
        } else {
            for (String c : cols) {
                series.add(bar("c_" + c, colLabels.get(c)));
            }
            List<Map<String, Object>> top = new ArrayList<>(out.subList(0, Math.min(15, out.size())));
            rep.chart = new SalesReportKit.Chart("bar", "label", series, true);
            rep.chartData = top;
        }
        return rep;
    }

    /** Standard KPI tiles of a totals row. */
    static void kpis(Report rep, Map<String, Object> t, boolean profit) {
        rep.kpi("revenue", "Net sales", t.get("revenue"), "money")
                .kpi("documents", "Invoices", t.get("documents"), "count")
                .kpi("aov", "Average order value", t.get("aov"), "money")
                .kpi("discount", "Discounts", t.get("discount"), "money", "Share " + fmt(t.get("discountPercent")) + "%");
        if (profit) {
            rep.kpi("margin", "Gross profit", t.get("margin"), "money", "Margin " + fmt(t.get("marginPercent")) + "%");
        } else {
            rep.kpi("returns", "Returns", t.get("returns"), "money");
        }
    }

    static String fmt(Object v) {
        return v == null ? "—" : v.toString();
    }

    /** Zero rows for missing time buckets, in date order. */
    static List<Map<String, Object>> fill(List<Map<String, Object>> rows, Bucket b, LocalDate from, LocalDate to) {
        Map<String, Map<String, Object>> by = new LinkedHashMap<>();
        rows.forEach(r -> by.put(str(r.get("k0")), r));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : b.keys(from, to)) {
            Map<String, Object> r = by.remove(k);
            if (r == null) {
                r = enrich(Obj.of("k0", k));
                r.put("label", b.label(k));
            }
            out.add(r);
        }
        out.addAll(by.values());
        return out;
    }

    /** Drill from a dimension row into the matching detail report. */
    static void drillFor(Map<String, Object> r, Dim dim, String id) {
        if (id == null) {
            return;
        }
        switch (dim) {
            case CUSTOMER -> SalesReportKit.drill(r, "customer-history", "partnerId", id);
            case PRODUCT -> SalesReportKit.drill(r, "product-customers", "productId", id);
            case SALESPERSON -> SalesReportKit.drill(r, "invoice-lines", "salespersonId", id);
            case BRANCH -> SalesReportKit.drill(r, "invoice-lines", "branchId", id);
            case CATEGORY -> SalesReportKit.drill(r, "product-sales", "categoryId", id);
            case CHANNEL -> SalesReportKit.drill(r, "invoice-lines", "channel", id);
            case CUSTOMER_CATEGORY -> SalesReportKit.drill(r, "customer-summary", "customerCategoryId", id);
            default -> {
                // no drill
            }
        }
    }
}
