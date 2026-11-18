package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.lossprevention.application.LpEngine.Finding;
import com.inventoryerp.lossprevention.application.LpEngine.Input;
import com.inventoryerp.lossprevention.application.LpEngine.LedgerEvent;
import com.inventoryerp.lossprevention.application.LpKitchenFlowEngine;
import com.inventoryerp.lossprevention.application.LpServiceVisionEngine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The restaurant and cafe rules of LP4 on synthetic events: K01 and K02 (kitchen flow, no camera), V-K1 and V-F1
 * (counts over the kitchen pass and the cafe counter), and LP4b's V-K2, V-K3 and V-F2. How well a camera counts plates or cups is not measured here.
 */
class LpRestaurantEnginesTest {

    static final LocalDate DAY = LocalDate.parse("2026-11-10");
    final LpKitchenFlowEngine kitchen = new LpKitchenFlowEngine();
    final LpServiceVisionEngine service = new LpServiceVisionEngine();
    int seq;

    static Instant at(String time) {
        return Instant.parse(DAY + "T" + time + "Z");
    }

    LedgerEvent ticket(String type, String time, LocalDate day, String ticket, String order,
            Map<String, Object> payload) {
        seq++;
        return new LedgerEvent("k" + seq, seq, type, null, null, order, ticket, null, null, day,
                Instant.parse(day + "T" + time + "Z"), "ticket", "kitchen.ticket_created".equals(type) ? "observed"
                        : "inferred", payload);
    }

    LedgerEvent created(String time, String ticket, String order, String station, Integer items) {
        return ticket("kitchen.ticket_created", time, DAY, ticket, order, Obj.of("station", station, "itemCount",
                items));
    }

    LedgerEvent stage(String time, String ticket, String order, String station, String stage) {
        return ticket("kitchen.stage", time, DAY, ticket, order, Obj.of("stage", stage, "station", station));
    }

    LedgerEvent sale(String time, LocalDate day, String order, String till, int qty, int total) {
        seq++;
        return new LedgerEvent("s" + seq, seq, "sale.completed", "cashier-1", "inv" + seq, order, null, null, null,
                day, Instant.parse(day + "T" + time + "Z"), "order", "observed", Obj.of("tillId", till, "lines",
                        List.of(Obj.of("quantity", qty, "total", total))));
    }

    LedgerEvent crossed(String time, String zone, int count, String direction) {
        seq++;
        return new LedgerEvent("v" + seq, seq, "vision.line_crossed", null, null, null, null, null, null, DAY,
                at(time), "zone", "observed", Obj.of("cameraId", "c1", "class", "plate", "direction", direction,
                        "count", count), zone, new BigDecimal("0.8"));
    }

    LedgerEvent gap(String from, String to, String zone) {
        seq++;
        return new LedgerEvent("g" + seq, seq, "vision.gap", null, null, null, null, null, null, DAY, at(from), "zone",
                "unavailable", Obj.of("cameraId", "c1", "from", at(from).toString(), "to", at(to).toString(),
                        "reason", "stream_lost"), zone, BigDecimal.ONE);
    }

    static Map<String, Object> context(String mark) {
        return Obj.of("timeZone", "UTC",
                "zones", List.of(
                        Obj.of("id", "zk", "cameraId", "c1", "agentId", "a1", "zoneType", "kitchen_pass",
                                "linkedType", "station", "linkedId", "Hot", "enabled", true, "createdAt",
                                "2026-11-01T00:00:00Z"),
                        Obj.of("id", "zf", "cameraId", "c1", "agentId", "a1", "zoneType", "cafe_counter",
                                "linkedType", "till", "linkedId", "till-1", "enabled", true, "createdAt",
                                "2026-11-01T00:00:00Z")),
                "cameras", List.of(Obj.of("id", "c1", "observedThrough", mark)),
                "agents", List.of(Obj.of("id", "a1", "observedThrough", mark)));
    }

    static final Map<String, Object> SEEN = context("2026-11-11T00:30:00Z");

    Input input(List<LedgerEvent> events, boolean dayFinal, Map<String, Object> context) {
        return new Input("b1", DAY, dayFinal, events, LpEnginesTest.rules(Map.of()), context);
    }

    static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::ruleCode).sorted().toList();
    }

    // ---------------------------------------------------------------- kitchen flow

    @Test
    @DisplayName("K01: a served ticket with no sale for its order is found; paid that day or the next, or cancelled, it is not")
    void servedWithoutSale() {
        List<LedgerEvent> events = new ArrayList<>(List.of(
                created("12:00:00", "t1", "o1", "Hot", 2), stage("12:20:00", "t1", "o1", "Hot", "dispatched"),
                created("12:05:00", "t2", "o2", "Hot", 1), stage("12:25:00", "t2", "o2", "Hot", "dispatched"),
                sale("12:40:00", DAY, "o2", "till-1", 1, 10),
                created("22:00:00", "t3", "o3", "Hot", 1), stage("22:20:00", "t3", "o3", "Hot", "dispatched"),
                sale("00:40:00", DAY.plusDays(1), "o3", "till-1", 1, 10),
                created("13:00:00", "t4", "o4", "Hot", 1), stage("13:20:00", "t4", "o4", "Hot", "dispatched"),
                stage("13:30:00", "t4", "o4", "Hot", "cancelled"),
                created("14:00:00", "t5", null, "Hot", 1), stage("14:20:00", "t5", null, "Hot", "dispatched")));
        List<Finding> f = kitchen.evaluate(input(events, true, Map.of()));
        assertThat(codes(f)).containsExactly("K01");
        assertThat(f.getFirst().subjectId()).isEqualTo("t1");
        assertThat(f.getFirst().detail()).containsEntry("itemCount", new BigDecimal("2"));
        // Not before the day is final.
        assertThat(kitchen.evaluate(input(events, false, Map.of()))).isEmpty();
        events.add(sale("09:00:00", DAY.plusDays(1), "o1", "till-1", 2, 20));
        assertThat(kitchen.evaluate(input(events, true, Map.of()))).isEmpty();
    }

    @Test
    @DisplayName("K02: remakes at one station above the rate are found; one station's remakes do not count for another")
    void remakes() {
        List<LedgerEvent> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String t = "t" + i;
            String station = i < 10 ? "Hot" : "Cold";
            events.add(created("11:" + (10 + i) + ":00", t, "o" + i, station, 1));
            events.add(stage("11:" + (30 + i) + ":00", t, "o" + i, station, "dispatched"));
            events.add(sale("11:" + (40 + i) + ":00", DAY, "o" + i, "till-1", 1, 10));
            if (i < 3 || i == 10) {
                events.add(stage("11:" + (35 + i) + ":00", t, "o" + i, station, "remade"));
            }
        }
        List<Finding> f = kitchen.evaluate(input(events, true, Map.of()));
        assertThat(codes(f)).containsExactly("K02");
        assertThat(f.getFirst().subjectId()).isEqualTo("Hot");
        assertThat(f.getFirst().observed()).isEqualByComparingTo("30.0");
    }

    // ---------------------------------------------------------------- service vision

    @Test
    @DisplayName("V-K1: more plates over the pass than the hour's tickets is found; within tolerance it is not")
    void platesAboveTickets() {
        List<LedgerEvent> events = new ArrayList<>(List.of(
                created("12:00:00", "t1", "o1", "Hot", 3), stage("12:10:00", "t1", "o1", "Hot", "prepared"),
                created("12:00:00", "t2", "o2", "Cold", 9), stage("12:12:00", "t2", "o2", "cold", "prepared"),
                crossed("12:11:00", "zk", 3, "out"), crossed("12:30:00", "zk", 5, "out"),
                crossed("12:31:00", "zk", 4, "in")));
        List<Finding> f = service.evaluate(input(events, false, SEEN));
        assertThat(codes(f)).containsExactly("V-K1");
        Finding k1 = f.getFirst();
        assertThat(k1.expected()).isEqualByComparingTo("3");
        assertThat(k1.observed()).isEqualByComparingTo("8");
        assertThat(k1.detail()).containsEntry("periodFrom", at("12:00:00").toString());

        events.add(created("12:20:00", "t3", "o3", "Hot", 4));
        events.add(stage("12:29:00", "t3", "o3", "hot", "dispatched"));
        assertThat(service.evaluate(input(events, false, SEEN))).isEmpty();
    }

    @Test
    @DisplayName("V-K1: a period with a gap, after the watermark, or with a ticket of unknown size is skipped")
    void platesNotCovered() {
        List<LedgerEvent> base = List.of(created("12:00:00", "t1", "o1", "Hot", 1),
                stage("12:10:00", "t1", "o1", "Hot", "prepared"), crossed("12:30:00", "zk", 9, "out"));
        assertThat(service.evaluate(input(base, false, SEEN))).hasSize(1);

        List<LedgerEvent> withGap = new ArrayList<>(base);
        withGap.add(gap("12:40:00", "12:45:00", "zk"));
        assertThat(service.evaluate(input(withGap, false, SEEN))).isEmpty();
        assertThat(service.coverage(input(withGap, false, SEEN)).gap()).isTrue();

        Map<String, Object> early = context("2026-11-10T12:30:00Z");
        assertThat(service.evaluate(input(base, false, early))).isEmpty();
        assertThat(service.coverage(input(base, false, early)).pendingUntil()).isEqualTo(at("13:00:00"));

        List<LedgerEvent> unknown = new ArrayList<>(base);
        unknown.add(created("12:00:00", "t2", "o2", "Hot", null));
        unknown.add(stage("12:15:00", "t2", "o2", "Hot", "dispatched"));
        // t2 served with no plate within 5 minutes is V-K2's business (LP4b), not V-K1's.
        assertThat(codes(service.evaluate(input(unknown, false, SEEN)))).doesNotContain("V-K1");
    }

    @Test
    @DisplayName("V-F1: more cups over the counter than till-1 sold is found, with an estimated amount; other tills do not count")
    void cupsAboveSales() {
        List<LedgerEvent> events = List.of(
                sale("08:10:00", DAY, null, "till-1", 2, 6), sale("08:20:00", DAY, null, "till-2", 10, 30),
                crossed("08:15:00", "zf", 4, "out"), crossed("08:40:00", "zf", 3, "out"));
        List<Finding> f = service.evaluate(input(events, false, SEEN));
        assertThat(codes(f)).containsExactly("V-F1");
        Finding f1 = f.getFirst();
        assertThat(f1.subjectType()).isEqualTo("till");
        assertThat(f1.subjectId()).isEqualTo("till-1");
        assertThat(f1.delta()).isEqualByComparingTo("5");
        assertThat(f1.exposureAmount()).isEqualByComparingTo("15");
        assertThat(service.evaluate(input(events, false, SEEN))).isEqualTo(f);
    }

    // ---------------------------------------------------------------- LP4b

    LedgerEvent crossedAt(String time, String zone, String cls, int count, String direction) {
        seq++;
        return new LedgerEvent("v" + seq, seq, "vision.line_crossed", null, null, null, null, null, null, DAY,
                at(time), "zone", "observed", Obj.of("cameraId", "c1", "class", cls, "direction", direction,
                        "count", count), zone, new BigDecimal("0.8"));
    }

    LedgerEvent tableTicket(String time, String ticket, String order, String table) {
        return ticket("kitchen.ticket_created", time, DAY, ticket, order, Obj.of("station", "Hot", "itemCount", 2,
                "tableId", table));
    }

    LedgerEvent tableSale(String time, String order, String table) {
        seq++;
        return new LedgerEvent("s" + seq, seq, "sale.completed", "cashier-1", "inv" + seq, order, null, null, null,
                DAY, at(time), "order", "observed", Obj.of("tillId", "till-1", "tableId", table, "lines",
                        List.of(Obj.of("quantity", 2, "total", 20))));
    }

    /** A dining zone at table T1 and a cafe counter linked to the Bar station, beside the pass of SEEN. */
    static Map<String, Object> lp4b(String mark) {
        return Obj.of("timeZone", "UTC",
                "zones", List.of(
                        Obj.of("id", "zk", "cameraId", "c1", "agentId", "a1", "zoneType", "kitchen_pass",
                                "linkedType", "station", "linkedId", "Hot", "enabled", true, "createdAt",
                                "2026-11-01T00:00:00Z"),
                        Obj.of("id", "zd", "cameraId", "c1", "agentId", "a1", "zoneType", "dining",
                                "linkedType", "table", "linkedId", "T1", "enabled", true, "createdAt",
                                "2026-11-01T00:00:00Z"),
                        Obj.of("id", "zc", "cameraId", "c1", "agentId", "a1", "zoneType", "cafe_counter",
                                "linkedType", "station", "linkedId", "Bar", "enabled", true, "createdAt",
                                "2026-11-01T00:00:00Z")),
                "cameras", List.of(Obj.of("id", "c1", "observedThrough", mark)),
                "agents", List.of(Obj.of("id", "a1", "observedThrough", mark)));
    }

    static final Map<String, Object> SEEN_4B = lp4b("2026-11-11T00:30:00Z");

    static List<Finding> only(List<Finding> f, String code) {
        return f.stream().filter(x -> code.equals(x.ruleCode())).toList();
    }

    @Test
    @DisplayName("V-K2: a ticket served with no plate over its station's pass is found; a plate near it, another"
            + " station, a cancelled ticket or a gap is not")
    void servedWithoutPlate() {
        List<LedgerEvent> events = new ArrayList<>(List.of(
                created("12:00:00", "t1", "o1", "Hot", 2), stage("12:20:00", "t1", "o1", "Hot", "dispatched"),
                created("12:00:00", "t2", "o2", "Hot", 1), stage("13:00:00", "t2", "o2", "Hot", "dispatched"),
                crossedAt("13:03:00", "zk", "plate", 1, "out"),
                created("12:00:00", "t3", "o3", "Grill", 1), stage("14:00:00", "t3", "o3", "Grill", "dispatched"),
                created("12:00:00", "t4", "o4", "Hot", 1), stage("15:00:00", "t4", "o4", "Hot", "dispatched"),
                stage("15:10:00", "t4", "o4", "Hot", "cancelled"),
                crossedAt("12:21:00", "zk", "person", 1, "out")));
        List<Finding> f = only(service.evaluate(input(events, false, SEEN_4B)), "V-K2");
        assertThat(f).extracting(Finding::subjectId).containsExactly("t1");
        assertThat(f.getFirst().expected()).isEqualByComparingTo("2");
        assertThat(f.getFirst().observed()).isEqualByComparingTo("0");
        assertThat(f.getFirst().subjectType()).isEqualTo("ticket");

        events.add(gap("12:18:00", "12:19:00", "zk"));
        assertThat(only(service.evaluate(input(events, false, SEEN_4B)), "V-K2")).isEmpty();
        assertThat(only(service.evaluate(input(events.subList(0, 2), false, lp4b("2026-11-10T12:22:00Z"))), "V-K2"))
                .as("the window is not observed yet").isEmpty();
    }

    @Test
    @DisplayName("V-K3: plates arriving at a table with no open order are found once per delivery; an open order,"
            + " a sale on the table, or a day without table ids is not")
    void platesWithoutOrder() {
        List<LedgerEvent> events = new ArrayList<>(List.of(
                tableTicket("12:00:00", "t1", "o1", "T1"), crossedAt("12:20:00", "zd", "plate", 2, "in"),
                tableSale("12:50:00", "o1", "T1"),
                crossedAt("08:00:00", "zd", "plate", 1, "in"), crossedAt("08:04:00", "zd", "plate", 2, "in"),
                crossedAt("08:05:00", "zd", "person", 3, "in"),
                crossedAt("18:00:00", "zd", "plate", 1, "in"), tableSale("19:00:00", null, "T1")));
        List<Finding> f = only(service.evaluate(input(events, false, SEEN_4B)), "V-K3");
        assertThat(f).hasSize(1);
        assertThat(f.getFirst().subjectType()).isEqualTo("table");
        assertThat(f.getFirst().subjectId()).isEqualTo("T1");
        assertThat(f.getFirst().observed()).as("two arrivals, one delivery, people not counted")
                .isEqualByComparingTo("3");
        assertThat(f.getFirst().detail()).containsEntry("firstArrival", at("08:00:00").toString());

        List<LedgerEvent> noTables = events.stream().filter(e -> e.type().startsWith("vision")).toList();
        assertThat(only(service.evaluate(input(noTables, false, SEEN_4B)), "V-K3")).as("no table ids sent")
                .isEmpty();
    }

    @Test
    @DisplayName("V-F2: fewer cups handed over than the Bar station prepared is found; within tolerance or another"
            + " station it is not")
    void preparedNotHandedOver() {
        List<LedgerEvent> events = new ArrayList<>(List.of(
                created("09:00:00", "b1", "o1", "Bar", 6), stage("09:10:00", "b1", "o1", "Bar", "prepared"),
                created("09:00:00", "h1", "o2", "Hot", 9), stage("09:15:00", "h1", "o2", "Hot", "prepared"),
                crossedAt("09:20:00", "zc", "cup", 2, "out"), crossedAt("09:25:00", "zc", "cup", 1, "in")));
        List<Finding> f = only(service.evaluate(input(events, false, SEEN_4B)), "V-F2");
        assertThat(f).hasSize(1);
        assertThat(f.getFirst().expected()).isEqualByComparingTo("6");
        assertThat(f.getFirst().observed()).isEqualByComparingTo("2");
        assertThat(f.getFirst().delta()).isEqualByComparingTo("-4");

        events.add(crossedAt("10:03:00", "zc", "cup", 3, "out"));
        assertThat(only(service.evaluate(input(events, false, SEEN_4B)), "V-F2")).as("handed over in the slack")
                .isEmpty();
        assertThat(service.evaluate(input(events, false, SEEN_4B))).isEqualTo(service.evaluate(input(events, false,
                SEEN_4B)));
    }
}
