package com.inventoryerp.lossprevention.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The canonical event contract, version 1 (ADR-S077 §3): the event types a sender may post and the values of the
 * evidence fields. Every sender uses this vocabulary, this ERP's own POS included.
 */
public final class LpEventTypes {

    private LpEventTypes() {}

    public static final String SALE_COMPLETED = "sale.completed";
    public static final String SALE_LINE_VOIDED = "sale.line_voided";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String REFUND_ISSUED = "refund.issued";
    public static final String OVERRIDE_APPROVED = "override.approved";
    public static final String CASH_SESSION_OPENED = "cash_session.opened";
    public static final String CASH_SESSION_CLOSED = "cash_session.closed";
    public static final String CASH_SESSION_RECONCILED = "cash_session.reconciled";
    public static final String CASH_MOVEMENT = "cash.movement";
    public static final String KITCHEN_TICKET_CREATED = "kitchen.ticket_created";
    public static final String KITCHEN_STAGE = "kitchen.stage";
    public static final String STOCK_COUNT_LINE = "stock_count.line";
    public static final String STAFF_SESSION = "staff.session";

    /** Highest payload schema version understood for each type. */
    public static final Map<String, Integer> SCHEMA_VERSIONS = Map.ofEntries(
            Map.entry(SALE_COMPLETED, 1),
            Map.entry(SALE_LINE_VOIDED, 1),
            Map.entry(ORDER_CANCELLED, 1),
            Map.entry(REFUND_ISSUED, 1),
            Map.entry(OVERRIDE_APPROVED, 1),
            Map.entry(CASH_SESSION_OPENED, 1),
            Map.entry(CASH_SESSION_CLOSED, 1),
            Map.entry(CASH_SESSION_RECONCILED, 1),
            Map.entry(CASH_MOVEMENT, 1),
            Map.entry(KITCHEN_TICKET_CREATED, 1),
            Map.entry(KITCHEN_STAGE, 1),
            Map.entry(STOCK_COUNT_LINE, 1),
            Map.entry(STAFF_SESSION, 1));

    public static final List<String> EVIDENCE_LEVELS = List.of("item", "ticket", "order", "session", "zone");
    public static final List<String> EVIDENCE_SOURCES = List.of("pos", "kds", "stock", "camera", "sensor", "manual");
    public static final List<String> OBSERVATION_STATUSES = List.of("observed", "inferred", "unavailable");

    /** Stages of {@link #KITCHEN_STAGE}. Dispatched and delivered are separate; one never implies the other. */
    public static final List<String> KITCHEN_STAGES = List.of("preparation_started", "prepared", "dispatched",
            "delivered", "remade", "wasted", "cancelled");

    public static Set<String> types() {
        return SCHEMA_VERSIONS.keySet();
    }
}
