package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.WarehouseStore.NOW;
import static com.inventoryerp.inventory.application.WarehouseStore.id;
import static com.inventoryerp.inventory.application.WarehouseStore.org;
import static com.inventoryerp.inventory.application.WarehouseStore.user;

import com.inventoryerp.inventory.application.WarehouseStore.Settings;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warehouse master data (roadmap 2.1, ADR-S026): per-location settings, the zone / aisle / rack / bin tree, putaway
 * rules, and the bin-stock views (binned, staged, unbinned per product).
 */
@Service
public class WarehouseSetupService {

    /** Settings fields; {@code null} keeps the stored (or default) value. */
    public record SettingsInput(Boolean enabled, Integer outboundSteps, BigDecimal abcAPercent, BigDecimal abcBPercent,
            Integer abcLookbackDays, Integer countDaysA, Integer countDaysB, Integer countDaysC) {}

    /** Bin fields; on update {@code null} keeps the stored value. */
    public record BinInput(String locationId, String parentId, String code, String name, String kind, String usage,
            Integer pickSequence, BigDecimal maxQty, Boolean mixedProducts, Boolean isActive) {}

    public record RuleInput(String locationId, String productId, String categoryId, String targetBinId,
            String targetZoneId, Integer priority, Boolean isActive) {}

    private final JdbcClient jdbc;
    private final WarehouseStore store;
    private final AuditService audit;

    public WarehouseSetupService(WarehouseStore store, AuditService audit) {
        this.jdbc = store.jdbc();
        this.store = store;
        this.audit = audit;
    }

    private static Map<String, Object> settingsView(Settings s) {
        return Obj.of("locationId", s.locationId(), "enabled", s.enabled(), "outboundSteps", s.outboundSteps(),
                "abcAPercent", s.abcAPercent(), "abcBPercent", s.abcBPercent(), "abcLookbackDays", s.abcLookbackDays(),
                "countDaysA", s.countDaysA(), "countDaysB", s.countDaysB(), "countDaysC", s.countDaysC(),
                "configured", s.stored());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> settings(String locationId) {
        store.location(locationId);
        return settingsView(store.settings(locationId));
    }

    /** Create or change the location's warehouse settings. */
    @Transactional
    public Map<String, Object> saveSettings(String locationId, SettingsInput in) {
        store.location(locationId);
        Settings cur = store.settings(locationId);
        boolean enabled = in.enabled() != null ? in.enabled() : cur.stored() ? cur.enabled() : true;
        int steps = in.outboundSteps() != null ? in.outboundSteps() : cur.outboundSteps();
        BigDecimal a = in.abcAPercent() != null ? in.abcAPercent() : cur.abcAPercent();
        BigDecimal b = in.abcBPercent() != null ? in.abcBPercent() : cur.abcBPercent();
        if (a.compareTo(b) >= 0) {
            throw ApiException.badRequest("abcAPercent must be lower than abcBPercent");
        }
        int lookback = in.abcLookbackDays() != null ? in.abcLookbackDays() : cur.abcLookbackDays();
        int da = in.countDaysA() != null ? in.countDaysA() : cur.countDaysA();
        int db = in.countDaysB() != null ? in.countDaysB() : cur.countDaysB();
        int dc = in.countDaysC() != null ? in.countDaysC() : cur.countDaysC();
        jdbc.sql("INSERT INTO \"WarehouseSettings\" (id, \"organizationId\", \"locationId\", enabled, \"outboundSteps\","
                        + " \"abcAPercent\", \"abcBPercent\", \"abcLookbackDays\", \"countDaysA\", \"countDaysB\", \"countDaysC\","
                        + " \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ")"
                        + " ON CONFLICT (\"locationId\") DO UPDATE SET enabled = EXCLUDED.enabled,"
                        + " \"outboundSteps\" = EXCLUDED.\"outboundSteps\", \"abcAPercent\" = EXCLUDED.\"abcAPercent\","
                        + " \"abcBPercent\" = EXCLUDED.\"abcBPercent\", \"abcLookbackDays\" = EXCLUDED.\"abcLookbackDays\","
                        + " \"countDaysA\" = EXCLUDED.\"countDaysA\", \"countDaysB\" = EXCLUDED.\"countDaysB\","
                        + " \"countDaysC\" = EXCLUDED.\"countDaysC\", \"updatedAt\" = " + NOW)
                .params(id(), org(), locationId, enabled, steps, a, b, lookback, da, db, dc).update();
        audit.recordInTx(AuditInput.of("WarehouseSettings", locationId, "update", settingsView(cur), Obj.of("enabled",
                enabled, "outboundSteps", steps)));
        return settingsView(store.settings(locationId));
    }

    // ---------------------------------------------------------------- bins

    private Map<String, Object> binView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("quantity", store.binQty(SqlRows.str(row, "id")));
        return out;
    }

    /** The bin tree of a location as a flat list (parents before children), with the quantity each bin holds. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> bins(String locationId, boolean includeInactive) {
        store.location(locationId);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("WITH RECURSIVE tree AS ("
                + " SELECT b.*, b.code::text AS path, 0 AS depth FROM \"WarehouseBin\" b"
                + " WHERE b.\"organizationId\" = ? AND b.\"locationId\" = ? AND b.\"parentId\" IS NULL"
                + " UNION ALL SELECT c.*, t.path || '/' || c.code, t.depth + 1 FROM \"WarehouseBin\" c"
                + " JOIN tree t ON c.\"parentId\" = t.id)"
                + " SELECT t.*, coalesce(s.qty, 0) AS quantity, coalesce(s.products, 0) AS products FROM tree t"
                + " LEFT JOIN (SELECT \"binId\", sum(quantity) AS qty, count(DISTINCT \"productId\") AS products"
                + " FROM \"WarehouseBinStock\" GROUP BY \"binId\") s ON s.\"binId\" = t.id"
                + (includeInactive ? "" : " WHERE t.\"isActive\" = true") + " ORDER BY t.path")
                .params(org(), locationId));
        return rows;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> bin(String id) {
        Map<String, Object> bin = store.bin(id);
        store.location(SqlRows.str(bin, "locationId"));
        Map<String, Object> out = binView(bin);
        out.put("stock", binStockRows(null, id, null));
        return out;
    }

    @Transactional
    public Map<String, Object> createBin(BinInput in) {
        store.location(in.locationId());
        if (in.code() == null || in.code().isBlank()) {
            throw ApiException.badRequest("code should not be empty");
        }
        String kind = in.kind() == null ? "bin" : in.kind();
        if (in.parentId() != null) {
            Map<String, Object> parent = store.bin(in.parentId());
            if (!in.locationId().equals(parent.get("locationId"))) {
                throw ApiException.badRequest("Parent bin is in another location");
            }
            if ("bin".equals(parent.get("kind"))) {
                throw ApiException.badRequest("A bin cannot contain other bins");
            }
        }
        String id = id();
        try {
            jdbc.sql("INSERT INTO \"WarehouseBin\" (id, \"organizationId\", \"locationId\", \"parentId\", code, name, kind,"
                            + " usage, \"pickSequence\", \"maxQty\", \"mixedProducts\", \"isActive\", \"createdBy\", \"createdAt\","
                            + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ")")
                    .params(id, org(), in.locationId(), in.parentId(), in.code().trim(), in.name(), kind,
                            in.usage() == null ? "storage" : in.usage(), in.pickSequence() == null ? 0 : in.pickSequence(),
                            in.maxQty(), in.mixedProducts() == null || in.mixedProducts(),
                            in.isActive() == null || in.isActive(), user())
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Bin code " + in.code().trim() + " already exists in this location");
        }
        audit.recordInTx(AuditInput.of("WarehouseBin", id, "create", null, Obj.of("code", in.code(), "kind", kind)));
        return binView(store.bin(id));
    }

    @Transactional
    public Map<String, Object> updateBin(String id, BinInput in) {
        Map<String, Object> bin = store.bin(id);
        String locationId = SqlRows.str(bin, "locationId");
        store.location(locationId);
        String parentId = in.parentId() != null ? (in.parentId().isEmpty() ? null : in.parentId())
                : SqlRows.str(bin, "parentId");
        if (parentId != null) {
            Map<String, Object> parent = store.bin(parentId);
            if (!locationId.equals(parent.get("locationId")) || "bin".equals(parent.get("kind"))) {
                throw ApiException.badRequest("A bin cannot contain other bins");
            }
            long cycle = jdbc.sql("WITH RECURSIVE up AS (SELECT id, \"parentId\" FROM \"WarehouseBin\" WHERE id = ?"
                    + " UNION ALL SELECT b.id, b.\"parentId\" FROM \"WarehouseBin\" b JOIN up ON b.id = up.\"parentId\")"
                    + " SELECT count(*) FROM up WHERE id = ?").params(parentId, id).query(Long.class).single();
            if (cycle > 0) {
                throw ApiException.badRequest("A bin cannot be moved under itself");
            }
        }
        String kind = in.kind() != null ? in.kind() : SqlRows.str(bin, "kind");
        BigDecimal qty = store.binQty(id);
        if (!"bin".equals(kind) && qty.signum() > 0) {
            throw ApiException.badRequest("Bin " + bin.get("code") + " holds stock; empty it first");
        }
        if ("bin".equals(kind) && jdbc.sql("SELECT count(*) FROM \"WarehouseBin\" WHERE \"parentId\" = ?").param(id)
                .query(Long.class).single() > 0) {
            throw ApiException.badRequest("A bin cannot contain other bins");
        }
        BigDecimal max = in.maxQty() != null ? in.maxQty() : (BigDecimal) bin.get("maxQty");
        if (max != null && max.compareTo(qty) < 0) {
            throw ApiException.badRequest("maxQty is below the quantity the bin holds");
        }
        Boolean active = in.isActive() != null ? in.isActive() : (Boolean) bin.get("isActive");
        if (!Boolean.TRUE.equals(active) && qty.signum() > 0) {
            throw ApiException.badRequest("Bin " + bin.get("code") + " holds stock; empty it first");
        }
        try {
            jdbc.sql("UPDATE \"WarehouseBin\" SET \"parentId\" = ?, code = ?, name = ?, kind = ?, usage = ?,"
                            + " \"pickSequence\" = ?, \"maxQty\" = ?, \"mixedProducts\" = ?, \"isActive\" = ?, \"updatedAt\" = "
                            + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                    .params(parentId, in.code() != null ? in.code().trim() : bin.get("code"),
                            in.name() != null ? in.name() : bin.get("name"), kind,
                            in.usage() != null ? in.usage() : bin.get("usage"),
                            in.pickSequence() != null ? in.pickSequence() : bin.get("pickSequence"), max,
                            in.mixedProducts() != null ? in.mixedProducts() : bin.get("mixedProducts"), active, id, org())
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Bin code " + in.code() + " already exists in this location");
        }
        audit.recordInTx(AuditInput.of("WarehouseBin", id, "update", Obj.of("code", bin.get("code")), Obj.of("code",
                in.code(), "isActive", active)));
        return binView(store.bin(id));
    }

    /** Delete an empty bin with no children and no history; otherwise deactivate it instead. */
    @Transactional
    public Map<String, Object> deleteBin(String id) {
        Map<String, Object> bin = store.bin(id);
        store.location(SqlRows.str(bin, "locationId"));
        if (store.binQty(id).signum() > 0) {
            throw ApiException.badRequest("Bin " + bin.get("code") + " holds stock; empty it first");
        }
        if (jdbc.sql("SELECT count(*) FROM \"WarehouseBin\" WHERE \"parentId\" = ?").param(id).query(Long.class)
                .single() > 0) {
            throw ApiException.badRequest("Remove or move the bins inside " + bin.get("code") + " first");
        }
        long used = jdbc.sql("SELECT count(*) FROM \"WarehouseTask\" WHERE \"fromBinId\" = ? OR \"toBinId\" = ?")
                .params(id, id).query(Long.class).single();
        if (used > 0) {
            jdbc.sql("UPDATE \"WarehouseBin\" SET \"isActive\" = false, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .param(id).update();
            audit.recordInTx(AuditInput.of("WarehouseBin", id, "update", null, Obj.of("isActive", false)));
            return Obj.of("id", id, "deleted", false, "deactivated", true);
        }
        jdbc.sql("DELETE FROM \"WarehouseBinStock\" WHERE \"binId\" = ?").param(id).update();
        jdbc.sql("DELETE FROM \"WarehouseBin\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()).update();
        audit.recordInTx(AuditInput.of("WarehouseBin", id, "delete", Obj.of("code", bin.get("code")), null));
        return Obj.of("id", id, "deleted", true, "deactivated", false);
    }

    // ---------------------------------------------------------------- putaway rules

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rules(String locationId) {
        store.location(locationId);
        return SqlRows.list(jdbc.sql("SELECT r.*, p.name AS \"productName\", c.name AS \"categoryName\","
                + " tb.code AS \"targetBinCode\", tz.code AS \"targetZoneCode\" FROM \"WarehousePutawayRule\" r"
                + " LEFT JOIN \"Product\" p ON p.id = r.\"productId\" LEFT JOIN \"ProductCategory\" c ON c.id = r.\"categoryId\""
                + " LEFT JOIN \"WarehouseBin\" tb ON tb.id = r.\"targetBinId\""
                + " LEFT JOIN \"WarehouseBin\" tz ON tz.id = r.\"targetZoneId\""
                + " WHERE r.\"organizationId\" = ? AND r.\"locationId\" = ?"
                + " ORDER BY (r.\"productId\" IS NULL), (r.\"categoryId\" IS NULL), r.priority, r.\"createdAt\"")
                .params(org(), locationId));
    }

    private void checkRuleTargets(String locationId, String productId, String categoryId,
            String targetBinId, String targetZoneId) {
        if (productId != null && categoryId != null) {
            throw ApiException.badRequest("A rule applies to a product or a category, not both");
        }
        if (targetBinId == null && targetZoneId == null) {
            throw ApiException.badRequest("A rule needs a targetBinId or a targetZoneId");
        }
        if (productId != null) {
            store.product(productId);
        }
        if (categoryId != null && jdbc.sql("SELECT count(*) FROM \"ProductCategory\" WHERE id = ? AND \"organizationId\""
                + " = ? AND \"deletedAt\" IS NULL").params(categoryId, org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Category not found");
        }
        if (targetBinId != null) {
            store.stockBin(targetBinId, locationId);
        }
        if (targetZoneId != null) {
            Map<String, Object> zone = store.bin(targetZoneId);
            if (!locationId.equals(zone.get("locationId"))) {
                throw ApiException.badRequest("Bin " + zone.get("code") + " is not in this location");
            }
        }
    }

    @Transactional
    public Map<String, Object> createRule(RuleInput in) {
        store.location(in.locationId());
        checkRuleTargets(in.locationId(), in.productId(), in.categoryId(), in.targetBinId(), in.targetZoneId());
        String id = id();
        jdbc.sql("INSERT INTO \"WarehousePutawayRule\" (id, \"organizationId\", \"locationId\", \"productId\", \"categoryId\","
                        + " \"targetBinId\", \"targetZoneId\", priority, \"isActive\", \"createdAt\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ")")
                .params(id, org(), in.locationId(), in.productId(), in.categoryId(), in.targetBinId(), in.targetZoneId(),
                        in.priority() == null ? 10 : in.priority(), in.isActive() == null || in.isActive())
                .update();
        audit.recordInTx(AuditInput.of("WarehousePutawayRule", id, "create"));
        return rule(id);
    }

    private Map<String, Object> rule(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"WarehousePutawayRule\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Putaway rule not found");
        }
        return r;
    }

    @Transactional
    public Map<String, Object> updateRule(String id, RuleInput in) {
        Map<String, Object> r = rule(id);
        String locationId = SqlRows.str(r, "locationId");
        store.location(locationId);
        String productId = in.productId() != null ? emptyToNull(in.productId()) : SqlRows.str(r, "productId");
        String categoryId = in.categoryId() != null ? emptyToNull(in.categoryId()) : SqlRows.str(r, "categoryId");
        String binId = in.targetBinId() != null ? emptyToNull(in.targetBinId()) : SqlRows.str(r, "targetBinId");
        String zoneId = in.targetZoneId() != null ? emptyToNull(in.targetZoneId()) : SqlRows.str(r, "targetZoneId");
        checkRuleTargets(locationId, productId, categoryId, binId, zoneId);
        jdbc.sql("UPDATE \"WarehousePutawayRule\" SET \"productId\" = ?, \"categoryId\" = ?, \"targetBinId\" = ?,"
                        + " \"targetZoneId\" = ?, priority = ?, \"isActive\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(productId, categoryId, binId, zoneId, in.priority() != null ? in.priority() : r.get("priority"),
                        in.isActive() != null ? in.isActive() : r.get("isActive"), id)
                .update();
        audit.recordInTx(AuditInput.of("WarehousePutawayRule", id, "update"));
        return rule(id);
    }

    @Transactional
    public Map<String, Object> deleteRule(String id) {
        Map<String, Object> r = rule(id);
        store.location(SqlRows.str(r, "locationId"));
        jdbc.sql("DELETE FROM \"WarehousePutawayRule\" WHERE id = ? AND \"organizationId\" = ?").params(id, org())
                .update();
        audit.recordInTx(AuditInput.of("WarehousePutawayRule", id, "delete"));
        return Obj.of("id", id, "deleted", true);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    // ---------------------------------------------------------------- stock views

    private List<Map<String, Object>> binStockRows(String locationId, String binId, String productId) {
        StringBuilder sql = new StringBuilder("SELECT s.\"binId\", b.code AS \"binCode\", b.usage, s.\"productId\","
                + " p.name AS \"productName\", p.sku, s.\"variantKey\", s.\"batchKey\", ib.\"batchNumber\","
                + " ib.\"expiryDate\", s.quantity FROM \"WarehouseBinStock\" s JOIN \"WarehouseBin\" b ON b.id = s.\"binId\""
                + " JOIN \"Product\" p ON p.id = s.\"productId\""
                + " LEFT JOIN \"InventoryBatch\" ib ON ib.id = s.\"batchKey\" WHERE s.\"organizationId\" = :org");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("org", org());
        if (locationId != null) {
            sql.append(" AND s.\"locationId\" = :loc");
            params.put("loc", locationId);
        }
        if (binId != null) {
            sql.append(" AND s.\"binId\" = :bin");
            params.put("bin", binId);
        }
        if (productId != null) {
            sql.append(" AND s.\"productId\" = :prod");
            params.put("prod", productId);
        }
        sql.append(" ORDER BY b.\"pickSequence\", b.code, p.name LIMIT 2000");
        return SqlRows.list(jdbc.sql(sql.toString()).params(params));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> binStock(String locationId, String binId, String productId) {
        store.location(locationId);
        return binStockRows(locationId, binId, productId);
    }

    /**
     * Per product of a location: ledger quantity, binned, staged (picked, not shipped), in open putaway tasks, and
     * unbinned (waiting for putaway). Only products with stock or bin activity are listed.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> overview(String locationId) {
        store.location(locationId);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("WITH keys AS ("
                + " SELECT \"productId\", \"variantKey\" FROM \"StockItem\" WHERE \"organizationId\" = :org"
                + " AND \"locationId\" = :loc AND quantity <> 0"
                + " UNION SELECT \"productId\", \"variantKey\" FROM \"WarehouseBinStock\" WHERE \"organizationId\" = :org"
                + " AND \"locationId\" = :loc)"
                + " SELECT k.\"productId\", k.\"variantKey\", p.name AS \"productName\", p.sku,"
                + " coalesce((SELECT sum(quantity) FROM \"StockItem\" si WHERE si.\"organizationId\" = :org"
                + "   AND si.\"locationId\" = :loc AND si.\"productId\" = k.\"productId\""
                + "   AND si.\"variantKey\" = k.\"variantKey\"), 0) AS \"locationQty\","
                + " coalesce((SELECT sum(quantity) FROM \"WarehouseBinStock\" bs WHERE bs.\"organizationId\" = :org"
                + "   AND bs.\"locationId\" = :loc AND bs.\"productId\" = k.\"productId\""
                + "   AND bs.\"variantKey\" = k.\"variantKey\"), 0) AS \"binnedQty\","
                + " coalesce((SELECT sum(l.\"pickedQty\" - l.\"shippedQty\") FROM \"WarehousePickLine\" l"
                + "   JOIN \"WarehousePickOrder\" o ON o.id = l.\"pickOrderId\" WHERE o.\"organizationId\" = :org"
                + "   AND o.\"locationId\" = :loc AND o.status <> 'cancelled' AND l.\"productId\" = k.\"productId\""
                + "   AND l.\"variantKey\" = k.\"variantKey\"), 0) AS \"stagedQty\","
                + " coalesce((SELECT sum(quantity) FROM \"WarehouseTask\" t WHERE t.\"organizationId\" = :org"
                + "   AND t.\"locationId\" = :loc AND t.type = 'putaway' AND t.status = 'open'"
                + "   AND t.\"productId\" = k.\"productId\" AND t.\"variantKey\" = k.\"variantKey\"), 0) AS \"putawayQty\""
                + " FROM keys k JOIN \"Product\" p ON p.id = k.\"productId\" ORDER BY p.name")
                .param("org", org()).param("loc", locationId));
        BigDecimal totalUnbinned = BigDecimal.ZERO;
        List<Map<String, Object>> products = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal unbinned = SqlRows.dec(r, "locationQty").subtract(SqlRows.dec(r, "binnedQty"))
                    .subtract(SqlRows.dec(r, "stagedQty"));
            Map<String, Object> out = new LinkedHashMap<>(r);
            out.put("unbinnedQty", unbinned.max(BigDecimal.ZERO));
            out.put("mismatch", unbinned.signum() < 0);
            totalUnbinned = totalUnbinned.add(unbinned.max(BigDecimal.ZERO));
            products.add(out);
        }
        long bins = jdbc.sql("SELECT count(*) FROM \"WarehouseBin\" WHERE \"organizationId\" = ? AND \"locationId\" = ?"
                + " AND kind = 'bin' AND \"isActive\" = true").params(org(), locationId).query(Long.class).single();
        long openTasks = jdbc.sql("SELECT count(*) FROM \"WarehouseTask\" WHERE \"organizationId\" = ?"
                + " AND \"locationId\" = ? AND status = 'open'").params(org(), locationId).query(Long.class).single();
        long openPicks = jdbc.sql("SELECT count(*) FROM \"WarehousePickOrder\" WHERE \"organizationId\" = ?"
                + " AND \"locationId\" = ? AND status NOT IN ('shipped', 'cancelled')").params(org(), locationId)
                .query(Long.class).single();
        return Obj.of("locationId", locationId, "settings", settingsView(store.settings(locationId)), "bins", bins,
                "openTasks", openTasks, "openPickOrders", openPicks, "unbinnedQty", totalUnbinned, "products",
                products);
    }
}
