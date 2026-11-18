package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.lossprevention.application.LpCashierEngine;
import com.inventoryerp.lossprevention.application.LpCashierVisionEngine;
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
 * The cashier vision rules (LP2) and C11 on synthetic events: what must be found, what must not, and that nothing is
 * found where the camera did not see the whole window. These test the rules, not a detector: how often a real camera
 * reports a drawer or a customer correctly is not measured here.
 */
class LpVisionEngineTest {

    static final LocalDate DAY = LocalDate.parse("2026-11-10");
    static final String TILL = "till-1";
    final LpCashierVisionEngine engine = new LpCashierVisionEngine();
    int seq;

    static Instant at(String time) {
        return Instant.parse(DAY + "T" + time + "Z");
    }

    LedgerEvent pos(String type, String time, String actor, String session, Map<String, Object> payload) {
        seq++;
        return new LedgerEvent("p" + seq, seq, type, actor, "t" + seq, null, null, null, session, DAY, at(time),
                "order", "observed", payload, null, null);
    }

    LedgerEvent seen(String type, String time, String zone, String confidence, Map<String, Object> payload) {
        seq++;
        return new LedgerEvent("v" + seq, seq, type, null, null, null, null, null, null, DAY, at(time), "zone",
                "vision.gap".equals(type) ? "unavailable" : "observed", payload, zone,
                confidence == null ? null : new BigDecimal(confidence));
    }

    LedgerEvent sessionOpened(String time, String actor) {
        return pos("cash_session.opened", time, actor, "s1", Obj.of("tillId", TILL, "openingFloat", 100));
    }

    /** One camera over till-1 with a drawer zone and a checkout zone, observed through {@code mark}. */
    static Map<String, Object> context(String mark) {
        return Obj.of("timeZone", "UTC",
                "zones", List.of(
                        Obj.of("id", "zd", "cameraId", "c1", "agentId", "a1", "zoneType", "cash_drawer", "linkedType",
                                "till", "linkedId", TILL, "enabled", true, "createdAt", "2026-11-01T00:00:00Z"),
                        Obj.of("id", "zc", "cameraId", "c1", "agentId", "a1", "zoneType", "checkout", "linkedType",
                                "till", "linkedId", TILL, "enabled", true, "createdAt", "2026-11-01T00:00:00Z")),
                "cameras", List.of(Obj.of("id", "c1", "observedThrough", mark)),
                "agents", List.of(Obj.of("id", "a1", "observedThrough", mark)));
    }

    static final Map<String, Object> SEEN_ALL_DAY = context("2026-11-11T00:30:00Z");

    Input input(List<LedgerEvent> events, Map<String, Object> context) {
        return new Input("b1", DAY, false, events, LpEnginesTest.rules(Map.of()), context);
    }

    static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::ruleCode).sorted().toList();
    }

    Map<String, Object> drawerOpen(String clip) {
        return Obj.of("cameraId", "c1", "object", "cash_drawer", "from", "closed", "to", "open", "clipId", clip);
    }

    @Test
    @DisplayName("V-C1: a drawer opening with no POS record near it is found; one next to a sale is not")
    void drawerWithoutSale() {
        List<LedgerEvent> events = new ArrayList<>(List.of(
                sessionOpened("08:00:00", "cashier-1"),
                seen("vision.state_changed", "10:00:00", "zd", "0.9", drawerOpen("clip-1")),
                pos("sale.completed", "11:00:30", "cashier-1", "s1", Obj.of("total", 12)),
                seen("vision.state_changed", "11:00:00", "zd", "0.9", drawerOpen("clip-2"))));
        List<Finding> f = engine.evaluate(input(events, SEEN_ALL_DAY));
        assertThat(codes(f)).containsExactly("V-C1");
        Finding found = f.getFirst();
        assertThat(found.subjectType()).isEqualTo("actor");
        assertThat(found.subjectId()).isEqualTo("cashier-1");
        assertThat(found.detail()).containsEntry("clipId", "clip-1").containsEntry("tillId", TILL);
        assertThat(found.evidenceEventIds()).hasSize(2);
    }

    @Test
    @DisplayName("V-C1: an unsure detection is ignored; a cash movement or a session change explains an opening")
    void drawerExplainedOrUnsure() {
        List<LedgerEvent> events = List.of(
                seen("vision.state_changed", "09:00:00", "zd", "0.4", drawerOpen(null)),
                pos("cash.movement", "10:01:00", "cashier-1", null, Obj.of("kind", "pay_out", "amount", 5, "tillId",
                        TILL)),
                seen("vision.state_changed", "10:00:00", "zd", "0.95", drawerOpen(null)),
                pos("cash_session.closed", "18:00:30", "cashier-1", "s9", Obj.of("tillId", TILL)),
                seen("vision.state_changed", "18:00:00", "zd", "0.95", drawerOpen(null)));
        assertThat(engine.evaluate(input(events, SEEN_ALL_DAY))).isEmpty();
    }

    @Test
    @DisplayName("V-C1: a drawer that closes is not an opening, and an opening outside the day belongs to another run")
    void drawerOtherStatesAndDays() {
        List<LedgerEvent> events = List.of(
                seen("vision.state_changed", "10:00:00", "zd", "0.9", Obj.of("cameraId", "c1", "object",
                        "cash_drawer", "to", "closed")),
                new LedgerEvent("late", 99, "vision.state_changed", null, null, null, null, null, null,
                        DAY.plusDays(1), Instant.parse("2026-11-11T00:05:00Z"), "zone", "observed",
                        drawerOpen(null), "zd", new BigDecimal("0.9")));
        assertThat(engine.evaluate(input(events, SEEN_ALL_DAY))).isEmpty();
    }

    @Test
    @DisplayName("V-C2: a refund with nobody at the till is found; with a customer present it is not")
    void refundWithoutCustomer() {
        List<LedgerEvent> events = List.of(
                pos("refund.issued", "12:00:00", "cashier-1", null, Obj.of("amount", "40.00", "tillId", TILL)),
                pos("refund.issued", "15:00:00", "cashier-1", null, Obj.of("amount", "25.00", "tillId", TILL)),
                seen("vision.dwell", "15:00:30", "zc", "0.8", Obj.of("cameraId", "c1", "class", "person",
                        "startedAt", "2026-11-10T14:58:30Z", "endedAt", "2026-11-10T15:00:30Z")));
        List<Finding> f = engine.evaluate(input(events, SEEN_ALL_DAY));
        assertThat(codes(f)).containsExactly("V-C2");
        assertThat(f.getFirst().exposureAmount()).isEqualByComparingTo("40.00");
        assertThat(f.getFirst().subjectId()).isEqualTo("cashier-1");
        assertThat(engine.coverage(input(events, SEEN_ALL_DAY)).incomplete()).isFalse();
    }

    @Test
    @DisplayName("V-C2: a period count with someone in it is a customer; the till comes from the session")
    void refundWithOccupancy() {
        List<LedgerEvent> events = List.of(
                sessionOpened("08:00:00", "cashier-1"),
                pos("refund.issued", "12:00:00", "cashier-1", "s1", Obj.of("amount", "10.00")),
                seen("vision.occupancy", "12:00:30", "zc", "0.7", Obj.of("cameraId", "c1", "class", "person",
                        "count", 1, "periodSeconds", 60)));
        assertThat(engine.evaluate(input(events, SEEN_ALL_DAY))).isEmpty();
    }

    @Test
    @DisplayName("V-C2: nothing is found inside a gap or after the camera's watermark, and the run says why")
    void refundNotFullyObserved() {
        List<LedgerEvent> inGap = List.of(
                pos("refund.issued", "12:00:00", "cashier-1", null, Obj.of("amount", "40.00", "tillId", TILL)),
                seen("vision.gap", "12:10:00", "zc", null, Obj.of("cameraId", "c1", "from", "2026-11-10T11:55:00Z",
                        "to", "2026-11-10T12:05:00Z", "reason", "stream_lost")));
        assertThat(engine.evaluate(input(inGap, SEEN_ALL_DAY))).isEmpty();
        Coverage gap = engine.coverage(input(inGap, SEEN_ALL_DAY));
        assertThat(gap.gap()).isTrue();

        List<LedgerEvent> late = List.of(
                pos("refund.issued", "12:00:00", "cashier-1", null, Obj.of("amount", "40.00", "tillId", TILL)));
        Map<String, Object> notYet = context("2026-11-10T11:59:00Z");
        assertThat(engine.evaluate(input(late, notYet))).isEmpty();
        Coverage pending = engine.coverage(input(late, notYet));
        assertThat(pending.pendingUntil()).isEqualTo(at("12:01:00"));
        // Once the watermark passes the window, the same refund is found.
        assertThat(codes(engine.evaluate(input(late, SEEN_ALL_DAY)))).containsExactly("V-C2");
    }

    @Test
    @DisplayName("V-C2: a till without a checkout zone, or a zone drawn after the refund, is not judged")
    void refundWithoutZone() {
        List<LedgerEvent> events = List.of(
                pos("refund.issued", "12:00:00", "cashier-1", null, Obj.of("amount", "40.00", "tillId", "till-2")));
        assertThat(engine.evaluate(input(events, SEEN_ALL_DAY))).isEmpty();
        Map<String, Object> drawnLater = Obj.of("timeZone", "UTC", "zones", List.of(Obj.of("id", "zc", "cameraId",
                "c1", "agentId", "a1", "zoneType", "checkout", "linkedType", "till", "linkedId", TILL, "enabled", true,
                "createdAt", "2026-11-10T13:00:00Z")), "cameras", List.of(Obj.of("id", "c1", "observedThrough",
                        "2026-11-11T00:30:00Z")));
        List<LedgerEvent> atTill = List.of(
                pos("refund.issued", "12:00:00", "cashier-1", null, Obj.of("amount", "40.00", "tillId", TILL)));
        assertThat(engine.evaluate(input(atTill, drawnLater))).isEmpty();
    }

    @Test
    @DisplayName("V-C3: a long presence at an open till with no sale is found; a sale after it clears it")
    void serviceWithoutSale() {
        List<LedgerEvent> events = List.of(
                sessionOpened("08:00:00", "cashier-1"),
                seen("vision.dwell", "10:02:00", "zc", "0.9", Obj.of("cameraId", "c1", "class", "person",
                        "startedAt", "2026-11-10T10:00:00Z", "endedAt", "2026-11-10T10:02:00Z", "maxCount", 1,
                        "clipId", "clip-9")),
                seen("vision.dwell", "11:02:00", "zc", "0.9", Obj.of("cameraId", "c1", "class", "person",
                        "startedAt", "2026-11-10T11:00:00Z", "endedAt", "2026-11-10T11:02:00Z")),
                pos("sale.completed", "11:04:00", "cashier-1", "s1", Obj.of("total", 8)),
                seen("vision.dwell", "12:00:30", "zc", "0.9", Obj.of("cameraId", "c1", "class", "person",
                        "startedAt", "2026-11-10T12:00:00Z", "endedAt", "2026-11-10T12:00:30Z")));
        List<Finding> f = engine.evaluate(input(events, SEEN_ALL_DAY));
        assertThat(codes(f)).containsExactly("V-C3");
        assertThat(f.getFirst().subjectId()).isEqualTo("cashier-1");
        assertThat(f.getFirst().detail()).containsEntry("clipId", "clip-9");
    }

    @Test
    @DisplayName("V-C3: someone standing at a closed till is not a customer")
    void presenceAtClosedTill() {
        List<LedgerEvent> events = List.of(
                sessionOpened("08:00:00", "cashier-1"),
                pos("cash_session.closed", "09:00:00", "cashier-1", "s1", Obj.of("tillId", TILL)),
                seen("vision.dwell", "10:02:00", "zc", "0.9", Obj.of("cameraId", "c1", "class", "person",
                        "startedAt", "2026-11-10T10:00:00Z", "endedAt", "2026-11-10T10:02:00Z")));
        assertThat(engine.evaluate(input(events, SEEN_ALL_DAY))).isEmpty();
    }

    @Test
    @DisplayName("the vision engine gives the same findings for the same input")
    void deterministic() {
        List<LedgerEvent> events = List.of(
                sessionOpened("08:00:00", "cashier-1"),
                seen("vision.state_changed", "10:00:00", "zd", "0.9", drawerOpen("clip-1")),
                pos("refund.issued", "12:00:00", "cashier-1", null, Obj.of("amount", "40.00", "tillId", TILL)));
        assertThat(engine.evaluate(input(events, SEEN_ALL_DAY))).isEqualTo(engine.evaluate(input(events,
                SEEN_ALL_DAY)));
    }

    @Test
    @DisplayName("C11: no-sale drawer openings far above the branch are found once the day is final")
    void c11() {
        LpCashierEngine cashier = new LpCashierEngine();
        List<LedgerEvent> events = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            events.add(pos("cash_drawer.opened", String.format("10:%02d:00", i), "u1", null, Obj.of("reason",
                    "no_sale", "tillId", TILL)));
        }
        for (String other : List.of("u2", "u3", "u4")) {
            events.add(pos("cash_drawer.opened", "11:00:00", other, null, Obj.of("reason", "no_sale")));
            events.add(pos("sale.completed", "11:30:00", other, null, Obj.of("total", 5)));
        }
        events.add(pos("cash_drawer.opened", "12:00:00", "u2", null, Obj.of("reason", "sale")));
        List<Finding> notFinal = cashier.evaluate(new Input("b1", DAY, false, events, LpEnginesTest.rules(Map.of())));
        assertThat(codes(notFinal)).doesNotContain("C11");
        List<Finding> f = cashier.evaluate(new Input("b1", DAY, true, events, LpEnginesTest.rules(Map.of())));
        assertThat(codes(f)).contains("C11");
        assertThat(f.stream().filter(x -> x.ruleCode().equals("C11")).findFirst().orElseThrow().subjectId())
                .isEqualTo("u1");
    }
}
