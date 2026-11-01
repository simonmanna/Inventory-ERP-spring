package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.num;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.invoicing.application.DocumentBuilderService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.PreparedLine;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Totals;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.workflow.WorkflowService;
import com.inventoryerp.pos.domain.Order;
import com.inventoryerp.pos.domain.OrderItem;
import com.inventoryerp.pos.domain.OrderItemModifier;
import com.inventoryerp.pos.domain.PosTable;
import com.inventoryerp.pos.domain.PosTableOrder;
import com.inventoryerp.pos.domain.PosTableReservation;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/** Tables management (port of {@code pos-tables.service.ts}): CRUD, merge / transfer / split, floor SSE stream. */
@Service
public class PosTablesService {

    private static final Logger log = LoggerFactory.getLogger(PosTablesService.class);
    private static final List<String> FIELDS = List.of("name", "number", "seats", "zone", "shape", "posX", "posY", "width",
            "height", "notes", "active", "assignedWaiterId", "sortOrder", "qrCodeUrl");

    private final PosDb db;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventBus events;
    private final DocumentBuilderService builder;
    private final PosTableZonesService zones;
    private final WorkflowService workflows;
    private final PosOrdersService orders;
    private final TransactionTemplate tx;
    private final PosSseHub hub;

    public PosTablesService(PosDb db, AuditService audit, EventBus events, DocumentBuilderService builder,
            PosTableZonesService zones, WorkflowService workflows, PosOrdersService orders, JsonMapper mapper,
            PlatformTransactionManager txm) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.audit = audit;
        this.events = events;
        this.builder = builder;
        this.zones = zones;
        this.workflows = workflows;
        this.orders = orders;
        this.tx = new TransactionTemplate(txm);
        this.hub = new PosSseHub("tables", mapper, c -> Obj.of("type", "snapshot", "tables", list(null, null, true),
                "stats", stats()));
    }

    @PostConstruct
    void subscribe() {
        for (String evt : List.of(DomainEvents.POS_TABLE_CREATED, DomainEvents.POS_TABLE_UPDATED,
                DomainEvents.POS_TABLE_DELETED, DomainEvents.POS_TABLE_STATUS_CHANGED, DomainEvents.POS_TABLE_MERGED,
                DomainEvents.POS_TABLE_UNMERGED, DomainEvents.POS_TABLE_TRANSFERRED, DomainEvents.POS_TABLE_SPLIT,
                DomainEvents.POS_TABLE_CLEANED, DomainEvents.POS_TABLE_RESERVATION_CREATED,
                DomainEvents.POS_TABLE_RESERVATION_SEATED, DomainEvents.POS_TABLE_RESERVATION_CANCELLED,
                DomainEvents.POS_TABLE_RESERVATION_NO_SHOW, DomainEvents.POS_ORDER_CREATED, DomainEvents.POS_ORDER_UPDATED,
                DomainEvents.POS_ORDER_CANCELLED, DomainEvents.POS_ORDER_CLOSED, DomainEvents.POS_ORDER_INVOICED)) {
            events.subscribe(evt, p -> {
                if (hub.size() > 0 && p.get("organizationId") != null) {
                    hub.changed(p.get("organizationId").asString());
                }
            });
        }
    }

    /** OCCUPIED iff ≥1 active item; 404 without a table. */
    public String syncTableStatus(String tableId) {
        String status = TableStatus.recompute(jdbc, org(), tableId);
        if (status == null) {
            throw ApiException.notFound("Table not found");
        }
        return status;
    }

    // ─── order helpers ───

    private Map<String, Object> createTabOrder(String tableId, String partnerId, String branchId) {
        String orgId = org();
        Map<String, Object> order = db.insert(Order.class, Obj.of("organizationId", orgId,
                "orderNumber", orders.nextOrderNumber(), "orderType", "dine_in", "status", "confirmed", "tableId", tableId,
                "partnerId", partnerId, "waiterId", user(), "branchId", branchId, "createdBy", user()));
        db.insert(PosTableOrder.class, Obj.of("organizationId", orgId, "tableId", tableId, "orderId", order.get("id")));
        return order;
    }

    /** One rebuild input: priced line + modifier rows. */
    record Input(Map<String, Object> line, List<Map<String, Object>> mods) {}

    /** Replace an order's items (priced through the tax engine), keeping kitchen lifecycle by productId. */
    private void rebuildOrderItems(String orderId, List<Input> inputs) {
        String orgId = org();
        TableStatus.lockFloorShared(jdbc, orgId);
        Map<String, Map<String, Object>> lifecycle = new HashMap<>();
        for (Map<String, Object> o : db.list(OrderItem.class, "\"orderId\" = ?", orderId)) {
            if (o.get("productId") != null) {
                lifecycle.put((String) o.get("productId"), o);
            }
        }
        List<LineInput> lines = new ArrayList<>();
        inputs.forEach(i -> lines.add(PosLines.lineInput(i.line())));
        Totals totals = builder.prepareLines(lines);
        jdbc.sql("DELETE FROM \"OrderItem\" WHERE \"orderId\" = ? AND \"organizationId\" = ?").params(orderId, orgId).update();
        for (int i = 0; i < totals.prepared().size(); i++) {
            PreparedLine p = totals.prepared().get(i);
            Map<String, Object> lc = p.productId() == null ? null : lifecycle.get(p.productId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("organizationId", orgId);
            data.put("orderId", orderId);
            data.put("productId", p.productId());
            data.put("menuItemId", p.menuItemId());
            data.put("variantId", p.variantId());
            data.put("variantName", p.variantName());
            data.put("description", p.description());
            data.put("quantity", p.quantity());
            data.put("unitPrice", p.unitPrice());
            data.put("discountPercent", p.discountPercent());
            data.put("taxId", p.taxId());
            data.put("taxInclusive", p.taxInclusive());
            data.put("lineNumber", p.lineNumber());
            data.put("kitchenStatus", lc == null || lc.get("kitchenStatus") == null ? "pending" : lc.get("kitchenStatus"));
            data.put("kitchenPrintCount", lc == null ? 0 : lc.get("kitchenPrintCount"));
            data.put("kitchenLastPrintedAt", lc == null ? null : lc.get("kitchenLastPrintedAt"));
            data.put("kitchenPrintedQty", lc == null ? null : lc.get("kitchenPrintedQty"));
            data.put("kotPrintedQty", lc == null ? 0 : lc.get("kotPrintedQty"));
            data.put("billPrintedQty", lc == null ? 0 : lc.get("billPrintedQty"));
            data.put("billLastPrintedAt", lc == null ? null : lc.get("billLastPrintedAt"));
            data.put("lastBillPrintedById", lc == null ? null : lc.get("lastBillPrintedById"));
            data.put("cancelPrintCount", lc == null ? 0 : lc.get("cancelPrintCount"));
            data.put("cancelLastPrintedAt", lc == null ? null : lc.get("cancelLastPrintedAt"));
            data.put("lastKitchenPrintedById", lc == null ? null : lc.get("lastKitchenPrintedById"));
            Map<String, Object> item = db.insert(OrderItem.class, data);
            for (Map<String, Object> m : inputs.get(i).mods()) {
                db.insert(OrderItemModifier.class, Obj.of("organizationId", orgId, "orderItemId", item.get("id"),
                        "modifierId", m.get("modifierId"), "name", m.get("name"),
                        "priceDelta", m.get("priceDelta") == null ? 0 : m.get("priceDelta")));
            }
        }
        db.update(Order.class, Obj.of("subtotal", totals.subtotal(), "discountTotal", totals.discountTotal(),
                "taxAmount", totals.taxAmount(), "totalAmount", totals.total(), "version", PosDb.raw("version + 1")),
                "id = ?", orderId);
    }

    // ─── queries ───

    private void reconcileStuckTables(String orgId) {
        List<String> occupied = jdbc.sql("SELECT DISTINCT o.\"tableId\" FROM \"Order\" o WHERE o.\"organizationId\" = ?"
                + " AND o.status::text IN " + TableStatus.HELD_SQL + " AND o.\"tableId\" IS NOT NULL AND EXISTS (SELECT 1 FROM"
                + " \"OrderItem\" oi WHERE oi.\"orderId\" = o.id AND oi.cancelled = false)").param(orgId).query(String.class).list();
        List<Object> params = new ArrayList<>(List.of(orgId));
        String notIn = "";
        if (!occupied.isEmpty()) {
            notIn = " AND id NOT IN (" + PosSql.marks(occupied.size()) + ")";
            params.addAll(occupied);
        }
        jdbc.sql("UPDATE \"PosTable\" SET status = 'available', \"updatedAt\" = " + NOW + " WHERE \"organizationId\" = ?"
                + " AND status = 'occupied'" + notIn).params(params).update();
    }

    private Map<String, String> waiterNames(List<Object> ids) {
        Map<String, String> out = new HashMap<>();
        for (Object id : new LinkedHashSet<>(ids)) {
            if (id == null) {
                continue;
            }
            Map<String, Object> u = SqlRows.one(jdbc.sql("SELECT \"firstName\", \"lastName\" FROM \"User\" WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
            if (u != null) {
                out.put((String) id, (u.get("firstName") + (PosSql.truthy(u.get("lastName")) ? " " + u.get("lastName") : "")).trim());
            }
        }
        return out;
    }

    /** PosTableOrder rows (non-cancelled orders) with their order summary. */
    private List<Map<String, Object>> tableOrders(Object tableId, boolean openOnly, boolean withCreatedAt) {
        List<Map<String, Object>> links = SqlRows.list(PosTableOrder.class, jdbc.sql("SELECT l.* FROM \"PosTableOrder\" l"
                + " JOIN \"Order\" o ON o.id = l.\"orderId\" WHERE l.\"tableId\" = ? AND o.status::text <> 'cancelled'"
                + (openOnly ? " AND l.\"closedAt\" IS NULL" : " ORDER BY l.\"openedAt\" DESC LIMIT 50")).param(tableId));
        for (Map<String, Object> l : links) {
            Map<String, Object> o = SqlRows.one(jdbc.sql("SELECT id, \"orderNumber\", \"totalAmount\", status::text AS status,"
                    + (withCreatedAt ? " \"createdAt\"," : "") + " \"waiterId\", \"billPrintCount\", \"billLastPrintedAt\""
                    + " FROM \"Order\" WHERE id = ?").param(l.get("orderId")));
            l.put("order", o);
        }
        return links;
    }

    private Map<String, Object> decorate(Map<String, Object> t, List<Map<String, Object>> links,
            Map<String, Map<String, Object>> zoneMap) {
        List<Object> ids = new ArrayList<>();
        links.forEach(l -> ids.add(PosSql.map(l.get("order")) == null ? null : PosSql.map(l.get("order")).get("waiterId")));
        Map<String, String> names = waiterNames(ids);
        Map<String, Object> out = new LinkedHashMap<>(t);
        Map<String, Object> zone = zoneMap.get((String) t.get("zone"));
        out.put("zoneName", zone == null ? t.get("zone") : zone.get("name"));
        out.put("zoneColor", zone == null ? null : zone.get("color"));
        List<Map<String, Object>> ord = new ArrayList<>();
        for (Map<String, Object> l : links) {
            Map<String, Object> o = new LinkedHashMap<>(l);
            Map<String, Object> order = PosSql.map(l.get("order"));
            Object wid = order == null ? null : order.get("waiterId");
            o.put("waiterId", wid);
            o.put("waiterName", wid == null ? null : names.get((String) wid));
            ord.add(o);
        }
        out.put("orders", ord);
        return out;
    }

    public List<Map<String, Object>> list(String status, String zone, Boolean active) {
        String orgId = org();
        try {
            reconcileStuckTables(orgId);
        } catch (RuntimeException e) {
            log.warn("table reconcile skipped: {}", e.getMessage());
        }
        Map<String, Map<String, Object>> zoneMap = zones.mapForOrganization(orgId);
        StringBuilder where = new StringBuilder("\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(orgId));
        if (status != null && !status.isEmpty()) {
            where.append(" AND status = ?::\"PosTableStatus\"");
            params.add(status);
        }
        if (zone != null && !zone.isEmpty()) {
            where.append(" AND zone = ?");
            params.add(zone);
        }
        if (active != null) {
            where.append(" AND active = ?");
            params.add(active);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : SqlRows.list(PosTable.class, jdbc.sql("SELECT * FROM \"PosTable\" WHERE " + where
                + " ORDER BY \"sortOrder\" ASC, number ASC").params(params))) {
            List<Map<String, Object>> links = tableOrders(t.get("id"), true, false);
            t.put("orders", links);
            t.put("reservations", db.list(PosTableReservation.class, "\"tableId\" = ? AND status::text IN ('pending', 'seated')"
                    + " ORDER BY \"startAt\" ASC", t.get("id")));
            Map<String, Object> d = decorate(t, links, zoneMap);
            d.put("reservations", t.get("reservations"));
            out.add(reorder(d));
        }
        return out;
    }

    /** Prisma key order: scalars, then {@code orders}, {@code reservations}, (mergedInto), then the computed keys. */
    private static Map<String, Object> reorder(Map<String, Object> d) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : d.entrySet()) {
            if (!List.of("orders", "reservations", "mergedInto", "zoneName", "zoneColor").contains(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        out.put("orders", d.get("orders"));
        out.put("reservations", d.get("reservations"));
        if (d.containsKey("mergedInto")) {
            out.put("mergedInto", d.get("mergedInto"));
        }
        out.put("zoneName", d.get("zoneName"));
        out.put("zoneColor", d.get("zoneColor"));
        return out;
    }

    public Map<String, Object> get(String id) {
        String orgId = org();
        Map<String, Object> table = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", id, orgId);
        if (table == null) {
            throw ApiException.notFound("Table not found");
        }
        List<Map<String, Object>> links = tableOrders(id, false, true);
        Map<String, Object> d = decorate(table, links, zones.mapForOrganization(orgId));
        d.put("reservations", db.list(PosTableReservation.class, "\"tableId\" = ? ORDER BY \"startAt\" DESC LIMIT 50", id));
        d.put("mergedInto", table.get("mergedIntoId") == null ? null
                : SqlRows.one(jdbc.sql("SELECT id, number, name FROM \"PosTable\" WHERE id = ?").param(table.get("mergedIntoId"))));
        return reorder(d);
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = Obj.of("total", 0L, "available", 0L, "occupied", 0L, "reserved", 0L, "out_of_service", 0L);
        for (Map<String, Object> g : SqlRows.list(jdbc.sql("SELECT status::text AS status, count(*) AS n FROM \"PosTable\""
                + " WHERE \"organizationId\" = ? AND active = true GROUP BY status").param(org()))) {
            long n = ((Number) g.get("n")).longValue();
            out.put("total", (long) out.get("total") + n);
            out.put((String) g.get("status"), n);
        }
        long denom = (long) out.get("total") - ((Number) out.get("out_of_service")).longValue();
        long occ = ((Number) out.get("occupied")).longValue() + ((Number) out.get("reserved")).longValue();
        out.put("occupancyPct", denom > 0 ? Math.round((occ / (double) denom) * 100) : 0);
        return out;
    }

    public SseEmitter stream() {
        return hub.connect(null);
    }

    // ─── mutations ───

    private String resolveZoneKey(String orgId, Object requested) {
        zones.ensureDefaults(orgId);
        String r = requested == null ? "" : requested.toString().trim().toLowerCase();
        String wanted = r.isEmpty() ? "indoor" : r;
        Map<String, Object> zone = zones.resolveKey(orgId, wanted);
        if (zone != null) {
            return (String) zone.get("key");
        }
        List<String> available = new ArrayList<>();
        for (Map<String, Object> z : SqlRows.list(jdbc.sql("SELECT key, name FROM \"PosTableZone\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND active = true ORDER BY \"sortOrder\" ASC").param(orgId))) {
            available.add(z.get("key") + " (" + z.get("name") + ")");
        }
        throw ApiException.badRequest("Unknown zone \"" + wanted + "\" — available zones: "
                + (available.isEmpty() ? "none" : String.join(", ", available)));
    }

    public Map<String, Object> create(Map<String, Object> dto) {
        String orgId = org();
        String name = dto.get("name") == null ? "" : dto.get("name").toString().trim();
        if (name.isEmpty()) {
            throw ApiException.badRequest("Table name is required");
        }
        Object number = dto.get("number");
        if (!(number instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            throw ApiException.badRequest("Table number is required");
        }
        String zoneKey = resolveZoneKey(orgId, dto.get("zone"));
        Map<String, Object> created;
        try {
            created = tx.execute(st -> {
                Map<String, Object> t = db.insert(PosTable.class, Obj.of("organizationId", orgId, "name", name,
                        "number", ((Number) number).intValue(), "seats", or(dto.get("seats"), 2), "zone", zoneKey,
                        "shape", or(dto.get("shape"), "square"), "posX", or(dto.get("posX"), 40), "posY", or(dto.get("posY"), 40),
                        "width", or(dto.get("width"), 120), "height", or(dto.get("height"), 120), "notes", dto.get("notes"),
                        "active", or(dto.get("active"), true), "assignedWaiterId", dto.get("assignedWaiterId"),
                        "sortOrder", or(dto.get("sortOrder"), 0), "qrCodeUrl", dto.get("qrCodeUrl")));
                audit.recordInTx(AuditInput.of("PosTable", (String) t.get("id"), "create", null, Obj.of("name", t.get("name"),
                        "number", t.get("number"), "zone", t.get("zone"), "seats", t.get("seats"))));
                return t;
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Table number " + JsFormat.str(num(number)) + " already exists");
        }
        events.publish(DomainEvents.POS_TABLE_CREATED, Obj.of("organizationId", orgId, "tableId", created.get("id"),
                "number", created.get("number"), "name", created.get("name")));
        return created;
    }

    private static Object or(Object v, Object def) {
        return v == null ? def : v;
    }

    public Map<String, Object> update(String id, Map<String, Object> dto) {
        String orgId = org();
        String zoneKey = dto.containsKey("zone") ? resolveZoneKey(orgId, dto.get("zone")) : null;
        Map<String, Object> updated;
        try {
            updated = tx.execute(st -> {
                Map<String, Object> existing = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", id, orgId);
                if (existing == null) {
                    throw ApiException.notFound("Table not found");
                }
                if ("occupied".equals(existing.get("status")) && dto.get("name") != null
                        && !dto.get("name").toString().trim().equals(existing.get("name"))) {
                    throw ApiException.badRequest("Cannot rename an occupied table");
                }
                if (dto.get("seats") != null && num(dto.get("seats")) < num(existing.get("seats"))) {
                    double maxGuests = 0;
                    for (Object g : jdbc.sql("SELECT \"guestCount\" FROM \"PosTableOrder\" WHERE \"tableId\" = ? AND"
                            + " \"closedAt\" IS NULL AND \"organizationId\" = ?").params(id, orgId).query(PosSql.SCALAR).list()) {
                        maxGuests = Math.max(maxGuests, num(g));
                    }
                    if (maxGuests > num(dto.get("seats"))) {
                        throw ApiException.badRequest("Cannot reduce seats to " + JsFormat.str(num(dto.get("seats")))
                                + ": open orders have " + JsFormat.str(maxGuests) + " guests");
                    }
                }
                Map<String, Object> changes = new LinkedHashMap<>();
                for (String f : FIELDS) {
                    if (dto.get(f) != null && !PosTableZonesService.jsEquals(existing.get(f), dto.get(f))) {
                        changes.put(f, Obj.of("from", existing.get(f), "to", dto.get(f)));
                    }
                }
                Map<String, Object> data = new LinkedHashMap<>();
                for (String f : FIELDS) {
                    if (!dto.containsKey(f)) {
                        continue;
                    }
                    if ("zone".equals(f)) {
                        data.put("zone", zoneKey);
                    } else if ("name".equals(f)) {
                        data.put("name", dto.get("name") == null ? null : dto.get("name").toString().trim());
                    } else {
                        Object v = dto.get(f);
                        data.put(f, v instanceof Double d ? (Object) (int) Math.round(d) : v);
                    }
                }
                Map<String, Object> u = db.update(PosTable.class, data, "id = ? AND \"organizationId\" = ?", id, orgId);
                if (!changes.isEmpty()) {
                    audit.recordInTx(AuditInput.of("PosTable", id, "update", changes, Obj.of("updatedBy", user())));
                }
                return u;
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Table number " + (dto.get("number") == null ? "undefined"
                    : JsFormat.str(num(dto.get("number")))) + " already exists");
        }
        events.publish(DomainEvents.POS_TABLE_UPDATED, Obj.of("organizationId", orgId, "tableId", id, "changes",
                PosSql.defined("name", dto.get("name"), "number", dto.get("number"), "seats", dto.get("seats"))));
        return updated;
    }

    public Map<String, Object> archive(String id) {
        String orgId = org();
        Map<String, Object> t = tx.execute(st -> {
            Map<String, Object> existing = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", id, orgId);
            if (existing == null) {
                throw ApiException.notFound("Table not found");
            }
            long open = db.count(PosTableOrder.class, "\"tableId\" = ? AND \"closedAt\" IS NULL AND \"organizationId\" = ?",
                    id, orgId);
            if (open > 0) {
                throw ApiException.conflict("Cannot archive table " + existing.get("number") + ": " + open + " open order(s)");
            }
            Map<String, Object> u = db.update(PosTable.class, Obj.of("active", false), "id = ?", id);
            audit.recordInTx(AuditInput.of("PosTable", id, "delete", null, Obj.of("active", false)));
            return u;
        });
        events.publish(DomainEvents.POS_TABLE_DELETED, Obj.of("organizationId", orgId, "tableId", id));
        return t;
    }

    private void lock(String... ids) {
        List<String> sorted = new ArrayList<>(List.of(ids));
        sorted.sort(null);
        List<Object> params = new ArrayList<>(sorted);
        params.add(org());
        jdbc.sql("SELECT id FROM \"PosTable\" WHERE id IN (" + PosSql.marks(sorted.size()) + ") AND \"organizationId\" = ?"
                + " ORDER BY id FOR UPDATE").params(params).query().listOfRows();
    }

    public Map<String, Object> setStatus(String id, String status, String reason) {
        String orgId = org();
        Object[] r = tx.execute(st -> {
            lock(id);
            Map<String, Object> existing = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", id, orgId);
            if (existing == null) {
                throw ApiException.notFound("Table not found");
            }
            Map<String, Object> updated = db.update(PosTable.class, Obj.of("status", status), "id = ?", id);
            audit.recordInTx(AuditInput.of("PosTable", id, "update", Obj.of("status", existing.get("status")),
                    Obj.of("status", status, "reason", reason)));
            return new Object[] {existing.get("status"), updated};
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) r[1];
        events.publish(DomainEvents.POS_TABLE_STATUS_CHANGED, PosSql.defined("organizationId", orgId, "tableId", id,
                "from", r[0], "to", current.get("status"), "reason", reason));
        return current;
    }

    public Map<String, Object> assignWaiter(String id, String waiterId) {
        String orgId = org();
        return tx.execute(st -> {
            Map<String, Object> existing = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", id, orgId);
            if (existing == null) {
                throw ApiException.notFound("Table not found");
            }
            Map<String, Object> updated = db.update(PosTable.class, Obj.of("assignedWaiterId", waiterId), "id = ?", id);
            audit.recordInTx(AuditInput.of("PosTable", id, "update", Obj.of("assignedWaiterId", existing.get("assignedWaiterId")),
                    Obj.of("assignedWaiterId", waiterId)));
            return updated;
        });
    }

    // ─── merge / transfer ───

    public Map<String, Object> merge(String sourceId, String targetId) {
        if (sourceId.equals(targetId)) {
            throw ApiException.badRequest("A table cannot be merged into itself");
        }
        String orgId = org();
        Object[] r = tx.execute(st -> {
            lock(sourceId, targetId);
            Map<String, Object> source = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", sourceId, orgId);
            Map<String, Object> target = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", targetId, orgId);
            if (source == null) {
                throw ApiException.notFound("Source table not found");
            }
            if (target == null) {
                throw ApiException.notFound("Target table not found");
            }
            if (source.get("mergedIntoId") != null) {
                throw ApiException.conflict("Source table is already merged into T" + source.get("mergedIntoId"));
            }
            if (!Boolean.TRUE.equals(source.get("active")) || !Boolean.TRUE.equals(target.get("active"))) {
                throw ApiException.conflict("Cannot merge an archived table");
            }
            if ("out_of_service".equals(source.get("status")) || "out_of_service".equals(target.get("status"))) {
                throw ApiException.conflict("Cannot merge an out-of-service table");
            }
            List<Map<String, Object>> involved = SqlRows.list(jdbc.sql("SELECT o.status::text AS status, o.\"branchId\","
                    + " o.\"invoiceId\" FROM \"PosTableOrder\" l LEFT JOIN \"Order\" o ON o.id = l.\"orderId\""
                    + " WHERE l.\"tableId\" IN (?, ?) AND l.\"closedAt\" IS NULL AND l.\"organizationId\" = ?")
                    .params(sourceId, targetId, orgId));
            for (Map<String, Object> o : involved) {
                if (o.get("status") != null && (o.get("invoiceId") != null || "closed".equals(o.get("status"))
                        || "cancelled".equals(o.get("status")))) {
                    throw ApiException.conflict("Cannot merge settled tables");
                }
            }
            LinkedHashSet<Object> branches = new LinkedHashSet<>();
            involved.forEach(o -> {
                if (o.get("branchId") != null) {
                    branches.add(o.get("branchId"));
                }
            });
            if (branches.size() > 1) {
                throw ApiException.conflict("Cannot merge tables from different branches");
            }
            List<String> cascaded = jdbc.sql("SELECT id FROM \"PosTable\" WHERE \"mergedIntoId\" = ? AND \"organizationId\" = ?")
                    .params(sourceId, orgId).query(String.class).list();
            List<String> allSource = new ArrayList<>(List.of(sourceId));
            allSource.addAll(cascaded);
            Map<String, Object> targetTab = SqlRows.one(jdbc.sql("SELECT o.id, o.\"invoiceId\", o.status::text AS status FROM"
                    + " \"PosTableOrder\" l JOIN \"Order\" o ON o.id = l.\"orderId\" WHERE l.\"tableId\" = ? AND l.\"closedAt\" IS NULL"
                    + " AND l.\"organizationId\" = ? LIMIT 1").params(targetId, orgId));
            List<Object> srcParams = new ArrayList<>(allSource);
            srcParams.add(orgId);
            String inSrc = "l.\"tableId\" IN (" + PosSql.marks(allSource.size()) + ") AND l.\"closedAt\" IS NULL"
                    + " AND l.\"organizationId\" = ?";
            if (targetTab != null && targetTab.get("invoiceId") == null
                    && !List.of("closed", "cancelled").contains(targetTab.get("status"))) {
                for (Map<String, Object> tab : SqlRows.list(jdbc.sql("SELECT l.id, l.\"orderId\" FROM \"PosTableOrder\" l WHERE "
                        + inSrc).params(srcParams))) {
                    orders.absorbOrderInTx((String) targetTab.get("id"), (String) tab.get("orderId"),
                            "Merged into T" + target.get("number"));
                    db.update(PosTableOrder.class, Obj.of("closedAt", PosDb.raw(NOW)), "id = ?", tab.get("id"));
                }
            }
            List<Map<String, Object>> reassigned = SqlRows.list(jdbc.sql("SELECT l.id, l.\"orderId\" FROM \"PosTableOrder\" l WHERE "
                    + inSrc).params(srcParams));
            List<Object> docIds = new ArrayList<>();
            reassigned.forEach(x -> docIds.add(x.get("orderId")));
            if (!docIds.isEmpty()) {
                for (Map<String, Object> x : reassigned) {
                    db.update(PosTableOrder.class, Obj.of("tableId", targetId), "id = ?", x.get("id"));
                    if (x.get("orderId") != null) {
                        db.updateMany(Order.class, Obj.of("tableId", targetId), "id = ? AND \"organizationId\" = ?",
                                x.get("orderId"), orgId);
                    }
                }
            }
            db.update(PosTable.class, Obj.of("status", "available", "mergedIntoId", targetId, "mergedAt", PosDb.raw(NOW),
                    "mergedById", user()), "id = ?", sourceId);
            for (String c : cascaded) {
                db.update(PosTable.class, Obj.of("mergedIntoId", targetId, "status", "available"), "id = ?", c);
            }
            syncTableStatus(targetId);
            audit.recordInTx(AuditInput.of("PosTable", sourceId, "merge", null, Obj.of("targetId", targetId,
                    "reassignedDocs", docIds, "actorId", user())));
            return new Object[] {source, target, docIds};
        });
        events.publish(DomainEvents.POS_TABLE_MERGED, Obj.of("organizationId", orgId, "sourceId", sourceId, "targetId", targetId,
                "orderIds", r[2], "actorId", user() == null ? "" : user()));
        return Obj.of("sourceId", sourceId, "targetId", targetId, "mergedSource", r[0], "target", r[1],
                "reassignedDocuments", r[2]);
    }

    public Map<String, Object> unmerge(String sourceId) {
        String orgId = org();
        Map<String, Object> t = tx.execute(st -> {
            lock(sourceId);
            Map<String, Object> source = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", sourceId, orgId);
            if (source == null) {
                throw ApiException.notFound("Source table not found");
            }
            if (source.get("mergedIntoId") == null) {
                throw ApiException.badRequest("Table is not merged");
            }
            if (db.count(PosTableOrder.class, "\"tableId\" = ? AND \"closedAt\" IS NULL AND \"organizationId\" = ?",
                    sourceId, orgId) > 0) {
                throw ApiException.conflict("Cannot unmerge: source table still has open orders (transfer first).");
            }
            db.update(PosTable.class, Obj.of("mergedIntoId", null, "mergedAt", null, "mergedById", null), "id = ?", sourceId);
            syncTableStatus(sourceId);
            Map<String, Object> updated = db.first(PosTable.class, "id = ?", sourceId);
            audit.recordInTx(AuditInput.of("PosTable", sourceId, "update", Obj.of("mergedIntoId", source.get("mergedIntoId")),
                    Obj.of("mergedIntoId", null)));
            return updated;
        });
        events.publish(DomainEvents.POS_TABLE_UNMERGED, Obj.of("organizationId", orgId, "tableId", sourceId,
                "actorId", user() == null ? "" : user()));
        return t;
    }

    public Map<String, Object> transfer(String sourceId, String targetId) {
        if (sourceId.equals(targetId)) {
            throw ApiException.badRequest("A table cannot transfer to itself");
        }
        String orgId = org();
        List<Object> docIds = tx.execute(st -> {
            lock(sourceId, targetId);
            Map<String, Object> source = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", sourceId, orgId);
            Map<String, Object> target = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", targetId, orgId);
            if (source == null) {
                throw ApiException.notFound("Source table not found");
            }
            if (target == null) {
                throw ApiException.notFound("Target table not found");
            }
            if (!Boolean.TRUE.equals(source.get("active")) || !Boolean.TRUE.equals(target.get("active"))) {
                throw ApiException.conflict("Cannot transfer to/from an archived table");
            }
            if ("occupied".equals(target.get("status")) || "reserved".equals(target.get("status"))) {
                throw ApiException.conflict("Target table T" + target.get("number") + " is " + target.get("status")
                        + "; cannot transfer into it");
            }
            List<Map<String, Object>> links = SqlRows.list(jdbc.sql("SELECT id, \"orderId\" FROM \"PosTableOrder\" WHERE"
                    + " \"tableId\" = ? AND \"closedAt\" IS NULL AND \"organizationId\" = ?").params(sourceId, orgId));
            List<Object> ids = new ArrayList<>();
            links.forEach(l -> ids.add(l.get("orderId")));
            if (ids.isEmpty()) {
                throw ApiException.badRequest("No open orders on the source table");
            }
            for (Map<String, Object> l : links) {
                db.update(PosTableOrder.class, Obj.of("tableId", targetId), "id = ?", l.get("id"));
                if (l.get("orderId") != null) {
                    db.updateMany(Order.class, Obj.of("tableId", targetId), "id = ? AND \"organizationId\" = ?",
                            l.get("orderId"), orgId);
                }
            }
            syncTableStatus(sourceId);
            syncTableStatus(targetId);
            audit.recordInTx(AuditInput.of("PosTable", sourceId, "transfer", null, Obj.of("targetId", targetId,
                    "documents", ids, "actorId", user())));
            return ids;
        });
        events.publish(DomainEvents.POS_TABLE_TRANSFERRED, Obj.of("organizationId", orgId, "sourceId", sourceId,
                "targetId", targetId, "orderIds", docIds, "actorId", user() == null ? "" : user()));
        return Obj.of("sourceId", sourceId, "targetId", targetId, "transferredDocuments", docIds);
    }

    private List<Map<String, Object>> liveItems(String orderId) {
        List<Map<String, Object>> items = db.list(OrderItem.class, "\"orderId\" = ? AND cancelled = false"
                + " ORDER BY \"lineNumber\" ASC", orderId);
        PosModifiers.attach(jdbc, items, OrderItemModifier.class, "OrderItemModifier", "orderItemId");
        return items;
    }

    private static Map<String, Object> toInput(Map<String, Object> l, double quantity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", l.get("productId"));
        m.put("menuItemId", l.get("menuItemId"));
        m.put("description", l.get("description"));
        m.put("quantity", quantity);
        m.put("unitPrice", num(l.get("unitPrice")));
        m.put("taxId", l.get("taxId"));
        m.put("discountPercent", num(l.get("discountPercent")));
        m.put("taxInclusive", l.get("taxInclusive"));
        return m;
    }

    private static List<Map<String, Object>> modsOf(Map<String, Object> l) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : PosSql.list(l.get("modifiers"))) {
            Map<String, Object> m = PosSql.map(o);
            out.add(Obj.of("modifierId", m.get("modifierId"), "name", m.get("name"), "priceDelta", m.get("priceDelta")));
        }
        return out;
    }

    /** Move selected lines (partial quantities) from this table's draft order into another table's. */
    public Map<String, Object> transferItems(String sourceId, String targetId, List<Map<String, Object>> items) {
        if (sourceId.equals(targetId)) {
            throw ApiException.badRequest("A table cannot transfer items to itself");
        }
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("No items selected to transfer");
        }
        String orgId = org();
        Map<String, Object> res = tx.execute(st -> {
            lock(sourceId, targetId);
            Map<String, Object> source = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", sourceId, orgId);
            Map<String, Object> target = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", targetId, orgId);
            if (source == null) {
                throw ApiException.notFound("Source table not found");
            }
            if (target == null) {
                throw ApiException.notFound("Target table not found");
            }
            if (!Boolean.TRUE.equals(source.get("active")) || !Boolean.TRUE.equals(target.get("active"))) {
                throw ApiException.conflict("Cannot transfer to/from an archived table");
            }
            if ("out_of_service".equals(target.get("status"))) {
                throw ApiException.conflict("Target table T" + target.get("number") + " is out of service");
            }
            Map<String, Object> sourceLink = SqlRows.one(jdbc.sql("SELECT id, \"orderId\" FROM \"PosTableOrder\" WHERE"
                    + " \"tableId\" = ? AND \"closedAt\" IS NULL AND \"organizationId\" = ? LIMIT 1").params(sourceId, orgId));
            Map<String, Object> sourceOrder = sourceLink == null || sourceLink.get("orderId") == null ? null
                    : db.first(Order.class, "id = ?", sourceLink.get("orderId"));
            if (sourceOrder == null || sourceOrder.get("invoiceId") != null || !TableStatus.isHeld(sourceOrder.get("status"))) {
                throw ApiException.badRequest("No open order on the source table");
            }
            Map<String, Double> byLine = new LinkedHashMap<>();
            for (Map<String, Object> it : items) {
                double q = num(it.get("quantity"));
                if (!(q > 0)) {
                    throw ApiException.badRequest("Transfer quantity must be greater than zero");
                }
                byLine.merge((String) it.get("lineId"), q, Double::sum);
            }
            List<Input> remaining = new ArrayList<>();
            List<Input> moved = new ArrayList<>();
            List<Map<String, Object>> movedSummary = new ArrayList<>();
            for (Map<String, Object> l : liveItems((String) sourceOrder.get("id"))) {
                double moveQty = byLine.getOrDefault((String) l.get("id"), 0.0);
                double have = num(l.get("quantity"));
                if (moveQty == 0) {
                    remaining.add(new Input(toInput(l, have), modsOf(l)));
                    continue;
                }
                if (moveQty > have + 1e-6) {
                    throw ApiException.badRequest("Cannot transfer " + JsFormat.str(moveQty) + " of \"" + l.get("description")
                            + "\" — only " + JsFormat.str(have) + " on the order");
                }
                double keep = have - moveQty;
                if (keep > 1e-6) {
                    remaining.add(new Input(toInput(l, keep), modsOf(l)));
                }
                moved.add(new Input(toInput(l, moveQty), modsOf(l)));
                movedSummary.add(Obj.of("description", l.get("description"), "quantity", PosSql.js(moveQty)));
                byLine.remove((String) l.get("id"));
            }
            if (!byLine.isEmpty()) {
                throw ApiException.badRequest("One or more selected items are not on this table's order");
            }
            if (moved.isEmpty()) {
                throw ApiException.badRequest("No items selected to transfer");
            }
            String sourceOrderId = (String) sourceOrder.get("id");
            if (remaining.isEmpty()) {
                jdbc.sql("DELETE FROM \"OrderItem\" WHERE \"orderId\" = ? AND \"organizationId\" = ?").params(sourceOrderId, orgId)
                        .update();
                db.update(Order.class, Obj.of("status", "cancelled", "cancelledAt", PosDb.raw(NOW),
                        "cancelReason", "Items transferred to T" + target.get("number"), "subtotal", 0, "discountTotal", 0,
                        "taxAmount", 0, "totalAmount", 0, "version", PosDb.raw("version + 1")), "id = ?", sourceOrderId);
                db.updateMany(PosTableOrder.class, Obj.of("closedAt", PosDb.raw(NOW)), "id = ?", sourceLink.get("id"));
            } else {
                rebuildOrderItems(sourceOrderId, remaining);
            }
            syncTableStatus(sourceId);
            Map<String, Object> targetLink = SqlRows.one(jdbc.sql("SELECT id, \"orderId\" FROM \"PosTableOrder\" WHERE"
                    + " \"tableId\" = ? AND \"closedAt\" IS NULL AND \"organizationId\" = ? LIMIT 1").params(targetId, orgId));
            Map<String, Object> targetExisting = targetLink == null || targetLink.get("orderId") == null ? null
                    : db.first(Order.class, "id = ?", targetLink.get("orderId"));
            boolean targetOpen = targetExisting != null && targetExisting.get("invoiceId") == null
                    && TableStatus.isHeld(targetExisting.get("status"));
            String targetOrderId;
            if (targetOpen) {
                targetOrderId = (String) targetExisting.get("id");
                List<Input> all = new ArrayList<>();
                for (Map<String, Object> l : liveItems(targetOrderId)) {
                    all.add(new Input(toInput(l, num(l.get("quantity"))), modsOf(l)));
                }
                all.addAll(moved);
                rebuildOrderItems(targetOrderId, all);
            } else {
                Map<String, Object> newOrder = createTabOrder(targetId, (String) sourceOrder.get("partnerId"),
                        (String) target.get("branchId"));
                targetOrderId = (String) newOrder.get("id");
                rebuildOrderItems(targetOrderId, moved);
            }
            if (!"occupied".equals(target.get("status")) && !"reserved".equals(target.get("status"))) {
                db.update(PosTable.class, Obj.of("status", "occupied"), "id = ?", targetId);
            }
            syncTableStatus(targetId);
            audit.recordInTx(AuditInput.of("PosTable", sourceId, "transfer", null, Obj.of("kind", "transfer_items",
                    "targetId", targetId, "items", movedSummary, "actorId", user())));
            Map<String, Object> sourceFresh = db.first(Order.class, "id = ?", sourceOrderId);
            sourceFresh.put("items", db.list(OrderItem.class, "\"orderId\" = ? ORDER BY \"lineNumber\" ASC", sourceOrderId));
            Map<String, Object> targetFresh = db.first(Order.class, "id = ?", targetOrderId);
            targetFresh.put("items", db.list(OrderItem.class, "\"orderId\" = ? ORDER BY \"lineNumber\" ASC", targetOrderId));
            return Obj.of("sourceId", sourceId, "targetId", targetId, "targetOrderId", targetOrderId,
                    "movedSummary", movedSummary, "source", sourceFresh, "target", targetFresh);
        });
        events.publish(DomainEvents.POS_TABLE_TRANSFERRED, Obj.of("organizationId", orgId, "sourceId", sourceId,
                "targetId", targetId, "orderIds", List.of(res.get("targetOrderId")), "actorId", user() == null ? "" : user()));
        return res;
    }

    /** Split one open tab Order into N orders (one per guest check). */
    public Map<String, Object> splitBill(String tableId, String sourceOrderId, List<Map<String, Object>> splits,
            String partnerId) {
        String orgId = org();
        Map<String, Object> res = tx.execute(st -> {
            lock(tableId);
            if (db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", tableId, orgId) == null) {
                throw ApiException.notFound("Table not found");
            }
            Map<String, Object> sourceOrder = db.first(Order.class, "id = ? AND \"organizationId\" = ?", sourceOrderId, orgId);
            if (sourceOrder == null) {
                throw ApiException.notFound("Source order not found");
            }
            if (sourceOrder.get("invoiceId") != null || !TableStatus.isHeld(sourceOrder.get("status"))) {
                throw ApiException.badRequest("Only an open (un-billed) bill can be split — a billed sale cannot be re-split");
            }
            List<Map<String, Object>> srcItems = liveItems(sourceOrderId);
            Map<String, Double> used = new LinkedHashMap<>();
            for (Map<String, Object> split : splits) {
                for (Object o : PosSql.list(split.get("lines"))) {
                    Map<String, Object> ln = PosSql.map(o);
                    used.merge((String) ln.get("sourceItemId"), num(ln.get("quantity")), Double::sum);
                }
            }
            for (Map<String, Object> it : srcItems) {
                double requested = num(it.get("quantity"));
                double allocated = used.getOrDefault((String) it.get("id"), 0.0);
                if (Math.abs(requested - allocated) > 0.0001) {
                    throw ApiException.badRequest("Split quantities do not match source item " + it.get("id") + " (have "
                            + JsFormat.str(allocated) + ", need " + JsFormat.str(requested) + ")");
                }
            }
            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            Map<String, Double> firedRemaining = new HashMap<>();
            Map<String, Double> kotRemaining = new HashMap<>();
            for (Map<String, Object> it : srcItems) {
                byId.put((String) it.get("id"), it);
                firedRemaining.put((String) it.get("id"), num(it.get("kitchenPrintedQty")));
                kotRemaining.put((String) it.get("id"), num(it.get("kotPrintedQty")));
            }
            db.updateMany(PosTableOrder.class, Obj.of("closedAt", PosDb.raw(NOW)), "\"tableId\" = ? AND \"orderId\" = ?"
                    + " AND \"closedAt\" IS NULL AND \"organizationId\" = ?", tableId, sourceOrderId, orgId);
            List<String> created = new ArrayList<>();
            for (Map<String, Object> split : splits) {
                String label = (String) split.get("label");
                List<Map<String, Object>> lines = PosOrdersService.maps(split.get("lines"));
                List<Input> inputs = new ArrayList<>();
                for (Map<String, Object> ln : lines) {
                    Map<String, Object> src = byId.get((String) ln.get("sourceItemId"));
                    if (src == null) {
                        throw ApiException.badRequest("Unknown source item " + ln.get("sourceItemId"));
                    }
                    Map<String, Object> in = toInput(src, num(ln.get("quantity")));
                    in.put("description", src.get("description") + (PosSql.truthy(label) ? " (" + label + ")" : ""));
                    inputs.add(new Input(in, modsOf(src)));
                }
                Map<String, Object> child = db.insert(Order.class, Obj.of("organizationId", orgId,
                        "orderNumber", orders.nextOrderNumber(), "orderType", sourceOrder.get("orderType"), "status", "confirmed",
                        "tableId", tableId, "partnerId", partnerId != null ? partnerId : sourceOrder.get("partnerId"),
                        "waiterId", user(), "branchId", sourceOrder.get("branchId"), "createdBy", user()));
                String childId = (String) child.get("id");
                db.insert(PosTableOrder.class, Obj.of("organizationId", orgId, "tableId", tableId, "orderId", childId,
                        "customerName", label, "notes", "Split from " + sourceOrder.get("orderNumber")));
                rebuildOrderItems(childId, inputs);
                List<Map<String, Object>> childItems = db.list(OrderItem.class, "\"orderId\" = ? ORDER BY \"lineNumber\" ASC",
                        childId);
                for (int ci = 0; ci < childItems.size(); ci++) {
                    Map<String, Object> item = childItems.get(ci);
                    String sid = ci < lines.size() ? (String) lines.get(ci).get("sourceItemId") : null;
                    Map<String, Object> lc = sid == null ? null : byId.get(sid);
                    if (lc == null) {
                        continue;
                    }
                    double left = firedRemaining.getOrDefault(sid, 0.0);
                    double claimed = Math.min(num(item.get("quantity")), left);
                    firedRemaining.put(sid, left - claimed);
                    double kotLeft = kotRemaining.getOrDefault(sid, 0.0);
                    double kotClaimed = Math.min(num(item.get("quantity")), kotLeft);
                    kotRemaining.put(sid, kotLeft - kotClaimed);
                    db.update(OrderItem.class, Obj.of(
                            "kitchenPrintCount", claimed > 0 ? lc.get("kitchenPrintCount") : 0,
                            "kitchenLastPrintedAt", claimed > 0 ? lc.get("kitchenLastPrintedAt") : null,
                            "kitchenPrintedQty", claimed > 0 ? PosSql.decOf(claimed) : null,
                            "kotPrintedQty", PosSql.decOf(kotClaimed),
                            "cancelPrintCount", lc.get("cancelPrintCount"), "cancelLastPrintedAt", lc.get("cancelLastPrintedAt"),
                            "lastKitchenPrintedById", claimed > 0 ? lc.get("lastKitchenPrintedById") : null,
                            "kitchenStatus", claimed > 0 ? (lc.get("kitchenStatus") == null ? "pending" : lc.get("kitchenStatus"))
                                    : "pending"), "id = ?", item.get("id"));
                }
                created.add(childId);
            }
            workflows.transition("order", sourceOrderId, "supersede", Obj.of("splitInto", created), null);
            db.update(Order.class, Obj.of("cancelReason", "Split into " + created.size() + " ticket(s)",
                    "version", PosDb.raw("version + 1")), "id = ?", sourceOrderId);
            syncTableStatus(tableId);
            audit.recordInTx(AuditInput.of("PosTable", tableId, "update", null, Obj.of("kind", "split",
                    "sourceOrderId", sourceOrderId, "newOrderIds", created, "actorId", user())));
            return Obj.of("sourceOrderId", sourceOrderId, "newOrderIds", created);
        });
        events.publish(DomainEvents.POS_TABLE_SPLIT, Obj.of("organizationId", orgId, "sourceOrderId", sourceOrderId,
                "newOrderIds", res.get("newOrderIds"), "actorId", user() == null ? "" : user()));
        return res;
    }

    // ─── links ───

    public Map<String, Object> attachSaleToTable(String tableId, Object orderId, Object customerName, Object guestCount) {
        String orgId = org();
        return tx.execute(st -> {
            lock(tableId);
            Map<String, Object> table = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", tableId, orgId);
            if (table == null) {
                throw ApiException.notFound("Table not found");
            }
            if ("out_of_service".equals(table.get("status"))) {
                throw ApiException.conflict("Table is out of service");
            }
            if (orderId == null) {
                throw new IllegalStateException("Argument `orderId` is missing.");
            }
            Integer guests = guestCount == null ? null : (int) num(guestCount);
            jdbc.sql("INSERT INTO \"PosTableOrder\" (id, \"organizationId\", \"tableId\", \"orderId\", \"customerName\","
                    + " \"guestCount\", \"openedAt\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW
                    + ", " + NOW + ") ON CONFLICT (\"tableId\", \"orderId\") DO UPDATE SET \"customerName\" = EXCLUDED.\"customerName\","
                    + " \"guestCount\" = EXCLUDED.\"guestCount\", \"updatedAt\" = " + NOW)
                    .params(java.util.UUID.randomUUID().toString(), orgId, tableId, orderId, customerName, guests).update();
            db.update(Order.class, Obj.of("tableId", tableId), "id = ? AND \"organizationId\" = ?", orderId, orgId);
            syncTableStatus(tableId);
            return Obj.of("tableId", tableId, "orderId", orderId);
        });
    }

    /** Close the open link(s) of a table after payment; returns {closed, tableStatus}. */
    public Map<String, Object> closeTableOrder(String tableId, String orderId) {
        String orgId = org();
        return tx.execute(st -> {
            lock(tableId);
            if (db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", tableId, orgId) == null) {
                throw ApiException.notFound("Table not found");
            }
            int closed = orderId == null
                    ? db.updateMany(PosTableOrder.class, Obj.of("closedAt", PosDb.raw(NOW)), "\"tableId\" = ? AND"
                            + " \"closedAt\" IS NULL AND \"organizationId\" = ?", tableId, orgId)
                    : db.updateMany(PosTableOrder.class, Obj.of("closedAt", PosDb.raw(NOW)), "\"tableId\" = ? AND"
                            + " \"orderId\" = ? AND \"closedAt\" IS NULL AND \"organizationId\" = ?", tableId, orderId, orgId);
            return Obj.of("closed", closed, "tableStatus", syncTableStatus(tableId));
        });
    }

    static boolean same(Object a, Object b) {
        return Objects.equals(a, b);
    }

    static BigDecimal zero() {
        return BigDecimal.ZERO;
    }
}
