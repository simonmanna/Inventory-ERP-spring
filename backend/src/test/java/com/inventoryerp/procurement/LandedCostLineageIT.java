package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.BooksGuard;
import com.inventoryerp.inventory.application.CostLineageService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.kernel.settings.SettingResolverService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Landed cost LC-1 acceptance (ADR-S053): a transfer is not consumption — the charge follows the goods to wherever
 * they are now. Every step is checked with the LC-0 books assertion (balanced, tie-out unchanged, 1396 = open adjunct,
 * exact classification); figures are hand-computed in each display name.
 */
@ExtendWith(BooksGuard.class)
class LandedCostLineageIT extends LandedCostFixtures {

    @Autowired
    CostLineageService lineage;

    @Autowired
    SettingResolverService settingResolver;

    void evictSettings() {
        settingResolver.invalidate(demoOrg);
    }

    /** A DEMO user holding exactly {@code permissions}; returns its token. */
    String userWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Lineage " + role, permissions).update();
        String email = "lineage-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Lineage@12345", role);
        return login("DEMO", email, "Lineage@12345");
    }

    Map<String, String> keyFor(String token) {
        return Map.of("Authorization", "Bearer " + token, "Idempotency-Key", UUID.randomUUID().toString());
    }

    String location3() {
        return location("Lineage W3");
    }

    void transfer(String p, String from, String to, int qty) {
        asAdmin(() -> stock.transfer(StockMove.transfer(p, from, to, new BigDecimal(qty)).source("audit", suffix())));
    }

    String outRow(String p, String from) {
        return jdbc.sql("SELECT id FROM \"InventoryLedger\" WHERE \"productId\" = ? AND \"locationId\" = ? AND type ="
                + " 'transfer_out' ORDER BY \"createdAt\" DESC LIMIT 1").params(p, from).query(String.class).single();
    }

    String inRow(String p, String to) {
        return jdbc.sql("SELECT id FROM \"InventoryLedger\" WHERE \"productId\" = ? AND \"locationId\" = ? AND type ="
                + " 'transfer_in' ORDER BY \"createdAt\" DESC LIMIT 1").params(p, to).query(String.class).single();
    }

    List<Map<String, Object>> exceptions(String p) {
        return jdbc.sql("SELECT * FROM \"LineageException\" WHERE \"productId\" = ? ORDER BY \"createdAt\"").param(p)
                .query().listOfRows();
    }

    @Test
    @DisplayName("multi-hop: 60 W1→W2, 30 W2→W3, 10 sold at W3, 20 at W1; 1000 → W1 200, W2 300, W3 200, COGS 300")
    void multiHop() {
        String p = product("AVCO", false, 0);
        String w3 = location3();
        String grn = receive(p, 100, 50);
        transfer(p, w1, w2, 60);
        transfer(p, w2, w3, 30);
        sell(p, w3, 10);
        sell(p, w1, 20);
        Snap s = snap();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        assertBooks(s, moves("stock", "700", "cogs", "300", "clearing", "-1000"));
        assertThat(avg(p, w1)).isEqualByComparingTo("60");
        assertThat(avg(p, w2)).isEqualByComparingTo("60");
        assertThat(avg(p, w3)).isEqualByComparingTo("60");
        // Lineage: one adjustment per pool, children linked to the root.
        assertThat(jdbc.sql("SELECT count(*) FROM \"CostValueAdjustment\" WHERE \"productId\" = ? AND"
                + " \"rootAdjustmentId\" IS NOT NULL").param(p).query(Long.class).single()).isEqualTo(3L);
        sell(p, w3, 5);
        reverse(v);
        // Only the 5 sold after posting keep their landed share in COGS until the reclass takes it back: net zero.
        assertBooks(s, moves("stock", "-250", "cogs", "250"));
        assertThat(avg(p, w3)).isEqualByComparingTo("50");
        assertThat(avg(p, w1)).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("transfer after posting: the reversal follows the goods to W2 and takes 600 out of W2, not COGS")
    void reverseFollowsLaterTransfer() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        Snap s = snap();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        transfer(p, w1, w2, 60);
        assertThat(avg(p, w2)).isEqualByComparingTo("60");
        reverse(v);
        assertBooks(s, moves());
        assertThat(avg(p, w1)).isEqualByComparingTo("50");
        assertThat(avg(p, w2)).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("unpaired transfer: 600 parked in 1396 with an exception; assigning the in row releases it to W2")
    void unpairedThenAssigned() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        transfer(p, w1, w2, 60);
        String out = outRow(p, w1);
        String in = inRow(p, w2);
        jdbc.sql("DELETE FROM \"InventoryTransferLineage\" WHERE \"sourceLedgerId\" = ?").param(out).update();
        Snap s = snap();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        assertBooks(s, moves("stock", "400", "adjunct", "600", "clearing", "-1000"));
        List<Map<String, Object>> ex = exceptions(p);
        assertThat(ex).hasSize(1);
        assertThat(ex.getFirst().get("kind")).isEqualTo("unpaired_transfer");
        String id = (String) ex.getFirst().get("id");
        Res bad = call("POST", "/inventory/lineage-exceptions/" + id + "/assign", Map.of("destinationLedgerId", out,
                "reason", "wrong row"), key());
        assertThat(bad.status()).isEqualTo(400);
        ok(200, "POST", "/inventory/lineage-exceptions/" + id + "/assign", Map.of("destinationLedgerId", in,
                "reason", "Delivery note DN-7 matched"));
        assertBooks(s, moves("stock", "1000", "clearing", "-1000"));
        assertThat(avg(p, w2)).isEqualByComparingTo("60");
        assertThat(exceptions(p).getFirst().get("status")).isEqualTo("resolved");
        Res rev = call("POST", "/procurement/landed-cost-vouchers/" + v + "/reverse", null, key());
        assertThat(rev.status()).isEqualTo(400);
        assertThat(rev.body().get("message").asString()).contains("lineage exception");
    }

    @Test
    @DisplayName("unpaired transfer written off: 600 from 1396 to the chosen account, with a reason; 403 without rights")
    void unpairedWrittenOff() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        transfer(p, w1, w2, 60);
        jdbc.sql("DELETE FROM \"InventoryTransferLineage\" WHERE \"sourceLedgerId\" = ?").param(outRow(p, w1))
                .update();
        Snap s = snap();
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000))));
        String id = (String) exceptions(p).getFirst().get("id");
        String viewer = userWith("landed_cost:read");
        assertThat(call("POST", "/inventory/lineage-exceptions/" + id + "/write-off", Map.of("accountId",
                mapped("cogs"), "reason", "x"), keyFor(viewer)).status()).isEqualTo(403);
        assertThat(call("GET", "/inventory/lineage-exceptions?status=open", null, viewer).status()).isEqualTo(200);
        ok(200, "POST", "/inventory/lineage-exceptions/" + id + "/write-off", Map.of("accountId", mapped("cogs"),
                "reason", "Goods lost in transit, insurer declined"));
        assertBooks(s, moves("stock", "400", "cogs", "600", "clearing", "-1000"));
        assertThat(exceptions(p).getFirst().get("status")).isEqualTo("written_off");
    }

    @Test
    @DisplayName("cycle injected: half the transferred share loops back → parked 300 with a cycle exception")
    void cycleDetected() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        transfer(p, w1, w2, 60);
        String firstIn = inRow(p, w2);
        transfer(p, w2, w1, 60);
        jdbc.sql("INSERT INTO \"InventoryTransferLineage\" (id, \"organizationId\", \"sourceLedgerId\","
                + " \"destinationLedgerId\", quantity) VALUES (?, ?, ?, ?, 60)").params(UUID.randomUUID().toString(),
                demoOrg, outRow(p, w2), firstIn).update();
        Snap s = snap();
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000))));
        assertBooks(s, moves("stock", "700", "adjunct", "300", "clearing", "-1000"));
        assertThat(exceptions(p)).extracting(e -> e.get("kind")).containsExactly("cycle");
    }

    @Test
    @DisplayName("returns: 20 of 100 returned to the supplier → 200 on the returns account (policy expense)")
    void returnPolicyExpense() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        asAdmin(() -> stock.issue(StockMove.of(p, w1, new BigDecimal(20)).moveType("return_to_supplier")
                .source("debit_note", suffix())));
        Snap s = snap();
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000))));
        assertBooks(s, moves("stock", "800", "returns", "200", "clearing", "-1000"));
    }

    @Test
    @DisplayName("returns: recover_from_supplier parks the 200 in 1396 awaiting the supplier's credit")
    void returnPolicyRecover() {
        String setting = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"updatedAt\") VALUES"
                + " (?, ?, 'costing.landedCost.returnPolicy', '\"recover_from_supplier\"'::jsonb, 'organization',"
                + " now())").params(setting, demoOrg).update();
        try {
            evictSettings();
            String p = product("AVCO", false, 0);
            String grn = receive(p, 100, 50);
            asAdmin(() -> stock.issue(StockMove.of(p, w1, new BigDecimal(20)).moveType("return_to_supplier")
                    .source("debit_note", suffix())));
            Snap s = snap();
            String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
            approveAndPost(v);
            assertBooks(s, moves("stock", "800", "adjunct", "200", "clearing", "-1000"));
            reverse(v);
            assertBooks(s, moves());
        } finally {
            jdbc.sql("DELETE FROM \"Setting\" WHERE id = ?").param(setting).update();
            evictSettings();
        }
    }

    @Test
    @DisplayName("STANDARD: transferred after posting, sold at W2 → the release takes the adjunct from W2 to COGS")
    void standardAdjunctFollowsTransfer() {
        String p = product("STANDARD", false, 50);
        String grn = receive(p, 100, 50);
        Snap s = snap();
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000))));
        assertBooks(s, moves("adjunct", "1000", "clearing", "-1000"));
        transfer(p, w1, w2, 60);
        asAdmin(() -> lineage.release());
        // Nothing consumed yet: the 600 moved with the goods, still in 1396.
        assertBooks(s, moves("adjunct", "1000", "clearing", "-1000"));
        sell(p, w2, 30);
        asAdmin(() -> lineage.release());
        assertBooks(s, moves("stock", "-1500", "adjunct", "700", "cogs", "1800", "clearing", "-1000"));
    }

    @Test
    @DisplayName("lot: 60 of the lot moved to W2 before the charge → both batch rows carry +10, nothing to COGS")
    void lotFollowsTransfer() {
        String p = product("FIFO", true, 0);
        String grn = receive(p, 100, 50);
        transfer(p, w1, w2, 60);
        Snap s = snap();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        assertBooks(s, moves("stock", "1000", "clearing", "-1000"));
        assertThat(lotValue(p)).isEqualByComparingTo("6000");
        sell(p, w2, 10);
        reverse(v);
        assertBooks(s, moves("stock", "-500", "cogs", "500"));
        assertThat(lotValue(p)).isEqualByComparingTo("4500");
    }

    @Test
    @DisplayName("credit voucher: 200 of a 1000 freight credited after 40 sold → stock −120, COGS −80; reversible")
    void creditVoucher() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        sell(p, w1, 40);
        Snap s = snap();
        String chargeId = ok(200, "GET", "/procurement/landed-cost-vouchers/" + v, null).get("charges").get(0)
                .get("id").asString();
        Res over = call("POST", "/procurement/landed-cost-vouchers/" + v + "/credit", Map.of("charges",
                List.of(Map.of("chargeId", chargeId, "amount", 1200))), key());
        assertThat(over.status()).isEqualTo(400);
        Res created = call("POST", "/procurement/landed-cost-vouchers/" + v + "/credit", Map.of("charges",
                List.of(Map.of("chargeId", chargeId, "amount", 200, "supplierInvoiceRef", "CN-" + suffix()))), key());
        assertThat(created.status()).as(created.raw().body()).isIn(200, 201);
        JsonNode credit = created.body();
        assertThat(credit.get("voucherType").asString()).isEqualTo("credit");
        String c = credit.get("id").asString();
        approveAndPost(c);
        assertBooks(s, moves("stock", "-120", "cogs", "-80", "clearing", "200"));
        assertThat(avg(p, w1)).isEqualByComparingTo("58");
        Res blocked = call("POST", "/procurement/landed-cost-vouchers/" + v + "/reverse", null, key());
        assertThat(blocked.status()).isEqualTo(400);
        reverse(c);
        assertBooks(s, moves());
        assertThat(avg(p, w1)).isEqualByComparingTo("60");
    }

    @Test
    @DisplayName("completed production order: its share stays WIP of the order and is listed for the CM-10 cascade")
    void completedOrderCascades() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        String code = "PO-LCL-" + suffix();
        jdbc.sql("INSERT INTO \"ProductionOrder\" (id, \"organizationId\", \"orderCode\", \"outputProductId\","
                + " \"locationId\", \"plannedQty\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, 1, now())")
                .params(UUID.randomUUID().toString(), demoOrg, code, p, w1).update();
        asAdmin(() -> stock.issue(StockMove.of(p, w1, new BigDecimal(30)).moveType("production_consume")
                .source("production_order", code)));
        jdbc.sql("UPDATE \"ProductionOrder\" SET status = 'completed' WHERE \"orderCode\" = ?").param(code).update();
        Snap s = snap();
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000))));
        assertBooks(s, moves("stock", "700", "wip", "300", "clearing", "-1000"));
        JsonNode pending = ok(200, "GET", "/costing/cost-adjustment-runs/preview", null);
        boolean listed = false;
        for (JsonNode l : pending.get("lines")) {
            listed |= code.equals(l.get("orderCode").asString());
        }
        assertThat(listed).as("WIP share of the completed order is pending cascade").isTrue();
    }

    @Test
    @DisplayName("accrual after a transfer: the estimate follows the goods → adjunct at W1 and W2, nothing to COGS")
    void accrualFollowsTransfer() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        transfer(p, w1, w2, 60);
        String rule = call("POST", "/procurement/landed-cost-accrual-rules", Map.of("scopeType", "supplier", "scopeId",
                vendor, "ratePct", 10), key()).body().get("id").asString();
        try {
            Snap s = snap();
            Res created = call("POST", "/procurement/landed-cost-accruals", Map.of("goodsReceiptId", grn), key());
            assertThat(created.status()).as(created.raw().body()).isIn(200, 201);
            JsonNode a = created.body();
            assertBooks(s, moves("adjunct", "500", "accrual", "-500"));
            assertThat(jdbc.sql("SELECT coalesce(sum(\"openAmount\"), 0) FROM \"CostValueAdjustment\" WHERE"
                    + " \"productId\" = ? AND \"locationId\" = ?").params(p, w2).query(BigDecimal.class).single())
                    .isEqualByComparingTo("300");
            Res rev = call("POST", "/procurement/landed-cost-accruals/" + a.get("id").asString() + "/reverse", null,
                    key());
            assertThat(rev.status()).as(rev.raw().body()).isIn(200, 201);
            assertBooks(s, moves());
        } finally {
            ok(204, "DELETE", "/procurement/landed-cost-accrual-rules/" + rule, null);
        }
    }
}
