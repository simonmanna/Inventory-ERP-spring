package com.inventoryerp.lossprevention.application;

import static com.inventoryerp.lossprevention.application.LpEventTypes.MEASUREMENT_GAP;
import static com.inventoryerp.lossprevention.application.LpEventTypes.POUR_MEASURED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.SALE_COMPLETED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.VISION_GAP;
import static com.inventoryerp.lossprevention.application.LpEventTypes.VISION_HANDLING;
import static com.inventoryerp.lossprevention.application.LpObservedDay.confidence;
import static com.inventoryerp.lossprevention.application.LpObservedDay.maps;
import static com.inventoryerp.lossprevention.application.LpObservedDay.observed;
import static com.inventoryerp.lossprevention.application.LpObservedDay.sameName;
import static com.inventoryerp.lossprevention.application.LpObservedDay.seconds;
import static com.inventoryerp.lossprevention.application.LpObservedDay.within;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.lossprevention.application.LpObservedDay.Device;
import com.inventoryerp.lossprevention.application.LpObservedDay.Zone;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Bar intelligence (LP3, docs/phases/LP3-bar-intelligence.md): pour meters and bar-counter cameras against what the
 * sales drew, over one calendar day of one branch.
 *
 * <ul>
 *   <li>B04: more volume poured of a product than its sales drew (recipe snapshot on the sale lines).</li>
 *   <li>B05: pours well above the standard measure at one device.</li>
 *   <li>V-B1: a bottle poured at the bar counter with no drink sale near it.</li>
 *   <li>V-B2: a pour the camera saw with no meter reading at that station.</li>
 * </ul>
 *
 * <p>Pure: everything it reads is in the input, the devices and zones of the run's context included.
 */
@Component
public class LpBarPourEngine implements LpEngine {

    public static final String VERSION = "1";
    static final String BAR_COUNTER = "bar_counter";

    private static final Set<String> TYPES = Set.of(POUR_MEASURED, MEASUREMENT_GAP, VISION_HANDLING, VISION_GAP,
            SALE_COMPLETED);

    @Override
    public String code() {
        return LpRuleCatalog.BAR_POUR;
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
        return Map.of();
    }

    @Override
    public Set<String> zoneTypes() {
        return Set.of(BAR_COUNTER);
    }

    @Override
    public boolean usesMeasurementDevices() {
        return true;
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

    private static Result run(Input in) {
        LpObservedDay day = new LpObservedDay(in);
        List<Finding> out = new ArrayList<>();
        pouredAboveSold(in, day, out);
        overPours(in, day, out);
        tiltsWithoutSale(in, day, out);
        Coverage coverage = tiltsWithoutReading(in, day, out);
        return new Result(out, coverage);
    }

    // ---------------------------------------------------------------- B04

    private static void pouredAboveSold(Input in, LpObservedDay day, List<Finding> out) {
        RuleConfig rule = in.active("B04");
        if (rule == null) {
            return;
        }
        Map<String, BigDecimal> poured = new LinkedHashMap<>();
        Map<String, List<String>> pourIds = new LinkedHashMap<>();
        for (LedgerEvent e : in.events()) {
            if (!POUR_MEASURED.equals(e.type()) || !day.owns(e.occurredAt()) || !"observed".equals(e.observationStatus())) {
                continue;
            }
            String product = productOf(e, day);
            BigDecimal volume = e.num("volumeMl");
            if (product == null || volume == null) {
                continue;
            }
            poured.merge(product, volume, BigDecimal::add);
            pourIds.computeIfAbsent(product, k -> new ArrayList<>()).add(e.id());
        }
        if (poured.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> sold = new LinkedHashMap<>();
        Map<String, BigDecimal> cost = new LinkedHashMap<>();
        Map<String, Integer> sales = new LinkedHashMap<>();
        for (LedgerEvent e : sales(in, day)) {
            for (Map<String, Object> line : maps(e.payload().get("lines"))) {
                if (!line.containsKey("ingredients")) {
                    // Sold before LP3, or by a sender without the recipe snapshot: the day cannot be compared.
                    return;
                }
                for (Map<String, Object> ing : maps(line.get("ingredients"))) {
                    String product = LpObservedDay.str(ing.get("productId"));
                    BigDecimal qty = LpNumbers.decimal(ing.get("quantity"));
                    if (product == null || qty == null || !poured.containsKey(product)) {
                        continue;
                    }
                    sold.merge(product, qty, BigDecimal::add);
                    sales.merge(product, 1, Integer::sum);
                    BigDecimal value = LpNumbers.decimal(ing.get("totalValue"));
                    if (value != null) {
                        cost.merge(product, value, BigDecimal::add);
                    }
                }
            }
        }
        BigDecimal tolerance = LpNumbers.zeroIfNull(rule.num("tolerancePercent"));
        BigDecimal minExcess = LpNumbers.zeroIfNull(rule.num("minExcessMl"));
        for (Map.Entry<String, BigDecimal> p : poured.entrySet()) {
            String product = p.getKey();
            BigDecimal pour = p.getValue();
            BigDecimal sale = sold.getOrDefault(product, BigDecimal.ZERO);
            BigDecimal excess = pour.subtract(sale);
            BigDecimal allowed = sale.multiply(BigDecimal.ONE.add(tolerance.movePointLeft(2)));
            if (excess.compareTo(minExcess) < 0 || pour.compareTo(allowed) <= 0) {
                continue;
            }
            BigDecimal saleCost = cost.get(product);
            BigDecimal perMl = saleCost != null && sale.signum() > 0 ? saleCost.divide(sale, 10, RoundingMode.HALF_UP)
                    : null;
            out.add(new Finding("B04", product, "More volume poured of a product than its sales drew", "product",
                    product, sale, pour, excess, "ml", perMl == null ? null : LpNumbers.round(excess.multiply(perMl)),
                    perMl == null ? null : "Excess ml × cost per ml of the volume sold",
                    Obj.of("pours", pourIds.get(product).size(), "salesDrawing", sales.getOrDefault(product, 0),
                            "tolerancePercent", tolerance, "minExcessMl", minExcess),
                    pourIds.get(product).stream().limit(50).toList()));
        }
    }

    // ---------------------------------------------------------------- B05

    private static void overPours(Input in, LpObservedDay day, List<Finding> out) {
        RuleConfig rule = in.active("B05");
        if (rule == null) {
            return;
        }
        BigDecimal tolerance = LpNumbers.zeroIfNull(rule.num("tolerancePercent"));
        int minCount = Math.max(1, rule.intValue("minCount", 5));
        BigDecimal minShare = LpNumbers.zeroIfNull(rule.num("minSharePercent"));
        for (Device device : day.devices.values()) {
            BigDecimal standard = device.standardPourMl();
            if (standard == null || standard.signum() <= 0) {
                continue;
            }
            BigDecimal limit = standard.multiply(BigDecimal.ONE.add(tolerance.movePointLeft(2)));
            int total = 0;
            List<String> over = new ArrayList<>();
            BigDecimal extra = BigDecimal.ZERO;
            for (LedgerEvent e : day.of(device.id())) {
                BigDecimal volume = e.num("volumeMl");
                if (!POUR_MEASURED.equals(e.type()) || !day.owns(e.occurredAt()) || volume == null) {
                    continue;
                }
                total++;
                if (volume.compareTo(limit) > 0) {
                    over.add(e.id());
                    extra = extra.add(volume.subtract(standard));
                }
            }
            if (over.size() < minCount || BigDecimal.valueOf(over.size() * 100L)
                    .compareTo(minShare.multiply(BigDecimal.valueOf(total))) < 0) {
                continue;
            }
            out.add(new Finding("B05", device.id(), "Pours well above the standard measure at one device", "device",
                    device.id(), BigDecimal.valueOf(minCount), BigDecimal.valueOf(over.size()),
                    BigDecimal.valueOf(over.size() - minCount), "count", null, null,
                    Obj.of("standardPourMl", standard, "tolerancePercent", tolerance, "pours", total, "overPours",
                            over.size(), "sharePercent", BigDecimal.valueOf(over.size() * 100L)
                                    .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP),
                            "extraMl", extra, "productId", device.productId(), "station", device.station(), "kind",
                            device.kind()),
                    over.stream().limit(50).toList()));
        }
    }

    // ---------------------------------------------------------------- V-B1

    private static void tiltsWithoutSale(Input in, LpObservedDay day, List<Finding> out) {
        RuleConfig rule = in.active("V-B1");
        if (rule == null) {
            return;
        }
        int before = seconds(rule, "windowBeforeSeconds", 120);
        int after = seconds(rule, "windowAfterSeconds", 600);
        BigDecimal min = confidence(rule, "0.6");
        List<LedgerEvent> drinkSales = sales(in, day).stream().filter(LpBarPourEngine::drinkSale).toList();
        for (LedgerEvent e : tilts(in, day, rule, min)) {
            Zone zone = day.zones.get(e.deviceId());
            Instant at = e.occurredAt();
            Instant from = at.minusSeconds(before);
            Instant to = at.plusSeconds(after);
            if (drinkSales.stream().anyMatch(s -> within(s, from, to))) {
                continue;
            }
            out.add(new Finding("V-B1", e.id(), "Bottle poured at the bar counter with no drink sale near it", "zone",
                    zone.id(), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE.negate(), "count", null, null,
                    detail(e, zone, Obj.of("windowBeforeSeconds", before, "windowAfterSeconds", after)),
                    List.of(e.id())));
        }
    }

    // ---------------------------------------------------------------- V-B2

    private static Coverage tiltsWithoutReading(Input in, LpObservedDay day, List<Finding> out) {
        RuleConfig rule = in.active("V-B2");
        if (rule == null) {
            return Coverage.FULL;
        }
        int window = seconds(rule, "windowSeconds", 30);
        BigDecimal min = confidence(rule, "0.6");
        boolean gap = false;
        Instant pending = null;
        for (LedgerEvent e : tilts(in, day, rule, min)) {
            Zone zone = day.zones.get(e.deviceId());
            if (!"station".equals(zone.linkedType()) || zone.linkedId() == null) {
                continue;
            }
            Instant at = e.occurredAt();
            Instant from = at.minusSeconds(window);
            Instant to = at.plusSeconds(window);
            List<Device> meters = day.devices.values().stream()
                    .filter(d -> sameName(d.station(), zone.linkedId()) && d.liveDuring(from, to)).toList();
            if (meters.isEmpty()) {
                continue;
            }
            boolean covered = true;
            for (Device d : meters) {
                Instant mark = day.watermark(d);
                if (mark == null || mark.isBefore(to)) {
                    covered = false;
                    pending = pending == null || to.isAfter(pending) ? to : pending;
                }
                if (day.gapDuring(d.id(), from, to)) {
                    covered = false;
                    gap = true;
                }
            }
            if (!covered || meters.stream().anyMatch(d -> day.of(d.id()).stream()
                    .anyMatch(r -> POUR_MEASURED.equals(r.type()) && pourOverlaps(r, from, to)))) {
                continue;
            }
            out.add(new Finding("V-B2", e.id(), "Pour seen by the camera with no meter reading", "zone", zone.id(),
                    BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE.negate(), "count", null, null,
                    detail(e, zone, Obj.of("station", zone.linkedId(), "windowSeconds", window, "deviceIds",
                            meters.stream().map(Device::id).toList())),
                    List.of(e.id())));
        }
        return new Coverage(gap, pending);
    }

    // ---------------------------------------------------------------- helpers

    private static List<LedgerEvent> sales(Input in, LpObservedDay day) {
        return in.events().stream().filter(e -> SALE_COMPLETED.equals(e.type()) && day.owns(e.occurredAt()))
                .toList();
    }

    /** A sale with a line that drew an ingredient; a sale without the recipe snapshot may be one, so it counts. */
    static boolean drinkSale(LedgerEvent sale) {
        List<Map<String, Object>> lines = maps(sale.payload().get("lines"));
        if (lines.isEmpty()) {
            return true;
        }
        for (Map<String, Object> line : lines) {
            if (!line.containsKey("ingredients") || !maps(line.get("ingredients")).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Bottles tilted over a bar-counter zone on the day, long enough and sure enough. */
    private static List<LedgerEvent> tilts(Input in, LpObservedDay day, RuleConfig rule, BigDecimal min) {
        BigDecimal minTilt = LpNumbers.zeroIfNull(rule.num("minTiltSeconds"));
        List<LedgerEvent> out = new ArrayList<>();
        for (LedgerEvent e : in.events()) {
            if (!VISION_HANDLING.equals(e.type()) || !day.owns(e.occurredAt()) || !"tilted".equals(e.text("action"))
                    || !observed(e, min)) {
                continue;
            }
            Zone zone = day.zones.get(e.deviceId());
            BigDecimal duration = e.num("durationSeconds");
            if (zone == null || !BAR_COUNTER.equals(zone.type())
                    || duration != null && duration.compareTo(minTilt) < 0) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    /** The reading's own pour time overlaps the window: from its start to its end, else its moment. */
    private static boolean pourOverlaps(LedgerEvent r, Instant from, Instant to) {
        Instant started = LpObservedDay.instant(r.text("startedAt"));
        Instant ended = LpObservedDay.instant(r.text("endedAt"));
        Instant a = started != null ? started : r.occurredAt();
        Instant b = ended != null ? ended : r.occurredAt();
        return !a.isAfter(to) && !b.isBefore(from);
    }

    private static String productOf(LedgerEvent e, LpObservedDay day) {
        String own = e.text("productId");
        if (own != null && !own.isBlank()) {
            return own;
        }
        Device d = day.devices.get(e.deviceId());
        return d == null ? null : d.productId();
    }

    private static Map<String, Object> detail(LedgerEvent e, Zone zone, Map<String, Object> extra) {
        Map<String, Object> d = Obj.of("zoneId", zone.id(), "cameraId", zone.cameraId(), "clipId", e.text("clipId"),
                "pouredAt", e.occurredAt().toString(), "object", e.text("object"), "durationSeconds",
                e.num("durationSeconds"), "confidence", e.confidence());
        d.putAll(extra);
        return d;
    }
}
