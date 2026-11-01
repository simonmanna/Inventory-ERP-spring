package com.inventoryerp.costing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.costing.web.DistributionCostingController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Cost Management CM-7 (ADR-S037): a same-entity branch transfer with inter-branch clearing (each branch balances) and
 * capitalised transfer freight; guards; an owned-fleet delivery cost run with an empty return leg charged to
 * distribution with the customer dimension, idempotency, one run per period, reversal.
 */
class DistributionBranchScenarioIT extends ApiTestSupport {

    String sfx = UUID.randomUUID().toString().substring(0, 6);
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    String adminId;
    String branchA;
    String branchB;
    String locA;
    String locB;
    String cash;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        branchA = branch("A");
        branchB = branch("B");
        locA = location(branchA);
        locB = location(branchB);
        cash = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1100'").param(demoOrg)
                .query(String.class).single();
    }

    String branch(String tag) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(id, demoOrg, "BR" + tag + sfx, "Branch " + tag + " " + sfx).update();
        return id;
    }

    String location(String branchId) {
        String id = ok(201, "POST", "/inventory/locations", Map.of("code", "BT-" + UUID.randomUUID().toString()
                .substring(0, 8), "name", "Depot " + UUID.randomUUID().toString().substring(0, 6), "type", "warehouse"))
                .get("id").asString();
        if (branchId != null) {
            jdbc.sql("UPDATE \"InventoryLocation\" SET \"branchId\" = ? WHERE id = ?").params(branchId, id).update();
        }
        return id;
    }

    String product() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                        + " 'stockable', true, 'AVCO'::\"CostingMethod\", 0, 0, now())")
                .params(id, demoOrg, "BT-" + UUID.randomUUID().toString().substring(0, 8), "Blocks " + sfx).update();
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", locA, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", id, "quantity", 20, "unitCost", 10))));
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res post(String path, Object body) {
        return call("POST", path, body, key());
    }

    BigDecimal branchNet(String entryId, String branchId) {
        return jdbc.sql("SELECT coalesce(sum(\"baseDebit\" - \"baseCredit\"), 0) FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ? AND \"branchId\" = ?").params(entryId, branchId).query(BigDecimal.class)
                .single();
    }

    BigDecimal line(String entryId, String code) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ? AND a.code = ?")
                .params(entryId, code).query(BigDecimal.class).single();
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    @Test
    @DisplayName("branch transfer: clearing balances each branch, freight capitalised on the transfer-in layer")
    void branchTransfer() {
        String blocks = product();
        Map<String, Object> body = new HashMap<>(Map.of("productId", blocks, "quantity", 10, "fromLocationId", locA,
                "toLocationId", locB, "freight", 30, "freightAccountId", cash));
        assertThat(call("POST", "/costing/branch-transfers", body, adminToken).status()).as("Idempotency-Key")
                .isEqualTo(400);
        Res r = post("/costing/branch-transfers", body);
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        JsonNode t = r.body();
        assertThat(num(t.get("value"))).isEqualByComparingTo("100");
        String je = t.get("journalEntryId").asString();
        assertThat(branchNet(je, branchA)).as("source branch balances").isEqualByComparingTo("0");
        assertThat(branchNet(je, branchB)).as("destination branch balances").isEqualByComparingTo("0");
        assertThat(line(je, "1299")).isEqualByComparingTo("0");
        assertThat(line(je, "1396")).as("transfer freight capitalised").isEqualByComparingTo("30");
        BigDecimal adjunct = jdbc.sql("SELECT coalesce(sum(\"totalValue\"), 0) FROM \"InventoryLedger\" WHERE"
                + " \"productId\" = ? AND \"locationId\" = ? AND \"valuationComponent\" = 'price_difference'")
                .params(blocks, locB).query(BigDecimal.class).single();
        assertThat(adjunct).isEqualByComparingTo("30");
        BigDecimal atB = jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(blocks, locB).query(BigDecimal.class).single();
        assertThat(atB).isEqualByComparingTo("10");
        assertThat(num(ok(200, "GET", "/costing/branch-transfers/clearing", null).get("total")))
                .isEqualByComparingTo("0");

        String sameBranch = location(branchA);
        assertThat(post("/costing/branch-transfers", Map.of("productId", blocks, "quantity", 1, "fromLocationId",
                locA, "toLocationId", sameBranch)).status()).isEqualTo(400);
        assertThat(post("/costing/branch-transfers", Map.of("productId", blocks, "quantity", 1, "fromLocationId",
                locA, "toLocationId", location(null))).status()).isEqualTo(400);
        String unclassified = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id ="
                + " a.\"categoryId\" WHERE a.\"organizationId\" = ? AND c.key = 'operating_expense' AND a.\"isPostable\""
                + " AND NOT EXISTS (SELECT 1 FROM \"AccountingPolicy\" p WHERE p.\"scopeId\" = a.id) ORDER BY a.code"
                + " LIMIT 1").param(demoOrg).query(String.class).single();
        Res ineligible = post("/costing/branch-transfers", Map.of("productId", blocks, "quantity", 1,
                "fromLocationId", locA, "toLocationId", locB, "freight", 5, "freightAccountId", unclassified));
        assertThat(ineligible.status()).isEqualTo(400);
        assertThat(ineligible.body().get("message").asString()).contains("capitalisable");
        assertThat(call("GET", "/costing/branch-transfers/" + t.get("id").asString(), null, otherToken).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("delivery cost run: own truck, empty return charged, distribution with the customer; reversal")
    void deliveryCostRun() {
        String customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, true, now())").params(customer, demoOrg, "CU-" + sfx, "Site customer " + sfx).update();
        String category = null;
        for (JsonNode c : ok(200, "GET", "/fleet/categories", null)) {
            if ("TIPPER".equals(c.get("code").asString())) {
                category = c.get("id").asString();
            }
        }
        String truck = ok(201, "POST", "/fleet/vehicles", Map.of("categoryId", category, "registrationPlate",
                "DCR " + sfx, "payloadCapacityT", 20)).get("id").asString();
        String order = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetDeliveryOrder\" (id, \"organizationId\", code, \"partnerId\", \"locationId\","
                + " \"requestedDate\", \"branchId\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, current_date, ?, now())")
                .params(order, demoOrg, "DO-" + sfx, customer, locA, branchA).update();
        String load = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetLoad\" (id, \"organizationId\", code, \"deliveryOrderId\", sequence,"
                + " \"plannedDate\", status, carrier, \"vehicleId\", \"locationId\", \"deliveredTonnes\", \"distanceKm\","
                + " \"emptyKm\", \"deliveredAt\", \"updatedAt\") VALUES (?, ?, ?, ?, 1, current_date, 'delivered', 'own',"
                + " ?, ?, 20, 100, 100, now(), now())").params(load, demoOrg, "TR-" + sfx, order, truck, locA).update();

        Map<String, Object> body = Map.of("from", today, "to", today, "branchId", branchA, "ratePerKm",
                Map.of(truck, 2));
        JsonNode pv = ok(200, "POST", "/costing/delivery-cost-runs/preview", body);
        JsonNode mine = null;
        for (JsonNode l : pv.get("lines")) {
            if (l.get("loadId").asString().equals(load)) {
                mine = l;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(num(mine.get("amount"))).isEqualByComparingTo("400"); // (100 loaded + 100 empty) × 2
        assertThat(num(mine.get("perTonneKm"))).isEqualByComparingTo("0.2");

        assertThat(pv.get("lines")).hasSize(1);
        Map<String, Object> runBody = body;
        assertThat(call("POST", "/costing/delivery-cost-runs", runBody, adminToken).status()).isEqualTo(400);
        Res run = post("/costing/delivery-cost-runs", runBody);
        assertThat(run.status()).as(run.raw().body()).isIn(200, 201);
        String je = run.body().get("journalEntryId").asString();
        BigDecimal toCustomer = jdbc.sql("SELECT coalesce(sum(\"baseDebit\"), 0) FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ? AND dimensions->>'fleetLoadId' = ? AND dimensions->>'customerId' = ? AND"
                + " \"branchId\" = ?").params(je, load, customer, branchA).query(BigDecimal.class).single();
        assertThat(toCustomer).as("distribution cost carries the customer and branch").isEqualByComparingTo("400");
        assertThat(line(je, "6460").add(line(je, "5385"))).as("P&L neutral reclass").isEqualByComparingTo("0");
        assertThat(post("/costing/delivery-cost-runs", runBody).status()).isEqualTo(409);

        String id = run.body().get("id").asString();
        assertThat(call("GET", "/costing/delivery-cost-runs/" + id, null, otherToken).status()).isEqualTo(404);
        Res rev = post("/costing/delivery-cost-runs/" + id + "/reverse", null);
        assertThat(rev.status()).as(rev.raw().body()).isEqualTo(200);
        assertThat(post("/costing/delivery-cost-runs/" + id + "/reverse", null).status()).isEqualTo(400);
        assertThat(assertAuthorizationMatrix(DistributionCostingController.class)).isEqualTo(9);
    }
}
