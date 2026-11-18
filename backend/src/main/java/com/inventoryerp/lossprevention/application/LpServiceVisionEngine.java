package com.inventoryerp.lossprevention.application;

import static com.inventoryerp.lossprevention.application.LpEventTypes.KITCHEN_STAGE;
import static com.inventoryerp.lossprevention.application.LpEventTypes.KITCHEN_TICKET_CREATED;
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
 * </ul>
 *
 * <p>A period not fully observed (a gap, after the camera's watermark) or with a ticket of unknown size is skipped,
 * never found, and the run says so.
 */
@Component
public class LpServiceVisionEngine implements LpEngine {

    public static final String VERSION = "1";
    static final String KITCHEN_PASS = "kitchen_pass";
    static final String CAFE_COUNTER = "cafe_counter";
    static final int MAX_BUCKET_MINUTES = 240;
    static final int MAX_SLACK_MINUTES = 15;

    private static final Set<String> TYPES = Set.of(VISION_LINE_CROSSED, VISION_GAP, KITCHEN_TICKET_CREATED,
            KITCHEN_STAGE, SALE_COMPLETED);

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
        return Set.of(KITCHEN_PASS, CAFE_COUNTER);
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
                    periods(day, zone, k1, gaps, (from, to, slackFrom, slackTo) -> {
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
                    periods(day, zone, f1, gaps, (from, to, slackFrom, slackTo) -> {
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
        return new Result(out, new Coverage(gaps.gap, gaps.pending));
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

    private static void periods(LpObservedDay day, Zone zone, RuleConfig rule, Gaps gaps, ExpectedOf expectedOf,
            List<Finding> out) {
        boolean kitchen = KITCHEN_PASS.equals(zone.type());
        String code = kitchen ? "V-K1" : "V-F1";
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
