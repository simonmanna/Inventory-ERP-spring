package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.lossprevention.application.LpBarEngine;
import com.inventoryerp.lossprevention.application.LpCashierEngine;
import com.inventoryerp.lossprevention.application.LpEngine;
import com.inventoryerp.lossprevention.application.LpEngine.Finding;
import com.inventoryerp.lossprevention.application.LpEngine.Input;
import com.inventoryerp.lossprevention.application.LpEngine.LedgerEvent;
import com.inventoryerp.lossprevention.application.LpEngine.RuleConfig;
import com.inventoryerp.lossprevention.application.LpRuleCatalog;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Every LP0 rule on synthetic events: one case that must be found and one that must not. */
class LpEnginesTest {

    static final LocalDate DAY = LocalDate.parse("2026-11-10");
    final LpEngine cashier = new LpCashierEngine();
    final LpEngine bar = new LpBarEngine();
    int seq;

    LedgerEvent ev(String type, LocalDate day, String actor, Map<String, Object> ids, Map<String, Object> payload) {
        seq++;
        return new LedgerEvent("e" + seq, seq, type, actor, (String) ids.get("transactionId"),
                (String) ids.get("orderId"), (String) ids.get("ticketId"), (String) ids.get("lineId"),
                (String) ids.get("sessionId"), day, Instant.parse(day + "T10:00:00Z").plusSeconds(seq), "order",
                "observed", payload);
    }

    /** The catalog defaults, with the given parameter overrides (rule code → params). */
    static Map<String, RuleConfig> rules(Map<String, Map<String, Object>> overrides) {
        Map<String, RuleConfig> out = new LinkedHashMap<>();
        for (LpRuleCatalog.Definition d : LpRuleCatalog.definitions()) {
            Map<String, Object> params = new LinkedHashMap<>(d.params());
            params.putAll(overrides.getOrDefault(d.code(), Map.of()));
            out.put(d.code(), new RuleConfig(d.code(), 0, d.enabled(), d.severity(), params));
        }
        return out;
    }

    List<Finding> run(LpEngine engine, List<LedgerEvent> events, boolean dayFinal,
            Map<String, Map<String, Object>> overrides) {
        return engine.evaluate(new Input("b1", DAY, dayFinal, events, rules(overrides)));
    }

    static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::ruleCode).sorted().toList();
    }

    @Test
    @DisplayName("C01: a fired item voided is found; an unfired one is not; the last step of a partial void counts")
    void c01() {
        List<LedgerEvent> events = List.of(
                ev("sale.line_voided", DAY, "u1", Obj.of("orderId", "o1", "lineId", "l1"),
                        Obj.of("quantity", 1, "firedQuantity", 2, "amount", "4.00")),
                ev("sale.line_voided", DAY, "u1", Obj.of("orderId", "o1", "lineId", "l1"),
                        Obj.of("quantity", 2, "firedQuantity", 2, "amount", "8.00")),
                ev("sale.line_voided", DAY, "u1", Obj.of("orderId", "o2", "lineId", "l2"),
                        Obj.of("quantity", 1, "firedQuantity", 0)));
        List<Finding> f = run(cashier, events, false, Map.of());
        assertThat(codes(f)).containsExactly("C01");
        assertThat(f.get(0).observed()).isEqualByComparingTo("2");
        assertThat(f.get(0).exposureAmount()).isEqualByComparingTo("8.00");
        assertThat(f.get(0).subjectId()).isEqualTo("u1");
    }

    @Test
    @DisplayName("C02: an order cancelled with fired lines is one finding, not one per voided line")
    void c02() {
        List<LedgerEvent> events = List.of(
                ev("sale.line_voided", DAY, "u1", Obj.of("orderId", "o1", "lineId", "l1"),
                        Obj.of("quantity", 1, "firedQuantity", 1)),
                ev("order.cancelled", DAY, "u1", Obj.of("orderId", "o1"), Obj.of("reason", "left", "firedLines",
                        List.of(Obj.of("description", "Chips", "quantity", 2, "firedQuantity", 2)))),
                ev("order.cancelled", DAY, "u1", Obj.of("orderId", "o2"), Obj.of("firedLines", List.of())));
        List<Finding> f = run(cashier, events, false, Map.of());
        assertThat(codes(f)).containsExactly("C02");
        assertThat(f.get(0).observed()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("C03: silent until a threshold is set; then a refund at or above it is found")
    void c03() {
        List<LedgerEvent> events = List.of(
                ev("refund.issued", DAY, "u1", Obj.of("transactionId", "t1"), Obj.of("amount", "50")),
                ev("refund.issued", DAY, "u1", Obj.of("transactionId", "t2"), Obj.of("amount", "10")));
        assertThat(run(cashier, events, false, Map.of())).isEmpty();
        List<Finding> f = run(cashier, events, false, Map.of("C03", Obj.of("amount", 50)));
        assertThat(codes(f)).containsExactly("C03");
        assertThat(f.get(0).exposureAmount()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("C04 and C05: compared with the branch only once the day is final")
    void c04c05() {
        List<LedgerEvent> events = new ArrayList<>();
        for (String cashierId : List.of("u1", "u2", "u3")) {
            events.add(ev("sale.completed", DAY, cashierId, Obj.of(), Obj.of("total", "10", "discountTotal", "0")));
        }
        for (int i = 0; i < 4; i++) {
            events.add(ev("refund.issued", DAY, "u1", Obj.of("transactionId", "r" + i), Obj.of("amount", "5")));
        }
        for (int i = 0; i < 6; i++) {
            events.add(ev("override.approved", DAY, "u2", Obj.of(), Obj.of("kind", "discount", "approverId",
                    "m" + i)));
        }
        assertThat(run(cashier, events, false, Map.of())).isEmpty();
        List<Finding> f = run(cashier, events, true, Map.of());
        assertThat(codes(f)).containsExactly("C04", "C05");
        assertThat(f.stream().filter(x -> x.ruleCode().equals("C04")).findFirst().orElseThrow().subjectId())
                .isEqualTo("u1");
    }

    @Test
    @DisplayName("C06: one manager behind most of a cashier's overrides in the last seven days")
    void c06() {
        List<LedgerEvent> events = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            events.add(ev("override.approved", DAY.minusDays(i + 1), "u1", Obj.of(), Obj.of("approverId", "m1")));
        }
        events.add(ev("override.approved", DAY, "u1", Obj.of(), Obj.of("approverId", "m2")));
        events.add(ev("override.approved", DAY.minusDays(20), "u1", Obj.of(), Obj.of("approverId", "m2")));
        List<Finding> f = run(cashier, events, true, Map.of());
        assertThat(codes(f)).containsExactly("C06");
        assertThat(f.get(0).observed()).isEqualByComparingTo("80");
        assertThat(f.get(0).detail().get("approverId")).isEqualTo("m1");
    }

    @Test
    @DisplayName("C07: a discount rate far above the branch median, among cashiers with enough sales")
    void c07() {
        List<LedgerEvent> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(ev("sale.completed", DAY, "u1", Obj.of(), Obj.of("total", "80", "discountTotal", "20")));
            events.add(ev("sale.completed", DAY, "u2", Obj.of(), Obj.of("total", "98", "discountTotal", "2")));
            events.add(ev("sale.completed", DAY, "u3", Obj.of(), Obj.of("total", "98", "discountTotal", "2")));
        }
        // Too few sales to compare.
        events.add(ev("sale.completed", DAY, "u4", Obj.of(), Obj.of("total", "10", "discountTotal", "90")));
        List<Finding> f = run(cashier, events, true, Map.of());
        assertThat(codes(f)).containsExactly("C07");
        assertThat(f.get(0).subjectId()).isEqualTo("u1");
        assertThat(f.get(0).observed()).isEqualByComparingTo("20");
        assertThat(f.get(0).exposureAmount()).isEqualByComparingTo("180");
    }

    @Test
    @DisplayName("C08: the last close of a session against the tolerance; a shortage carries the exposure")
    void c08() {
        List<LedgerEvent> events = List.of(
                ev("cash_session.closed", DAY, "u1", Obj.of("sessionId", "s1"),
                        Obj.of("expected", "100", "counted", "70", "difference", "-30")),
                ev("cash_session.closed", DAY, "u1", Obj.of("sessionId", "s1"),
                        Obj.of("expected", "100", "counted", "88", "difference", "-12")),
                ev("cash_session.closed", DAY, "u2", Obj.of("sessionId", "s2"),
                        Obj.of("expected", "100", "counted", "98", "difference", "-2")));
        assertThat(run(cashier, events, false, Map.of())).isEmpty();
        List<Finding> f = run(cashier, events, false, Map.of("C08", Obj.of("amount", 5)));
        assertThat(codes(f)).containsExactly("C08");
        assertThat(f.get(0).delta()).isEqualByComparingTo("-12");
        assertThat(f.get(0).exposureAmount()).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("C09: four short sessions in the cashier's last ten, counted when the latest is short too")
    void c09() {
        List<LedgerEvent> events = new ArrayList<>();
        for (int i = 3; i >= 1; i--) {
            events.add(ev("cash_session.closed", DAY.minusDays(i), "u1", Obj.of("sessionId", "s" + i),
                    Obj.of("difference", "-5")));
        }
        events.add(ev("cash_session.closed", DAY.minusDays(4), "u1", Obj.of("sessionId", "ok"),
                Obj.of("difference", "0")));
        assertThat(run(cashier, events, false, Map.of())).isEmpty();
        events.add(ev("cash_session.closed", DAY, "u1", Obj.of("sessionId", "today"), Obj.of("difference", "-1")));
        List<Finding> f = run(cashier, events, false, Map.of());
        assertThat(codes(f)).containsExactly("C09");
        assertThat(f.get(0).observed()).isEqualByComparingTo("4");
        assertThat(f.get(0).exposureAmount()).isEqualByComparingTo("16");
    }

    @Test
    @DisplayName("C10: a ticket recalled or cancelled after it was ready; before ready it is not a finding")
    void c10() {
        List<LedgerEvent> events = List.of(
                ev("kitchen.stage", DAY, null, Obj.of("ticketId", "k1"), Obj.of("stage", "remade", "afterReady", true,
                        "recallCount", 1)),
                ev("kitchen.stage", DAY, null, Obj.of("ticketId", "k2"), Obj.of("stage", "cancelled", "afterReady",
                        false)),
                ev("kitchen.stage", DAY, null, Obj.of("ticketId", "k3"), Obj.of("stage", "prepared")));
        List<Finding> f = run(cashier, events, false, Map.of());
        assertThat(codes(f)).containsExactly("C10");
        assertThat(f.get(0).subjectId()).isEqualTo("k1");
    }

    LedgerEvent line(LocalDate day, String lineId, String product, String variance, String reason,
            Map<String, Object> extra) {
        Map<String, Object> payload = Obj.of("productId", product, "productName", "Gin", "systemMl", "700",
                "countedMl", new BigDecimal("700").add(new BigDecimal(variance)), "varianceMl", variance, "reasonCode",
                reason);
        payload.putAll(extra);
        return ev("stock_count.line", day, "counter", Obj.of("lineId", lineId, "sessionId", "count-" + day), payload);
    }

    @Test
    @DisplayName("B01: over tolerance with no cause is found; an explained or small variance is not")
    void b01() {
        List<LedgerEvent> events = List.of(
                line(DAY, "l1", "gin", "-150", null, Obj.of("costPerMl", "0.02")),
                line(DAY, "l2", "rum", "-150", "spillage", Obj.of()),
                line(DAY, "l3", "vodka", "-10", null, Obj.of()),
                line(DAY, "l4", "whisky", "-40", "unexplained", Obj.of("toleranceMl", "50")));
        List<Finding> f = run(bar, events, false, Map.of());
        assertThat(codes(f)).containsExactly("B01");
        assertThat(f.get(0).subjectId()).isEqualTo("gin");
        assertThat(f.get(0).delta()).isEqualByComparingTo("-150");
        assertThat(f.get(0).exposureAmount()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("B02: the same cause on one product in three of the last four counts")
    void b02() {
        List<LedgerEvent> events = List.of(
                line(DAY.minusDays(3), "a", "gin", "-90", "spillage", Obj.of()),
                line(DAY.minusDays(2), "b", "gin", "-90", "breakage", Obj.of()),
                line(DAY.minusDays(1), "c", "gin", "-90", "spillage", Obj.of()),
                line(DAY, "d", "gin", "-90", "spillage", Obj.of()),
                line(DAY, "e", "rum", "-90", "spillage", Obj.of()));
        List<Finding> f = run(bar, events, false, Map.of());
        assertThat(codes(f)).containsExactly("B02");
        assertThat(f.get(0).subjectId()).isEqualTo("gin");
        assertThat(f.get(0).observed()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("B03: when switched on, movements that do not tie are a data finding and hold back B01")
    void b03() {
        List<LedgerEvent> events = List.of(
                line(DAY, "l1", "gin", "-150", null, Obj.of("openingMl", "1000", "ledgerNetMl", "-200")),
                line(DAY, "l2", "rum", "-150", null, Obj.of("openingMl", "1000", "ledgerNetMl", "-300")));
        // Off by default: both lines are plain B01 findings.
        assertThat(codes(run(bar, events, false, Map.of()))).containsExactly("B01", "B01");
        Map<String, RuleConfig> on = rules(Map.of());
        on.put("B03", new RuleConfig("B03", 1, true, "info", Obj.of("roundingMl", 1)));
        List<Finding> f = bar.evaluate(new Input("b1", DAY, false, events, on));
        assertThat(codes(f)).containsExactly("B01", "B03");
        assertThat(f.stream().filter(x -> x.ruleCode().equals("B03")).findFirst().orElseThrow().subjectId())
                .isEqualTo("gin");
        assertThat(f.stream().filter(x -> x.ruleCode().equals("B01")).findFirst().orElseThrow().subjectId())
                .isEqualTo("rum");
    }

    @Test
    @DisplayName("an engine gives the same findings for the same input")
    void deterministic() {
        List<LedgerEvent> events = List.of(
                ev("sale.line_voided", DAY, "u1", Obj.of("orderId", "o1", "lineId", "l1"),
                        Obj.of("quantity", 1, "firedQuantity", 1)),
                ev("kitchen.stage", DAY, null, Obj.of("ticketId", "k1"), Obj.of("stage", "remade", "afterReady", true,
                        "recallCount", 1)));
        assertThat(run(cashier, events, true, Map.of())).isEqualTo(run(cashier, events, true, Map.of()));
    }
}
