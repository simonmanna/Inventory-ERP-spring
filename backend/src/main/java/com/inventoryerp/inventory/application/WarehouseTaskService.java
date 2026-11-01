package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.WarehouseStore.NOW;
import static com.inventoryerp.inventory.application.WarehouseStore.key;
import static com.inventoryerp.inventory.application.WarehouseStore.org;
import static com.inventoryerp.inventory.application.WarehouseStore.user;

import com.inventoryerp.inventory.application.WarehouseStore.NewTask;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warehouse floor work (roadmap 2.1, ADR-S026): putaway suggestions and putaway generation from unbinned stock, bin
 * to bin moves, and completing / cancelling tasks (pick tasks come from {@link WarehouseOutboundService#release}).
 *
 * <p>Putaway rules are tried most specific first (product, then category, then the location default) and by
 * priority. A rule targets one bin or a zone (any storage bin below it). Inside the candidates, bins that already hold
 * the product come first, then empty bins, then the rest, each by pick sequence; capacity counts stock already in the
 * bin plus open tasks heading there. Without a matching rule any active storage bin of the location is a candidate.
 */
@Service
public class WarehouseTaskService {

    /** One planned placement. */
    public record Placement(String binId, String binCode, BigDecimal quantity) {}

    private final JdbcClient jdbc;
    private final WarehouseStore store;
    private final WarehouseOutboundService outbound;
    private final AuditService audit;

    public WarehouseTaskService(WarehouseStore store, WarehouseOutboundService outbound, AuditService audit) {
        this.jdbc = store.jdbc();
        this.store = store;
        this.outbound = outbound;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- putaway planning

    private List<Map<String, Object>> candidateBins(String locationId, String productId, String variantKey) {
        Map<String, Object> product = store.product(productId);
        String categoryId = SqlRows.str(product, "categoryId");
        List<Map<String, Object>> rules = SqlRows.list(jdbc.sql("SELECT * FROM \"WarehousePutawayRule\""
                + " WHERE \"organizationId\" = ? AND \"locationId\" = ? AND \"isActive\" = true"
                + " AND (\"productId\" = ? OR (\"productId\" IS NULL AND \"categoryId\" = ?)"
                + "   OR (\"productId\" IS NULL AND \"categoryId\" IS NULL))"
                + " ORDER BY (\"productId\" IS NULL), (\"categoryId\" IS NULL), priority, \"createdAt\"")
                .params(org(), locationId, productId, categoryId == null ? "" : categoryId));
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> r : rules) {
            String binId = SqlRows.str(r, "targetBinId");
            String zoneId = SqlRows.str(r, "targetZoneId");
            List<Map<String, Object>> bins = binId != null
                    ? binsWhere("b.id = :root", binId, locationId, productId, variantKey)
                    : binsWhere("b.id IN (WITH RECURSIVE z AS (SELECT id FROM \"WarehouseBin\" WHERE id = :root"
                            + " UNION ALL SELECT c.id FROM \"WarehouseBin\" c JOIN z ON c.\"parentId\" = z.id)"
                            + " SELECT id FROM z)", zoneId, locationId, productId, variantKey);
            for (Map<String, Object> b : bins) {
                if (seen.add(SqlRows.str(b, "id"))) {
                    out.add(b);
                }
            }
        }
        if (rules.isEmpty()) {
            out.addAll(binsWhere("true", null, locationId, productId, variantKey));
        }
        return out;
    }

    private List<Map<String, Object>> binsWhere(String filter, String root, String locationId, String productId,
            String variantKey) {
        var spec = jdbc.sql("SELECT b.*, coalesce(s.qty, 0) AS held, coalesce(s.mine, 0) AS mine,"
                        + " coalesce(t.incoming, 0) AS incoming FROM \"WarehouseBin\" b"
                        + " LEFT JOIN (SELECT \"binId\", sum(quantity) AS qty, sum(CASE WHEN \"productId\" = :prod"
                        + "   AND \"variantKey\" = :var THEN quantity ELSE 0 END) AS mine FROM \"WarehouseBinStock\""
                        + "   GROUP BY \"binId\") s ON s.\"binId\" = b.id"
                        + " LEFT JOIN (SELECT \"toBinId\", sum(quantity) AS incoming FROM \"WarehouseTask\""
                        + "   WHERE status = 'open' AND type IN ('putaway', 'move') GROUP BY \"toBinId\") t ON t.\"toBinId\" = b.id"
                        + " WHERE b.\"organizationId\" = :org AND b.\"locationId\" = :loc AND b.kind = 'bin'"
                        + " AND b.\"isActive\" = true AND b.usage = 'storage' AND " + filter
                        + " ORDER BY (coalesce(s.mine, 0) > 0) DESC, (coalesce(s.qty, 0) = 0) DESC, b.\"pickSequence\", b.code")
                .param("org", org()).param("loc", locationId).param("prod", productId).param("var", variantKey);
        if (root != null) {
            spec = spec.param("root", root);
        }
        return SqlRows.list(spec);
    }

    /** Split {@code qty} over the candidate bins; the remainder that fits nowhere is left out. */
    List<Placement> plan(String locationId, String productId, String variantKey, BigDecimal qty) {
        List<Placement> out = new ArrayList<>();
        BigDecimal rest = qty;
        for (Map<String, Object> b : candidateBins(locationId, productId, variantKey)) {
            if (rest.signum() <= 0) {
                break;
            }
            boolean mixed = Boolean.TRUE.equals(b.get("mixedProducts"));
            BigDecimal held = SqlRows.dec(b, "held");
            if (!mixed && held.subtract(SqlRows.dec(b, "mine")).signum() > 0) {
                continue;
            }
            BigDecimal max = (BigDecimal) b.get("maxQty");
            BigDecimal room = max == null ? rest : max.subtract(held).subtract(SqlRows.dec(b, "incoming"));
            if (room.signum() <= 0) {
                continue;
            }
            BigDecimal take = room.min(rest);
            out.add(new Placement(SqlRows.str(b, "id"), SqlRows.str(b, "code"), take));
            rest = rest.subtract(take);
        }
        return out;
    }

    /** Where {@code quantity} of a product would go (nothing is written). */
    @Transactional(readOnly = true)
    public Map<String, Object> suggest(String locationId, String productId, String variantId, BigDecimal quantity) {
        store.location(locationId);
        store.product(productId);
        List<Placement> plan = plan(locationId, productId, key(variantId), quantity);
        BigDecimal placed = plan.stream().map(Placement::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Obj.of("productId", productId, "quantity", quantity, "placements", plan, "unplaced",
                quantity.subtract(placed));
    }

    /**
     * Create putaway tasks for everything unbinned in the location (ledger quantity − binned − staged − open
     * putaway tasks), placed by the rules. Returns the tasks and what did not fit anywhere.
     */
    @Transactional
    public Map<String, Object> generatePutaway(String locationId, String productId) {
        store.location(locationId);
        List<Map<String, Object>> keys = SqlRows.list(jdbc.sql("SELECT DISTINCT \"productId\", \"variantKey\""
                        + " FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"locationId\" = ? AND quantity > 0"
                        + (productId == null ? "" : " AND \"productId\" = ?") + " ORDER BY \"productId\"")
                .params(productId == null ? List.of(org(), locationId) : List.of(org(), locationId, productId)));
        List<String> created = new ArrayList<>();
        List<Map<String, Object>> unplaced = new ArrayList<>();
        for (Map<String, Object> k : keys) {
            String prod = SqlRows.str(k, "productId");
            String variant = SqlRows.str(k, "variantKey");
            store.lock(locationId, prod);
            BigDecimal waiting = store.unbinnedQty(locationId, prod, variant)
                    .subtract(store.openPutawayQty(locationId, prod, variant, null));
            if (waiting.signum() <= 0) {
                continue;
            }
            BigDecimal placed = BigDecimal.ZERO;
            for (Placement p : plan(locationId, prod, variant, waiting)) {
                created.add(store.insertTask(new NewTask(locationId, "putaway", prod, variant, "", p.quantity(), null,
                        p.binId(), null, null, null, 0)));
                placed = placed.add(p.quantity());
            }
            if (placed.compareTo(waiting) < 0) {
                unplaced.add(Obj.of("productId", prod, "variantKey", variant, "productName", store.productName(prod),
                        "quantity", waiting.subtract(placed)));
            }
        }
        if (!created.isEmpty()) {
            audit.recordInTx(AuditInput.of("WarehouseTask", locationId, "create", null, Obj.of("putawayTasks",
                    created.size())));
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (String id : created) {
            tasks.add(task(id));
        }
        return Obj.of("created", created.size(), "tasks", tasks, "unplaced", unplaced);
    }

    // ---------------------------------------------------------------- tasks

    private Map<String, Object> taskRow(String id, boolean forUpdate) {
        Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT * FROM \"WarehouseTask\" WHERE id = ? AND \"organizationId\""
                + " = ?" + (forUpdate ? " FOR UPDATE" : "")).params(id, org()));
        if (t == null) {
            throw ApiException.notFound("Warehouse task not found");
        }
        return t;
    }

    private Map<String, Object> task(String id) {
        return SqlRows.one(jdbc.sql("SELECT t.*, p.name AS \"productName\", fb.code AS \"fromBinCode\","
                + " tb.code AS \"toBinCode\", o.code AS \"pickOrderCode\" FROM \"WarehouseTask\" t"
                + " JOIN \"Product\" p ON p.id = t.\"productId\" LEFT JOIN \"WarehouseBin\" fb ON fb.id = t.\"fromBinId\""
                + " LEFT JOIN \"WarehouseBin\" tb ON tb.id = t.\"toBinId\""
                + " LEFT JOIN \"WarehousePickOrder\" o ON o.id = t.\"pickOrderId\" WHERE t.id = ?").param(id));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> tasks(String locationId, String type, String status, String waveId) {
        store.location(locationId);
        StringBuilder sql = new StringBuilder("SELECT t.*, p.name AS \"productName\", fb.code AS \"fromBinCode\","
                + " tb.code AS \"toBinCode\", o.code AS \"pickOrderCode\" FROM \"WarehouseTask\" t"
                + " JOIN \"Product\" p ON p.id = t.\"productId\" LEFT JOIN \"WarehouseBin\" fb ON fb.id = t.\"fromBinId\""
                + " LEFT JOIN \"WarehouseBin\" tb ON tb.id = t.\"toBinId\""
                + " LEFT JOIN \"WarehousePickOrder\" o ON o.id = t.\"pickOrderId\""
                + " WHERE t.\"organizationId\" = :org AND t.\"locationId\" = :loc");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("org", org());
        params.put("loc", locationId);
        if (type != null && !type.isBlank()) {
            sql.append(" AND t.type = :type");
            params.put("type", type);
        }
        sql.append(" AND t.status = :status");
        params.put("status", status == null || status.isBlank() ? "open" : status);
        if (waveId != null && !waveId.isBlank()) {
            sql.append(" AND t.\"waveId\" = :wave");
            params.put("wave", waveId);
        }
        sql.append(" ORDER BY t.sequence, fb.code, t.\"createdAt\" LIMIT 1000");
        return SqlRows.list(jdbc.sql(sql.toString()).params(params));
    }

    /**
     * A manual task: {@code putaway} (unbinned → {@code toBinId}, suggested when omitted) or {@code move}
     * ({@code fromBinId} → {@code toBinId}).
     */
    @Transactional
    public Map<String, Object> create(String locationId, String type, String productId, String variantId,
            String batchKey, BigDecimal quantity, String fromBinId, String toBinId) {
        store.location(locationId);
        store.product(productId);
        String variant = key(variantId);
        String batch = key(batchKey);
        store.lock(locationId, productId);
        String id;
        if ("putaway".equals(type)) {
            BigDecimal waiting = store.unbinnedQty(locationId, productId, variant)
                    .subtract(store.openPutawayQty(locationId, productId, variant, null));
            if (waiting.compareTo(quantity) < 0) {
                throw ApiException.badRequest("Only " + waiting.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString()
                        + " of " + store.productName(productId) + " is waiting for putaway");
            }
            String target = toBinId;
            if (target == null) {
                List<Placement> plan = plan(locationId, productId, variant, quantity);
                if (plan.size() != 1 || plan.getFirst().quantity().compareTo(quantity) != 0) {
                    throw ApiException.badRequest("No single bin can take " + quantity.stripTrailingZeros()
                            .toPlainString() + " of " + store.productName(productId) + "; choose a bin or generate putaway");
                }
                target = plan.getFirst().binId();
            } else {
                store.stockBin(target, locationId);
            }
            id = store.insertTask(new NewTask(locationId, "putaway", productId, variant, batch, quantity, null, target,
                    null, null, null, 0));
        } else if ("move".equals(type)) {
            if (fromBinId == null || toBinId == null) {
                throw ApiException.badRequest("A move needs fromBinId and toBinId");
            }
            if (fromBinId.equals(toBinId)) {
                throw ApiException.badRequest("fromBinId and toBinId must differ");
            }
            Map<String, Object> from = store.stockBin(fromBinId, locationId);
            store.stockBin(toBinId, locationId);
            BigDecimal held = jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"WarehouseBinStock\" WHERE \"binId\" = ?"
                    + " AND \"productId\" = ? AND \"variantKey\" = ? AND \"batchKey\" = ?")
                    .params(fromBinId, productId, variant, batch).query(BigDecimal.class).single();
            if (held.compareTo(quantity) < 0) {
                throw ApiException.badRequest("Not enough stock in bin " + from.get("code") + " for "
                        + store.productName(productId));
            }
            id = store.insertTask(new NewTask(locationId, "move", productId, variant, batch, quantity, fromBinId, toBinId,
                    null, null, null, 0));
        } else {
            throw ApiException.badRequest("type must be one of the following values: putaway, move");
        }
        audit.recordInTx(AuditInput.of("WarehouseTask", id, "create", null, Obj.of("type", type, "quantity", quantity)));
        return task(id);
    }

    /**
     * Complete a task. {@code quantity} defaults to the task quantity and may be lower (short pick / partial putaway);
     * {@code toBinId} overrides the destination of a putaway or move.
     */
    @Transactional
    public Map<String, Object> complete(String id, BigDecimal quantity, String toBinId) {
        Map<String, Object> t = taskRow(id, true);
        String locationId = SqlRows.str(t, "locationId");
        store.location(locationId);
        if (!"open".equals(t.get("status"))) {
            throw ApiException.badRequest("Task is already " + t.get("status"));
        }
        BigDecimal taskQty = SqlRows.dec(t, "quantity");
        BigDecimal qty = quantity == null ? taskQty : quantity;
        if (qty.signum() < 0 || qty.compareTo(taskQty) > 0) {
            throw ApiException.badRequest("quantity must be between 0 and " + taskQty.stripTrailingZeros().toPlainString());
        }
        String type = SqlRows.str(t, "type");
        String productId = SqlRows.str(t, "productId");
        String variant = SqlRows.str(t, "variantKey");
        String batch = SqlRows.str(t, "batchKey");
        store.lock(locationId, productId);
        String target = toBinId != null ? toBinId : SqlRows.str(t, "toBinId");
        if (qty.signum() > 0) {
            switch (type) {
                case "putaway" -> {
                    BigDecimal waiting = store.unbinnedQty(locationId, productId, variant)
                            .subtract(store.openPutawayQty(locationId, productId, variant, id));
                    if (waiting.compareTo(qty) < 0) {
                        throw ApiException.badRequest("Only " + waiting.max(BigDecimal.ZERO).stripTrailingZeros()
                                .toPlainString() + " of " + store.productName(productId) + " is waiting for putaway");
                    }
                    store.addStock(store.stockBin(target, locationId), productId, variant, batch, qty);
                }
                case "move" -> {
                    store.removeStock(store.stockBin(SqlRows.str(t, "fromBinId"), locationId), productId, variant, batch,
                            qty);
                    store.addStock(store.stockBin(target, locationId), productId, variant, batch, qty);
                }
                case "pick" -> {
                    store.removeStock(store.bin(SqlRows.str(t, "fromBinId")), productId, variant, batch, qty);
                    jdbc.sql("UPDATE \"WarehousePickLine\" SET \"pickedQty\" = \"pickedQty\" + ? WHERE id = ?")
                            .params(qty, t.get("pickLineId")).update();
                }
                default -> throw new IllegalStateException("unknown task type " + type);
            }
        }
        jdbc.sql("UPDATE \"WarehouseTask\" SET status = 'done', \"doneQty\" = ?, \"toBinId\" = ?, \"doneBy\" = ?,"
                        + " \"doneAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(qty, "pick".equals(type) ? null : target, user(), id).update();
        if ("pick".equals(type)) {
            outbound.refreshAfterPick(SqlRows.str(t, "pickOrderId"));
        }
        audit.recordInTx(AuditInput.of("WarehouseTask", id, "update", Obj.of("status", "open"), Obj.of("status", "done",
                "doneQty", qty)));
        return task(id);
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> t = taskRow(id, true);
        store.location(SqlRows.str(t, "locationId"));
        if (!"open".equals(t.get("status"))) {
            throw ApiException.badRequest("Task is already " + t.get("status"));
        }
        jdbc.sql("UPDATE \"WarehouseTask\" SET status = 'cancelled', \"updatedAt\" = " + NOW + " WHERE id = ?").param(id)
                .update();
        if ("pick".equals(t.get("type"))) {
            outbound.refreshAfterPick(SqlRows.str(t, "pickOrderId"));
        }
        audit.recordInTx(AuditInput.of("WarehouseTask", id, "update", Obj.of("status", "open"), Obj.of("status",
                "cancelled")));
        return task(id);
    }
}
