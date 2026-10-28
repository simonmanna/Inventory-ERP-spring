package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Configurable costing policy (ADR-S007 §1). Activation tests use a category-scoped policy on a fresh category so the
 * shared demo organisation keeps its legacy behaviour for every other test class.
 */
class InventoryCostPolicyIT extends ApiTestSupport {

    String warehouse;

    @BeforeAll
    void fixtures() {
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "CP-" + suffix(), "name", "Policy "
                + suffix(), "type", "warehouse")).get("id").asString();
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String category() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProductCategory\" (id, \"organizationId\", name, \"updatedAt\") VALUES (?, ?, ?, now())")
                .params(id, demoOrg, "Policy cat " + suffix()).update();
        return id;
    }

    JsonNode product(String categoryId, String method) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "CP-" + suffix());
        body.put("name", "Policy item " + suffix());
        body.put("productType", "stockable");
        body.put("trackInventory", true);
        body.put("categoryId", categoryId);
        if (method != null) {
            body.put("costingMethod", method);
        }
        return as("POST", "/products", body).body();
    }

    void receive(String productId, Object qty, Object cost, String batch) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", productId);
        line.put("description", "goods");
        line.put("quantity", qty);
        line.put("unitCost", cost);
        if (batch != null) {
            line.put("batchNumber", batch);
        }
        ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("warehouseId", warehouse, "receivedAt",
                java.time.Instant.now().toString(), "lines", List.of(line)));
    }

    String policy(String categoryId, String method, String effectiveFrom) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", categoryId == null ? "organization" : "category");
        if (categoryId != null) {
            body.put("categoryId", categoryId);
        }
        body.put("method", method);
        body.put("effectiveFrom", effectiveFrom);
        return ok(201, "POST", "/inventory/cost-policies", body).get("id").asString();
    }

    JsonNode activate(String id) {
        ok(200, "POST", "/inventory/cost-policies/" + id + "/approve", null);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res r = call("POST", "/inventory/cost-policies/" + id + "/activate", null, h);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    BigDecimal stockValue(String productId) {
        return jdbc.sql("SELECT COALESCE(SUM(b.quantity * b.\"unitCost\"), 0) + COALESCE((SELECT SUM(s.quantity) FROM"
                + " \"StockItem\" s WHERE s.\"productId\" = ?), 0) * 0 FROM \"InventoryBatch\" b WHERE b.\"productId\" = ?"
                + " AND b.\"isActive\" = true AND b.quantity > 0").params(productId, productId).query(BigDecimal.class)
                .single();
    }

    @Test
    @DisplayName("AVCO → FIFO: on-hand becomes one opening lot at the average, value kept; product follows the policy")
    void avcoToFifo() {
        String cat = category();
        String avco = product(cat, "AVCO").get("id").asString();
        receive(avco, 10, 100, null);
        receive(avco, 10, 200, null);
        String id = policy(cat, "FIFO", LocalDate.now().toString());

        JsonNode preview = ok(200, "GET", "/inventory/cost-policies/" + id + "/preview", null);
        assertThat(preview.get("products")).hasSize(1);
        assertThat(preview.get("totalValue").asString()).isEqualTo("3000");

        JsonNode active = activate(id);
        assertThat(active.get("status").asString()).isEqualTo("active");
        assertThat(active.get("migrationSummary").get("difference").asString()).isEqualTo("0");
        assertThat(active.get("migrationLines")).hasSize(1);
        Map<String, Object> p = jdbc.sql("SELECT \"costingMethod\"::text AS m, \"batchTracking\" AS b FROM \"Product\""
                + " WHERE id = ?").param(avco).query().singleRow();
        assertThat(p.get("m")).isEqualTo("FIFO");
        assertThat(p.get("b")).isEqualTo(true);
        assertThat(stockValue(avco)).isEqualByComparingTo("3000");

        // New products in the category follow the policy; the other interchangeable method is refused.
        assertThat(product(cat, null).get("costingMethod").asString()).isEqualTo("FIFO");
        JsonNode conflict = product(cat, "AVCO");
        assertThat(conflict.get("message").asString()).isEqualTo("Costing method AVCO conflicts with the active"
                + " inventory cost policy (FIFO); change the policy instead of the product");
        assertThat(product(cat, "STANDARD").get("costingMethod").asString()).isEqualTo("STANDARD");
        Res moved = as("PATCH", "/products/" + avco, Map.of("costingMethod", "AVCO"));
        assertThat(moved.status()).isEqualTo(400);

        // Products outside the category are untouched (legacy behaviour).
        assertThat(product(null, "AVCO").get("costingMethod").asString()).isEqualTo("AVCO");
    }

    @Test
    @DisplayName("FIFO → AVCO: lots collapse to the weighted average, lot tracking retired; future policy waits")
    void fifoToAvcoAndLifecycle() {
        String cat = category();
        String fifo = product(cat, "FIFO").get("id").asString();
        receive(fifo, 10, 100, "A-" + suffix());
        receive(fifo, 30, 200, "B-" + suffix());
        String id = policy(cat, "AVCO", LocalDate.now().toString());
        JsonNode active = activate(id);
        assertThat(active.get("migrationSummary").get("valueBefore").asString()).isEqualTo("7000");
        assertThat(active.get("migrationSummary").get("valueAfter").asString()).isEqualTo("7000");
        Map<String, Object> s = jdbc.sql("SELECT quantity, \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ?")
                .param(fifo).query().singleRow();
        assertThat((BigDecimal) s.get("runningAverageCost")).isEqualByComparingTo("175");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryBatch\" WHERE \"productId\" = ? AND \"isActive\" = true")
                .param(fifo).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT \"batchTracking\" FROM \"Product\" WHERE id = ?").param(fifo).query(Boolean.class)
                .single()).isFalse();

        // A newer version supersedes; a future-dated one cannot be activated yet; drafts edit / cancel.
        String future = policy(cat, "FIFO", LocalDate.now().plusDays(30).toString());
        ok(200, "POST", "/inventory/cost-policies/" + future + "/approve", null);
        Res early = as("POST", "/inventory/cost-policies/" + future + "/activate", null);
        assertThat(early.status()).isEqualTo(400);
        assertThat(early.body().get("message").asString()).startsWith("The policy takes effect on");
        assertThat(ok(200, "POST", "/inventory/cost-policies/" + future + "/cancel", null).get("status").asString())
                .isEqualTo("cancelled");
        String draft = policy(cat, "FIFO", LocalDate.now().toString());
        assertThat(ok(200, "PATCH", "/inventory/cost-policies/" + draft, Map.of("notes", "accountant sign-off"))
                .get("notes").asString()).isEqualTo("accountant sign-off");
        String v2 = policy(cat, "AVCO", LocalDate.now().toString());
        activate(v2);
        assertThat(ok(200, "GET", "/inventory/cost-policies/" + id, null).get("status").asString()).isEqualTo("superseded");
        assertThat(ok(200, "GET", "/inventory/cost-policies/" + v2, null).get("version").asInt()).isGreaterThan(1);

        // Org-scoped drafts validate but stay inert in this shared org.
        Res bad = as("POST", "/inventory/cost-policies", Map.of("scopeType", "category", "method", "FIFO",
                "effectiveFrom", LocalDate.now().toString()));
        assertThat(bad.status()).isEqualTo(400);
        String orgDraft = policy(null, "FIFO", LocalDate.now().toString());
        ok(200, "POST", "/inventory/cost-policies/" + orgDraft + "/cancel", null);
        assertThat(ok(200, "GET", "/inventory/cost-policies/current", null).get("governed").asBoolean()).isFalse();

        assertThat(call("GET", "/inventory/cost-policies/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/inventory/cost-policies", null, otherToken).body()).isEmpty();
    }
}
