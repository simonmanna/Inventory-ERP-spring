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
    /** A cash drawer opened, as the POS saw it (LP2). This ERP's POS does not record drawer openings. */
    public static final String CASH_DRAWER_OPENED = "cash_drawer.opened";
    /** A line of a stock ledger at one stock location (LP5): what V-S1 compares a store room's carry-outs with. */
    public static final String STOCK_MOVEMENT = "stock.movement";

    // Observations of an edge agent (LP1): what a camera saw in a zone. The zone id is the event's deviceId.
    public static final String VISION_LINE_CROSSED = "vision.line_crossed";
    public static final String VISION_DWELL = "vision.dwell";
    public static final String VISION_STATE_CHANGED = "vision.state_changed";
    public static final String VISION_HANDLING = "vision.handling";
    public static final String VISION_OCCUPANCY = "vision.occupancy";
    public static final String VISION_GAP = "vision.gap";
    /** LP7, research: an activity a recognizer saw in a zone. No rule reads it. */
    public static final String VISION_ACTIVITY = "vision.activity";

    // Readings of a pour meter (LP3). The measurement device id is the event's deviceId.
    public static final String POUR_MEASURED = "pour.measured";
    public static final String MEASUREMENT_GAP = "measurement.gap";

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
            Map.entry(STAFF_SESSION, 1),
            Map.entry(CASH_DRAWER_OPENED, 1),
            Map.entry(STOCK_MOVEMENT, 1),
            Map.entry(VISION_LINE_CROSSED, 1),
            Map.entry(VISION_DWELL, 1),
            Map.entry(VISION_STATE_CHANGED, 1),
            Map.entry(VISION_HANDLING, 1),
            Map.entry(VISION_OCCUPANCY, 1),
            Map.entry(VISION_GAP, 1),
            Map.entry(VISION_ACTIVITY, 1),
            Map.entry(POUR_MEASURED, 1),
            Map.entry(MEASUREMENT_GAP, 1));

    /** The observation types of an edge agent. */
    public static final Set<String> VISION_TYPES = Set.of(VISION_LINE_CROSSED, VISION_DWELL, VISION_STATE_CHANGED,
            VISION_HANDLING, VISION_OCCUPANCY, VISION_GAP, VISION_ACTIVITY);

    /** The reading types of a pour meter. */
    public static final Set<String> MEASUREMENT_TYPES = Set.of(POUR_MEASURED, MEASUREMENT_GAP);

    /** Why a meter reported nothing ({@link #MEASUREMENT_GAP}). */
    public static final List<String> MEASUREMENT_GAP_REASONS = List.of("device_offline", "adapter_down",
            "clock_drift", "disabled");

    /** The largest single pour a meter may report, in millilitres. */
    public static final int MAX_POUR_ML = 5000;

    /** Why a drawer opened ({@link #CASH_DRAWER_OPENED}). */
    public static final List<String> DRAWER_REASONS = List.of("sale", "refund", "cash_movement", "no_sale", "float",
            "other");

    /** Why a camera had no usable picture ({@link #VISION_GAP}). */
    public static final List<String> GAP_REASONS = List.of("stream_lost", "agent_down", "clock_drift", "disabled");

    /** Directions of a counting line ({@link #VISION_LINE_CROSSED}) and of a {@link #STOCK_MOVEMENT}. */
    public static final List<String> DIRECTIONS = List.of("in", "out");

    /** {@link #VISION_HANDLING} on a {@code scan_area}: an item carried from the unscanned side past the scanner. */
    public static final String ACTION_BYPASSED = "bypassed";

    public static boolean isVision(String type) {
        return type != null && VISION_TYPES.contains(type);
    }

    public static boolean isMeasurement(String type) {
        return type != null && MEASUREMENT_TYPES.contains(type);
    }

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
