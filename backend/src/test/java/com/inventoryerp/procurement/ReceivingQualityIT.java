package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.procurement.web.ReceivingQualityController;
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
 * ADR-PROCUREMENT-001 wave 6: incoming inspection (auto-opened by policy, holds the bill, rejected goods become a
 * quality claim), weighbridge evidence for bulk receipts, emergency purchases with independent review.
 */
class ReceivingQualityIT extends ApiTestSupport {

    String checkerToken;
    String storekeeperToken;
    String warehouse;
    String supplier;
    String branch;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        String admin = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id JOIN \"User\" u"
                + " ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' LIMIT 1").query(String.class).single();
        String email = "quality-checker-" + suffix() + "@demo.test";
        createUser(demoOrg, email, "Checker@12345", admin);
        checkerToken = login("DEMO", email, "Checker@12345");
        String store = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(store, demoOrg, "Storekeeper " + suffix(), new String[] {
                    "purchase_order:read", "purchase_order:receive", "goods_receipt:read"}).update();
        String keeper = "storekeeper-" + suffix() + "@demo.test";
        createUser(demoOrg, keeper, "Store@12345", store);
        storekeeperToken = login("DEMO", keeper, "Store@12345");
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "QW-" + suffix(), "name", "Quarry yard "
                + suffix(), "type", "warehouse")).get("id").asString();
        supplier = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(supplier, demoOrg, "SUP-" + suffix(), "Cement " + suffix())
                .update();
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    void newBranch() {
        branch = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(branch, demoOrg, "BQ-" + suffix(), "Quarry " + suffix()).update();
    }

    String product() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true,"
                + " 'AVCO', 0, 0, now())").params(id, demoOrg, "QP-" + suffix(), "Cement " + suffix()).update();
        return id;
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    void policy(String key, Object value) {
        String id = ok(201, "POST", "/procurement/policies", Map.of("key", key, "scopeType", "branch", "scopeId", branch,
                "value", value)).get("id").asString();
        assertThat(call("POST", "/procurement/policies/" + id + "/approve", null, checkerToken).status()).isEqualTo(200);
        ok(200, "POST", "/procurement/policies/" + id + "/activate", null);
    }

    String po(String productId, Object qty, Object price) {
        JsonNode po = ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "branchId", branch, "paymentType", "credit", "currencyCode", "UGX", "lines",
                List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty, "unitPrice", price))));
        return po.get("id").asString();
    }

    Res receive(String token, String poId, String productId, Object qty) {
        return call("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty))), key(token));
    }

    @Test
    @DisplayName("inspection: opened on receipt by policy, holds the bill, rejects become a quality claim")
    void inspection() {
        newBranch();
        policy("receiving.inspectionRequired", true);
        String cement = product();
        String poId = po(cement, 10, 30000);
        assertThat(receive(adminToken, poId, cement, 10).status()).isIn(200, 201);
        JsonNode list = ok(200, "GET", "/procurement/inspections?purchaseOrderId=" + poId, null);
        assertThat(list).hasSize(1);
        String id = list.get(0).get("id").asString();
        assertThat(list.get(0).get("status").asString()).isEqualTo("pending");
        assertThat(list.get(0).get("inspectionNumber").asString()).startsWith("QC-");

        String bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "branchId",
                branch, "purchaseOrderIds", List.of(poId), "lines", List.of(Map.of("productId", cement, "description",
                        "Cement", "quantity", 10, "unitPrice", 30000)))).get("id").asString();
        Res held = as("POST", "/vendor-bills/" + bill + "/post", Map.of());
        assertThat(held.status()).isEqualTo(400);
        assertThat(held.body().get("message").asString()).startsWith("Incoming inspection QC-");

        JsonNode insp = ok(200, "GET", "/procurement/inspections/" + id, null);
        String line = insp.get("lines").get(0).get("id").asString();
        Res tooMany = as("POST", "/procurement/inspections/" + id + "/results", Map.of("lines", List.of(Map.of("lineId",
                line, "acceptedQuantity", 9, "rejectedQuantity", 2))));
        assertThat(tooMany.body().get("message").asString()).isEqualTo(
                "Accepted + rejected for \"Cement\" cannot exceed the received 10");
        ok(200, "POST", "/procurement/inspections/" + id + "/results", Map.of("lines", List.of(Map.of("lineId", line,
                "rejectedQuantity", 2, "defectNotes", "bags torn, wet")), "notes", "Truck UBA 123X",
                "fileIds", List.of("photo-1")));
        JsonNode done = call("POST", "/procurement/inspections/" + id + "/complete", null, key(adminToken)).body();
        assertThat(done.get("status").asString()).isEqualTo("partial");
        assertThat(done.get("claimNumber").asString()).startsWith("CLM-");
        JsonNode claim = ok(200, "GET", "/procurement/claims/" + done.get("claimId").asString(), null);
        assertThat(claim.get("type").asString()).isEqualTo("quality");
        assertThat(claim.get("amount").asString()).isEqualTo("60000");
        assertThat(claim.get("description").asString()).contains("2 × Cement (bags torn, wet)");
        assertThat(as("POST", "/procurement/inspections/" + id + "/cancel", null).status()).isEqualTo(400);
        assertThat(ok(200, "POST", "/vendor-bills/" + bill + "/post", Map.of("override3WM", true)).get("status")
                .asString()).isEqualTo("posted");
        assertThat(call("GET", "/procurement/inspections/" + id, null, otherToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("weighbridge: bulk receipts need a ticket; only receiving:override_evidence may skip it")
    void weighbridgeEvidence() {
        newBranch();
        String cement = product();
        String before = po(cement, 5, 100);
        assertThat(receive(storekeeperToken, before, cement, 1).status()).isIn(200, 201);

        policy("receiving.weighbridgeRequired", true);
        String poId = po(cement, 5, 100);
        Res refused = receive(storekeeperToken, poId, cement, 5);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).startsWith(
                "A completed weighbridge ticket is required before receiving Cement");
        assertThat(receive(adminToken, poId, cement, 5).status()).isIn(200, 201);
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"organizationId\" = ? AND entity ="
                + " 'GoodsReceiptNote' AND \"newValues\"::text LIKE '%weighbridgeOverride%'").param(demoOrg)
                .query(Long.class).single()).isPositive();
    }

    @Test
    @DisplayName("emergency purchases: flagged with a reason, reviewed by someone other than the buyer")
    void emergencyPurchases() {
        newBranch();
        String poId = po(product(), 1, 500);
        assertThat(as("POST", "/procurement/purchase-orders/" + poId + "/emergency", Map.of()).status()).isEqualTo(400);
        ok(200, "POST", "/procurement/purchase-orders/" + poId + "/emergency", Map.of("reason", "Crusher bearing failed"));
        assertThat(ok(200, "GET", "/procurement/reports/emergency?reviewed=false", null).findValuesAsString("id"))
                .contains(poId);
        Res self = as("POST", "/procurement/purchase-orders/" + poId + "/emergency-review", Map.of("note", "fine"));
        assertThat(self.status()).isEqualTo(403);
        Res reviewed = call("POST", "/procurement/purchase-orders/" + poId + "/emergency-review", Map.of("note",
                "justified, 2 quotes by phone"), checkerToken);
        assertThat(reviewed.status()).isEqualTo(200);
        assertThat(reviewed.body().get("emergencyReviewNote").asString()).isEqualTo("justified, 2 quotes by phone");
        assertThat(ok(200, "GET", "/procurement/reports/emergency?reviewed=false", null).findValuesAsString("id"))
                .doesNotContain(poId);
    }

    @Test
    @DisplayName("authorization matrix for the receiving-quality routes")
    void authorization() {
        assertThat(assertAuthorizationMatrix(ReceivingQualityController.class)).isEqualTo(9);
    }
}
