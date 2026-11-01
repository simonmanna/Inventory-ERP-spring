package com.inventoryerp.costing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.costing.web.CostCloseController;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Cost Management CM-10 (ADR-S041): the ordered close gates block in turn (allocation, revenue recognition,
 * profitability); the lock refuses costing postings until reopened with permission and a reason; the cost adjustment
 * run cascades late WIP cost of a completed order to its output layers by lineage (on hand → adjunct, sold → cost of
 * sales), once, and reverses.
 */
class CostCloseIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    AccountDeterminationService determination;

    String today = LocalDate.now(ZoneOffset.UTC).toString();

    Map<String, String> headers(String token, boolean idempotent) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        if (idempotent) {
            h.put("Idempotency-Key", UUID.randomUUID().toString());
        }
        return h;
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    @Test
    @DisplayName("close: gates block in order; lock refuses costing postings; reopen needs permission and a reason")
    void closeGatesAndLock() {
        String code = "cc-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Close Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
                "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        String org = boot.body().get("organization").get("id").asString();
        String token = login(code, "owner@" + code + ".test", "Owner@12345");
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\") VALUES"
                + " (?, ?, 'Closer', false, ?, now())").params(role, org, Permissions.ALL.stream().filter(p ->
                !p.equals(Permissions.COSTING_CLOSE__REOPEN)).toArray(String[]::new)).update();
        createUser(org, "closer@" + code + ".test", "Closer@12345", role);
        String closer = login(code, "closer@" + code + ".test", "Closer@12345");
        String gatesPath = "/costing/close/gates?from=" + today + "&to=" + today;

        // gate 3: an active allocation cycle without a run
        String cycle = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"AllocationCycle\" (id, \"organizationId\", code, name) VALUES (?, ?, 'C1', 'Cycle')")
                .params(cycle, org).update();
        JsonNode g = call("GET", gatesPath, null, token).body();
        assertThat(g.get("blocking").get("key").asString()).isEqualTo("allocation");
        Res refused = call("POST", "/costing/close/locks", Map.of("from", today, "to", today), token);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().get("message").asString()).startsWith("Gate 3 (Allocation cycles posted)");
        jdbc.sql("UPDATE \"AllocationCycle\" SET \"isActive\" = false WHERE id = ?").param(cycle).update();

        // gate 6: a contract with cost and no revenue run
        String customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\") VALUES"
                + " (?, ?, 'CU1', 'Customer', true, now())").params(customer, org).update();
        String pid = call("POST", "/projects", Map.of("code", "P1", "name", "Job", "customerId", customer), token)
                .body().get("id").asString();
        String w = call("POST", "/projects/" + pid + "/wbs", Map.of("code", "1", "name", "All"), token).body()
                .get("id").asString();
        call("POST", "/projects/" + pid + "/status", Map.of("status", "active"), token);
        Map<String, Object> capture = Map.of("wbsId", w, "hours", 2, "ratePerHour", 50);
        assertThat(call("POST", "/projects/" + pid + "/costs/equipment", capture, headers(token, true)).status())
                .isIn(200, 201);
        assertThat(call("GET", gatesPath, null, token).body().get("blocking").get("key").asString())
                .isEqualTo("revenue");
        assertThat(call("POST", "/projects/" + pid + "/revenue-runs", Map.of("periodEnd", today), headers(token,
                true)).status()).isIn(200, 201);

        // gate 8: profitability build of exactly this period
        assertThat(call("GET", gatesPath, null, token).body().get("blocking").get("key").asString())
                .isEqualTo("profitability");
        assertThat(call("POST", "/costing/profitability/runs", Map.of("from", today, "to", today), token).status())
                .isEqualTo(201);
        JsonNode ready = call("GET", gatesPath, null, token).body();
        assertThat(ready.get("ready").asBoolean()).as(ready.toString()).isTrue();
        assertThat(ready.get("gates")).hasSize(9);

        Res locked = call("POST", "/costing/close/locks", Map.of("from", today, "to", today), token);
        assertThat(locked.status()).as(locked.raw().body()).isEqualTo(201);
        String lockId = locked.body().get("id").asString();
        assertThat(call("POST", "/costing/close/locks", Map.of("from", today, "to", today), token).status())
                .isEqualTo(409);
        Res blocked = call("POST", "/projects/" + pid + "/costs/equipment", capture, headers(token, true));
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(blocked.body().get("message").asString()).contains("is locked");

        assertThat(call("POST", "/costing/close/locks/" + lockId + "/reopen", Map.of("reason",
                "Late supplier invoice"), closer).status()).as("reopen needs costing_close:reopen").isEqualTo(403);
        assertThat(call("POST", "/costing/close/locks/" + lockId + "/reopen", Map.of("reason", "late"), token)
                .status()).isEqualTo(400);
        Res reopened = call("POST", "/costing/close/locks/" + lockId + "/reopen", Map.of("reason",
                "Late supplier invoice received"), token);
        assertThat(reopened.status()).as(reopened.raw().body()).isEqualTo(200);
        assertThat(reopened.body().get("status").asString()).isEqualTo("reopened");
        assertThat(call("POST", "/projects/" + pid + "/costs/equipment", capture, headers(token, true)).status())
                .isIn(200, 201);
        assertThat(call("GET", "/costing/close/locks", null, otherToken).body()).isEmpty();
    }

    @Test
    @DisplayName("cost adjustment run: late WIP cost of a completed order → output on hand / sold, once; reversal")
    void costAdjustmentRun() {
        String location = ok(201, "POST", "/inventory/locations", Map.of("code", "CA-" + UUID.randomUUID().toString()
                .substring(0, 8), "name", "Plant " + UUID.randomUUID().toString().substring(0, 6), "type", "warehouse"))
                .get("id").asString();
        String product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, 'Blocks',"
                        + " 'stockable', true, 'AVCO'::\"CostingMethod\", 0, 0, now())")
                .params(product, demoOrg, "CA-" + UUID.randomUUID().toString().substring(0, 8)).update();
        String orderCode = "MO-CA-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("INSERT INTO \"ProductionOrder\" (id, \"organizationId\", \"orderCode\", \"outputProductId\","
                + " \"locationId\", \"plannedQty\", status, \"updatedAt\") VALUES (?, ?, ?, ?, ?, 10, 'completed', now())")
                .params(UUID.randomUUID().toString(), demoOrg, orderCode, product, location).update();
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> {
            stock.receive(StockMove.of(product, location, new BigDecimal("10")).unitCost(new BigDecimal("20"))
                    .moveType("production_output").source("production_order", orderCode));
            stock.issue(StockMove.of(product, location, new BigDecimal("4")).source("sale_test", orderCode).skipGl());
            return null;
        });
        String adj = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostValueAdjustment\" (id, \"organizationId\", \"adjustmentType\", \"sourceType\","
                + " \"sourceId\", \"productId\", \"locationId\", amount) VALUES (?, ?, 'landed_cost', 'test', ?, ?, ?, 100)")
                .params(adj, demoOrg, UUID.randomUUID().toString(), product, location).update();
        jdbc.sql("INSERT INTO \"CostValueAdjustmentLine\" (id, \"organizationId\", \"adjustmentId\", target,"
                + " \"targetRef\", amount) VALUES (?, ?, ?, 'wip', ?, 100)").params(UUID.randomUUID().toString(), demoOrg,
                adj, orderCode).update();

        JsonNode pv = ok(200, "GET", "/costing/cost-adjustment-runs/preview", null);
        boolean listed = false;
        for (JsonNode l : pv.get("lines")) {
            listed |= orderCode.equals(l.get("orderCode").asString());
        }
        assertThat(listed).isTrue();
        Map<String, Object> period = Map.of("from", today, "to", today);
        assertThat(call("POST", "/costing/cost-adjustment-runs", period, adminToken).status()).as("Idempotency-Key")
                .isEqualTo(400);
        Res run = call("POST", "/costing/cost-adjustment-runs", period, headers(adminToken, true));
        assertThat(run.status()).as(run.raw().body()).isIn(200, 201);
        String id = run.body().get("id").asString();
        String je = run.body().get("journalEntryId").asString();
        String wip = TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> determination.mapped(
                "wip"));
        BigDecimal mine = jdbc.sql("SELECT coalesce(sum(\"baseCredit\" - \"baseDebit\"), 0) FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ? AND \"accountId\" = ? AND dimensions->>'productionOrderCode' = ?").params(je,
                wip, orderCode).query(BigDecimal.class).single();
        assertThat(mine).as("WIP relieved").isEqualByComparingTo("100");
        BigDecimal adjunct = jdbc.sql("SELECT coalesce(sum(\"totalValue\"), 0) FROM \"InventoryLedger\" WHERE"
                + " \"productId\" = ? AND \"referenceType\" = 'cost_adjustment_run' AND \"referenceId\" = ?").params(
                product, id).query(BigDecimal.class).single();
        assertThat(adjunct).as("6 of 10 still on hand").isEqualByComparingTo("60");
        BigDecimal sold = jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\"), 0) FROM \"JournalLine\" l WHERE"
                + " l.\"journalEntryId\" = ? AND l.dimensions->>'productionOrderCode' = ? AND l.\"accountId\" NOT IN (?,"
                + " (SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1396'))").params(je, orderCode,
                wip, demoOrg).query(BigDecimal.class).single();
        assertThat(sold).as("4 of 10 sold → cost of sales").isEqualByComparingTo("40");

        Res again = call("POST", "/costing/cost-adjustment-runs", period, headers(adminToken, true));
        assertThat(again.status()).isEqualTo(400);
        assertThat(call("GET", "/costing/cost-adjustment-runs/" + id, null, otherToken).status()).isEqualTo(404);
        Res rev = call("POST", "/costing/cost-adjustment-runs/" + id + "/reverse", null, headers(adminToken, true));
        assertThat(rev.status()).as(rev.raw().body()).isEqualTo(200);
        assertThat(call("POST", "/costing/cost-adjustment-runs/" + id + "/reverse", null, headers(adminToken, true))
                .status()).isEqualTo(400);
        boolean back = false;
        for (JsonNode l : ok(200, "GET", "/costing/cost-adjustment-runs/preview", null).get("lines")) {
            back |= orderCode.equals(l.get("orderCode").asString());
        }
        assertThat(back).as("a reversed share waits again").isTrue();
        // leave the demo books clean for the close gate of other suites
        jdbc.sql("UPDATE \"CostValueAdjustment\" SET \"reversedAt\" = now() WHERE id = ?").param(adj).update();
        assertThat(assertAuthorizationMatrix(CostCloseController.class)).isEqualTo(10);
        assertThat(List.of(id)).isNotEmpty();
    }
}
