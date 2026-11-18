package com.inventoryerp.lossprevention.application;

import static com.inventoryerp.lossprevention.application.LpEventTypes.ORDER_CANCELLED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.REFUND_ISSUED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.SALE_COMPLETED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.SALE_LINE_VOIDED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.STOCK_MOVEMENT;
import static com.inventoryerp.lossprevention.application.LpEventTypes.VISION_GAP;
import static com.inventoryerp.lossprevention.application.LpEventTypes.VISION_HANDLING;
import static com.inventoryerp.lossprevention.application.LpEventTypes.VISION_LINE_CROSSED;
import static com.inventoryerp.lossprevention.application.LpObservedDay.confidence;
import static com.inventoryerp.lossprevention.application.LpObservedDay.maps;
import static com.inventoryerp.lossprevention.application.LpObservedDay.observed;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.lossprevention.application.LpObservedDay.Zone;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Retail checkout and store room seen by a camera (LP5, docs/phases/LP5-retail-checkout.md), over one calendar day of a
 * branch:
 *
 * <ul>
 *   <li>V-R1: more items passed a checkout's scanner than the sale they went into records.</li>
 *   <li>V-R2: items carried around the scanner that the sale does not explain.</li>
 *   <li>V-S1: goods carried out of a store room with no stock movement out of its location recorded then.</li>
 * </ul>
 *
 * <p>A checkout is judged per transaction batch: the items seen over the scanner between two sales of its till. A
 * batch or period not fully observed (a gap, after the camera's watermark), or one the records cannot size (a sale
 * without lines, a refund in the batch), is skipped, never found, and the run says so.
 */
@Component
public class LpRetailVisionEngine implements LpEngine {

    public static final String VERSION = "1";
    static final String SCAN_AREA = "scan_area";
    static final String STORAGE = "storage";
    /** Events are read this far either side of the day: the longest batch, and the widest slack. */
    static final int MARGIN_SECONDS = 1800;
    static final int MAX_BATCH_MINUTES = 30;
    static final int MAX_SLACK_MINUTES = 30;
    static final int MAX_BUCKET_MINUTES = 240;

    private static final Set<String> TYPES = Set.of(VISION_LINE_CROSSED, VISION_HANDLING, VISION_GAP, SALE_COMPLETED,
            SALE_LINE_VOIDED, ORDER_CANCELLED, REFUND_ISSUED, STOCK_MOVEMENT);

    @Override
    public String code() {
        return LpRuleCatalog.RETAIL_VISION;
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
        // Everything a batch or period needs is within the time margin.
        return Map.of();
    }

    @Override
    public Set<String> zoneTypes() {
        return Set.of(SCAN_AREA, STORAGE);
    }

    @Override
    public int timeMarginSeconds() {
        return MARGIN_SECONDS;
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

    /** Batches and periods skipped for want of observation. */
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
        RuleConfig r1 = in.active("V-R1");
        RuleConfig r2 = in.active("V-R2");
        if (r1 != null || r2 != null) {
            for (Zone zone : day.zones.values()) {
                if (SCAN_AREA.equals(zone.type()) && "till".equals(zone.linkedType()) && zone.linkedId() != null) {
                    checkout(in, day, zone, r1, r2, gaps, out);
                }
            }
        }
        RuleConfig s1 = in.active("V-S1");
        if (s1 != null) {
            for (Zone zone : day.zones.values()) {
                if (STORAGE.equals(zone.type()) && "location".equals(zone.linkedType()) && zone.linkedId() != null) {
                    storeRoom(in, day, zone, s1, gaps, out);
                }
            }
        }
        return new Result(out, new Coverage(gaps.gap, gaps.pending));
    }

    // ---------------------------------------------------------------- V-R1, V-R2: checkout batches

    /** The till a POS event happened at: its {@code payload.tillId}, else the device it came from. */
    static String tillOf(LedgerEvent e) {
        return e.text("tillId") != null ? e.text("tillId") : e.deviceId();
    }

    /**
     * Items a quantity stands for: a whole quantity is that many items, a fraction (a weighed item) is one, zero or
     * less is none.
     */
    static BigDecimal items(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal q = quantity.stripTrailingZeros();
        return q.scale() <= 0 ? q.setScale(0, RoundingMode.UNNECESSARY) : BigDecimal.ONE;
    }

    private static boolean inBatch(Instant t, Instant from, Instant to) {
        return t.isAfter(from) && !t.isAfter(to);
    }

    private static void checkout(Input in, LpObservedDay day, Zone zone, RuleConfig r1, RuleConfig r2, Gaps gaps,
            List<Finding> out) {
        String till = zone.linkedId();
        List<LedgerEvent> closing = new ArrayList<>();
        List<LedgerEvent> refunds = new ArrayList<>();
        Map<String, List<LedgerEvent>> voidsByOrder = new LinkedHashMap<>();
        for (LedgerEvent e : in.events()) {
            if ((SALE_COMPLETED.equals(e.type()) || ORDER_CANCELLED.equals(e.type())) && till.equals(tillOf(e))) {
                closing.add(e);
            } else if (REFUND_ISSUED.equals(e.type()) && till.equals(tillOf(e))) {
                refunds.add(e);
            } else if (SALE_LINE_VOIDED.equals(e.type()) && e.orderId() != null) {
                voidsByOrder.computeIfAbsent(e.orderId(), k -> new ArrayList<>()).add(e);
            }
        }
        closing.sort(Comparator.comparing(LedgerEvent::occurredAt).thenComparingLong(LedgerEvent::seq));
        RuleConfig sizing = r1 != null ? r1 : r2;
        int batchMinutes = Math.min(MAX_BATCH_MINUTES, Math.max(1, sizing.intValue("maxBatchMinutes", 20)));
        Instant previous = null;
        for (LedgerEvent close : closing) {
            Instant to = close.occurredAt();
            Instant earliest = to.minus(Duration.ofMinutes(batchMinutes));
            Instant from = previous == null || previous.isBefore(earliest) ? earliest : previous;
            previous = to;
            if (!SALE_COMPLETED.equals(close.type()) || !day.owns(to) || !zone.liveDuring(from, to)) {
                continue;
            }
            Batch b = batch(day, zone, from, to, r1, r2);
            if (b.passed.signum() == 0 && b.bypassed.signum() == 0) {
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
            if (refunds.stream().anyMatch(r -> inBatch(r.occurredAt(), from, to))) {
                continue;
            }
            Object lines = close.payload() == null ? null : close.payload().get("lines");
            if (!(lines instanceof List<?> list) || list.isEmpty()) {
                continue;
            }
            BigDecimal onSale = BigDecimal.ZERO;
            BigDecimal amount = BigDecimal.ZERO;
            for (Map<String, Object> line : maps(lines)) {
                onSale = onSale.add(items(LpNumbers.decimal(line.get("quantity"))));
                BigDecimal total = LpNumbers.decimal(line.get("total"));
                amount = amount.add(total == null ? BigDecimal.ZERO : total);
            }
            if (close.num("total") != null) {
                amount = close.num("total");
            }
            Map<String, BigDecimal> voided = new LinkedHashMap<>();
            List<String> voidIds = new ArrayList<>();
            for (LedgerEvent v : close.orderId() == null ? List.<LedgerEvent>of()
                    : voidsByOrder.getOrDefault(close.orderId(), List.of())) {
                voided.merge(v.lineId() != null ? v.lineId() : v.id(), items(v.num("quantity")), BigDecimal::max);
                voidIds.add(v.id());
            }
            BigDecimal voidedItems = voided.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal expected = onSale.add(voidedItems);
            BigDecimal price = onSale.signum() > 0 && amount.signum() > 0
                    ? amount.divide(onSale, 6, RoundingMode.HALF_UP) : null;
            Context c = new Context(zone, close, from, to, onSale, voidedItems, price, voidIds);
            if (r1 != null) {
                passedTooMany(c, b, expected, r1, out);
            }
            if (r2 != null) {
                bypassed(c, b, expected, r2, out);
            }
        }
    }

    /** What the camera saw in one batch, at each rule's confidence. */
    private record Batch(BigDecimal out, BigDecimal in, BigDecimal passed, BigDecimal bypassed,
            List<String> crossings, List<String> bypasses) {}

    /** One judged batch: the zone, the sale that closed it and what the sale says. */
    private record Context(Zone zone, LedgerEvent sale, Instant from, Instant to, BigDecimal onSale,
            BigDecimal voided, BigDecimal price, List<String> voidIds) {}

    private static Batch batch(LpObservedDay day, Zone zone, Instant from, Instant to, RuleConfig r1, RuleConfig r2) {
        BigDecimal crossMin = confidence(r1 != null ? r1 : r2, "0.5");
        BigDecimal bypassMin = confidence(r2 != null ? r2 : r1, "0.5");
        BigDecimal outCount = BigDecimal.ZERO;
        BigDecimal inCount = BigDecimal.ZERO;
        BigDecimal bypassed = BigDecimal.ZERO;
        List<String> crossings = new ArrayList<>();
        List<String> bypasses = new ArrayList<>();
        for (LedgerEvent e : day.of(zone.id())) {
            if (!inBatch(e.occurredAt(), from, to)) {
                continue;
            }
            if (VISION_LINE_CROSSED.equals(e.type()) && !"person".equals(e.text("class")) && observed(e, crossMin)) {
                BigDecimal count = e.num("count") == null ? BigDecimal.ONE : e.num("count");
                if ("out".equals(e.text("direction"))) {
                    outCount = outCount.add(count);
                    crossings.add(e.id());
                } else if ("in".equals(e.text("direction"))) {
                    inCount = inCount.add(count);
                    crossings.add(e.id());
                }
            } else if (VISION_HANDLING.equals(e.type()) && LpEventTypes.ACTION_BYPASSED.equals(e.text("action"))
                    && !"person".equals(e.text("object")) && observed(e, bypassMin)) {
                bypassed = bypassed.add(BigDecimal.ONE);
                bypasses.add(e.id());
            }
        }
        BigDecimal passed = outCount.subtract(inCount).max(BigDecimal.ZERO);
        return new Batch(outCount, inCount, passed, bypassed, crossings, bypasses);
    }

    private static void passedTooMany(Context c, Batch b, BigDecimal expected, RuleConfig rule, List<Finding> out) {
        BigDecimal tolerance = LpNumbers.zeroIfNull(rule.num("tolerancePercent"));
        BigDecimal minExcess = LpNumbers.zeroIfNull(rule.num("minExcess"));
        BigDecimal excess = b.passed().subtract(expected);
        BigDecimal allowed = expected.multiply(BigDecimal.ONE.add(tolerance.movePointLeft(2)));
        if (excess.compareTo(minExcess) < 0 || b.passed().compareTo(allowed) <= 0 || excess.signum() <= 0) {
            return;
        }
        Map<String, Object> detail = detail(c, b);
        detail.put("tolerancePercent", tolerance);
        detail.put("minExcess", minExcess);
        out.add(finding("V-R1", "More items passed the scanner than the sale records", c, expected, b.passed(),
                excess, detail, b.crossings()));
    }

    private static void bypassed(Context c, Batch b, BigDecimal expected, RuleConfig rule, List<Finding> out) {
        BigDecimal minCount = LpNumbers.zeroIfNull(rule.num("minCount")).max(BigDecimal.ONE);
        BigDecimal explained = expected.subtract(b.passed()).max(BigDecimal.ZERO);
        BigDecimal unexplained = b.bypassed().subtract(explained);
        if (unexplained.compareTo(minCount) < 0) {
            return;
        }
        Map<String, Object> detail = detail(c, b);
        detail.put("explainedBySale", explained);
        detail.put("minCount", minCount);
        out.add(finding("V-R2", "Items carried past the scanner that the sale does not explain", c, explained,
                b.bypassed(), unexplained, detail, b.bypasses()));
    }

    private static Map<String, Object> detail(Context c, Batch b) {
        LedgerEvent sale = c.sale();
        Map<String, Object> d = new LinkedHashMap<>(Obj.of("zoneId", c.zone().id(), "cameraId", c.zone().cameraId(),
                "tillId", c.zone().linkedId(), "transactionId", sale.transactionId(), "orderId", sale.orderId(),
                "batchFrom", c.from().toString(), "batchTo", c.to().toString(), "itemsOnSale", c.onSale(),
                "voidedItems", c.voided(), "crossedOut", b.out(), "crossedIn", b.in(), "passed", b.passed(),
                "bypassed", b.bypassed()));
        return d;
    }

    private static Finding finding(String code, String title, Context c, BigDecimal expected, BigDecimal observed,
            BigDecimal excess, Map<String, Object> detail, List<String> observations) {
        LedgerEvent sale = c.sale();
        boolean byActor = sale.actorId() != null;
        BigDecimal exposure = c.price() == null ? null : LpNumbers.round(excess.multiply(c.price()));
        List<String> evidence = new ArrayList<>();
        evidence.add(sale.id());
        evidence.addAll(c.voidIds().stream().limit(20).toList());
        evidence.addAll(observations.stream().limit(60).toList());
        String transaction = sale.transactionId() != null ? sale.transactionId() : sale.id();
        return new Finding(code, c.zone().id() + ":" + transaction, title, byActor ? "actor" : "till",
                byActor ? sale.actorId() : c.zone().linkedId(), expected, observed, excess, "count", exposure,
                exposure == null ? null : "Estimated: excess items × the sale's average item price", detail,
                evidence);
    }

    // ---------------------------------------------------------------- V-S1: store room carry-outs

    private static void storeRoom(Input in, LpObservedDay day, Zone zone, RuleConfig rule, Gaps gaps,
            List<Finding> out) {
        int bucket = Math.min(MAX_BUCKET_MINUTES, Math.max(5, rule.intValue("bucketMinutes", 60)));
        int slack = Math.min(MAX_SLACK_MINUTES, Math.max(0, rule.intValue("slackMinutes", 30)));
        BigDecimal minCount = LpNumbers.zeroIfNull(rule.num("minCount")).max(BigDecimal.ONE);
        BigDecimal min = confidence(rule, "0.5");
        String location = zone.linkedId();
        List<LedgerEvent> movements = in.events().stream().filter(e -> STOCK_MOVEMENT.equals(e.type())
                && location.equals(e.text("locationId")) && "out".equals(e.text("direction"))).toList();
        for (Instant from = day.start; from.isBefore(day.end); from = from.plus(Duration.ofMinutes(bucket))) {
            Instant to = from.plus(Duration.ofMinutes(bucket));
            if (to.isAfter(day.end)) {
                to = day.end;
            }
            if (!zone.liveDuring(from, to)) {
                continue;
            }
            BigDecimal carried = BigDecimal.ZERO;
            List<String> crossings = new ArrayList<>();
            for (LedgerEvent e : day.of(zone.id())) {
                if (!VISION_LINE_CROSSED.equals(e.type()) || e.occurredAt().isBefore(from)
                        || !e.occurredAt().isBefore(to) || !"out".equals(e.text("direction"))
                        || "person".equals(e.text("class")) || !observed(e, min)) {
                    continue;
                }
                carried = carried.add(e.num("count") == null ? BigDecimal.ONE : e.num("count"));
                crossings.add(e.id());
            }
            if (carried.compareTo(minCount) < 0) {
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
            if (movements.stream().anyMatch(m -> !m.occurredAt().isBefore(slackFrom)
                    && !m.occurredAt().isAfter(slackTo))) {
                continue;
            }
            out.add(new Finding("V-S1", zone.id() + ":" + from,
                    "Goods carried out of a store room with no stock movement recorded", "location", location,
                    BigDecimal.ZERO, carried, carried, "count", null, null,
                    Obj.of("zoneId", zone.id(), "cameraId", zone.cameraId(), "locationId", location, "periodFrom",
                            from.toString(), "periodTo", to.toString(), "slackMinutes", slack, "minCount", minCount,
                            "crossings", crossings.size()),
                    crossings.stream().limit(60).toList()));
        }
    }
}
