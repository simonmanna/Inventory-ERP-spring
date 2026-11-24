package com.inventoryerp.plm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** PLM settings, the lifecycle policy across purchasing / sales / replenishment, first-article inspection (ADR-S086). */
class PlmPolicyIT extends PlmTestSupport {

    static final String PLM = "/manufacturing/plm";

    String msg(Res r) {
        return r.body().get("message").asString();
    }

    String register(String productId, String state) {
        return ok(201, "POST", PLM + "/items", Map.of("productId", productId, "lifecycleState", state))
                .get("id").asString();
    }

    String code(String productId) {
        return jdbc.sql("SELECT code FROM \"Product\" WHERE id = ?").param(productId).query(String.class).single();
    }

    String partner(String flag) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"" + flag + "\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "PLM-" + suffix(), "PLM partner "
                        + suffix()).update();
        return id;
    }

    static Map<String, Object> line(String productId, int qty) {
        return Map.of("componentProductId", productId, "quantity", qty);
    }

    JsonNode activeBom(String output, List<Map<String, Object>> lines) {
        JsonNode draft = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Recipe " + suffix(),
                "outputProductId", output, "outputQuantity", 1, "lines", lines));
        return ok(201, "POST", "/manufacturing/boms/" + draft.get("id").asString() + "/activate", null);
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

    void resetSettings() {
        ok(200, "PUT", PLM + "/settings", Map.of("lifecyclePolicy", Map.of("prototype", Map.of("sell", "allow")),
                "defaultCostPolicy", "none", "firstArticleDefault", false));
    }

    /** An applied BOM change asking for a first article; returns {eco id, new BOM id}. */
    String[] appliedWithFirstArticle(String output, String component) {
        JsonNode v1 = activeBom(output, List.of(line(component, 1)));
        register(output, "released");
        JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", v1.get("id").asString(),
                "firstArticle", true, "stockDisposition", "none"));
        String ecoId = eco.get("id").asString();
        ok(200, "PATCH", "/manufacturing/boms/" + eco.get("newBomId").asString(), Map.of("lines",
                List.of(line(component, 2))));
        approve(ecoId);
        JsonNode applied = ok(201, "POST", PLM + "/changes/" + ecoId + "/apply", null);
        assertThat(applied.get("faiStatus").asString()).isEqualTo("pending");
        return new String[] {ecoId, eco.get("newBomId").asString()};
    }

    void completeOneOn(String bomId) {
        JsonNode order = started(bomId);
        ok(201, "POST", "/manufacturing/production-orders/" + order.get("id").asString() + "/complete",
                Map.of("qtyProduced", 1));
        drainOutboxFully();
    }

    @Test
    @DisplayName("lifecycle policy: obsolete / phase-out products refused in purchasing and sales, left out of"
            + " replenishment; the matrix is the organization's")
    void lifecyclePolicy() {
        JsonNode defaults = ok(200, "GET", PLM + "/settings", null);
        assertThat(defaults.get("lifecyclePolicy").get("obsolete").get("purchase").asString()).isEqualTo("block");
        assertThat(defaults.get("lifecyclePolicy").get("phase_out").get("sell").asString()).isEqualTo("allow");
        assertThat(msg(as("PUT", PLM + "/settings", Map.of("lifecyclePolicy", Map.of("released", Map.of("sell",
                "block")))))).isEqualTo("A released product is always allowed to be purchased, sold and replenished.");
        assertThat(msg(as("PUT", PLM + "/settings", Map.of("lifecyclePolicy", Map.of("retired", Map.of())))))
                .startsWith("lifecyclePolicy.retired is not a lifecycle state");
        assertThat(msg(as("PUT", PLM + "/settings", Map.of("lifecyclePolicy", Map.of("obsolete", Map.of("sell",
                "maybe")))))).isEqualTo("lifecyclePolicy.obsolete.sell must be allow or block.");

        String old = product("POLD");
        String replacement = product("PNEW");
        String oldItem = register(old, "design");
        ok(201, "POST", PLM + "/items/" + oldItem + "/transition", Map.of("toState", "obsolete",
                "replacementProductId", replacement));
        String supplier = partner("isSupplier");
        String customer = partner("isCustomer");
        String expected = "Product " + code(old) + " is obsolete and cannot be purchased. Replacement: "
                + code(replacement) + ".";
        assertThat(msg(as("POST", "/procurement/purchase-requests", Map.of("lines", List.of(Map.of("productId", old,
                "description", "Old part", "quantity", 4, "estimatedUnitPrice", 10)))))).isEqualTo(expected);
        assertThat(msg(as("POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(Map.of("productId", old,
                        "description", "Old part", "quantity", 4, "unitPrice", 10)))))).isEqualTo(expected);
        assertThat(msg(as("POST", "/sales/orders", Map.of("partnerId", customer, "lines", List.of(Map.of("productId",
                old, "quantity", 1)))))).isEqualTo("Product " + code(old) + " is obsolete and cannot be sold."
                        + " Replacement: " + code(replacement) + ".");
        assertThat(msg(as("POST", "/sales/quotations", Map.of("partnerId", customer, "lines", List.of(Map.of(
                "productId", old, "quantity", 1)))))).endsWith("is obsolete and cannot be sold. Replacement: "
                        + code(replacement) + ".");

        // Being phased out: no longer bought, still sold.
        String fading = product("PFADE");
        activeBom(fading, List.of(line(replacement, 1)));
        String fadingItem = register(fading, "released");
        ok(201, "POST", PLM + "/items/" + fadingItem + "/transition", Map.of("toState", "phase_out"));
        assertThat(msg(as("POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(Map.of("productId",
                        fading, "description", "Fading", "quantity", 1, "unitPrice", 10))))))
                .isEqualTo("Product " + code(fading) + " is being phased out and cannot be purchased.");
        ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "lines", List.of(Map.of("productId", fading,
                "quantity", 1))));

        // Replenishment leaves the obsolete product out.
        String store = location("warehouse");
        String rule = ok(200, "PUT", "/procurement/replenishment/rules", Map.of("productId", old, "locationId", store,
                "minQuantity", 20, "maxQuantity", 50)).get("id").asString();
        try {
            JsonNode suggestion = null;
            for (JsonNode s : ok(200, "GET", "/procurement/replenishment/suggestions?all=true", null)) {
                if (rule.equals(s.get("ruleId").asString())) {
                    suggestion = s;
                }
            }
            assertThat(suggestion).isNotNull();
            assertThat(suggestion.get("needed").asBoolean()).isFalse();
            assertThat(suggestion.get("lifecycleBlocked").asBoolean()).isTrue();
        } finally {
            jdbc.sql("DELETE FROM \"ReorderRule\" WHERE id = ?").param(rule).update();
        }

        // The organization tightens the matrix: prototypes are not sold.
        String proto = product("PPROTO");
        register(proto, "prototype");
        String protoOrder = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "lines", List.of(Map.of(
                "productId", proto, "quantity", 1)))).get("id").asString();
        try {
            JsonNode saved = ok(200, "PUT", PLM + "/settings", Map.of("lifecyclePolicy", Map.of("prototype",
                    Map.of("sell", "block"))));
            assertThat(saved.get("lifecyclePolicy").get("prototype").get("sell").asString()).isEqualTo("block");
            assertThat(saved.get("lifecyclePolicy").get("obsolete").get("purchase").asString()).isEqualTo("block");
            assertThat(msg(as("POST", "/sales/orders", Map.of("partnerId", customer, "lines", List.of(Map.of(
                    "productId", proto, "quantity", 1)))))).isEqualTo("Product " + code(proto) + " is a prototype and"
                            + " cannot be sold.");
            // A line the order already had stays editable.
            ok(200, "PUT", "/sales/orders/" + protoOrder, Map.of("lines", List.of(Map.of("productId", proto,
                    "quantity", 3))));
        } finally {
            resetSettings();
        }
    }

    @Test
    @DisplayName("settings defaults: new BOM changes take the default cost policy (as far as the product allows) and"
            + " first article")
    void settingsDefaults() {
        String flour = product("SFLOUR");
        String loaf = product("SLOAF");
        JsonNode v1 = activeBom(loaf, List.of(line(flour, 1)));
        register(loaf, "released");
        try {
            ok(200, "PUT", PLM + "/settings", Map.of("defaultCostPolicy", "activate", "firstArticleDefault", true));
            JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", v1.get("id").asString()));
            // AVCO product: activate falls back to propose.
            assertThat(eco.get("costPolicy").asString()).isEqualTo("propose");
            assertThat(eco.get("firstArticle").asBoolean()).isTrue();
            ok(201, "POST", PLM + "/changes/" + eco.get("id").asString() + "/cancel", Map.of("reason", "test"));
        } finally {
            resetSettings();
        }
        String productId = product("SPROD");
        register(productId, "design");
        assertThat(msg(as("POST", PLM + "/changes", Map.of("type", "product", "productId", productId,
                "targetLifecycleState", "prototype", "firstArticle", true))))
                .isEqualTo("firstArticle only applies to a BOM or routing change.");
    }

    @Test
    @DisplayName("first article without an inspection plan: the first order on the new version asks for a result"
            + " recorded on the change")
    void firstArticleByHand() {
        String flour = product("FFLOUR");
        String bun = product("FBUN");
        stockIn(bakery, flour, 20, 2);
        String[] eco = appliedWithFirstArticle(bun, flour);
        assertThat(as("POST", PLM + "/changes/" + eco[0] + "/first-article", Map.of("result", "maybe")).status())
                .isEqualTo(400);
        assertThat(msg(as("POST", PLM + "/changes/" + eco[0] + "/first-article", Map.of("result", "passed"))))
                .isEqualTo("The first article of " + ok(200, "GET", PLM + "/changes/" + eco[0], null).get("number")
                        .asString() + " is not made yet: no production order on the new version has completed.");

        completeOneOn(eco[1]);
        JsonNode waiting = ok(200, "GET", PLM + "/changes/" + eco[0], null);
        assertThat(waiting.get("faiStatus").asString()).isEqualTo("no_plan");
        assertThat(waiting.get("faiNote").asString()).startsWith("No active final inspection plan");
        assertThat(waiting.get("faiOrderId").isNull()).isFalse();

        JsonNode passed = ok(201, "POST", PLM + "/changes/" + eco[0] + "/first-article", Map.of("result", "passed",
                "note", "Visual check OK"));
        assertThat(passed.get("faiStatus").asString()).isEqualTo("passed");
        assertThat(msg(as("POST", PLM + "/changes/" + eco[0] + "/first-article", Map.of("result", "failed"))))
                .endsWith("was already recorded (passed).");
        assertThat(ok(200, "GET", PLM + "/changes/" + eco[0], null).get("activity").get(0).get("kind").asString())
                .isEqualTo("fai");
    }

    @Test
    @DisplayName("first article with a final inspection plan: a lot opens on the first order and its evaluation"
            + " closes the inspection")
    void firstArticleLot() {
        String flour = product("QFLOUR");
        String cake = product("QCAKE");
        stockIn(bakery, flour, 20, 2);
        JsonNode spec = ok(201, "POST", "/quality/specifications", Map.of("code", "SPEC-" + suffix(), "name",
                "Cake spec " + suffix(), "productId", cake, "standard", "House", "parameters", List.of(Map.of("code",
                        "MOIST", "name", "Moisture", "kind", "numeric", "unit", "%", "testMethod", "Oven dry",
                        "maxValue", 3, "targetValue", 1.5))));
        ok(200, "POST", "/quality/specifications/" + spec.get("id").asString() + "/activate", null);
        ok(201, "POST", "/quality/inspection-plans", Map.of("code", "PLAN-" + suffix(), "name", "Cake final "
                + suffix(), "stage", "final", "productId", cake, "specificationId", spec.get("id").asString()));

        String[] eco = appliedWithFirstArticle(cake, flour);
        completeOneOn(eco[1]);
        JsonNode open = ok(200, "GET", PLM + "/changes/" + eco[0], null);
        assertThat(open.get("faiStatus").asString()).isEqualTo("open");
        String lotId = open.get("faiLotId").asString();
        assertThat(open.get("faiLot").get("lotNumber").asString()).startsWith("QL-");
        assertThat(jdbc.sql("SELECT \"sourceType\" FROM \"QualityInspectionLot\" WHERE id = ?").param(lotId)
                .query(String.class).single()).isEqualTo("plm_fai");
        assertThat(msg(as("POST", PLM + "/changes/" + eco[0] + "/first-article", Map.of("result", "passed"))))
                .contains("is inspected on lot QL-");

        ok(200, "POST", "/quality/inspection-lots/" + lotId + "/results", Map.of("results", List.of(Map.of(
                "parameterCode", "MOIST", "sampleNo", 1, "numericValue", 1))));
        drainOutboxFully();
        assertThat(ok(200, "GET", PLM + "/changes/" + eco[0], null).get("faiStatus").asString()).isEqualTo("passed");
    }
}
