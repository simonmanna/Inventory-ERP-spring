package com.inventoryerp.costing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.web.CostLineageController;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.extension.ExtendWith;
import com.inventoryerp.BooksGuard;

/**
 * Cost Management CM-1 (ADR-S031): cost lineage, value-only stock ledger rows, inventory revaluation, IAS 2 NRV
 * allowance and release, landed cost on a STANDARD item split over lineage — each tying the stock sub-ledger to the GL.
 */
@ExtendWith(BooksGuard.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ValuationIntegrityIT extends ApiTestSupport {

    String org;
    String token;
    String userId;
    String location;
    String today = LocalDate.now(ZoneOffset.UTC).toString();

    @BeforeAll
    void books() {
        String code = "vi-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Valuation Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
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

    Res keyed(String method, String path, Object body) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    JsonNode keyedOk(String method, String path, Object body) {
        Res r = keyed(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    String product(String name, String method) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                + " \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', ?::\"CostingMethod\", 0,"
                + " 20, now())").params(id, org, name.toUpperCase() + "-" + id.substring(0, 4), name, method).update();
        return id;
    }

    String receive(String productId, int qty, int unitCost) {
        return ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("warehouseId", location, "receivedAt",
                Instant.now().toString(), "lines", List.of(Map.of("productId", productId, "description", "Goods",
                        "quantity", qty, "unitCost", unitCost)))).get("id").asString();
    }

    void issue(String productId, int qty) {
        ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", location, "responsibleById", userId,
                "approvedById", userId, "items", List.of(Map.of("productId", productId, "quantity", qty))));
    }

    String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?")
                .params(org, key).query(String.class).single();
    }

    BigDecimal balance(String accountId) {
        return jdbc.sql("SELECT coalesce(sum(debit), 0) - coalesce(sum(credit), 0) FROM \"JournalLine\" WHERE"
                + " \"organizationId\" = ? AND \"accountId\" = ?").params(org, accountId).query(BigDecimal.class)
                .single();
    }

    BigDecimal avg(String productId) {
        return jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, location).query(BigDecimal.class).single();
    }

    String receiptLedger(String productId) {
        return jdbc.sql("SELECT id FROM \"InventoryLedger\" WHERE \"productId\" = ? AND \"quantityChange\" > 0 ORDER BY"
                + " \"createdAt\" LIMIT 1").param(productId).query(String.class).single();
    }

    /** Sub-ledger (current valuation) equals the inventory control accounts; historical (ledger) equals it too. */
    void assertTiesOut() {
        JsonNode t = ok(200, "GET", "/reports/inventory/gl-tieout?tolerance=0.05", null);
        assertThat(t.get("withinTolerance").asBoolean()).as(t.toString()).isTrue();
        assertThat(new BigDecimal(t.get("valuationVsLedger").asString())).as(t.toString())
                .isBetween(new BigDecimal("-0.05"), new BigDecimal("0.05"));
    }

    @Test
    @Order(1)
    @DisplayName("lineage: distribution preview and trace of an AVCO receipt layer")
    void lineage() {
        String cement = product("Cement", "AVCO");
        receive(cement, 100, 10);
        issue(cement, 30);
        String layer = receiptLedger(cement);
        JsonNode d = ok(200, "GET", "/inventory/cost-layers/" + layer + "/distribution?amount=100", null);
        assertThat(new BigDecimal(d.get("onHand").asString())).isEqualByComparingTo("70");
        assertThat(d.get("consumed")).hasSize(1);
        assertThat(new BigDecimal(d.get("consumed").get(0).get("amount").asString())).isEqualByComparingTo("30");
        assertThat(d.get("consumed").get(0).get("target").asString()).isEqualTo("cogs");
        JsonNode trace = ok(200, "GET", "/inventory/cost-layers/" + layer + "/trace", null);
        assertThat(new BigDecimal(trace.get("originalUnitCost").asString())).isEqualByComparingTo("10");
        assertThat(call("GET", "/inventory/cost-layers/" + layer + "/trace", null, otherToken).status())
                .isEqualTo(404);
        assertTiesOut();
    }

    @Test
    @Order(2)
    @DisplayName("revaluation: AVCO to 12, GL and value row, reverse restores; refused once stock moved")
    void revaluation() {
        String sand = product("Sand", "AVCO");
        receive(sand, 50, 10);
        BigDecimal stock0 = balance(mapped("stock_valuation"));
        JsonNode draft = ok(201, "POST", "/inventory/revaluations", Map.of("kind", "revaluation", "date", today,
                "reason", "Market", "lines", List.of(Map.of("productId", sand, "locationId", location,
                        "unitCostAfter", 12))));
        String id = draft.get("id").asString();
        assertThat(call("POST", "/inventory/revaluations/" + id + "/post", null, token).status()).isEqualTo(400);
        JsonNode posted = keyedOk("POST", "/inventory/revaluations/" + id + "/post", null);
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        assertThat(new BigDecimal(posted.get("totalAmount").asString())).isEqualByComparingTo("100");
        assertThat(avg(sand)).isEqualByComparingTo("12");
        assertThat(balance(mapped("stock_valuation")).subtract(stock0)).isEqualByComparingTo("100");
        assertTiesOut();

        JsonNode reversed = keyedOk("POST", "/inventory/revaluations/" + id + "/reverse", null);
        assertThat(reversed.get("status").asString()).isEqualTo("reversed");
        assertThat(avg(sand)).isEqualByComparingTo("10");
        assertThat(balance(mapped("stock_valuation")).subtract(stock0)).isEqualByComparingTo("0");
        assertTiesOut();

        String again = ok(201, "POST", "/inventory/revaluations", Map.of("lines", List.of(Map.of("productId", sand,
                "locationId", location, "unitCostAfter", 11)))).get("id").asString();
        keyedOk("POST", "/inventory/revaluations/" + again + "/post", null);
        issue(sand, 5);
        Res refused = keyed("POST", "/inventory/revaluations/" + again + "/reverse", null);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).startsWith("Stock has moved since the revaluation");

        String std = product("Rebar", "STANDARD");
        Res bad = call("POST", "/inventory/revaluations", Map.of("lines", List.of(Map.of("productId", std,
                "locationId", location, "unitCostAfter", 11))), token);
        assertThat(bad.body().get("message").asString()).contains("activating a new standard cost");
        assertTiesOut();
    }

    @Test
    @Order(3)
    @DisplayName("NRV: write-down to allowance, remeasured down and back (never above cost), released when sold")
    void nrv() {
        String tile = product("Tile", "AVCO");
        receive(tile, 60, 10);
        String allowance = null;
        // Carrying 10, NRV 8 → allowance 60 × 2 = 120.
        String a = ok(201, "POST", "/inventory/revaluations", Map.of("kind", "nrv", "lines", List.of(Map.of(
                "productId", tile, "locationId", location, "netRealisableValue", 8)))).get("id").asString();
        JsonNode posted = keyedOk("POST", "/inventory/revaluations/" + a + "/post", null);
        assertThat(new BigDecimal(posted.get("totalAmount").asString())).isEqualByComparingTo("-120");
        allowance = jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key ="
                + " 'inventory_nrv_allowance'").param(org).query(String.class).single();
        assertThat(balance(allowance)).isEqualByComparingTo("-120");
        assertTiesOut();

        // NRV recovers above cost: the allowance is reversed to zero, not beyond (IAS 2.33).
        String b = ok(201, "POST", "/inventory/revaluations", Map.of("kind", "nrv", "lines", List.of(Map.of(
                "productId", tile, "locationId", location, "netRealisableValue", 25)))).get("id").asString();
        assertThat(new BigDecimal(keyedOk("POST", "/inventory/revaluations/" + b + "/post", null).get("totalAmount")
                .asString())).isEqualByComparingTo("120");
        assertThat(balance(allowance)).isEqualByComparingTo("0");

        // Write down again (NRV 9 → 60), sell half, release: 30 released to cost of sales.
        String c = ok(201, "POST", "/inventory/revaluations", Map.of("kind", "nrv", "lines", List.of(Map.of(
                "productId", tile, "locationId", location, "netRealisableValue", 9)))).get("id").asString();
        keyedOk("POST", "/inventory/revaluations/" + c + "/post", null);
        assertThat(balance(allowance)).isEqualByComparingTo("-60");
        issue(tile, 30);
        assertThat(call("POST", "/inventory/price-differences/release", null, token).status()).isEqualTo(400);
        JsonNode released = keyedOk("POST", "/inventory/price-differences/release", null);
        assertThat(new BigDecimal(released.get("released").asString())).isEqualByComparingTo("-30");
        assertThat(balance(allowance)).isEqualByComparingTo("-30");
        assertTiesOut();
        Res refused = keyed("POST", "/inventory/revaluations/" + c + "/reverse", null);
        assertThat(refused.body().get("message").asString()).startsWith("Part of this allowance was already released");
    }

    @Test
    @Order(4)
    @DisplayName("STANDARD landed cost: on-hand share to the price-difference adjunct, sold share to COGS (AD-2b)")
    void standardLanded() {
        String bolt = product("Bolt", "AVCO");
        JsonNode draft = ok(201, "POST", "/inventory/standard-costs", Map.of("productId", bolt, "effectiveFrom", today,
                "materialCost", 10, "convertMethod", true));
        String stdId = draft.get("id").asString();
        ok(200, "POST", "/inventory/standard-costs/" + stdId + "/approve", null);
        keyedOk("POST", "/inventory/standard-costs/" + stdId + "/activate", null);
        String grn = receive(bolt, 40, 10);
        issue(bolt, 10);
        BigDecimal cogs0 = balance(mapped("cogs"));
        String handling = jdbc.sql("SELECT id FROM \"CostComponent\" WHERE \"organizationId\" = ? AND code = 'HANDLING'")
                .param(org).query(String.class).optional().orElse(null);
        if (handling == null) {
            ok(200, "GET", "/procurement/cost-components", null);
            handling = jdbc.sql("SELECT id FROM \"CostComponent\" WHERE \"organizationId\" = ? AND code = 'HANDLING'")
                    .param(org).query(String.class).single();
        }
        JsonNode voucher = keyedOk("POST", "/procurement/landed-cost-vouchers", Map.of("goodsReceiptIds", List.of(grn),
                "charges", List.of(Map.of("costComponentId", handling, "amount", 80))));
        String id = voucher.get("id").asString();
        ok(200, "POST", "/procurement/landed-cost-vouchers/" + id + "/submit", null);
        ok(200, "POST", "/procurement/landed-cost-vouchers/" + id + "/approve", null);
        keyedOk("POST", "/procurement/landed-cost-vouchers/" + id + "/post", null);

        String adjunct = jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key ="
                + " 'inventory_price_difference'").param(org).query(String.class).single();
        // 30 of 40 still on hand → 60 to the adjunct, 10 sold → 20 to COGS; the standard stays 10.
        assertThat(balance(adjunct)).isEqualByComparingTo("60");
        assertThat(balance(mapped("cogs")).subtract(cogs0)).isEqualByComparingTo("20");
        assertThat(jdbc.sql("SELECT \"costPrice\" FROM \"Product\" WHERE id = ?").param(bolt).query(BigDecimal.class)
                .single()).isEqualByComparingTo("10");
        assertTiesOut();

        // Sell 15 more and release: half of the open 60 goes to COGS.
        issue(bolt, 15);
        keyedOk("POST", "/inventory/price-differences/release", null);
        assertThat(balance(adjunct)).isEqualByComparingTo("30");
        assertTiesOut();

        // Reverse the voucher: the open 30 leaves the adjunct; the released 30 stays in COGS.
        keyedOk("POST", "/procurement/landed-cost-vouchers/" + id + "/reverse", null);
        assertThat(balance(adjunct)).isEqualByComparingTo("0");
        assertTiesOut();
    }

    @Test
    @Order(5)
    @DisplayName("authorization: every route needs its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(CostLineageController.class)).isGreaterThan(5);
    }
}
