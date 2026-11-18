package com.inventoryerp.lossprevention.application;

import static com.inventoryerp.lossprevention.application.LpEventTypes.CASH_DRAWER_OPENED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.CASH_MOVEMENT;
import static com.inventoryerp.lossprevention.application.LpEventTypes.CASH_SESSION_CLOSED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.CASH_SESSION_OPENED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.ORDER_CANCELLED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.REFUND_ISSUED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.SALE_COMPLETED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.SALE_LINE_VOIDED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.VISION_DWELL;
import static com.inventoryerp.lossprevention.application.LpEventTypes.VISION_GAP;
import static com.inventoryerp.lossprevention.application.LpEventTypes.VISION_OCCUPANCY;
import static com.inventoryerp.lossprevention.application.LpEventTypes.VISION_STATE_CHANGED;

import com.inventoryerp.kernel.common.Obj;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Cashier vision (LP2, ADR-S077): what a camera saw at a till against what the POS recorded at the same till, over one
 * calendar day of one branch.
 *
 * <ul>
 *   <li>V-C1: a cash drawer opened with no sale, refund, cash movement or session change near it.</li>
 *   <li>V-C2: a refund, void or cancellation with no customer at the till.</li>
 *   <li>V-C3: a customer served at the till with no sale recorded.</li>
 * </ul>
 *
 * <p>Nothing is found where the camera did not see the whole window: a reported gap, or a time the camera's agent has
 * not yet reported as observed. Such items are skipped, and the run says so ({@link #coverage}).
 *
 * <p>Pure: everything it reads is in the input, the context included (zones, their links and the watermarks as they
 * were when the run started), so a run repeats exactly.
 */
@Component
public class LpCashierVisionEngine implements LpEngine {

    public static final String VERSION = "1";
    /** The longest window a rule may set, and the margin read on each side of the day. */
    public static final int MAX_WINDOW_SECONDS = 900;

    static final String CASH_DRAWER = "cash_drawer";
    static final String CHECKOUT = "checkout";

    private static final Set<String> TYPES = Set.of(VISION_STATE_CHANGED, VISION_DWELL, VISION_OCCUPANCY, VISION_GAP,
            SALE_COMPLETED, REFUND_ISSUED, SALE_LINE_VOIDED, ORDER_CANCELLED, CASH_MOVEMENT, CASH_DRAWER_OPENED,
            CASH_SESSION_OPENED, CASH_SESSION_CLOSED);
    /** POS records that explain an opened drawer. */
    private static final Set<String> DRAWER_EXPLAINED_BY = Set.of(SALE_COMPLETED, REFUND_ISSUED, CASH_MOVEMENT,
            CASH_DRAWER_OPENED, CASH_SESSION_OPENED, CASH_SESSION_CLOSED);
    /** Money handed back or taken off a bill. */
    private static final Set<String> REVERSALS = Set.of(REFUND_ISSUED, SALE_LINE_VOIDED, ORDER_CANCELLED);
    /** A transaction recorded with a customer at the till. */
    private static final Set<String> RECORDED = Set.of(SALE_COMPLETED, REFUND_ISSUED);

    @Override
    public String code() {
        return LpRuleCatalog.CASHIER_VISION;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> eventTypes() {
        return TYPES;
    }

    @Override
    public Map<String, Integer> lookbackDays() {
        // A session opened before the day may still be open on it: it says who was on the till.
        return Map.of(CASH_SESSION_OPENED, 2);
    }

    @Override
    public Set<String> zoneTypes() {
        return Set.of(CASH_DRAWER, CHECKOUT);
    }

    @Override
    public int timeMarginSeconds() {
        return MAX_WINDOW_SECONDS;
    }

    @Override
    public List<Finding> evaluate(Input in) {
        return run(in).findings();
    }

    @Override
    public Coverage coverage(Input in) {
        return run(in).coverage();
    }

    private record Result(List<Finding> findings, Coverage coverage) {}

    private static Result run(Input in) {
        Day day = new Day(in);
        List<Finding> out = new ArrayList<>();
        drawerOpenings(in, day, out);
        Coverage coverage = reversalsWithoutCustomer(in, day, out);
        unrecordedService(in, day, out);
        return new Result(out, coverage);
    }

    // ---------------------------------------------------------------- V-C1

    private static void drawerOpenings(Input in, Day day, List<Finding> out) {
        RuleConfig rule = in.active("V-C1");
        if (rule == null) {
            return;
        }
        int before = seconds(rule, "windowBeforeSeconds", 60);
        int after = seconds(rule, "windowAfterSeconds", 120);
        BigDecimal min = confidence(rule, "0.6");
        for (LedgerEvent e : in.events()) {
            if (!VISION_STATE_CHANGED.equals(e.type()) || !day.owns(e.occurredAt()) || !"open".equals(e.text("to"))
                    || !observed(e, min)) {
                continue;
            }
            Zone zone = day.zones.get(e.deviceId());
            if (zone == null || !zone.tillZone(CASH_DRAWER)) {
                continue;
            }
            String till = zone.linkedId();
            Instant at = e.occurredAt();
            List<LedgerEvent> pos = day.posByTill.getOrDefault(till, List.of());
            LedgerEvent previous = nearest(pos, DRAWER_EXPLAINED_BY, at, false);
            LedgerEvent next = nearest(pos, DRAWER_EXPLAINED_BY, at, true);
            if (within(previous, at.minusSeconds(before), at) || within(next, at, at.plusSeconds(after))) {
                continue;
            }
            Session session = day.sessionAt(till, at);
            List<String> evidence = new ArrayList<>(List.of(e.id()));
            if (session != null && session.openEventId() != null) {
                evidence.add(session.openEventId());
            }
            boolean byActor = session != null && session.actorId() != null;
            out.add(new Finding("V-C1", e.id(), "Cash drawer opened with no sale, refund or cash movement",
                    byActor ? "actor" : "till", byActor ? session.actorId() : till, BigDecimal.ZERO, BigDecimal.ONE,
                    BigDecimal.ONE, "count", null, null,
                    Obj.of("tillId", till, "zoneId", zone.id(), "cameraId", zone.cameraId(), "clipId",
                            e.text("clipId"), "openedAt", at.toString(), "confidence", e.confidence(),
                            "windowBeforeSeconds", before, "windowAfterSeconds", after, "sessionId",
                            session == null ? null : session.id(), "previousPosEvent", describe(previous, at),
                            "nextPosEvent", describe(next, at)),
                    evidence));
        }
    }

    // ---------------------------------------------------------------- V-C2

    private static Coverage reversalsWithoutCustomer(Input in, Day day, List<Finding> out) {
        RuleConfig rule = in.active("V-C2");
        if (rule == null) {
            return Coverage.FULL;
        }
        int before = seconds(rule, "windowBeforeSeconds", 120);
        int after = seconds(rule, "windowAfterSeconds", 60);
        long minPresence = Math.max(0, rule.intValue("minPresenceSeconds", 5));
        BigDecimal min = confidence(rule, "0.5");
        boolean gap = false;
        Instant pending = null;
        for (LedgerEvent e : in.events()) {
            if (!REVERSALS.contains(e.type()) || !day.owns(e.occurredAt())) {
                continue;
            }
            String till = day.tillOf(e);
            if (till == null) {
                continue;
            }
            Instant at = e.occurredAt();
            Instant from = at.minusSeconds(before);
            Instant to = at.plusSeconds(after);
            List<Zone> zones = day.tillZones(till, CHECKOUT, from, to);
            if (zones.isEmpty()) {
                continue;
            }
            boolean covered = true;
            for (Zone zone : zones) {
                Instant mark = day.watermark(zone);
                if (mark == null || mark.isBefore(to)) {
                    covered = false;
                    pending = pending == null || to.isAfter(pending) ? to : pending;
                }
                if (day.gapDuring(zone.id(), from, to)) {
                    covered = false;
                    gap = true;
                }
            }
            if (!covered || zones.stream().anyMatch(z -> day.present(z.id(), from, to, minPresence, min))) {
                continue;
            }
            BigDecimal amount = ORDER_CANCELLED.equals(e.type()) ? e.num("firedAmount") : e.num("amount");
            boolean byActor = e.actorId() != null;
            out.add(new Finding("V-C2", e.id(), reversalTitle(e.type()), byActor ? "actor" : "till",
                    byActor ? e.actorId() : till, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE.negate(), "count",
                    amount, amount == null ? null : "Amount refunded or voided",
                    Obj.of("tillId", till, "type", e.type(), "occurredAt", at.toString(), "zoneIds",
                            zones.stream().map(Zone::id).toList(), "cameraIds",
                            zones.stream().map(Zone::cameraId).distinct().toList(), "windowBeforeSeconds", before,
                            "windowAfterSeconds", after, "amount", amount, "reason", e.text("reason"),
                            "transactionId", e.transactionId(), "orderId", e.orderId()),
                    List.of(e.id())));
        }
        return new Coverage(gap, pending);
    }

    private static String reversalTitle(String type) {
        return switch (type) {
            case REFUND_ISSUED -> "Refund with no customer at the till";
            case SALE_LINE_VOIDED -> "Item voided with no customer at the till";
            default -> "Order cancelled with no customer at the till";
        };
    }

    // ---------------------------------------------------------------- V-C3

    private static void unrecordedService(Input in, Day day, List<Finding> out) {
        RuleConfig rule = in.active("V-C3");
        if (rule == null) {
            return;
        }
        long minDwell = Math.max(0, rule.intValue("minDwellSeconds", 90));
        int before = seconds(rule, "windowBeforeSeconds", 0);
        int after = seconds(rule, "windowAfterSeconds", 300);
        BigDecimal min = confidence(rule, "0.6");
        for (LedgerEvent e : in.events()) {
            if (!VISION_DWELL.equals(e.type()) || !day.owns(e.occurredAt()) || !person(e) || !observed(e, min)) {
                continue;
            }
            Zone zone = day.zones.get(e.deviceId());
            if (zone == null || !zone.tillZone(CHECKOUT)) {
                continue;
            }
            Instant started = instant(e.text("startedAt"));
            Instant ended = instant(e.text("endedAt"));
            if (started == null || ended == null || Duration.between(started, ended).toSeconds() < minDwell) {
                continue;
            }
            String till = zone.linkedId();
            // Someone standing at a closed till is not a customer being served.
            if (day.tillsWithSessions.contains(till) && !day.sessionDuring(till, started, ended)) {
                continue;
            }
            Instant from = started.minusSeconds(before);
            Instant to = ended.plusSeconds(after);
            if (day.posByTill.getOrDefault(till, List.of()).stream()
                    .anyMatch(p -> RECORDED.contains(p.type()) && within(p, from, to))) {
                continue;
            }
            Session session = day.sessionAt(till, ended);
            if (session == null) {
                session = day.sessionAt(till, started);
            }
            List<String> evidence = new ArrayList<>(List.of(e.id()));
            if (session != null && session.openEventId() != null) {
                evidence.add(session.openEventId());
            }
            boolean byActor = session != null && session.actorId() != null;
            out.add(new Finding("V-C3", e.id(), "Customer served with no sale recorded", byActor ? "actor" : "till",
                    byActor ? session.actorId() : till, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE.negate(),
                    "count", null, null,
                    Obj.of("tillId", till, "zoneId", zone.id(), "cameraId", zone.cameraId(), "clipId",
                            e.text("clipId"), "startedAt", started.toString(), "endedAt", ended.toString(),
                            "dwellSeconds", Duration.between(started, ended).toSeconds(), "maxCount",
                            e.num("maxCount"), "confidence", e.confidence(), "windowBeforeSeconds", before,
                            "windowAfterSeconds", after, "sessionId", session == null ? null : session.id()),
                    evidence));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int seconds(RuleConfig rule, String key, int fallback) {
        return Math.min(MAX_WINDOW_SECONDS, Math.max(0, rule.intValue(key, fallback)));
    }

    private static BigDecimal confidence(RuleConfig rule, String fallback) {
        BigDecimal v = rule.num("minConfidence");
        return v == null ? new BigDecimal(fallback) : v;
    }

    /** Seen, not inferred, and at least as sure as the rule asks. A sender that states no confidence is taken as sure. */
    private static boolean observed(LedgerEvent e, BigDecimal min) {
        return "observed".equals(e.observationStatus()) && (e.confidence() == null || e.confidence().compareTo(min) >= 0);
    }

    private static boolean person(LedgerEvent e) {
        String cls = e.text("class");
        return cls == null || "person".equals(cls);
    }

    private static boolean within(LedgerEvent e, Instant from, Instant to) {
        return e != null && !e.occurredAt().isBefore(from) && !e.occurredAt().isAfter(to);
    }

    /** The POS record of one of {@code types} closest before (or at) {@code at}, or closest after it. */
    private static LedgerEvent nearest(List<LedgerEvent> events, Set<String> types, Instant at, boolean after) {
        LedgerEvent best = null;
        for (LedgerEvent e : events) {
            if (!types.contains(e.type())) {
                continue;
            }
            Instant t = e.occurredAt();
            if (after ? t.isAfter(at) && (best == null || t.isBefore(best.occurredAt()))
                    : !t.isAfter(at) && (best == null || t.isAfter(best.occurredAt()))) {
                best = e;
            }
        }
        return best;
    }

    private static Map<String, Object> describe(LedgerEvent e, Instant at) {
        return e == null ? null : Obj.of("eventId", e.id(), "type", e.type(), "occurredAt", e.occurredAt().toString(),
                "secondsAway", Math.abs(Duration.between(e.occurredAt(), at).toSeconds()));
    }

    static Instant instant(Object v) {
        return v == null ? null : LpEventValidator.instant(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    private static String str(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private static ZoneId zoneOf(Object v) {
        try {
            return v == null ? ZoneOffset.UTC : ZoneId.of(String.valueOf(v));
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    /** A zone as the run saw it. */
    record Zone(String id, String cameraId, String agentId, String type, String linkedType, String linkedId,
            boolean enabled, Instant createdAt, Instant retiredAt) {

        boolean tillZone(String wanted) {
            return wanted.equals(type) && "till".equals(linkedType) && linkedId != null;
        }

        /** Drawn before the window began and not retired before it ended. */
        boolean liveDuring(Instant from, Instant to) {
            return enabled && (createdAt == null || !createdAt.isAfter(from)) && (retiredAt == null
                    || retiredAt.isAfter(to));
        }
    }

    /** A cash session: who was on which till, when. {@code openedAt} is null when its opening is outside the read. */
    record Session(String id, String tillId, String actorId, Instant openedAt, Instant closedAt, String openEventId) {

        boolean openAt(Instant t) {
            return (openedAt == null || !openedAt.isAfter(t)) && (closedAt == null || closedAt.isAfter(t));
        }

        boolean overlaps(Instant from, Instant to) {
            return (openedAt == null || !openedAt.isAfter(to)) && (closedAt == null || closedAt.isAfter(from));
        }
    }

    /** The window's events and context, indexed. */
    private static final class Day {

        final Instant start;
        final Instant end;
        final Map<String, Zone> zones = new LinkedHashMap<>();
        final Map<String, Instant> cameraMarks = new HashMap<>();
        final Map<String, Instant> agentMarks = new HashMap<>();
        final Map<String, Session> sessions = new LinkedHashMap<>();
        final Map<String, List<LedgerEvent>> posByTill = new HashMap<>();
        final Map<String, List<LedgerEvent>> byZone = new HashMap<>();
        final Set<String> tillsWithSessions = new HashSet<>();

        Day(Input in) {
            Map<String, Object> context = in.context() == null ? Map.of() : in.context();
            ZoneId tz = zoneOf(context.get("timeZone"));
            start = in.businessDate().atStartOfDay(tz).toInstant();
            end = in.businessDate().plusDays(1).atStartOfDay(tz).toInstant();
            for (Map<String, Object> z : maps(context.get("zones"))) {
                zones.put(str(z.get("id")), new Zone(str(z.get("id")), str(z.get("cameraId")), str(z.get("agentId")),
                        str(z.get("zoneType")), str(z.get("linkedType")), str(z.get("linkedId")),
                        !Boolean.FALSE.equals(z.get("enabled")), instant(z.get("createdAt")),
                        instant(z.get("retiredAt"))));
            }
            for (Map<String, Object> c : maps(context.get("cameras"))) {
                Instant mark = instant(c.get("observedThrough"));
                if (mark != null) {
                    cameraMarks.put(str(c.get("id")), mark);
                }
            }
            for (Map<String, Object> a : maps(context.get("agents"))) {
                Instant mark = instant(a.get("observedThrough"));
                if (mark != null) {
                    agentMarks.put(str(a.get("id")), mark);
                }
            }
            for (LedgerEvent e : in.events()) {
                if (CASH_SESSION_OPENED.equals(e.type()) && e.sessionId() != null) {
                    Session old = sessions.get(e.sessionId());
                    sessions.put(e.sessionId(), new Session(e.sessionId(), firstOf(e.text("tillId"), e.deviceId()),
                            e.actorId(), e.occurredAt(), old == null ? null : old.closedAt(), e.id()));
                }
            }
            // A reopened session closes again: its latest close counts.
            for (LedgerEvent e : in.events()) {
                if (!CASH_SESSION_CLOSED.equals(e.type()) || e.sessionId() == null) {
                    continue;
                }
                Session s = sessions.get(e.sessionId());
                if (s == null) {
                    sessions.put(e.sessionId(), new Session(e.sessionId(), firstOf(e.text("tillId"), e.deviceId()),
                            e.actorId(), null, e.occurredAt(), null));
                } else if (s.closedAt() == null || e.occurredAt().isAfter(s.closedAt())) {
                    sessions.put(s.id(), new Session(s.id(), s.tillId(), s.actorId(), s.openedAt(), e.occurredAt(),
                            s.openEventId()));
                }
            }
            sessions.values().forEach(s -> {
                if (s.tillId() != null) {
                    tillsWithSessions.add(s.tillId());
                }
            });
            for (LedgerEvent e : in.events()) {
                if (LpEventTypes.isVision(e.type())) {
                    if (e.deviceId() != null) {
                        byZone.computeIfAbsent(e.deviceId(), k -> new ArrayList<>()).add(e);
                    }
                } else {
                    String till = tillOf(e);
                    if (till != null) {
                        posByTill.computeIfAbsent(till, k -> new ArrayList<>()).add(e);
                    }
                }
            }
        }

        private static String firstOf(String a, String b) {
            return a != null ? a : b;
        }

        boolean owns(Instant t) {
            return !t.isBefore(start) && t.isBefore(end);
        }

        /** The till of a POS record: its own, else its cash session's, else the device it came from. */
        String tillOf(LedgerEvent e) {
            String till = e.text("tillId");
            if (till != null) {
                return till;
            }
            Session s = e.sessionId() == null ? null : sessions.get(e.sessionId());
            return s != null && s.tillId() != null ? s.tillId() : e.deviceId();
        }

        /** The session open on the till at {@code t}; the latest opened when several are. */
        Session sessionAt(String till, Instant t) {
            Session found = null;
            for (Session s : sessions.values()) {
                if (till.equals(s.tillId()) && s.openAt(t) && (found == null || found.openedAt() == null
                        || (s.openedAt() != null && s.openedAt().isAfter(found.openedAt())))) {
                    found = s;
                }
            }
            return found;
        }

        boolean sessionDuring(String till, Instant from, Instant to) {
            return sessions.values().stream().anyMatch(s -> till.equals(s.tillId()) && s.overlaps(from, to));
        }

        List<Zone> tillZones(String till, String type, Instant from, Instant to) {
            return zones.values().stream().filter(z -> z.tillZone(type) && till.equals(z.linkedId())
                    && z.liveDuring(from, to)).toList();
        }

        /** How far the zone's camera has been observed and reported: its own watermark, else its agent's. */
        Instant watermark(Zone zone) {
            Instant camera = cameraMarks.get(zone.cameraId());
            return camera != null ? camera : agentMarks.get(zone.agentId());
        }

        boolean gapDuring(String zoneId, Instant from, Instant to) {
            for (LedgerEvent g : byZone.getOrDefault(zoneId, List.of())) {
                if (!VISION_GAP.equals(g.type())) {
                    continue;
                }
                Instant gapFrom = instant(g.text("from"));
                Instant gapTo = instant(g.text("to"));
                if (gapFrom != null && gapTo != null && !gapFrom.isAfter(to) && !gapTo.isBefore(from)) {
                    return true;
                }
            }
            return false;
        }

        /** A person was seen in the zone during the window: a long enough presence, or a period with someone in it. */
        boolean present(String zoneId, Instant from, Instant to, long minSeconds, BigDecimal min) {
            for (LedgerEvent e : byZone.getOrDefault(zoneId, List.of())) {
                if (!observed(e, min) || !person(e)) {
                    continue;
                }
                if (VISION_DWELL.equals(e.type())) {
                    Instant started = instant(e.text("startedAt"));
                    Instant ended = instant(e.text("endedAt"));
                    if (started != null && ended != null && !started.isAfter(to) && !ended.isBefore(from)
                            && Duration.between(started, ended).toSeconds() >= minSeconds) {
                        return true;
                    }
                } else if (VISION_OCCUPANCY.equals(e.type())) {
                    BigDecimal count = e.num("count");
                    BigDecimal period = e.num("periodSeconds");
                    Instant periodEnd = e.occurredAt();
                    Instant periodStart = periodEnd.minusSeconds(period == null ? 60 : Math.max(0, period.longValue()));
                    if (count != null && count.signum() > 0 && !periodStart.isAfter(to) && !periodEnd.isBefore(from)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
