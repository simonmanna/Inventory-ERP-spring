package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.InventoryValuationService;
import static com.inventoryerp.inventory.application.InventoryLookups.dec;
import static com.inventoryerp.inventory.application.InventoryLookups.str;

import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.inventory.domain.InventoryBatch;
import com.inventoryerp.inventory.domain.InventoryLedger;
import com.inventoryerp.inventory.domain.StockItem;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory read models (port of {@code inventory-query.service.ts}): stock items, per-product stock levels,
 * item detail, stats, the paged ledger, expiring batches, reorder suggestions, movement summary, the
 * cached-vs-ledger-vs-batch reconciliation and negative-stock exposure.
 */
@Service
@Transactional(readOnly = true)
public class InventoryQueryService {

    private final JdbcClient jdbc;
    private final UomConversionService uomConversion;
    private final InventoryValuationService valuation;
    private final LocationScope scope;

    public InventoryQueryService(JdbcClient jdbc, UomConversionService uomConversion, LocationScope scope,
            InventoryValuationService valuation) {
        this.valuation = valuation;
        this.jdbc = jdbc;
        this.uomConversion = uomConversion;
        this.scope = scope;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static int page(String v) {
        return Math.max(1, JsNumbers.orDefault(v, 1));
    }

    private static int pageSize(String v, int def, int max) {
        return Math.min(max, Math.max(1, JsNumbers.orDefault(v, def)));
    }

    private static String like(String s) {
        return "%" + BaseCrudService.escapeLike(s) + "%";
    }

    // ------------------------------------------------------------------ items

    /** Stock items of inventory-tracked products (product + location includes), paged by product name. */
    public PageResult<Map<String, Object>> listItems(Map<String, String> q) {
        int page = page(q.get("page"));
        int size = pageSize(q.get("pageSize"), 25, 200);
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" si.\"organizationId\" = ? AND p.\"trackInventory\" = true");
        if (notBlank(q.get("locationId"))) {
            scope.assertAllowed(q.get("locationId"));
            where.append(" AND si.\"locationId\" = ?");
            params.add(q.get("locationId"));
        }
        DataScope access = scope.current();
        if (access.restricted()) {
            where.append(" AND si.\"locationId\" = ANY(?::text[])");
            params.add(access.locationArray());
        }
        if ("true".equals(q.get("lowStock"))) {
            where.append(" AND si.quantity > 0 AND p.\"minQuantity\" > 0 AND si.quantity <= p.\"minQuantity\"");
        }
        if (notBlank(q.get("search"))) {
            where.append(" AND (p.code ILIKE ? OR p.name ILIKE ? OR p.sku ILIKE ? OR l.name ILIKE ?)");
            String s = like(q.get("search"));
            params.addAll(List.of(s, s, s, s));
        }
        String from = " FROM \"StockItem\" si JOIN \"Product\" p ON p.id = si.\"productId\""
                + " JOIN \"InventoryLocation\" l ON l.id = si.\"locationId\" WHERE" + where;
        List<Map<String, Object>> rows = jdbc.sql("SELECT si.*" + from + " ORDER BY p.name ASC LIMIT " + size
                + " OFFSET " + (page - 1) * size).params(params).query().listOfRows();
        long total = jdbc.sql("SELECT count(*)" + from).params(params).query(Long.class).single();
        List<Map<String, Object>> items = rows.stream().map(raw -> SqlRows.as(StockItem.class, raw)).toList();
        // One query each for the page's products, their units and locations (was three per row).
        Map<String, Map<String, Object>> products = byId(SqlRows.list(jdbc.sql("SELECT id, code, name, sku,"
                + " \"minQuantity\", \"batchTracking\", \"uomId\" FROM \"Product\" WHERE id IN (:ids)"
                + " AND \"organizationId\" = :org")
                .param("ids", idsOf(items, "productId")).param("org", org())), items.isEmpty());
        Map<String, Map<String, Object>> uoms = byId(SqlRows.list(com.inventoryerp.core.domain.UnitOfMeasure.class,
                jdbc.sql("SELECT * FROM \"UnitOfMeasure\" WHERE id IN (:ids)")
                        .param("ids", idsOf(products.values(), "uomId"))), products.isEmpty());
        Map<String, Map<String, Object>> locations = locationRefs(idsOf(items, "locationId"));
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> r : items) {
            Map<String, Object> found = products.get(str(r, "productId"));
            Map<String, Object> p = found == null ? null : new LinkedHashMap<>(found);
            if (p != null) {
                Object uomId = p.remove("uomId");
                Map<String, Object> uom = uomId == null ? null : uoms.get(uomId.toString());
                p.put("uom", uom == null ? null : new LinkedHashMap<>(uom));
            }
            r.put("product", p);
            Map<String, Object> location = locations.get(str(r, "locationId"));
            r.put("location", location == null ? null : new LinkedHashMap<>(location));
            data.add(r);
        }
        return PageResult.of(data, page, size, total);
    }

    /**
     * All products with aggregated stock levels (+ reservations), one row per product. h-01 F6: the low / out-of-stock
     * filters run in SQL so only the page is mapped, each page is mapped with one query per kind of data, quantities
     * stay decimal, and a branch-restricted user's {@code locationBreakdown} lists only the locations they may see.
     */
    public PageResult<Map<String, Object>> listProductStockLevels(Map<String, String> query) {
        Map<String, String> q = scope.apply(query);
        int page = page(q.get("page"));
        int size = pageSize(q.get("pageSize"), 25, 200);
        String locationId = notBlank(q.get("locationId")) ? q.get("locationId") : null;
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ? AND \"deletedAt\" IS NULL");
        if (notBlank(q.get("search"))) {
            where.append(" AND (code ILIKE ? OR name ILIKE ? OR sku ILIKE ?)");
            String s = like(q.get("search"));
            params.addAll(List.of(s, s, s));
        }
        boolean low = "true".equals(q.get("lowStock"));
        boolean out = "true".equals(q.get("outOfStock"));
        if (low || out) {
            // The isOut / isLow rule of mapProduct: out = no stock; low = a minimum is set and stock is at most that.
            List<Object> all = new ArrayList<>();
            if (locationId != null) {
                all.add(locationId);
            }
            all.addAll(params);
            String stocked = "WITH p AS (SELECT \"Product\".*, (SELECT COALESCE(SUM(si.quantity), 0) FROM \"StockItem\" si"
                    + " WHERE si.\"organizationId\" = \"Product\".\"organizationId\" AND si.\"productId\" = \"Product\".id"
                    + (locationId != null ? " AND si.\"locationId\" = ?" : "") + ") AS stock_qty FROM \"Product\" WHERE"
                    + where + ") ";
            String cond = out ? " WHERE stock_qty <= 0"
                    : " WHERE (stock_qty <= 0 OR (COALESCE(\"minQuantity\", 0) > 0 AND stock_qty <= \"minQuantity\"))";
            List<Map<String, Object>> rows = SqlRows.list(jdbc.sql(stocked + "SELECT * FROM p" + cond
                    + " ORDER BY name ASC LIMIT " + size + " OFFSET " + (page - 1) * size).params(all));
            long total = jdbc.sql(stocked + "SELECT count(*) FROM p" + cond).params(all).query(Long.class).single();
            return PageResult.of(mapProducts(rows, locationId), page, size, total);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT * FROM \"Product\" WHERE" + where
                + " ORDER BY name ASC LIMIT " + size + " OFFSET " + (page - 1) * size).params(params));
        long total = jdbc.sql("SELECT count(*) FROM \"Product\" WHERE" + where).params(params).query(Long.class).single();
        return PageResult.of(mapProducts(rows, locationId), page, size, total);
    }

    private Map<String, BigDecimal> loadReserved(List<String> ids, String locationId) {
        Map<String, BigDecimal> out = new HashMap<>();
        var spec = jdbc.sql("SELECT \"productId\", SUM(quantity) AS q FROM \"StockReservation\" WHERE"
                        + " \"organizationId\" = :org AND status = 'active' AND \"productId\" IN (:ids)"
                        + (locationId != null ? " AND \"locationId\" = :loc" : "") + " GROUP BY \"productId\"")
                .param("org", org()).param("ids", ids);
        if (locationId != null) {
            spec = spec.param("loc", locationId);
        }
        for (Map<String, Object> g : spec.query().listOfRows()) {
            out.put((String) g.get("productId"), g.get("q") == null ? BigDecimal.ZERO : (BigDecimal) g.get("q"));
        }
        return out;
    }

    /** One page of products: stock rows, reservations, values and units read once for the whole page. */
    private List<Map<String, Object>> mapProducts(List<Map<String, Object>> products, String locationId) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (products.isEmpty()) {
            return data;
        }
        List<String> ids = products.stream().map(p -> str(p, "id")).toList();
        Map<String, BigDecimal> reserved = loadReserved(ids, locationId);
        Map<String, List<Map<String, Object>>> items = new HashMap<>();
        for (Map<String, Object> si : SqlRows.list(jdbc.sql("SELECT si.\"productId\", si.quantity, si.\"locationId\","
                        + " l.id AS lid, l.code, l.name FROM \"StockItem\" si JOIN \"InventoryLocation\" l"
                        + " ON l.id = si.\"locationId\" WHERE si.\"organizationId\" = :org AND si.\"productId\" IN (:ids)"
                        + " ORDER BY si.\"createdAt\", si.id")
                .param("org", org()).param("ids", ids))) {
            items.computeIfAbsent(str(si, "productId"), k -> new ArrayList<>()).add(si);
        }
        // Phase I2 (audit C-2): value by the product's costing method (lots, serials, standard), not qty × average.
        Map<String, BigDecimal[]> values = valuation.qtyAndValueByProduct(ids, locationId);
        Set<String> uomIds = new HashSet<>();
        products.forEach(p -> {
            if (p.get("uomId") != null) {
                uomIds.add(str(p, "uomId"));
            }
        });
        Map<String, String> uoms = new HashMap<>();
        if (!uomIds.isEmpty()) {
            for (Map<String, Object> u : SqlRows.list(jdbc.sql("SELECT id, code FROM \"UnitOfMeasure\" WHERE id IN (:ids)")
                    .param("ids", List.copyOf(uomIds)))) {
                uoms.put(str(u, "id"), str(u, "code"));
            }
        }
        DataScope access = scope.current();
        for (Map<String, Object> p : products) {
            String id = str(p, "id");
            data.add(mapProduct(p, locationId, access, items.getOrDefault(id, List.of()), values.get(id),
                    reserved.getOrDefault(id, BigDecimal.ZERO), p.get("uomId") == null ? null : uoms.get(str(p, "uomId"))));
        }
        return data;
    }

    private static Map<String, Object> mapProduct(Map<String, Object> p, String locationId, DataScope access,
            List<Map<String, Object>> items, BigDecimal[] qv, BigDecimal res, String uom) {
        List<Map<String, Object>> breakdown = new ArrayList<>();
        BigDecimal totalQty = BigDecimal.ZERO;
        for (Map<String, Object> si : items) {
            BigDecimal qty = dec(si, "quantity");
            // h-01 F6: a branch-restricted user sees only the locations of their own access.
            if (access.allowsLocation(str(si, "locationId"))) {
                breakdown.add(Obj.of("locationId", si.get("lid"), "code", si.get("code"), "name", si.get("name"),
                        "quantity", JsNumbers.js(qty)));
            }
            if (locationId == null || locationId.equals(si.get("locationId"))) {
                totalQty = totalQty.add(qty);
            }
        }
        BigDecimal totalCost = qv == null ? BigDecimal.ZERO : qv[1];
        BigDecimal avg = totalQty.signum() > 0 ? Money.div(totalCost, totalQty) : BigDecimal.ZERO;
        BigDecimal min = (BigDecimal) p.get("minQuantity");
        BigDecimal minQty = min != null ? min : BigDecimal.ZERO;
        return Obj.of("id", p.get("id"), "code", p.get("code"), "name", p.get("name"), "sku", p.get("sku"),
                "productType", p.get("productType"),
                "minQuantity", min != null && min.signum() != 0 ? JsNumbers.js(min) : null,
                "batchTracking", p.get("batchTracking"), "uom", uom, "totalQuantity", JsNumbers.js(totalQty),
                "averageCost", JsNumbers.js(avg), "totalValue", JsNumbers.js(totalCost), "locationBreakdown", breakdown,
                "reservedQuantity", JsNumbers.js(res), "availableQuantity", JsNumbers.js(totalQty.subtract(res)),
                "isLow", minQty.signum() > 0 && totalQty.signum() > 0 && totalQty.compareTo(minQty) <= 0,
                "isOut", totalQty.signum() <= 0);
    }

    /** Product header, quants, batches, recent ledger, recipes and purchase lines for one product. */
    public Map<String, Object> getItemDetail(String productId, String locationFilter) {
        String locationId = scope.resolve(locationFilter);
        Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT id, code, name, sku, barcode, description,"
                        + " \"productType\", \"costingMethod\", \"minQuantity\", \"reorderQty\", \"batchTracking\","
                        + " \"trackInventory\", \"stockPolicy\", \"uomId\", \"purchaseUomId\", \"salesPrice\", \"costPrice\","
                        + " \"isActive\", \"createdAt\", \"updatedAt\", \"categoryId\", \"supplierId\" FROM \"Product\""
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1")
                .params(productId, org()));
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        Object categoryId = product.remove("categoryId");
        Object supplierId = product.remove("supplierId");
        product.put("category", categoryId == null ? null : SqlRows.one(jdbc.sql("SELECT id, name, \"parentId\" FROM"
                + " \"ProductCategory\" WHERE id = ?").param(categoryId)));
        product.put("supplier", supplierId == null ? null : SqlRows.one(jdbc.sql("SELECT id, name FROM \"Partner\""
                + " WHERE id = ?").param(supplierId)));
        String loc = notBlank(locationId) ? locationId : null;
        List<Object> params = new ArrayList<>(List.of(org(), productId));
        String locFilter = "";
        if (loc != null) {
            locFilter = " AND \"locationId\" = ?";
            params.add(loc);
        }
        List<Map<String, Object>> items = SqlRows.list(StockItem.class, jdbc.sql("SELECT * FROM \"StockItem\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ?" + locFilter).params(params));
        BigDecimal onHand = Money.ZERO;
        // Phase I2 (audit C-2): value by costing method.
        BigDecimal[] qv = valuation.qtyAndValueByProduct(List.of(productId), loc).get(productId);
        BigDecimal value = qv == null ? Money.ZERO : qv[1];
        for (Map<String, Object> i : items) {
            onHand = onHand.add(dec(i, "quantity"));
            i.put("location", SqlRows.one(jdbc.sql("SELECT id, code, name, type::text AS type FROM \"InventoryLocation\""
                    + " WHERE id = ?").param(i.get("locationId"))));
        }
        List<Map<String, Object>> batches = new ArrayList<>();
        if (Boolean.TRUE.equals(product.get("batchTracking"))) {
            batches = SqlRows.list(InventoryBatch.class, jdbc.sql("SELECT * FROM \"InventoryBatch\" WHERE"
                    + " \"organizationId\" = ? AND \"productId\" = ? AND \"isActive\" = true" + locFilter
                    + " ORDER BY \"expiryDate\" ASC NULLS LAST, \"receivedAt\" ASC").params(params));
            for (Map<String, Object> b : batches) {
                b.put("location", locationRef(b.get("locationId")));
            }
        }
        List<Map<String, Object>> ledger = SqlRows.list(InventoryLedger.class, jdbc.sql("SELECT * FROM \"InventoryLedger\""
                + " WHERE \"organizationId\" = ? AND \"productId\" = ?" + locFilter + " ORDER BY \"createdAt\" DESC"
                + " LIMIT 100").params(params));
        for (Map<String, Object> l : ledger) {
            l.put("location", SqlRows.one(jdbc.sql("SELECT code, name FROM \"InventoryLocation\" WHERE id = ?")
                    .param(l.get("locationId"))));
            l.put("batch", l.get("batchId") == null ? null : SqlRows.one(jdbc.sql("SELECT \"batchNumber\" FROM"
                    + " \"InventoryBatch\" WHERE id = ?").param(l.get("batchId"))));
        }
        List<Map<String, Object>> menuProducts = SqlRows.list(
                jdbc.sql("SELECT * FROM \"MenuProduct\" WHERE \"organizationId\" = ? AND \"productId\" = ?")
                        .params(org(), productId));
        for (Map<String, Object> mp : menuProducts) {
            mp.put("menuItem", SqlRows.one(jdbc.sql("SELECT id, code, name FROM \"MenuItem\" WHERE id = ?")
                    .param(mp.get("menuItemId"))));
        }
        List<Map<String, Object>> poLines = new ArrayList<>();
        for (Map<String, Object> raw : jdbc.sql("SELECT pl.* FROM \"PurchaseOrderLine\" pl JOIN \"PurchaseOrder\" o"
                        + " ON o.id = pl.\"purchaseOrderId\" WHERE pl.\"organizationId\" = ? AND pl.\"productId\" = ?"
                        + " ORDER BY o.\"createdAt\" DESC LIMIT 50")
                .params(org(), productId).query().listOfRows()) {
            Map<String, Object> line = SqlRows.normalize(raw);
            Map<String, Object> order = SqlRows.one(jdbc.sql("SELECT id, \"orderNumber\", status::text AS status,"
                    + " \"createdAt\", \"partnerId\" FROM \"PurchaseOrder\" WHERE id = ?").param(line.get("purchaseOrderId")));
            if (order != null) {
                Object partnerId = order.remove("partnerId");
                order.put("partner", partnerId == null ? null : SqlRows.one(jdbc.sql("SELECT id, name FROM \"Partner\""
                        + " WHERE id = ?").param(partnerId)));
            }
            line.put("order", order);
            poLines.add(line);
        }
        return Obj.of("product", product, "items", items, "totalOnHand", JsNumbers.js(onHand), "totalValue",
                JsNumbers.js(value), "batches", batches, "recentLedger", ledger, "menuProducts", menuProducts,
                "purchaseOrderLines", poLines);
    }

    private Map<String, Object> locationRef(Object id) {
        return SqlRows.one(jdbc.sql("SELECT id, code, name FROM \"InventoryLocation\" WHERE id = ?").param(id));
    }

    /** {@code {id, code, name}} of each location, by id (one query). */
    private Map<String, Map<String, Object>> locationRefs(List<String> ids) {
        return byId(SqlRows.list(jdbc.sql("SELECT id, code, name FROM \"InventoryLocation\" WHERE id IN (:ids)"
                + " AND \"organizationId\" = :org").param("ids", ids).param("org", org())), ids.isEmpty());
    }

    /** Distinct non-null values of {@code key}; a never-matching placeholder when there are none. */
    private static List<String> idsOf(java.util.Collection<Map<String, Object>> rows, String key) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            if (r.get(key) != null) {
                ids.add(r.get(key).toString());
            }
        }
        return ids.isEmpty() ? List.of("") : List.copyOf(ids);
    }

    /** Rows keyed by {@code id} ({@code skip}: nothing to look up, no rows). */
    private static Map<String, Map<String, Object>> byId(List<Map<String, Object>> rows, boolean skip) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (!skip) {
            rows.forEach(r -> out.put(r.get("id").toString(), r));
        }
        return out;
    }

    /**
     * Usability fix 2026-11-17 (UB1): the people a stock form can name as responsible / approver: the organization's
     * active users, id and name only. The forms used {@code GET /users}, which needs {@code user:read}, so a storekeeper
     * without user administration could not fill them in.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> staffOptions() {
        return SqlRows.list(jdbc.sql("SELECT u.id, u.\"firstName\", u.\"lastName\" FROM \"User\" u WHERE"
                + " u.\"organizationId\" = ? AND u.\"deletedAt\" IS NULL AND u.\"isActive\" = true ORDER BY"
                + " u.\"firstName\", u.\"lastName\", u.id LIMIT 2000").param(TenantContext.organizationId()));
    }

    /** {@code {totalItems, lowStockCount, totalLocations, totalProducts}}. */
    public Map<String, Object> getStockStats() {
        DataScope access = scope.current();
        if (access.restricted()) {
            return restrictedStats(access);
        }
        List<Map<String, Object>> products = jdbc.sql("SELECT p.id, p.\"minQuantity\","
                        + " COALESCE((SELECT SUM(si.quantity) FROM \"StockItem\" si WHERE si.\"productId\" = p.id), 0) AS qty,"
                        + " EXISTS (SELECT 1 FROM \"StockItem\" si WHERE si.\"productId\" = p.id AND si.quantity > 0) AS has"
                        + " FROM \"Product\" p WHERE p.\"organizationId\" = ? AND p.\"deletedAt\" IS NULL")
                .param(org()).query().listOfRows();
        long totalItems = products.stream().filter(p -> Boolean.TRUE.equals(p.get("has"))).count();
        long low = products.stream().filter(p -> {
            BigDecimal qty = (BigDecimal) p.get("qty");
            BigDecimal min = p.get("minQuantity") == null ? Money.ZERO : (BigDecimal) p.get("minQuantity");
            return min.signum() > 0 && qty.signum() > 0 && qty.compareTo(min) <= 0;
        }).count();
        long locations = jdbc.sql("SELECT count(*) FROM \"InventoryLocation\" WHERE \"organizationId\" = ?"
                + " AND \"isActive\" = true AND \"deletedAt\" IS NULL").param(org()).query(Long.class).single();
        return Obj.of("totalItems", totalItems, "lowStockCount", low, "totalLocations", locations, "totalProducts",
                (long) products.size());
    }

    /** {@link #getStockStats} over a restricted user's locations (BR-1). */
    private Map<String, Object> restrictedStats(DataScope access) {
        String[] allowed = access.locationArray();
        List<Map<String, Object>> products = jdbc.sql("SELECT p.id, p.\"minQuantity\","
                        + " COALESCE((SELECT SUM(si.quantity) FROM \"StockItem\" si WHERE si.\"productId\" = p.id AND"
                        + " si.\"locationId\" = ANY(?::text[])), 0) AS qty FROM \"Product\" p WHERE"
                        + " p.\"organizationId\" = ? AND p.\"deletedAt\" IS NULL")
                .params(allowed, org()).query().listOfRows();
        long totalItems = products.stream().filter(p -> ((BigDecimal) p.get("qty")).signum() > 0).count();
        long low = products.stream().filter(p -> {
            BigDecimal qty = (BigDecimal) p.get("qty");
            BigDecimal min = p.get("minQuantity") == null ? Money.ZERO : (BigDecimal) p.get("minQuantity");
            return min.signum() > 0 && qty.signum() > 0 && qty.compareTo(min) <= 0;
        }).count();
        return Obj.of("totalItems", totalItems, "lowStockCount", low, "totalLocations", (long) allowed.length,
                "totalProducts", (long) products.size());
    }

    /** Paged stock ledger (newest first) with product / variant / location / batch includes. */
    public PageResult<Map<String, Object>> getLedger(Map<String, String> q) {
        int page = page(q.get("page"));
        int size = pageSize(q.get("pageSize"), 25, 200);
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ?");
        for (String f : List.of("productId", "locationId", "referenceType")) {
            if (notBlank(q.get(f))) {
                where.append(" AND \"").append(f).append("\" = ?");
                params.add(q.get(f));
            }
        }
        DataScope access = scope.current();
        if (access.restricted()) {
            where.append(" AND \"locationId\" = ANY(?::text[])");
            params.add(access.locationArray());
        }
        if (notBlank(q.get("type"))) {
            where.append(" AND type::text = ?");
            params.add(q.get("type"));
        }
        if (notBlank(q.get("dateFrom"))) {
            where.append(" AND \"createdAt\" >= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(q.get("dateFrom"))));
        }
        if (notBlank(q.get("dateTo"))) {
            where.append(" AND \"createdAt\" <= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(q.get("dateTo"))));
        }
        List<Map<String, Object>> rows = SqlRows.list(InventoryLedger.class, jdbc.sql("SELECT * FROM \"InventoryLedger\""
                + " WHERE" + where + " ORDER BY \"createdAt\" DESC LIMIT " + size + " OFFSET " + (page - 1) * size)
                .params(params));
        long total = jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE" + where).params(params)
                .query(Long.class).single();
        for (Map<String, Object> r : rows) {
            includeLedger(r);
        }
        return PageResult.of(rows, page, size, total);
    }

    /** product {id, code, name}, variant {id, name}, location {id, code, name}, batch {id, batchNumber, expiryDate}. */
    void includeLedger(Map<String, Object> r) {
        r.put("product", SqlRows.one(jdbc.sql("SELECT id, code, name FROM \"Product\" WHERE id = ?")
                .param(r.get("productId"))));
        r.put("variant", r.get("variantId") == null ? null : SqlRows.one(jdbc.sql("SELECT id, name FROM"
                + " \"ProductVariant\" WHERE id = ?").param(r.get("variantId"))));
        r.put("location", locationRef(r.get("locationId")));
        r.put("batch", r.get("batchId") == null ? null : SqlRows.one(jdbc.sql("SELECT id, \"batchNumber\","
                + " \"expiryDate\" FROM \"InventoryBatch\" WHERE id = ?").param(r.get("batchId"))));
    }

    // ------------------------------------------------------------------ report feeds

    /** Active batches expiring within {@code days} (default 30), FEFO order. */
    public List<Map<String, Object>> getExpiringBatches(String days, String locationId) {
        return expiringBatches(days, scope.resolve(locationId));
    }

    private List<Map<String, Object>> expiringBatches(String days, String locationId) {
        double d = JsNumbers.number(days);
        int n = (int) Math.max(1, Double.isNaN(d) || d == 0 ? 30 : d);
        Instant horizon = Instant.now().plusSeconds(86_400L * n);
        List<Object> params = new ArrayList<>(List.of(org(), SqlRows.ts(horizon)));
        String loc = "";
        if (notBlank(locationId)) {
            loc = " AND \"locationId\" = ?";
            params.add(locationId);
        }
        List<Map<String, Object>> batches = SqlRows.list(InventoryBatch.class, jdbc.sql("SELECT * FROM \"InventoryBatch\""
                + " WHERE \"organizationId\" = ? AND \"isActive\" = true AND quantity > 0 AND \"expiryDate\" IS NOT NULL"
                + " AND \"expiryDate\" <= ?" + loc + " ORDER BY \"expiryDate\" ASC").params(params));
        long now = System.currentTimeMillis();
        // One query for the products (+ unit) and one for the locations of every batch (was two per batch).
        Map<String, Map<String, Object>> products = byId(SqlRows.list(jdbc.sql("SELECT p.id, p.code, p.name,"
                + " u.code AS uom FROM \"Product\" p LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\""
                + " WHERE p.id IN (:ids) AND p.\"organizationId\" = :org").param("ids", idsOf(batches, "productId"))
                .param("org", org())), batches.isEmpty());
        Map<String, Map<String, Object>> locations = locationRefs(idsOf(batches, "locationId"));
        for (Map<String, Object> b : batches) {
            Map<String, Object> found = products.get(str(b, "productId"));
            Map<String, Object> p = found == null ? null : new LinkedHashMap<>(found);
            if (p != null) {
                Object uom = p.remove("uom");
                p.put("uom", uom == null ? null : Obj.of("code", uom));
            }
            b.put("product", p);
            Map<String, Object> location = locations.get(str(b, "locationId"));
            b.put("location", location == null ? null : new LinkedHashMap<>(location));
            Instant exp = (Instant) b.get("expiryDate");
            b.put("daysToExpiry", exp == null ? null : (long) Math.ceil((exp.toEpochMilli() - now) / 86_400_000.0));
            b.put("expired", exp != null && exp.toEpochMilli() < now);
        }
        return batches;
    }

    /** Par-based reorder suggestions (suggested qty in purchase units). */
    public List<Map<String, Object>> getReorderSuggestions(String locationId) {
        return reorderSuggestions(scope.resolve(locationId));
    }

    /**
     * Par lines. Phase I2 (audit V-10): a product with variants gets one line per active variant, each compared with
     * the product's par on its own stock (variants are held and bought separately; a surplus of one never hides the
     * shortage of another). Audit U-9: a product whose purchase unit cannot be converted no longer fails the whole
     * report — its line keeps the shortfall in the stock unit and says why in {@code unitError}.
     */
    private List<Map<String, Object>> reorderSuggestions(String locationId) {
        List<Map<String, Object>> products = SqlRows.list(jdbc.sql("SELECT id, code, name, \"minQuantity\", \"reorderQty\","
                + " \"uomConversion\", \"uomId\", \"purchaseUomId\", \"supplierId\", \"hasVariants\" FROM \"Product\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"trackInventory\" = true"
                + " AND (\"minQuantity\" > 0 OR \"reorderQty\" > 0)").param(org()));
        // On-hand per product and variant in one grouped query (was one per product).
        List<Object> params = new ArrayList<>(List.of(org()));
        String loc = "";
        if (notBlank(locationId)) {
            loc = " AND \"locationId\" = ?";
            params.add(locationId);
        }
        Map<String, BigDecimal> onHandBy = new HashMap<>();
        for (Map<String, Object> g : jdbc.sql("SELECT \"productId\", \"variantKey\", COALESCE(SUM(quantity), 0) AS q"
                + " FROM \"StockItem\" WHERE \"organizationId\" = ?" + loc + " GROUP BY \"productId\", \"variantKey\"")
                .params(params).query().listOfRows()) {
            BigDecimal q = Money.dec(g.get("q"));
            onHandBy.merge((String) g.get("productId"), q, BigDecimal::add);
            onHandBy.merge(g.get("productId") + "::" + g.get("variantKey"), q, BigDecimal::add);
        }
        List<Object> parents = products.stream().filter(p -> Boolean.TRUE.equals(p.get("hasVariants")))
                .map(p -> p.get("id")).toList();
        Map<Object, List<Map<String, Object>>> variantsBy = new HashMap<>();
        if (!parents.isEmpty()) {
            for (Map<String, Object> v : SqlRows.list(jdbc.sql("SELECT id, \"productId\", name FROM \"ProductVariant\""
                    + " WHERE \"productId\" IN (:ids) AND \"organizationId\" = :org AND \"isActive\" AND \"deletedAt\" IS NULL"
                    + " ORDER BY name").param("ids", parents).param("org", org()))) {
                variantsBy.computeIfAbsent(v.get("productId"), k -> new ArrayList<>()).add(v);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> belowPar = new ArrayList<>();
        for (Map<String, Object> p : products) {
            List<Map<String, Object>> cells = Boolean.TRUE.equals(p.get("hasVariants"))
                    ? variantsBy.getOrDefault(p.get("id"), List.of()) : java.util.Collections.singletonList(null);
            for (Map<String, Object> variant : cells) {
                BigDecimal onHand = variant == null ? onHandBy.getOrDefault(str(p, "id"), Money.ZERO)
                        : onHandBy.getOrDefault(p.get("id") + "::" + variant.get("id"), Money.ZERO);
                BigDecimal par = p.get("minQuantity") == null ? Money.ZERO : dec(p, "minQuantity");
                if (onHand.compareTo(par) > 0) {
                    continue;
                }
                BigDecimal shortfall = par.subtract(onHand);
                BigDecimal suggest = Money.ZERO;
                String unitError = null;
                BigDecimal reorder = (BigDecimal) p.get("reorderQty");
                if (reorder != null && reorder.signum() > 0) {
                    suggest = reorder;
                } else if (shortfall.signum() > 0) {
                    String purchaseUom = str(p, "purchaseUomId");
                    if (purchaseUom != null && !purchaseUom.equals(str(p, "uomId"))) {
                        BigDecimal converted = uomConversion.tryFromBase(shortfall, purchaseUom, str(p, "id"),
                                str(p, "uomId"), true);
                        if (converted != null) {
                            suggest = converted.setScale(2, RoundingMode.CEILING);
                        } else {
                            suggest = shortfall.setScale(2, RoundingMode.CEILING);
                            unitError = "The purchase unit cannot be converted to the stock unit — quantity shown in the"
                                    + " stock unit";
                        }
                    } else {
                        BigDecimal conv = p.get("uomConversion") != null && dec(p, "uomConversion").signum() > 0
                                ? dec(p, "uomConversion") : BigDecimal.ONE;
                        suggest = Money.div(shortfall, conv).setScale(2, RoundingMode.CEILING);
                    }
                }
                belowPar.add(p);
                Map<String, Object> line = Obj.of("productId", p.get("id"), "code", p.get("code"), "name", p.get("name"),
                        "onHand", JsNumbers.js(onHand), "par", JsNumbers.js(par), "belowPar", true, "suggestedOrderQty",
                        JsNumbers.js(suggest), "supplier", null);
                if (variant != null) {
                    line.put("variantId", variant.get("id"));
                    line.put("variantName", variant.get("name"));
                }
                if (unitError != null) {
                    line.put("unitError", unitError);
                }
                out.add(line);
            }
        }
        Map<String, Map<String, Object>> suppliers = byId(SqlRows.list(jdbc.sql("SELECT id, name FROM \"Partner\""
                + " WHERE id IN (:ids) AND \"organizationId\" = :org").param("ids", idsOf(belowPar, "supplierId"))
                .param("org", org())), belowPar.isEmpty());
        for (int i = 0; i < out.size(); i++) {
            Object supplierId = belowPar.get(i).get("supplierId");
            Map<String, Object> supplier = supplierId == null ? null : suppliers.get(supplierId.toString());
            out.get(i).put("supplier", supplier == null ? null : new LinkedHashMap<>(supplier));
        }
        return out;
    }

    /** Ledger grouped by product and move type over a window. */
    public List<Map<String, Object>> getMovementSummary(String start, String end, String locationId) {
        return movementSummary(start, end, scope.resolve(locationId));
    }

    private List<Map<String, Object>> movementSummary(String start, String end, String locationId) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ?");
        if (notBlank(locationId)) {
            where.append(" AND \"locationId\" = ?");
            params.add(locationId);
        }
        if (notBlank(start)) {
            where.append(" AND \"createdAt\" >= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(start)));
        }
        if (notBlank(end)) {
            where.append(" AND \"createdAt\" <= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(end)));
        }
        List<Map<String, Object>> grouped = jdbc.sql("SELECT \"productId\", type::text AS type, SUM(\"quantityChange\") AS q,"
                + " SUM(\"totalValue\") AS v FROM \"InventoryLedger\" WHERE" + where + " GROUP BY \"productId\", type")
                .params(params).query().listOfRows();
        Set<String> ids = new LinkedHashSet<>();
        grouped.forEach(g -> ids.add((String) g.get("productId")));
        Map<String, Map<String, Object>> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> p : jdbc.sql("SELECT id, code, name FROM \"Product\" WHERE id IN (:ids)"
                    + " AND \"organizationId\" = :org AND \"deletedAt\" IS NULL").param("ids", List.copyOf(ids))
                    .param("org", org()).query().listOfRows()) {
                names.put((String) p.get("id"), p);
            }
        }
        Map<String, Map<String, Object>> byProduct = new LinkedHashMap<>();
        Map<String, BigDecimal[]> nets = new HashMap<>();
        for (Map<String, Object> g : grouped) {
            String pid = (String) g.get("productId");
            Map<String, Object> row = byProduct.computeIfAbsent(pid, k -> Obj.of("product",
                    names.containsKey(k) ? names.get(k) : Obj.of("id", k), "byType", new LinkedHashMap<String, Object>(),
                    "netQty", 0, "netValue", 0));
            BigDecimal qty = Money.dec(g.get("q"));
            BigDecimal val = Money.dec(g.get("v"));
            @SuppressWarnings("unchecked")
            Map<String, Object> byType = (Map<String, Object>) row.get("byType");
            byType.put((String) g.get("type"), Obj.of("qty", JsNumbers.js(qty), "value", JsNumbers.js(val)));
            BigDecimal[] n = nets.computeIfAbsent(pid, k -> new BigDecimal[] {Money.ZERO, Money.ZERO});
            n[0] = n[0].add(qty);
            n[1] = n[1].add(val);
            row.put("netQty", JsNumbers.js(n[0]));
            row.put("netValue", JsNumbers.js(n[1]));
        }
        return new ArrayList<>(byProduct.values());
    }

    // ------------------------------------------------------------------ integrity

    private record Cell(String productId, String variantKey, String locationId) {}

    /** Cached on-hand vs Σ ledger vs Σ batches per (product, variant, location) cell. */
    public Map<String, Object> getStockReconciliation(String locationId, Double toleranceIn, boolean includeMatched) {
        return stockReconciliation(scope.resolve(locationId), toleranceIn, includeMatched);
    }

    /** Most cells one reconciliation returns (h-01 F10); {@code summary.truncated} says when there were more. */
    private static final int RECON_ROWS = 5000;

    /**
     * The cell comparison shared by the stock reconciliation report and the integrity checks (R0): a {@code WITH}
     * clause ending in CTE {@code c} with one row per (product, variant, location) cell found in {@code StockItem}, the
     * stock ledger or the lots: {@code pid, vk, lid, quant, cached} (Σ StockItem.quantity), {@code ledger} (Σ
     * quantityChange), {@code batches} (Σ lot quantity), {@code code, name, lots} (batch-tracked) and {@code lname}.
     * Binds {@code :org}, and {@code :loc} when {@code byLocation}.
     */
    static String reconciliationCells(boolean byLocation) {
        String loc = byLocation ? " AND \"locationId\" = :loc" : "";
        return "WITH si AS (SELECT \"productId\" AS pid, \"variantKey\" AS vk, \"locationId\" AS lid,"
                + " SUM(quantity) AS cached FROM \"StockItem\" WHERE \"organizationId\" = :org" + loc
                + " GROUP BY 1, 2, 3),"
                + " lg AS (SELECT \"productId\" AS pid, COALESCE(\"variantId\", '') AS vk, \"locationId\" AS lid,"
                + " SUM(\"quantityChange\") AS ledger FROM \"InventoryLedger\" WHERE \"organizationId\" = :org" + loc
                + " GROUP BY 1, 2, 3),"
                + " bt AS (SELECT \"productId\" AS pid, COALESCE(\"variantId\", '') AS vk, \"locationId\" AS lid,"
                + " SUM(quantity) AS batches FROM \"InventoryBatch\" WHERE \"organizationId\" = :org" + loc
                + " GROUP BY 1, 2, 3),"
                + " k AS (SELECT pid, vk, lid FROM si UNION SELECT pid, vk, lid FROM lg UNION SELECT pid, vk, lid FROM bt),"
                + " c AS (SELECT k.pid, k.vk, k.lid, si.pid IS NOT NULL AS quant, COALESCE(si.cached, 0) AS cached,"
                + " COALESCE(lg.ledger, 0) AS ledger, COALESCE(bt.batches, 0) AS batches, p.code, p.name,"
                + " COALESCE(p.\"batchTracking\", false) AS lots, l.name AS lname FROM k"
                + " LEFT JOIN si ON si.pid = k.pid AND si.vk = k.vk AND si.lid = k.lid"
                + " LEFT JOIN lg ON lg.pid = k.pid AND lg.vk = k.vk AND lg.lid = k.lid"
                + " LEFT JOIN bt ON bt.pid = k.pid AND bt.vk = k.vk AND bt.lid = k.lid"
                + " LEFT JOIN \"Product\" p ON p.id = k.pid AND p.\"organizationId\" = :org"
                + " AND (si.pid IS NOT NULL OR p.\"deletedAt\" IS NULL)"
                + " LEFT JOIN \"InventoryLocation\" l ON l.id = k.lid)";
    }

    /**
     * h-01 F10: the cells are compared in the database and only the cells shown come back (drifted ones, or every cell
     * with {@code includeMatched}), at most {@value #RECON_ROWS}. A lot-tracked quant below zero has no lots to match,
     * so its lots are compared with zero rather than with the negative quantity (negative stock has its own report).
     */
    private Map<String, Object> stockReconciliation(String locationId, Double toleranceIn, boolean includeMatched) {
        BigDecimal tolerance = new BigDecimal(JsNumbers.toJsString(toleranceIn != null ? toleranceIn : 0.000001));
        String loc = notBlank(locationId) ? " AND \"locationId\" = :loc" : "";
        var spec = jdbc.sql(reconciliationCells(!loc.isEmpty()) + ","
                        + " e AS (SELECT c.*, abs(c.cached - c.ledger) > :tol"
                        + " OR (c.lots AND abs(GREATEST(c.cached, 0) - c.batches) > :tol) AS drifted FROM c)"
                        // The first cell always comes back, so the counts arrive even when nothing drifted.
                        + " SELECT x.* FROM (SELECT e.*, count(*) OVER () AS cells,"
                        + " count(*) FILTER (WHERE e.drifted) OVER () AS drifted_cells,"
                        + " row_number() OVER (ORDER BY NOT e.quant, e.name, e.pid, e.lid, e.vk) AS rn FROM e) x"
                        + " WHERE x.drifted OR :all OR x.rn = 1 ORDER BY x.rn LIMIT " + (RECON_ROWS + 1))
                .param("org", org()).param("tol", tolerance).param("all", includeMatched);
        if (!loc.isEmpty()) {
            spec = spec.param("loc", locationId);
        }
        List<Map<String, Object>> found = SqlRows.list(spec);
        long cells = found.isEmpty() ? 0 : ((Number) found.getFirst().get("cells")).longValue();
        long drifted = found.isEmpty() ? 0 : ((Number) found.getFirst().get("drifted_cells")).longValue();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : found) {
            if (rows.size() == RECON_ROWS) {
                break;
            }
            pushRow(rows, new Cell(str(r, "pid"), str(r, "vk"), str(r, "lid")), str(r, "code"), str(r, "name"),
                    str(r, "lname"), dec(r, "cached"), dec(r, "ledger"),
                    Boolean.TRUE.equals(r.get("lots")) ? dec(r, "batches") : null, tolerance, includeMatched);
        }
        return Obj.of("summary", Obj.of("cellsChecked", cells, "driftedCells", drifted, "clean", drifted == 0,
                "tolerance", JsNumbers.dec(tolerance), "truncated", (includeMatched ? cells : drifted) > rows.size()),
                "rows", rows);
    }

    private static void pushRow(List<Map<String, Object>> rows, Cell key, String code, String name, String locationName,
            BigDecimal cached, BigDecimal ledger, BigDecimal batches, BigDecimal tolerance, boolean includeMatched) {
        BigDecimal ledgerDrift = cached.subtract(ledger);
        BigDecimal batchDrift = batches != null ? cached.max(BigDecimal.ZERO).subtract(batches) : null;
        boolean drifted = ledgerDrift.abs().compareTo(tolerance) > 0
                || (batchDrift != null && batchDrift.abs().compareTo(tolerance) > 0);
        if (!drifted && !includeMatched) {
            return;
        }
        rows.add(Obj.of("productId", key.productId(), "variantKey", key.variantKey(), "locationId", key.locationId(),
                "code", code, "name", name, "locationName", locationName, "cached", JsNumbers.dec(cached), "ledger",
                JsNumbers.dec(ledger), "batches", batches != null ? JsNumbers.dec(batches) : null, "ledgerDrift",
                JsNumbers.dec(ledgerDrift), "batchDrift", batchDrift != null ? JsNumbers.dec(batchDrift) : null,
                "drifted", drifted));
    }

    /** Quants at negative on-hand with the estimated valuation exposure. */
    public Map<String, Object> getNegativeStock(String locationId) {
        return negativeStock(scope.resolve(locationId));
    }

    private Map<String, Object> negativeStock(String locationId) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String loc = "";
        if (notBlank(locationId)) {
            loc = " AND si.\"locationId\" = ?";
            params.add(locationId);
        }
        List<Map<String, Object>> items = SqlRows.list(jdbc.sql("SELECT si.\"productId\", si.\"variantKey\","
                + " si.\"locationId\", si.quantity, si.\"runningAverageCost\", si.\"updatedAt\", p.code, p.name,"
                + " p.\"costPrice\", p.\"costingMethod\"::text AS \"costingMethod\", l.name AS lname FROM \"StockItem\" si"
                + " LEFT JOIN \"Product\" p ON p.id = si.\"productId\" LEFT JOIN \"InventoryLocation\" l"
                + " ON l.id = si.\"locationId\" WHERE si.\"organizationId\" = ? AND si.quantity < 0" + loc
                + " ORDER BY si.quantity ASC").params(params));
        BigDecimal total = Money.ZERO;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> it : items) {
            BigDecimal shortQty = dec(it, "quantity").abs();
            BigDecimal avg = dec(it, "runningAverageCost");
            BigDecimal unit = avg.signum() > 0 ? avg : dec(it, "costPrice");
            BigDecimal exposure = shortQty.multiply(unit);
            total = total.add(exposure);
            rows.add(Obj.of("productId", it.get("productId"), "variantKey", it.get("variantKey"), "locationId",
                    it.get("locationId"), "code", it.get("code"), "name", it.get("name"), "locationName", it.get("lname"),
                    "costingMethod", it.get("costingMethod"), "quantity", JsNumbers.dec(dec(it, "quantity")), "shortBy",
                    JsNumbers.dec(shortQty), "unitCost", JsNumbers.dec(unit), "valuationExposure",
                    JsNumbers.dec(exposure), "zeroCostBasis", unit.signum() <= 0, "lastMovedAt", it.get("updatedAt")));
        }
        return Obj.of("summary", Obj.of("cells", rows.size(), "clean", rows.isEmpty(), "totalValuationExposure",
                JsNumbers.dec(total), "zeroCostBasisCells", rows.stream().filter(r -> Boolean.TRUE.equals(
                        r.get("zeroCostBasis"))).count()), "rows", rows);
    }

    static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }
}
