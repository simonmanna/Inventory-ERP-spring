package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.lossprevention.application.LpBarPourEngine;
import com.inventoryerp.lossprevention.application.LpEngine.Coverage;
import com.inventoryerp.lossprevention.application.LpEngine.Finding;
import com.inventoryerp.lossprevention.application.LpEngine.Input;
import com.inventoryerp.lossprevention.application.LpEngine.LedgerEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bar rules of LP3 on synthetic readings and observations. These test the rules, not a meter or a detector: how
 * accurate a real spout or camera is has not been measured.
 */
class LpBarPourEngineTest {

    static final LocalDate DAY = LocalDate.parse("2026-11-10");
    static final String VODKA = "prod-vodka";
    final LpBarPourEngine engine = new LpBarPourEngine();
    int seq;

    static Instant at(String time) {
        return Instant.parse(DAY + "T" + time + "Z");
    }

    LedgerEvent pour(String time, String device, String ml, String product) {
        seq++;
        Map<String, Object> payload = Obj.of("volumeMl", new BigDecimal(ml));
        if (product != null) {
            payload.put("productId", product);
        }
        return new LedgerEvent("m" + seq, seq, "pour.measured", null, null, null, null, null, null, DAY, at(time),
                "item", "observed", payload, device, null);
    }

    LedgerEvent meterGap(String from, String to, String device) {
        seq++;
        return new LedgerEvent("g" + seq, seq, "measurement.gap", null, null, null, null, null, null, DAY, at(from),
                "item", "unavailable", Obj.of("from", at(from).toString(), "to", at(to).toString(), "reason",
                        "adapter_down"), device, null);
    }

    /** A sale whose one line drew {@code ml} of vodka at 0.05 a millilitre; {@code ml} null: no recipe data. */
    LedgerEvent sale(String time, String ml) {
        seq++;
        Map<String, Object> line = Obj.of("lineId", "l" + seq, "quantity", 1, "total", 8);
        if (ml != null) {
            line.put("ingredients", List.of(Obj.of("productId", VODKA, "quantity", new BigDecimal(ml), "totalValue",
                    new BigDecimal(ml).multiply(new BigDecimal("0.05")))));
        }
        return new LedgerEvent("s" + seq, seq, "sale.completed", "cashier-1", "inv" + seq, null, null, null, null, DAY,
                at(time), "order", "observed", Obj.of("lines", List.of(line), "total", 8));
    }

    /** A food sale: a line with no ingredients. */
    LedgerEvent foodSale(String time) {
        seq++;
        return new LedgerEvent("s" + seq, seq, "sale.completed", "cashier-1", "inv" + seq, null, null, null, null, DAY,
                at(time), "order", "observed", Obj.of("lines", List.of(Obj.of("quantity", 1, "ingredients",
                        List.of()))));
    }

    LedgerEvent tilt(String time, String zone, String confidence) {
        seq++;
        return new LedgerEvent("v" + seq, seq, "vision.handling", null, null, null, null, null, null, DAY, at(time),
                "zone", "observed", Obj.of("cameraId", "c1", "object", "bottle", "action", "tilted",
                        "durationSeconds", 3, "clipId", "clip-" + seq), zone, new BigDecimal(confidence));
    }

    /** A bar-counter zone over station "Main bar", a spout there fixed to vodka, both observed through {@code mark}. */
    static Map<String, Object> context(String mark, String meterAgent) {
        return Obj.of("timeZone", "UTC",
                "zones", List.of(Obj.of("id", "zb", "cameraId", "c1", "agentId", "a1", "zoneType", "bar_counter",
                        "linkedType", "station", "linkedId", "Main bar", "enabled", true, "createdAt",
                        "2026-11-01T00:00:00Z")),
                "devices", List.of(Obj.of("id", "d1", "agentId", meterAgent, "kind", "pour_spout", "productId", VODKA,
                        "station", "main BAR ", "standardPourMl", 25, "enabled", true, "createdAt",
                        "2026-11-01T00:00:00Z")),
                "cameras", List.of(Obj.of("id", "c1", "observedThrough", mark)),
                "agents", List.of(Obj.of("id", "a1", "observedThrough", mark)));
    }

    static final Map<String, Object> SEEN = context("2026-11-11T00:30:00Z", "a1");

    Input input(List<LedgerEvent> events, boolean dayFinal, Map<String, Object> context) {
        return new Input("b1", DAY, dayFinal, events, LpEnginesTest.rules(Map.of()), context);
    }

    static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::ruleCode).sorted().toList();
    }

    @Test
    @DisplayName("B04: more vodka poured than the day's sales drew is found, with its cost; within tolerance it is not")
    void pouredAboveSold() {
        List<LedgerEvent> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(pour("20:0" + i + ":00", "d1", "50", null));
        }
        events.add(sale("20:30:00", "100"));
        events.add(sale("20:31:00", "200"));
        List<Finding> f = engine.evaluate(input(events, true, SEEN));
        assertThat(codes(f)).containsExactly("B04", "B05");
        Finding b04 = f.stream().filter(x -> x.ruleCode().equals("B04")).findFirst().orElseThrow();
        assertThat(b04.subjectId()).isEqualTo(VODKA);
        assertThat(b04.expected()).isEqualByComparingTo("300");
        assertThat(b04.observed()).isEqualByComparingTo("500");
        assertThat(b04.exposureAmount()).isEqualByComparingTo("10");

        events.add(sale("20:32:00", "190"));
        assertThat(codes(engine.evaluate(input(events, true, SEEN)))).doesNotContain("B04");
        // Not before the day is final.
        assertThat(engine.evaluate(input(events.subList(0, 12), false, SEEN))).isEmpty();
    }

    @Test
    @DisplayName("B04: a day with a sale that has no recipe data is not compared")
    void noIngredientData() {
        List<LedgerEvent> events = List.of(pour("20:00:00", "d1", "500", VODKA), sale("20:30:00", null));
        assertThat(codes(engine.evaluate(input(events, true, SEEN)))).doesNotContain("B04");
    }

    @Test
    @DisplayName("B05: enough pours above the measure at one spout are found; a few are not")
    void overPours() {
        List<LedgerEvent> events = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            events.add(pour("19:0" + i + ":00", "d1", "35", null));
        }
        events.add(sale("19:30:00", "140"));
        assertThat(codes(engine.evaluate(input(events, true, SEEN)))).isEmpty();
        events.add(pour("19:05:00", "d1", "28", null));
        events.add(pour("19:06:00", "d1", "40", null));
        events.add(sale("19:31:00", "68"));
        List<Finding> f = engine.evaluate(input(events, true, SEEN));
        assertThat(codes(f)).containsExactly("B05");
        assertThat(f.getFirst().observed()).isEqualByComparingTo("5");
        assertThat(f.getFirst().detail()).containsEntry("pours", 6);
    }

    @Test
    @DisplayName("V-B1: a pour with no drink sale near it is found; a drink sale explains it, a food sale does not")
    void tiltWithoutSale() {
        List<LedgerEvent> events = List.of(
                tilt("02:00:00", "zb", "0.9"), pour("02:00:01", "d1", "25", null),
                foodSale("02:01:00"),
                tilt("21:00:00", "zb", "0.9"), pour("21:00:01", "d1", "25", null), sale("21:03:00", "25"),
                tilt("22:00:00", "zb", "0.3"));
        List<Finding> f = engine.evaluate(input(events, false, SEEN));
        assertThat(codes(f)).containsExactly("V-B1");
        assertThat(f.getFirst().detail()).containsEntry("pouredAt", at("02:00:00").toString());
    }

    @Test
    @DisplayName("V-B2: a pour seen with no reading from the station's spout is found; a reading explains it")
    void tiltWithoutReading() {
        List<LedgerEvent> events = List.of(
                tilt("21:00:00", "zb", "0.9"), sale("21:01:00", "25"),
                tilt("21:10:00", "zb", "0.9"), pour("21:10:10", "d1", "25", null), sale("21:11:00", "25"));
        List<Finding> f = engine.evaluate(input(events, false, SEEN));
        assertThat(codes(f)).containsExactly("V-B2");
        assertThat(f.getFirst().detail()).containsEntry("station", "Main bar");
        assertThat(engine.coverage(input(events, false, SEEN)).incomplete()).isFalse();
    }

    @Test
    @DisplayName("V-B2: nothing is found inside a meter gap or before the meter's agent reported that far")
    void tiltNotCovered() {
        List<LedgerEvent> events = List.of(tilt("21:00:00", "zb", "0.9"), sale("21:01:00", "25"),
                meterGap("20:55:00", "21:05:00", "d1"));
        assertThat(engine.evaluate(input(events, false, SEEN))).isEmpty();
        assertThat(engine.coverage(input(events, false, SEEN)).gap()).isTrue();

        List<LedgerEvent> noGap = List.of(tilt("21:00:00", "zb", "0.9"), sale("21:01:00", "25"));
        Map<String, Object> early = context("2026-11-10T20:00:00Z", "a1");
        assertThat(engine.evaluate(input(noGap, false, early))).isEmpty();
        Coverage c = engine.coverage(input(noGap, false, early));
        assertThat(c.pendingUntil()).isEqualTo(at("21:00:30"));
        // A meter posted by an outside system has no watermark: the settling time stands in for one.
        assertThat(codes(engine.evaluate(input(noGap, false, context("2026-11-10T20:00:00Z", null)))))
                .containsExactly("V-B2");
    }

    @Test
    @DisplayName("The same input gives the same findings")
    void deterministic() {
        List<LedgerEvent> events = List.of(pour("20:00:00", "d1", "900", null), sale("20:01:00", "100"),
                tilt("03:00:00", "zb", "0.9"));
        List<Finding> a = engine.evaluate(input(events, true, SEEN));
        List<Finding> b = engine.evaluate(input(events, true, SEEN));
        assertThat(a).isEqualTo(b);
        assertThat(codes(a)).containsExactly("B04", "V-B1", "V-B2");
    }
}
