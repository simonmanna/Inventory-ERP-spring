package com.inventoryerp.plm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Before-merge fixes from the PLM audit (2026-11-24): {@code costPolicy=activate} needs {@code standard_cost:approve};
 * an ECO disposition may recreate a not-started order of a product being phased out.
 */
class PlmAuditFixesIT extends PlmTestSupport {

    static final String PLM = "/manufacturing/plm";

    @AfterEach
    void shippedDefaults() {
        jdbc.sql("DELETE FROM \"PlmSettings\" WHERE \"organizationId\" = ?").param(demoOrg).update();
    }

    String register(String productId, String state) {
        return ok(201, "POST", PLM + "/items", Map.of("productId", productId, "lifecycleState", state))
                .get("id").asString();
    }

    String standardProduct(String code, int cost) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                        + " \"costPrice\", \"salesPrice\", \"manufacturingRole\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', 'STANDARD', ?, 30, 'finished', now())")
                .params(id, demoOrg, code + "-" + suffix(), code + " " + suffix(), cost).update();
        return id;
    }

    JsonNode activeBom(String output, String component) {
        JsonNode draft = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Recipe " + suffix(),
                "outputProductId", output, "outputQuantity", 1, "lines", List.of(Map.of("componentProductId",
                        component, "quantity", 2))));
        return ok(201, "POST", "/manufacturing/boms/" + draft.get("id").asString() + "/activate", null);
    }

    /** A DEMO user with every permission of the administrator except {@code standard_cost:approve}. */
    String engineerWithoutApproval() {
        String[] perms = jdbc.sql("SELECT r.permissions FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id"
                + " WHERE ur.\"B\" = ? LIMIT 1").param(adminId).query((rs, i) -> (String[]) rs.getArray(1).getArray())
                .single();
        List<String> kept = new ArrayList<>(Arrays.asList(perms));
        kept.remove("standard_cost:approve");
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Engineer " + role,
                        kept.toArray(String[]::new)).update();
        String email = "plm-engineer-" + suffix() + "@demo.test";
        createUser(demoOrg, email, "Engineer@12345", role);
        return login("DEMO", email, "Engineer@12345");
    }

    JsonNode approve(String ecoId) {
        JsonNode detail = ok(200, "GET", PLM + "/changes/" + ecoId, null);
        String review = null;
        for (JsonNode s : detail.get("stages")) {
            if (s.get("requiresApproval").asBoolean()) {
                review = s.get("id").asString();
                break;
            }
        }
        ok(201, "POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId", review));
        return okAs(approver(), 201, "POST", PLM + "/changes/" + ecoId + "/decisions", Map.of("decision", "approved"));
    }

    @Test
    @DisplayName("costPolicy activate needs standard_cost:approve: refused when named, a default activate becomes"
            + " propose, and an applier without it leaves the standard a draft")
    void activateNeedsApproval() {
        String engineer = engineerWithoutApproval();
        String flour = product("AFLOUR");
        stockIn(bakery, flour, 50, 2);
        String cake = standardProduct("ACAKE", 10);
        JsonNode v1 = activeBom(cake, flour);
        register(cake, "released");

        Res named = call("POST", PLM + "/changes", Map.of("type", "bom", "bomId", v1.get("id").asString(),
                "costPolicy", "activate", "stockDisposition", "none"), Map.of("Authorization", "Bearer " + engineer));
        assertThat(named.status()).isEqualTo(403);
        assertThat(named.body().get("message").asString()).isEqualTo("costPolicy activate approves and activates a"
                + " standard cost; it needs the standard_cost:approve permission (use propose).");

        // The organization default activate is fitted to the user: propose.
        ok(200, "PUT", PLM + "/settings", Map.of("defaultCostPolicy", "activate"));
        JsonNode defaulted = okAs(engineer, 201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId",
                v1.get("id").asString(), "stockDisposition", "none"));
        assertThat(defaulted.get("costPolicy").asString()).isEqualTo("propose");
        ok(201, "POST", PLM + "/changes/" + defaulted.get("id").asString() + "/cancel", Map.of("reason", "test"));

        // Named by an administrator, applied by the engineer: the standard waits for someone who may approve it.
        JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", v1.get("id").asString(),
                "costPolicy", "activate", "stockDisposition", "none"));
        String ecoId = eco.get("id").asString();
        approve(ecoId);
        JsonNode applied = okAs(engineer, 201, "POST", PLM + "/changes/" + ecoId + "/apply", null);
        assertThat(applied.get("status").asString()).isEqualTo("applied");
        assertThat(jdbc.sql("SELECT status FROM \"ProductStandardCost\" WHERE id = ?")
                .param(applied.get("standardCostId").asString()).query(String.class).single()).isEqualTo("draft");
        assertThat(applied.get("costResult").get("note").asString()).startsWith("The user applying the change may not"
                + " approve standard cost");
        assertThat(applied.get("revaluationJournalId").isNull()).isTrue();
    }

    @Test
    @DisplayName("phase-out: a new production order is refused, but an ECO disposition may recreate a not-started one"
            + " on the new version")
    void phaseOutReplacement() {
        String old = product("POLD");
        String kept = product("PKEEP");
        String neu = product("PNEW");
        String out = product("POUT");
        stockIn(bakery, old, 10, 2);
        stockIn(bakery, kept, 10, 1);
        JsonNode bom = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Disp " + suffix(), "outputProductId", out,
                "outputQuantity", 1, "lines", List.of(Map.of("componentProductId", old, "quantity", 1),
                        Map.of("componentProductId", kept, "quantity", 1))));
        String bomId = bom.get("id").asString();
        ok(201, "POST", "/manufacturing/boms/" + bomId + "/activate", null);
        String item = register(out, "released");
        JsonNode order = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1,
                "locationId", bakery, "outputLocationId", warehouse, "scheduledFor", "2030-01-15"));
        ok(201, "POST", PLM + "/items/" + item + "/transition", Map.of("toState", "phase_out"));
        Res fresh = as("POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1, "locationId",
                bakery, "outputLocationId", warehouse));
        assertThat(fresh.status()).isEqualTo(400);
        assertThat(fresh.body().get("message").asString()).contains("phase out in its lifecycle and cannot be produced");

        Map<String, Object> body = new HashMap<>(Map.of("type", "bom", "bomId", bomId, "stockDisposition", "none",
                "wipDisposition", "rework"));
        JsonNode eco = ok(201, "POST", PLM + "/changes", body);
        ok(200, "PATCH", "/manufacturing/boms/" + eco.get("newBomId").asString(), Map.of("lines", List.of(
                Map.of("componentProductId", kept, "quantity", 1), Map.of("componentProductId", neu, "quantity", 1))));
        okAs(approver(), 201, "POST", "/manufacturing/engineering-changes/" + eco.get("id").asString() + "/approve",
                null);
        JsonNode applied = ok(201, "POST", PLM + "/changes/" + eco.get("id").asString() + "/apply", null);
        assertThat(applied.get("status").asString()).isEqualTo("applied");
        JsonNode replaced = applied.get("dispositionResult").get("replacedOrders");
        assertThat(replaced).hasSize(1);
        assertThat(replaced.get(0).get("fromId").asString()).isEqualTo(order.get("id").asString());
        assertThat(ok(200, "GET", "/manufacturing/production-orders/" + replaced.get(0).get("toId").asString(), null)
                .get("bomId").asString()).isEqualTo(eco.get("newBomId").asString());
    }
}
