package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.pos.domain.KitchenTicket;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/** Kitchen display tickets (port of {@code pos-kds.service.ts}, {@code kds-cancellation.subscriber.ts}). */
@Service
public class PosKdsService {

    private static final Logger log = LoggerFactory.getLogger(PosKdsService.class);
    private static final Map<String, List<String>> ALLOWED = Map.of(
            "new", List.of("preparing", "cancelled"),
            "preparing", List.of("ready", "cancelled"),
            "ready", List.of("served", "preparing", "cancelled"),
            "served", List.of(),
            "cancelled", List.of());

    private final PosDb db;
    private final JdbcClient jdbc;
    private final EventBus events;
    private final AuditService audit;
    private final SequenceService sequence;
    private final JsonMapper mapper;
    private final ScheduledExecutorService streamPool = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "kds-stream");
        t.setDaemon(true);
        return t;
    });

    public PosKdsService(PosDb db, EventBus events, AuditService audit, SequenceService sequence, JsonMapper mapper) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.events = events;
        this.audit = audit;
        this.sequence = sequence;
        this.mapper = mapper;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe(DomainEvents.POS_ORDER_CANCELLED, p -> {
            String orgId = p.get("organizationId") == null ? null : p.get("organizationId").asString();
            String orderId = p.get("orderId") == null ? null : p.get("orderId").asString();
            String reason = p.get("reason") == null || p.get("reason").isNull() ? null : p.get("reason").asString();
            if (orgId == null || orderId == null) {
                return;
            }
            TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
                try {
                    int n = cancelTicketsForOrder(orderId, reason == null ? "Order cancelled" : reason);
                    if (n > 0) {
                        log.info("Cancelled {} kitchen ticket(s) for order {}", n, orderId);
                    }
                } catch (RuntimeException e) {
                    log.error("Failed to cancel kitchen tickets for order {}: {}", orderId, e.toString());
                }
            });
        });
    }

    @PreDestroy
    void stop() {
        streamPool.shutdownNow();
    }

    private String nextTicketNo() {
        String ymd = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate().toString().replace("-", "");
        try {
            return sequence.next("kds_ticket:" + ymd, "K-", 3);
        } catch (RuntimeException e) {
            log.warn("KDS ticket number allocation failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * One ticket per station; {@code inTx} = called inside the caller's transaction (no events, like Nest's
     * {@code tx} path).
     */
    public List<String> createTicketsForSale(String invoiceId, String orderId, String label, String orderType,
            String priority, List<Map<String, Object>> items, boolean inTx) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            groups.computeIfAbsent(String.valueOf(it.get("station")), k -> new ArrayList<>()).add(it);
        }
        String orgId = org();
        List<String[]> created = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> g : groups.entrySet()) {
            String ticketNo = nextTicketNo();
            Map<String, Object> t = db.insert(KitchenTicket.class, Obj.of("organizationId", orgId, "invoiceId", invoiceId,
                    "orderId", orderId, "label", label, "ticketNo", ticketNo.isEmpty() ? null : ticketNo,
                    "station", g.getKey(), "status", "new", "priority", priority == null ? "normal" : priority,
                    "orderType", orderType, "items", PosDb.json(g.getValue())));
            created.add(new String[] {(String) t.get("id"), g.getKey()});
        }
        if (!inTx) {
            for (String[] c : created) {
                events.publish("pos.kds.ticket_created", Obj.of("organizationId", orgId, "ticketId", c[0], "station", c[1]));
            }
        }
        return created.stream().map(c -> c[0]).toList();
    }

    /** Cancel every still-active ticket of an order (F11). */
    public int cancelTicketsForOrder(String orderId, String reason) {
        String orgId = org();
        List<Map<String, Object>> active = SqlRows.list(jdbc.sql("SELECT id, station FROM \"KitchenTicket\" WHERE"
                + " \"orderId\" = ? AND \"organizationId\" = ? AND status::text IN ('new', 'preparing', 'ready')")
                .params(orderId, orgId));
        if (active.isEmpty()) {
            return 0;
        }
        for (Map<String, Object> t : active) {
            db.updateMany(KitchenTicket.class, Obj.of("status", "cancelled",
                    "recallReason", reason == null ? "Order cancelled" : reason), "id = ? AND \"organizationId\" = ?",
                    t.get("id"), orgId);
        }
        for (Map<String, Object> t : active) {
            events.publish("pos.kds.ticket_updated", Obj.of("organizationId", orgId, "ticketId", t.get("id"),
                    "station", t.get("station")));
        }
        audit.record(AuditInput.of("Order", orderId, "cancel", null, Obj.of("kind", "kds_cancel",
                "tickets", active.size(), "reason", reason)));
        return active.size();
    }

    /** A-016: pull one voided line off the board ({@code quantity} null = whole line). */
    public int cancelOrderItemTickets(String orderId, String orderItemId, Double quantity, String reason) {
        String orgId = org();
        List<Map<String, Object>> active = SqlRows.list(jdbc.sql("SELECT id, station, items FROM \"KitchenTicket\""
                + " WHERE \"orderId\" = ? AND \"organizationId\" = ? AND status::text IN ('new', 'preparing', 'ready')")
                .params(orderId, orgId));
        if (active.isEmpty()) {
            return 0;
        }
        double remaining = quantity == null ? Double.POSITIVE_INFINITY : quantity;
        int touched = 0;
        for (Map<String, Object> ticket : active) {
            if (remaining <= 0) {
                break;
            }
            Object itemsRaw = PosSql.java(ticket.get("items"));
            List<Object> items = itemsRaw instanceof List<?> l ? PosSql.list(l) : new ArrayList<>();
            boolean changed = false;
            List<Object> next = new ArrayList<>();
            for (Object o : items) {
                Map<String, Object> it = PosSql.map(o);
                if (remaining <= 0 || it == null || PosSql.truthy(it.get("cancelled"))) {
                    next.add(o);
                    continue;
                }
                boolean isMatch = PosSql.truthy(it.get("orderItemId")) && orderItemId.equals(it.get("orderItemId"));
                if (!isMatch) {
                    next.add(o);
                    continue;
                }
                double have = PosSql.num(it.get("quantity"));
                double take = Math.min(have, remaining);
                if (!(take > 0)) {
                    next.add(o);
                    continue;
                }
                remaining -= take;
                changed = true;
                double left = have - take;
                Map<String, Object> n = new LinkedHashMap<>(it);
                n.put("quantity", left > 0.000001 ? PosSql.js(left) : 0);
                if (!(left > 0.000001)) {
                    n.put("cancelled", true);
                }
                n.put("cancelledQty", PosSql.js(PosSql.num(it.get("cancelledQty")) + take));
                n.put("cancelReason", reason);
                next.add(n);
            }
            if (!changed) {
                continue;
            }
            boolean anyActive = next.stream().map(PosSql::map).anyMatch(it -> it != null
                    && !PosSql.truthy(it.get("cancelled")) && PosSql.num(it.get("quantity")) > 0.000001);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", PosDb.json(next));
            if (!anyActive) {
                data.put("status", "cancelled");
            }
            data.put("recallReason", reason);
            db.update(KitchenTicket.class, data, "id = ? AND \"organizationId\" = ?", ticket.get("id"), orgId);
            events.publish("pos.kds.ticket_updated", Obj.of("organizationId", orgId, "ticketId", ticket.get("id"),
                    "station", ticket.get("station")));
            touched++;
        }
        if (touched > 0) {
            audit.record(AuditInput.of("Order", orderId, "cancel", null, Obj.of("kind", "kds_item_cancel",
                    "orderItemId", orderItemId, "quantity", quantity == null ? null : PosSql.js(quantity),
                    "reason", reason, "tickets", touched)));
        }
        return touched;
    }

    /** Pre-payment "Send To Kitchen" from cart items. */
    public List<String> createTicketsFromCart(String label, String tableId, List<Map<String, Object>> items) {
        String orgId = org();
        if (tableId != null && !tableId.isEmpty()) {
            String orderId = jdbc.sql("SELECT \"orderId\" FROM \"PosTableOrder\" WHERE \"tableId\" = ? AND"
                            + " \"organizationId\" = ? AND \"closedAt\" IS NULL LIMIT 1")
                    .params(tableId, orgId).query(String.class).optional().orElse(null);
            if (orderId != null) {
                Set<String> printed = new HashSet<>(jdbc.sql("SELECT \"productId\" FROM \"OrderItem\" WHERE \"orderId\" = ?"
                        + " AND \"kitchenPrintCount\" > 0").param(orderId).query(String.class).list());
                if (!printed.isEmpty()) {
                    List<String> already = new ArrayList<>();
                    for (Map<String, Object> it : items) {
                        if (printed.contains((String) it.get("productId"))) {
                            already.add(String.valueOf(it.get("productName")));
                        }
                    }
                    if (!already.isEmpty()) {
                        throw ApiException.badRequest("KOT cannot be reprinted. Items already sent to kitchen for table: "
                                + String.join(", ", already) + ". Use the order panel to add new items.");
                    }
                }
            }
        }
        Map<String, String> stationMap = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            String pid = (String) it.get("productId");
            if (!stationMap.containsKey(pid)) {
                String st = jdbc.sql("SELECT station FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL").params(pid, orgId).query(String.class).optional().orElse(null);
                if (st != null) {
                    stationMap.put(pid, st);
                }
            }
        }
        List<Map<String, Object>> kds = new ArrayList<>();
        for (Map<String, Object> it : items) {
            List<Map<String, Object>> mods = new ArrayList<>();
            for (Object o : PosSql.list(it.get("modifiers"))) {
                Map<String, Object> m = PosSql.map(o);
                // Nest: { name, kitchenPrintName ?? null, priceDelta: Number(x) } — NaN serializes as null.
                mods.add(Obj.of("name", m.get("name"), "kitchenPrintName", m.get("kitchenPrintName"),
                        "priceDelta", m.get("priceDelta") == null ? null : PosSql.js(m.get("priceDelta"))));
            }
            double qty = it.get("quantity") == null ? 1 : PosSql.num(it.get("quantity"));
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("productId", it.get("productId"));
            k.put("productName", it.get("productName"));
            k.put("quantity", PosSql.js(Math.max(1, qty)));
            k.put("modifiers", mods);
            k.put("notes", it.get("notes"));
            k.put("station", stationMap.getOrDefault((String) it.get("productId"), "cafe"));
            if (it.get("variantName") != null) {
                k.put("variantName", it.get("variantName"));
            }
            if (it.get("accompanimentNames") != null) {
                k.put("accompanimentNames", it.get("accompanimentNames"));
            }
            kds.add(k);
        }
        return createTicketsForSale(null, null, label, null, null, kds, false);
    }

    public List<Map<String, Object>> listTickets(String station, String status) {
        String orgId = org();
        String stationWhere = station != null && !station.isEmpty() ? " AND station = ?" : "";
        List<Object> base = new ArrayList<>(List.of(orgId));
        if (!stationWhere.isEmpty()) {
            base.add(station);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            List<Object> p = new ArrayList<>(base);
            p.add(status);
            for (Map<String, Object> t : SqlRows.list(KitchenTicket.class, jdbc.sql("SELECT * FROM \"KitchenTicket\""
                    + " WHERE \"organizationId\" = ?" + stationWhere + " AND status = ?::\"KdsTicketStatus\""
                    + " ORDER BY \"createdAt\" DESC LIMIT 500").params(p))) {
                out.add(serialize(t));
            }
            return out;
        }
        for (Map<String, Object> t : SqlRows.list(KitchenTicket.class, jdbc.sql("SELECT * FROM \"KitchenTicket\""
                + " WHERE \"organizationId\" = ?" + stationWhere + " AND status::text IN ('new', 'preparing', 'ready')"
                + " ORDER BY priority DESC, \"createdAt\" ASC").params(base))) {
            out.add(serialize(t));
        }
        for (Map<String, Object> t : SqlRows.list(KitchenTicket.class, jdbc.sql("SELECT * FROM \"KitchenTicket\""
                + " WHERE \"organizationId\" = ?" + stationWhere + " AND status::text IN ('served', 'cancelled')"
                + " ORDER BY \"updatedAt\" DESC LIMIT 50").params(base))) {
            out.add(serialize(t));
        }
        return out;
    }

    private Map<String, Object> find(String id) {
        Map<String, Object> t = db.first(KitchenTicket.class, "id = ? AND \"organizationId\" = ?", id, org());
        if (t == null) {
            throw ApiException.notFound("KDS ticket not found");
        }
        return t;
    }

    public Map<String, Object> getTicket(String id) {
        return serialize(find(id));
    }

    public Map<String, Object> transition(String id, String action, String reason) {
        String orgId = org();
        Map<String, Object> t = find(id);
        String userId = user();
        Map<String, Object> data = new LinkedHashMap<>();
        String nextStatus;
        switch (action == null ? "" : action) {
            case "start" -> {
                nextStatus = "preparing";
                data.put("startedAt", PosDb.raw(NOW));
                data.put("startedBy", userId);
            }
            case "ready" -> {
                nextStatus = "ready";
                data.put("readyAt", PosDb.raw(NOW));
                data.put("readyBy", userId);
            }
            case "serve" -> {
                nextStatus = "served";
                data.put("servedAt", PosDb.raw(NOW));
            }
            case "cancel" -> nextStatus = "cancelled";
            case "recall" -> {
                if (!"ready".equals(t.get("status"))) {
                    throw ApiException.badRequest("Only a ready ticket can be recalled");
                }
                nextStatus = "preparing";
                data.put("readyAt", null);
                data.put("readyBy", null);
                data.put("recallCount", PosDb.raw("\"recallCount\" + 1"));
                data.put("recallReason", reason);
            }
            default -> throw ApiException.badRequest("Invalid KDS action");
        }
        if (!ALLOWED.getOrDefault(String.valueOf(t.get("status")), List.of()).contains(nextStatus)) {
            throw ApiException.badRequest("Cannot " + action + " a " + t.get("status") + " ticket");
        }
        data.put("status", nextStatus);
        Map<String, Object> updated = db.update(KitchenTicket.class, data, "id = ? AND \"organizationId\" = ?", id, orgId);
        if (t.get("orderId") != null) {
            Map<String, Object> fact = Obj.of("organizationId", orgId, "orderId", t.get("orderId"), "strategy", "kitchen",
                    "documentType", "kitchen_ticket", "documentId", id, "station", t.get("station"));
            if (reason != null && !reason.isEmpty()) {
                fact.put("reason", reason);
            }
            if ("start".equals(action)) {
                events.publish(DomainEvents.FULFILLMENT_STARTED, fact);
            } else if ("ready".equals(nextStatus) || "served".equals(nextStatus)) {
                Map<String, Object> f = new LinkedHashMap<>(fact);
                f.put("kdsStatus", nextStatus);
                events.publish(DomainEvents.FULFILLMENT_COMPLETED, f);
            }
        }
        Map<String, Object> nv = Obj.of("kdsAction", action, "status", nextStatus);
        if (reason != null && !reason.isEmpty()) {
            nv.put("reason", reason);
        }
        audit.record(AuditInput.of("KitchenTicket", id, "update", null, nv));
        events.publish("pos.kds.ticket_updated", Obj.of("organizationId", orgId, "ticketId", id, "action", action,
                "status", nextStatus));
        return serialize(updated);
    }

    public Map<String, Object> bulkTransition(List<String> ids, String action, String reason) {
        List<Map<String, Object>> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String id : ids) {
            try {
                updated.add(transition(id, action, reason));
            } catch (RuntimeException e) {
                skipped.add(id);
            }
        }
        return Obj.of("updated", updated, "skipped", skipped);
    }

    public Map<String, Object> setPriority(String id, String priority) {
        Map<String, Object> t = find(id);
        Map<String, Object> updated = db.update(KitchenTicket.class, Obj.of("priority", priority),
                "id = ? AND \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of("KitchenTicket", id, "update", null, Obj.of("priority", priority)));
        events.publish("pos.kds.ticket_updated", Obj.of("organizationId", org(), "ticketId", id, "action", "priority",
                "status", t.get("status")));
        return serialize(updated);
    }

    public Map<String, Object> assignChef(String id, String chefUserId) {
        Map<String, Object> t = find(id);
        Map<String, Object> updated = db.update(KitchenTicket.class, Obj.of("assignedTo", chefUserId),
                "id = ? AND \"organizationId\" = ?", id, org());
        events.publish("pos.kds.ticket_updated", Obj.of("organizationId", org(), "ticketId", id, "action", "assign",
                "status", t.get("status")));
        return serialize(updated);
    }

    /** SSE: {@code retry: 5000}, then a snapshot every second (Nest polls at 1 s), scoped to this tenant. */
    public SseEmitter streamTickets(String station) {
        TenantContext.TenantStore store = TenantContext.current().orElseThrow();
        SseEmitter emitter = new SseEmitter(0L);
        final ScheduledFuture<?>[] task = new ScheduledFuture<?>[1];
        Runnable stop = () -> {
            if (task[0] != null) {
                task[0].cancel(false);
            }
        };
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(e -> stop.run());
        try {
            emitter.send(SseEmitter.event().reconnectTime(5000));
        } catch (IOException e) {
            return emitter;
        }
        Runnable send = () -> {
            String payload;
            try {
                List<Map<String, Object>> tickets = TenantContext.run(store, () -> listTickets(station, null));
                payload = mapper.writeValueAsString(Obj.of("type", "snapshot", "tickets", tickets));
            } catch (RuntimeException e) {
                log.warn("KDS stream ticket fetch failed: {}", e.getMessage());
                return;
            }
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().data(payload, MediaType.TEXT_PLAIN));
                }
            } catch (IOException | IllegalStateException e) {
                stop.run();
            }
        };
        send.run();
        task[0] = streamPool.scheduleWithFixedDelay(send, 1, 1, TimeUnit.SECONDS);
        return emitter;
    }

    Map<String, Object> serialize(Map<String, Object> t) {
        Object items = PosSql.java(t.get("items"));
        return Obj.of("id", t.get("id"), "invoiceId", t.get("invoiceId"), "label", t.get("label"),
                "ticketNo", t.get("ticketNo"), "station", t.get("station"), "status", t.get("status"),
                "priority", t.get("priority") == null ? "normal" : t.get("priority"), "orderType", t.get("orderType"),
                "items", items instanceof List<?> ? items : List.of(),
                "startedAt", iso(t.get("startedAt")), "readyAt", iso(t.get("readyAt")), "servedAt", iso(t.get("servedAt")),
                "startedBy", t.get("startedBy"), "readyBy", t.get("readyBy"), "assignedTo", t.get("assignedTo"),
                "recallCount", t.get("recallCount") == null ? 0 : t.get("recallCount"),
                "recallReason", t.get("recallReason"), "createdAt", iso(t.get("createdAt")),
                "updatedAt", iso(t.get("updatedAt")));
    }

    private static String iso(Object v) {
        return v instanceof Instant i ? JsonConfig.toJsIso(i) : null;
    }

    // ─── reports ───

    private static double mins(Instant a, Instant b) {
        return (a.toEpochMilli() - b.toEpochMilli()) / 60000.0;
    }

    private static double percentile(List<Double> sortedAsc, int p) {
        if (sortedAsc.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.min(sortedAsc.size() - 1, Math.max(0, Math.ceil((p / 100.0) * sortedAsc.size()) - 1));
        return Math.round(sortedAsc.get(idx) * 10) / 10.0;
    }

    private static double avg(List<Double> xs) {
        if (xs.isEmpty()) {
            return 0;
        }
        double s = 0;
        for (double x : xs) {
            s += x;
        }
        return Math.round((s / xs.size()) * 10) / 10.0;
    }

    /** KDS performance over a range (default last 7 days). */
    public Map<String, Object> summary(String fromIso, String toIso) {
        String orgId = org();
        Instant to = toIso != null && !toIso.isEmpty() ? PosSql.date(toIso) : Instant.now();
        Instant from = fromIso != null && !fromIso.isEmpty() ? PosSql.date(fromIso)
                : to.minusMillis(7L * 24 * 3600 * 1000);
        List<Map<String, Object>> tickets = SqlRows.list(KitchenTicket.class, jdbc.sql("SELECT * FROM \"KitchenTicket\""
                + " WHERE \"organizationId\" = ? AND \"createdAt\" >= ? AND \"createdAt\" <= ? ORDER BY \"createdAt\" ASC")
                .params(orgId, SqlRows.ts(from), SqlRows.ts(to)));
        List<Double> prepTimes = new ArrayList<>();
        List<Double> waitTimes = new ArrayList<>();
        Map<String, Object[]> perStation = new LinkedHashMap<>();
        Map<String, Object[]> perChef = new LinkedHashMap<>();
        Map<String, Integer> recallReasons = new LinkedHashMap<>();
        int[] hours = new int[24];
        Map<String, Double> dishes = new LinkedHashMap<>();
        int delayed = 0;
        int cancelled = 0;
        int recalls = 0;
        for (Map<String, Object> t : tickets) {
            Instant created = (Instant) t.get("createdAt");
            hours[created.atOffset(ZoneOffset.UTC).getHour()]++;
            if ("cancelled".equals(t.get("status"))) {
                cancelled++;
            }
            if (PosSql.intOf(t.get("recallCount")) > 0) {
                recalls++;
                String r = t.get("recallReason") == null ? "unspecified" : (String) t.get("recallReason");
                recallReasons.merge(r, 1, Integer::sum);
            }
            Object[] st = perStation.computeIfAbsent((String) t.get("station"), k -> new Object[] {0, new ArrayList<Double>()});
            st[0] = (int) st[0] + 1;
            Double prep = null;
            if (t.get("startedAt") != null && t.get("readyAt") != null) {
                prep = mins((Instant) t.get("readyAt"), (Instant) t.get("startedAt"));
                if (prep >= 0) {
                    prepTimes.add(prep);
                    @SuppressWarnings("unchecked")
                    List<Double> sp = (List<Double>) st[1];
                    sp.add(prep);
                    if (prep > 10) {
                        delayed++;
                    }
                }
            }
            if (t.get("servedAt") != null) {
                double w = mins((Instant) t.get("servedAt"), created);
                if (w >= 0) {
                    waitTimes.add(w);
                }
            }
            String chef = t.get("readyBy") != null ? (String) t.get("readyBy") : (String) t.get("startedBy");
            if (chef != null && prep != null) {
                Object[] c = perChef.computeIfAbsent(chef, k -> new Object[] {0, new ArrayList<Double>()});
                c[0] = (int) c[0] + 1;
                @SuppressWarnings("unchecked")
                List<Double> cp = (List<Double>) c[1];
                cp.add(prep);
            }
            Object items = PosSql.java(t.get("items"));
            if (items instanceof List<?> l) {
                for (Object o : l) {
                    Map<String, Object> it = PosSql.map(o);
                    String name = it == null || it.get("productName") == null ? "Unknown" : it.get("productName").toString();
                    double q = it == null ? 0 : PosSql.num(it.get("quantity"));
                    dishes.merge(name, q == 0 || Double.isNaN(q) ? 1 : q, Double::sum);
                }
            }
        }
        List<Double> prepSorted = new ArrayList<>(prepTimes);
        prepSorted.sort(Double::compare);
        List<Double> waitSorted = new ArrayList<>(waitTimes);
        waitSorted.sort(Double::compare);
        List<Map<String, Object>> stations = new ArrayList<>();
        perStation.forEach((k, v) -> stations.add(Obj.of("station", k, "tickets", v[0],
                "avgPrep", PosSql.js(avg(castList(v[1]))))));
        stations.sort((a, b) -> (int) b.get("tickets") - (int) a.get("tickets"));
        List<Map<String, Object>> chefs = new ArrayList<>();
        perChef.forEach((k, v) -> chefs.add(Obj.of("chefUserId", k, "tickets", v[0],
                "avgPrep", PosSql.js(avg(castList(v[1]))))));
        chefs.sort((a, b) -> (int) b.get("tickets") - (int) a.get("tickets"));
        List<Map<String, Object>> causes = new ArrayList<>();
        recallReasons.forEach((k, v) -> causes.add(Obj.of("reason", k, "count", v)));
        causes.sort((a, b) -> (int) b.get("count") - (int) a.get("count"));
        List<Map<String, Object>> peak = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            peak.add(Obj.of("hour", h, "count", hours[h]));
        }
        List<Map<String, Object>> top = new ArrayList<>();
        dishes.forEach((k, v) -> top.add(Obj.of("name", k, "qty", PosSql.js(v))));
        top.sort((a, b) -> Double.compare(PosSql.num(b.get("qty")), PosSql.num(a.get("qty"))));
        return Obj.of("range", Obj.of("from", JsonConfig.toJsIso(from), "to", JsonConfig.toJsIso(to)),
                "totals", Obj.of("tickets", tickets.size(), "cancelled", cancelled, "recalls", recalls, "delayed", delayed),
                "prepTime", Obj.of("avg", PosSql.js(avg(prepTimes)), "p50", PosSql.js(percentile(prepSorted, 50)),
                        "p90", PosSql.js(percentile(prepSorted, 90)), "samples", prepTimes.size()),
                "waitTime", Obj.of("avg", PosSql.js(avg(waitTimes)), "p50", PosSql.js(percentile(waitSorted, 50)),
                        "p90", PosSql.js(percentile(waitSorted, 90)), "samples", waitTimes.size()),
                "perStation", stations, "perChef", chefs, "recallCauses", causes, "peakHours", peak,
                "topDishes", top.size() > 15 ? new ArrayList<>(top.subList(0, 15)) : top);
    }

    @SuppressWarnings("unchecked")
    private static List<Double> castList(Object o) {
        return (List<Double>) o;
    }

    /** Live operational snapshot. */
    public Map<String, Object> live() {
        List<Map<String, Object>> active = SqlRows.list(KitchenTicket.class, jdbc.sql("SELECT * FROM \"KitchenTicket\""
                + " WHERE \"organizationId\" = ? AND status::text IN ('new', 'preparing', 'ready') ORDER BY \"createdAt\" ASC")
                .param(org()));
        long now = System.currentTimeMillis();
        Map<String, Object> byStatus = Obj.of("new", 0, "preparing", 0, "ready", 0);
        Map<String, Integer> perStation = new LinkedHashMap<>();
        double longest = 0;
        List<Double> ages = new ArrayList<>();
        for (Map<String, Object> t : active) {
            String s = (String) t.get("status");
            byStatus.put(s, (int) byStatus.get(s) + 1);
            perStation.merge((String) t.get("station"), 1, Integer::sum);
            double age = (now - ((Instant) t.get("createdAt")).toEpochMilli()) / 60000.0;
            ages.add(age);
            if (("new".equals(s) || "preparing".equals(s)) && age > longest) {
                longest = age;
            }
        }
        List<Map<String, Object>> stations = new ArrayList<>();
        perStation.forEach((k, v) -> stations.add(Obj.of("station", k, "count", v)));
        stations.sort((a, b) -> (int) b.get("count") - (int) a.get("count"));
        return Obj.of("counts", byStatus, "totalActive", active.size(), "avgAgeMin", PosSql.js(avg(ages)),
                "longestWaitMin", PosSql.js(Math.round(longest * 10) / 10.0), "perStation", stations);
    }
}
