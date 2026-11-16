package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.WarehouseStore.NOW;
import static com.inventoryerp.inventory.application.WarehouseStore.id;
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
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbound warehouse flow (roadmap 2.1, ADR-S026): pick orders in one to three steps (pick + ship, pick → ship,
 * pick → pack → ship), packages, and waves that release several pick orders as one sequenced pick list.
 *
 * <p>Releasing allocates bin stock FEFO (batch expiry) then by bin pick sequence and creates one pick task per bin.
 * Completing a pick moves the quantity out of the bin into staging; shipping records the shipment. For an order with a
 * source document (sale, delivery, stock-out, transfer) the ledger issue stays with that document. A {@code manual}
 * order has no document behind it, so shipping it issues the shipped quantities itself ({@link StockService#issue},
 * stock-out GL) in the same transaction.
 */
@Service
public class WarehouseOutboundService {

    /** One requested line. */
    public record LineInput(String productId, String variantId, BigDecimal quantity) {}

    public record PickOrderInput(String locationId, String sourceType, String sourceId, String sourceRef,
            Integer priority, LocalDate scheduledDate, String notes, Integer steps, List<LineInput> lines) {}

    public record PackItem(String lineId, BigDecimal quantity) {}

    /** Source type of a pick order without a source document: shipping it posts the stock issue. */
    static final String MANUAL = "manual";

    private final JdbcClient jdbc;
    private final WarehouseStore store;
    private final StockService stock;
    private final AuditService audit;

    public WarehouseOutboundService(WarehouseStore store, StockService stock, AuditService audit) {
        this.jdbc = store.jdbc();
        this.store = store;
        this.stock = stock;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- reads

    Map<String, Object> order(String id, boolean forUpdate) {
        Map<String, Object> o = SqlRows.one(jdbc.sql("SELECT * FROM \"WarehousePickOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ?" + (forUpdate ? " FOR UPDATE" : "")).params(id, org()));
        if (o == null) {
            throw ApiException.notFound("Pick order not found");
        }
        return o;
    }

    private List<Map<String, Object>> lines(String orderId) {
        return SqlRows.list(jdbc.sql("SELECT l.*, p.name AS \"productName\", p.sku FROM \"WarehousePickLine\" l"
                + " JOIN \"Product\" p ON p.id = l.\"productId\" WHERE l.\"pickOrderId\" = ? ORDER BY p.name, l.id")
                .param(orderId));
    }

    List<Map<String, Object>> tasksOf(String column, String value) {
        return SqlRows.list(jdbc.sql("SELECT t.*, p.name AS \"productName\", fb.code AS \"fromBinCode\","
                + " tb.code AS \"toBinCode\", o.code AS \"pickOrderCode\" FROM \"WarehouseTask\" t"
                + " JOIN \"Product\" p ON p.id = t.\"productId\" LEFT JOIN \"WarehouseBin\" fb ON fb.id = t.\"fromBinId\""
                + " LEFT JOIN \"WarehouseBin\" tb ON tb.id = t.\"toBinId\""
                + " LEFT JOIN \"WarehousePickOrder\" o ON o.id = t.\"pickOrderId\""
                + " WHERE t.\"organizationId\" = ? AND t.\"" + column + "\" = ? ORDER BY t.sequence, fb.code, t.code")
                .params(org(), value));
    }

    private Map<String, Object> view(String id) {
        Map<String, Object> o = order(id, false);
        Map<String, Object> out = new LinkedHashMap<>(o);
        out.put("lines", lines(id));
        out.put("tasks", tasksOf("pickOrderId", id));
        List<Map<String, Object>> packages = SqlRows.list(jdbc.sql("SELECT * FROM \"WarehousePackage\""
                + " WHERE \"pickOrderId\" = ? ORDER BY \"createdAt\", code").param(id));
        for (Map<String, Object> p : packages) {
            p.put("items", SqlRows.list(jdbc.sql("SELECT i.\"pickLineId\", i.quantity, pr.name AS \"productName\""
                    + " FROM \"WarehousePackageItem\" i JOIN \"WarehousePickLine\" l ON l.id = i.\"pickLineId\""
                    + " JOIN \"Product\" pr ON pr.id = l.\"productId\" WHERE i.\"packageId\" = ?").param(p.get("id"))));
        }
        out.put("packages", packages);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String locationId, String status, String waveId) {
        store.location(locationId);
        StringBuilder sql = new StringBuilder("SELECT o.*, w.code AS \"waveCode\","
                + " (SELECT count(*) FROM \"WarehousePickLine\" l WHERE l.\"pickOrderId\" = o.id) AS \"lineCount\","
                + " (SELECT coalesce(sum(l.quantity), 0) FROM \"WarehousePickLine\" l WHERE l.\"pickOrderId\" = o.id)"
                + " AS \"totalQty\", (SELECT coalesce(sum(l.\"pickedQty\"), 0) FROM \"WarehousePickLine\" l"
                + " WHERE l.\"pickOrderId\" = o.id) AS \"pickedQty\" FROM \"WarehousePickOrder\" o"
                + " LEFT JOIN \"WarehouseWave\" w ON w.id = o.\"waveId\" WHERE o.\"organizationId\" = :org"
                + " AND o.\"locationId\" = :loc");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("org", org());
        params.put("loc", locationId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND o.status = :status");
            params.put("status", status);
        }
        if (waveId != null && !waveId.isBlank()) {
            sql.append(" AND o.\"waveId\" = :wave");
            params.put("wave", waveId);
        }
        sql.append(" ORDER BY o.priority DESC, o.\"createdAt\" DESC LIMIT 500");
        return SqlRows.list(jdbc.sql(sql.toString()).params(params));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> o = order(id, false);
        store.location(SqlRows.str(o, "locationId"));
        return view(id);
    }

    // ---------------------------------------------------------------- create / release

    /** A draft pick order; {@code steps} defaults to the location's setting. */
    @Transactional
    public Map<String, Object> create(PickOrderInput in) {
        store.operable(in.locationId());
        if (in.lines() == null || in.lines().isEmpty()) {
            throw ApiException.badRequest("A pick order needs at least one line");
        }
        int steps = in.steps() != null ? in.steps() : store.settings(in.locationId()).outboundSteps();
        String id = id();
        String code = store.nextCode("wms_pick", "PK-");
        jdbc.sql("INSERT INTO \"WarehousePickOrder\" (id, \"organizationId\", \"locationId\", code, \"sourceType\","
                        + " \"sourceId\", \"sourceRef\", steps, status, priority, \"scheduledDate\", notes, \"createdBy\","
                        + " \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?, ?, ?, " + NOW + ", "
                        + NOW + ")")
                .params(id, org(), in.locationId(), code, in.sourceType() == null ? MANUAL : in.sourceType(),
                        in.sourceId(), in.sourceRef(), steps, in.priority() == null ? 0 : in.priority(),
                        in.scheduledDate() == null ? null : Date.valueOf(in.scheduledDate()), in.notes(), user())
                .update();
        for (LineInput l : in.lines()) {
            store.product(l.productId());
            jdbc.sql("INSERT INTO \"WarehousePickLine\" (id, \"organizationId\", \"pickOrderId\", \"productId\","
                            + " \"variantKey\", quantity) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(id(), org(), id, l.productId(), key(l.variantId()), l.quantity()).update();
        }
        audit.recordInTx(AuditInput.of("WarehousePickOrder", id, "create", null, Obj.of("code", code, "steps", steps)));
        return view(id);
    }

    /** Allocate bin stock and create the pick tasks (400 when a line cannot be covered from the bins). */
    @Transactional
    public Map<String, Object> release(String id) {
        Map<String, Object> o = order(id, true);
        store.operable(SqlRows.str(o, "locationId"));
        releaseLocked(o);
        audit.recordInTx(AuditInput.of("WarehousePickOrder", id, "update", Obj.of("status", "draft"), Obj.of("status",
                "released")));
        return view(id);
    }

    private void releaseLocked(Map<String, Object> o) {
        if (!"draft".equals(o.get("status"))) {
            throw ApiException.badRequest("Only a draft pick order can be released");
        }
        String locationId = SqlRows.str(o, "locationId");
        for (Map<String, Object> line : lines(SqlRows.str(o, "id"))) {
            String productId = SqlRows.str(line, "productId");
            String variantKey = SqlRows.str(line, "variantKey");
            store.lock(locationId, productId);
            BigDecimal need = SqlRows.dec(line, "quantity");
            List<Map<String, Object>> binRows = SqlRows.list(jdbc.sql("SELECT s.\"binId\", s.\"batchKey\", b.\"pickSequence\","
                    + " s.quantity - coalesce((SELECT sum(t.quantity) FROM \"WarehouseTask\" t WHERE t.\"fromBinId\" = s.\"binId\""
                    + "   AND t.\"productId\" = s.\"productId\" AND t.\"variantKey\" = s.\"variantKey\""
                    + "   AND t.\"batchKey\" = s.\"batchKey\" AND t.status = 'open' AND t.type IN ('pick', 'move')), 0)"
                    + " AS free FROM \"WarehouseBinStock\" s JOIN \"WarehouseBin\" b ON b.id = s.\"binId\""
                    + " LEFT JOIN \"InventoryBatch\" ib ON ib.id = s.\"batchKey\""
                    + " WHERE s.\"organizationId\" = ? AND s.\"locationId\" = ? AND s.\"productId\" = ?"
                    + " AND s.\"variantKey\" = ? AND s.quantity > 0 AND b.\"isActive\" = true"
                    + " AND b.usage IN ('storage', 'receiving')"
                    + " ORDER BY ib.\"expiryDate\" ASC NULLS LAST, b.\"pickSequence\", b.code")
                    .params(org(), locationId, productId, variantKey));
            BigDecimal available = binRows.stream().map(r -> SqlRows.dec(r, "free").max(BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (available.compareTo(need) < 0) {
                throw ApiException.badRequest("Not enough binned stock to pick " + line.get("productName") + ": need "
                        + need.stripTrailingZeros().toPlainString() + ", available "
                        + available.stripTrailingZeros().toPlainString());
            }
            // Bins are a sub-ledger and can hold more than the ledger (stock issued without a pick order): never
            // allocate more than the location really has, net of what is staged and already allocated to open picks.
            BigDecimal onHand = store.locationQty(locationId, productId, variantKey)
                    .subtract(store.stagedQty(locationId, productId, variantKey))
                    .subtract(store.openPickQty(locationId, productId, variantKey)).max(BigDecimal.ZERO);
            if (onHand.compareTo(need) < 0) {
                throw ApiException.badRequest("Not enough stock on hand to pick " + line.get("productName") + ": need "
                        + need.stripTrailingZeros().toPlainString() + ", on hand "
                        + onHand.stripTrailingZeros().toPlainString() + " (the bins hold more than the stock ledger;"
                        + " reconcile the bins)");
            }
            for (Map<String, Object> r : binRows) {
                if (need.signum() <= 0) {
                    break;
                }
                BigDecimal free = SqlRows.dec(r, "free");
                if (free.signum() <= 0) {
                    continue;
                }
                BigDecimal take = free.min(need);
                store.insertTask(new NewTask(locationId, "pick", productId, variantKey, SqlRows.str(r, "batchKey"), take,
                        SqlRows.str(r, "binId"), null, SqlRows.str(o, "id"), SqlRows.str(line, "id"),
                        SqlRows.str(o, "waveId"), ((Number) r.get("pickSequence")).intValue()));
                need = need.subtract(take);
            }
        }
        jdbc.sql("UPDATE \"WarehousePickOrder\" SET status = 'released', \"updatedAt\" = " + NOW + " WHERE id = ?")
                .param(o.get("id")).update();
    }

    // ---------------------------------------------------------------- after a pick

    /**
     * Called by the task service after a pick task is done or cancelled: moves the order to {@code picking} or
     * {@code picked}; a one-step order ships itself once nothing is left to pick. When every task ended without
     * anything picked (all cancelled or done with 0), the order goes back to {@code draft}: it can be released again
     * or cancelled, and is never shipped empty.
     */
    void refreshAfterPick(String orderId) {
        Map<String, Object> o = order(orderId, true);
        String status = SqlRows.str(o, "status");
        if (!"released".equals(status) && !"picking".equals(status)) {
            return;
        }
        long open = jdbc.sql("SELECT count(*) FROM \"WarehouseTask\" WHERE \"pickOrderId\" = ? AND status = 'open'")
                .param(orderId).query(Long.class).single();
        BigDecimal picked = jdbc.sql("SELECT coalesce(sum(\"pickedQty\"), 0) FROM \"WarehousePickLine\""
                + " WHERE \"pickOrderId\" = ?").param(orderId).query(BigDecimal.class).single();
        String next = open > 0 ? (picked.signum() > 0 ? "picking" : "released") : picked.signum() > 0 ? "picked"
                : "draft";
        if ("picked".equals(next) && ((Number) o.get("steps")).intValue() == 3 && picked.signum() > 0
                && jdbc.sql("SELECT count(*) FROM \"WarehousePickLine\" WHERE \"pickOrderId\" = ?"
                        + " AND \"packedQty\" < \"pickedQty\"").param(orderId).query(Long.class).single() == 0) {
            next = "packed";
        }
        jdbc.sql("UPDATE \"WarehousePickOrder\" SET status = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(next, orderId).update();
        if ("picked".equals(next) && ((Number) o.get("steps")).intValue() == 1) {
            shipLocked(order(orderId, true), null, null);
        }
    }

    // ---------------------------------------------------------------- pack / ship / cancel

    /** Pack picked quantities into a package (three-step orders). Without items, everything still unpacked. */
    @Transactional
    public Map<String, Object> pack(String id, String packageType, BigDecimal weightKg, List<PackItem> items) {
        Map<String, Object> o = order(id, true);
        store.operable(SqlRows.str(o, "locationId"));
        if (((Number) o.get("steps")).intValue() != 3) {
            throw ApiException.badRequest("Only a three-step pick order is packed");
        }
        String status = SqlRows.str(o, "status");
        if (!"picking".equals(status) && !"picked".equals(status)) {
            throw ApiException.badRequest("Nothing is picked yet");
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> l : lines(id)) {
            byId.put(SqlRows.str(l, "id"), l);
        }
        List<PackItem> toPack = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            for (Map<String, Object> l : byId.values()) {
                BigDecimal rest = SqlRows.dec(l, "pickedQty").subtract(SqlRows.dec(l, "packedQty"));
                if (rest.signum() > 0) {
                    toPack.add(new PackItem(SqlRows.str(l, "id"), rest));
                }
            }
        } else {
            toPack.addAll(items);
        }
        if (toPack.isEmpty()) {
            throw ApiException.badRequest("Everything picked is already packed");
        }
        String pkgId = id();
        String code = store.nextCode("wms_pkg", "PKG-");
        jdbc.sql("INSERT INTO \"WarehousePackage\" (id, \"organizationId\", \"pickOrderId\", code, \"packageType\","
                        + " \"weightKg\", status, \"createdBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, 'closed',"
                        + " ?, " + NOW + ", " + NOW + ")")
                .params(pkgId, org(), id, code, packageType, weightKg, user()).update();
        for (PackItem item : toPack) {
            Map<String, Object> l = byId.get(item.lineId());
            if (l == null) {
                throw ApiException.badRequest("Line " + item.lineId() + " is not on this pick order");
            }
            BigDecimal rest = SqlRows.dec(l, "pickedQty").subtract(SqlRows.dec(l, "packedQty"));
            if (item.quantity() == null || item.quantity().signum() <= 0 || item.quantity().compareTo(rest) > 0) {
                throw ApiException.badRequest("Cannot pack more of " + l.get("productName") + " than is picked and"
                        + " unpacked (" + rest.stripTrailingZeros().toPlainString() + ")");
            }
            jdbc.sql("INSERT INTO \"WarehousePackageItem\" (id, \"organizationId\", \"packageId\", \"pickLineId\", quantity)"
                    + " VALUES (?, ?, ?, ?, ?)").params(id(), org(), pkgId, item.lineId(), item.quantity()).update();
            jdbc.sql("UPDATE \"WarehousePickLine\" SET \"packedQty\" = \"packedQty\" + ? WHERE id = ?")
                    .params(item.quantity(), item.lineId()).update();
            l.put("packedQty", SqlRows.dec(l, "packedQty").add(item.quantity()));
        }
        long unpacked = jdbc.sql("SELECT count(*) FROM \"WarehousePickLine\" WHERE \"pickOrderId\" = ?"
                + " AND \"packedQty\" < \"pickedQty\"").param(id).query(Long.class).single();
        if ("picked".equals(status) && unpacked == 0) {
            jdbc.sql("UPDATE \"WarehousePickOrder\" SET status = 'packed', \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .param(id).update();
        }
        audit.recordInTx(AuditInput.of("WarehousePackage", pkgId, "create", null, Obj.of("code", code, "pickOrderId",
                id)));
        return view(id);
    }

    /** Ship a picked (two-step) or packed (three-step) order. */
    @Transactional
    public Map<String, Object> ship(String id, String carrier, String trackingRef) {
        Map<String, Object> o = order(id, true);
        store.operable(SqlRows.str(o, "locationId"));
        int steps = ((Number) o.get("steps")).intValue();
        String status = SqlRows.str(o, "status");
        if ("shipped".equals(status)) {
            throw ApiException.badRequest("Pick order is already shipped");
        }
        if (steps == 3 && !"packed".equals(status)) {
            throw ApiException.badRequest("Pack everything picked before shipping");
        }
        if (steps < 3 && !"picked".equals(status)) {
            throw ApiException.badRequest("Finish picking before shipping");
        }
        if (pickedTotal(id).signum() <= 0) {
            throw ApiException.badRequest("Nothing was picked on this pick order; release it again or cancel it");
        }
        shipLocked(o, carrier, trackingRef);
        audit.recordInTx(AuditInput.of("WarehousePickOrder", id, "update", Obj.of("status", status), Obj.of("status",
                "shipped", "carrier", carrier, "trackingRef", trackingRef)));
        return view(id);
    }

    private BigDecimal pickedTotal(String orderId) {
        return jdbc.sql("SELECT coalesce(sum(\"pickedQty\"), 0) FROM \"WarehousePickLine\" WHERE \"pickOrderId\" = ?")
                .param(orderId).query(BigDecimal.class).single();
    }

    /** Caller holds the order row lock and has checked the status: this runs once per order. */
    private void shipLocked(Map<String, Object> o, String carrier, String trackingRef) {
        String id = SqlRows.str(o, "id");
        if (MANUAL.equals(o.get("sourceType"))) {
            issueShipped(o);
        }
        jdbc.sql("UPDATE \"WarehousePickLine\" SET \"shippedQty\" = \"pickedQty\" WHERE \"pickOrderId\" = ?").param(id)
                .update();
        jdbc.sql("UPDATE \"WarehousePackage\" SET status = 'shipped', \"updatedAt\" = " + NOW
                + " WHERE \"pickOrderId\" = ?").param(id).update();
        jdbc.sql("UPDATE \"WarehousePickOrder\" SET status = 'shipped', carrier = coalesce(?, carrier),"
                        + " \"trackingRef\" = coalesce(?, \"trackingRef\"), \"shippedAt\" = " + NOW + ", \"shippedBy\" = ?,"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(carrier, trackingRef, user(), id).update();
        refreshWave(SqlRows.str(o, "waveId"));
    }

    /**
     * A manual order has no source document to post the issue: issue every picked quantity (per product, variant and
     * lot, in the engine's lock order) at the order's location, refusing a shortfall. GL follows the stock-out path
     * ({@code STOCK_OUT}: Dr stock-out expense / Cr stock valuation). Sales / delivery / stock-out / transfer orders
     * never get here: their document posts the issue.
     */
    private void issueShipped(Map<String, Object> o) {
        String id = SqlRows.str(o, "id");
        String code = SqlRows.str(o, "code");
        String locationId = SqlRows.str(o, "locationId");
        List<Map<String, Object>> picked = SqlRows.list(jdbc.sql("SELECT \"productId\", \"variantKey\", \"batchKey\","
                + " sum(\"doneQty\") AS qty FROM \"WarehouseTask\" WHERE \"pickOrderId\" = ? AND \"organizationId\" = ?"
                + " AND type = 'pick' AND status = 'done' AND \"doneQty\" > 0 GROUP BY 1, 2, 3"
                + " ORDER BY \"productId\", \"variantKey\", \"batchKey\"").params(id, org()));
        List<Map<String, Object>> issued = new ArrayList<>();
        for (Map<String, Object> p : picked) {
            String variantKey = SqlRows.str(p, "variantKey");
            String batchKey = SqlRows.str(p, "batchKey");
            StockMove m = StockMove.of(SqlRows.str(p, "productId"), locationId, SqlRows.dec(p, "qty"))
                    .variant(variantKey == null || variantKey.isEmpty() ? null : variantKey).moveType("issue")
                    .source("WarehousePickOrder", code).notes("Pick order " + code + " shipped")
                    .people(user(), user()).requireAvailable(true);
            if (batchKey != null && !batchKey.isEmpty()) {
                m.pin(List.of(batchKey), null, null);
            }
            StockMove.Issued res = stock.issue(m);
            issued.add(Obj.of("productId", p.get("productId"), "variantKey", variantKey, "batchKey", batchKey,
                    "quantity", p.get("qty"), "ledgerCode", res.ledgerCode(), "totalValue", res.totalValue()));
        }
        audit.recordInTx(AuditInput.of("WarehousePickOrder", id, "update", null, Obj.of("stockIssued", issued)));
    }

    /**
     * Cancel an order that is not shipped. Open pick tasks are cancelled; stock already picked goes back into
     * {@code returnBinId} (required when anything was picked).
     */
    @Transactional
    public Map<String, Object> cancel(String id, String returnBinId) {
        Map<String, Object> o = order(id, true);
        String locationId = SqlRows.str(o, "locationId");
        store.operable(locationId);
        String status = SqlRows.str(o, "status");
        if ("shipped".equals(status) || "cancelled".equals(status)) {
            throw ApiException.badRequest("A " + status + " pick order cannot be cancelled");
        }
        List<Map<String, Object>> picked = SqlRows.list(jdbc.sql("SELECT t.\"productId\", t.\"variantKey\", t.\"batchKey\","
                + " sum(t.\"doneQty\") AS qty FROM \"WarehouseTask\" t WHERE t.\"pickOrderId\" = ? AND t.status = 'done'"
                + " AND t.\"doneQty\" > 0 GROUP BY 1, 2, 3").param(id));
        if (!picked.isEmpty()) {
            if (returnBinId == null || returnBinId.isBlank()) {
                throw ApiException.badRequest("returnBinId is required to put the picked stock back");
            }
            Map<String, Object> bin = store.stockBin(returnBinId, locationId);
            for (Map<String, Object> p : picked) {
                store.addStock(bin, SqlRows.str(p, "productId"), SqlRows.str(p, "variantKey"),
                        SqlRows.str(p, "batchKey"), SqlRows.dec(p, "qty"));
            }
            jdbc.sql("UPDATE \"WarehousePickLine\" SET \"pickedQty\" = 0, \"packedQty\" = 0 WHERE \"pickOrderId\" = ?")
                    .param(id).update();
            jdbc.sql("DELETE FROM \"WarehousePackage\" WHERE \"pickOrderId\" = ?").param(id).update();
        }
        jdbc.sql("UPDATE \"WarehouseTask\" SET status = 'cancelled', \"updatedAt\" = " + NOW
                + " WHERE \"pickOrderId\" = ? AND status = 'open'").param(id).update();
        jdbc.sql("UPDATE \"WarehousePickOrder\" SET status = 'cancelled', \"updatedAt\" = " + NOW + " WHERE id = ?")
                .param(id).update();
        refreshWave(SqlRows.str(o, "waveId"));
        audit.recordInTx(AuditInput.of("WarehousePickOrder", id, "update", Obj.of("status", status), Obj.of("status",
                "cancelled", "returnBinId", returnBinId)));
        return view(id);
    }

    // ---------------------------------------------------------------- waves

    private Map<String, Object> wave(String id, boolean forUpdate) {
        Map<String, Object> w = SqlRows.one(jdbc.sql("SELECT * FROM \"WarehouseWave\" WHERE id = ?"
                + " AND \"organizationId\" = ?" + (forUpdate ? " FOR UPDATE" : "")).params(id, org()));
        if (w == null) {
            throw ApiException.notFound("Wave not found");
        }
        return w;
    }

    private void refreshWave(String waveId) {
        if (waveId == null) {
            return;
        }
        long live = jdbc.sql("SELECT count(*) FROM \"WarehousePickOrder\" WHERE \"waveId\" = ?"
                + " AND status NOT IN ('shipped', 'cancelled')").param(waveId).query(Long.class).single();
        if (live == 0) {
            jdbc.sql("UPDATE \"WarehouseWave\" SET status = 'done', \"updatedAt\" = " + NOW
                    + " WHERE id = ? AND status = 'released'").param(waveId).update();
        }
    }

    private Map<String, Object> waveView(String id) {
        Map<String, Object> out = new LinkedHashMap<>(wave(id, false));
        out.put("orders", SqlRows.list(jdbc.sql("SELECT id, code, status, \"sourceType\", \"sourceRef\", priority, steps"
                + " FROM \"WarehousePickOrder\" WHERE \"waveId\" = ? ORDER BY priority DESC, code").param(id)));
        out.put("pickList", SqlRows.list(jdbc.sql("SELECT t.\"fromBinId\", b.code AS \"binCode\", t.\"productId\","
                + " p.name AS \"productName\", t.\"batchKey\", sum(t.quantity) AS quantity,"
                + " sum(CASE WHEN t.status = 'open' THEN t.quantity ELSE 0 END) AS \"openQty\","
                + " count(*) AS tasks, min(t.sequence) AS sequence FROM \"WarehouseTask\" t"
                + " JOIN \"WarehouseBin\" b ON b.id = t.\"fromBinId\" JOIN \"Product\" p ON p.id = t.\"productId\""
                + " WHERE t.\"waveId\" = ? AND t.type = 'pick' AND t.status <> 'cancelled'"
                + " GROUP BY t.\"fromBinId\", b.code, t.\"productId\", p.name, t.\"batchKey\""
                + " ORDER BY min(t.sequence), b.code, p.name").param(id)));
        out.put("tasks", tasksOf("waveId", id));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> waves(String locationId) {
        store.location(locationId);
        return SqlRows.list(jdbc.sql("SELECT w.*, (SELECT count(*) FROM \"WarehousePickOrder\" o WHERE o.\"waveId\" = w.id)"
                + " AS \"orderCount\" FROM \"WarehouseWave\" w WHERE w.\"organizationId\" = ? AND w.\"locationId\" = ?"
                + " ORDER BY w.\"createdAt\" DESC LIMIT 200").params(org(), locationId));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getWave(String id) {
        store.location(SqlRows.str(wave(id, false), "locationId"));
        return waveView(id);
    }

    /** Group draft pick orders of one location into a wave. */
    @Transactional
    public Map<String, Object> createWave(String locationId, List<String> pickOrderIds, String notes) {
        store.operable(locationId);
        if (pickOrderIds == null || pickOrderIds.isEmpty()) {
            throw ApiException.badRequest("A wave needs at least one pick order");
        }
        String id = id();
        String code = store.nextCode("wms_wave", "WV-");
        jdbc.sql("INSERT INTO \"WarehouseWave\" (id, \"organizationId\", \"locationId\", code, status, notes, \"createdBy\","
                        + " \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, 'open', ?, ?, " + NOW + ", " + NOW + ")")
                .params(id, org(), locationId, code, notes, user()).update();
        for (String orderId : new LinkedHashSet<>(pickOrderIds)) {
            Map<String, Object> o = order(orderId, true);
            if (!locationId.equals(o.get("locationId"))) {
                throw ApiException.badRequest("Pick order " + o.get("code") + " is in another location");
            }
            if (!"draft".equals(o.get("status"))) {
                throw ApiException.badRequest("Pick order " + o.get("code") + " is not a draft");
            }
            if (o.get("waveId") != null) {
                throw ApiException.badRequest("Pick order " + o.get("code") + " is already in a wave");
            }
            jdbc.sql("UPDATE \"WarehousePickOrder\" SET \"waveId\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(id, orderId).update();
        }
        audit.recordInTx(AuditInput.of("WarehouseWave", id, "create", null, Obj.of("code", code, "orders",
                pickOrderIds.size())));
        return waveView(id);
    }

    /** Release every draft order of the wave, highest priority first: one sequenced pick list. */
    @Transactional
    public Map<String, Object> releaseWave(String id) {
        Map<String, Object> w = wave(id, true);
        store.operable(SqlRows.str(w, "locationId"));
        if (!"open".equals(w.get("status"))) {
            throw ApiException.badRequest("Only an open wave can be released");
        }
        List<String> ids = jdbc.sql("SELECT id FROM \"WarehousePickOrder\" WHERE \"waveId\" = ? AND status = 'draft'"
                + " ORDER BY priority DESC, code").param(id).query(String.class).list();
        if (ids.isEmpty()) {
            throw ApiException.badRequest("The wave has no draft pick orders");
        }
        for (String orderId : ids) {
            releaseLocked(order(orderId, true));
        }
        jdbc.sql("UPDATE \"WarehouseWave\" SET status = 'released', \"releasedAt\" = " + NOW + ", \"updatedAt\" = " + NOW
                + " WHERE id = ?").param(id).update();
        audit.recordInTx(AuditInput.of("WarehouseWave", id, "update", Obj.of("status", "open"), Obj.of("status",
                "released")));
        return waveView(id);
    }
}
