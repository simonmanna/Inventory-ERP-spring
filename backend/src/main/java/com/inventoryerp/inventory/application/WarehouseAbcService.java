package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.WarehouseStore.NOW;
import static com.inventoryerp.inventory.application.WarehouseStore.id;
import static com.inventoryerp.inventory.application.WarehouseStore.org;

import com.inventoryerp.inventory.application.AbcClassifier.Classified;
import com.inventoryerp.inventory.application.AbcClassifier.Item;
import com.inventoryerp.inventory.application.WarehouseStore.Settings;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ABC classification and cycle-count scheduling (roadmap 2.1, ADR-S026). Consumption value is the cost of stock that
 * left the location over the look-back window (issues, transfers out, production consumption, internal use). Each
 * class has a count interval; a product is due when it was never counted or its last submitted count is older than
 * the interval. Generating a cycle count opens a standard blind {@code cycle} count session scoped to the due products,
 * so counting, variance approval and posting stay in {@link InventoryCountService}.
 */
@Service
public class WarehouseAbcService {

    private final JdbcClient jdbc;
    private final WarehouseStore store;
    private final InventoryCountService counts;
    private final AuditService audit;

    public WarehouseAbcService(WarehouseStore store, InventoryCountService counts, AuditService audit) {
        this.jdbc = store.jdbc();
        this.store = store;
        this.counts = counts;
        this.audit = audit;
    }

    /** Recompute the classes of a location; count history ({@code lastCountedAt}) is kept. */
    @Transactional
    public Map<String, Object> classify(String locationId) {
        store.location(locationId);
        Settings s = store.settings(locationId);
        List<Item> items = jdbc.sql("WITH used AS (SELECT \"productId\", sum(abs(\"totalValue\")) AS v"
                        + " FROM \"InventoryLedger\" WHERE \"organizationId\" = :org AND \"locationId\" = :loc"
                        + " AND \"quantityChange\" < 0 AND type::text IN ('issue', 'transfer_out', 'production_consume',"
                        + " 'internal_use') AND \"createdAt\" >= (now() at time zone 'utc') - make_interval(days => :days)"
                        + " GROUP BY \"productId\"),"
                        + " stocked AS (SELECT DISTINCT \"productId\" FROM \"StockItem\" WHERE \"organizationId\" = :org"
                        + " AND \"locationId\" = :loc AND quantity > 0)"
                        + " SELECT k.\"productId\", coalesce(u.v, 0) AS v FROM (SELECT \"productId\" FROM used UNION"
                        + " SELECT \"productId\" FROM stocked) k LEFT JOIN used u ON u.\"productId\" = k.\"productId\""
                        + " JOIN \"Product\" p ON p.id = k.\"productId\" AND p.\"deletedAt\" IS NULL")
                .param("org", org()).param("loc", locationId).param("days", s.abcLookbackDays())
                .query((rs, n) -> new Item(rs.getString("productId"), rs.getBigDecimal("v"))).list();
        List<Classified> classes = AbcClassifier.classify(items, s.abcAPercent(), s.abcBPercent());
        for (Classified c : classes) {
            jdbc.sql("INSERT INTO \"WarehouseAbcClass\" (id, \"organizationId\", \"locationId\", \"productId\", \"abcClass\","
                            + " \"consumptionValue\", \"cumulativeShare\", \"classifiedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, " + NOW
                            + ") ON CONFLICT (\"locationId\", \"productId\") DO UPDATE SET \"abcClass\" = EXCLUDED.\"abcClass\","
                            + " \"consumptionValue\" = EXCLUDED.\"consumptionValue\","
                            + " \"cumulativeShare\" = EXCLUDED.\"cumulativeShare\", \"classifiedAt\" = EXCLUDED.\"classifiedAt\"")
                    .params(id(), org(), locationId, c.id(), String.valueOf(c.abcClass()), c.value(), c.cumulativeShare())
                    .update();
        }
        List<String> keep = classes.stream().map(Classified::id).toList();
        if (keep.isEmpty()) {
            jdbc.sql("DELETE FROM \"WarehouseAbcClass\" WHERE \"organizationId\" = ? AND \"locationId\" = ?")
                    .params(org(), locationId).update();
        } else {
            jdbc.sql("DELETE FROM \"WarehouseAbcClass\" WHERE \"organizationId\" = :org AND \"locationId\" = :loc"
                    + " AND \"productId\" NOT IN (:keep)").param("org", org()).param("loc", locationId)
                    .param("keep", keep).update();
        }
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("A", classes.stream().filter(c -> c.abcClass() == 'A').count());
        totals.put("B", classes.stream().filter(c -> c.abcClass() == 'B').count());
        totals.put("C", classes.stream().filter(c -> c.abcClass() == 'C').count());
        audit.recordInTx(AuditInput.of("WarehouseAbcClass", locationId, "update", null, totals));
        return Obj.of("locationId", locationId, "products", classes.size(), "classes", totals, "rows", rows(locationId));
    }

    /** Pull the latest submitted count of each classified product into {@code lastCountedAt}. */
    private void refreshCounted(String locationId) {
        jdbc.sql("UPDATE \"WarehouseAbcClass\" a SET \"lastCountedAt\" = c.at FROM (SELECT l.\"productId\","
                        + " max(s.\"submittedAt\") AS at FROM \"InventoryCountLine\" l JOIN \"InventoryCountSession\" s"
                        + " ON s.id = l.\"sessionId\" WHERE s.\"organizationId\" = ? AND s.\"locationId\" = ?"
                        + " AND s.status = 'submitted' AND s.\"deletedAt\" IS NULL AND l.\"countedQty\" IS NOT NULL"
                        + " GROUP BY l.\"productId\") c WHERE a.\"locationId\" = ? AND a.\"organizationId\" = ?"
                        + " AND a.\"productId\" = c.\"productId\" AND (a.\"lastCountedAt\" IS NULL OR a.\"lastCountedAt\" < c.at)")
                .params(org(), locationId, locationId, org()).update();
    }

    private List<Map<String, Object>> rows(String locationId) {
        Settings s = store.settings(locationId);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT a.*, p.name AS \"productName\", p.sku,"
                + " s.status::text AS \"lastSessionStatus\" FROM \"WarehouseAbcClass\" a"
                + " JOIN \"Product\" p ON p.id = a.\"productId\" LEFT JOIN \"InventoryCountSession\" s"
                + " ON s.id = a.\"lastSessionId\" WHERE a.\"organizationId\" = ? AND a.\"locationId\" = ?"
                + " ORDER BY a.\"abcClass\", a.\"consumptionValue\" DESC, p.name").params(org(), locationId));
        Instant now = Instant.now();
        for (Map<String, Object> r : rows) {
            char cls = SqlRows.str(r, "abcClass").charAt(0);
            Instant last = (Instant) r.get("lastCountedAt");
            Instant due = last == null ? null : last.plus(s.countDays(cls), ChronoUnit.DAYS);
            r.put("countEveryDays", s.countDays(cls));
            r.put("nextCountDue", due);
            r.put("due", due == null || !due.isAfter(now));
            r.put("scheduled", "draft".equals(r.get("lastSessionStatus")));
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> list(String locationId) {
        store.location(locationId);
        refreshCounted(locationId);
        List<Map<String, Object>> rows = rows(locationId);
        return Obj.of("locationId", locationId, "rows", rows, "due", rows.stream()
                .filter(r -> Boolean.TRUE.equals(r.get("due")) && !Boolean.TRUE.equals(r.get("scheduled"))).count());
    }

    /**
     * Open a blind cycle count for the products that are due (optionally only some classes, at most {@code limit}
     * products, A first). 400 when nothing is due or a cycle count is already open for the location.
     */
    @Transactional
    public Map<String, Object> generateCycleCount(String locationId, List<String> classes, Integer limit) {
        store.location(locationId);
        String open = jdbc.sql("SELECT \"countCode\" FROM \"InventoryCountSession\" WHERE \"organizationId\" = ?"
                + " AND \"locationId\" = ? AND \"countType\"::text = 'cycle' AND status = 'draft'"
                + " AND \"deletedAt\" IS NULL LIMIT 1").params(org(), locationId).query(String.class).optional()
                .orElse(null);
        if (open != null) {
            throw ApiException.badRequest("Cycle count " + open + " is still open for this location; submit or cancel it"
                    + " first");
        }
        refreshCounted(locationId);
        Set<String> wanted = classes == null || classes.isEmpty() ? Set.of("A", "B", "C") : Set.copyOf(classes);
        List<String> productIds = new ArrayList<>();
        for (Map<String, Object> r : rows(locationId)) {
            if (Boolean.TRUE.equals(r.get("due")) && wanted.contains(SqlRows.str(r, "abcClass"))) {
                productIds.add(SqlRows.str(r, "productId"));
            }
        }
        if (limit != null && limit > 0 && productIds.size() > limit) {
            productIds = productIds.subList(0, limit);
        }
        if (productIds.isEmpty()) {
            throw ApiException.badRequest("No products are due for a cycle count");
        }
        Map<String, Object> session = counts.startChecked(locationId, "cycle", "ABC cycle count", true, null,
                productIds, false);
        jdbc.sql("UPDATE \"WarehouseAbcClass\" SET \"lastScheduledAt\" = " + NOW + ", \"lastSessionId\" = :sid"
                + " WHERE \"organizationId\" = :org AND \"locationId\" = :loc AND \"productId\" IN (:ids)")
                .param("sid", session.get("id")).param("org", org()).param("loc", locationId).param("ids", productIds)
                .update();
        audit.recordInTx(AuditInput.of("WarehouseAbcClass", locationId, "create", null, Obj.of("cycleCount",
                session.get("id"), "products", productIds.size())));
        return Obj.of("session", session, "products", productIds.size());
    }
}
