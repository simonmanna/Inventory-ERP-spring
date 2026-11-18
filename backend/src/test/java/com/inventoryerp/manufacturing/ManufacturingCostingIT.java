package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.manufacturing.web.CostingAnalyticsController;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.extension.ExtendWith;
import com.inventoryerp.BooksGuard;

/** Manufacturing costing (Track C 2.8, ADR-S018): rates, absorption, variances, cost reports, margin. */
@ExtendWith(BooksGuard.class)
class ManufacturingCostingIT extends MfgTestSupport {

    static final String C = "/manufacturing/costing";

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    Map<String, String> key(String token, String idem) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", idem);
        return h;
    }

    JsonNode absorb(String orderId, String idem) {
        Res r = call("POST", C + "/orders/" + orderId + "/absorb", null, key(adminToken, idem));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    record Fixture(String wc, String orderId, String workOrderId, String product) {}

    /** A started one-unit order with one 60 minute operation, run for two hours, at rates 10 / 20 / 5 per hour. */
    Fixture ranTwoHours() {
        String wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "CST-" + suffix(), "name", "Cost"))
                .get("id").asString();
        String input = product("CSTIN");
        String output = product("CSTOUT");
        stockIn(bakery, input, 10, 10);
        String bomId = bom(output, input, 1, 1, Map.of());
        ok(201, "POST", "/manufacturing/routings", Map.of("name", "Cost line " + suffix(), "bomId", bomId, "operations",
                List.of(Map.of("sequence", 1, "name", "Run", "workCenterId", wc, "durationMins", 60))));
        ok(200, "PUT", C + "/rates/" + wc, Map.of("labourRatePerHour", 10, "machineRatePerHour", 20,
                "overheadRatePerHour", 5));
        String orderId = started(bomId).get("id").asString();
        String wo = ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/work-orders/generate", null).get(0)
                .get("id").asString();
        ok(201, "POST", "/manufacturing/work-orders/" + wo + "/start", null);
        ok(201, "POST", "/manufacturing/work-orders/" + wo + "/complete", Map.of("labourMins", 120));
        return new Fixture(wc, orderId, wo, output);
    }

    @Test
    @DisplayName("absorption posts what is due once; the order carries it to completion; variances reconcile")
    void plainOrder() {
        Fixture f = ranTwoHours();
        JsonNode before = ok(200, "GET", C + "/orders/" + f.orderId(), null);
        assertThat(before.get("absorptionDue")).hasSize(3);

        String idem = UUID.randomUUID().toString();
        JsonNode first = absorb(f.orderId(), idem);
        assertThat(num(first.get("total"))).isEqualByComparingTo("70"); // 20 labour + 40 machine + 10 overhead
        assertThat(first.get("posted")).hasSize(3);
        assertThat(num(absorb(f.orderId(), idem).get("total"))).as("replay").isEqualByComparingTo("70");
        assertThat(num(absorb(f.orderId(), UUID.randomUUID().toString()).get("total"))).as("second run")
                .isEqualByComparingTo("0");

        JsonNode cost = ok(200, "GET", C + "/orders/" + f.orderId(), null);
        assertThat(num(cost.get("labourCost"))).isEqualByComparingTo("20");
        assertThat(num(cost.get("machineCost"))).isEqualByComparingTo("40");
        assertThat(num(cost.get("overheadCost"))).isEqualByComparingTo("10");
        assertThat(cost.get("absorptionDue")).isEmpty();

        ok(201, "POST", "/manufacturing/production-orders/" + f.orderId() + "/complete", Map.of("qtyProduced", 1));
        JsonNode v = ok(200, "GET", C + "/orders/" + f.orderId() + "/variances", null);
        // Two hours against one standard hour at 10; overhead 10 against 5; material on plan.
        assertThat(num(v.get("labourEfficiency"))).isEqualByComparingTo("10");
        assertThat(num(v.get("overhead"))).isEqualByComparingTo("5");
        assertThat(num(v.get("materialUsage"))).isEqualByComparingTo("0");
        assertThat(num(v.get("yield"))).isEqualByComparingTo("0");
        // Review 2026-11-17: the full split — machine 2 h against 1 h at 20, overhead on machine hours — reconciles:
        // actual 80 (10 + 20 + 40 + 10) less the standard 45 of the good unit = 35
        assertThat(num(v.get("labourRate"))).isEqualByComparingTo("0");
        assertThat(num(v.get("machineEfficiency"))).isEqualByComparingTo("20");
        assertThat(num(v.get("machineSpending"))).isEqualByComparingTo("0");
        assertThat(num(v.get("overheadEfficiency"))).isEqualByComparingTo("5");
        assertThat(num(v.get("overheadSpending"))).isEqualByComparingTo("0");
        assertThat(num(v.get("standardUnitCost"))).isEqualByComparingTo("45");
        assertThat(num(v.get("total"))).isEqualByComparingTo("35");
        assertThat(num(v.get("reconciliation").get("actualCost"))).isEqualByComparingTo("80");
        assertThat(num(v.get("reconciliation").get("standardCost"))).isEqualByComparingTo("45");
        assertThat(num(v.get("reconciliation").get("unexplained"))).isZero();
        assertThat(v.get("materialMix").isNull()).isTrue();
        assertThat(v.get("mixNote").asString()).isEqualTo("A mix variance needs two or more materials.");

        // Rates raised after the output posted: nothing can be absorbed any more.
        ok(200, "PUT", C + "/rates/" + f.wc(), Map.of("labourRatePerHour", 50));
        JsonNode late = absorb(f.orderId(), UUID.randomUUID().toString());
        assertThat(num(late.get("total"))).isEqualByComparingTo("0");
        assertThat(late.get("skipped").get(0).get("reason").asString()).isEqualTo("The order has posted its output");

        JsonNode byProduct = ok(200, "GET", C + "/reports/summary?groupBy=product", null);
        assertThat(byProduct.get("rows").toString()).contains(f.product());
        assertThat(ok(200, "GET", C + "/reports/summary?groupBy=month", null).get("rows")).isNotEmpty();
        assertThat(as("GET", C + "/reports/summary?groupBy=weather", null).status()).isEqualTo(400);
        assertThat(ok(200, "GET", C + "/variances?productId=" + f.product(), null).get("orders")).hasSize(1);
        assertThat(ok(200, "GET", C + "/reports/margin", null).has("totals")).isTrue();
        assertThat(as("GET", C + "/reports/margin?groupBy=colour", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("work order labour already costed through a resource is not absorbed again")
    void resourceCosted() {
        String wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "RES-" + suffix(), "name", "Res"))
                .get("id").asString();
        String input = product("RESIN");
        stockIn(bakery, input, 5, 10);
        String bomId = bom(product("RESOUT"), input, 1, 1, Map.of());
        ok(201, "POST", "/manufacturing/routings", Map.of("name", "Res line " + suffix(), "bomId", bomId, "operations",
                List.of(Map.of("sequence", 1, "name", "Run", "workCenterId", wc, "durationMins", 60))));
        String res = ok(201, "POST", "/manufacturing/resources", Map.of("code", "OP-" + suffix(), "name", "Operator",
                "kind", "labour", "costPerHour", 30)).get("id").asString();
        ok(200, "PUT", C + "/rates/" + wc, Map.of("labourRatePerHour", 10, "overheadRatePerHour", 5));
        String orderId = started(bomId).get("id").asString();
        String wo = ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/work-orders/generate", null).get(0)
                .get("id").asString();
        ok(200, "PATCH", "/manufacturing/work-orders/" + wo + "/assign", Map.of("resourceId", res));
        ok(201, "POST", "/manufacturing/work-orders/" + wo + "/start", null);
        ok(201, "POST", "/manufacturing/work-orders/" + wo + "/complete", Map.of("labourMins", 60));
        JsonNode out = absorb(orderId, UUID.randomUUID().toString());
        assertThat(num(out.get("total"))).isEqualByComparingTo("5"); // overhead only
        // Review 2026-11-17: the operator costs 30 an hour against the centre's standard 10 — a labour rate variance of
        // 20 on the standard hour, no efficiency variance
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/complete", Map.of("qtyProduced", 1));
        JsonNode v = ok(200, "GET", C + "/orders/" + orderId + "/variances", null);
        assertThat(num(v.get("labourRate"))).isEqualByComparingTo("20");
        assertThat(num(v.get("labourEfficiency"))).isEqualByComparingTo("0");
        assertThat(num(v.get("total"))).isEqualByComparingTo("20");
        assertThat(num(v.get("reconciliation").get("unexplained"))).isZero();
    }

    @Test
    @DisplayName("mix and input yield: 9 kg A + 2 kg B instead of 6 + 4 — usage −3 = mix −4.8 + input yield 1.8")
    void mixAndYield() {
        String kg = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, category, factor, ratio,"
                + " \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, 'Kilogram', 'weight', 1, 1, 0.001, now())")
                .params(kg, demoOrg, "KG-" + suffix()).update();
        String a = product("MIXA");
        String b = product("MIXB");
        String out = product("MIXOUT");
        jdbc.sql("UPDATE \"Product\" SET \"uomId\" = ? WHERE id IN (?, ?)").params(kg, a, b).update();
        stockIn(bakery, a, 20, 1);
        stockIn(bakery, b, 20, 3);
        String bomId = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Blend " + suffix(), "outputProductId",
                out, "outputQuantity", 10, "lines", List.of(Map.of("componentProductId", a, "quantity", 6),
                        Map.of("componentProductId", b, "quantity", 4)))).get("id").asString();
        ok(201, "POST", "/manufacturing/boms/" + bomId + "/activate", null);
        String id = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1,
                "locationId", bakery, "outputLocationId", warehouse)).get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + id + "/confirm", null);
        Map<String, String> line = new LinkedHashMap<>();
        for (JsonNode m : ok(200, "GET", "/manufacturing/production-orders/" + id, null).get("materials")) {
            line.put(m.get("productId").asString(), m.get("id").asString());
        }
        ok(201, "POST", "/manufacturing/production-orders/" + id + "/start", Map.of("materials", List.of(Map.of(
                "lineId", line.get(a), "qtyConsumed", 9), Map.of("lineId", line.get(b), "qtyConsumed", 2))));
        ok(201, "POST", "/manufacturing/production-orders/" + id + "/complete", Map.of("qtyProduced", 10));

        JsonNode v = ok(200, "GET", C + "/orders/" + id + "/variances", null);
        assertThat(num(v.get("materialPrice"))).isEqualByComparingTo("0");
        assertThat(num(v.get("materialUsage"))).isEqualByComparingTo("-3");
        // standard mix of 11 kg: 6.6 A + 4.4 B; the cheaper blend saves 2.4 − 7.2 = −4.8; 1 kg more in total at the
        // recipe's average 1.8 a kg costs 1.8
        assertThat(num(v.get("materialMix"))).isEqualByComparingTo("-4.8");
        assertThat(num(v.get("materialInputYield"))).isEqualByComparingTo("1.8");
        assertThat(num(v.get("materialUsageNotSplit"))).isZero();
        assertThat(v.get("mixNote").isNull()).isTrue();
        JsonNode lineA = v.get("materials").get(0).get("productId").asString().equals(a) ? v.get("materials").get(0)
                : v.get("materials").get(1);
        assertThat(num(lineA.get("mixVariance"))).isEqualByComparingTo("2.4");
        assertThat(num(lineA.get("inputYieldVariance"))).isEqualByComparingTo("0.6");
        assertThat(num(v.get("reconciliation").get("actualCost"))).isEqualByComparingTo("15");
        assertThat(num(v.get("reconciliation").get("standardCost"))).isEqualByComparingTo("18");
        assertThat(num(v.get("reconciliation").get("unexplained"))).isZero();
        JsonNode mix = null;
        for (JsonNode l : v.get("lines")) {
            if ("materialMix".equals(l.get("key").asString())) {
                mix = l;
            }
        }
        assertThat(mix).isNotNull();
        assertThat(mix.get("parent").asString()).isEqualTo("materialUsage");
        assertThat(mix.get("label").asString()).isEqualTo("Mix");

        JsonNode period = ok(200, "GET", C + "/variances?productId=" + out, null);
        assertThat(num(period.get("totals").get("materialMix"))).isEqualByComparingTo("-4.8");
        assertThat(num(period.get("totals").get("materialInputYield"))).isEqualByComparingTo("1.8");
    }

    @Test
    @DisplayName("rates: validation, unknown centre, delete; every active centre is listed")
    void rates() {
        String wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "RAT-" + suffix(), "name", "Rate"))
                .get("id").asString();
        JsonNode list = ok(200, "GET", C + "/rates", null);
        assertThat(list.toString()).contains(wc);
        assertThat(as("PUT", C + "/rates/" + wc, Map.of("labourRatePerHour", -1)).status()).isEqualTo(400);
        assertThat(as("PUT", C + "/rates/" + UUID.randomUUID(), Map.of()).status()).isEqualTo(404);
        assertThat(as("PUT", C + "/rates/" + wc, Map.of("fleetVehicleId", UUID.randomUUID().toString())).status())
                .isEqualTo(404);
        ok(200, "PUT", C + "/rates/" + wc, Map.of("labourRatePerHour", 7));
        ok(204, "DELETE", C + "/rates/" + wc, null);
        assertThat(as("DELETE", C + "/rates/" + wc, null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("absorb needs an Idempotency-Key; other organizations see nothing")
    void guards() {
        Fixture f = ranTwoHours();
        assertThat(as("POST", C + "/orders/" + f.orderId() + "/absorb", null).status()).isEqualTo(400);
        assertThat(call("GET", C + "/orders/" + f.orderId(), null, otherToken).status()).isEqualTo(404);
        assertThat(call("PUT", C + "/rates/" + f.wc(), Map.of(), otherToken).status()).isEqualTo(404);
        assertThat(call("POST", C + "/orders/" + f.orderId() + "/absorb", null, key(otherToken,
                UUID.randomUUID().toString())).status()).isEqualTo(404);
        assertThat(call("GET", C + "/rates", null, otherToken).body().toString()).doesNotContain(f.wc());
    }

    @Test
    @DisplayName("authorization matrix of the costing routes")
    void authorization() {
        assertThat(assertAuthorizationMatrix(CostingAnalyticsController.class)).isEqualTo(9);
    }
}
