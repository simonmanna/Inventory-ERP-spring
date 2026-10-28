package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** ADR-S006 package D: cost roll-up, standard cost + variances, subcontracting, where-used, substitutes, ECOs. */
class CostingEngineeringIT extends MfgTestSupport {

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    @Test
    @DisplayName("roll-up → standard → order with subcontracting → price variance; where-used")
    void costing() {
        String flour = product("CFLOUR");
        String box = product("CBOX");
        String dough = product("CDOUGH");
        String cake = product("CCAKE");
        jdbc.sql("UPDATE \"Product\" SET \"costPrice\" = 2 WHERE id = ?").param(box).update();
        stockIn(warehouse, flour, 10, 5);
        bom(dough, flour, 1, 3, Map.of());

        JsonNode wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "DEC-" + suffix(), "name", "Deco"));
        ok(200, "PATCH", "/manufacturing/work-centers/" + wc.get("id").asString(), Map.of("costPerHour", 60));
        JsonNode routing = ok(201, "POST", "/manufacturing/routings", Map.of("name", "Cake line", "operations", List.of(
                Map.of("sequence", 1, "name", "Bake", "workCenterId", wc.get("id").asString(), "durationMins", 30),
                Map.of("sequence", 2, "name", "Decorate", "isSubcontracted", true, "subcontractCostPerUnit", 0.5))));
        JsonNode cakeBom = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Cake", "outputProductId", cake,
                "outputQuantity", 10, "overheadCost", 10, "routingId", routing.get("id").asString(),
                "lines", List.of(Map.of("componentProductId", dough, "quantity", 2),
                        Map.of("componentProductId", box, "quantity", 1))));
        String bomId = cakeBom.get("id").asString();
        ok(201, "POST", "/manufacturing/boms/" + bomId + "/activate", null);

        // dough = 3 × 5 = 15; material 2 × 15 + 1 × 2 = 32; labour 30 min × 60/h = 30; subcontract 10 × 0.5 = 5.
        JsonNode cost = ok(200, "GET", "/manufacturing/boms/" + bomId + "/cost", null);
        assertThat(num(cost.get("materialCost"))).isEqualByComparingTo("32");
        assertThat(num(cost.get("labourCost"))).isEqualByComparingTo("30");
        assertThat(num(cost.get("subcontractCost"))).isEqualByComparingTo("5");
        assertThat(num(cost.get("totalCost"))).isEqualByComparingTo("77");
        assertThat(num(cost.get("unitCost"))).isEqualByComparingTo("7.7");
        JsonNode std = ok(201, "POST", "/manufacturing/boms/" + bomId + "/standard-cost", null);
        assertThat(ok(200, "GET", "/manufacturing/boms/" + bomId + "/standard-costs", null)).hasSize(1);

        // Actual dough costs 20 (standard 15): a material price variance of (20 − 15) × 2 = 10.
        stockIn(bakery, dough, 2, 20);
        stockIn(bakery, box, 1, 2);
        JsonNode order = started(bomId);
        String orderId = order.get("id").asString();
        assertThat(ok(200, "GET", "/manufacturing/production-orders/" + orderId, null).get("standardCostId").asString())
                .isEqualTo(std.get("id").asString());
        JsonNode res = ok(201, "POST", "/manufacturing/resources", Map.of("name", "Baker", "kind", "labour",
                "costPerHour", 60));
        JsonNode wos = ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/work-orders/generate", null);
        ok(200, "PATCH", "/manufacturing/work-orders/" + wos.get(0).get("id").asString() + "/assign",
                Map.of("resourceId", res.get("id").asString()));
        ok(201, "POST", "/manufacturing/work-orders/" + wos.get(0).get("id").asString() + "/complete",
                Map.of("labourMins", 30));
        JsonNode sub = ok(201, "POST", "/manufacturing/work-orders/" + wos.get(1).get("id").asString() + "/complete",
                Map.of("qtyGood", 10));
        assertThat(num(sub.get("subcontractCost"))).isEqualByComparingTo("5");
        assertThat(jdbc.sql("SELECT count(*) FROM \"PurchaseRequest\" WHERE \"organizationId\" = ? AND description"
                + " LIKE ?").params(demoOrg, "Subcontract Decorate · " + order.get("orderCode").asString())
                .query(Long.class).single()).isEqualTo(1);

        JsonNode done = ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/complete",
                Map.of("qtyProduced", 10));
        // 42 material + 10 BOM overhead + 30 labour + 5 subcontract = 87 → 8.7 per cake.
        assertThat(num(done.get("outputUnitCost"))).isEqualByComparingTo("8.7");
        JsonNode variances = ok(200, "GET", "/manufacturing/reports/variances?orderId=" + orderId, null);
        assertThat(num(variances.get("totals").get("material_price"))).isEqualByComparingTo("10");
        assertThat(num(variances.get("totals").get("material_usage"))).isEqualByComparingTo("0");
        assertThat(num(variances.get("totals").get("labour"))).isEqualByComparingTo("0");
        assertThat(num(variances.get("totals").get("subcontract"))).isEqualByComparingTo("0");
        assertThat(num(variances.get("total"))).isEqualByComparingTo("10");

        JsonNode used = ok(200, "GET", "/manufacturing/reports/where-used/" + flour, null);
        assertThat(used.get(0).get("outputProductId").asString()).isEqualTo(dough);
        assertThat(used.get(1).get("outputProductId").asString()).isEqualTo(cake);
        assertThat(used.get(1).get("level").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("substitutes cover a shortage at start; ECO revise → compare → approve → apply")
    void substitutesAndEco() {
        String flour = product("EFLOUR");
        String alt = product("EALT");
        String bread = product("EBREAD");
        String bomId = bom(bread, flour, 1, 2, Map.of());
        JsonNode lines = ok(200, "GET", "/manufacturing/boms/" + bomId + "/substitutes", null);
        String lineId = lines.get(0).get("id").asString();
        assertThat(as("PUT", "/manufacturing/boms/" + bomId + "/lines/" + lineId + "/substitutes", Map.of("substitutes",
                List.of(Map.of("productId", flour)))).body().get("message").asString())
                .isEqualTo("A component cannot substitute itself.");
        ok(200, "PUT", "/manufacturing/boms/" + bomId + "/lines/" + lineId + "/substitutes", Map.of("substitutes",
                List.of(Map.of("productId", alt, "ratio", 1.5))));

        stockIn(bakery, alt, 10, 4);
        JsonNode order = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1,
                "locationId", bakery, "outputLocationId", warehouse));
        String id = order.get("id").asString();
        JsonNode avail = ok(200, "GET", "/manufacturing/production-orders/" + id + "/availability", null);
        assertThat(avail.get(0).get("shortage").asString()).isEqualTo("2");
        assertThat(avail.get(0).get("substitutes").get(0).get("covers").asBoolean()).isTrue();
        ok(201, "POST", "/manufacturing/production-orders/" + id + "/confirm", null);
        String lid = order.get("materials").get(0).get("id").asString();
        Res bad = as("POST", "/manufacturing/production-orders/" + id + "/start", Map.of("materials",
                List.of(Map.of("lineId", lid, "qtyConsumed", 2, "substituteProductId", bread))));
        assertThat(bad.body().get("message").asString()).endsWith("is not an approved substitute for "
                + order.get("materials").get(0).get("productName").asString() + ".");
        JsonNode started = ok(201, "POST", "/manufacturing/production-orders/" + id + "/start", Map.of("materials",
                List.of(Map.of("lineId", lid, "qtyConsumed", 2, "substituteProductId", alt))));
        assertThat(started.get("materials").get(0).get("productId").asString()).isEqualTo(alt);
        assertThat(num(started.get("materials").get(0).get("qtyConsumed"))).isEqualByComparingTo("3");
        assertThat(onHand(alt, bakery)).isEqualByComparingTo("7");

        JsonNode revised = ok(201, "POST", "/manufacturing/boms/" + bomId + "/revise", Map.of("title", "Less flour",
                "reason", "cost"));
        JsonNode eco = revised.get("change");
        assertThat(eco.get("number").asString()).startsWith("ECO-");
        String newBom = revised.get("bom").get("id").asString();
        assertThat(revised.get("bom").get("version").asInt()).isEqualTo(2);
        assertThat(ok(200, "GET", "/manufacturing/boms/" + newBom + "/substitutes", null).get(0).get("substitutes"))
                .hasSize(1);
        ok(200, "PATCH", "/manufacturing/boms/" + newBom, Map.of("lines", List.of(Map.of("componentProductId", flour,
                "quantity", 1.5))));
        JsonNode diff = ok(200, "GET", "/manufacturing/boms/" + bomId + "/compare/" + newBom, null);
        assertThat(diff.get("changed").get(0).get("changes").get("quantity").get("to").asString()).isEqualTo("1.5");
        String ecoId = eco.get("id").asString();
        assertThat(as("POST", "/manufacturing/engineering-changes/" + ecoId + "/apply", null).body().get("message")
                .asString()).isEqualTo("Only an approved change can be applied (status draft).");
        ok(201, "POST", "/manufacturing/engineering-changes/" + ecoId + "/approve", null);
        JsonNode applied = ok(201, "POST", "/manufacturing/engineering-changes/" + ecoId + "/apply", null);
        assertThat(applied.get("status").asString()).isEqualTo("applied");
        assertThat(ok(200, "GET", "/manufacturing/boms/" + bomId, null).get("status").asString()).isEqualTo("archived");
        assertThat(ok(200, "GET", "/manufacturing/boms/" + newBom, null).get("status").asString()).isEqualTo("active");
        assertThat(ok(200, "GET", "/manufacturing/engineering-changes/" + ecoId, null).get("comparison").get("changed"))
                .hasSize(1);
    }
}
