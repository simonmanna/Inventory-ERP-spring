package com.inventoryerp.costing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.costing.web.CostingSettingsController;
import com.inventoryerp.kernel.security.Permissions;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

/**
 * Cost Management CM-2 (ADR-S032): Costing &amp; Valuation settings façade, governed policy elections (forbidden
 * choices, prospective dates, maker-checker), cost eligibility, landed-cost switch, branch dimension enforcement.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CostingSettingsIT extends ApiTestSupport {

    String org;
    String code;
    String token;
    String checkerToken;
    String userId;
    String location;
    String today = LocalDate.now(ZoneOffset.UTC).toString();

    @BeforeAll
    void books() {
        code = "cs-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Settings Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
                "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        org = boot.body().get("organization").get("id").asString();
        userId = boot.body().get("adminUser").get("id").asString();
        token = login(code, "owner@" + code + ".test", "Owner@12345");
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\") VALUES"
                + " (?, ?, 'Controller', false, ?, now())").params(role, org, Permissions.ALL.toArray(String[]::new))
                .update();
        createUser(org, "checker@" + code + ".test", "Checker@12345", role);
        checkerToken = login(code, "checker@" + code + ".test", "Checker@12345");
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

    String message(Res r) {
        return r.body().get("message").asString();
    }

    Map<String, Object> proposal(String election, String value, String justification, String effectiveFrom) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("election", election);
        m.put("value", value);
        m.put("justification", justification);
        if (effectiveFrom != null) {
            m.put("effectiveFrom", effectiveFrom);
        }
        return m;
    }

    @Test
    @Order(1)
    @DisplayName("settings: one page over the owning stores; landed costs switch blocks new vouchers")
    void settingsFacade() {
        JsonNode s = ok(200, "GET", "/costing/settings", null);
        assertThat(s.get("landedCosts").get("enabled").asBoolean()).isTrue();
        assertThat(s.get("valuation").get("defaultCostingMethod").asString()).isEqualTo("AVCO");
        JsonNode duty = null;
        for (JsonNode c : s.get("landedCosts").get("components")) {
            if ("DUTY".equals(c.get("code").asString())) {
                duty = c;
            }
        }
        assertThat(duty).isNotNull();
        assertThat(duty.get("eligibility").asString()).isEqualTo("acquisition_capitalisable");
        assertThat(s.get("accounts")).isNotEmpty();
        assertThat(s.get("policies")).isNotEmpty();

        JsonNode ensured = ok(200, "POST", "/costing/settings/accounts/ensure", null);
        boolean priceDiff = false;
        for (JsonNode a : ensured.get("accounts")) {
            if ("inventory_price_difference".equals(a.get("key").asString()) && !a.get("accountId").isNull()) {
                priceDiff = true;
            }
        }
        assertThat(priceDiff).isTrue();

        assertThat(call("PUT", "/costing/settings", Map.of("defaultAllocationMethod", "pallets"), token).status())
                .isEqualTo(400);
        JsonNode off = ok(200, "PUT", "/costing/settings", Map.of("landedCostEnabled", false, "defaultCostingMethod",
                "FIFO"));
        assertThat(off.get("landedCosts").get("enabled").asBoolean()).isFalse();
        assertThat(off.get("valuation").get("defaultCostingMethod").asString()).isEqualTo("FIFO");
        // Single source of truth: the generic settings registry sees the same value.
        assertThat(ok(200, "GET", "/settings/inventory-defaults", null).toString()).contains("FIFO");
        Res blocked = keyed("POST", "/procurement/landed-cost-vouchers", Map.of("goodsReceiptIds", List.of(
                UUID.randomUUID().toString()), "charges", List.of(Map.of("kind", "freight", "amount", 10))));
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(message(blocked)).isEqualTo("Landed costs are switched off in Costing & Valuation settings");
        ok(200, "PUT", "/costing/settings", Map.of("landedCostEnabled", true, "defaultCostingMethod", "AVCO"));
    }

    @Test
    @Order(2)
    @DisplayName("policies: forbidden choices refused, prospective only, justification, maker-checker")
    void governance() {
        Res lifo = call("POST", "/costing/policies", proposal("cost_formula", "LIFO", "Inflation hedge for tax",
                null), token);
        assertThat(lifo.status()).isEqualTo(400);
        assertThat(message(lifo)).contains("LIFO is not permitted");
        assertThat(message(call("POST", "/costing/policies", proposal("stripping_cost", "stripping_asset",
                "Waste removal benefits next benches", null), token))).contains("IFRIC 20 assessment");
        assertThat(message(call("POST", "/costing/policies", proposal("project_revenue_recognition",
                "over_time_cost_to_cost", "Long build contracts", null), token))).contains("IFRS 15.35");
        assertThat(message(call("POST", "/costing/policies", proposal("variance_posting", "summary", "short", null),
                token))).startsWith("Give a justification");
        assertThat(message(call("POST", "/costing/policies", proposal("variance_posting", "summary",
                "Management reporting needs one line", "2020-01-01"), token))).contains("prospectively");
        assertThat(message(call("POST", "/costing/policies", proposal("price_difference_tolerance_pct", "80",
                "Volatile cement prices", null), token))).isEqualTo("value must be between 0 and 50");

        JsonNode p = ok(201, "POST", "/costing/policies", proposal("variance_posting", "summary",
                "Management reporting needs one line", today));
        String id = p.get("id").asString();
        assertThat(p.get("status").asString()).isEqualTo("proposed");
        Res self = call("POST", "/costing/policies/" + id + "/approve", null, token);
        assertThat(self.status()).isEqualTo(403);
        assertThat(message(self)).isEqualTo("A policy change must be approved by someone other than its proposer");
        Res approved = call("POST", "/costing/policies/" + id + "/approve", null, checkerToken);
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(200);
        assertThat(approved.body().get("inForce").asBoolean()).isTrue();
        String current = null;
        for (JsonNode e : ok(200, "GET", "/costing/policies/catalogue", null)) {
            if ("variance_posting".equals(e.get("key").asString())) {
                current = e.get("current").asString();
            }
        }
        assertThat(current).isEqualTo("summary");
        assertThat(call("GET", "/costing/policies/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/costing/policies/" + id + "/approve", null, checkerToken).status()).isEqualTo(400);
    }

    @Test
    @Order(3)
    @DisplayName("eligibility: a component classified as storage can never be capitalised (IAS 2.16)")
    void eligibility() {
        JsonNode comp = ok(201, "POST", "/procurement/cost-components", Map.of("code", "YARD", "name", "Yard storage",
                "kind", "other", "treatment", "expense"));
        String compId = comp.get("id").asString();
        Map<String, Object> body = proposal("cost_eligibility", "storage", "Yard storage after receipt is not a"
                + " cost of bringing goods to their location", today);
        body.put("scopeType", "cost_component");
        body.put("scopeId", compId);
        String id = ok(201, "POST", "/costing/policies", body).get("id").asString();
        assertThat(call("POST", "/costing/policies/" + id + "/approve", null, checkerToken).status()).isEqualTo(200);
        Res refused = call("PATCH", "/procurement/cost-components/" + compId, Map.of("treatment", "capitalize"), token);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(message(refused)).isEqualTo("Cost component YARD is classified as storage; it cannot be"
                + " capitalised into inventory (IAS 2.16)");
        JsonNode e = ok(200, "GET", "/costing/eligibility", null);
        assertThat(e.get("classifications").toString()).contains(compId);
        assertThat(e.get("unclassifiedAccounts")).isNotEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("dimensions: enforce refuses a costing posting without a branch; compliance lists them in warn mode")
    void dimensions() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                + " \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, 'Gravel', 'stockable', 'AVCO', 0, 20,"
                + " now())").params(id, org, "GRV-" + id.substring(0, 4)).update();
        ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("warehouseId", location, "receivedAt",
                Instant.now().toString(), "lines", List.of(Map.of("productId", id, "description", "Gravel",
                        "quantity", 10, "unitCost", 5))));
        ok(200, "PUT", "/costing/settings", Map.of("dimensionEnforcement", "enforce"));
        String doc = ok(201, "POST", "/inventory/revaluations", Map.of("lines", List.of(Map.of("productId", id,
                "locationId", location, "unitCostAfter", 6)))).get("id").asString();
        Res refused = keyed("POST", "/inventory/revaluations/" + doc + "/post", null);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(message(refused)).startsWith("Costing postings need a branch on every line");
        ok(200, "PUT", "/costing/settings", Map.of("dimensionEnforcement", "warn"));
        Res posted = keyed("POST", "/inventory/revaluations/" + doc + "/post", null);
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        JsonNode c = ok(200, "GET", "/costing/dimension-compliance?from=" + today + "&to=" + today, null);
        assertThat(c.get("missingBranch").toString()).contains("inventory_revaluation");
    }

    @Test
    @Order(5)
    @DisplayName("authorization: every route needs its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(CostingSettingsController.class)).isGreaterThan(8);
    }
}
