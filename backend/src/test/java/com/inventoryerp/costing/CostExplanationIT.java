package com.inventoryerp.costing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Review 2026-11-17 (ease of use): "why does this item cost X" — {@code GET /inventory/cost-explanation} explains a
 * carrying cost from the stock sub-ledger: value, quantity and unit cost per location, the NRV allowance beside it, the
 * value-only changes, and every movement with the running quantity, value and unit cost after it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CostExplanationIT extends ApiTestSupport {

    String org;
    String token;
    String userId;
    String location;
    String today = LocalDate.now(ZoneOffset.UTC).toString();

    @BeforeAll
    void books() {
        String code = "ce-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Explain Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
                "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        org = boot.body().get("organization").get("id").asString();
        userId = boot.body().get("adminUser").get("id").asString();
        token = login(code, "owner@" + code + ".test", "Owner@12345");
        location = ok(201, "POST", "/inventory/locations", Map.of("code", "WH-1", "name", "Main", "type", "warehouse"))
                .get("id").asString();
    }

    @Override
    protected JsonNode ok(int status, String method, String path, Object body) {
        Res r = call(method, path, body, token);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    JsonNode keyedOk(String method, String path, Object body) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res r = call(method, path, body, h);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    String product(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                + " \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', 'AVCO', 0, 20, now())")
                .params(id, org, name.toUpperCase() + "-" + id.substring(0, 4), name).update();
        return id;
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    void revalue(String productId, Map<String, Object> line, String kind) {
        Map<String, Object> l = new HashMap<>(line);
        l.put("productId", productId);
        l.put("locationId", location);
        String id = ok(201, "POST", "/inventory/revaluations", Map.of("kind", kind, "date", today, "reason", "Review",
                "lines", List.of(l))).get("id").asString();
        keyedOk("POST", "/inventory/revaluations/" + id + "/post", null);
    }

    @Test
    @DisplayName("100 in at 10, 30 out, revalued to 12, NRV 11: value 840, allowance −70, carried 770 = 11 a unit")
    void explainsTheCarryingCost() {
        String cement = product("Cement");
        String grn = ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("warehouseId", location, "receivedAt",
                Instant.now().toString(), "lines", List.of(Map.of("productId", cement, "description", "Cement",
                        "quantity", 100, "unitCost", 10)))).get("id").asString();
        String grnNumber = jdbc.sql("SELECT \"receiptNumber\" FROM \"GoodsReceiptNote\" WHERE id = ?").param(grn)
                .query(String.class).single();
        ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", location, "responsibleById", userId,
                "approvedById", userId, "items", List.of(Map.of("productId", cement, "quantity", 30))));
        revalue(cement, Map.of("unitCostAfter", 12), "revaluation");
        revalue(cement, Map.of("netRealisableValue", 11), "nrv");

        JsonNode e = ok(200, "GET", "/inventory/cost-explanation?productId=" + cement, null);
        assertThat(e.get("product").get("name").asString()).isEqualTo("Cement");
        assertThat(e.get("method").get("code").asString()).isEqualTo("AVCO");
        assertThat(e.get("method").get("label").asString()).isEqualTo("Weighted average cost");

        JsonNode loc = e.get("locations");
        assertThat(loc).hasSize(1);
        assertThat(loc.get(0).get("locationName").asString()).isEqualTo("Main");
        assertThat(num(loc.get(0).get("quantity"))).isEqualByComparingTo("70");
        assertThat(num(loc.get(0).get("value"))).isEqualByComparingTo("840");
        assertThat(num(loc.get(0).get("unitCost"))).isEqualByComparingTo("12");
        assertThat(num(loc.get(0).get("nrvAllowance"))).isEqualByComparingTo("-70");
        assertThat(num(loc.get(0).get("priceDifference"))).isZero();
        assertThat(num(loc.get(0).get("carryingValue"))).isEqualByComparingTo("770");
        assertThat(num(loc.get(0).get("carryingUnitCost"))).isEqualByComparingTo("11");
        assertThat(num(e.get("totals").get("carryingValue"))).isEqualByComparingTo("770");

        List<String> summary = new ArrayList<>();
        e.get("summary").forEach(s -> summary.add(s.asString()));
        assertThat(summary.get(0)).isEqualTo("70 on hand at Main, carried at 770.00 in total — 11.00 a unit.");
        assertThat(summary).anyMatch(s -> s.startsWith("Weighted average cost: Every receipt is blended"));
        assertThat(summary).contains("Stock last came in on " + today + ": 100 at 10.00 a unit (" + grnNumber + ").");
        assertThat(summary).anyMatch(s -> s.startsWith("An allowance of 70.00 writes this stock down"));
        assertThat(summary).contains("The stock value was changed without a quantity movement 1 time, mainly:"
                + " revaluation +140.00.");

        assertThat(e.get("adjustments")).hasSize(1);
        assertThat(e.get("adjustments").get(0).get("referenceType").asString()).isEqualTo("inventory_revaluation");
        assertThat(num(e.get("adjustments").get(0).get("amount"))).isEqualByComparingTo("140");
        assertThat(e.get("openAdjuncts")).hasSize(1);
        assertThat(e.get("openAdjuncts").get(0).get("component").asString()).isEqualTo("nrv_allowance");
        assertThat(num(e.get("openAdjuncts").get(0).get("openAmount"))).isEqualByComparingTo("-70");

        // newest first: NRV (adjunct, running unchanged), revaluation, issue, receipt
        JsonNode m = e.get("movements");
        assertThat(e.get("movementsTotal").asLong()).isEqualTo(4);
        assertThat(m).hasSize(4);
        assertThat(m.get(0).get("component").asString()).isEqualTo("nrv_allowance");
        assertThat(m.get(0).get("label").asString()).isEqualTo("Write-down to net realisable value");
        assertThat(num(m.get(0).get("runningValue"))).isEqualByComparingTo("840");
        assertThat(m.get(1).get("label").asString()).isEqualTo("Revaluation");
        assertThat(num(m.get(1).get("runningUnitCost"))).isEqualByComparingTo("12");
        assertThat(m.get(2).get("label").asString()).isEqualTo("Issued — direct stock entry");
        assertThat(num(m.get(2).get("value"))).isEqualByComparingTo("-300");
        assertThat(num(m.get(2).get("runningQuantity"))).isEqualByComparingTo("70");
        assertThat(num(m.get(2).get("runningValue"))).isEqualByComparingTo("700");
        JsonNode receipt = m.get(3);
        assertThat(receipt.get("label").asString()).isEqualTo("Received — goods receipt");
        assertThat(receipt.get("documentNo").asString()).isEqualTo(grnNumber);
        assertThat(receipt.get("traceable").asBoolean()).isTrue();
        assertThat(m.get(2).get("traceable").asBoolean()).isFalse();
        assertThat(num(receipt.get("runningUnitCost"))).isEqualByComparingTo("10");
        // the receipt row is the layer the trace screen opens
        JsonNode trace = ok(200, "GET", "/inventory/cost-layers/" + receipt.get("ledgerId").asString() + "/trace", null);
        assertThat(num(trace.get("originalUnitCost"))).isEqualByComparingTo("10");

        JsonNode one = ok(200, "GET", "/inventory/cost-explanation?productId=" + cement + "&locationId=" + location
                + "&limit=1", null);
        assertThat(one.get("movements")).hasSize(1);
        assertThat(one.get("movementsShown").asInt()).isEqualTo(1);
        assertThat(one.get("movementsTotal").asLong()).isEqualTo(4);
        assertThat(num(one.get("totals").get("carryingValue"))).isEqualByComparingTo("770");
    }

    @Test
    @DisplayName("validation, unknown location, other organisation, and nothing on hand")
    void guards() {
        Res missing = call("GET", "/inventory/cost-explanation", null, token);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body().get("message").asString()).isEqualTo("productId should not be empty");
        String sand = product("Sand");
        Res badLimit = call("GET", "/inventory/cost-explanation?productId=" + sand + "&limit=x", null, token);
        assertThat(badLimit.status()).isEqualTo(400);
        assertThat(badLimit.body().get("message").asString()).isEqualTo("limit must be an integer number");
        Res unknownLocation = call("GET", "/inventory/cost-explanation?productId=" + sand + "&locationId="
                + UUID.randomUUID(), null, token);
        assertThat(unknownLocation.status()).isEqualTo(404);
        assertThat(unknownLocation.body().get("message").asString()).isEqualTo("Location not found");
        Res foreign = call("GET", "/inventory/cost-explanation?productId=" + sand, null, otherToken);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.body().get("message").asString()).isEqualTo("Product not found");

        JsonNode empty = ok(200, "GET", "/inventory/cost-explanation?productId=" + sand, null);
        assertThat(empty.get("locations")).isEmpty();
        assertThat(empty.get("movements")).isEmpty();
        JsonNode unit = empty.get("totals").get("unitCost");
        assertThat(unit == null || unit.isNull()).as("no unit cost without stock").isTrue();
        assertThat(empty.get("summary").get(0).asString()).isEqualTo("Nothing is on hand now.");
    }
}
