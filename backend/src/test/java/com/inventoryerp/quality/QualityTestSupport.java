package com.inventoryerp.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.events.OutboxWorker;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Fixtures of the quality integration tests: products, warehouse, supplier, specs / plans, stock and GL checks. */
abstract class QualityTestSupport extends ApiTestSupport {

    @Autowired
    OutboxWorker outbox;

    @Autowired
    StockService stock;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    int seq;
    String adminId;
    String warehouse;
    String supplier;

    @BeforeAll
    void qualityFixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "QW-" + sfx, "name", "Quality yard " + sfx,
                "type", "warehouse")).get("id").asString();
        supplier = partner("Cement supplier", true);
    }

    String next() {
        return sfx + "-" + (++seq);
    }

    String partner(String name, boolean supplierFlag) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())").params(id, demoOrg, "QP-" + next(),
                name + " " + sfx, supplierFlag, !supplierFlag).update();
        return id;
    }

    String product(String name, boolean batch) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"batchTracking\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, 0, ?, now())")
                .params(id, demoOrg, "Q-" + next(), name + " " + sfx + "-" + seq, batch).update();
        return id;
    }

    /** A bare receipt (no GL) into {@code location}, optionally into a batch. */
    void receive(String productId, String location, String qty, String unitCost, String batch) {
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).executeWithoutResult(
                s -> stock.receive(StockMove.of(productId, location, new BigDecimal(qty))
                        .unitCost(new BigDecimal(unitCost)).batch(batch))));
    }

    /** Post an ad-hoc GRN (one line) from the supplier into the warehouse; returns the receipt. */
    JsonNode grn(String partnerId, String productId, Object qty, Object unitCost, String batch) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", productId);
        line.put("description", "Delivery");
        line.put("quantity", qty);
        line.put("unitCost", unitCost);
        if (batch != null) {
            line.put("batchNumber", batch);
        }
        return ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", partnerId, "warehouseId",
                warehouse, "receivedAt", java.time.Instant.now().toString(), "lines", List.of(line)));
    }

    /** Specification with a moisture limit (≤ 3 %), an optional 20 mm grading envelope and a cleanliness check. */
    JsonNode activeSpec(String productId, boolean grading) {
        List<Map<String, Object>> params = new ArrayList<>();
        params.add(Map.of("code", "MOIST", "name", "Moisture content", "kind", "numeric", "unit", "%", "testMethod",
                "Oven dry", "maxValue", 3, "targetValue", 1.5));
        if (grading) {
            params.add(Map.of("code", "GRADING", "name", "Sieve analysis", "kind", "sieve", "unit", "%", "testMethod",
                    "Dry sieving", "sieveEnvelope", List.of(
                            Map.of("sieveMm", 20, "minPassingPct", 85, "maxPassingPct", 100),
                            Map.of("sieveMm", 10, "minPassingPct", 0, "maxPassingPct", 25))));
        }
        params.add(Map.of("code", "CLEAN", "name", "Free of lumps", "kind", "boolean", "isCritical", false));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "SPEC-" + next());
        body.put("name", "Spec " + sfx);
        body.put("productId", productId);
        body.put("standard", "KS EAS 18-1");
        body.put("parameters", params);
        JsonNode spec = ok(201, "POST", "/quality/specifications", body);
        return ok(200, "POST", "/quality/specifications/" + spec.get("id").asString() + "/activate", null);
    }

    JsonNode plan(String stage, String productId, String specId, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "PLAN-" + next());
        body.put("name", "Plan " + sfx);
        body.put("stage", stage);
        body.put("productId", productId);
        body.put("specificationId", specId);
        body.putAll(extra);
        return ok(201, "POST", "/quality/inspection-plans", body);
    }

    JsonNode lotsOf(String sourceType, String sourceId) {
        return ok(200, "GET", "/quality/inspection-lots?sourceType=" + sourceType + "&sourceId=" + sourceId, null)
                .get("data");
    }

    static Map<String, Object> num(String code, int sample, Object value) {
        return Map.of("parameterCode", code, "sampleNo", sample, "numericValue", value);
    }

    static Map<String, Object> sieve(int sample, Object p20, Object p10) {
        return Map.of("parameterCode", "GRADING", "sampleNo", sample, "sieve", List.of(
                Map.of("sieveMm", 20, "passingPct", p20), Map.of("sieveMm", 10, "passingPct", p10)));
    }

    static Map<String, Object> text(String code, int sample, String value) {
        return Map.of("parameterCode", code, "sampleNo", sample, "textValue", value);
    }

    Map<String, String> keyed(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res money(String method, String path, Object body) {
        return call(method, path, body, keyed(adminToken));
    }

    String quarantine() {
        return jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code = 'QC-QUARANTINE'"
                + " AND \"deletedAt\" IS NULL").param(demoOrg).query(String.class).single();
    }

    BigDecimal onHand(String productId, String location) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ?").params(demoOrg, productId, location)
                .query(BigDecimal.class).single();
    }

    BigDecimal inBatch(String productId, String location, String batch) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ? AND \"batchNumber\" = ?")
                .params(demoOrg, productId, location, batch).query(BigDecimal.class).single();
    }

    void drainOutbox() {
        drainOutboxFully();
    }

    /** A DEMO user holding exactly {@code permissions}; returns {token, userId}. */
    String[] userWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Quality " + role, permissions).update();
        String email = "quality-" + role.substring(0, 8) + "@demo.test";
        String id = createUser(demoOrg, email, "Quality@12345", role);
        return new String[] {login("DEMO", email, "Quality@12345"), id};
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m.isArray() ? m.get(0).asString() : m.asString();
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    void assertBalanced(String entryId) {
        Map<String, Object> t = jdbc.sql("SELECT COALESCE(SUM(debit), 0) AS d, COALESCE(SUM(credit), 0) AS c FROM"
                + " \"JournalLine\" WHERE \"journalEntryId\" = ?").param(entryId).query().singleRow();
        assertThat((BigDecimal) t.get("d")).as("entry " + entryId + " balanced").isEqualByComparingTo((BigDecimal) t.get("c"));
        assertThat(((BigDecimal) t.get("d")).signum()).isPositive();
    }
}
