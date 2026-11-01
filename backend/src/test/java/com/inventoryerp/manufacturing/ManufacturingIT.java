package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.CompleteOrder;
import com.inventoryerp.manufacturing.application.ProductionService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Manufacturing on Testcontainers Postgres — port of {@code test/integration/manufacturing-flow.spec.ts} (the
 * WIP-flat invariant across bake / cancel / under-yield / zero-yield / QC / reverse / overhead) plus requests,
 * plans, MRP, work centres, routings, work orders, reports, validation, tenant isolation and the authorization matrix.
 */
class ManufacturingIT extends MfgTestSupport {

    @Test
    @DisplayName("bakes a batch: consume → WIP, output ← WIP, WIP nets to zero, no COGS leak")
    void bake() {
        String flour = product("FLOUR");
        String cake = product("CAKE");
        stockIn(bakery, flour, 10, 5000);
        BigDecimal wipBefore = balance(wipAccount);
        BigDecimal cogsBefore = balance(mapped("cogs"));

        String bomId = bom(cake, flour, 20, 4, Map.of());
        JsonNode s = started(bomId);
        assertThat(s.get("status").asString()).isEqualTo("in_progress");
        assertThat(s.get("materialCost").asString()).isEqualTo("20000");
        String id = s.get("id").asString();
        assertThat(ok(200, "GET", "/manufacturing/production-orders/" + id, null).get("materials").get(0)
                .get("qtyConsumed").asString()).isEqualTo("4");

        JsonNode done = ok(201, "POST", "/manufacturing/production-orders/" + id + "/complete",
                Map.of("qtyProduced", 20));
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(done.get("outputUnitCost").asString()).isEqualTo("1000");
        assertThat(done.get("outputs").get(0).get("qtyProduced").asString()).isEqualTo("20");

        assertThat(onHand(flour, bakery)).isEqualByComparingTo("6");
        assertThat(onHand(cake, warehouse)).isEqualByComparingTo("20");
        assertThat(balance(wipAccount)).isEqualByComparingTo(wipBefore);
        assertThat(balance(mapped("cogs"))).isEqualByComparingTo(cogsBefore);

        // Idempotency guards: a second start/complete is refused verbatim.
        Res again = as("POST", "/manufacturing/production-orders/" + id + "/complete", Map.of("qtyProduced", 20));
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body().get("message").asString())
                .isEqualTo("Order " + s.get("orderCode").asString() + " is not completable (status completed).");
        Res restart = as("POST", "/manufacturing/production-orders/" + id + "/start", Map.of());
        assertThat(restart.body().get("message").asString())
                .isEqualTo("Order " + s.get("orderCode").asString() + " is not in a startable state (status completed).");
        Res cancel = as("POST", "/manufacturing/production-orders/" + id + "/cancel", Map.of());
        assertThat(cancel.body().get("message").asString())
                .isEqualTo("A completed order cannot be cancelled — reverse it (Phase 3).");

        // Reports over the completed order.
        JsonNode back = ok(200, "GET", "/manufacturing/reports/genealogy/backward/" + id, null);
        assertThat(back.get("materials").get(0).get("ledgerCode").isNull()).isFalse();
        assertThat(ok(200, "GET", "/manufacturing/reports/genealogy/forward/" + flour, null).get(0).get("orderId")
                .asString()).isEqualTo(id);
        assertThat(ok(200, "GET", "/manufacturing/reports/kpis?from=2020-01-01", null).get("orders").asInt())
                .isPositive();
        assertThat(ok(200, "GET", "/manufacturing/reports/trends", null)).isNotEmpty();
        assertThat(ok(200, "GET", "/manufacturing/reports/yield-by-bom", null)).isNotEmpty();
        assertThat(ok(200, "GET", "/manufacturing/reports/ingredient-usage", null)).isNotEmpty();
        assertThat(ok(200, "GET", "/manufacturing/reports/wip-reconciliation", null).has("drift")).isTrue();
        JsonNode shortage = ok(200, "GET", "/manufacturing/reports/shortage/" + cake + "?qty=40&locationId=" + bakery,
                null);
        assertThat(shortage.get("lines").get(0).get("required").asString()).isEqualTo("8");
        assertThat(shortage.get("lines").get(0).get("shortage").asString()).isEqualTo("2");
        assertThat(shortage.get("hasShortage").asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/manufacturing/reports/forecast?weeks=2", null).get("weeks").asInt()).isEqualTo(2);

        // Finished good → POS menu item (basePrice ×100).
        JsonNode menu = ok(201, "POST", "/manufacturing/production-orders/finished-goods/" + cake + "/menu-item", null);
        assertThat(menu.get("basePrice").asString()).isEqualTo("1500");
        assertThat(menu.get("ingredients").get(0).get("productId").asString()).isEqualTo(cake);
    }

    @Test
    @DisplayName("cancel after start returns material and clears WIP; under-yield raises unit cost")
    void cancelAndUnderYield() {
        String flour = product("FLOUR3");
        String cake = product("CAKE3");
        stockIn(bakery, flour, 10, 5000);
        BigDecimal wipBefore = balance(wipAccount);
        String bomId = bom(cake, flour, 10, 3, Map.of());
        String id = started(bomId).get("id").asString();
        JsonNode cancelled = ok(201, "POST", "/manufacturing/production-orders/" + id + "/cancel",
                Map.of("reason", "burnt oven"));
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        assertThat(cancelled.get("cancelReason").asString()).isEqualTo("burnt oven");
        assertThat(onHand(flour, bakery)).isEqualByComparingTo("10");
        assertThat(balance(wipAccount)).isEqualByComparingTo(wipBefore);

        String cake4 = product("CAKE4");
        String bom4 = bom(cake4, flour, 20, 4, Map.of());
        String id4 = started(bom4).get("id").asString();
        JsonNode done = ok(201, "POST", "/manufacturing/production-orders/" + id4 + "/complete",
                Map.of("qtyProduced", 18, "scrapQty", 2));
        assertThat(new BigDecimal(done.get("outputUnitCost").asString()).doubleValue())
                .isCloseTo(20000.0 / 18, org.assertj.core.data.Offset.offset(0.01));
        assertThat(done.get("scrapQty").asString()).isEqualTo("2");
        assertThat(balance(wipAccount)).isEqualByComparingTo(wipBefore);
    }

    @Test
    @DisplayName("loss write-off: with the pair on, whoever ran the order cannot write off its loss, someone else can")
    void lossWriteOffSeparation() {
        String flour = product("FLOUR-SOD");
        String cake = product("CAKE-SOD");
        stockIn(bakery, flour, 10, 5000);
        String id = started(bom(cake, flour, 20, 4, Map.of())).get("id").asString();
        String rule = "/approval-controls/sod-rules/" + SegregationOfDutiesService.PRODUCTION_LOSS_WRITE_OFF;
        ok(200, "PUT", rule, Map.of("isActive", true));
        try {
            assertThatThrownBy(() -> TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId),
                    () -> production.completeOrder(id, CompleteOrder.of(BigDecimal.ZERO))))
                    .isInstanceOf(ApiException.class).hasMessageContaining("Separation of duties");
            String other = createUser(demoOrg, "writeoff-" + UUID.randomUUID() + "@demo.test", "Other@12345", null);
            Map<String, Object> done = TenantContext.run(TenantContext.TenantStore.user(demoOrg, other),
                    () -> production.completeOrder(id, CompleteOrder.of(BigDecimal.ZERO)));
            assertThat(done.get("status")).isEqualTo("completed");
        } finally {
            ok(200, "PUT", rule, Map.of("isActive", false));
        }
    }

    @Test
    @DisplayName("zero-yield batch (service API) writes WIP off to scrap expense")
    void zeroYield() {
        String flour = product("FLOUR5");
        String cake = product("CAKE5");
        stockIn(bakery, flour, 10, 5000);
        BigDecimal wipBefore = balance(wipAccount);
        BigDecimal scrapBefore = balance(scrapAccount);
        String id = started(bom(cake, flour, 20, 4, Map.of())).get("id").asString();
        Map<String, Object> done = TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId),
                () -> production.completeOrder(id, CompleteOrder.of(BigDecimal.ZERO)));
        assertThat(done.get("status")).isEqualTo("completed");
        assertThat(balance(wipAccount)).isEqualByComparingTo(wipBefore);
        assertThat(balance(scrapAccount)).isEqualByComparingTo(scrapBefore.add(BigDecimal.valueOf(20000)));
        assertThat(onHand(cake, warehouse)).isEqualByComparingTo("0");
        Res rev = as("POST", "/manufacturing/production-orders/" + id + "/reverse", Map.of());
        assertThat(rev.body().get("message").asString())
                .isEqualTo("A scrapped (zero-yield) order has no output to reverse.");
    }

    @Test
    @DisplayName("QC hold in quarantine then pass/fail; reverse a completed order; overhead absorbed")
    void qcReverseOverhead() {
        String flour = product("FLOUR6");
        String cake = product("CAKE6");
        stockIn(bakery, flour, 30, 5000);
        String id = started(bom(cake, flour, 20, 4, Map.of("qcRequired", true))).get("id").asString();
        JsonNode held = ok(201, "POST", "/manufacturing/production-orders/" + id + "/complete",
                Map.of("qtyProduced", 20));
        assertThat(held.get("status").asString()).isEqualTo("qc_hold");
        String quarantine = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ?"
                + " AND code = 'QC-QUARANTINE'").param(demoOrg).query(String.class).single();
        assertThat(onHand(cake, quarantine)).isEqualByComparingTo("20");
        assertThat(onHand(cake, warehouse)).isEqualByComparingTo("0");
        JsonNode qc = ok(201, "POST", "/manufacturing/production-orders/" + id + "/qc",
                Map.of("passedQty", 18, "failedQty", 2, "wasteCategory", "qc_rejection"));
        assertThat(qc.get("status").asString()).isEqualTo("completed");
        assertThat(onHand(cake, warehouse)).isEqualByComparingTo("18");
        assertThat(onHand(cake, quarantine)).isEqualByComparingTo("0");
        Res notQc = as("POST", "/manufacturing/production-orders/" + id + "/qc", Map.of("passedQty", 1));
        assertThat(notQc.body().get("message").asString())
                .isEqualTo("Order " + held.get("orderCode").asString() + " is not awaiting QC (status completed).");

        // Reverse: finished goods out, materials back, WIP flat.
        String cake7 = product("CAKE7");
        BigDecimal wipBefore = balance(wipAccount);
        BigDecimal flourBefore = onHand(flour, bakery);
        String id7 = started(bom(cake7, flour, 20, 4, Map.of())).get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + id7 + "/complete", Map.of("qtyProduced", 20));
        JsonNode reversed = ok(201, "POST", "/manufacturing/production-orders/" + id7 + "/reverse",
                Map.of("reason", "wrong recipe"));
        assertThat(reversed.get("status").asString()).isEqualTo("cancelled");
        assertThat(reversed.get("reversedAt").isNull()).isFalse();
        assertThat(onHand(cake7, warehouse)).isEqualByComparingTo("0");
        assertThat(onHand(flour, bakery)).isEqualByComparingTo(flourBefore);
        assertThat(balance(wipAccount)).isEqualByComparingTo(wipBefore);

        // Overhead: (20,000 + 4,000) / 20 = 1,200; absorbed credited, WIP flat.
        String cake8 = product("CAKE8");
        BigDecimal absorbedBefore = balance(overheadAccount);
        String id8 = started(bom(cake8, flour, 20, 4, Map.of("overheadCost", 4000))).get("id").asString();
        JsonNode done = ok(201, "POST", "/manufacturing/production-orders/" + id8 + "/complete",
                Map.of("qtyProduced", 20));
        assertThat(done.get("outputUnitCost").asString()).isEqualTo("1200");
        assertThat(balance(overheadAccount)).isEqualByComparingTo(absorbedBefore.subtract(BigDecimal.valueOf(4000)));
        assertThat(balance(wipAccount)).isEqualByComparingTo(wipBefore);
    }

    @Test
    @DisplayName("BOM versioning, draft-only edits, cycle guard, resolution errors")
    void bomRules() {
        String a = product("A");
        String b = product("B");
        String v1 = bom(a, b, 10, 1, Map.of());
        Res edit = as("PATCH", "/manufacturing/boms/" + v1, Map.of("name", "x"));
        assertThat(edit.status()).isEqualTo(400);
        assertThat(edit.body().get("message").asString()).endsWith("is active and cannot be edited. Create a new version instead.");
        JsonNode v2 = ok(201, "POST", "/manufacturing/boms", Map.of("name", "v2", "outputProductId", a,
                "outputQuantity", 5, "lines", List.of(Map.of("componentProductId", b, "quantity", 2))));
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        JsonNode patched = ok(200, "PATCH", "/manufacturing/boms/" + v2.get("id").asString(),
                Map.of("notes", "n", "lines", List.of(Map.of("componentProductId", b, "quantity", 3))));
        assertThat(patched.get("lines").get(0).get("quantity").asString()).isEqualTo("3");
        ok(201, "POST", "/manufacturing/boms/" + v2.get("id").asString() + "/activate", null);
        assertThat(ok(200, "GET", "/manufacturing/boms/" + v1, null).get("status").asString()).isEqualTo("archived");
        Res reactivate = as("POST", "/manufacturing/boms/" + v1 + "/activate", null);
        assertThat(reactivate.body().get("message").asString()).isEqualTo("Cannot re-activate an archived BOM version.");
        assertThat(ok(200, "GET", "/manufacturing/boms?outputProductId=" + a, null)).hasSize(2);

        Res cycle = as("POST", "/manufacturing/boms", Map.of("name", "cyc", "outputProductId", b, "outputQuantity", 1,
                "lines", List.of(Map.of("componentProductId", a, "quantity", 1))));
        assertThat(cycle.body().get("message").asString())
                .isEqualTo("Circular BOM: a component is (transitively) the output product.");
        Res none = as("POST", "/manufacturing/production-orders", Map.of("outputProductId", b, "runs", 1));
        assertThat(none.body().get("message").asString())
                .isEqualTo("No active BOM for this product. Create and activate one first.");
        Res neither = as("POST", "/manufacturing/production-orders", Map.of());
        assertThat(neither.body().get("message").asString()).isEqualTo("Provide a bomId or an outputProductId.");
        Res qty = as("POST", "/manufacturing/production-orders", Map.of("outputProductId", a));
        assertThat(qty.body().get("message").asString()).isEqualTo("Provide runs or plannedQty.");
        Res loc = as("POST", "/manufacturing/production-orders", Map.of("outputProductId", a, "runs", 1));
        assertThat(loc.body().get("message").asString())
                .isEqualTo("No production location: pass locationId or set the BOM default.");
        Res adhoc = as("POST", "/manufacturing/production-orders", Map.of("outputProductId", a,
                "materials", List.of(Map.of("productId", b, "quantity", 1))));
        assertThat(adhoc.body().get("message").asString())
                .isEqualTo("plannedQty is required for an ad-hoc production order.");

        ok(204, "DELETE", "/manufacturing/boms/" + v1, null);
        Res gone = as("GET", "/manufacturing/boms/" + v1, null);
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.body().get("message").asString()).isEqualTo("BOM not found");

        Res invalid = as("POST", "/manufacturing/boms", Map.of("name", "", "outputQuantity", -1, "lines", List.of()));
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(validationMessages(invalid)).contains("name should not be empty",
                "outputQuantity must be a positive number", "lines must contain at least 1 elements");
        Res unknown = as("POST", "/manufacturing/boms", Map.of("name", "x", "outputProductId", a, "outputQuantity", 1,
                "overheadCostX", 1, "lines", List.of(Map.of("componentProductId", b, "quantity", 1))));
        assertThat(unknown.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("requests → plan (consolidated) → confirm generates draft orders; MRP raises purchase request")
    void requestsPlansMrp() {
        String flour = product("FLOUR9");
        String bread = product("BREAD");
        String bomId = bom(bread, flour, 10, 5, Map.of("defaultLocationId", bakery, "defaultOutputLocationId",
                warehouse));
        JsonNode req = ok(201, "POST", "/manufacturing/requests", Map.of("lines", List.of(
                Map.of("productId", bread, "quantity", 20, "bomId", bomId))));
        assertThat(req.get("requestNumber").asString()).startsWith("PRQ-");
        String rid = req.get("id").asString();
        Res approveDraft = as("POST", "/manufacturing/requests/" + rid + "/approve", null);
        assertThat(approveDraft.body().get("message").asString()).isEqualTo("Cannot move a draft request to approved.");
        ok(201, "POST", "/manufacturing/requests/" + rid + "/submit", null);
        assertThat(ok(201, "POST", "/manufacturing/requests/" + rid + "/approve", null).get("approvedById").asString())
                .isEqualTo(adminId);
        JsonNode r2 = ok(201, "POST", "/manufacturing/requests", Map.of("lines", List.of(
                Map.of("productId", bread, "quantity", 30))));
        ok(201, "POST", "/manufacturing/requests/" + r2.get("id").asString() + "/submit", null);

        JsonNode plan = ok(201, "POST", "/manufacturing/plans", Map.of("fromOpenRequests", true,
                "locationId", bakery));
        assertThat(plan.get("planNumber").asString()).startsWith("PLN-");
        JsonNode line = null;
        for (JsonNode l : plan.get("lines")) {
            if (l.get("productId").asString().equals(bread)) {
                line = l;
            }
        }
        assertThat(line).isNotNull();
        assertThat(line.get("targetQty").asString()).isEqualTo("50");
        String pid = plan.get("id").asString();
        JsonNode added = ok(201, "POST", "/manufacturing/plans/" + pid + "/lines", Map.of("lines", List.of(
                Map.of("productId", bread, "targetQty", 10))));
        assertThat(added.get("lines").size()).isEqualTo(plan.get("lines").size() + 1);
        JsonNode confirmed = ok(201, "POST", "/manufacturing/plans/" + pid + "/confirm", null);
        assertThat(confirmed.get("status").asString()).isEqualTo("confirmed");
        for (JsonNode l : confirmed.get("lines")) {
            assertThat(l.get("generatedOrderId").isNull()).isFalse();
        }
        assertThat(ok(200, "GET", "/manufacturing/requests/" + rid, null).get("status").asString()).isEqualTo("planned");
        Res edit = as("POST", "/manufacturing/plans/" + pid + "/lines", Map.of("lines", List.of(
                Map.of("productId", bread, "targetQty", 1))));
        assertThat(edit.body().get("message").asString()).isEqualTo("Only a draft plan can be edited.");
        assertThat(ok(201, "POST", "/manufacturing/plans/" + pid + "/cancel", null).get("status").asString())
                .isEqualTo("cancelled");
        assertThat(as("POST", "/manufacturing/plans/" + pid + "/confirm", null).body().get("message").asString())
                .isEqualTo("Cannot confirm a cancelled plan.");

        // MRP: draft orders need flour that is not on hand → a draft purchase request for the shortfall.
        JsonNode mrp = ok(201, "POST", "/manufacturing/mrp/run", null);
        assertThat(mrp.get("raised").asBoolean()).isTrue();
        assertThat(mrp.get("purchaseRequest").get("requestNumber").asString()).startsWith("MRPPR-");
        boolean hasFlour = false;
        for (JsonNode l : mrp.get("purchaseRequest").get("lines")) {
            hasFlour |= l.get("productId").asString().equals(flour);
        }
        assertThat(hasFlour).isTrue();

        // Min-stock scan: bread below its minimum with an active BOM.
        jdbc.sql("UPDATE \"Product\" SET \"minQuantity\" = 1000 WHERE id = ?").param(bread).update();
        JsonNode scan = ok(201, "POST", "/manufacturing/mrp/scan-min-stock", null);
        assertThat(scan.get("raised").asBoolean()).isTrue();
        assertThat(scan.get("request").get("sourceType").asString()).isEqualTo("min_stock");
        Res doc = as("POST", "/manufacturing/mrp/from-document/" + UUID.randomUUID(), null);
        assertThat(doc.status()).isEqualTo(404);
        assertThat(doc.body().get("message").asString()).isEqualTo("Document not found");

        Res cancelPlanned = as("POST", "/manufacturing/requests/" + r2.get("id").asString() + "/cancel", null);
        assertThat(cancelPlanned.body().get("message").asString())
                .isEqualTo("Cannot move a planned request to cancelled.");
        JsonNode r3 = ok(201, "POST", "/manufacturing/requests", Map.of("lines", List.of(
                Map.of("productId", bread, "quantity", 1))));
        JsonNode rejected = ok(201, "POST", "/manufacturing/requests/" + r3.get("id").asString() + "/reject",
                Map.of("reason", "no"));
        assertThat(rejected.get("rejectedReason").asString()).isEqualTo("no");
        assertThat(ok(200, "GET", "/manufacturing/requests?status=rejected", null).toString())
                .contains(r3.get("id").asString());
        assertThat(ok(200, "GET", "/manufacturing/plans?status=cancelled", null).toString()).contains(pid);
    }

    @Test
    @DisplayName("work centres, resources, routings and work orders roll labour into overhead")
    void workOrders() {
        JsonNode wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "OV-" + suffix(), "name", "Oven"));
        String wcId = wc.get("id").asString();
        assertThat(ok(200, "PATCH", "/manufacturing/work-centers/" + wcId, Map.of("description", "hot"))
                .get("description").asString()).isEqualTo("hot");
        JsonNode res = ok(201, "POST", "/manufacturing/resources", Map.of("name", "Baker", "kind", "labour",
                "workCenterId", wcId, "costPerHour", 60, "capacityMinsPerDay", 480));
        assertThat(ok(200, "PATCH", "/manufacturing/resources/" + res.get("id").asString(), Map.of("costPerHour", 120))
                .get("costPerHour").asString()).isEqualTo("120");
        assertThat(ok(200, "GET", "/manufacturing/resources?kind=labour", null)).isNotEmpty();
        Res badKind = as("POST", "/manufacturing/resources", Map.of("name", "x", "kind", "robot"));
        assertThat(badKind.status()).isEqualTo(400);

        String flour = product("FLOUR10");
        String bun = product("BUN");
        stockIn(bakery, flour, 10, 100);
        String bomId = bom(bun, flour, 10, 1, Map.of());
        JsonNode routing = ok(201, "POST", "/manufacturing/routings", Map.of("name", "Bun line", "bomId", bomId,
                "operations", List.of(Map.of("sequence", 2, "name", "Bake", "workCenterId", wcId, "durationMins", 30,
                        "setupMins", 5), Map.of("sequence", 1, "name", "Mix"))));
        assertThat(routing.get("operations").get(0).get("name").asString()).isEqualTo("Mix");
        assertThat(ok(200, "GET", "/manufacturing/routings/" + routing.get("id").asString(), null).get("name")
                .asString()).isEqualTo("Bun line");

        String orderId = started(bomId).get("id").asString();
        JsonNode wos = ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/work-orders/generate", null);
        assertThat(wos).hasSize(2);
        assertThat(wos.get(1).get("plannedDurationMins").asInt()).isEqualTo(35);
        assertThat(ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/work-orders/generate", null))
                .hasSize(2);
        String woId = wos.get(1).get("id").asString();
        ok(200, "PATCH", "/manufacturing/work-orders/" + woId + "/assign", Map.of("resourceId", res.get("id").asString()));
        assertThat(ok(201, "POST", "/manufacturing/work-orders/" + woId + "/start", null).get("status").asString())
                .isEqualTo("in_progress");
        assertThat(ok(201, "POST", "/manufacturing/work-orders/" + woId + "/pause", null).get("status").asString())
                .isEqualTo("paused");
        assertThat(as("POST", "/manufacturing/work-orders/" + woId + "/pause", null).body().get("message").asString())
                .isEqualTo("Only an in-progress work order can pause.");
        JsonNode cap = ok(200, "GET", "/manufacturing/work-centers/capacity", null);
        assertThat(cap.toString()).contains(wcId);
        assertThat(ok(201, "POST", "/manufacturing/work-orders/" + woId + "/complete", Map.of("labourMins", 30))
                .get("status").asString()).isEqualTo("completed");
        assertThat(as("POST", "/manufacturing/work-orders/" + woId + "/start", null).body().get("message").asString())
                .isEqualTo("Cannot start a completed work order.");
        // 30 min × 120/h = 60 rolled into overhead; complete absorbs it.
        assertThat(ok(200, "GET", "/manufacturing/production-orders/" + orderId, null).get("overheadCost").asString())
                .isEqualTo("60");
        JsonNode done = ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/complete",
                Map.of("qtyProduced", 10));
        assertThat(done.get("outputUnitCost").asString()).isEqualTo("16");

        ok(204, "DELETE", "/manufacturing/routings/" + routing.get("id").asString(), null);
        ok(204, "DELETE", "/manufacturing/resources/" + res.get("id").asString(), null);
        ok(204, "DELETE", "/manufacturing/work-centers/" + wcId, null);
        assertThat(as("GET", "/manufacturing/routings/" + routing.get("id").asString(), null).status()).isEqualTo(404);
        assertThat(as("POST", "/manufacturing/work-orders/" + UUID.randomUUID() + "/start", null).body().get("message")
                .asString()).isEqualTo("Work order not found");
        Res noRouting = as("POST", "/manufacturing/production-orders/" + UUID.randomUUID() + "/work-orders/generate",
                null);
        assertThat(noRouting.body().get("message").asString()).isEqualTo("Production order not found");
    }

    @Test
    @DisplayName("tenant isolation and 404s")
    void tenancy() {
        String a = product("ISO");
        String b = product("ISOB");
        String bomId = bom(a, b, 1, 1, Map.of());
        Res foreign = call("GET", "/manufacturing/boms/" + bomId, null, otherToken);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(call("GET", "/manufacturing/boms", null, otherToken).body().toString()).doesNotContain(bomId);
        assertThat(as("GET", "/manufacturing/production-orders/" + UUID.randomUUID(), null).body().get("message")
                .asString()).isEqualTo("Production order not found");
        assertThat(as("GET", "/manufacturing/plans/" + UUID.randomUUID(), null).body().get("message").asString())
                .isEqualTo("Plan not found");
        assertThat(as("GET", "/manufacturing/requests/" + UUID.randomUUID(), null).body().get("message").asString())
                .isEqualTo("Request not found");
        assertThat(as("POST", "/manufacturing/production-orders/finished-goods/" + UUID.randomUUID() + "/menu-item",
                null).body().get("message").asString()).isEqualTo("Product not found");
    }

    @Test
    @DisplayName("authorization matrix: 401 without token, 403 without permission")
    void authorization() {
        // 131 + 26 precast (ADR-S014, 2.4) + 41 shop-floor (ADR-S015, 2.7) + 36 crushing routes (ADR-S016, 2.3)
        // + 25 planning (ADR-S017, 2.5, incl. the forecast route) + 9 costing routes (ADR-S018, 2.8).
        assertThat(assertAuthorizationMatrix("com.inventoryerp.manufacturing")).isEqualTo(271);
    }
}
