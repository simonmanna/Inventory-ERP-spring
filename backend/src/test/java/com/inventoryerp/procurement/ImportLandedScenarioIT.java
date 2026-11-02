package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.costing.web.LandedCostAccrualController;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.extension.ExtendWith;
import com.inventoryerp.BooksGuard;

/**
 * Cost Management CM-6 (ADR-S036): landed cost accrued at receipt by rule, 40 % sold and released before the actual
 * voucher, the accrual cleared exactly where it went (adjunct / cost of sales) so 2156 returns to zero; reversal of an
 * unvouchered accrual; reversal of a legacy per-receipt landed cost through the voucher engine.
 */
@ExtendWith(BooksGuard.class)
class ImportLandedScenarioIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    String adminId;
    String warehouse;
    String supplier;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "LA-" + suffix(), "name", "Import "
                + suffix(), "type", "warehouse")).get("id").asString();
        supplier = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(supplier, demoOrg, "SUP-" + suffix(), "Importer "
                        + suffix()).update();
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String product() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                        + " 'stockable', true, 'AVCO'::\"CostingMethod\", 0, 0, now())")
                .params(id, demoOrg, "LA-" + suffix(), "Pump " + suffix()).update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    String receive(String product, int qty, int unitCost) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", product);
        line.put("description", "Pumps");
        line.put("quantity", qty);
        line.put("unitCost", unitCost);
        return ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "receivedAt", Instant.now().toString(), "lines", List.of(line))).get("id").asString();
    }

    void sell(String product, int qty) {
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> stock.issue(StockMove.of(product, warehouse,
                new BigDecimal(qty)).source("audit", suffix())));
    }

    String account(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    BigDecimal entry(String journalEntryId, String code) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ? AND a.code = ?")
                .params(journalEntryId, code).query(BigDecimal.class).single();
    }

    BigDecimal adjunct(String product) {
        return jdbc.sql("SELECT coalesce(sum(\"totalValue\"), 0) FROM \"InventoryLedger\" WHERE \"productId\" = ? AND"
                + " \"valuationComponent\" = 'price_difference'").param(product).query(BigDecimal.class).single();
    }

    void clearRules() {
        for (JsonNode r : ok(200, "GET", "/procurement/landed-cost-accrual-rules", null)) {
            ok(204, "DELETE", "/procurement/landed-cost-accrual-rules/" + r.get("id").asString(), null);
        }
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    @Test
    @DisplayName("accrual 10 % by supplier rule, 40 % sold and released, actual voucher 1200, clearing zeroes 2156")
    void accrueSellReleaseVoucherClear() {
        money("/procurement/landed-cost-accrual-rules", Map.of("scopeType", "default", "ratePct", 5));
        money("/procurement/landed-cost-accrual-rules", Map.of("scopeType", "supplier", "scopeId", supplier,
                "ratePct", 10));
        String pump = product();
        String grn = receive(pump, 100, 100);
        assertThat(call("POST", "/procurement/landed-cost-accruals", Map.of("goodsReceiptId", grn), adminToken)
                .status()).as("Idempotency-Key").isEqualTo(400);
        JsonNode a = money("/procurement/landed-cost-accruals", Map.of("goodsReceiptId", grn));
        assertThat(num(a.get("amount"))).isEqualByComparingTo("1000");
        assertThat(entry(a.get("journalEntryId").asString(), "1396")).isEqualByComparingTo("1000");
        assertThat(entry(a.get("journalEntryId").asString(), "2156")).isEqualByComparingTo("-1000");
        Res dup = call("POST", "/procurement/landed-cost-accruals", Map.of("goodsReceiptId", grn), key());
        assertThat(dup.status()).isEqualTo(409);

        sell(pump, 40);
        money("/inventory/price-differences/release", null); // 400 of the estimate leaves the adjunct for COGS
        assertThat(adjunct(pump)).isEqualByComparingTo("600");

        Map<String, Object> charge = new LinkedHashMap<>();
        charge.put("kind", "freight");
        charge.put("description", "Sea freight");
        charge.put("amount", 1200);
        String voucher = money("/procurement/landed-cost-vouchers", Map.of("goodsReceiptIds", List.of(grn), "charges",
                List.of(charge))).get("id").asString();
        ok(200, "POST", "/procurement/landed-cost-vouchers/" + voucher + "/approve", null);
        money("/procurement/landed-cost-vouchers/" + voucher + "/post", null);

        String id = a.get("id").asString();
        JsonNode cleared = money("/procurement/landed-cost-accruals/" + id + "/clear", Map.of("voucherId", voucher));
        assertThat(cleared.get("status").asString()).isEqualTo("cleared");
        assertThat(num(cleared.get("actualAmount"))).isEqualByComparingTo("1200");
        String clearing = cleared.get("clearingEntryId").asString();
        assertThat(entry(clearing, "2156")).isEqualByComparingTo("1000");
        assertThat(entry(clearing, "1396")).isEqualByComparingTo("-600");
        assertThat(adjunct(pump)).as("adjunct of the estimate fully removed").isEqualByComparingTo("0");
        BigDecimal accrualNet = jdbc.sql("SELECT coalesce(sum(\"baseCredit\" - \"baseDebit\"), 0) FROM \"JournalLine\""
                + " WHERE \"accountId\" = ? AND \"journalEntryId\" IN (?, ?)").params(account("2156"),
                a.get("journalEntryId").asString(), clearing).query(BigDecimal.class).single();
        assertThat(accrualNet).isEqualByComparingTo("0");
        assertThat(num(ok(200, "GET", "/procurement/landed-cost-accruals/reconciliation", null).get("difference")))
                .isEqualByComparingTo("0");
        assertThat(call("POST", "/procurement/landed-cost-accruals/" + id + "/clear", Map.of("voucherId", voucher),
                key()).status()).isEqualTo(400);
        assertThat(call("GET", "/procurement/landed-cost-accruals/" + id, null, otherToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("an accrual without a voucher reverses; no rule refuses; legacy landed cost reverses")
    void reversalsAndRules() {
        String pump = product();
        String grn = receive(pump, 10, 50);
        clearRules();
        money("/procurement/landed-cost-accrual-rules", Map.of("scopeType", "default", "ratePct", 4));
        JsonNode a = money("/procurement/landed-cost-accruals", Map.of("goodsReceiptId", grn));
        assertThat(num(a.get("amount"))).isEqualByComparingTo("20");
        JsonNode reversed = money("/procurement/landed-cost-accruals/" + a.get("id").asString() + "/reverse", null);
        assertThat(reversed.get("status").asString()).isEqualTo("reversed");
        assertThat(adjunct(pump)).isEqualByComparingTo("0");

        clearRules();
        Res none = call("POST", "/procurement/landed-cost-accruals", Map.of("goodsReceiptId", receive(pump, 1, 50)),
                key());
        assertThat(none.status()).isEqualTo(400);
        assertThat(none.body().get("message").asString()).startsWith("No landed-cost accrual rule");
        assertThat(as("POST", "/procurement/landed-cost-accrual-rules", Map.of("scopeType", "default", "ratePct", 0))
                .status()).isEqualTo(400);

        // Legacy per-receipt landed cost: posted, half sold, reversed through the voucher engine.
        String legacyGrn = receive(pump, 10, 50);
        String cash = account("1100");
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("kind", "freight");
        ch.put("description", "Truck");
        ch.put("amount", 100);
        JsonNode lc = money("/procurement/landed-costs", Map.of("goodsReceiptId", legacyGrn, "creditAccountId", cash,
                "allocationMethod", "value", "charges", List.of(ch)));
        String lcId = lc.get("id").asString();
        JsonNode posted = money("/procurement/landed-costs/" + lcId + "/post", null);
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        sell(pump, 5);
        assertThat(call("POST", "/procurement/landed-costs/" + lcId + "/reverse", null, adminToken).status())
                .as("Idempotency-Key").isEqualTo(400);
        JsonNode rev = money("/procurement/landed-costs/" + lcId + "/reverse", null);
        assertThat(rev.get("status").asString()).isEqualTo("reversed");
        assertThat(call("POST", "/procurement/landed-costs/" + lcId + "/reverse", null, key()).status())
                .isEqualTo(400);
        assertThat(assertAuthorizationMatrix(LandedCostAccrualController.class)).isEqualTo(9);
    }
}
