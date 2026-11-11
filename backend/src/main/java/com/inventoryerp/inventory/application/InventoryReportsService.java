package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.InventoryLookups.dec;
import static com.inventoryerp.inventory.application.InventoryLookups.str;
import static com.inventoryerp.inventory.application.InventoryQueryService.notBlank;
import static com.inventoryerp.kernel.common.JsNumbers.js;
import static com.inventoryerp.kernel.common.JsNumbers.round;

import com.inventoryerp.accounting.application.InventoryValuationService;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The /inventory/reports page (port of {@code inventory-reports.service.ts}): item movement summary, current
 * valuation, movement analysis, reorder / expiring / negative exceptions and stock health. Date filters are
 * local calendar days; ledger {@code totalValue} is unsigned and direction comes from {@code quantityChange}.
 */
@Service
@Transactional(readOnly = true)
public class InventoryReportsService {

    /** StockMoveType → report group. */
    public static final Map<String, String> MOVE_TYPE_GROUPS = Map.ofEntries(
            Map.entry("receipt", "received"), Map.entry("return_in", "received"),
            Map.entry("production_output", "received"), Map.entry("opening_balance", "received"),
            Map.entry("issue", "consumed"), Map.entry("production_consume", "consumed"),
            Map.entry("adjustment_in", "adjusted"), Map.entry("adjustment_out", "adjusted"),
            Map.entry("transfer_in", "transferred"), Map.entry("transfer_out", "transferred"),
            Map.entry("waste", "lost"), Map.entry("expiry_write_off", "lost"), Map.entry("internal_use", "lost"),
            Map.entry("promo_sample", "lost"), Map.entry("return_to_supplier", "returned"));

    private static final Set<String> STOCK_STATUSES = Set.of("all", "in_stock", "low", "out", "negative", "below_par");
    private static final Pattern DAY = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})");
    static final Collator COLLATOR = Collator.getInstance(Locale.ENGLISH);

    private final JdbcClient jdbc;
    private final InventoryQueryService queries;
    private final LocationScope scope;
    private final InventoryValuationService accountingValuation;

    public InventoryReportsService(JdbcClient jdbc, InventoryQueryService queries, LocationScope scope,
            InventoryValuationService accountingValuation) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.scope = scope;
        this.accountingValuation = accountingValuation;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    // ------------------------------------------------------------------ scope helpers

    /** Local start/end instant of a YYYY-MM-DD day (any other string is parsed as a date first). */
    public static Instant parseLocalDay(String value, boolean start) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        ZoneId zone = BusinessZone.get();
        LocalDate day;
        Matcher m = DAY.matcher(value.trim());
        if (m.find()) {
            try {
                day = LocalDate.of(Integer.parseInt(m.group(1)), 1, 1).plusMonths(Integer.parseInt(m.group(2)) - 1)
                        .plusDays(Integer.parseInt(m.group(3)) - 1);
            } catch (RuntimeException e) {
                throw ApiException.badRequest("Invalid date: " + value);
            }
        } else {
            try {
                day = EntityPatch.toInstant(value).atZone(zone).toLocalDate();
            } catch (RuntimeException e) {
                throw ApiException.badRequest("Invalid date: " + value);
            }
        }
        LocalDateTime at = start ? day.atStartOfDay() : day.atTime(LocalTime.of(23, 59, 59, 999_000_000));
        return at.atZone(zone).toInstant();
    }

    public static String localIso(Instant i) {
        return i.atZone(BusinessZone.get()).toLocalDate().toString();
    }

    record Range(Instant start, Instant end) {}

    private Range range(Map<String, String> q) {
        Instant start = parseLocalDay(q.get("start"), true);
        Instant end = parseLocalDay(q.get("end"), false);
        if (start != null && end != null && start.isAfter(end)) {
            String s = localIso(end);
            String e = localIso(start);
            start = parseLocalDay(s, true);
            end = parseLocalDay(e, false);
        }
        return new Range(start, end);
    }

    private static List<String> moveTypes(Map<String, String> q) {
        String raw = q.get("moveTypes");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (String s : raw.split(",")) {
            if (!s.trim().isEmpty()) {
                list.add(s.trim());
            }
        }
        List<String> bad = list.stream().filter(t -> !MOVE_TYPE_GROUPS.containsKey(t)).toList();
        if (!bad.isEmpty()) {
            throw ApiException.badRequest("Unknown move type(s): " + String.join(", ", bad));
        }
        return list.isEmpty() ? null : list;
    }

    /** Category id plus every descendant id (depth-first, like the Nest stack walk). */
    List<String> categoryTree(String categoryId) {
        Map<String, List<String>> children = new HashMap<>();
        for (Map<String, Object> c : jdbc.sql("SELECT id, \"parentId\" FROM \"ProductCategory\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").param(org()).query().listOfRows()) {
            if (c.get("parentId") != null) {
                children.computeIfAbsent((String) c.get("parentId"), k -> new ArrayList<>()).add((String) c.get("id"));
            }
        }
        List<String> out = new ArrayList<>();
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        stack.push(categoryId);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (out.contains(id)) {
                continue;
            }
            out.add(id);
            for (String c : children.getOrDefault(id, List.of())) {
                stack.push(c);
            }
        }
        return out;
    }

    /** Product-level filter SQL (alias {@code p}) for category / search / single product. */
    private String productWhere(Map<String, String> q, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder("p.\"organizationId\" = :org AND p.\"deletedAt\" IS NULL");
        params.put("org", org());
        if (notBlank(q.get("productId"))) {
            sb.append(" AND p.id = :pid");
            params.put("pid", q.get("productId"));
        }
        if (notBlank(q.get("categoryId"))) {
            sb.append(" AND p.\"categoryId\" IN (:cats)");
            params.put("cats", categoryTree(q.get("categoryId")));
        }
        String search = q.get("search") == null ? null : q.get("search").trim();
        if (search != null && !search.isEmpty()) {
            sb.append(" AND (p.code ILIKE :s OR p.name ILIKE :s OR p.sku ILIKE :s OR p.barcode ILIKE :s)");
            params.put("s", "%" + BaseCrudService.escapeLike(search) + "%");
        }
        return sb.toString();
    }

    /** Product ids in scope, or null when no product-level filter is set. */
    List<String> scopedProductIds(Map<String, String> q) {
        String search = q.get("search") == null ? "" : q.get("search").trim();
        if (!notBlank(q.get("productId")) && !notBlank(q.get("categoryId")) && search.isEmpty()) {
            return null;
        }
        Map<String, Object> params = new HashMap<>();
        String where = productWhere(q, params);
        return jdbc.sql("SELECT p.id FROM \"Product\" p WHERE " + where).params(params).query(String.class).list();
    }

    private Map<String, Map<String, Object>> productMeta(java.util.Collection<String> ids) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT p.id, p.code, p.name, p.sku, p.\"costPrice\","
                        + " p.\"minQuantity\", u.code AS uom, c.id AS cid, c.name AS cname FROM \"Product\" p"
                        + " LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\" LEFT JOIN \"ProductCategory\" c"
                        + " ON c.id = p.\"categoryId\" WHERE p.id IN (:ids) AND p.\"organizationId\" = :org"
                        + " AND p.\"deletedAt\" IS NULL")
                .param("ids", List.copyOf(ids)).param("org", org()))) {
            out.put(str(p, "id"), p);
        }
        return out;
    }

    private static double n(Object v) {
        return v == null ? 0 : v instanceof BigDecimal b ? b.doubleValue() : ((Number) v).doubleValue();
    }

    /** A column / rendered number as BigDecimal (null = 0). */
    private static BigDecimal d(Object v) {
        return Money.dec(v);
    }

    /** Quantity rounded to 6 places (HALF_UP). */
    private static BigDecimal r6(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    // ------------------------------------------------------------------ 1. item movements

    /** Stock card summary per item over local days [start, end]. */
    public Map<String, Object> itemMovements(Map<String, String> query) {
        Map<String, String> q = scope.apply(query);
        Range r = range(q);
        List<String> types = moveTypes(q);
        List<String> productIds = scopedProductIds(q);
        boolean excludeTransfers = "true".equals(q.get("excludeTransfers"));
        boolean endsToday = r.end() == null || !r.end().isBefore(parseLocalDay(localIso(Instant.now()), true));

        Map<String, Object> base = new HashMap<>();
        StringBuilder baseSql = new StringBuilder("\"organizationId\" = :org");
        base.put("org", org());
        if (notBlank(q.get("locationId"))) {
            baseSql.append(" AND \"locationId\" = :loc");
            base.put("loc", q.get("locationId"));
        }
        if (productIds != null) {
            baseSql.append(" AND \"productId\" IN (:pids)");
            base.put("pids", productIds.isEmpty() ? List.of("") : productIds);
        }
        Map<String, Object> wp = new HashMap<>(base);
        StringBuilder window = new StringBuilder(baseSql);
        if (r.start() != null) {
            window.append(" AND \"createdAt\" >= :start");
            wp.put("start", SqlRows.ts(r.start()));
        }
        if (r.end() != null) {
            window.append(" AND \"createdAt\" <= :end");
            wp.put("end", SqlRows.ts(r.end()));
        }
        if (types != null) {
            window.append(" AND type::text IN (:types)");
            wp.put("types", types);
        }
        if (excludeTransfers) {
            window.append(" AND type::text NOT IN ('transfer_in', 'transfer_out')");
        }
        List<Map<String, Object>> typed = jdbc.sql("SELECT \"productId\", type::text AS type, SUM(\"quantityChange\") AS q,"
                + " SUM(\"totalValue\") AS v, count(*) AS n FROM \"InventoryLedger\" WHERE " + window
                + " GROUP BY \"productId\", type").params(wp).query().listOfRows();
        Map<String, BigDecimal> opening = new HashMap<>();
        if (r.start() != null) {
            Map<String, Object> op = new HashMap<>(base);
            op.put("start", SqlRows.ts(r.start()));
            for (Map<String, Object> g : jdbc.sql("SELECT \"productId\", SUM(\"quantityChange\") AS q FROM \"InventoryLedger\""
                    + " WHERE " + baseSql + " AND \"createdAt\" < :start GROUP BY \"productId\"").params(op).query()
                    .listOfRows()) {
                opening.put((String) g.get("productId"), d(g.get("q")));
            }
        }
        Map<String, Object> cp = new HashMap<>(base);
        String closingSql = baseSql.toString();
        if (r.end() != null) {
            closingSql += " AND \"createdAt\" <= :end";
            cp.put("end", SqlRows.ts(r.end()));
        }
        Map<String, BigDecimal> closing = new HashMap<>();
        for (Map<String, Object> g : jdbc.sql("SELECT \"productId\", SUM(\"quantityChange\") AS q FROM \"InventoryLedger\""
                + " WHERE " + closingSql + " GROUP BY \"productId\"").params(cp).query().listOfRows()) {
            closing.put((String) g.get("productId"), d(g.get("q")));
        }
        Set<String> universe = new LinkedHashSet<>();
        typed.forEach(g -> universe.add((String) g.get("productId")));
        opening.forEach((k, v) -> {
            if (v.signum() != 0) {
                universe.add(k);
            }
        });
        closing.forEach((k, v) -> {
            if (v.signum() != 0) {
                universe.add(k);
            }
        });
        if ("true".equals(q.get("includeIdle"))) {
            Map<String, Object> params = new HashMap<>();
            String where = productWhere(q, params);
            universe.addAll(jdbc.sql("SELECT p.id FROM \"Product\" p WHERE " + where + " AND p.\"trackInventory\" = true")
                    .params(params).query(String.class).list());
        }
        if (types != null) {
            universe.clear();
            typed.forEach(g -> universe.add((String) g.get("productId")));
        }
        Map<String, Map<String, Object>> products = productMeta(universe);
        Map<String, BigDecimal[]> costAgg = new HashMap<>();
        Map<String, BigDecimal> onHand = new HashMap<>();
        if (!universe.isEmpty()) {
            var spec = jdbc.sql("SELECT \"productId\", quantity, \"runningAverageCost\" FROM \"StockItem\" WHERE"
                            + " \"organizationId\" = :org AND \"productId\" IN (:ids)"
                            + (notBlank(q.get("locationId")) ? " AND \"locationId\" = :loc" : ""))
                    .param("org", org()).param("ids", List.copyOf(universe));
            if (notBlank(q.get("locationId"))) {
                spec = spec.param("loc", q.get("locationId"));
            }
            for (Map<String, Object> si : spec.query().listOfRows()) {
                String pid = (String) si.get("productId");
                BigDecimal qty = d(si.get("quantity"));
                onHand.merge(pid, qty, BigDecimal::add);
                if (qty.signum() <= 0) {
                    continue;
                }
                BigDecimal[] a = costAgg.computeIfAbsent(pid, k -> new BigDecimal[] {Money.ZERO, Money.ZERO});
                a[0] = a[0].add(qty);
                a[1] = a[1].add(qty.multiply(d(si.get("runningAverageCost"))));
            }
        }
        Map<String, Agg> aggs = new HashMap<>();
        for (Map<String, Object> g : typed) {
            Agg a = aggs.computeIfAbsent((String) g.get("productId"), k -> new Agg());
            BigDecimal qty = d(g.get("q"));
            BigDecimal val = d(g.get("v"));
            if (qty.signum() >= 0) {
                a.qtyIn = a.qtyIn.add(qty);
                a.valueIn = a.valueIn.add(val);
            } else {
                a.qtyOut = a.qtyOut.add(qty.negate());
                a.valueOut = a.valueOut.add(val);
            }
            a.count += ((Number) g.get("n")).longValue();
            String type = (String) g.get("type");
            a.byType.put(type, r6(a.byType.getOrDefault(type, Money.ZERO).add(qty)));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        // Totals add the rounded row figures (what the screen shows): opening, in, out, net, closing, valueIn,
        // valueOut, closingValue.
        BigDecimal[] t = new BigDecimal[8];
        java.util.Arrays.fill(t, Money.ZERO);
        long movements = 0;
        long drift = 0;
        for (Map<String, Object> p : products.values()) {
            String id = str(p, "id");
            Agg a = aggs.getOrDefault(id, new Agg());
            BigDecimal[] c = costAgg.get(id);
            BigDecimal avgCost = c != null && c[0].signum() > 0 ? Money.div(c[1], c[0]) : d(p.get("costPrice"));
            BigDecimal openingQty = r6(opening.getOrDefault(id, Money.ZERO));
            BigDecimal closingQty = r6(closing.getOrDefault(id, Money.ZERO));
            Map<String, Object> groups = new LinkedHashMap<>();
            Map<String, BigDecimal> groupSums = new LinkedHashMap<>();
            Map<String, Object> byType = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> e : a.byType.entrySet()) {
                byType.put(e.getKey(), js(e.getValue()));
                String grp = MOVE_TYPE_GROUPS.getOrDefault(e.getKey(), "other");
                BigDecimal sum = r6(groupSums.getOrDefault(grp, Money.ZERO).add(e.getValue()));
                groupSums.put(grp, sum);
                groups.put(grp, js(sum));
            }
            BigDecimal current = onHand.getOrDefault(id, Money.ZERO);
            BigDecimal qtyIn = r6(a.qtyIn);
            BigDecimal qtyOut = r6(a.qtyOut);
            BigDecimal netQty = r6(a.qtyIn.subtract(a.qtyOut));
            BigDecimal valueIn = Money.round(a.valueIn, 2);
            BigDecimal valueOut = Money.round(a.valueOut, 2);
            BigDecimal closingValue = Money.round(closingQty.multiply(avgCost), 2);
            BigDecimal ledgerDrift = endsToday ? r6(current.subtract(closingQty)) : null;
            Map<String, Object> row = Obj.of("productId", id, "code", p.get("code"), "name", p.get("name"), "sku",
                    p.get("sku"), "categoryId", p.get("cid"), "category", p.get("cname"), "uom", p.get("uom"),
                    "openingQty", js(openingQty), "qtyIn", js(qtyIn), "qtyOut", js(qtyOut),
                    "netQty", js(netQty), "closingQty", js(closingQty), "balance", js(closingQty),
                    "valueIn", js(valueIn), "valueOut", js(valueOut), "avgCost",
                    js(avgCost, 4), "closingValue", js(closingValue), "movements", a.count,
                    "byType", byType, "byGroup", groups, "minQuantity",
                    p.get("minQuantity") != null ? js(d(p.get("minQuantity"))) : null, "currentOnHand",
                    js(current, 6), "ledgerDrift", ledgerDrift == null ? null : js(ledgerDrift));
            rows.add(row);
            BigDecimal[] figures = {openingQty, qtyIn, qtyOut, netQty, closingQty, valueIn, valueOut, closingValue};
            for (int i = 0; i < t.length; i++) {
                t[i] = t[i].add(figures[i]);
            }
            movements += a.count;
            if (ledgerDrift != null && ledgerDrift.abs().compareTo(new BigDecimal("0.000001")) > 0) {
                drift++;
            }
        }
        rows.sort((x, y) -> COLLATOR.compare(String.valueOf(x.get("name")), String.valueOf(y.get("name"))));
        Map<String, Object> totals = Obj.of("items", rows.size(), "openingQty", js(t[0], 6), "qtyIn",
                js(t[1], 6), "qtyOut", js(t[2], 6), "netQty", js(t[3], 6), "closingQty",
                js(t[4], 6), "valueIn", js(t[5], 2), "valueOut", js(t[6], 2), "closingValue",
                js(t[7], 2), "movements", movements, "driftItems", drift);
        return Obj.of("data", rows, "totals", totals, "meta", Obj.of("start", r.start() != null ? localIso(r.start()) : null,
                "end", r.end() != null ? localIso(r.end()) : null, "total", rows.size()));
    }

    private static final class Agg {
        BigDecimal qtyIn = Money.ZERO;
        BigDecimal qtyOut = Money.ZERO;
        BigDecimal valueIn = Money.ZERO;
        BigDecimal valueOut = Money.ZERO;
        long count;
        final Map<String, BigDecimal> byType = new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------ 2. valuation

    /** One valuation row: the rendered JSON plus the figures the filters and totals work on. */
    private record ValuationRow(Map<String, Object> json, BigDecimal quantity, BigDecimal value, BigDecimal retailValue,
            BigDecimal minQuantity, String stockStatus) {}

    /**
     * Current on-hand valuation per item with stock-status filter and category breakdown. Each quant is valued by its
     * product's costing method exactly as the accounting valuation does ({@link
     * InventoryValuationService#currentValueByQuant}: lots / serials / standard / average, plus the CM-1 adjunct
     * components), so a product's value here equals {@code GET /reports/inventory/valuation}. Set-based: one query for
     * the products, one for the quants, one for the values.
     */
    public Map<String, Object> valuation(Map<String, String> query) {
        Map<String, String> q = scope.apply(query);
        String status = q.get("status") == null || q.get("status").isEmpty() ? "all" : q.get("status");
        if (!STOCK_STATUSES.contains(status)) {
            throw ApiException.badRequest("Unknown status: " + status);
        }
        String loc = notBlank(q.get("locationId")) ? q.get("locationId") : null;
        Map<String, Object> params = new HashMap<>();
        String where = productWhere(q, params);
        if (loc != null) {
            params.put("loc", loc);
        }
        List<Map<String, Object>> products = SqlRows.list(jdbc.sql("SELECT p.id, p.code, p.name, p.sku, p.\"costPrice\","
                + " p.\"salesPrice\", p.\"minQuantity\", p.\"reorderQty\", p.\"isActive\", u.code AS uom, c.id AS cid,"
                + " c.name AS cname FROM \"Product\" p LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\""
                + " LEFT JOIN \"ProductCategory\" c ON c.id = p.\"categoryId\" WHERE " + where
                + " AND (p.\"trackInventory\" = true OR EXISTS (SELECT 1 FROM \"StockItem\" si WHERE si.\"productId\" = p.id"
                + " AND si.\"organizationId\" = :org" + (loc != null ? " AND si.\"locationId\" = :loc" : "")
                + ")) ORDER BY p.name ASC").params(params));

        // Method-correct value per quant (product | variant | location), adjunct components merged in.
        Map<String, Map<String, BigDecimal>> values = new HashMap<>();
        for (Map<String, Object> v : accountingValuation.currentValueByQuant(loc)) {
            values.computeIfAbsent(str(v, "product_id"), k -> new LinkedHashMap<>())
                    .merge(str(v, "variant_key") + "|" + str(v, "location_id"), SqlRows.dec(v, "value"),
                            BigDecimal::add);
        }
        Map<String, List<Map<String, Object>>> quants = new HashMap<>();
        var spec = jdbc.sql("SELECT si.\"productId\", si.\"variantKey\", si.\"locationId\", si.quantity,"
                + " si.\"updatedAt\", l.code, l.name FROM \"StockItem\" si JOIN \"InventoryLocation\" l ON l.id = si.\"locationId\""
                + " WHERE si.\"organizationId\" = :org" + (loc != null ? " AND si.\"locationId\" = :loc" : "")
                + " ORDER BY l.name ASC, l.id ASC").param("org", org());
        if (loc != null) {
            spec = spec.param("loc", loc);
        }
        for (Map<String, Object> si : SqlRows.list(spec)) {
            quants.computeIfAbsent(str(si, "productId"), k -> new ArrayList<>()).add(si);
        }

        List<ValuationRow> rows = new ArrayList<>();
        for (Map<String, Object> p : products) {
            String productId = str(p, "id");
            Map<String, BigDecimal> productValues = values.getOrDefault(productId, new HashMap<>());
            BigDecimal qty = Money.ZERO;
            BigDecimal value = Money.ZERO;
            BigDecimal posQty = Money.ZERO;
            BigDecimal posValue = Money.ZERO;
            Instant lastMovedAt = null;
            Map<String, BigDecimal[]> byLoc = new LinkedHashMap<>();
            Map<String, Map<String, Object>> locMeta = new LinkedHashMap<>();
            for (Map<String, Object> si : quants.getOrDefault(productId, List.of())) {
                BigDecimal sq = d(si.get("quantity"));
                BigDecimal sv = productValues.remove(str(si, "variantKey") + "|" + str(si, "locationId"));
                sv = sv == null ? Money.ZERO : sv;
                qty = qty.add(sq);
                value = value.add(sv);
                if (sq.signum() > 0) {
                    posQty = posQty.add(sq);
                    posValue = posValue.add(sv);
                }
                Instant upd = (Instant) si.get("updatedAt");
                if (upd != null && (lastMovedAt == null || upd.isAfter(lastMovedAt))) {
                    lastMovedAt = upd;
                }
                String lid = str(si, "locationId");
                locMeta.computeIfAbsent(lid, k -> Obj.of("locationId", k, "code", si.get("code"), "name",
                        si.get("name")));
                BigDecimal[] l = byLoc.computeIfAbsent(lid, k -> new BigDecimal[] {Money.ZERO, Money.ZERO});
                l[0] = l[0].add(sq);
                l[1] = l[1].add(sv);
            }
            // Adjunct components (price difference / NRV allowance) at a location without a quant row.
            for (BigDecimal rest : productValues.values()) {
                value = value.add(rest);
            }
            BigDecimal avgCost = posQty.signum() > 0 ? Money.div(posValue, posQty) : d(p.get("costPrice"));
            BigDecimal min = d(p.get("minQuantity"));
            String stockStatus = qty.signum() < 0 ? "negative" : qty.signum() == 0 ? "out"
                    : min.signum() > 0 && qty.compareTo(min) <= 0 ? "low" : "in_stock";
            BigDecimal salesPrice = d(p.get("salesPrice"));
            List<Map<String, Object>> locations = new ArrayList<>();
            for (Map.Entry<String, BigDecimal[]> e : byLoc.entrySet()) {
                BigDecimal lq = r6(e.getValue()[0]);
                if (lq.signum() != 0) {
                    Map<String, Object> l = locMeta.get(e.getKey());
                    l.put("quantity", js(lq));
                    l.put("value", js(e.getValue()[1], 2));
                    locations.add(l);
                }
            }
            BigDecimal qty6 = r6(qty);
            BigDecimal value2 = Money.round(value, 2);
            BigDecimal retail2 = Money.round(qty.multiply(salesPrice), 2);
            rows.add(new ValuationRow(Obj.of("productId", p.get("id"), "code", p.get("code"), "name", p.get("name"),
                    "sku", p.get("sku"), "isActive", p.get("isActive"), "categoryId", p.get("cid"), "category",
                    p.get("cname"), "uom", p.get("uom"), "quantity", js(qty6), "avgCost", js(avgCost, 4), "value",
                    js(value2), "salesPrice", js(salesPrice), "retailValue", js(retail2),
                    "minQuantity", min.signum() != 0 ? js(min) : null, "reorderQty",
                    p.get("reorderQty") != null ? js(d(p.get("reorderQty"))) : null, "stockStatus", stockStatus,
                    "lastMovedAt", lastMovedAt, "locations", locations),
                    qty6, value2, retail2, min.signum() != 0 ? min : null, stockStatus));
        }
        boolean includeZero = "true".equals(q.get("includeZero"));
        List<ValuationRow> filtered = rows.stream().filter(r -> {
            if ("all".equals(status)) {
                return includeZero || r.quantity().signum() != 0;
            }
            if ("below_par".equals(status)) {
                return r.minQuantity() != null && r.quantity().compareTo(r.minQuantity()) <= 0;
            }
            return status.equals(r.stockStatus());
        }).toList();
        BigDecimal totalValue = Money.ZERO;
        BigDecimal totalQty = Money.ZERO;
        BigDecimal totalRetail = Money.ZERO;
        Map<String, BigDecimal[]> byCategory = new LinkedHashMap<>();
        Map<String, Long> categoryItems = new LinkedHashMap<>();
        for (ValuationRow r : filtered) {
            totalValue = totalValue.add(r.value());
            totalQty = totalQty.add(r.quantity());
            totalRetail = totalRetail.add(r.retailValue());
            Object cat = r.json().get("category");
            String key = cat != null ? (String) cat : "Uncategorised";
            BigDecimal[] c = byCategory.computeIfAbsent(key, k -> new BigDecimal[] {Money.ZERO, Money.ZERO});
            categoryItems.merge(key, 1L, Long::sum);
            c[0] = c[0].add(r.quantity());
            c[1] = c[1].add(r.value());
        }
        List<Map<String, Object>> cats = new ArrayList<>();
        List<BigDecimal> catValues = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : byCategory.entrySet()) {
            BigDecimal[] c = e.getValue();
            BigDecimal share = totalValue.signum() != 0
                    ? c[1].multiply(HUNDRED).divide(totalValue, 10, RoundingMode.HALF_UP) : Money.ZERO;
            cats.add(Obj.of("category", e.getKey(), "items", categoryItems.get(e.getKey()), "quantity", js(c[0], 6),
                    "value", js(c[1], 2), "share", js(share, 1)));
            catValues.add(c[1]);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < cats.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> catValues.get(b).compareTo(catValues.get(a)));
        List<Map<String, Object>> data = filtered.stream().map(ValuationRow::json).toList();
        return Obj.of("data", data, "totals", Obj.of("items", filtered.size(),
                        "quantity", js(totalQty, 6), "value", js(totalValue, 2),
                        "retailValue", js(totalRetail, 2),
                        "inStock", count(rows, "in_stock"), "low", count(rows, "low"), "out", count(rows, "out"),
                        "negative", count(rows, "negative")),
                "byCategory", order.stream().map(cats::get).toList());
    }

    private static long count(List<ValuationRow> rows, String status) {
        return rows.stream().filter(r -> status.equals(r.stockStatus())).count();
    }

    // ------------------------------------------------------------------ 3. movement analysis

    /** Totals by move type, daily trend, top consumed / lost / received items (default: last 30 days). */
    public Map<String, Object> movementAnalysis(Map<String, String> query) {
        Map<String, String> q = scope.apply(query);
        Range r = range(q);
        List<String> types = moveTypes(q);
        List<String> productIds = scopedProductIds(q);
        Instant effEnd = r.end() != null ? r.end() : parseLocalDay(localIso(Instant.now()), false);
        Instant effStart = r.start() != null ? r.start()
                : parseLocalDay(effEnd.atZone(BusinessZone.get()).toLocalDate().minusDays(29).toString(), true);
        if (effEnd.toEpochMilli() - effStart.toEpochMilli() > 400L * 86_400_000L) {
            throw ApiException.badRequest("Movement analysis is limited to a 400-day window");
        }
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder("\"organizationId\" = :org AND \"createdAt\" >= :start AND \"createdAt\" <= :end");
        params.put("org", org());
        params.put("start", SqlRows.ts(effStart));
        params.put("end", SqlRows.ts(effEnd));
        if (notBlank(q.get("locationId"))) {
            where.append(" AND \"locationId\" = :loc");
            params.put("loc", q.get("locationId"));
        }
        if (productIds != null) {
            where.append(" AND \"productId\" IN (:pids)");
            params.put("pids", productIds.isEmpty() ? List.of("") : productIds);
        }
        if (types != null) {
            where.append(" AND type::text IN (:types)");
            params.put("types", types);
        }
        List<Map<String, Object>> byType = new ArrayList<>();
        for (Map<String, Object> g : jdbc.sql("SELECT type::text AS type, SUM(\"quantityChange\") AS q, SUM(\"totalValue\") AS v,"
                + " count(*) AS n FROM \"InventoryLedger\" WHERE " + where + " GROUP BY type").params(params).query()
                .listOfRows()) {
            BigDecimal qty = d(g.get("q"));
            byType.add(Obj.of("type", g.get("type"), "group", MOVE_TYPE_GROUPS.getOrDefault((String) g.get("type"), "other"),
                    "movements", ((Number) g.get("n")).longValue(), "quantity", js(qty, 6), "value",
                    js(d(g.get("v")), 2), "direction", qty.signum() >= 0 ? "in" : "out"));
        }
        byType.sort((a, b) -> d(b.get("value")).compareTo(d(a.get("value"))));
        Map<String, BigDecimal[]> days = new LinkedHashMap<>();
        ZoneId zone = BusinessZone.get();
        for (LocalDate d = effStart.atZone(zone).toLocalDate(); !d.atStartOfDay(zone).toInstant().isAfter(effEnd);
                d = d.plusDays(1)) {
            days.put(d.toString(), new BigDecimal[] {Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO});
        }
        // Daily buckets are summed in SQL on the business-zone calendar day (no ledger rows loaded into memory).
        Map<String, Object> dayParams = new HashMap<>(params);
        String localDay = localDaySql(zone, dayParams);
        for (Map<String, Object> g : jdbc.sql("SELECT to_char(" + localDay + ", 'YYYY-MM-DD') AS day,"
                + " COALESCE(SUM(\"totalValue\") FILTER (WHERE \"quantityChange\" >= 0), 0) AS vin,"
                + " COALESCE(SUM(\"totalValue\") FILTER (WHERE \"quantityChange\" < 0), 0) AS vout,"
                + " COALESCE(SUM(\"quantityChange\") FILTER (WHERE \"quantityChange\" >= 0), 0) AS qin,"
                + " COALESCE(SUM(-\"quantityChange\") FILTER (WHERE \"quantityChange\" < 0), 0) AS qout,"
                + " count(*) AS n FROM \"InventoryLedger\" WHERE " + where + " GROUP BY 1").params(dayParams).query()
                .listOfRows()) {
            BigDecimal[] b = days.get((String) g.get("day"));
            if (b == null) {
                continue;
            }
            b[0] = b[0].add(d(g.get("vin")));
            b[1] = b[1].add(d(g.get("vout")));
            b[2] = b[2].add(d(g.get("qin")));
            b[3] = b[3].add(d(g.get("qout")));
            b[4] = b[4].add(d(g.get("n")));
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        days.forEach((k, b) -> trend.add(Obj.of("date", k, "valueIn", js(b[0], 2), "valueOut", js(b[1], 2),
                "qtyIn", js(b[2], 6), "qtyOut", js(b[3], 6), "movements", b[4].longValue())));
        Map<String, BigDecimal[]> perProduct = new LinkedHashMap<>();
        for (Map<String, Object> g : jdbc.sql("SELECT \"productId\", type::text AS type, SUM(\"quantityChange\") AS q,"
                + " SUM(\"totalValue\") AS v FROM \"InventoryLedger\" WHERE " + where + " GROUP BY \"productId\", type")
                .params(params).query().listOfRows()) {
            String grp = MOVE_TYPE_GROUPS.get((String) g.get("type"));
            BigDecimal[] p = perProduct.computeIfAbsent((String) g.get("productId"),
                    k -> new BigDecimal[] {Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO});
            BigDecimal val = d(g.get("v"));
            BigDecimal qty = d(g.get("q")).abs();
            if ("consumed".equals(grp)) {
                p[0] = p[0].add(val);
                p[3] = p[3].add(qty);
            }
            if ("lost".equals(grp)) {
                p[1] = p[1].add(val);
                p[4] = p[4].add(qty);
            }
            if ("received".equals(grp)) {
                p[2] = p[2].add(val);
            }
        }
        Map<String, Map<String, Object>> meta = productMeta(perProduct.keySet());
        BigDecimal consumed = sumValues(byType, "group", "consumed");
        BigDecimal lost = sumValues(byType, "group", "lost");
        Map<String, Object> summary = Obj.of(
                "movements", byType.stream().mapToLong(t -> ((Number) t.get("movements")).longValue()).sum(),
                "valueIn", js(sumValues(byType, "direction", "in"), 2),
                "valueOut", js(sumValues(byType, "direction", "out"), 2),
                "consumedValue", js(consumed, 2), "lostValue", js(lost, 2),
                "lossRatePct", consumed.signum() > 0
                        ? js(lost.multiply(HUNDRED).divide(consumed.add(lost), 10, RoundingMode.HALF_UP), 1) : null);
        return Obj.of("range", Obj.of("start", localIso(effStart), "end", localIso(effEnd)), "summary", summary,
                "byType", byType, "trend", trend, "topConsumed", top(perProduct, meta, 0, 3),
                "topLost", top(perProduct, meta, 1, 4), "topReceived", top(perProduct, meta, 2, -1));
    }

    /** Σ of the rendered (2 dp) {@code value} of the rows whose {@code key} equals {@code match}. */
    private static BigDecimal sumValues(List<Map<String, Object>> rows, String key, String match) {
        BigDecimal s = Money.ZERO;
        for (Map<String, Object> r : rows) {
            if (match.equals(r.get(key))) {
                s = s.add(d(r.get("value")));
            }
        }
        return s;
    }

    /**
     * SQL for the business-zone calendar day of {@code "createdAt"} (UTC wall time). A fixed offset is added as an
     * interval (Postgres reads a bare "+03:00" zone name with the POSIX sign); region ids use the tz database.
     */
    private static String localDaySql(ZoneId zone, Map<String, Object> params) {
        ZoneId z = zone.normalized();
        if (z instanceof java.time.ZoneOffset offset) {
            params.put("zoneOffsetSecs", offset.getTotalSeconds());
            return "(\"createdAt\" + make_interval(secs => :zoneOffsetSecs))::date";
        }
        params.put("zoneName", z.getId());
        return "((\"createdAt\" AT TIME ZONE 'UTC') AT TIME ZONE :zoneName)::date";
    }

    private static List<Map<String, Object>> top(Map<String, BigDecimal[]> perProduct,
            Map<String, Map<String, Object>> meta, int key, int qtyKey) {
        return perProduct.entrySet().stream().filter(e -> e.getValue()[key].signum() > 0)
                .sorted((a, b) -> b.getValue()[key].compareTo(a.getValue()[key])).limit(10)
                .map(e -> {
                    Map<String, Object> m = meta.get(e.getKey());
                    return Obj.of("productId", e.getKey(), "code", m == null ? null : m.get("code"), "name",
                            m == null ? e.getKey() : m.get("name"), "uom", m == null ? null : m.get("uom"), "value",
                            js(e.getValue()[key], 2), "quantity",
                            qtyKey >= 0 ? js(e.getValue()[qtyKey], 6) : null);
                }).toList();
    }

    // ------------------------------------------------------------------ 4. exceptions

    /** Reorder suggestions within the product scope, largest shortfall first. */
    public List<Map<String, Object>> reorder(Map<String, String> q) {
        List<Map<String, Object>> rows = queries.getReorderSuggestions(q.get("locationId"));
        List<String> scopedIds = scopedProductIds(q);
        Set<String> ids = scopedIds == null ? null : new java.util.HashSet<>(scopedIds);
        List<Map<String, Object>> scoped = ids == null ? rows : rows.stream().filter(r -> ids.contains(r.get("productId")))
                .toList();
        Map<String, Map<String, Object>> meta = productMeta(scoped.stream().map(r -> (String) r.get("productId")).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : scoped) {
            Map<String, Object> p = meta.get((String) r.get("productId"));
            BigDecimal unitCost = p == null ? Money.ZERO : d(p.get("costPrice"));
            Map<String, Object> row = new LinkedHashMap<>(r);
            row.put("shortfall", js(Money.max(Money.ZERO, d(r.get("par")).subtract(d(r.get("onHand")))), 6));
            row.put("category", p == null ? null : p.get("cname"));
            row.put("uom", p == null ? null : p.get("uom"));
            row.put("unitCost", js(unitCost));
            row.put("estimatedCost", js(d(r.get("suggestedOrderQty")).multiply(unitCost), 2));
            out.add(row);
        }
        out.sort((a, b) -> d(b.get("shortfall")).compareTo(d(a.get("shortfall"))));
        return out;
    }

    /** Expiring batches within the product scope. */
    public List<Map<String, Object>> expiring(Map<String, String> q) {
        List<Map<String, Object>> rows = queries.getExpiringBatches(q.get("days"), q.get("locationId"));
        List<String> scopedIds = scopedProductIds(q);
        Set<String> ids = scopedIds == null ? null : new java.util.HashSet<>(scopedIds);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : rows) {
            if (ids != null && !ids.contains(b.get("productId"))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> product = (Map<String, Object>) b.get("product");
            @SuppressWarnings("unchecked")
            Map<String, Object> location = (Map<String, Object>) b.get("location");
            @SuppressWarnings("unchecked")
            Map<String, Object> uom = product == null ? null : (Map<String, Object>) product.get("uom");
            BigDecimal qty = d(b.get("quantity"));
            BigDecimal unit = d(b.get("unitCost"));
            out.add(Obj.of("id", b.get("id"), "batchNumber", b.get("batchNumber"), "productId", b.get("productId"),
                    "code", product == null ? null : product.get("code"), "name", product == null ? null : product.get("name"),
                    "uom", uom == null ? null : uom.get("code"), "location", location == null ? null : location.get("name"),
                    "quantity", js(qty), "unitCost", js(unit), "value", js(qty.multiply(unit), 2), "expiryDate",
                    b.get("expiryDate"), "daysToExpiry", b.get("daysToExpiry"), "expired", b.get("expired")));
        }
        return out;
    }

    /** Negative-stock cells within the product scope. */
    public Map<String, Object> negativeStock(Map<String, String> q) {
        Map<String, Object> res = queries.getNegativeStock(q.get("locationId"));
        List<String> scopedIds = scopedProductIds(q);
        Set<String> ids = scopedIds == null ? null : new java.util.HashSet<>(scopedIds);
        if (ids == null) {
            return res;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) res.get("rows");
        List<Map<String, Object>> rows = all.stream().filter(r -> ids.contains(r.get("productId"))).toList();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            total = total.add(new BigDecimal((String) r.get("valuationExposure")));
        }
        return Obj.of("summary", Obj.of("cells", rows.size(), "clean", rows.isEmpty(), "totalValuationExposure",
                JsNumbers.dec(total), "zeroCostBasisCells", rows.stream().filter(r -> Boolean.TRUE.equals(
                        r.get("zeroCostBasis"))).count()), "rows", rows);
    }

    // ------------------------------------------------------------------ 7. stock health

    /** Aging buckets, turnover, days of cover and slow / dead classification per quant. */
    public Map<String, Object> stockHealth(Map<String, String> query) {
        Map<String, String> q = scope.apply(query);
        Instant now = Instant.now();
        double sd = JsNumbers.number(q.getOrDefault("slowDays", "90"));
        int slowDays = (int) Math.max(1, Math.min(3650, Double.isNaN(sd) || sd == 0 ? 90 : sd));
        double dd = JsNumbers.number(q.getOrDefault("deadDays", "180"));
        int deadDays = (int) Math.max(slowDays, Math.min(3650, Double.isNaN(dd) || dd == 0 ? 180 : dd));
        Range range = range(q);
        Instant end = range.end() != null ? range.end() : now;
        Instant start = range.start() != null ? range.start() : end.minusMillis(90L * 86_400_000L);
        long periodDays = Math.max(1, Math.round((end.toEpochMilli() - start.toEpochMilli()) / 86_400_000.0));
        List<String> ids = scopedProductIds(q);
        if (ids != null && ids.isEmpty()) {
            return Obj.of("summary", emptyHealthSummary(slowDays, deadDays, start, end), "rows", List.of());
        }
        Map<String, Object> params = new HashMap<>();
        params.put("org", org());
        params.put("start", SqlRows.ts(start));
        params.put("end", SqlRows.ts(end));
        String scope = "";
        if (notBlank(q.get("locationId"))) {
            params.put("loc", q.get("locationId"));
            scope += " AND si.\"locationId\" = :loc";
        }
        if (ids != null) {
            params.put("ids", ids);
            scope += " AND si.\"productId\" IN (:ids)";
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("""
                WITH quant AS (
                  SELECT si."productId", si."variantId", si."variantKey", si."locationId", si.quantity AS on_hand, si."runningAverageCost" AS avg_cost
                    FROM "StockItem" si
                    JOIN "InventoryLocation" loc ON loc.id = si."locationId"
                   WHERE si."organizationId" = :org AND si.quantity > 0 AND loc.type <> 'transit' """ + scope + """
                ),
                inbound AS (
                  SELECT l."productId", COALESCE(l."variantId", '') AS vkey, l."locationId", l."createdAt", l."quantityChange" AS q,
                         SUM(l."quantityChange") OVER (
                           PARTITION BY l."productId", COALESCE(l."variantId", ''), l."locationId"
                           ORDER BY l."createdAt" DESC, l.id DESC
                         ) AS cum
                    FROM "InventoryLedger" l
                    JOIN quant qt ON qt."productId" = l."productId" AND qt."variantKey" = COALESCE(l."variantId", '') AND qt."locationId" = l."locationId"
                   WHERE l."organizationId" = :org AND l."quantityChange" > 0
                     AND l.type IN ('receipt', 'return_in', 'production_output', 'transfer_in', 'opening_balance', 'adjustment_in', 'reversal_in')
                ),
                layers AS (
                  SELECT i."productId", i.vkey, i."locationId",
                         GREATEST(LEAST(i.q, qt.on_hand - (i.cum - i.q)), 0) AS layer_qty,
                         EXTRACT(EPOCH FROM (now() - i."createdAt")) / 86400 AS age_days
                    FROM inbound i
                    JOIN quant qt ON qt."productId" = i."productId" AND qt."variantKey" = i.vkey AND qt."locationId" = i."locationId"
                   WHERE i.cum - i.q < qt.on_hand
                ),
                aging AS (
                  SELECT "productId", vkey, "locationId",
                         SUM(layer_qty) FILTER (WHERE age_days <= 30)                    AS b0_30,
                         SUM(layer_qty) FILTER (WHERE age_days > 30 AND age_days <= 60)  AS b31_60,
                         SUM(layer_qty) FILTER (WHERE age_days > 60 AND age_days <= 90)  AS b61_90,
                         SUM(layer_qty) FILTER (WHERE age_days > 90 AND age_days <= 180) AS b91_180,
                         SUM(layer_qty) FILTER (WHERE age_days > 180)                    AS b180_plus,
                         SUM(layer_qty * age_days) / NULLIF(SUM(layer_qty), 0)           AS weighted_age
                    FROM layers GROUP BY "productId", vkey, "locationId"
                ),
                moves AS (
                  SELECT l."productId", COALESCE(l."variantId", '') AS vkey, l."locationId",
                         MAX(l."createdAt") FILTER (WHERE l."quantityChange" < 0 AND l.type IN ('issue', 'production_consume')) AS last_consumed_at,
                         MAX(l."createdAt") FILTER (WHERE l."quantityChange" > 0 AND l.type IN ('receipt', 'return_in', 'production_output', 'transfer_in', 'opening_balance')) AS last_inbound_at,
                         COALESCE(SUM(-l."quantityChange") FILTER (WHERE l."quantityChange" < 0 AND l.type IN ('issue', 'production_consume') AND l."createdAt" BETWEEN :start AND :end), 0) AS consumed_qty,
                         COALESCE(SUM(l."totalValue") FILTER (WHERE l."quantityChange" < 0 AND l.type IN ('issue', 'production_consume') AND l."createdAt" BETWEEN :start AND :end), 0) AS consumed_value,
                         COALESCE(SUM(l."quantityChange") FILTER (WHERE l."createdAt" > :start), 0) AS net_since_start,
                         COALESCE(SUM(l."quantityChange") FILTER (WHERE l."createdAt" > :end), 0) AS net_since_end
                    FROM "InventoryLedger" l
                    JOIN quant qt ON qt."productId" = l."productId" AND qt."variantKey" = COALESCE(l."variantId", '') AND qt."locationId" = l."locationId"
                   WHERE l."organizationId" = :org
                   GROUP BY l."productId", COALESCE(l."variantId", ''), l."locationId"
                ),
                lots AS (
                  SELECT b."productId", COALESCE(b."variantId", '') AS vkey, b."locationId", SUM(b.quantity * COALESCE(b."unitCost", 0)) AS lot_value
                    FROM "InventoryBatch" b
                    JOIN quant qt ON qt."productId" = b."productId" AND qt."variantKey" = COALESCE(b."variantId", '') AND qt."locationId" = b."locationId"
                   WHERE b."organizationId" = :org AND b."isActive" = true AND b.quantity > 0
                   GROUP BY b."productId", COALESCE(b."variantId", ''), b."locationId"
                )
                SELECT qt."productId", qt."variantId", pv.name AS variant_name, qt."locationId", loc.name AS location_name,
                       p.code, p.name, p."batchTracking", p."costingMethod"::text AS "costingMethod", p."costPrice", u.code AS uom, c.name AS category,
                       qt.on_hand, qt.avg_cost, lots.lot_value,
                       a.b0_30, a.b31_60, a.b61_90, a.b91_180, a.b180_plus, a.weighted_age,
                       m.last_consumed_at, m.last_inbound_at, m.consumed_qty, m.consumed_value, m.net_since_start, m.net_since_end
                  FROM quant qt
                  JOIN "Product" p ON p.id = qt."productId"
                  JOIN "InventoryLocation" loc ON loc.id = qt."locationId"
                  LEFT JOIN "ProductVariant" pv ON pv.id = qt."variantId"
                  LEFT JOIN "UnitOfMeasure" u ON u.id = p."uomId"
                  LEFT JOIN "ProductCategory" c ON c.id = p."categoryId"
                  LEFT JOIN aging a ON a."productId" = qt."productId" AND a.vkey = qt."variantKey" AND a."locationId" = qt."locationId"
                  LEFT JOIN moves m ON m."productId" = qt."productId" AND m.vkey = qt."variantKey" AND m."locationId" = qt."locationId"
                  LEFT JOIN lots ON lots."productId" = qt."productId" AND lots.vkey = qt."variantKey" AND lots."locationId" = qt."locationId"
                 ORDER BY p.name ASC, loc.name ASC
                 LIMIT 5000
                """).params(params));
        long dayMs = 86_400_000L;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal onHandDec = d(r.get("on_hand"));
            double onHand = onHandDec.doubleValue();
            boolean layered = Boolean.TRUE.equals(r.get("batchTracking")) || "FIFO".equals(r.get("costingMethod"));
            BigDecimal unitDec = layered
                    ? (onHandDec.signum() > 0 ? Money.div(d(r.get("lot_value")), onHandDec) : Money.ZERO)
                    : "STANDARD".equals(r.get("costingMethod")) ? d(r.get("costPrice")) : d(r.get("avg_cost"));
            BigDecimal value = Money.round(onHandDec.multiply(unitDec), 2);
            double consumedQty = n(r.get("consumed_qty"));
            double closingQty = onHand - n(r.get("net_since_end"));
            double openingQty = onHand - n(r.get("net_since_start"));
            double avgQty = (Math.max(openingQty, 0) + Math.max(closingQty, 0)) / 2;
            Instant lastConsumed = (Instant) r.get("last_consumed_at");
            Long daysSince = lastConsumed == null ? null
                    : (long) Math.floor((now.toEpochMilli() - lastConsumed.toEpochMilli()) / (double) dayMs);
            String status = daysSince == null || daysSince > deadDays ? "dead" : daysSince > slowDays ? "slow" : "active";
            double dailyUse = consumedQty / periodDays;
            double attributed = n(r.get("b0_30")) + n(r.get("b31_60")) + n(r.get("b61_90")) + n(r.get("b91_180"))
                    + n(r.get("b180_plus"));
            double unattributed = Math.max(0, onHand - attributed);
            out.add(Obj.of("productId", r.get("productId"), "variantId", r.get("variantId"), "variantName",
                    r.get("variant_name"), "locationId",
                    r.get("locationId"), "location", r.get("location_name"), "code", r.get("code"), "name", r.get("name"),
                    "category", r.get("category"), "uom", r.get("uom"), "onHand", js(onHandDec, 6), "unitCost",
                    js(unitDec, 6), "value", js(value), "aging", Obj.of("d0_30", js(round(n(r.get("b0_30")), 6)),
                            "d31_60", js(round(n(r.get("b31_60")), 6)), "d61_90", js(round(n(r.get("b61_90")), 6)),
                            "d91_180", js(round(n(r.get("b91_180")), 6)),
                            "d180_plus", js(round(n(r.get("b180_plus")) + unattributed, 6))),
                    "weightedAgeDays", r.get("weighted_age") == null ? null : Math.round(n(r.get("weighted_age"))),
                    "lastConsumedAt", lastConsumed, "lastInboundAt", r.get("last_inbound_at"), "daysSinceConsumed",
                    daysSince, "consumedQty", js(round(consumedQty, 6)), "consumedValue",
                    js(d(r.get("consumed_value")), 2), "turnover",
                    avgQty > 0 ? js(round(consumedQty / avgQty, 2)) : null, "daysOfCover",
                    dailyUse > 0 ? Math.round(onHand / dailyUse) : null, "status", status));
        }
        String statusFilter = q.get("status");
        List<Map<String, Object>> filtered = statusFilter != null && !statusFilter.isEmpty() && !"all".equals(statusFilter)
                ? out.stream().filter(r -> statusFilter.equals(r.get("status"))).toList() : out;
        Map<String, Object> summary = emptyHealthSummary(slowDays, deadDays, start, end);
        summary.put("quants", out.size());
        summary.put("totalValue", js(sumValue(out, null)));
        summary.put("activeValue", js(sumValue(out, "active")));
        summary.put("slowValue", js(sumValue(out, "slow")));
        summary.put("deadValue", js(sumValue(out, "dead")));
        summary.put("slowCount", out.stream().filter(r -> "slow".equals(r.get("status"))).count());
        summary.put("deadCount", out.stream().filter(r -> "dead".equals(r.get("status"))).count());
        summary.put("truncated", rows.size() >= 5000);
        return Obj.of("summary", summary, "rows", filtered);
    }

    private static BigDecimal sumValue(List<Map<String, Object>> rows, String status) {
        BigDecimal s = Money.ZERO;
        for (Map<String, Object> r : rows) {
            if (status == null || status.equals(r.get("status"))) {
                s = s.add(d(r.get("value")));
            }
        }
        return Money.round(s, 2);
    }

    private static Map<String, Object> emptyHealthSummary(int slowDays, int deadDays, Instant start, Instant end) {
        return Obj.of("slowDays", slowDays, "deadDays", deadDays, "start", localIso(start), "end", localIso(end),
                "quants", 0, "totalValue", 0, "activeValue", 0, "slowValue", 0, "deadValue", 0, "slowCount", 0,
                "deadCount", 0, "truncated", false);
    }
}
