package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** ADR-S006 package B: multi-level, lead-time-offset MRP with lot sizing, pegging and firming. */
class MrpPlanningIT extends MfgTestSupport {

    void params(String productId, Map<String, Object> body) {
        ok(200, "PUT", "/manufacturing/planning-params/" + productId, body);
    }

    static JsonNode find(JsonNode rows, String productId) {
        for (JsonNode p : rows) {
            if (p.get("productId").asString().equals(productId)) {
                return p;
            }
        }
        return null;
    }

    @Test
    @DisplayName("cake → dough → flour: levels, lead-time offsets, lot sizing, safety stock, firming, supersede")
    void multiLevel() {
        String flour = product("MRPFLOUR");
        String box = product("MRPBOX");
        String dough = product("MRPDOUGH");
        String cake = product("MRPCAKE");
        bom(dough, flour, 1, 3, Map.of());
        // cake: 10 per run from 2 dough + 1 box.
        JsonNode cakeBom = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Cake", "outputProductId", cake,
                "outputQuantity", 10, "lines", List.of(Map.of("componentProductId", dough, "quantity", 2),
                        Map.of("componentProductId", box, "quantity", 1))));
        ok(201, "POST", "/manufacturing/boms/" + cakeBom.get("id").asString() + "/activate", null);
        params(cake, Map.of("procurement", "make", "leadTimeDays", 1, "locationId", bakery));
        params(dough, Map.of("procurement", "make", "leadTimeDays", 1, "locationId", bakery));
        params(flour, Map.of("procurement", "buy", "leadTimeDays", 2, "minOrderQty", 50, "orderMultiple", 25));
        params(box, Map.of("procurement", "buy", "safetyStock", 5));
        assertThat(ok(200, "GET", "/manufacturing/planning-params?productId=" + flour, null).get(0)
                .get("leadTimeDays").asInt()).isEqualTo(2);

        Instant due = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        JsonNode req = ok(201, "POST", "/manufacturing/requests", Map.of("neededBy", due.toString(), "lines",
                List.of(Map.of("productId", cake, "quantity", 30))));
        ok(201, "POST", "/manufacturing/requests/" + req.get("id").asString() + "/submit", null);

        JsonNode run = ok(201, "POST", "/manufacturing/mrp/runs", Map.of("horizonDays", 30));
        assertThat(run.get("runNumber").asString()).startsWith("MRP-");
        JsonNode pc = find(run.get("plannedOrders"), cake);
        JsonNode pd = find(run.get("plannedOrders"), dough);
        JsonNode pf = find(run.get("plannedOrders"), flour);
        JsonNode pb = find(run.get("plannedOrders"), box);
        assertThat(pc.get("kind").asString()).isEqualTo("make");
        assertThat(new BigDecimal(pc.get("quantity").asString())).isEqualByComparingTo("30");
        assertThat(pc.get("pegging").get(0).get("type").asString()).isEqualTo("production_request");
        assertThat(new BigDecimal(pd.get("quantity").asString())).isEqualByComparingTo("6");
        // 6 dough × 3 = 18 flour → min 50 → multiple of 25 → 50.
        assertThat(new BigDecimal(pf.get("quantity").asString())).isEqualByComparingTo("50");
        assertThat(pf.get("kind").asString()).isEqualTo("buy");
        // 3 boxes + safety stock 5.
        assertThat(new BigDecimal(pb.get("quantity").asString())).isEqualByComparingTo("8");
        assertThat(find(run.get("items"), cake).get("lowLevelCode").asInt()).isZero();
        assertThat(find(run.get("items"), dough).get("lowLevelCode").asInt()).isEqualTo(1);
        assertThat(find(run.get("items"), flour).get("lowLevelCode").asInt()).isEqualTo(2);
        // Releases step back by lead time: dough is due when the cake order is released.
        Instant cakeRelease = Instant.parse(pc.get("releaseDate").asString());
        Instant doughDue = Instant.parse(pd.get("dueDate").asString());
        assertThat(doughDue).isEqualTo(cakeRelease);
        assertThat(Instant.parse(pd.get("releaseDate").asString())).isEqualTo(doughDue.minus(1, ChronoUnit.DAYS));

        String runId = run.get("id").asString();
        JsonNode firmed = ok(201, "POST", "/manufacturing/mrp/runs/" + runId + "/firm",
                Map.of("ids", List.of(pc.get("id").asString(), pf.get("id").asString(), pb.get("id").asString())));
        assertThat(firmed.get("firmed").asInt()).isEqualTo(3);
        JsonNode after = ok(200, "GET", "/manufacturing/mrp/runs/" + runId, null);
        JsonNode cakeAfter = find(after.get("plannedOrders"), cake);
        assertThat(cakeAfter.get("firmedDocType").asString()).isEqualTo("production_order");
        assertThat(ok(200, "GET", "/manufacturing/production-orders/" + cakeAfter.get("firmedDocId").asString(), null)
                .get("status").asString()).isEqualTo("draft");
        assertThat(find(after.get("plannedOrders"), flour).get("firmedDocId").asString())
                .isEqualTo(find(after.get("plannedOrders"), box).get("firmedDocId").asString());
        Res twice = as("POST", "/manufacturing/mrp/planned-orders/" + pc.get("id").asString() + "/firm", null);
        assertThat(twice.body().get("message").asString()).isEqualTo("Planned order is already firmed.");
        assertThat(ok(201, "POST", "/manufacturing/mrp/planned-orders/" + pd.get("id").asString() + "/cancel", null)
                .get("status").asString()).isEqualTo("cancelled");

        // Rerun: the firmed cake order is supply, so no new cake is planned; the first run is superseded.
        JsonNode rerun = ok(201, "POST", "/manufacturing/mrp/runs", Map.of());
        assertThat(find(rerun.get("plannedOrders"), cake)).isNull();
        assertThat(ok(200, "GET", "/manufacturing/mrp/runs/" + runId, null).get("status").asString())
                .isEqualTo("superseded");
        assertThat(ok(200, "GET", "/manufacturing/mrp/runs?limit=1", null).get(0).get("id").asString())
                .isEqualTo(rerun.get("id").asString());
        assertThat(as("GET", "/manufacturing/mrp/runs/00000000-0000-0000-0000-000000000000", null).body()
                .get("message").asString()).isEqualTo("MRP run not found");
        assertThat(as("PUT", "/manufacturing/planning-params/" + flour, Map.of("procurement", "steal")).status())
                .isEqualTo(400);
    }
}
