package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.BooksGuard;
import com.inventoryerp.TestSuppliers;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

/**
 * Readiness gaps h-02 (inventory valuation), stream gC: one line per pool in a revaluation / NRV document (F3), a
 * reversal that would overwrite a later value change is refused (F4), maker-checker and the offset-account policy on
 * value changes, and policy edits only while a draft (F5), a standard that can never activate is refused at approval
 * (F7), and the posted amount at the ledger's 6-decimal precision (F9).
 */
@ExtendWith(BooksGuard.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValuationGapsIT extends ApiTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    javax.sql.DataSource dataSource;

    String org;
    String code;
    String token;
    String userId;
    String location;
    String today = LocalDate.now(ZoneOffset.UTC).toString();

    @BeforeAll
    void books() {
        code = "vg-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Valuation Gaps Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner",
                "adminPassword", "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        org = boot.body().get("organization").get("id").asString();
        userId = boot.body().get("adminUser").get("id").asString();
        token = login(code, "owner@" + code + ".test", "Owner@12345");
        inventorySodPairsOff(token);
        location = ok(201, "POST", "/inventory/locations", Map.of("code", "WH-1", "name", "Main", "type",
                "warehouse")).get("id").asString();
    }

    @Override
    protected JsonNode ok(int status, String method, String path, Object body) {
        Res r = call(method, path, body, token);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    Res keyed(String method, String path, Object body, String as) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + as);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    JsonNode keyedOk(String method, String path, Object body) {
        Res r = keyed(method, path, body, token);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    /** A second user of this organisation holding {@code permissions}. */
    String userWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, org, "Checker " + role, permissions).update();
        String email = "checker-" + role.substring(0, 8) + "@" + code + ".test";
        createUser(org, email, "Checker@12345", role);
        return login(code, email, "Checker@12345");
    }

    void sod(boolean on) {
        ok(200, "PUT", "/approval-controls/sod-rules/" + SegregationOfDutiesService.INVENTORY_VALUATION,
                Map.of("isActive", on));
    }

    String product(String name, String method) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                + " \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', ?::\"CostingMethod\", 0,"
                + " 20, now())").params(id, org, name.toUpperCase() + "-" + id.substring(0, 4), name, method).update();
        return id;
    }

    void receive(String productId, Object qty, Object unitCost) {
        ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", TestSuppliers.supplier(jdbc, org),
                "warehouseId", location, "receivedAt", Instant.now().toString(), "lines", List.of(Map.of("productId",
                        productId, "description", "Goods", "quantity", qty, "unitCost", unitCost))));
    }

    String revaluation(String kind, List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("date", today);
        body.put("reason", "Gaps");
        body.put("lines", lines);
        return ok(201, "POST", "/inventory/revaluations", body).get("id").asString();
    }

    Map<String, Object> line(String productId, String field, Object value) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("productId", productId);
        l.put("locationId", location);
        l.put(field, value);
        return l;
    }

    BigDecimal avg(String productId) {
        return jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, location).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("h-02 F3: a revaluation or NRV document lists each pool once; a duplicate is refused with a 400")
    void duplicatePoolLines() {
        String p = product("Gravel", "AVCO");
        receive(p, 100, 10);
        for (String kind : List.of("revaluation", "nrv")) {
            String field = "nrv".equals(kind) ? "netRealisableValue" : "unitCostAfter";
            Res r = call("POST", "/inventory/revaluations", Map.of("kind", kind, "date", today, "lines",
                    List.of(line(p, field, 6), line(p, field, 7))), token);
            assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
            assertThat(r.body().get("message").asString()).contains("is listed more than once at the same location");
        }
    }

    @Test
    @DisplayName("h-02 F4: reversing a revaluation after a later value change is refused, not overwritten")
    void reverseAfterLaterValueChange() {
        String p = product("Lime", "AVCO");
        receive(p, 50, 10);
        String first = revaluation("revaluation", List.of(line(p, "unitCostAfter", 12)));
        keyedOk("POST", "/inventory/revaluations/" + first + "/post", null);
        String second = revaluation("revaluation", List.of(line(p, "unitCostAfter", 13)));
        keyedOk("POST", "/inventory/revaluations/" + second + "/post", null);
        assertThat(avg(p)).isEqualByComparingTo("13");

        Res refused = keyed("POST", "/inventory/revaluations/" + first + "/reverse", null, token);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).contains("has changed since the revaluation");
        assertThat(avg(p)).isEqualByComparingTo("13");

        // The latest one still reverses, back to 12.
        keyedOk("POST", "/inventory/revaluations/" + second + "/reverse", null);
        assertThat(avg(p)).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("h-02 F5: with the inventory_valuation pair on, the creator cannot post; another user can")
    void makerChecker() {
        String p = product("Clay", "AVCO");
        receive(p, 20, 10);
        String id = revaluation("revaluation", List.of(line(p, "unitCostAfter", 11)));
        sod(true);
        try {
            Res blocked = keyed("POST", "/inventory/revaluations/" + id + "/post", null, token);
            assertThat(blocked.status()).as(blocked.raw().body()).isEqualTo(403);
            assertThat(blocked.body().get("message").asString()).startsWith("Separation of duties");

            String checker = userWith("inventory_valuation:read", "inventory_valuation:post");
            Res posted = keyed("POST", "/inventory/revaluations/" + id + "/post", null, checker);
            assertThat(posted.status()).as(posted.raw().body()).isIn(200, 201);
            assertThat(posted.body().get("status").asString()).isEqualTo("posted");
        } finally {
            sod(false);
        }
    }

    @Test
    @DisplayName("h-02 F5: a cash or bank offset account is refused; the stock value is not moved")
    void offsetAccountPolicy() {
        String bank = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.\"isCashEquivalent\" AND a.\"isActive\" LIMIT 1").param(org)
                .query(String.class).optional().orElse(null);
        Assumptions.assumeTrue(bank != null, "chart of accounts has a cash account");
        String p = product("Shale", "AVCO");
        receive(p, 10, 10);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "revaluation");
        body.put("date", today);
        body.put("accountId", bank);
        body.put("lines", List.of(line(p, "unitCostAfter", 15)));
        Res created = call("POST", "/inventory/revaluations", body, token);
        if (created.status() == 400) {
            assertThat(created.body().get("message").asString()).contains("cannot offset a stock value change");
        } else {
            assertThat(created.status()).as(created.raw().body()).isEqualTo(201);
            Res post = keyed("POST", "/inventory/revaluations/" + created.body().get("id").asString() + "/post", null,
                    token);
            assertThat(post.status()).as(post.raw().body()).isEqualTo(400);
            assertThat(post.body().get("message").asString()).contains("cannot offset a stock value change");
        }
        assertThat(avg(p)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("h-02 F5: a cost policy is edited only while it is a draft")
    void policyEditOnlyWhileDraft() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", "organization");
        body.put("method", "FIFO");
        body.put("effectiveFrom", LocalDate.now(ZoneOffset.UTC).plusDays(30).toString());
        String id = ok(201, "POST", "/inventory/cost-policies", body).get("id").asString();
        ok(200, "POST", "/inventory/cost-policies/" + id + "/approve", null);
        Res edit = call("PATCH", "/inventory/cost-policies/" + id, Map.of("notes", "late edit"), token);
        assertThat(edit.status()).as(edit.raw().body()).isEqualTo(400);
        ok(200, "POST", "/inventory/cost-policies/" + id + "/cancel", null);
    }

    @Test
    @DisplayName("h-02 F7: a standard for an AVCO item without convertMethod is refused at approval")
    void standardWithoutConvertMethod() {
        String p = product("Basalt", "AVCO");
        String id = ok(201, "POST", "/inventory/standard-costs", Map.of("productId", p, "effectiveFrom", today,
                "materialCost", 12)).get("id").asString();
        Res approve = call("POST", "/inventory/standard-costs/" + id + "/approve", null, token);
        assertThat(approve.status()).as(approve.raw().body()).isEqualTo(400);
        assertThat(approve.body().get("message").asString()).contains("set convertMethod to switch it to standard");
    }

    String standard(String productId, Object cost) {
        String id = ok(201, "POST", "/inventory/standard-costs", Map.of("productId", productId, "effectiveFrom", today,
                "materialCost", cost, "convertMethod", true)).get("id").asString();
        ok(200, "POST", "/inventory/standard-costs/" + id + "/approve", null);
        return id;
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

    @Test
    @DisplayName("h-02 F2: a standard cost activation waits for a stock move of the product that is in flight")
    void activationWaitsForInFlightMove() throws Exception {
        String p = product("Granite", "AVCO");
        receive(p, 10, 10);
        String std = standard(p, 12);
        // A move in flight holds the product's costing key shared until it commits (what StockService takes).
        try (java.sql.Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT set_config('app.org_id', ?, true),"
                    + " pg_advisory_xact_lock_shared(hashtext(?)::bigint)")) {
                ps.setString(1, org);
                ps.setString(2, "costing:" + org + ":" + p);
                ps.executeQuery().close();
            }
            java.util.concurrent.CompletableFuture<Res> activation = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> keyed("POST", "/inventory/standard-costs/" + std + "/activate", null, token));
            Thread.sleep(1500);
            assertThat(activation).as("activation must wait for the move").isNotDone();
            c.commit();
            Res done = activation.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(done.status()).as(done.raw().body()).isIn(200, 201);
        }
        assertThat(avg(p)).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("h-02 F6: a standard cost activation re-costs on the item's own STOCK_IN account, not the mapping")
    void recostingUsesItemStockAccount() {
        String p = product("Marble", "AVCO");
        String sv = mapped("stock_valuation");
        String own = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Account\" (id, \"organizationId\", code, name, \"categoryId\", \"normalBalance\","
                + " \"reportSection\", \"updatedAt\") SELECT ?, \"organizationId\", ?, 'Marble stock', \"categoryId\","
                + " \"normalBalance\", \"reportSection\", now() FROM \"Account\" WHERE id = ?").params(own, "13" + UUID
                        .randomUUID().toString().substring(0, 4), sv).update();
        Map<String, Object> debit = new LinkedHashMap<>(Map.of("movementType", "STOCK_IN", "lineIndex", 0,
                "debitOrCredit", "debit", "accountSource", "literal", "literalAccountId", own, "productId", p));
        Map<String, Object> credit = new LinkedHashMap<>(Map.of("movementType", "STOCK_IN", "lineIndex", 1,
                "debitOrCredit", "credit", "accountSource", "account_mapping", "accountMappingKey", "grni_accrued",
                "productId", p));
        ok(201, "POST", "/inventory/posting-rules", debit);
        ok(201, "POST", "/inventory/posting-rules", credit);
        receive(p, 10, 10);
        assertThat(balance(own)).isEqualByComparingTo("100");
        BigDecimal sv0 = balance(sv);

        keyedOk("POST", "/inventory/standard-costs/" + standard(p, 12) + "/activate", null);
        assertThat(balance(own)).isEqualByComparingTo("120");
        assertThat(balance(sv)).isEqualByComparingTo(sv0);
    }

    @Test
    @DisplayName("h-02 F7: a cost policy cannot take effect on a day the books are locked")
    void policyEffectiveDateMustBeOpen() {
        jdbc.sql("UPDATE \"Organization\" SET \"booksLockDate\" = now() - interval '1 day' WHERE id = ?").param(org)
                .update();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("scopeType", "organization");
            body.put("method", "FIFO");
            body.put("effectiveFrom", LocalDate.now(ZoneOffset.UTC).minusDays(5).toString());
            Res r = call("POST", "/inventory/cost-policies", body, token);
            assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
            assertThat(r.body().get("message").asString()).startsWith("The cost policy cannot take effect on")
                    .endsWith("Choose an effective date in an open period");
        } finally {
            jdbc.sql("UPDATE \"Organization\" SET \"booksLockDate\" = NULL WHERE id = ?").param(org).update();
        }
    }

    @Test
    @DisplayName("h-02 F9: the revaluation amount keeps the ledger's 6-decimal precision")
    void sixDecimalAmount() {
        String p = product("Quartz", "AVCO");
        receive(p, 3, 10);
        String id = revaluation("revaluation", List.of(line(p, "unitCostAfter", new BigDecimal("10.333333"))));
        JsonNode posted = keyedOk("POST", "/inventory/revaluations/" + id + "/post", null);
        // 3 × 0.333333 = 0.999999: two decimals would book 1.00 while the quant moved 0.999999.
        assertThat(new BigDecimal(posted.get("totalAmount").asString())).isEqualByComparingTo("0.999999");
    }
}
