package com.inventoryerp.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.fleet.application.DeliveryChargeCalculator.Facts;
import com.inventoryerp.fleet.application.DeliveryChargeCalculator.Rule;
import com.inventoryerp.fleet.application.LoadPlanner.Capacity;
import com.inventoryerp.fleet.application.LoadPlanner.Item;
import com.inventoryerp.fleet.application.LoadPlanner.Plan;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Fleet 1.3 — load splitting by payload / volume, freight, delivery-charge rules and the trip state machine. */
class DispatchPoliciesTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static Item item(String id, String remaining, String t, String m3, String step) {
        return new Item(id, id, "u", d(remaining), t == null ? null : d(t), m3 == null ? null : d(m3),
                step == null ? null : d(step));
    }

    @Nested
    class Planner {

        @Test
        @DisplayName("50 t of aggregate on a 20 t tipper → 20 + 20 + 10 t")
        void payloadSplit() {
            Plan p = LoadPlanner.plan(List.of(item("agg", "50", "1", null, null)), new Capacity(d("20"), null), null);
            assertThat(p.loads()).hasSize(3);
            assertThat(p.loads().get(0).lines().getFirst().quantity()).isEqualByComparingTo("20");
            assertThat(p.loads().get(1).tonnes()).isEqualByComparingTo("20");
            assertThat(p.loads().get(2).lines().getFirst().quantity()).isEqualByComparingTo("10");
            assertThat(p.warnings()).isEmpty();
        }

        @Test
        @DisplayName("volume binds before payload: sand 1.6 t/m³, 30 t / 12 m³ body → 12 m³ per load")
        void volumeSplit() {
            Plan p = LoadPlanner.plan(List.of(item("sand", "30", "1.6", "1", null)), new Capacity(d("30"), d("12")),
                    null);
            assertThat(p.loads()).hasSize(3);
            assertThat(p.loads().get(0).m3()).isEqualByComparingTo("12");
            assertThat(p.loads().get(0).tonnes()).isEqualByComparingTo("19.2");
            assertThat(p.loads().get(2).lines().getFirst().quantity()).isEqualByComparingTo("6");
        }

        @Test
        @DisplayName("whole bags only: 25 kg cement, 1.01 t payload → 40 bags, never 40.4")
        void wholeUnits() {
            Plan p = LoadPlanner.plan(List.of(item("cement", "100", "0.025", null, "1")),
                    new Capacity(d("1.01"), null), null);
            assertThat(p.loads()).hasSize(3);
            assertThat(p.loads().get(0).lines().getFirst().quantity()).isEqualByComparingTo("40");
            assertThat(p.loads().get(2).lines().getFirst().quantity()).isEqualByComparingTo("20");
        }

        @Test
        @DisplayName("mixed lines top up the same load before a new one starts")
        void mixedLines() {
            Plan p = LoadPlanner.plan(List.of(item("a", "15", "1", null, null), item("b", "10", "1", null, null)),
                    new Capacity(d("20"), null), null);
            assertThat(p.loads()).hasSize(2);
            assertThat(p.loads().get(0).lines()).hasSize(2);
            assertThat(p.loads().get(0).lines().get(1).quantity()).isEqualByComparingTo("5");
            assertThat(p.loads().get(1).lines().getFirst().lineId()).isEqualTo("b");
            assertThat(p.loads().get(1).tonnes()).isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("a product without weight / volume data is not limited (warning)")
        void unknownFactor() {
            Plan p = LoadPlanner.plan(List.of(item("x", "500", null, null, null)), new Capacity(d("20"), null), null);
            assertThat(p.loads()).hasSize(1);
            assertThat(p.warnings()).anyMatch(w -> w.startsWith("No weight or volume data for x"));
        }

        @Test
        @DisplayName("no capacity at all → one load with a warning; nothing left → 400; one unit too heavy → 400")
        void edges() {
            Plan p = LoadPlanner.plan(List.of(item("a", "80", "1", null, null)), new Capacity(null, null), null);
            assertThat(p.loads()).hasSize(1);
            assertThat(p.warnings()).contains("No payload or body volume given: everything goes on one load");
            assertThatThrownBy(() -> LoadPlanner.plan(List.of(item("a", "0", "1", null, null)),
                    new Capacity(d("20"), null), null)).isInstanceOf(ApiException.class)
                    .hasMessage("Nothing is left to plan on this delivery order");
            assertThatThrownBy(() -> LoadPlanner.plan(List.of(item("beam", "2", "12", null, "1")),
                    new Capacity(d("10"), null), null)).isInstanceOf(ApiException.class)
                    .hasMessage("One u of beam (12 t) does not fit the vehicle capacity");
            assertThatThrownBy(() -> LoadPlanner.plan(List.of(item("a", "50", "1", null, null)),
                    new Capacity(d("20"), null), 2)).isInstanceOf(ApiException.class)
                    .hasMessage("The remaining quantity needs 3 loads (at most 2 allowed)");
        }
    }

    @Nested
    class Freight {

        @Test
        @DisplayName("per trip / per tonne / per km, HALF_UP to 2 decimals; missing basis data → 400")
        void amounts() {
            assertThat(FreightCalculator.amount("per_trip", d("150000"), null, null)).isEqualByComparingTo("150000.00");
            assertThat(FreightCalculator.amount("per_tonne", d("8500"), d("19.375"), null))
                    .isEqualByComparingTo("164687.50");
            assertThat(FreightCalculator.amount("per_km", d("1200.5"), null, d("42.3")))
                    .isEqualByComparingTo("50781.15");
            assertThat(FreightCalculator.amount(null, null, null, null)).isNull();
            assertThatThrownBy(() -> FreightCalculator.amount("per_km", d("1"), d("1"), null))
                    .hasMessage("Enter the trip distance: the freight is paid per km");
            assertThatThrownBy(() -> FreightCalculator.amount("per_tonne", d("1"), null, d("1")))
                    .hasMessageStartingWith("The freight is paid per tonne");
        }
    }

    @Nested
    class Charges {

        final Rule general = new Rule("g", "General", null, null, null, null, "per_tonne", d("5000"), d("50000"), null);
        final Rule zone = new Rule("z", "Kampala", null, "Kampala", null, null, "per_trip", d("80000"), null, null);
        final Rule customer = new Rule("c", "Contract", "P1", null, null, null, "flat", d("100000"), null, "tax");
        final Rule customerZone = new Rule("cz", "Contract Kampala", "P1", "kampala", null, null, "per_km", d("2500"),
                null, null);
        final Rule far = new Rule("f", "Far band", null, null, d("50"), null, "per_km", d("3000"), null, null);
        final List<Rule> all = List.of(general, zone, customer, customerZone, far);

        Facts facts(String partner, String zoneName, String siteKm, int trips, String tonnes, String tripKm) {
            return new Facts(partner, zoneName, siteKm == null ? null : d(siteKm), trips, d(tonnes), d("0"),
                    tripKm == null ? null : d(tripKm));
        }

        @Test
        @DisplayName("most specific rule wins: customer + zone > customer > zone > general; band beats no band")
        void selection() {
            assertThat(DeliveryChargeCalculator.select(all, facts("P1", "Kampala", "10", 2, "40", null)).orElseThrow()
                    .id()).isEqualTo("cz");
            assertThat(DeliveryChargeCalculator.select(all, facts("P1", "Gulu", "10", 2, "40", null)).orElseThrow()
                    .id()).isEqualTo("c");
            assertThat(DeliveryChargeCalculator.select(all, facts("P2", " kampala ", "10", 2, "40", null))
                    .orElseThrow().id()).isEqualTo("z");
            assertThat(DeliveryChargeCalculator.select(all, facts("P2", null, "80", 2, "40", null)).orElseThrow().id())
                    .isEqualTo("f");
            assertThat(DeliveryChargeCalculator.select(all, facts("P2", null, null, 2, "40", null)).orElseThrow().id())
                    .isEqualTo("g");
            assertThat(DeliveryChargeCalculator.select(List.of(zone), facts("P2", null, null, 1, "1", null)))
                    .isEmpty();
        }

        @Test
        @DisplayName("amounts per basis, minimum charge, per km falls back to site distance × trips")
        void amounts() {
            Facts f = facts("P2", null, "35", 3, "4.5", null);
            assertThat(DeliveryChargeCalculator.compute(general, f).amount()).isEqualByComparingTo("50000.00");
            assertThat(DeliveryChargeCalculator.compute(general, facts("P2", null, "35", 3, "57.25", null)).amount())
                    .isEqualByComparingTo("286250.00");
            assertThat(DeliveryChargeCalculator.compute(zone, f).amount()).isEqualByComparingTo("240000.00");
            assertThat(DeliveryChargeCalculator.compute(customer, f).amount()).isEqualByComparingTo("100000.00");
            assertThat(DeliveryChargeCalculator.compute(customerZone, f).quantity()).isEqualByComparingTo("105");
            assertThat(DeliveryChargeCalculator.compute(customerZone, facts("P1", null, "35", 3, "1", "96.4"))
                    .amount()).isEqualByComparingTo("241000.00");
            assertThat(DeliveryChargeCalculator.compute(zone, f).description())
                    .isEqualTo("Transport charge · Kampala · 3 trip(s)");
        }
    }

    @Nested
    class StateMachine {

        @Test
        @DisplayName("planned → loading → weighed → dispatched → in transit → delivered → closed")
        void happyPath() {
            List<String> path = List.of("planned", "loading", "weighed", "dispatched", "in_transit", "delivered",
                    "closed");
            for (int i = 0; i + 1 < path.size(); i++) {
                assertThat(TripStatusPolicy.canMove(path.get(i), path.get(i + 1))).as(path.get(i)).isTrue();
            }
            assertThat(TripStatusPolicy.canMove("planned", "dispatched")).isTrue();
            assertThat(TripStatusPolicy.canMove("dispatched", "delivered")).isTrue();
            assertThat(TripStatusPolicy.canMove("weighed", "weighed")).isTrue();
        }

        @Test
        @DisplayName("cancel only before the goods leave; return only once they did; terminal states stay")
        void guards() {
            assertThat(TripStatusPolicy.canMove("weighed", "cancelled")).isTrue();
            assertThat(TripStatusPolicy.canMove("dispatched", "cancelled")).isFalse();
            assertThat(TripStatusPolicy.canMove("in_transit", "returned")).isTrue();
            assertThat(TripStatusPolicy.canMove("planned", "returned")).isFalse();
            assertThat(TripStatusPolicy.canMove("delivered", "returned")).isFalse();
            for (String terminal : List.of("closed", "cancelled", "returned")) {
                for (String to : List.of("planned", "dispatched", "delivered", "closed")) {
                    assertThat(TripStatusPolicy.canMove(terminal, to)).isFalse();
                }
            }
            assertThatThrownBy(() -> TripStatusPolicy.assertMove("TRP-000001", "in_transit", "cancelled"))
                    .hasMessage("Trip TRP-000001 is in transit and cannot be cancelled");
            assertThat(TripStatusPolicy.isOpen("weighed")).isTrue();
            assertThat(TripStatusPolicy.isOnRoad("in_transit")).isTrue();
            assertThat(TripStatusPolicy.isActive("returned")).isFalse();
        }
    }
}
