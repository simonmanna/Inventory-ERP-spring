package com.inventoryerp.plm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * PLM as its own application (ADR-S086), exercised on the suite's second organization so DEMO's module set is never
 * touched: on by default where manufacturing is on, switched off per organization on the Modules routes (its routes
 * then answer 403 and ADR-S006 change orders fall back to their original workflow), and switched on again.
 */
class PlmModuleIT extends ApiTestSupport {

    static final String PLM = "/manufacturing/plm";

    Res other(String method, String path, Object body) {
        return call(method, path, body, otherToken);
    }

    /** A second administrator of the second organization (the approver of its change orders). */
    String secondOtherAdmin() {
        String role = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'"
                + " LIMIT 1").param(otherOrg).query(String.class).single();
        String email = "approver-" + UUID.randomUUID().toString().substring(0, 8) + "@other.test";
        createUser(otherOrg, email, "Other@12345", role);
        return login(otherOrgCode, email, "Other@12345");
    }

    /** A product of the second organization (finished good or component). */
    String otherProduct(String code) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                        + " \"costPrice\", \"salesPrice\", \"manufacturingRole\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', 'AVCO', 1, 15, 'finished', now())")
                .params(id, otherOrg, code + "-" + id.substring(0, 6), code).update();
        return id;
    }

    @Test
    @DisplayName("catalog: manufacturing and its add-on plm (depends on manufacturing)")
    void catalog() {
        JsonNode catalog = ok(200, "GET", "/feature-flags/modules/catalog", null);
        JsonNode plm = null;
        boolean manufacturing = false;
        for (JsonNode m : catalog) {
            if ("plm".equals(m.get("name").asString())) {
                plm = m;
            }
            manufacturing |= "manufacturing".equals(m.get("name").asString());
        }
        assertThat(manufacturing).isTrue();
        assertThat(plm).isNotNull();
        assertThat(plm.get("dependencies").toString()).isEqualTo("[\"manufacturing\"]");
    }

    @Test
    @DisplayName("on by default; switched off → 403 and basic ADR-S006 workflow; switched on again → back")
    void switchOffAndOn() {
        // On by default (an add-on of manufacturing, which the deployment flag turns on).
        assertThat(other("GET", PLM + "/overview", null).status()).isEqualTo(200);

        // A BOM in the second organization, revised while PLM is on: the ECO gets PLM's first stage.
        String output = otherProduct("PLMSW-OUT");
        String component = otherProduct("PLMSW-CMP");
        Res draft = other("POST", "/manufacturing/boms", Map.of("name", "Switch recipe", "outputProductId", output,
                "outputQuantity", 1, "lines", List.of(Map.of("componentProductId", component, "quantity", 2))));
        assertThat(draft.status()).isEqualTo(201);
        String bomId = draft.body().get("id").asString();
        assertThat(other("POST", "/manufacturing/boms/" + bomId + "/activate", null).status()).isEqualTo(201);
        Res revised = other("POST", "/manufacturing/boms/" + bomId + "/revise", Map.of());
        assertThat(revised.status()).isEqualTo(201);
        assertThat(revised.body().get("change").get("stageId").isNull()).isFalse();
        String withPlm = revised.body().get("change").get("id").asString();
        assertThat(other("POST", "/manufacturing/engineering-changes/" + withPlm + "/reject", null).status())
                .isEqualTo(201);

        // Switched off for this organization.
        assertThat(other("PATCH", "/feature-flags/modules/plm/disable", null).status()).isEqualTo(200);
        Res refused = other("GET", PLM + "/overview", null);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.body().get("message").asString())
                .isEqualTo("Module 'plm' is not enabled for this organization");
        // Manufacturing itself is untouched.
        assertThat(other("GET", "/manufacturing/boms", null).status()).isEqualTo(200);

        // ADR-S006 change orders without PLM: no stage, approve → approved, apply → applied (new version active).
        Res basic = other("POST", "/manufacturing/boms/" + bomId + "/revise", Map.of());
        assertThat(basic.status()).isEqualTo(201);
        JsonNode eco = basic.body().get("change");
        assertThat(eco.get("stageId").isNull()).isTrue();
        String ecoId = eco.get("id").asString();
        // Integrity fix 2026-11-24 (ML-4) holds without PLM too: who raised the change cannot approve it.
        Res own = other("POST", "/manufacturing/engineering-changes/" + ecoId + "/approve", null);
        assertThat(own.status()).isEqualTo(403);
        assertThat(own.body().get("message").asString()).isEqualTo("You cannot approve a change you raised.");
        Res approved = call("POST", "/manufacturing/engineering-changes/" + ecoId + "/approve", null,
                secondOtherAdmin());
        assertThat(approved.body().get("status").asString()).isEqualTo("approved");
        Res applied = other("POST", "/manufacturing/engineering-changes/" + ecoId + "/apply", null);
        assertThat(applied.body().get("status").asString()).isEqualTo("applied");
        String newBom = basic.body().get("bom").get("id").asString();
        assertThat(other("GET", "/manufacturing/boms/" + newBom, null).body().get("status").asString())
                .isEqualTo("active");

        // Switched on again.
        assertThat(other("POST", "/feature-flags/modules/plm/enable", null).status()).isEqualTo(201);
        assertThat(other("GET", PLM + "/overview", null).status()).isEqualTo(200);
        // The organization now has explicit module rows: PLM stays on because manufacturing is on (deployment flag).
        assertThat(other("GET", PLM + "/changes", null).status()).isEqualTo(200);
    }
}
