package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.lossprevention.application.LpCommands.EventInput;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Projects this ERP's own records into the event ledger (ADR-S077 §4). It only reads POS, cash, kitchen, beverage and
 * stock tables and writes ledger events through the same ingest as an outside sender.
 *
 * <p>Each source is swept by a cursor on its timestamp, re-reading a short overlap so a row committed late is not
 * missed. A row read twice is stored once: its {@code sourceEventId} is derived from the row.
 */
@Service
public class LpProjectionService {

    private static final Logger log = LoggerFactory.getLogger(LpProjectionService.class);

    public static final String SOURCE = "erp-pos";
    static final int PAGE = 500;
    static final Duration OVERLAP = Duration.ofMinutes(5);

    public static final String SALES = "sales";
    public static final String VOIDS = "voids";
    public static final String CANCELLATIONS = "cancellations";
    public static final String REFUNDS = "refunds";
    public static final String OVERRIDES = "overrides";
    public static final String CASH_SESSIONS = "cash_sessions";
    public static final String KITCHEN = "kitchen";
    public static final String STOCK_COUNTS = "stock_counts";
    public static final List<String> SOURCES = List.of(SALES, VOIDS, CANCELLATIONS, REFUNDS, OVERRIDES,
            CASH_SESSIONS, KITCHEN, STOCK_COUNTS);

    private final JdbcClient jdbc;
    private final LpLedgerService ledger;
    private final LpSettingsService settings;
    private final JsonMapper mapper;
    private final Clock clock = Clock.systemUTC();

    public LpProjectionService(JdbcClient jdbc, LpLedgerService ledger, LpSettingsService settings,
            JsonMapper mapper) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.settings = settings;
        this.mapper = mapper;
    }

    /** Sweeps every source for the caller's organization. One failing source does not stop the others. */
    public Map<String, Object> sweepAll() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String source : SOURCES) {
            out.put(source, sweep(source));
        }
        return out;
    }

    /** Sweeps one source from its cursor. Returns what was read and stored, or the error. */
    public Map<String, Object> sweep(String source) {
        try {
            String org = LpScope.org();
            Instant start = jdbc.sql("SELECT \"cursorAt\" FROM \"LpProjectionCursor\" WHERE \"organizationId\" = ? AND"
                    + " source = ?").params(org, source).query(java.sql.Timestamp.class).optional()
                    .map(t -> (Instant) SqlRows.value(t))
                    .orElseGet(() -> clock.instant().minus(Duration.ofDays(
                            settings.effective(null).get(LpSettingsService.BACKFILL_DAYS))));
            List<Map<String, Object>> rows = rows(source, org, start);
            List<EventInput> events = events(source, org, rows);
            Map<String, Object> result = events.isEmpty() ? Obj.of("accepted", 0, "duplicates", 0, "rejected", 0)
                    : ingest(events);
            Instant next = start;
            if (!rows.isEmpty()) {
                Instant last = (Instant) rows.getLast().get("ts");
                // A full page continues from its last row; otherwise re-read the overlap next time.
                Instant candidate = rows.size() >= PAGE ? last : last.minus(OVERLAP);
                next = candidate.isAfter(start) ? candidate : start;
            }
            jdbc.sql("INSERT INTO \"LpProjectionCursor\" (\"organizationId\", source, \"cursorAt\") VALUES (?, ?, ?)"
                    + " ON CONFLICT (\"organizationId\", source) DO UPDATE SET \"cursorAt\" = EXCLUDED.\"cursorAt\","
                    + " \"updatedAt\" = CURRENT_TIMESTAMP").params(org, source, SqlRows.ts(next)).update();
            return Obj.of("scanned", rows.size(), "events", events.size(), "accepted", result.get("accepted"),
                    "duplicates", result.get("duplicates"), "rejected", result.get("rejected"), "rejections",
                    rejections(result), "error", null);
        } catch (RuntimeException err) {
            log.error("loss-prevention projection of {} failed: {}", source, err.toString());
            return Obj.of("scanned", 0, "events", 0, "accepted", 0, "duplicates", 0, "rejected", 0, "rejections",
                    List.of(), "error", err.toString());
        }
    }

    private Map<String, Object> ingest(List<EventInput> events) {
        Map<String, Object> total = Obj.of("accepted", 0, "duplicates", 0, "rejected", 0, "results",
                new ArrayList<Map<String, Object>>());
        for (int i = 0; i < events.size(); i += LpCommands.MAX_BATCH) {
            Map<String, Object> r = ledger.ingest(events.subList(i, Math.min(events.size(), i + LpCommands.MAX_BATCH)),
                    false);
            for (String k : new String[] {"accepted", "duplicates", "rejected"}) {
                total.put(k, (Integer) total.get(k) + (Integer) r.get(k));
            }
            results(total).addAll(results(r));
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Map<String, Object> r) {
        Object v = r.get("results");
        return v == null ? new ArrayList<>() : (List<Map<String, Object>>) v;
    }

    /** The first few refusals, so a mapping mistake is visible instead of silent. */
    private static List<Map<String, Object>> rejections(Map<String, Object> result) {
        return results(result).stream().filter(r -> LpLedgerService.REJECTED.equals(r.get("status"))).limit(5)
                .toList();
    }

    // ------------------------------------------------------------------ source rows

    private List<Map<String, Object>> rows(String source, String org, Instant start) {
        String sql = switch (source) {
            case SALES -> "SELECT i.id, i.\"invoiceNumber\", i.\"deviceId\", i.\"orderId\", i.\"branchId\","
                    + " i.\"cashSessionId\", i.\"businessDate\", i.subtotal, i.\"discountTotal\", i.\"taxAmount\","
                    + " i.\"totalAmount\", coalesce(i.\"postedBy\", i.\"createdBy\") AS actor, i.\"postedAt\" AS ts"
                    + " FROM \"Invoice\" i WHERE i.\"organizationId\" = ? AND i.\"cashSessionId\" IS NOT NULL AND"
                    + " i.\"postedAt\" IS NOT NULL AND i.\"postedAt\" >= ? ORDER BY i.\"postedAt\", i.id";
            case VOIDS -> "SELECT oi.id, oi.\"orderId\", oi.description, oi.quantity, oi.\"unitPrice\", oi.cancelled,"
                    + " oi.\"cancelReason\", oi.\"voidedBy\", oi.\"voidApprovedBy\", oi.\"voidedQty\","
                    + " oi.\"kitchenPrintedQty\", coalesce(oi.\"cancelledAt\", oi.\"updatedAt\") AS at, oi.\"updatedAt\""
                    + " AS ts, o.\"branchId\", o.\"cashSessionId\", o.\"deviceId\" FROM \"OrderItem\" oi JOIN \"Order\" o"
                    + " ON o.id = oi.\"orderId\" WHERE oi.\"organizationId\" = ? AND (oi.cancelled = true OR"
                    + " coalesce(oi.\"voidedQty\", 0) > 0) AND oi.\"updatedAt\" >= ? ORDER BY oi.\"updatedAt\", oi.id";
            case CANCELLATIONS -> "SELECT o.id, o.\"branchId\", o.\"cashSessionId\", o.\"deviceId\", o.\"cancelReason\","
                    + " o.\"cancelledBy\", o.\"cancelledAt\" AS ts FROM \"Order\" o WHERE o.\"organizationId\" = ? AND"
                    + " o.status::text = 'cancelled' AND o.\"cancelledAt\" IS NOT NULL AND o.\"cancelledAt\" >= ?"
                    + " ORDER BY o.\"cancelledAt\", o.id";
            case REFUNDS -> "SELECT r.id, r.\"invoiceId\", r.\"cashSessionId\", r.amount, r.reason,"
                    + " r.\"stockDisposition\", r.\"approvedById\", r.\"createdBy\", r.\"createdAt\" AS ts,"
                    + " i.\"branchId\", i.\"orderId\", i.\"deviceId\", coalesce(i.\"postedBy\", i.\"createdBy\") AS"
                    + " \"originalActor\" FROM \"PosRefund\" r LEFT JOIN \"Invoice\" i ON i.id = r.\"invoiceId\" WHERE"
                    + " r.\"organizationId\" = ? AND r.\"createdAt\" >= ? ORDER BY r.\"createdAt\", r.id";
            case OVERRIDES -> "SELECT g.id, g.\"cashierId\", g.\"managerId\", g.\"overrideKind\", g.endpoint,"
                    + " g.\"createdAt\" AS ts, u.\"defaultBranchId\" AS \"branchId\" FROM \"PosApprovalGrant\" g LEFT"
                    + " JOIN \"User\" u ON u.id = g.\"cashierId\" WHERE g.\"organizationId\" = ? AND g.\"createdAt\" >= ?"
                    + " ORDER BY g.\"createdAt\", g.id";
            case CASH_SESSIONS -> "SELECT s.id, s.\"branchId\", s.\"userId\", s.status::text AS status, s.\"deviceId\","
                    + " s.\"openedAt\", s.\"businessDate\", s.\"closedAt\", s.\"openingFloat\", s.\"closingCounted\","
                    + " s.\"closingExpected\", s.\"closingDifference\", s.\"varianceReason\", s.\"varianceStatus\","
                    + " s.\"updatedAt\" AS ts FROM \"CashSession\" s WHERE s.\"organizationId\" = ? AND s.\"updatedAt\""
                    + " >= ? ORDER BY s.\"updatedAt\", s.id";
            case KITCHEN -> "SELECT k.id, k.\"orderId\", k.\"invoiceId\", k.\"ticketNo\", k.station, k.status::text AS"
                    + " status, k.\"startedAt\", k.\"readyAt\", k.\"servedAt\", k.\"startedBy\", k.\"readyBy\","
                    + " k.\"recallCount\", k.\"recallReason\", k.\"createdAt\", k.\"updatedAt\" AS ts, o.\"branchId\" FROM"
                    + " \"KitchenTicket\" k LEFT JOIN \"Order\" o ON o.id = k.\"orderId\" WHERE k.\"organizationId\" = ?"
                    + " AND k.\"updatedAt\" >= ? ORDER BY k.\"updatedAt\", k.id";
            case STOCK_COUNTS -> "SELECT s.id, s.\"countCode\", s.\"locationId\", s.\"startedAt\", s.\"submittedById\","
                    + " s.\"submittedAt\" AS ts, l.\"branchId\" FROM \"BottleCountSession\" s LEFT JOIN"
                    + " \"InventoryLocation\" l ON l.id = s.\"locationId\" WHERE s.\"organizationId\" = ? AND"
                    + " s.\"deletedAt\" IS NULL AND s.status IN ('submitted', 'approved') AND s.\"submittedAt\" IS NOT"
                    + " NULL AND s.\"submittedAt\" >= ? ORDER BY s.\"submittedAt\", s.id";
            default -> throw new IllegalArgumentException("Unknown projection source " + source);
        };
        return SqlRows.list(jdbc.sql(sql + " LIMIT " + PAGE).params(org, SqlRows.ts(start)));
    }

    private List<EventInput> events(String source, String org, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        return switch (source) {
            case SALES -> sales(org, rows);
            case VOIDS -> map(rows, this::voided);
            case CANCELLATIONS -> cancellations(org, rows);
            case REFUNDS -> map(rows, this::refund);
            case OVERRIDES -> map(rows, this::override);
            case CASH_SESSIONS -> flat(rows, this::cashSession);
            case KITCHEN -> flat(rows, this::kitchen);
            default -> stockCounts(org, rows);
        };
    }

    private static List<EventInput> map(List<Map<String, Object>> rows, Function<Map<String, Object>, EventInput> f) {
        return rows.stream().map(f).toList();
    }

    private static List<EventInput> flat(List<Map<String, Object>> rows,
            Function<Map<String, Object>, List<EventInput>> f) {
        List<EventInput> out = new ArrayList<>();
        rows.forEach(r -> out.addAll(f.apply(r)));
        return out;
    }

    // ------------------------------------------------------------------ event building

    private static String s(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private static BigDecimal n(Map<String, Object> r, String key) {
        return LpNumbers.zeroIfNull(LpNumbers.decimal(r.get(key)));
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** One event of this ERP. The event id is derived from the source id, so a re-read is the same event. */
    private EventInput event(String type, String sourceEventId, Map<String, Object> ids, Instant occurredAt,
            LocalDate businessDate, String level, String evidenceSource, String status, Map<String, Object> payload) {
        return new EventInput(UUID.nameUUIDFromBytes((SOURCE + "|" + sourceEventId).getBytes(StandardCharsets.UTF_8))
                .toString(), SOURCE, sourceEventId, (String) ids.get("branchId"), (String) ids.get("deviceId"),
                (String) ids.get("actorId"), (String) ids.get("transactionId"), (String) ids.get("orderId"),
                (String) ids.get("ticketId"), (String) ids.get("lineId"), (String) ids.get("sessionId"),
                businessDate == null ? null : businessDate.toString(), occurredAt.toString(), 1, type,
                mapper.valueToTree(payload), level, evidenceSource, status, "observed".equals(status)
                        ? BigDecimal.ONE : null);
    }

    private static String[] ids(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> (String) r.get("id")).toArray(String[]::new);
    }

    private List<EventInput> sales(String org, List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> lines = new LinkedHashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT id, \"invoiceId\", \"productId\", description,"
                + " quantity, \"unitPrice\", \"discountAmount\", total FROM \"InvoiceItem\" WHERE \"organizationId\" = ?"
                + " AND \"invoiceId\" = ANY(?::text[]) ORDER BY \"lineNumber\", id").params(org, ids(rows)))) {
            lines.computeIfAbsent((String) l.get("invoiceId"), k -> new ArrayList<>()).add(Obj.of("lineId",
                    l.get("id"), "productId", l.get("productId"), "description", l.get("description"), "quantity",
                    l.get("quantity"), "unitPrice", l.get("unitPrice"), "discountAmount", l.get("discountAmount"),
                    "total", l.get("total")));
        }
        List<EventInput> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String id = s(r, "id");
            out.add(event(LpEventTypes.SALE_COMPLETED, "invoice:" + id, Obj.of("branchId", s(r, "branchId"),
                    "deviceId", s(r, "deviceId"), "actorId", s(r, "actor"), "transactionId", id, "orderId",
                    s(r, "orderId"), "sessionId", s(r, "cashSessionId")), (Instant) r.get("ts"),
                    (LocalDate) r.get("businessDate"), "order", "pos", "observed",
                    Obj.of("invoiceNumber", r.get("invoiceNumber"), "subtotal", n(r, "subtotal"), "discountTotal",
                            n(r, "discountTotal"), "taxAmount", n(r, "taxAmount"), "total", n(r, "totalAmount"),
                            "lines", lines.getOrDefault(id, List.of()))));
        }
        return out;
    }

    private EventInput voided(Map<String, Object> r) {
        BigDecimal voidedQty = n(r, "voidedQty");
        BigDecimal quantity = voidedQty.signum() > 0 ? voidedQty : n(r, "quantity");
        return event(LpEventTypes.SALE_LINE_VOIDED, "order-item-void:" + s(r, "id") + ":" + plain(quantity),
                Obj.of("branchId", s(r, "branchId"), "deviceId", s(r, "deviceId"), "actorId", s(r, "voidedBy"),
                        "orderId", s(r, "orderId"), "lineId", s(r, "id"), "sessionId", s(r, "cashSessionId")),
                (Instant) r.get("at"), null, "item", "pos", "observed",
                Obj.of("quantity", quantity, "firedQuantity", n(r, "kitchenPrintedQty"), "whole",
                        Boolean.TRUE.equals(r.get("cancelled")), "reason", r.get("cancelReason"), "approvedById",
                        r.get("voidApprovedBy"), "description", r.get("description"), "amount",
                        LpNumbers.round(quantity.multiply(n(r, "unitPrice")))));
    }

    private List<EventInput> cancellations(String org, List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> fired = new LinkedHashMap<>();
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT \"orderId\", description, quantity, \"unitPrice\","
                + " \"kitchenPrintedQty\" FROM \"OrderItem\" WHERE \"organizationId\" = ? AND \"orderId\" ="
                + " ANY(?::text[]) AND coalesce(\"kitchenPrintedQty\", 0) > 0 ORDER BY \"lineNumber\", id")
                .params(org, ids(rows)))) {
            String order = (String) l.get("orderId");
            fired.computeIfAbsent(order, k -> new ArrayList<>()).add(Obj.of("description", l.get("description"),
                    "quantity", l.get("quantity"), "firedQuantity", l.get("kitchenPrintedQty")));
            amounts.merge(order, n(l, "kitchenPrintedQty").multiply(n(l, "unitPrice")), BigDecimal::add);
        }
        List<EventInput> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String id = s(r, "id");
            out.add(event(LpEventTypes.ORDER_CANCELLED, "order-cancel:" + id, Obj.of("branchId", s(r, "branchId"),
                    "deviceId", s(r, "deviceId"), "actorId", s(r, "cancelledBy"), "orderId", id, "sessionId",
                    s(r, "cashSessionId")), (Instant) r.get("ts"), null, "order", "pos", "observed",
                    Obj.of("reason", r.get("cancelReason"), "firedLines", fired.getOrDefault(id, List.of()),
                            "firedAmount", amounts.containsKey(id) ? LpNumbers.round(amounts.get(id)) : null)));
        }
        return out;
    }

    private EventInput refund(Map<String, Object> r) {
        return event(LpEventTypes.REFUND_ISSUED, "pos-refund:" + s(r, "id"), Obj.of("branchId", s(r, "branchId"),
                "deviceId", s(r, "deviceId"), "actorId", s(r, "createdBy"), "transactionId", s(r, "invoiceId"),
                "orderId", s(r, "orderId"), "sessionId", s(r, "cashSessionId")), (Instant) r.get("ts"), null,
                "order", "pos", "observed", Obj.of("amount", n(r, "amount"), "reason", r.get("reason"),
                        "stockDisposition", r.get("stockDisposition"), "approvedById", r.get("approvedById"),
                        "originalActorId", r.get("originalActor")));
    }

    private EventInput override(Map<String, Object> r) {
        return event(LpEventTypes.OVERRIDE_APPROVED, "pos-approval:" + s(r, "id"), Obj.of("branchId",
                s(r, "branchId"), "actorId", s(r, "cashierId")), (Instant) r.get("ts"), null, "order", "pos",
                "observed", Obj.of("kind", r.get("overrideKind"), "approverId", r.get("managerId"), "endpoint",
                        r.get("endpoint")));
    }

    private List<EventInput> cashSession(Map<String, Object> r) {
        String id = s(r, "id");
        Map<String, Object> ids = Obj.of("branchId", s(r, "branchId"), "deviceId", s(r, "deviceId"), "actorId",
                s(r, "userId"), "sessionId", id);
        LocalDate day = (LocalDate) r.get("businessDate");
        List<EventInput> out = new ArrayList<>();
        if (r.get("openedAt") != null) {
            out.add(event(LpEventTypes.CASH_SESSION_OPENED, "cash-session:" + id + ":opened", ids,
                    (Instant) r.get("openedAt"), day, "session", "pos", "observed", Obj.of("openingFloat",
                            n(r, "openingFloat"))));
        }
        Instant closedAt = (Instant) r.get("closedAt");
        if (closedAt != null) {
            // A reopened session closes again at a new time: that is a new event, and the latest close counts.
            out.add(event(LpEventTypes.CASH_SESSION_CLOSED, "cash-session:" + id + ":closed:"
                    + closedAt.toEpochMilli(), ids, closedAt, day, "session", "pos", "observed",
                    Obj.of("expected", n(r, "closingExpected"), "counted", n(r, "closingCounted"), "difference",
                            n(r, "closingDifference"), "varianceReason", r.get("varianceReason"), "varianceStatus",
                            r.get("varianceStatus"))));
            if ("reconciled".equals(r.get("status"))) {
                out.add(event(LpEventTypes.CASH_SESSION_RECONCILED, "cash-session:" + id + ":reconciled:"
                        + closedAt.toEpochMilli(), ids, (Instant) r.get("ts"), day, "session", "pos", "observed",
                        Obj.of("difference", n(r, "closingDifference"), "varianceStatus", r.get("varianceStatus"))));
            }
        }
        return out;
    }

    /**
     * A kitchen ticket's life. Stage changes come from the kitchen display, per ticket: they are {@code inferred} for
     * the items on the ticket. "Served" on the display is recorded as {@code dispatched}; delivery to the guest is a
     * different stage and is not claimed here.
     */
    private List<EventInput> kitchen(Map<String, Object> r) {
        String id = s(r, "id");
        boolean wasReady = r.get("readyAt") != null;
        List<EventInput> out = new ArrayList<>();
        Function<String, Map<String, Object>> ids = actor -> Obj.of("branchId", s(r, "branchId"), "actorId", actor,
                "transactionId", s(r, "invoiceId"), "orderId", s(r, "orderId"), "ticketId", id);
        out.add(event(LpEventTypes.KITCHEN_TICKET_CREATED, "kitchen-ticket:" + id + ":created", ids.apply(null),
                (Instant) r.get("createdAt"), null, "ticket", "kds", "observed", Obj.of("station", r.get("station"),
                        "ticketNo", r.get("ticketNo"))));
        String[][] stages = {{"startedAt", "preparation_started", "startedBy"}, {"readyAt", "prepared", "readyBy"},
            {"servedAt", "dispatched", null}};
        for (String[] st : stages) {
            Instant at = (Instant) r.get(st[0]);
            if (at != null) {
                out.add(event(LpEventTypes.KITCHEN_STAGE, "kitchen-ticket:" + id + ":" + st[1] + ":"
                        + at.toEpochMilli(), ids.apply(st[2] == null ? null : s(r, st[2])), at, null, "ticket", "kds",
                        "inferred", Obj.of("stage", st[1], "station", r.get("station"))));
            }
        }
        int recalls = r.get("recallCount") == null ? 0 : ((Number) r.get("recallCount")).intValue();
        if (recalls > 0) {
            out.add(event(LpEventTypes.KITCHEN_STAGE, "kitchen-ticket:" + id + ":recall:" + recalls, ids.apply(null),
                    (Instant) r.get("ts"), null, "ticket", "kds", "inferred", Obj.of("stage", "remade", "station",
                            r.get("station"), "recallCount", recalls, "recallReason", r.get("recallReason"),
                            "afterReady", wasReady)));
        }
        if ("cancelled".equals(r.get("status"))) {
            out.add(event(LpEventTypes.KITCHEN_STAGE, "kitchen-ticket:" + id + ":cancelled", ids.apply(null),
                    (Instant) r.get("ts"), null, "ticket", "kds", "inferred", Obj.of("stage", "cancelled", "station",
                            r.get("station"), "recallCount", recalls, "afterReady", wasReady)));
        }
        return out;
    }

    /**
     * One event per counted line of a submitted bottle count, with the figures that explain it: the previous count,
     * what the stock ledger moved in between, and what sales drew through their recipes (the snapshot taken at the
     * sale, so a later recipe change does not alter it). The count's own variance is passed on unchanged.
     */
    private List<EventInput> stockCounts(String org, List<Map<String, Object>> sessions) {
        List<EventInput> out = new ArrayList<>();
        for (Map<String, Object> s : sessions) {
            String session = s(s, "id");
            String location = s(s, "locationId");
            Instant startedAt = (Instant) s.get("startedAt");
            Map<String, Object> previous = SqlRows.one(jdbc.sql("SELECT id, \"startedAt\" FROM \"BottleCountSession\""
                    + " WHERE \"organizationId\" = ? AND \"locationId\" = ? AND \"deletedAt\" IS NULL AND status IN"
                    + " ('submitted', 'approved') AND \"startedAt\" < ? ORDER BY \"startedAt\" DESC LIMIT 1")
                    .params(org, location, SqlRows.ts(startedAt)));
            Map<String, BigDecimal> opening = new LinkedHashMap<>();
            Map<String, Map<String, Object>> movements = new LinkedHashMap<>();
            Map<String, BigDecimal> net = new LinkedHashMap<>();
            Map<String, BigDecimal> sold = new LinkedHashMap<>();
            if (previous != null) {
                Object from = SqlRows.ts((Instant) previous.get("startedAt"));
                Object to = SqlRows.ts(startedAt);
                for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT \"productId\", \"countedMl\" FROM"
                        + " \"BottleCountLine\" WHERE \"sessionId\" = ? AND \"countedMl\" IS NOT NULL")
                        .param(previous.get("id")))) {
                    opening.put((String) l.get("productId"), n(l, "countedMl"));
                }
                for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT \"productId\", type::text AS type,"
                        + " sum(\"quantityChange\") AS qty FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                        + " \"locationId\" = ? AND \"createdAt\" >= ? AND \"createdAt\" < ? GROUP BY \"productId\", type")
                        .params(org, location, from, to))) {
                    String product = (String) m.get("productId");
                    movements.computeIfAbsent(product, k -> new LinkedHashMap<>()).put((String) m.get("type"),
                            n(m, "qty"));
                    net.merge(product, n(m, "qty"), BigDecimal::add);
                }
                for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT \"productId\", sum(\"baseQuantity\") AS qty"
                        + " FROM \"InvoiceItemRecipeIngredient\" WHERE \"organizationId\" = ? AND \"createdAt\" >= ? AND"
                        + " \"createdAt\" < ? GROUP BY \"productId\"").params(org, from, to))) {
                    sold.put((String) m.get("productId"), n(m, "qty"));
                }
            }
            for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT l.id, l.\"productId\", l.\"productName\","
                    + " l.\"systemMl\", l.\"countedMl\", l.\"varianceMl\", l.\"reasonCode\", l.reason, l.confidence,"
                    + " l.\"countedById\", p.\"varianceToleranceG\", p.\"conversionFactorMlPerG\" FROM"
                    + " \"BottleCountLine\" l LEFT JOIN \"Product\" p ON p.id = l.\"productId\" WHERE l.\"sessionId\" = ?"
                    + " AND l.\"countedMl\" IS NOT NULL ORDER BY l.id").param(session))) {
                String product = (String) l.get("productId");
                BigDecimal tolerance = n(l, "varianceToleranceG").multiply(n(l, "conversionFactorMlPerG"));
                String counter = s(l, "countedById");
                out.add(event(LpEventTypes.STOCK_COUNT_LINE, "bottle-count-line:" + s(l, "id"), Obj.of("branchId",
                        s(s, "branchId"), "actorId", counter != null ? counter : s(s, "submittedById"), "lineId",
                        s(l, "id"), "sessionId", session), (Instant) s.get("ts"), null, "session", "stock",
                        "observed", Obj.of("productId", product, "productName", l.get("productName"), "countCode",
                                s.get("countCode"), "locationId", location, "systemMl", n(l, "systemMl"), "countedMl",
                                n(l, "countedMl"), "varianceMl", n(l, "varianceMl"), "reasonCode",
                                l.get("reasonCode"), "reason", l.get("reason"), "confidence", l.get("confidence"),
                                "toleranceMl", tolerance.signum() > 0 ? LpNumbers.round(tolerance) : null,
                                "openingMl", opening.get(product), "ledgerNetMl",
                                previous == null ? null : net.getOrDefault(product, BigDecimal.ZERO), "movements",
                                movements.getOrDefault(product, Map.of()), "soldMl",
                                previous == null ? null : sold.getOrDefault(product, BigDecimal.ZERO))));
            }
        }
        return out;
    }
}
