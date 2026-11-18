package com.inventoryerp.lossprevention.application;

import static com.inventoryerp.lossprevention.application.LpEventTypes.KITCHEN_STAGE;
import static com.inventoryerp.lossprevention.application.LpEventTypes.KITCHEN_TICKET_CREATED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.ORDER_CANCELLED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.SALE_COMPLETED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.VISION_GAP;
import static com.inventoryerp.lossprevention.application.LpEventTypes.VISION_LINE_CROSSED;
import static com.inventoryerp.lossprevention.application.LpObservedDay.confidence;
import static com.inventoryerp.lossprevention.application.LpObservedDay.maps;
import static com.inventoryerp.lossprevention.application.LpObservedDay.observed;
import static com.inventoryerp.lossprevention.application.LpObservedDay.sameName;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.lossprevention.application.LpObservedDay.Zone;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Restaurant and cafe service seen by a camera (LP4, docs/phases/LP4-restaurant-cafe.md), over one calendar day of a
 * branch, in periods of {@code bucketMinutes}:
 *
 * <ul>
 *   <li>V-K1: more plates crossed the kitchen pass than the tickets prepared or served then account for.</li>
 *   <li>V-F1: more items crossed the cafe counter than the till sold then.</li>
 *   <li>V-K2: a ticket marked served with no plate seen leaving its station's pass near that time.</li>
 *   <li>V-K3: plates arriving at a table (a {@code dining} zone linked to a POS table) while it had no open order.</li>
 *   <li>V-F2: fewer items handed over at the cafe counter than the counter's station prepared then.</li>
 * </ul>
 *
 * <p>A period, ticket or delivery not fully observed (a gap, after the camera's watermark) or with a ticket of unknown
 * size is skipped, never found, and the run says so.
 */
@Component
public class LpServiceVisionEngine implements LpEngine {

    public static final String VERSION = "1";
    static final String KITCHEN_PASS = "kitchen_pass";
    static final String CAFE_COUNTER = "cafe_counter";
    static final String DINING = "dining";
    static final int MAX_BUCKET_MINUTES = 240;
    static final int MAX_SLACK_MINUTES = 15;

    private static final Set<String> TYPES = Set.of(VISION_LINE_CROSSED, VISION_GAP, KITCHEN_TICKET_CREATED,
            KITCHEN_STAGE, SALE_COMPLETED, ORDER_CANCELLED);

    @Override
    public String code() {
        return LpRuleCatalog.SERVICE_VISION;
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
        // The size and station of a ticket created before the day.
        return Map.of(KITCHEN_TICKET_CREATED, 1);
    }

    @Override
    public Set<String> zoneTypes() {
        return Set.of(KITCHEN_PASS, CAFE_COUNTER, DINING);
    }

    @Override
    public int timeMarginSeconds() {
        return LpCashierVisionEngine.MAX_WINDOW_SECONDS;
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

    /** A ticket leaving the kitchen: when it was first prepared or served, its station and its size. */
    private record Leaving(String ticketId, String station, Instant at, BigDecimal items, String eventId) {}

    /** Periods skipped for want of observation. */
    private static final class Gaps {
        boolean gap;
        Instant pending;

        void pending(Instant t) {
            pending = pending == null || t.isAfter(pending) ? t : pending;
        }
    }

    private static Result run(Input in) {
        LpObservedDay day = new LpObservedDay(in);
        List<Finding> out = new ArrayList<>();
        Gaps gaps = new Gaps();
        RuleConfig k1 = in.active("V-K1");
        if (k1 != null) {
            List<Leaving> leaving = leaving(in);
            for (Zone zone : day.zones.values()) {
                if (KITCHEN_PASS.equals(zone.type())) {
                    periods(day, zone, "V-K1", k1, gaps, (from, to, slackFrom, slackTo) -> {
                        BigDecimal expected = BigDecimal.ZERO;
                        List<String> ids = new ArrayList<>();
                        for (Leaving l : leaving) {
                            boolean station = !"station".equals(zone.linkedType()) || zone.linkedId() == null
                                    || sameName(zone.linkedId(), l.station());
                            if (!station || l.at().isBefore(slackFrom) || !l.at().isBefore(slackTo)) {
                                continue;
                            }
                            if (l.items() == null) {
                                return null;
                            }
                            expected = expected.add(l.items());
                            ids.add(l.eventId());
                        }
                        return new Expected(expected, ids, null);
                    }, out);
                }
            }
        }
        RuleConfig f1 = in.active("V-F1");
        if (f1 != null) {
            List<LedgerEvent> sales = in.events().stream().filter(e -> SALE_COMPLETED.equals(e.type())).toList();
            for (Zone zone : day.zones.values()) {
                if (CAFE_COUNTER.equals(zone.type())) {
                    periods(day, zone, "V-F1", f1, gaps, (from, to, slackFrom, slackTo) -> {
                        BigDecimal qty = BigDecimal.ZERO;
                        BigDecimal amount = BigDecimal.ZERO;
                        List<String> ids = new ArrayList<>();
                        for (LedgerEvent s : sales) {
                            if (s.occurredAt().isBefore(slackFrom) || !s.occurredAt().isBefore(slackTo)
                                    || !atZone(zone, s)) {
                                continue;
                            }
                            ids.add(s.id());
                            for (Map<String, Object> line : maps(s.payload().get("lines"))) {
                                BigDecimal q = LpNumbers.decimal(line.get("quantity"));
                                BigDecimal total = LpNumbers.decimal(line.get("total"));
                                qty = qty.add(q == null ? BigDecimal.ZERO : q);
                                amount = amount.add(total == null ? BigDecimal.ZERO : total);
                            }
                        }
                        BigDecimal price = qty.signum() > 0 ? amount.divide(qty, 6, RoundingMode.HALF_UP) : null;
                        return new Expected(qty, ids, price);
                    }, out);
                }
            }
        }
        RuleConfig k2 = in.active("V-K2");
        if (k2 != null) {
            servedWithoutPlate(in, day, k2, gaps, out);
        }
        RuleConfig k3 = in.active("V-K3");
        if (k3 != null) {
            platesWithoutOrder(in, day, k3, gaps, out);
        }
        RuleConfig f2 = in.active("V-F2");
        if (f2 != null) {
            for (Zone zone : day.zones.values()) {
                if (CAFE_COUNTER.equals(zone.type()) && "station".equals(zone.linkedType())
                        && zone.linkedId() != null) {
                    preparedNotHandedOver(in, day, zone, f2, gaps, out);
                }
            }
        }
        out.sort(Comparator.comparing(Finding::ruleCode).thenComparing(Finding::key));
        return new Result(out, new Coverage(gaps.gap, gaps.pending));
    }

    /** Plates leaving through {@code zone} in [from, to): Σ count of confident {@code out} crossings, not people. */
    private static List<LedgerEvent> crossings(LpObservedDay day, Zone zone, String direction, Instant from,
            Instant to, BigDecimal min) {
        List<LedgerEvent> out = new ArrayList<>();
        for (LedgerEvent e : day.of(zone.id())) {
            if (VISION_LINE_CROSSED.equals(e.type()) && !e.occurredAt().isBefore(from) && e.occurredAt().isBefore(to)
                    && direction.equals(e.text("direction")) && !"person".equals(e.text("class"))
                    && observed(e, min)) {
                out.add(e);
            }
        }
        return out;
    }

    private static BigDecimal count(List<LedgerEvent> crossings) {
        BigDecimal n = BigDecimal.ZERO;
        for (LedgerEvent e : crossings) {
            BigDecimal c = e.num("count");
            n = n.add(c == null ? BigDecimal.ONE : c);
        }
        return n;
    }

    /** Whether [from, to) of the zone is observed: before its watermark (else pending) and with no gap. */
    private static boolean covered(LpObservedDay day, Zone zone, Instant from, Instant to, Gaps gaps) {
        Instant mark = day.watermark(zone);
        if (mark == null || mark.isBefore(to)) {
            gaps.pending(to);
            return false;
        }
        if (day.gapDuring(zone.id(), from, to)) {
            gaps.gap = true;
            return false;
        }
        return true;
    }

    /**
     * V-K2. Each ticket of the day marked {@code dispatched} (the ERP's "served"; the first such stage), not cancelled,
     * with items: a finding when no plate left through the kitchen pass of its station (a zone linked to the station,
     * or a pass with no link) within {@code windowMinutes} of it. A ticket whose station no pass covers, or whose
     * window is not fully observed, is skipped.
     */
    private static void servedWithoutPlate(Input in, LpObservedDay day, RuleConfig rule, Gaps gaps,
            List<Finding> out) {
        int window = Math.min(MAX_SLACK_MINUTES, Math.max(1, rule.intValue("windowMinutes", 5)));
        BigDecimal min = confidence(rule, "0.5");
        Map<String, LedgerEvent> created = new LinkedHashMap<>();
        Map<String, LedgerEvent> served = new LinkedHashMap<>();
        Set<String> cancelled = new HashSet<>();
        for (LedgerEvent e : in.events()) {
            if (e.ticketId() == null) {
                continue;
            }
            if (KITCHEN_TICKET_CREATED.equals(e.type())) {
                created.put(e.ticketId(), e);
            } else if (KITCHEN_STAGE.equals(e.type()) && "cancelled".equals(e.text("stage"))) {
                cancelled.add(e.ticketId());
            } else if (KITCHEN_STAGE.equals(e.type()) && "dispatched".equals(e.text("stage"))) {
                LedgerEvent seen = served.get(e.ticketId());
                if (seen == null || e.occurredAt().isBefore(seen.occurredAt())) {
                    served.put(e.ticketId(), e);
                }
            }
        }
        for (Map.Entry<String, LedgerEvent> entry : served.entrySet()) {
            String ticket = entry.getKey();
            LedgerEvent e = entry.getValue();
            if (cancelled.contains(ticket) || !day.owns(e.occurredAt())) {
                continue;
            }
            LedgerEvent c = created.get(ticket);
            BigDecimal items = e.num("itemCount") != null ? e.num("itemCount") : c == null ? null : c.num("itemCount");
            if (items != null && items.signum() <= 0) {
                continue;
            }
            String station = e.text("station") != null ? e.text("station") : c == null ? null : c.text("station");
            Instant from = e.occurredAt().minus(Duration.ofMinutes(window));
            Instant to = e.occurredAt().plus(Duration.ofMinutes(window));
            List<Zone> passes = new ArrayList<>();
            for (Zone zone : day.zones.values()) {
                if (KITCHEN_PASS.equals(zone.type()) && zone.liveDuring(from, to) && (!"station".equals(
                        zone.linkedType()) || zone.linkedId() == null || sameName(zone.linkedId(), station))) {
                    passes.add(zone);
                }
            }
            if (passes.isEmpty()) {
                continue;
            }
            boolean observedAll = true;
            boolean plate = false;
            for (Zone zone : passes) {
                if (!covered(day, zone, from, to, gaps)) {
                    observedAll = false;
                    break;
                }
                plate = plate || !crossings(day, zone, "out", from, to, min).isEmpty();
            }
            if (!observedAll || plate) {
                continue;
            }
            Zone first = passes.getFirst();
            List<String> evidence = new ArrayList<>(List.of(e.id()));
            if (c != null) {
                evidence.add(c.id());
            }
            out.add(new Finding("V-K2", ticket, "Ticket marked served with no plate seen leaving the kitchen pass",
                    "ticket", ticket, items == null ? BigDecimal.ONE : items, BigDecimal.ZERO,
                    (items == null ? BigDecimal.ONE : items).negate(), "count", null, null,
                    Obj.of("zoneId", first.id(), "cameraId", first.cameraId(), "zones", passes.size(), "station",
                            station, "orderId", e.orderId() != null ? e.orderId() : c == null ? null : c.orderId(),
                            "servedAt", e.occurredAt().toString(), "windowMinutes", window),
                    evidence));
        }
    }

    /** When a table's order was open: from its first ticket or {@code maxOrderMinutes} before its sale, to its close. */
    private record Open(Instant from, Instant to, String eventId) {}

    /**
     * V-K3. Plates arriving at a table: {@code in} crossings, not people, of a {@code dining} zone linked to a POS
     * table, grouped into deliveries no more than {@code clusterMinutes} apart. A delivery is found when the table had
     * no open order within {@code slackMinutes} of it. Skipped when no ticket or sale of the day records a table (the
     * sender does not send one), or when the delivery is not fully observed.
     */
    private static void platesWithoutOrder(Input in, LpObservedDay day, RuleConfig rule, Gaps gaps,
            List<Finding> out) {
        int cluster = Math.min(MAX_SLACK_MINUTES, Math.max(1, rule.intValue("clusterMinutes", 10)));
        int slack = Math.min(MAX_SLACK_MINUTES, Math.max(0, rule.intValue("slackMinutes", 10)));
        int maxOrder = Math.min(24 * 60, Math.max(30, rule.intValue("maxOrderMinutes", 240)));
        BigDecimal minPlates = LpNumbers.zeroIfNull(rule.num("minPlates")).max(BigDecimal.ONE);
        BigDecimal min = confidence(rule, "0.5");
        boolean tablesRecorded = in.events().stream().anyMatch(e -> (KITCHEN_TICKET_CREATED.equals(e.type())
                || SALE_COMPLETED.equals(e.type())) && e.payload().containsKey("tableId"));
        if (!tablesRecorded) {
            return;
        }
        for (Zone zone : day.zones.values()) {
            if (!DINING.equals(zone.type()) || !"table".equals(zone.linkedType()) || zone.linkedId() == null) {
                continue;
            }
            List<Open> open = openOrders(in, zone.linkedId(), maxOrder);
            List<LedgerEvent> arrivals = crossings(day, zone, "in", day.start, day.end, min);
            arrivals.sort(Comparator.comparing(LedgerEvent::occurredAt).thenComparing(LedgerEvent::seq));
            int i = 0;
            while (i < arrivals.size()) {
                List<LedgerEvent> delivery = new ArrayList<>(List.of(arrivals.get(i)));
                int j = i + 1;
                while (j < arrivals.size() && !arrivals.get(j).occurredAt().isAfter(delivery.getLast().occurredAt()
                        .plus(Duration.ofMinutes(cluster)))) {
                    delivery.add(arrivals.get(j));
                    j++;
                }
                i = j;
                Instant first = delivery.getFirst().occurredAt();
                Instant last = delivery.getLast().occurredAt();
                BigDecimal plates = count(delivery);
                if (plates.compareTo(minPlates) < 0 || !zone.liveDuring(first, last)) {
                    continue;
                }
                Instant from = first.minus(Duration.ofMinutes(slack));
                Instant to = last.plus(Duration.ofMinutes(slack));
                if (!covered(day, zone, from, to, gaps)) {
                    continue;
                }
                boolean ordered = open.stream().anyMatch(o -> !o.from().isAfter(to) && !o.to().isBefore(from));
                if (ordered) {
                    continue;
                }
                out.add(new Finding("V-K3", zone.id() + ":" + first, "Plates delivered to a table with no open order",
                        "table", zone.linkedId(), BigDecimal.ZERO, plates, plates, "count", null, null,
                        Obj.of("zoneId", zone.id(), "cameraId", zone.cameraId(), "tableId", zone.linkedId(),
                                "firstArrival", first.toString(), "lastArrival", last.toString(), "slackMinutes",
                                slack, "clusterMinutes", cluster),
                        delivery.stream().map(LedgerEvent::id).limit(40).toList()));
            }
        }
    }

    /** The open periods of the table's orders, from its tickets and sales of the input. */
    private static List<Open> openOrders(Input in, String table, int maxOrderMinutes) {
        Map<String, Instant> opened = new LinkedHashMap<>();
        Map<String, String> openedBy = new LinkedHashMap<>();
        Map<String, Instant> closed = new LinkedHashMap<>();
        List<Open> out = new ArrayList<>();
        for (LedgerEvent e : in.events()) {
            if (KITCHEN_TICKET_CREATED.equals(e.type()) && table.equals(e.text("tableId"))) {
                String order = e.orderId() != null ? e.orderId() : "ticket:" + e.ticketId();
                if (!opened.containsKey(order) || e.occurredAt().isBefore(opened.get(order))) {
                    opened.put(order, e.occurredAt());
                    openedBy.put(order, e.id());
                }
            }
        }
        for (LedgerEvent e : in.events()) {
            boolean sale = SALE_COMPLETED.equals(e.type());
            if ((sale || ORDER_CANCELLED.equals(e.type())) && e.orderId() != null && opened.containsKey(e.orderId())
                    && !e.occurredAt().isBefore(opened.get(e.orderId()))) {
                Instant seen = closed.get(e.orderId());
                if (seen == null || e.occurredAt().isBefore(seen)) {
                    closed.put(e.orderId(), e.occurredAt());
                }
            }
            if (sale && table.equals(e.text("tableId"))) {
                out.add(new Open(e.occurredAt().minus(Duration.ofMinutes(maxOrderMinutes)), e.occurredAt(), e.id()));
            }
        }
        opened.forEach((order, at) -> out.add(new Open(at, closed.getOrDefault(order, Instant.MAX),
                openedBy.get(order))));
        return out;
    }

    /**
     * V-F2. Per period of a {@code cafe_counter} zone linked to a station: prepared = Σ {@code itemCount} of the
     * station's tickets first {@code prepared} or {@code dispatched} in the period; handed over = Σ {@code out}
     * crossings in the period widened by {@code slackMinutes} after it. A finding when prepared − handed over ≥
     * {@code minShortfall} and handed over &lt; prepared × (1 − {@code tolerancePercent}/100).
     */
    private static void preparedNotHandedOver(Input in, LpObservedDay day, Zone zone, RuleConfig rule, Gaps gaps,
            List<Finding> out) {
        int bucket = Math.min(MAX_BUCKET_MINUTES, Math.max(5, rule.intValue("bucketMinutes", 60)));
        int slack = Math.min(MAX_SLACK_MINUTES, Math.max(0, rule.intValue("slackMinutes", 5)));
        BigDecimal tolerance = LpNumbers.zeroIfNull(rule.num("tolerancePercent"));
        BigDecimal minShortfall = LpNumbers.zeroIfNull(rule.num("minShortfall")).max(BigDecimal.ONE);
        BigDecimal min = confidence(rule, "0.5");
        List<Leaving> leaving = leaving(in).stream().filter(l -> sameName(zone.linkedId(), l.station())).toList();
        for (Instant from = day.start; from.isBefore(day.end); from = from.plus(Duration.ofMinutes(bucket))) {
            Instant to = from.plus(Duration.ofMinutes(bucket));
            if (to.isAfter(day.end)) {
                to = day.end;
            }
            BigDecimal prepared = BigDecimal.ZERO;
            List<String> ids = new ArrayList<>();
            boolean unknown = false;
            for (Leaving l : leaving) {
                if (l.at().isBefore(from) || !l.at().isBefore(to)) {
                    continue;
                }
                if (l.items() == null) {
                    unknown = true;
                    break;
                }
                prepared = prepared.add(l.items());
                ids.add(l.eventId());
            }
            if (unknown || prepared.signum() == 0 || !zone.liveDuring(from, to)) {
                continue;
            }
            Instant slackTo = to.plus(Duration.ofMinutes(slack));
            if (!covered(day, zone, from, slackTo, gaps)) {
                continue;
            }
            List<LedgerEvent> handed = crossings(day, zone, "out", from, slackTo, min);
            BigDecimal handedOver = count(handed);
            BigDecimal shortfall = prepared.subtract(handedOver);
            BigDecimal floor = prepared.multiply(BigDecimal.ONE.subtract(tolerance.movePointLeft(2)));
            if (shortfall.compareTo(minShortfall) < 0 || handedOver.compareTo(floor) >= 0) {
                continue;
            }
            List<String> evidence = new ArrayList<>(ids.stream().limit(40).toList());
            evidence.addAll(handed.stream().map(LedgerEvent::id).limit(40).toList());
            out.add(new Finding("V-F2", zone.id() + ":" + from, "Fewer items handed over at the cafe counter than were"
                    + " prepared", "zone", zone.id(), prepared, handedOver, shortfall.negate(), "count", null, null,
                    Obj.of("zoneId", zone.id(), "cameraId", zone.cameraId(), "station", zone.linkedId(),
                            "periodFrom", from.toString(), "periodTo", to.toString(), "slackMinutes", slack,
                            "tolerancePercent", tolerance, "minShortfall", minShortfall),
                    evidence));
        }
    }

    /** A sale at the till the zone is linked to; a zone linked to a counter, or not linked, takes every sale. */
    private static boolean atZone(Zone zone, LedgerEvent sale) {
        if (!"till".equals(zone.linkedType()) || zone.linkedId() == null) {
            return true;
        }
        String till = sale.text("tillId") != null ? sale.text("tillId") : sale.deviceId();
        return zone.linkedId().equals(till);
    }

    /** Tickets leaving the kitchen: the first {@code prepared} or {@code dispatched} of each, with its size. */
    private static List<Leaving> leaving(Input in) {
        Map<String, LedgerEvent> created = new LinkedHashMap<>();
        Map<String, LedgerEvent> first = new LinkedHashMap<>();
        for (LedgerEvent e : in.events()) {
            if (e.ticketId() == null) {
                continue;
            }
            if (KITCHEN_TICKET_CREATED.equals(e.type())) {
                created.put(e.ticketId(), e);
            } else if (KITCHEN_STAGE.equals(e.type()) && ("prepared".equals(e.text("stage"))
                    || "dispatched".equals(e.text("stage")))) {
                LedgerEvent seen = first.get(e.ticketId());
                if (seen == null || e.occurredAt().isBefore(seen.occurredAt())) {
                    first.put(e.ticketId(), e);
                }
            }
        }
        List<Leaving> out = new ArrayList<>();
        first.forEach((ticket, e) -> {
            LedgerEvent c = created.get(ticket);
            BigDecimal items = e.num("itemCount") != null ? e.num("itemCount") : c == null ? null : c.num("itemCount");
            String station = e.text("station") != null ? e.text("station") : c == null ? null : c.text("station");
            out.add(new Leaving(ticket, station, e.occurredAt(), items, e.id()));
        });
        return out;
    }

    /** What the records say should have crossed in a period; {@code null}: cannot tell (a ticket of unknown size). */
    private record Expected(BigDecimal count, List<String> eventIds, BigDecimal unitPrice) {}

    @FunctionalInterface
    private interface ExpectedOf {
        Expected of(Instant from, Instant to, Instant slackFrom, Instant slackTo);
    }

    private static void periods(LpObservedDay day, Zone zone, String code, RuleConfig rule, Gaps gaps,
            ExpectedOf expectedOf, List<Finding> out) {
        boolean kitchen = KITCHEN_PASS.equals(zone.type());
        int bucket = Math.min(MAX_BUCKET_MINUTES, Math.max(5, rule.intValue("bucketMinutes", 60)));
        int slack = Math.min(MAX_SLACK_MINUTES, Math.max(0, rule.intValue("slackMinutes", kitchen ? 10 : 5)));
        BigDecimal tolerance = LpNumbers.zeroIfNull(rule.num("tolerancePercent"));
        BigDecimal minExcess = LpNumbers.zeroIfNull(rule.num("minExcess"));
        BigDecimal min = confidence(rule, "0.5");
        for (Instant from = day.start; from.isBefore(day.end); from = from.plus(Duration.ofMinutes(bucket))) {
            Instant to = from.plus(Duration.ofMinutes(bucket));
            if (to.isAfter(day.end)) {
                to = day.end;
            }
            if (!zone.liveDuring(from, to)) {
                continue;
            }
            BigDecimal crossed = BigDecimal.ZERO;
            List<String> crossings = new ArrayList<>();
            for (LedgerEvent e : day.of(zone.id())) {
                if (!VISION_LINE_CROSSED.equals(e.type()) || e.occurredAt().isBefore(from)
                        || !e.occurredAt().isBefore(to) || !"out".equals(e.text("direction"))
                        || "person".equals(e.text("class")) || !observed(e, min)) {
                    continue;
                }
                BigDecimal count = e.num("count");
                crossed = crossed.add(count == null ? BigDecimal.ONE : count);
                crossings.add(e.id());
            }
            if (crossed.signum() == 0) {
                continue;
            }
            Instant mark = day.watermark(zone);
            if (mark == null || mark.isBefore(to)) {
                gaps.pending(to);
                continue;
            }
            if (day.gapDuring(zone.id(), from, to)) {
                gaps.gap = true;
                continue;
            }
            Instant slackFrom = from.minus(Duration.ofMinutes(slack));
            Instant slackTo = to.plus(Duration.ofMinutes(slack));
            Expected expected = expectedOf.of(from, to, slackFrom, slackTo);
            if (expected == null) {
                continue;
            }
            BigDecimal excess = crossed.subtract(expected.count());
            BigDecimal allowed = expected.count().multiply(BigDecimal.ONE.add(tolerance.movePointLeft(2)));
            if (excess.compareTo(minExcess) < 0 || crossed.compareTo(allowed) <= 0) {
                continue;
            }
            BigDecimal exposure = expected.unitPrice() == null ? null
                    : LpNumbers.round(excess.multiply(expected.unitPrice()));
            List<String> evidence = new ArrayList<>(crossings.stream().limit(40).toList());
            evidence.addAll(expected.eventIds().stream().limit(40).toList());
            boolean till = "till".equals(zone.linkedType()) && zone.linkedId() != null;
            out.add(new Finding(code, zone.id() + ":" + from, kitchen
                    ? "More plates left the kitchen pass than the tickets account for"
                    : "More items handed over at the cafe counter than were sold", till ? "till" : "zone",
                    till ? zone.linkedId() : zone.id(), expected.count(), crossed, excess, "count", exposure,
                    exposure == null ? null : "Estimated: excess items × average price of the items sold then",
                    Obj.of("zoneId", zone.id(), "cameraId", zone.cameraId(), "linkedType", zone.linkedType(),
                            "linkedId", zone.linkedId(), "periodFrom", from.toString(), "periodTo", to.toString(),
                            "slackMinutes", slack, "tolerancePercent", tolerance, "minExcess", minExcess,
                            "crossings", crossings.size()),
                    evidence));
        }
    }
}
