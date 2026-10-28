package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** ADR-S006 package A: the Nest bugs fixed for production use, each pinned by a test. */
class ManufacturingHardeningIT extends MfgTestSupport {

    String uom(String code) {
        return jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = ? LIMIT 1")
                .params(demoOrg, code).query(String.class).single();
    }

    @Test
    @DisplayName("A1: MRP shortfall is expressed in the purchase unit; open PO counts only its unreceived part")
    void mrpPurchaseUnit() {
        String sugar = product("SUGAR");
        jdbc.sql("UPDATE \"Product\" SET \"uomId\" = ?, \"purchaseUomId\" = ? WHERE id = ?")
                .params(uom("G"), uom("KG"), sugar).update();
        String cake = product("CAKEU");
        String bomId = bom(cake, sugar, 1, 2500, Map.of());
        ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 2, "locationId", bakery,
                "outputLocationId", warehouse));
        JsonNode mrp = ok(201, "POST", "/manufacturing/mrp/run", null);
        JsonNode line = null;
        for (JsonNode l : mrp.get("purchaseRequest").get("lines")) {
            if (l.get("productId").asString().equals(sugar)) {
                line = l;
            }
        }
        assertThat(line).isNotNull();
        assertThat(line.get("unitOfMeasureId").asString()).isEqualTo(uom("KG"));
        assertThat(new BigDecimal(line.get("quantity").asString())).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("A2+A3: labour is the sum of run intervals; no costing after the order has posted")
    void timeLogs() {
        JsonNode wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "H-" + suffix(), "name", "Line"));
        JsonNode res = ok(201, "POST", "/manufacturing/resources", Map.of("name", "Op", "kind", "labour",
                "costPerHour", 60));
        String flour = product("FLOURH");
        String bun = product("BUNH");
        stockIn(bakery, flour, 10, 10);
        String bomId = bom(bun, flour, 1, 1, Map.of());
        ok(201, "POST", "/manufacturing/routings", Map.of("name", "R", "bomId", bomId, "operations", List.of(
                Map.of("sequence", 1, "name", "Run", "workCenterId", wc.get("id").asString()))));
        String orderId = started(bomId).get("id").asString();
        String wo = ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/work-orders/generate", null)
                .get(0).get("id").asString();
        ok(200, "PATCH", "/manufacturing/work-orders/" + wo + "/assign", Map.of("resourceId", res.get("id").asString()));

        ok(201, "POST", "/manufacturing/work-orders/" + wo + "/start", null);
        backdateOpenLog(wo, 30);
        assertThat(ok(201, "POST", "/manufacturing/work-orders/" + wo + "/pause", null).get("labourMins").asInt())
                .isEqualTo(30);
        ok(201, "POST", "/manufacturing/work-orders/" + wo + "/start", null);
        backdateOpenLog(wo, 10);
        JsonNode done = ok(201, "POST", "/manufacturing/work-orders/" + wo + "/complete", Map.of());
        // Nest would count 30 + (time since the first start) = 70+.
        assertThat(done.get("labourMins").asInt()).isEqualTo(40);
        assertThat(jdbc.sql("SELECT count(*) FROM \"WorkOrderTimeLog\" WHERE \"workOrderId\" = ?").param(wo)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(ok(200, "GET", "/manufacturing/production-orders/" + orderId, null).get("overheadCost").asString())
                .isEqualTo("40");

        // A second work order completed after the production order posted is refused.
        String wo2 = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"WorkOrder\" (id, \"organizationId\", \"productionOrderId\", \"operationSequence\","
                + " name, status, \"updatedAt\") VALUES (?, ?, ?, 2, 'Late', 'pending', now())")
                .params(wo2, demoOrg, orderId).update();
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/complete", Map.of("qtyProduced", 1));
        Res late = as("POST", "/manufacturing/work-orders/" + wo2 + "/complete", Map.of("labourMins", 5));
        assertThat(late.status()).isEqualTo(400);
        assertThat(late.body().get("message").asString()).endsWith("is completed; work-order time can no longer be"
                + " costed to it.");
    }

    void backdateOpenLog(String wo, int minutes) {
        jdbc.sql("UPDATE \"WorkOrderTimeLog\" SET \"startedAt\" = \"startedAt\" - make_interval(mins => ?)"
                + " WHERE \"workOrderId\" = ? AND \"endedAt\" IS NULL").params(minutes, wo).update();
    }

    @Test
    @DisplayName("A4: a second min-stock scan does not raise the same shortfall again")
    void scanNetsOpenRequests() {
        String flour = product("FLOURS");
        String loaf = product("LOAF");
        bom(loaf, flour, 1, 1, Map.of());
        jdbc.sql("UPDATE \"Product\" SET \"minQuantity\" = 7 WHERE id = ?").param(loaf).update();
        JsonNode first = ok(201, "POST", "/manufacturing/mrp/scan-min-stock", null);
        assertThat(first.toString()).contains(loaf);
        JsonNode second = ok(201, "POST", "/manufacturing/mrp/scan-min-stock", null);
        assertThat(second.toString()).doesNotContain(loaf);
    }

    @Test
    @DisplayName("A5+A6: a QC-held order can be reversed; output lines link to the lot they created")
    void reverseHeldAndGenealogy() {
        String flour = product("FLOURQ");
        String cake = product("CAKEQ");
        jdbc.sql("UPDATE \"Product\" SET \"batchTracking\" = true WHERE id = ?").param(cake).update();
        stockIn(bakery, flour, 10, 100);
        BigDecimal wipBefore = balance(wipAccount);
        String id = started(bom(cake, flour, 10, 2, Map.of("qcRequired", true))).get("id").asString();
        JsonNode held = ok(201, "POST", "/manufacturing/production-orders/" + id + "/complete",
                Map.of("qtyProduced", 10, "batchNumber", "LOT-" + suffix()));
        assertThat(held.get("status").asString()).isEqualTo("qc_hold");
        assertThat(held.get("outputs").get(0).get("batchId").isNull()).isFalse();
        JsonNode reversed = ok(201, "POST", "/manufacturing/production-orders/" + id + "/reverse",
                Map.of("reason", "contaminated"));
        assertThat(reversed.get("status").asString()).isEqualTo("cancelled");
        assertThat(onHand(flour, bakery)).isEqualByComparingTo("10");
        assertThat(balance(wipAccount)).isEqualByComparingTo(wipBefore);
    }

    @Test
    @DisplayName("A7+A8+A9: 404s for unknown references, editable routing/overhead, list paging")
    void referencesAndPaging() {
        String a = product("RA");
        String b = product("RB");
        Res noProduct = as("POST", "/manufacturing/boms", Map.of("name", "x", "outputProductId", a, "outputQuantity", 1,
                "lines", List.of(Map.of("componentProductId", UUID.randomUUID().toString(), "quantity", 1))));
        assertThat(noProduct.status()).isEqualTo(404);
        assertThat(noProduct.body().get("message").asString()).startsWith("Product ").endsWith(" not found");
        Res noLoc = as("POST", "/manufacturing/boms", Map.of("name", "x", "outputProductId", a, "outputQuantity", 1,
                "defaultLocationId", UUID.randomUUID().toString(), "lines", List.of(Map.of("componentProductId", b,
                        "quantity", 1))));
        assertThat(noLoc.body().get("message").asString()).isEqualTo("Location not found");

        JsonNode routing = ok(201, "POST", "/manufacturing/routings", Map.of("name", "R", "operations",
                List.of(Map.of("sequence", 1, "name", "Op"))));
        JsonNode draft = ok(201, "POST", "/manufacturing/boms", Map.of("name", "x", "outputProductId", a,
                "outputQuantity", 1, "routingId", routing.get("id").asString(), "lines",
                List.of(Map.of("componentProductId", b, "quantity", 1))));
        assertThat(draft.get("routingId").asString()).isEqualTo(routing.get("id").asString());
        JsonNode edited = ok(200, "PATCH", "/manufacturing/boms/" + draft.get("id").asString(),
                Map.of("overheadCost", 12.5));
        assertThat(edited.get("overheadCost").asString()).isEqualTo("12.5");

        Res order = as("POST", "/manufacturing/production-orders", Map.of("outputProductId", a, "plannedQty", 1,
                "locationId", UUID.randomUUID().toString(), "materials", List.of(Map.of("productId", b, "quantity", 1))));
        assertThat(order.status()).isEqualTo(404);
        assertThat(order.body().get("message").asString()).isEqualTo("Location not found");

        ok(201, "POST", "/manufacturing/boms", Map.of("name", "v2", "outputProductId", a, "outputQuantity", 1,
                "lines", List.of(Map.of("componentProductId", b, "quantity", 1))));
        assertThat(ok(200, "GET", "/manufacturing/boms?outputProductId=" + a + "&limit=1", null)).hasSize(1);
        assertThat(ok(200, "GET", "/manufacturing/boms?outputProductId=" + a + "&limit=1&offset=1", null)).hasSize(1);
        assertThat(as("GET", "/manufacturing/production-orders?limit=0", null).body().get("message").asString())
                .isEqualTo("limit must be between 1 and 1000");
    }
}
