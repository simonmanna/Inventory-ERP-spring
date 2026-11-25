package com.inventoryerp.plm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** PLM ↔ product master data and accounting (ADR-S086 §4, §5). */
class PlmErpIntegrationIT extends PlmTestSupport {

    static final String PLM = "/manufacturing/plm";

    String msg(Res r) {
        return r.body().get("message").asString();
    }

    String register(String productId, String state) {
        return ok(201, "POST", PLM + "/items", Map.of("productId", productId, "lifecycleState", state))
                .get("id").asString();
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

    /** A finished product on standard cost at {@code cost}. */
    String standardProduct(String code, int cost) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                        + " \"costPrice\", \"salesPrice\", \"manufacturingRole\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', 'STANDARD', ?, 30, 'finished', now())")
                .params(id, demoOrg, code + "-" + suffix(), code + " " + suffix(), cost).update();
        return id;
    }

    Map<String, Object> productRow(String id) {
        return jdbc.sql("SELECT name, barcode, weight, description, \"costPrice\" FROM \"Product\" WHERE id = ?")
                .param(id).query().singleRow();
    }

    @Test
    @DisplayName("product ECO: master data proposed, validated, applied through the product service; new revision")
    void productData() {
        String bun = product("DBUN");
        String other = product("DOTHER");
        String taken = "PLM-" + suffix();
        jdbc.sql("UPDATE \"Product\" SET barcode = ? WHERE id = ?").params(taken, other).update();
        String itemId = register(bun, "design");
        String code = ok(200, "GET", PLM + "/items/" + itemId, null).get("product").get("code").asString();

        // Validation.
        assertThat(msg(as("POST", PLM + "/changes", Map.of("type", "product", "productId", bun)))).isEqualTo(
                "targetLifecycleState is required for a product change.");
        assertThat(msg(as("POST", PLM + "/changes", Map.of("type", "product", "productId", bun, "productChanges",
                Map.of("sku", "X"))))).startsWith("productChanges.sku is not a product field");
        assertThat(msg(as("POST", PLM + "/changes", Map.of("type", "product", "productId", bun, "productChanges",
                Map.of("name", " "))))).isEqualTo("productChanges.name should not be empty");
        assertThat(msg(as("POST", PLM + "/changes", Map.of("type", "product", "productId", bun, "productChanges",
                Map.of("weight", -1))))).isEqualTo("productChanges.weight must not be less than 0");
        assertThat(msg(as("POST", PLM + "/changes", Map.of("type", "product", "productId", bun, "productChanges",
                Map.of("barcode", taken))))).startsWith("Barcode " + taken + " is already used by product DOTHER-");

        String barcode = "PLM-" + suffix();
        JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "product", "productId", bun,
                "productChanges", Map.of("name", "Bun deluxe " + suffix(), "weight", 0.25, "barcode", barcode,
                        "description", "Brioche dough")));
        String ecoId = eco.get("id").asString();
        assertThat(eco.get("title").asString()).isEqualTo("Product data of " + code);
        JsonNode detail = ok(200, "GET", PLM + "/changes/" + ecoId, null);
        assertThat(detail.get("productData")).hasSize(4);
        assertThat(detail.get("productData").get(0).get("field").asString()).isEqualTo("name");
        assertThat(detail.get("productData").get(0).get("changes").asBoolean()).isTrue();

        // Edit: drop the description, keep the rest.
        Map<String, Object> changes = new HashMap<>(Map.of("name", detail.get("productChanges").get("name")
                .asString(), "weight", 0.25, "barcode", barcode));
        JsonNode edited = ok(200, "PATCH", PLM + "/changes/" + ecoId, Map.of("productChanges", changes));
        assertThat(edited.get("id").asString()).isEqualTo(ecoId);
        assertThat(ok(200, "GET", PLM + "/changes/" + ecoId, null).get("productData")).hasSize(3);

        approve(ecoId);
        JsonNode applied = ok(201, "POST", PLM + "/changes/" + ecoId + "/apply", null);
        assertThat(applied.get("status").asString()).isEqualTo("applied");
        assertThat(applied.get("newRevision").asString()).isEqualTo("B");
        Map<String, Object> p = productRow(bun);
        assertThat(p.get("name")).isEqualTo(changes.get("name"));
        assertThat(p.get("barcode")).isEqualTo(barcode);
        assertThat((BigDecimal) p.get("weight")).isEqualByComparingTo("0.25");
        JsonNode done = ok(200, "GET", PLM + "/changes/" + ecoId, null);
        assertThat(done.get("productChangesApplied").get("name").get("to").asString()).isEqualTo(changes.get("name"));
        assertThat(done.get("productData").get(0).get("before").asString()).startsWith("DBUN ");
        assertThat(done.get("activity").get(0).get("message").asString()).contains("Product data: name, barcode,"
                + " weight.");

        // The revisions record the product data: compare shows the name change.
        JsonNode revs = ok(200, "GET", PLM + "/items/" + itemId + "/revisions", null);
        JsonNode cmp = ok(200, "GET", PLM + "/revisions/compare?from=" + revs.get(1).get("id").asString() + "&to="
                + revs.get(0).get("id").asString(), null);
        boolean nameChanged = false;
        for (JsonNode f : cmp.get("product")) {
            nameChanged |= "name".equals(f.get("field").asString());
        }
        assertThat(nameChanged).isTrue();

        // Master data only belongs on product changes.
        String flour = product("DFLOUR");
        JsonNode bom = activeBom(bun, List.of(line(flour, 1)));
        assertThat(msg(as("POST", PLM + "/changes", Map.of("type", "bom", "bomId", bom.get("id").asString(),
                "productChanges", Map.of("name", "x"))))).isEqualTo("productChanges only applies to a product change.");
    }

    @Test
    @DisplayName("cost policy activate: the new version's roll-up becomes the active standard; stock revalued, posted")
    void costPolicyActivate() {
        String flour = product("CFLOUR");
        String cake = standardProduct("CCAKE", 10);
        stockIn(bakery, flour, 50, 2);
        stockIn(warehouse, cake, 10, 10);
        JsonNode v1 = activeBom(cake, List.of(line(flour, 2)));
        register(cake, "released");

        JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", v1.get("id").asString(),
                "costPolicy", "activate", "stockDisposition", "none"));
        String ecoId = eco.get("id").asString();
        ok(200, "PATCH", "/manufacturing/boms/" + eco.get("newBomId").asString(), Map.of("lines",
                List.of(line(flour, 3))));

        JsonNode fin = ok(200, "GET", PLM + "/changes/" + ecoId + "/financial", null);
        assertThat(fin.get("applicable").asBoolean()).isTrue();
        assertThat(fin.get("canActivate").asBoolean()).isTrue();
        assertThat(new BigDecimal(fin.get("currentUnitCost").asString())).isEqualByComparingTo("10");
        BigDecimal newUnit = new BigDecimal(fin.get("rollup").get("newVersion").asString());
        assertThat(newUnit.signum()).isPositive();
        assertThat(new BigDecimal(fin.get("onHand").get("quantity").asString())).isEqualByComparingTo("10");
        BigDecimal expected = newUnit.subtract(BigDecimal.TEN).multiply(BigDecimal.TEN);
        assertThat(new BigDecimal(fin.get("revaluation").get("amount").asString())).isEqualByComparingTo(expected);
        assertThat(fin.get("revaluation").get("account").get("code").asString()).isEqualTo("5960");

        approve(ecoId);
        JsonNode applied = ok(201, "POST", PLM + "/changes/" + ecoId + "/apply", null);
        assertThat(applied.get("status").asString()).isEqualTo("applied");
        String standardId = applied.get("standardCostId").asString();
        assertThat(jdbc.sql("SELECT status FROM \"ProductStandardCost\" WHERE id = ?").param(standardId)
                .query(String.class).single()).isEqualTo("active");
        assertThat((BigDecimal) productRow(cake).get("costPrice")).isEqualByComparingTo(newUnit);
        String journal = applied.get("revaluationJournalId").asString();
        BigDecimal posted = jdbc.sql("SELECT coalesce(sum(l.debit), 0) FROM \"JournalLine\" l JOIN \"Account\" a ON"
                + " a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ? AND a.code = '5960'").param(journal)
                .query(BigDecimal.class).single();
        BigDecimal credited = jdbc.sql("SELECT coalesce(sum(l.credit), 0) FROM \"JournalLine\" l JOIN \"Account\" a"
                + " ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ? AND a.code = '5960'").param(journal)
                .query(BigDecimal.class).single();
        // A cheaper standard lowers the stock value: Dr revaluation / Cr stock.
        assertThat(posted.subtract(credited)).isEqualByComparingTo(expected.negate());
        JsonNode after = ok(200, "GET", PLM + "/changes/" + ecoId + "/financial", null);
        assertThat(after.get("result").get("status").asString()).isEqualTo("active");
        assertThat(ok(200, "GET", PLM + "/changes/" + ecoId, null).get("activity").get(0).get("message").asString())
                .contains("Standard cost v").contains("activated");
    }

    @Test
    @DisplayName("cost policy: propose leaves a draft standard; activate refused off standard cost; SoD keeps a draft")
    void costPolicyRules() {
        String flour = product("PFLOUR");
        String pie = product("PPIE");
        stockIn(bakery, flour, 20, 2);
        JsonNode v1 = activeBom(pie, List.of(line(flour, 2)));
        register(pie, "released");

        assertThat(msg(as("POST", PLM + "/changes", Map.of("type", "bom", "bomId", v1.get("id").asString(),
                "costPolicy", "activate")))).contains(" is costed at AVCO; only a product on standard cost");
        assertThat(msg(as("POST", PLM + "/changes", Map.of("type", "product", "productId", pie,
                "targetLifecycleState", "phase_out", "costPolicy", "propose"))))
                .isEqualTo("costPolicy only applies to a BOM change.");

        JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", v1.get("id").asString(),
                "costPolicy", "propose", "stockDisposition", "none"));
        String ecoId = eco.get("id").asString();
        approve(ecoId);
        JsonNode applied = ok(201, "POST", PLM + "/changes/" + ecoId + "/apply", null);
        Map<String, Object> std = jdbc.sql("SELECT status, source, \"productId\" FROM \"ProductStandardCost\" WHERE id"
                + " = ?").param(applied.get("standardCostId").asString()).query().singleRow();
        assertThat(std.get("status")).isEqualTo("draft");
        assertThat(std.get("source")).isEqualTo("bom_rollup");
        assertThat(std.get("productId")).isEqualTo(pie);
        assertThat(applied.get("revaluationJournalId").isNull()).isTrue();

        // Separation of duties on inventory valuation: activate stays a proposal for another user to approve.
        String cake = standardProduct("SCAKE", 5);
        JsonNode c1 = activeBom(cake, List.of(line(flour, 1)));
        register(cake, "released");
        String rule = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"SodRule\" (id, \"organizationId\", \"pairKey\", \"isActive\", \"updatedAt\") VALUES (?,"
                + " ?, 'inventory_valuation', true, now())").params(rule, demoOrg).update();
        try {
            JsonNode sodEco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", c1.get("id").asString(),
                    "costPolicy", "activate", "stockDisposition", "none"));
            String sodId = sodEco.get("id").asString();
            assertThat(ok(200, "GET", PLM + "/changes/" + sodId + "/financial", null).get("activateBlocker")
                    .asString()).startsWith("Separation of duties on inventory valuation");
            approve(sodId);
            JsonNode sodApplied = ok(201, "POST", PLM + "/changes/" + sodId + "/apply", null);
            assertThat(sodApplied.get("status").asString()).isEqualTo("applied");
            assertThat(jdbc.sql("SELECT status FROM \"ProductStandardCost\" WHERE id = ?")
                    .param(sodApplied.get("standardCostId").asString()).query(String.class).single()).isEqualTo("draft");
            assertThat(ok(200, "GET", PLM + "/changes/" + sodId, null).get("costResult").get("note").asString())
                    .startsWith("Separation of duties");
        } finally {
            jdbc.sql("DELETE FROM \"SodRule\" WHERE id = ?").param(rule).update();
        }
    }

    @Test
    @DisplayName("cost fallback (ADR-S086 §5): a costing failure never undoes the applied change, never substitutes zero,"
            + " is recorded on the ECO with its reason, and no revaluation posts")
    void costFallback() {
        // 1. Incomplete roll-up: a component without any cost. The change applies; no standard is proposed.
        String water = product("FWATER"); // cost price 0, no stock: no cost at all
        String paste = standardProduct("FPASTE", 8);
        JsonNode p1 = activeBom(paste, List.of(line(water, 1)));
        register(paste, "released");
        JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", p1.get("id").asString(),
                "costPolicy", "propose", "stockDisposition", "none"));
        String ecoId = eco.get("id").asString();
        approve(ecoId);
        int standardsBefore = jdbc.sql("SELECT count(*) FROM \"ProductStandardCost\" WHERE \"productId\" = ?")
                .param(paste).query(Integer.class).single();
        JsonNode applied = ok(201, "POST", PLM + "/changes/" + ecoId + "/apply", null);
        assertThat(applied.get("status").asString()).isEqualTo("applied");
        assertThat(applied.get("costResult").get("status").asString()).isEqualTo("incomplete");
        assertThat(applied.get("costResult").get("note").asString()).contains("is incomplete: no cost for")
                .contains("No standard was proposed");
        assertThat(applied.get("standardCostId").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM \"ProductStandardCost\" WHERE \"productId\" = ?").param(paste)
                .query(Integer.class).single()).isEqualTo(standardsBefore);
        assertThat(jdbc.sql("SELECT status FROM \"Bom\" WHERE id = ?").param(eco.get("newBomId").asString())
                .query(String.class).single()).isEqualTo("active");
        JsonNode detail = ok(200, "GET", PLM + "/changes/" + ecoId, null);
        assertThat(detail.get("costResult").get("status").asString()).isEqualTo("incomplete");
        assertThat(detail.get("activity").get(0).get("message").asString()).contains("is incomplete");

        // 2. Activation refused at run time (books locked): the change still applies, the revaluation rolls back and
        // the proposal stays a draft with why; the failure is audited.
        String flour = product("FFLOUR");
        String cake = standardProduct("FCAKE", 10);
        stockIn(bakery, flour, 50, 2);
        stockIn(warehouse, cake, 10, 10);
        JsonNode c1 = activeBom(cake, List.of(line(flour, 2)));
        register(cake, "released");
        JsonNode eco2 = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", c1.get("id").asString(),
                "costPolicy", "activate", "stockDisposition", "none"));
        String eco2Id = eco2.get("id").asString();
        ok(200, "PATCH", "/manufacturing/boms/" + eco2.get("newBomId").asString(), Map.of("lines",
                List.of(line(flour, 3))));
        approve(eco2Id);
        java.sql.Timestamp lockBefore = jdbc.sql("SELECT \"booksLockDate\" FROM \"Organization\" WHERE id = ?")
                .param(demoOrg).query(java.sql.Timestamp.class).optional().orElse(null);
        BigDecimal costBefore = (BigDecimal) productRow(cake).get("costPrice");
        JsonNode applied2;
        jdbc.sql("UPDATE \"Organization\" SET \"booksLockDate\" = now() + interval '2 days' WHERE id = ?")
                .param(demoOrg).update();
        try {
            applied2 = ok(201, "POST", PLM + "/changes/" + eco2Id + "/apply", null);
        } finally {
            jdbc.sql("UPDATE \"Organization\" SET \"booksLockDate\" = ? WHERE id = ?").params(lockBefore, demoOrg)
                    .update();
        }
        assertThat(applied2.get("status").asString()).isEqualTo("applied");
        assertThat(applied2.get("revaluationJournalId").isNull()).isTrue();
        String standardId = applied2.get("standardCostId").asString();
        assertThat(jdbc.sql("SELECT status FROM \"ProductStandardCost\" WHERE id = ?").param(standardId)
                .query(String.class).single()).isEqualTo("draft");
        assertThat(applied2.get("costResult").get("note").asString()).startsWith("Activation refused: Books are"
                + " locked through");
        assertThat((BigDecimal) productRow(cake).get("costPrice")).isEqualByComparingTo(costBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'EngineeringChange' AND"
                + " \"entityId\" = ? AND \"newValues\"::text LIKE '%plm_cost_step_failed%'").param(eco2Id)
                .query(Integer.class).single()).isEqualTo(1);
    }
}
