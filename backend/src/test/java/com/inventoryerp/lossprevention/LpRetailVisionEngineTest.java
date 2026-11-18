package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.lossprevention.application.LpEngine.Coverage;
import com.inventoryerp.lossprevention.application.LpEngine.Finding;
import com.inventoryerp.lossprevention.application.LpEngine.Input;
import com.inventoryerp.lossprevention.application.LpEngine.LedgerEvent;
import com.inventoryerp.lossprevention.application.LpEngine.RuleConfig;
import com.inventoryerp.lossprevention.application.LpRetailVisionEngine;
import com.inventoryerp.lossprevention.application.LpRuleCatalog;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The retail rules of LP5 on synthetic events: V-R1 and V-R2 per checkout batch, V-S1 per store-room period. How well a
 * camera counts items is not measured here.
 */
class LpRetailVisionEngineTest {

    static final LocalDate DAY = LocalDate.parse("2026-11-10");
    final LpRetailVisionEngine engine = new LpRetailVisionEngine();
    int seq;

    static Instant at(String time) {
        return Instant.parse(DAY + "T" + time + "Z");
    }

    /** The catalog's rules; those named in {@code on} are switched on. */
    static Map<String, RuleConfig> rules(Set<String> on, Map<String, Map<String, Object>> overrides) {
        Map<String, RuleConfig> out = new LinkedHashMap<>();
        for (LpRuleCatalog.Definition d : LpRuleCatalog.definitions()) {
            Map<String, Object> params = new LinkedHashMap<>(d.params());
            params.putAll(overrides.getOrDefault(d.code(), Map.of()));
            out.put(d.code(), new RuleConfig(d.code(), 0, d.enabled() || on.contains(d.code()), d.severity(),
                    params));
        }
        return out;
    }

    LedgerEvent pos(String type, String time, String actor, String transaction, String order, String line,
            Map<String, Object> payload) {
        seq++;
        return new LedgerEvent("p" + seq, seq, type, actor, transaction, order, null, line, null, DAY, at(time),
                "order", "observed", payload);
    }

    /** A sale at a till; each line is {quantity, total}. */
    LedgerEvent sale(String time, String tx, String order, String till, String actor, Object... lines) {
        List<Map<String, Object>> l = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < lines.length; i += 2) {
            l.add(Obj.of("quantity", lines[i], "total", lines[i + 1]));
            total = total.add(new BigDecimal(String.valueOf(lines[i + 1])));
        }
        return pos("sale.completed", time, actor, tx, order, null, Obj.of("tillId", till, "total", total, "lines", l));
    }

    LedgerEvent voided(String time, String order, String line, Object qty) {
        return pos("sale.line_voided", time, "cashier-1", null, order, line, Obj.of("quantity", qty, "tillId",
                "till-1"));
    }

    LedgerEvent observation(String type, String time, String zone, Map<String, Object> payload, String confidence) {
        seq++;
        return new LedgerEvent("v" + seq, seq, type, null, null, null, null, null, null, DAY, at(time), "zone",
                "observed", payload, zone, new BigDecimal(confidence));
    }

    LedgerEvent crossed(String time, String zone, String direction, String cls, int count) {
        return observation("vision.line_crossed", time, zone, Obj.of("cameraId", "c1", "class", cls, "direction",
                direction, "count", count), "0.9");
    }

    LedgerEvent bypass(String time, String object, String confidence) {
        return observation("vision.handling", time, "zs", Obj.of("cameraId", "c1", "object", object, "action",
                "bypassed"), confidence);
    }

    LedgerEvent gap(String from, String to, String zone) {
        seq++;
        return new LedgerEvent("g" + seq, seq, "vision.gap", null, null, null, null, null, null, DAY, at(from), "zone",
                "unavailable", Obj.of("cameraId", "c1", "from", at(from).toString(), "to", at(to).toString(),
                        "reason", "stream_lost"), zone, BigDecimal.ONE);
    }

    LedgerEvent movement(String time, String location, String direction) {
        seq++;
        return new LedgerEvent("m" + seq, seq, "stock.movement", "storekeeper", null, null, null, "il" + seq, null, DAY,
                at(time), "item", "observed", Obj.of("locationId", location, "direction", direction, "quantity", 4,
                        "kind", "transfer_out"));
    }

    static Map<String, Object> context(String mark) {
        return Obj.of("timeZone", "UTC",
                "zones", List.of(
                        Obj.of("id", "zs", "cameraId", "c1", "agentId", "a1", "zoneType", "scan_area",
                                "linkedType", "till", "linkedId", "till-1", "enabled", true, "createdAt",
                                "2026-11-01T00:00:00Z"),
                        Obj.of("id", "zr", "cameraId", "c1", "agentId", "a1", "zoneType", "storage",
                                "linkedType", "location", "linkedId", "loc-1", "enabled", true, "createdAt",
                                "2026-11-01T00:00:00Z")),
                "cameras", List.of(Obj.of("id", "c1", "observedThrough", mark)),
                "agents", List.of(Obj.of("id", "a1", "observedThrough", mark)));
    }

    static final Map<String, Object> SEEN = context("2026-11-11T01:00:00Z");
    static final Set<String> CHECKOUT = Set.of("V-R1", "V-R2");

    List<Finding> run(List<LedgerEvent> events, Set<String> on) {
        return run(events, on, Map.of(), SEEN);
    }

    List<Finding> run(List<LedgerEvent> events, Set<String> on, Map<String, Map<String, Object>> overrides,
            Map<String, Object> context) {
        return engine.evaluate(new Input("b1", DAY, false, events, rules(on, overrides), context));
    }

    static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::ruleCode).sorted().toList();
    }

    /** Six items passed the scanner for a sale of three, between the previous sale and this one. */
    List<LedgerEvent> sixForThree() {
        List<LedgerEvent> events = new ArrayList<>();
        events.add(sale("09:58:00", "tx0", "o0", "till-1", "cashier-1", 1, 5));
        for (int i = 0; i < 6; i++) {
            events.add(crossed("10:0" + i + ":10", "zs", "out", "item", 1));
        }
        events.add(sale("10:07:00", "tx1", "o1", "till-1", "cashier-1", 2, 20, 1, 10));
        return events;
    }

    // ---------------------------------------------------------------- V-R1

    @Test
    @DisplayName("V-R1 and V-R2 ship off: the catalog's defaults find nothing")
    void offByDefault() {
        assertThat(LpRuleCatalog.definitions().stream().filter(d -> CHECKOUT.contains(d.code()))
                .noneMatch(LpRuleCatalog.Definition::enabled)).isTrue();
        assertThat(run(sixForThree(), Set.of())).isEmpty();
    }

    @Test
    @DisplayName("V-R1: more items over the scanner than the sale records is found, against the batch's own sale")
    void passedTooMany() {
        List<Finding> f = run(sixForThree(), CHECKOUT);
        assertThat(codes(f)).containsExactly("V-R1");
        Finding r1 = f.getFirst();
        assertThat(r1.key()).isEqualTo("zs:tx1");
        assertThat(r1.subjectType()).isEqualTo("actor");
        assertThat(r1.subjectId()).isEqualTo("cashier-1");
        assertThat(r1.expected()).isEqualByComparingTo("3");
        assertThat(r1.observed()).isEqualByComparingTo("6");
        assertThat(r1.delta()).isEqualByComparingTo("3");
        // 30 for 3 items: 10 each.
        assertThat(r1.exposureAmount()).isEqualByComparingTo("30");
        assertThat(r1.detail()).containsEntry("tillId", "till-1").containsEntry("transactionId", "tx1");
        assertThat(r1.evidenceEventIds()).hasSize(7);
        // The batch starts at the till's previous sale, not 20 minutes back.
        assertThat(r1.detail()).containsEntry("batchFrom", at("09:58:00").toString());
    }

    @Test
    @DisplayName("V-R1: a weighed item counts once, voided items count, items put back count off")
    void expectedItems() {
        List<LedgerEvent> events = new ArrayList<>();
        // 1.25 kg of apples is one item; 2 voided were passed too: expected 1 + 2 + 2 = 5.
        events.add(voided("10:01:00", "o1", "line-a", 1));
        events.add(voided("10:01:30", "o1", "line-a", 2));
        for (int i = 0; i < 6; i++) {
            events.add(crossed("10:02:0" + i, "zs", "out", "item", 1));
        }
        events.add(crossed("10:03:00", "zs", "in", "item", 1));
        events.add(sale("10:05:00", "tx1", "o1", "till-1", "cashier-1", "1.25", 4, 2, 6));
        // Out 6, back 1: 5 passed, 5 expected.
        assertThat(run(events, CHECKOUT)).isEmpty();
        events.add(crossed("10:04:00", "zs", "out", "item", 2));
        List<Finding> f = run(events, CHECKOUT);
        assertThat(codes(f)).containsExactly("V-R1");
        assertThat(f.getFirst().detail()).containsEntry("voidedItems", new BigDecimal("2"))
                .containsEntry("itemsOnSale", new BigDecimal("3"));
        assertThat(f.getFirst().observed()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("V-R1: people, low confidence, another till's sales and items before the batch limit are not counted")
    void whatCounts() {
        List<LedgerEvent> events = new ArrayList<>();
        // 40 minutes before the sale: outside the 20-minute batch.
        for (int i = 0; i < 5; i++) {
            events.add(crossed("09:2" + i + ":00", "zs", "out", "item", 1));
        }
        events.add(crossed("10:01:00", "zs", "out", "person", 3));
        events.add(observation("vision.line_crossed", "10:02:00", "zs", Obj.of("cameraId", "c1", "class", "item",
                "direction", "out", "count", 4), "0.2"));
        events.add(sale("10:03:00", "txB", "oB", "till-2", "cashier-2", 1, 10));
        events.add(sale("10:05:00", "tx1", "o1", "till-1", "cashier-1", 1, 10));
        assertThat(run(events, CHECKOUT)).isEmpty();
    }

    @Test
    @DisplayName("V-R1: a cancelled close, a refund, a gap or no lines skips a batch; after the watermark it waits")
    void skipped() {
        List<LedgerEvent> cancelled = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cancelled.add(crossed("10:0" + i + ":10", "zs", "out", "item", 1));
        }
        cancelled.add(pos("order.cancelled", "10:06:00", "cashier-1", null, "o9", null, Obj.of("tillId", "till-1")));
        cancelled.add(sale("10:08:00", "tx1", "o1", "till-1", "cashier-1", 1, 10));
        // The items belong to the cancelled order's batch, which is not judged; the sale's batch is empty.
        assertThat(run(cancelled, CHECKOUT)).isEmpty();

        List<LedgerEvent> refunded = new ArrayList<>(sixForThree());
        refunded.add(pos("refund.issued", "10:03:00", "cashier-1", "tx0", null, null, Obj.of("tillId", "till-1",
                "amount", 5)));
        assertThat(run(refunded, CHECKOUT)).isEmpty();

        List<LedgerEvent> gapped = new ArrayList<>(sixForThree());
        gapped.add(gap("10:04:00", "10:04:30", "zs"));
        assertThat(run(gapped, CHECKOUT)).isEmpty();
        assertThat(engine.coverage(new Input("b1", DAY, false, gapped, rules(CHECKOUT, Map.of()), SEEN)).gap())
                .isTrue();

        List<LedgerEvent> noLines = new ArrayList<>(sixForThree());
        noLines.removeLast();
        noLines.add(pos("sale.completed", "10:07:00", "cashier-1", "tx1", "o1", null, Obj.of("tillId", "till-1",
                "total", 30)));
        assertThat(run(noLines, CHECKOUT)).isEmpty();

        Map<String, Object> early = context("2026-11-10T10:06:00Z");
        assertThat(run(sixForThree(), CHECKOUT, Map.of(), early)).isEmpty();
        Coverage c = engine.coverage(new Input("b1", DAY, false, sixForThree(), rules(CHECKOUT, Map.of()), early));
        assertThat(c.pendingUntil()).isEqualTo(at("10:07:00"));
    }

    @Test
    @DisplayName("V-R1: a sale with no cashier is about the till")
    void tillSubject() {
        List<LedgerEvent> events = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            events.add(crossed("10:0" + i + ":10", "zs", "out", "item", 1));
        }
        events.add(sale("10:07:00", "tx1", "o1", "till-1", null, 1, 10));
        Finding f = run(events, CHECKOUT).getFirst();
        assertThat(f.subjectType()).isEqualTo("till");
        assertThat(f.subjectId()).isEqualTo("till-1");
    }

    // ---------------------------------------------------------------- V-R2

    @Test
    @DisplayName("V-R2: items carried around the scanner are found unless the sale explains them")
    void bypassed() {
        List<LedgerEvent> events = new ArrayList<>();
        events.add(crossed("10:01:00", "zs", "out", "item", 2));
        events.add(bypass("10:02:00", "item", "0.9"));
        events.add(bypass("10:03:00", "item", "0.9"));
        events.add(bypass("10:03:30", "item", "0.1"));
        events.add(bypass("10:03:40", "person", "0.9"));
        // Three on the sale, two seen over the scanner: one bypass is a keyed-in item, one is not explained.
        events.add(sale("10:05:00", "tx1", "o1", "till-1", "cashier-1", 3, 30));
        List<Finding> f = run(events, CHECKOUT);
        assertThat(codes(f)).containsExactly("V-R2");
        assertThat(f.getFirst().expected()).isEqualByComparingTo("1");
        assertThat(f.getFirst().observed()).isEqualByComparingTo("2");
        assertThat(f.getFirst().delta()).isEqualByComparingTo("1");
        assertThat(f.getFirst().exposureAmount()).isEqualByComparingTo("10");
        // With four on the sale both bypasses are explained.
        events.removeLast();
        events.add(sale("10:05:00", "tx1", "o1", "till-1", "cashier-1", 4, 40));
        assertThat(run(events, CHECKOUT)).isEmpty();
        // V-R2 alone runs without V-R1.
        events.removeLast();
        events.add(sale("10:05:00", "tx1", "o1", "till-1", "cashier-1", 2, 20));
        assertThat(codes(run(events, Set.of("V-R2")))).containsExactly("V-R2");
    }

    // ---------------------------------------------------------------- V-S1

    @Test
    @DisplayName("V-S1: goods carried out of the store room with no stock movement out of its location are found")
    void storeRoom() {
        List<LedgerEvent> events = new ArrayList<>();
        events.add(crossed("14:10:00", "zr", "out", "bag", 1));
        events.add(crossed("14:20:00", "zr", "out", "bag", 2));
        events.add(crossed("14:25:00", "zr", "out", "person", 5));
        events.add(crossed("14:30:00", "zr", "in", "bag", 4));
        events.add(movement("13:00:00", "loc-1", "out"));
        events.add(movement("14:40:00", "loc-2", "out"));
        events.add(movement("14:45:00", "loc-1", "in"));
        List<Finding> f = run(events, Set.of());
        assertThat(codes(f)).containsExactly("V-S1");
        Finding s1 = f.getFirst();
        assertThat(s1.key()).isEqualTo("zr:" + at("14:00:00"));
        assertThat(s1.subjectType()).isEqualTo("location");
        assertThat(s1.subjectId()).isEqualTo("loc-1");
        assertThat(s1.observed()).isEqualByComparingTo("3");
        assertThat(s1.exposureAmount()).isNull();
        // A transfer out recorded within the slack explains the period.
        events.add(movement("15:20:00", "loc-1", "out"));
        assertThat(run(events, Set.of())).isEmpty();
        // Fewer than minCount carried: nothing to find.
        assertThat(run(events.subList(0, 1), Set.of())).isEmpty();
    }

    @Test
    @DisplayName("V-S1: a gap or an unseen period is skipped; a store-room zone without a location is not read")
    void storeRoomSkipped() {
        List<LedgerEvent> events = new ArrayList<>(List.of(crossed("14:10:00", "zr", "out", "bag", 3),
                gap("14:50:00", "14:55:00", "zr")));
        assertThat(run(events, Set.of())).isEmpty();
        events.removeLast();
        assertThat(run(events, Set.of(), Map.of(), context("2026-11-10T14:30:00Z"))).isEmpty();
        Map<String, Object> unlinked = new LinkedHashMap<>(SEEN);
        unlinked.put("zones", List.of(Obj.of("id", "zr", "cameraId", "c1", "agentId", "a1", "zoneType", "storage",
                "enabled", true, "createdAt", "2026-11-01T00:00:00Z")));
        assertThat(run(events, Set.of(), Map.of(), unlinked)).isEmpty();
        assertThat(codes(run(events, Set.of()))).containsExactly("V-S1");
    }
}
