package com.inventoryerp.beverage.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Beverage Control — read-only variance / yield / shrinkage reporting (port of {@code beverage-report.service.ts}).
 *
 * <p>Pours sold come from real sales × recipes: a sale of a beverage product counts its quantity as ml; a menu item
 * expands through its recipe ({@code MenuProduct}) to the underlying bottle products. The weighed truth comes from
 * the latest submitted bottle counts.
 */
@Service
public class BeverageReportService {

    record BevProduct(String id, String name, double containerVolumeMl, double standardPourMl, double costPerMl) {}

    record Sold(Map<String, Double> byProduct, Map<String, double[]> byWaiter, double alcoholSalesValue) {}

    private final JdbcClient jdbc;

    public BeverageReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** All digital-weight (bar alcohol) products with their conversion + cost meta. */
    private List<BevProduct> beverageProducts() {
        List<BevProduct> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, name, \"containerVolumeMl\", \"standardPourMl\","
                + " \"costPrice\" FROM \"Product\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND \"measurementMethod\"::text = 'digital_weight'").param(org()))) {
            out.add(new BevProduct(str(r, "id"), str(r, "name"), dec(r, "containerVolumeMl").doubleValue(),
                    dec(r, "standardPourMl").doubleValue(), dec(r, "costPrice").doubleValue()));
        }
        return out;
    }

    /** ml sold per beverage product in [from, to), optionally grouped by waiter (bartender attribution). */
    private Sold soldMl(Instant from, Instant to, Set<String> bevIds) {
        List<Map<String, Object>> items = SqlRows.list(jdbc.sql("SELECT i.\"waiterId\", it.\"productId\","
                        + " it.\"menuItemId\", it.quantity, it.total FROM \"Invoice\" i JOIN \"InvoiceItem\" it"
                        + " ON it.\"invoiceId\" = i.id WHERE i.\"organizationId\" = ? AND i.\"issueDate\" >= ?"
                        + " AND i.\"issueDate\" < ? AND i.status::text NOT IN ('draft', 'cancelled')"
                        + " ORDER BY i.id, it.id")
                .params(org(), SqlRows.ts(from), SqlRows.ts(to)));

        Map<String, List<Object[]>> recipeByMenuItem = new HashMap<>();
        List<String> menuItemIds = items.stream().map(it -> str(it, "menuItemId")).filter(m -> m != null).distinct()
                .toList();
        if (!menuItemIds.isEmpty() && !bevIds.isEmpty()) {
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"menuItemId\", \"productId\", quantity FROM"
                            + " \"MenuProduct\" WHERE \"organizationId\" = :org AND \"menuItemId\" IN (:mi)"
                            + " AND \"productId\" IN (:p)")
                    .param("org", org()).param("mi", menuItemIds).param("p", List.copyOf(bevIds)))) {
                recipeByMenuItem.computeIfAbsent(str(r, "menuItemId"), k -> new ArrayList<>())
                        .add(new Object[] {str(r, "productId"), dec(r, "quantity").doubleValue()});
            }
        }

        Map<String, Double> byProduct = new HashMap<>();
        Map<String, double[]> byWaiter = new LinkedHashMap<>();
        double alcoholSalesValue = 0;
        for (Map<String, Object> it : items) {
            String waiter = it.get("waiterId") != null ? str(it, "waiterId") : "unassigned";
            double qty = dec(it, "quantity").doubleValue();
            String productId = str(it, "productId");
            String menuItemId = str(it, "menuItemId");
            double lineMl = 0;
            boolean isAlcohol = false;
            if (productId != null && bevIds.contains(productId)) {
                lineMl = qty;
                byProduct.merge(productId, qty, Double::sum);
                isAlcohol = true;
            } else if (menuItemId != null && recipeByMenuItem.containsKey(menuItemId)) {
                for (Object[] ing : recipeByMenuItem.get(menuItemId)) {
                    double ml = (double) ing[1] * qty;
                    lineMl += ml;
                    byProduct.merge((String) ing[0], ml, Double::sum);
                }
                isAlcohol = lineMl > 0;
            }
            if (isAlcohol) {
                double value = dec(it, "total").doubleValue();
                alcoholSalesValue += value;
                double[] w = byWaiter.computeIfAbsent(waiter, k -> new double[2]);
                w[0] += lineMl;
                w[1] += value;
            }
        }
        return new Sold(byProduct, byWaiter, alcoholSalesValue);
    }

    /** Latest submitted bottle-count line per product (optionally by location). */
    private Map<String, Map<String, Object>> latestCountLines(String locationId) {
        StringBuilder sql = new StringBuilder("SELECT id, \"submittedAt\", \"locationId\" FROM \"BottleCountSession\""
                + " WHERE \"organizationId\" = :org AND \"deletedAt\" IS NULL AND status = 'submitted'");
        if (locationId != null) {
            sql.append(" AND \"locationId\" = :loc");
        }
        sql.append(" ORDER BY \"submittedAt\" DESC NULLS LAST LIMIT 50");
        var spec = jdbc.sql(sql.toString()).param("org", org());
        if (locationId != null) {
            spec = spec.param("loc", locationId);
        }
        Map<String, Map<String, Object>> byProduct = new HashMap<>();
        for (Map<String, Object> s : SqlRows.list(spec)) {
            for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT \"productId\", \"systemMl\", \"countedMl\","
                    + " \"varianceMl\", \"varianceG\", confidence FROM \"BottleCountLine\" WHERE \"sessionId\" = ?"
                    + " AND \"organizationId\" = ?").params(s.get("id"), org()))) {
                if (!byProduct.containsKey(str(l, "productId"))) {
                    l.put("submittedAt", s.get("submittedAt"));
                    l.put("locationId", s.get("locationId"));
                    byProduct.put(str(l, "productId"), l);
                }
            }
        }
        return byProduct;
    }

    // ── reports ──────────────────────────────────────────────────────────────

    /** Bottle variance — weighed vs system, per product, from the latest counts. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> variance(String locationId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Row r : varianceRows(locationId)) {
            rows.add(Obj.of("productId", r.productId, "product", r.product, "systemMl", JsNumbers.js(r.systemMl),
                    "countedMl", JsNumbers.js(r.countedMl), "varianceMl", JsNumbers.js(r.varianceMl),
                    "varianceG", JsNumbers.js(r.varianceG), "variancePct", JsNumbers.js(JsNumbers.round(r.variancePct, 2)),
                    "lossValue", JsNumbers.js(JsNumbers.round(r.lossValue, 2)), "confidence", r.confidence,
                    "countedAt", r.countedAt));
        }
        return rows;
    }

    private record Row(String productId, String product, double systemMl, double countedMl, double varianceMl,
            double varianceG, double variancePct, double lossValue, String confidence, Object countedAt) {}

    private List<Row> varianceRows(String locationId) {
        List<BevProduct> products = beverageProducts();
        Map<String, Map<String, Object>> latest = latestCountLines(locationId);
        List<Row> rows = new ArrayList<>();
        for (BevProduct p : products) {
            Map<String, Object> l = latest.get(p.id());
            if (l == null || l.get("countedMl") == null) {
                continue;
            }
            double systemMl = dec(l, "systemMl").doubleValue();
            double varianceMl = dec(l, "varianceMl").doubleValue();
            double lossValue = varianceMl < 0 ? Math.abs(varianceMl) * p.costPerMl() : 0;
            double variancePct = systemMl > 0 ? (varianceMl / systemMl) * 100 : 0;
            rows.add(new Row(p.id(), p.name(), systemMl, dec(l, "countedMl").doubleValue(), varianceMl,
                    dec(l, "varianceG").doubleValue(), variancePct, JsNumbers.round(lossValue, 2), str(l, "confidence"),
                    SqlRows.value(l.get("submittedAt"))));
        }
        return rows;
    }

    /** Yield — expected shots per bottle vs actual shots sold in the window. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> yieldReport(Instant from, Instant to, String locationId) {
        List<BevProduct> products = beverageProducts();
        Sold sold = soldMl(from, to, ids(products));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BevProduct p : products) {
            double soldMl = sold.byProduct().getOrDefault(p.id(), 0.0);
            double soldShots = p.standardPourMl() > 0 ? soldMl / p.standardPourMl() : 0;
            double shotsPerBottle = p.standardPourMl() > 0 ? p.containerVolumeMl() / p.standardPourMl() : 0;
            double bottlesSold = p.containerVolumeMl() > 0 ? soldMl / p.containerVolumeMl() : 0;
            rows.add(Obj.of("productId", p.id(), "product", p.name(),
                    "shotsPerBottle", JsNumbers.js(JsNumbers.round(shotsPerBottle, 1)),
                    "soldMl", JsNumbers.js(JsNumbers.round(soldMl, 1)),
                    "soldShots", JsNumbers.js(JsNumbers.round(soldShots, 1)),
                    "bottlesSold", JsNumbers.js(JsNumbers.round(bottlesSold, 2))));
        }
        return rows;
    }

    /** Bartender variance — alcohol pours + value grouped by waiter. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> bartender(Instant from, Instant to) {
        Sold sold = soldMl(from, to, ids(beverageProducts()));
        List<String> waiterIds = sold.byWaiter().keySet().stream().filter(w -> !"unassigned".equals(w)).toList();
        Map<String, String> nameById = new HashMap<>();
        if (!waiterIds.isEmpty()) {
            for (Map<String, Object> u : SqlRows.list(jdbc.sql("SELECT id, \"firstName\", \"lastName\" FROM \"User\""
                    + " WHERE id IN (:ids) AND \"organizationId\" = :org AND \"deletedAt\" IS NULL")
                    .param("ids", waiterIds).param("org", org()))) {
                String full = ((u.get("firstName") == null ? "" : str(u, "firstName")) + " "
                        + (u.get("lastName") == null ? "" : str(u, "lastName"))).trim();
                nameById.put(str(u, "id"), full.isEmpty() ? str(u, "id") : full);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        sold.byWaiter().forEach((waiterId, v) -> rows.add(Obj.of("waiterId", waiterId,
                "bartender", "unassigned".equals(waiterId) ? "Unassigned" : nameById.getOrDefault(waiterId, waiterId),
                "soldMl", JsNumbers.js(JsNumbers.round(v[0], 1)),
                "salesValue", JsNumbers.js(JsNumbers.round(v[1], 2)))));
        rows.sort((a, b) -> Double.compare(((Number) b.get("salesValue")).doubleValue(),
                ((Number) a.get("salesValue")).doubleValue()));
        return rows;
    }

    /** KPI dashboard — open bottles, sales, variance %, open variances and shrinkage trend. */
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(String locationId) {
        ZoneId zone = BusinessZone.get();
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(zone);
        Instant startOfToday = today.atStartOfDay(zone).toInstant();
        Instant weekAgo = now.minus(Duration.ofDays(7));
        Instant startOfMonth = today.withDayOfMonth(1).atStartOfDay(zone).toInstant();
        Instant startOfLastMonth = today.withDayOfMonth(1).minusMonths(1).atStartOfDay(zone).toInstant();

        Set<String> bevIds = ids(beverageProducts());
        List<Row> variance = varianceRows(locationId);
        Sold todaySold = soldMl(startOfToday, now, bevIds);
        Sold week = soldMl(weekAgo, now, bevIds);
        Sold month = soldMl(startOfMonth, now, bevIds);
        Sold lastMonth = soldMl(startOfLastMonth, startOfMonth, bevIds);

        long openVariances = variance.stream().filter(v -> v.confidence() != null && !v.confidence().isEmpty()
                && !"GOOD".equals(v.confidence())).count();
        double totalLoss = 0;
        double totalSystemMl = 0;
        for (Row v : variance) {
            totalLoss += v.lossValue();
            totalSystemMl += v.systemMl();
        }
        double loss = totalLoss;
        return Obj.of(
                "openBottles", variance.stream().filter(v -> v.countedMl() > 0).count(),
                "todaysAlcoholSales", JsNumbers.js(JsNumbers.round(todaySold.alcoholSalesValue(), 2)),
                "todaysVariancePct", JsNumbers.js(totalSystemMl > 0 ? JsNumbers.round((totalLoss / totalSystemMl) * 100, 2)
                        : 0),
                "openVariances", openVariances,
                "countsPending", 0,
                "shrinkage", Obj.of(
                        "thisWeek", shrink(loss, week.alcoholSalesValue()),
                        "thisMonth", shrink(loss, month.alcoholSalesValue()),
                        "lastMonth", shrink(loss, lastMonth.alcoholSalesValue())),
                "totalLossValue", JsNumbers.js(JsNumbers.round(totalLoss, 2)));
    }

    private static Number shrink(double loss, double sales) {
        return JsNumbers.js(sales > 0 ? JsNumbers.round((loss / sales) * 100, 2) : 0);
    }

    private static Set<String> ids(List<BevProduct> products) {
        Set<String> ids = new LinkedHashSet<>();
        products.forEach(p -> ids.add(p.id()));
        return ids;
    }
}
