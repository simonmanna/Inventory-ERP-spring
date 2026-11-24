package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Integrity fixes 2026-11-24 (manufacturing production-readiness review, core step, MC-1..MC-13). Each test failed
 * before its fix.
 */
class ManufacturingCoreIntegrityIT extends MfgTestSupport {

    private static final String PO = "/manufacturing/production-orders/";
    private final String today = LocalDate.now().toString();

    private Map<String, String> keyed() {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    private JsonNode keyedOk(String path, Object body) {
        Res r = call("POST", path, body, keyed());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    @Test
    @DisplayName("MC-1: zero yield absorbs the overhead and writes off materials + overhead; it can be reversed")
    void zeroYieldOverhead() {
        String flour = product("FLOUR-ZY");
        String loaf = product("LOAF-ZY");
        stockIn(bakery, flour, 10, 100);
        String bomId = bom(loaf, flour, 10, 2, Map.of("overheadCost", 50));
        BigDecimal wip0 = balance(wipAccount);
        BigDecimal scrap0 = balance(scrapAccount);
        BigDecimal oh0 = balance(overheadAccount);
        String id = started(bomId).get("id").asString();
        // The route requires a positive quantity (as Nest's DTO); a zero yield reaches the engine from code only.
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .execute(st -> production.completeOrder(id,
                        com.inventoryerp.manufacturing.application.MfgCommands.CompleteOrder.of(BigDecimal.ZERO))));
        JsonNode done = ok(200, "GET", PO + id, null);
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(new BigDecimal(done.get("totalCost").asString())).isEqualByComparingTo("250");
        assertThat(balance(wipAccount)).isEqualByComparingTo(wip0);
        assertThat(balance(scrapAccount).subtract(scrap0)).isEqualByComparingTo("250");
        assertThat(balance(overheadAccount).subtract(oh0)).isEqualByComparingTo("-50");

        JsonNode rev = ok(201, "POST", PO + id + "/reverse", Map.of("reason", "counted wrong"));
        assertThat(rev.get("status").asString()).isEqualTo("cancelled");
        assertThat(onHand(flour, bakery)).isEqualByComparingTo("10");
        assertThat(balance(wipAccount)).isEqualByComparingTo(wip0);
        assertThat(balance(scrapAccount)).isEqualByComparingTo(scrap0);
        assertThat(balance(overheadAccount)).isEqualByComparingTo(oh0);
    }

    @Test
    @DisplayName("MC-2: roll-up deducts the by-product share: materials 1000, by-product 10 % → standard 90 for 10"
            + " units, no variance at completion")
    void standardRollupDeductsByproduct() {
        String flour = product("FLOUR-SB");
        String bread = product("BREAD-SB");
        String crumbs = product("CRUMB-SB");
        stockIn(bakery, flour, 10, 100);
        String bomId = bom(bread, flour, 10, 10, Map.of());
        ok(200, "PUT", "/manufacturing/boms/" + bomId + "/stage-setup", Map.of("coProducts", List.of(Map.of(
                "productId", crumbs, "role", "by_product", "quantity", 1, "valuation", "allocated", "fixedPct", 10)),
                "allocationRule", Map.of("method", "fixed_pct")));
        JsonNode cost = ok(200, "GET", "/manufacturing/boms/" + bomId + "/cost", null);
        assertThat(new BigDecimal(cost.get("totalCost").asString())).isEqualByComparingTo("1000");
        assertThat(new BigDecimal(cost.get("unitCost").asString())).isEqualByComparingTo("90");

        JsonNode proposed = ok(201, "POST", "/manufacturing/boms/" + bomId + "/product-standard",
                Map.of("effectiveFrom", today, "convertMethod", true));
        assertThat(new BigDecimal(proposed.get("totalCost").asString())).isEqualByComparingTo("90");
        String pid = proposed.get("id").asString();
        ok(200, "POST", "/inventory/standard-costs/" + pid + "/approve", null);
        keyedOk("/inventory/standard-costs/" + pid + "/activate", null);

        String id = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1,
                "locationId", bakery, "outputLocationId", warehouse, "outputs", List.of(Map.of("productId", crumbs,
                        "kind", "byproduct", "qtyExpected", 1, "costAllocationPct", 10)))).get("id").asString();
        ok(201, "POST", PO + id + "/confirm", null);
        ok(201, "POST", PO + id + "/start", Map.of());
        JsonNode done = ok(201, "POST", PO + id + "/complete", Map.of("qtyProduced", 10));
        assertThat(new BigDecimal(done.get("outputUnitCost").asString())).isEqualByComparingTo("90");
        assertThat(done.get("varianceJournalEntryId") == null || done.get("varianceJournalEntryId").isNull()).isTrue();
    }

    @Test
    @DisplayName("MC-3: overhead absorbed at a work-centre rate credits the rate's account, also when reversed")
    void ratedOverheadCreditsRateAccount() {
        String applied = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1100'")
                .param(demoOrg).query(String.class).single();
        String wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "RT-" + suffix(), "name", "Press"))
                .get("id").asString();
        String input = product("RTIN");
        String output = product("RTOUT");
        stockIn(bakery, input, 10, 10);
        String bomId = bom(output, input, 1, 1, Map.of());
        ok(201, "POST", "/manufacturing/routings", Map.of("name", "Rated line " + suffix(), "bomId", bomId,
                "operations", List.of(Map.of("sequence", 1, "name", "Press", "workCenterId", wc, "durationMins", 60))));
        ok(200, "PUT", "/manufacturing/costing/rates/" + wc, Map.of("labourRatePerHour", 10, "machineRatePerHour", 20,
                "overheadRatePerHour", 5, "creditAccountId", applied));
        String orderId = started(bomId).get("id").asString();
        String wo = ok(201, "POST", PO + orderId + "/work-orders/generate", null).get(0).get("id").asString();
        ok(201, "POST", "/manufacturing/work-orders/" + wo + "/start", null);
        ok(201, "POST", "/manufacturing/work-orders/" + wo + "/complete", Map.of("labourMins", 120));
        Res absorbed = call("POST", "/manufacturing/costing/orders/" + orderId + "/absorb", null, keyed());
        assertThat(absorbed.status()).as(absorbed.raw().body()).isEqualTo(200);
        BigDecimal total = new BigDecimal(absorbed.body().get("total").asString());
        assertThat(total.signum()).isPositive();
        BigDecimal applied0 = balance(applied);
        BigDecimal oh0 = balance(overheadAccount);
        BigDecimal wip0 = balance(wipAccount);
        ok(201, "POST", PO + orderId + "/complete", Map.of("qtyProduced", 1));
        assertThat(balance(applied).subtract(applied0)).isEqualByComparingTo(total.negate());
        assertThat(balance(overheadAccount)).isEqualByComparingTo(oh0);

        ok(201, "POST", PO + orderId + "/reverse", Map.of("reason", "test"));
        assertThat(balance(applied)).isEqualByComparingTo(applied0);
        assertThat(balance(overheadAccount)).isEqualByComparingTo(oh0);
        // The 10 of material in WIP since the start went back to stock with the reversal; nothing else is left.
        assertThat(balance(wipAccount).subtract(wip0)).isEqualByComparingTo("-10");
    }

    @Test
    @DisplayName("MC-5: process-costing material issue and cost need an Idempotency-Key")
    void processCostingMoneyRoutesNeedKey() {
        String fake = UUID.randomUUID().toString();
        for (String path : List.of("/materials", "/costs")) {
            Res r = as("POST", "/manufacturing/process-costing/periods/" + fake + path, Map.of("element", "labour",
                    "amount", 1));
            assertThat(r.status()).as(path + " " + r.raw().body()).isEqualTo(400);
            assertThat(r.raw().body()).containsIgnoringCase("Idempotency-Key");
        }
    }

    @Test
    @DisplayName("MC-6: a reversed order's variances leave the variance report")
    void reversedOrderLeavesVarianceReport() {
        String flour = product("FLOUR-VR");
        String bun = product("BUN-VR");
        stockIn(bakery, flour, 20, 100);
        String bomId = bom(bun, flour, 10, 2, Map.of());
        ok(201, "POST", "/manufacturing/boms/" + bomId + "/standard-cost", null);
        String id = started(bomId).get("id").asString();
        ok(201, "POST", PO + id + "/complete", Map.of("qtyProduced", 8));
        assertThat(ok(200, "GET", "/manufacturing/reports/variances?orderId=" + id, null).get("rows").size())
                .isPositive();
        ok(201, "POST", PO + id + "/reverse", Map.of("reason", "test"));
        assertThat(ok(200, "GET", "/manufacturing/reports/variances?orderId=" + id, null).get("rows").size())
                .isZero();
    }

    @Test
    @DisplayName("MC-7: QC decisions are bounded by what the order still holds in quarantine")
    void qcBoundedByQuarantine() {
        String flour = product("FLOUR-QB");
        String cake = product("CAKE-QB");
        stockIn(bakery, flour, 20, 100);
        String bomId = bom(cake, flour, 20, 4, Map.of("qcRequired", true));
        String id = started(bomId).get("id").asString();
        ok(201, "POST", PO + id + "/complete", Map.of("qtyProduced", 20));
        Res tooMany = as("POST", PO + id + "/qc", Map.of("passedQty", 15, "reworkQty", 10));
        assertThat(tooMany.status()).isEqualTo(400);
        String code = ok(200, "GET", PO + id, null).get("orderCode").asString();
        assertThat(tooMany.body().get("message").asString()).isEqualTo("QC quantities (passed + failed + rework = 25)"
                + " exceed the 20 of order " + code + " still in quarantine.");
        ok(201, "POST", PO + id + "/qc", Map.of("passedQty", 12, "reworkQty", 8));
        Res again = as("POST", PO + id + "/qc", Map.of("passedQty", 9));
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body().get("message").asString()).contains("exceed the 8 of order");
        assertThat(ok(201, "POST", PO + id + "/qc", Map.of("passedQty", 8)).get("status").asString())
                .isEqualTo("completed");
    }

    @Test
    @DisplayName("MC-8: a cancel rolled back by its caller keeps the order's reservations")
    void cancelReleaseRollsBackWithCaller() {
        String flour = product("FLOUR-RS");
        String roll = product("ROLL-RS");
        stockIn(bakery, flour, 10, 100);
        String bomId = bom(roll, flour, 10, 2, Map.of());
        String id = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1,
                "locationId", bakery, "outputLocationId", warehouse)).get("id").asString();
        ok(201, "POST", PO + id + "/confirm", null);
        String active = "SELECT count(*) FROM \"StockReservation\" WHERE \"sourceType\" = 'production_order' AND"
                + " \"sourceId\" = ? AND status = 'active'";
        assertThat(jdbc.sql(active).param(id).query(Long.class).single()).isPositive();
        try {
            TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                    .execute(st -> {
                        production.cancelOrder(id, null);
                        throw new IllegalStateException("caller fails after the cancel");
                    }));
        } catch (IllegalStateException expected) {
            // the caller's transaction rolled back
        }
        assertThat(ok(200, "GET", PO + id, null).get("status").asString()).isEqualTo("confirmed");
        assertThat(jdbc.sql(active).param(id).query(Long.class).single()).isPositive();
    }

    @Test
    @DisplayName("MC-9: assign and production requests answer 404 for unknown references")
    void unknownReferences404() {
        String wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "AS-" + suffix(), "name", "Oven"))
                .get("id").asString();
        String flour = product("FLOUR-AS");
        String bun = product("BUN-AS");
        stockIn(bakery, flour, 10, 100);
        String bomId = bom(bun, flour, 10, 1, Map.of());
        ok(201, "POST", "/manufacturing/routings", Map.of("name", "As line " + suffix(), "bomId", bomId,
                "operations", List.of(Map.of("sequence", 1, "name", "Bake", "workCenterId", wc, "durationMins", 30))));
        String orderId = started(bomId).get("id").asString();
        String wo = ok(201, "POST", PO + orderId + "/work-orders/generate", null).get(0).get("id").asString();
        Res res = as("PATCH", "/manufacturing/work-orders/" + wo + "/assign", Map.of("resourceId", "nope"));
        assertThat(res.status()).isEqualTo(404);
        assertThat(res.body().get("message").asString()).isEqualTo("Resource not found");
        Res centre = as("PATCH", "/manufacturing/work-orders/" + wo + "/assign", Map.of("workCenterId", "nope"));
        assertThat(centre.status()).isEqualTo(404);
        assertThat(centre.body().get("message").asString()).isEqualTo("Work centre not found");

        Res req = as("POST", "/manufacturing/requests", Map.of("lines", List.of(Map.of("productId", "ghost",
                "quantity", 1))));
        assertThat(req.status()).isEqualTo(404);
        assertThat(req.body().get("message").asString()).isEqualTo("Product ghost not found");
    }

    @Test
    @DisplayName("MC-10: two confirms of one plan at once create one order per line")
    void planConfirmOnce() throws Exception {
        String flour = product("FLOUR-PL");
        String pie = product("PIE-PL");
        String bomId = bom(pie, flour, 10, 1, Map.of());
        String planId = ok(201, "POST", "/manufacturing/plans", Map.of("name", "Plan " + suffix(), "locationId",
                bakery)).get("id").asString();
        ok(201, "POST", "/manufacturing/plans/" + planId + "/lines", Map.of("lines", List.of(
                Map.of("productId", pie, "bomId", bomId, "targetQty", 10),
                Map.of("productId", pie, "bomId", bomId, "targetQty", 20))));
        List<Integer> statuses = race(2, () -> as("POST", "/manufacturing/plans/" + planId + "/confirm", null)
                .status());
        assertThat(statuses).containsOnly(201);
        assertThat(jdbc.sql("SELECT count(*) FROM \"ProductionOrder\" WHERE \"planId\" = ?").param(planId)
                .query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    @DisplayName("MC-11: two drafts referencing each other cannot both activate")
    void mutualDraftsCannotBothActivate() {
        String x = product("X-CY");
        String y = product("Y-CY");
        String a = ok(201, "POST", "/manufacturing/boms", Map.of("name", "A " + suffix(), "outputProductId", x,
                "outputQuantity", 1, "lines", List.of(Map.of("componentProductId", y, "quantity", 1))))
                .get("id").asString();
        String b = ok(201, "POST", "/manufacturing/boms", Map.of("name", "B " + suffix(), "outputProductId", y,
                "outputQuantity", 1, "lines", List.of(Map.of("componentProductId", x, "quantity", 1))))
                .get("id").asString();
        ok(201, "POST", "/manufacturing/boms/" + a + "/activate", null);
        Res second = as("POST", "/manufacturing/boms/" + b + "/activate", null);
        assertThat(second.status()).isEqualTo(400);
        assertThat(second.body().get("message").asString())
                .isEqualTo("Circular BOM: a component is (transitively) the output product.");
    }

    @Test
    @DisplayName("MC-12: PATCH resources / work centres refuses system columns and negative cost rates")
    void patchRefusesSystemColumns() {
        String wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "PT-" + suffix(), "name", "Mixer"))
                .get("id").asString();
        Res org = as("PATCH", "/manufacturing/work-centers/" + wc, Map.of("organizationId", otherOrg));
        assertThat(org.status()).isEqualTo(400);
        assertThat(org.body().get("message").toString()).contains("property organizationId should not exist");
        Res neg = as("PATCH", "/manufacturing/work-centers/" + wc, Map.of("costPerHour", -5));
        assertThat(neg.status()).isEqualTo(400);
        assertThat(neg.body().get("message").toString()).contains("costPerHour must not be less than 0");
        String res = ok(201, "POST", "/manufacturing/resources", Map.of("name", "Baker", "kind", "labour",
                "workCenterId", wc, "costPerHour", 10)).get("id").asString();
        Res id = as("PATCH", "/manufacturing/resources/" + res, Map.of("id", "hijack"));
        assertThat(id.status()).isEqualTo(400);
        Res negRes = as("PATCH", "/manufacturing/resources/" + res, Map.of("costPerHour", -1));
        assertThat(negRes.status()).isEqualTo(400);
        assertThat(negRes.body().get("message").toString()).contains("costPerHour must not be less than 0");
        assertThat(ok(200, "PATCH", "/manufacturing/resources/" + res, Map.of("costPerHour", 12)).get("costPerHour")
                .asString()).isEqualTo("12");
    }

    private <T> List<T> race(int n, java.util.concurrent.Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return call.call();
            }));
        }
        go.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }
}
