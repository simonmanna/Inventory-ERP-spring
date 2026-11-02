package com.inventoryerp.costing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.costing.web.AdvancedCostingController;
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
 * PR-13 (ADR-S030): material-ledger run over a standard-costed product (PPV pool split between stock and consumption,
 * posting, reversal), variance analysis with GL tie-out, activity-based costing per customer, authorization.
 */
@ExtendWith(BooksGuard.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdvancedCostingIT extends ApiTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    com.inventoryerp.accounting.application.PostingService posting;

    String org;
    String token;
    String userId;
    String location;
    String today = LocalDate.now(ZoneOffset.UTC).toString();

    @BeforeAll
    void books() {
        String code = "ac-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Costing Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
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

    String product(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                + " \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', 'AVCO', 0, 20, now())")
                .params(id, org, name.toUpperCase() + "-" + id.substring(0, 4), name).update();
        return id;
    }

    String account(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(org, code)
                .query(String.class).single();
    }

    BigDecimal balance(String accountId) {
        return jdbc.sql("SELECT coalesce(sum(debit), 0) - coalesce(sum(credit), 0) FROM \"JournalLine\" WHERE"
                + " \"organizationId\" = ? AND \"accountId\" = ?").params(org, accountId).query(BigDecimal.class)
                .single();
    }

    String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?")
                .params(org, key).query(String.class).single();
    }

    @Test
    @Order(1)
    @DisplayName("material ledger: PPV pool split by remaining stock, posted to stock vs 5965, reversible")
    void materialLedger() {
        String bolt = product("Bolt");
        JsonNode draft = ok(201, "POST", "/inventory/standard-costs", Map.of("productId", bolt, "effectiveFrom", today,
                "materialCost", 10, "convertMethod", true));
        String stdId = draft.get("id").asString();
        ok(200, "POST", "/inventory/standard-costs/" + stdId + "/approve", null);
        assertThat(keyed("POST", "/inventory/standard-costs/" + stdId + "/activate", null).status()).isIn(200, 201);
        // 40 received at 13 against a standard of 10 → PPV +120; 25 consumed, 15 remain.
        ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("warehouseId", location, "receivedAt",
                Instant.now().toString(), "lines", List.of(Map.of("productId", bolt, "description", "Bolts", "quantity",
                        40, "unitCost", 13))));
        ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", location, "responsibleById", userId,
                "approvedById", userId, "items", List.of(Map.of("productId", bolt, "quantity", 25))));

        JsonNode run = ok(201, "POST", "/costing/material-ledger/runs", Map.of("from", today, "to", today));
        assertThat(run.get("status").asString()).isEqualTo("draft");
        JsonNode line = null;
        for (JsonNode l : run.get("lines")) {
            if (bolt.equals(l.get("productId").asString())) {
                line = l;
            }
        }
        assertThat(line).as(run.toString()).isNotNull();
        assertThat(new BigDecimal(line.get("periodVariance").asString())).isEqualByComparingTo("120");
        assertThat(new BigDecimal(line.get("endingQty").asString())).isEqualByComparingTo("15");
        assertThat(new BigDecimal(line.get("toInventory").asString())).isEqualByComparingTo("45");
        assertThat(new BigDecimal(line.get("toConsumption").asString())).isEqualByComparingTo("75");
        assertThat(new BigDecimal(line.get("periodicUnitCost").asString())).isEqualByComparingTo("13");

        String runId = run.get("id").asString();
        assertThat(call("POST", "/costing/material-ledger/runs", Map.of("from", today, "to", today), token).status())
                .isEqualTo(409);
        assertThat(call("POST", "/costing/material-ledger/runs/" + runId + "/post", null, token).status())
                .isEqualTo(400); // Idempotency-Key required
        BigDecimal stock0 = balance(mapped("stock_valuation"));
        Res posted = keyed("POST", "/costing/material-ledger/runs/" + runId + "/post", null);
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        assertThat(posted.body().get("status").asString()).isEqualTo("posted");
        assertThat(balance(mapped("stock_valuation")).subtract(stock0)).isEqualByComparingTo("45");
        assertThat(balance(account("5965"))).isEqualByComparingTo("-45");

        // The next run must follow on; a reversal restores the books.
        assertThat(call("POST", "/costing/material-ledger/runs", Map.of("from", today, "to", today), token).body()
                .get("message").asString()).startsWith("The next run must start on");
        Res reversed = keyed("POST", "/costing/material-ledger/runs/" + runId + "/reverse", null);
        assertThat(reversed.body().get("status").asString()).as(reversed.raw().body()).isEqualTo("reversed");
        assertThat(balance(mapped("stock_valuation")).subtract(stock0)).isEqualByComparingTo("0");
        assertThat(ok(200, "GET", "/costing/material-ledger/runs", null)).hasSize(1);
        assertThat(call("GET", "/costing/material-ledger/runs/" + runId, null, otherToken).status()).isEqualTo(404);

        JsonNode again = ok(201, "POST", "/costing/material-ledger/runs", Map.of("from", today, "to", today));
        ok(200, "DELETE", "/costing/material-ledger/runs/" + again.get("id").asString(), null);
        assertThat(call("POST", "/costing/material-ledger/runs", Map.of("from", today, "to", "2999-01-01"), token)
                .body().get("message").asString()).isEqualTo("A run cannot end in the future");
    }

    @Test
    @Order(2)
    @DisplayName("variance analysis: booked variances by kind and product, GL tie-out")
    void varianceAnalysis() {
        JsonNode a = ok(200, "GET", "/costing/variance-analysis?from=" + today + "&to=" + today, null);
        JsonNode ppv = null;
        for (JsonNode k : a.get("byKind")) {
            if ("purchase_price".equals(k.get("kind").asString())) {
                ppv = k;
            }
        }
        assertThat(ppv).as(a.toString()).isNotNull();
        assertThat(new BigDecimal(ppv.get("unfavourable").asString())).isEqualByComparingTo("120");
        assertThat(new BigDecimal(ppv.get("percentOfStandard").asString())).isEqualByComparingTo("30");
        assertThat(a.get("byProduct").get(0).get("name").asString()).isEqualTo("Bolt");
        assertThat(new BigDecimal(a.get("gl").get("difference").asString())).isEqualByComparingTo("0");
        assertThat(a.get("production").has("totals")).isTrue();
        assertThat(call("GET", "/costing/variance-analysis?from=2026-02-01&to=2026-01-01", null, token).status())
                .isEqualTo(400);
    }

    @Test
    @Order(3)
    @DisplayName("activity-based costing: pool from GL, driver volumes, cost per customer and margin")
    void activityBasedCosting() {
        String expense = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.\"reportSection\"::text = 'operating_expense' AND a.\"isPostable\""
                + " AND a.\"deletedAt\" IS NULL ORDER BY a.code LIMIT 1").param(org).query(String.class).single();
        String revenue = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.\"reportSection\"::text = 'revenue' AND a.\"isPostable\" AND"
                + " a.\"deletedAt\" IS NULL ORDER BY a.code LIMIT 1").param(org).query(String.class).single();
        // 300 of billing cost in the period (manual journal: Dr expense / Cr cash).
        String cash = account("1100");
        com.inventoryerp.kernel.tenancy.TenantContext.run(
                com.inventoryerp.kernel.tenancy.TenantContext.TenantStore.user(org, userId),
                () -> new org.springframework.transaction.support.TransactionTemplate(txm).execute(st -> posting.post(
                        com.inventoryerp.accounting.application.PostingRequest.of("GEN", Instant.now(), "Billing team",
                                "test", UUID.randomUUID().toString(), null, List.of(
                                        com.inventoryerp.accounting.application.PostingRequest.Line.debit(expense, 300,
                                                "Billing"),
                                        com.inventoryerp.accounting.application.PostingRequest.Line.credit(cash, 300,
                                                "Billing"))))));

        String a = partner("Alpha");
        String b = partner("Beta");
        invoice(a, revenue, 1000);
        invoice(a, revenue, 500);
        invoice(b, revenue, 200);

        assertThat(call("POST", "/costing/abc/activities", Map.of("code", "BILL", "name", "Billing", "costObject",
                "customer", "driver", "units_sold", "accountIds", List.of(expense)), token).body().get("message")
                .asString()).startsWith("driver must be one of the following values: sales_invoices");
        ok(201, "POST", "/costing/abc/activities", Map.of("code", "BILL", "name", "Billing", "costObject", "customer",
                "driver", "sales_invoices", "accountIds", List.of(expense), "poolPercent", 50));
        assertThat(call("POST", "/costing/abc/runs", Map.of("costObject", "product", "from", today, "to", today),
                token).body().get("message").asString()).isEqualTo("Define at least one active product activity first");

        JsonNode run = ok(201, "POST", "/costing/abc/runs", Map.of("costObject", "customer", "from", today, "to", today));
        assertThat(new BigDecimal(run.get("poolTotal").asString())).isEqualByComparingTo("150");
        assertThat(run.get("activities").get(0).get("driverTotal").asDouble()).isEqualTo(3.0);
        JsonNode alpha = null;
        JsonNode beta = null;
        for (JsonNode r : run.get("results")) {
            if (a.equals(r.get("objectId").asString())) {
                alpha = r;
            } else if (b.equals(r.get("objectId").asString())) {
                beta = r;
            }
        }
        assertThat(new BigDecimal(alpha.get("activityCost").asString())).isEqualByComparingTo("100");
        assertThat(new BigDecimal(alpha.get("revenue").asString())).isEqualByComparingTo("1500");
        assertThat(new BigDecimal(alpha.get("margin").asString())).isEqualByComparingTo("1400");
        assertThat(new BigDecimal(beta.get("activityCost").asString())).isEqualByComparingTo("50");
        assertThat(ok(200, "GET", "/costing/abc/runs", null)).hasSize(1);
        assertThat(ok(200, "GET", "/costing/abc/drivers", null).get("product")).hasSize(4);
    }

    @Test
    @Order(4)
    @DisplayName("CM-0: an ABC pool filtered on a parent cost centre includes its descendants")
    void abcCostCentreRollup() {
        String expense = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.\"reportSection\"::text = 'operating_expense' AND a.\"isPostable\""
                + " AND a.\"deletedAt\" IS NULL ORDER BY a.code DESC LIMIT 1").param(org).query(String.class).single();
        String parent = UUID.randomUUID().toString();
        String child = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostCenter\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, 'OPS',"
                + " 'Operations', now())").params(parent, org).update();
        jdbc.sql("INSERT INTO \"CostCenter\" (id, \"organizationId\", code, name, \"parentId\", \"updatedAt\") VALUES"
                + " (?, ?, 'OPS-DISP', 'Dispatch', ?, now())").params(child, org, parent).update();
        String cash = account("1100");
        com.inventoryerp.kernel.tenancy.TenantContext.run(
                com.inventoryerp.kernel.tenancy.TenantContext.TenantStore.user(org, userId),
                () -> new org.springframework.transaction.support.TransactionTemplate(txm).execute(st -> posting.post(
                        com.inventoryerp.accounting.application.PostingRequest.of("GEN", Instant.now(), "Dispatch",
                                "test", UUID.randomUUID().toString(), null, List.of(
                                        new com.inventoryerp.accounting.application.PostingRequest.Line(expense, 80,
                                                null, null, "Dispatch", null, child, null),
                                        com.inventoryerp.accounting.application.PostingRequest.Line.credit(cash, 80,
                                                "Dispatch"))))));
        ok(201, "POST", "/costing/abc/activities", Map.of("code", "DISP", "name", "Dispatch", "costObject", "customer",
                "driver", "sales_invoices", "accountIds", List.of(expense), "costCenterId", parent));
        jdbc.sql("UPDATE \"AbcActivity\" SET \"isActive\" = false WHERE \"organizationId\" = ? AND code = 'BILL'")
                .param(org).update();
        JsonNode run = ok(201, "POST", "/costing/abc/runs", Map.of("costObject", "customer", "from", today, "to", today));
        assertThat(new BigDecimal(run.get("poolTotal").asString())).isEqualByComparingTo("80");
    }

    String partner(String name) {
        return ok(201, "POST", "/partners", Map.of("code", name.toUpperCase() + "-" + UUID.randomUUID().toString()
                .substring(0, 4), "name", name, "isCustomer", true)).get("id").asString();
    }

    void invoice(String partnerId, String revenue, int amount) {
        JsonNode draft = ok(200, "POST", "/invoices", Map.of("partnerId", partnerId, "issueDate", today, "lines",
                List.of(Map.of("accountId", revenue, "description", "Service", "quantity", 1, "unitPrice", amount))));
        Res post = keyed("POST", "/invoices/" + draft.get("id").asString() + "/post", null);
        assertThat(post.status()).as(post.raw().body()).isIn(200, 201);
    }

    @Test
    @Order(4)
    @DisplayName("authorization: every advanced-costing route needs a token and its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(AdvancedCostingController.class)).isEqualTo(16);
    }
}
