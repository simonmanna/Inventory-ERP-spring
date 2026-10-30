package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.procurement.application.ProcurementPolicyJob;
import com.inventoryerp.procurement.web.ProcurementPolicyController;
import com.inventoryerp.procurement.web.SupplierController;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * ADR-PROCUREMENT-001 wave 2: purchasing policies (maker-checker, effective-dated, scoped), supplier lifecycle and
 * eligibility, dual-control bank accounts, certificates, price history, approval amount re-check and bill
 * segregation of duties. Policies are branch-scoped here so they never affect other suites sharing the DEMO org.
 */
class ProcurementSupplierPolicyIT extends ApiTestSupport {

    @Autowired
    ProcurementPolicyJob job;

    @Autowired
    ApprovalsService approvals;

    String adminId;
    String checkerToken;
    String warehouse;
    String branch;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        String role = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id JOIN \"User\" u"
                + " ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' LIMIT 1").query(String.class).single();
        String email = "checker-" + suffix() + "@demo.test";
        createUser(demoOrg, email, "Checker@12345", role);
        checkerToken = login("DEMO", email, "Checker@12345");
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "SW-" + suffix(), "name", "Supplier wh "
                + suffix(), "type", "warehouse")).get("id").asString();
        branch = newBranch();
    }

    /** Each scenario gets its own branch so its branch-scoped policies never leak into another scenario. */
    String newBranch() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(id, demoOrg, "BR-" + suffix(), "Quarry branch " + suffix()).update();
        branch = id;
        return id;
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String supplier() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "SUP-" + suffix(), "Cement Co " + suffix())
                .update();
        return id;
    }

    String product() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true,"
                + " 'AVCO', 0, 0, now())").params(id, demoOrg, "SP-" + suffix(), "Cement " + suffix()).update();
        return id;
    }

    Map<String, String> as(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    /** Draft (admin) → approve (checker) → activate (admin) a branch policy; returns its id. */
    String activeBranchPolicy(String key, Object value) {
        JsonNode draft = ok(201, "POST", "/procurement/policies", Map.of("key", key, "scopeType", "branch", "scopeId",
                branch, "value", value));
        String id = draft.get("id").asString();
        Res approved = call("POST", "/procurement/policies/" + id + "/approve", null, checkerToken);
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(200);
        ok(200, "POST", "/procurement/policies/" + id + "/activate", null);
        return id;
    }

    Res po(String partnerId, String productId, Object qty, Object price, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", partnerId, "warehouseId", warehouse,
                "branchId", branch, "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(Map.of("productId",
                        productId, "description", "Cement", "quantity", qty, "unitPrice", price))));
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return as("POST", "/procurement/purchase-orders", body);
    }

    // ------------------------------------------------------------------ policies

    @Test
    @DisplayName("policies: catalogue with defaults, validation, maker-checker, activation supersedes, resolution by scope")
    void policyLifecycle() {
        newBranch();
        JsonNode defs = ok(200, "GET", "/procurement/policies/definitions", null);
        assertThat(defs.findValuesAsString("key")).contains("supplier.approvedRequired",
                "receiving.overReceiptTolerancePct", "route.requisitionRequiredAbove", "supplier.certificatesRequired");

        Res bad = as("POST", "/procurement/policies", Map.of("key", "supplier.approvedRequired", "value", "sometimes"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("message").asString()).isEqualTo(
                "Invalid value for supplier.approvedRequired: must be one of off, warn, block");
        Res unknown = as("POST", "/procurement/policies", Map.of("key", "nope.nope", "value", 1));
        assertThat(unknown.body().get("message").asString()).isEqualTo("Unknown procurement policy key: nope.nope");
        Res noScope = as("POST", "/procurement/policies", Map.of("key", "receiving.overReceiptTolerancePct", "scopeType",
                "branch", "value", 5));
        assertThat(noScope.body().get("message").asString()).isEqualTo("scopeId is required for a branch policy");

        JsonNode draft = ok(201, "POST", "/procurement/policies", Map.of("key", "receiving.overReceiptTolerancePct",
                "scopeType", "branch", "scopeId", branch, "value", 5));
        String v1 = draft.get("id").asString();
        assertThat(draft.get("status").asString()).isEqualTo("draft");
        Res self = as("POST", "/procurement/policies/" + v1 + "/approve", null);
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.body().get("message").asString())
                .isEqualTo("A policy must be approved by someone other than its author");
        Res notApproved = as("POST", "/procurement/policies/" + v1 + "/activate", null);
        assertThat(notApproved.body().get("message").asString()).isEqualTo("Only an approved policy can be activated");
        assertThat(call("POST", "/procurement/policies/" + v1 + "/approve", null, checkerToken).status()).isEqualTo(200);
        assertThat(ok(200, "POST", "/procurement/policies/" + v1 + "/activate", null).get("status").asString())
                .isEqualTo("active");

        JsonNode resolved = ok(200, "GET", "/procurement/policies/resolve?key=receiving.overReceiptTolerancePct&branchId="
                + branch, null);
        assertThat(resolved.get("value").asInt()).isEqualTo(5);
        assertThat(resolved.get("source").asString()).isEqualTo("policy");
        JsonNode orgLevel = ok(200, "GET", "/procurement/policies/resolve?key=receiving.overReceiptTolerancePct", null);
        assertThat(orgLevel.get("source").asString()).isEqualTo("default");
        assertThat(orgLevel.get("value").asInt()).isZero();

        // A second version supersedes the first.
        String v2 = activeBranchPolicy("receiving.overReceiptTolerancePct", 8);
        assertThat(ok(200, "GET", "/procurement/policies/" + v1, null).get("status").asString()).isEqualTo("superseded");
        assertThat(ok(200, "GET", "/procurement/policies/" + v2, null).get("version").asInt()).isEqualTo(2);

        // An approved version due today is activated by the daily job.
        JsonNode v3 = ok(201, "POST", "/procurement/policies", Map.of("key", "receiving.overReceiptTolerancePct",
                "scopeType", "branch", "scopeId", branch, "value", 10, "effectiveFrom", today));
        call("POST", "/procurement/policies/" + v3.get("id").asString() + "/approve", null, checkerToken);
        Map<String, Object> run = job.runForOrg(demoOrg);
        assertThat(((Number) run.get("policiesActivated")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(ok(200, "GET", "/procurement/policies/resolve?key=receiving.overReceiptTolerancePct&branchId=" + branch,
                null).get("value").asInt()).isEqualTo(10);
        // A future version is refused by activate (the job will do it on the day).
        JsonNode future = ok(201, "POST", "/procurement/policies", Map.of("key", "receiving.overReceiptTolerancePct",
                "scopeType", "branch", "scopeId", branch, "value", 12, "effectiveFrom",
                LocalDate.now().plusDays(5).toString()));
        call("POST", "/procurement/policies/" + future.get("id").asString() + "/approve", null, checkerToken);
        Res early = as("POST", "/procurement/policies/" + future.get("id").asString() + "/activate", null);
        assertThat(early.body().get("message").asString()).startsWith("This policy takes effect on");
        assertThat(ok(200, "POST", "/procurement/policies/" + future.get("id").asString() + "/cancel", null)
                .get("status").asString()).isEqualTo("cancelled");
    }

    // ------------------------------------------------------------------ enforcement

    @Test
    @DisplayName("supplier eligibility: approved-supplier policy blocks unqualified suppliers; blacklisted always blocked")
    void supplierEligibilityGatesOrders() {
        newBranch();
        String partner = supplier();
        String product = product();
        // No policy at org level: anything goes (parity).
        assertThat(po(partner, product, 1, 10, null).status()).isEqualTo(201);
        activeBranchPolicy("supplier.approvedRequired", "block");
        Res blocked = po(partner, product, 1, 10, null);
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(blocked.body().get("message").asString()).isEqualTo(
                "Cannot order from this supplier: Supplier is not approved for purchasing (status: not qualified)");
        JsonNode approved = ok(200, "POST", "/procurement/suppliers/" + partner + "/profile/approve", Map.of());
        assertThat(approved.get("profile").get("lifecycleStatus").asString()).isEqualTo("approved");
        assertThat(po(partner, product, 1, 10, null).status()).isEqualTo(201);

        Res noReason = as("POST", "/procurement/suppliers/" + partner + "/profile/blacklist", Map.of());
        assertThat(noReason.body().get("message").asString()).isEqualTo("A reason is required to blacklist a supplier");
        ok(200, "POST", "/procurement/suppliers/" + partner + "/profile/blacklist", Map.of("reason", "Fake invoices"));
        String other = supplier();
        ok(200, "POST", "/procurement/suppliers/" + other + "/profile/suspend", Map.of("reason", "Quality"));
        // Blacklisted / suspended suppliers are refused even without a policy (org-level order, no branch).
        Res noBranch = as("POST", "/procurement/purchase-orders", Map.of("partnerId", partner, "warehouseId", warehouse,
                "lines", List.of(Map.of("productId", product, "description", "x", "quantity", 1, "unitPrice", 1))));
        assertThat(noBranch.body().get("message").asString())
                .isEqualTo("Cannot order from this supplier: Supplier is blacklisted: Fake invoices");
        ok(200, "POST", "/procurement/suppliers/" + partner + "/profile/reinstate", Map.of());
        JsonNode list = ok(200, "GET", "/procurement/suppliers?status=suspended&search=" + java.net.URLEncoder.encode(
                jdbc.sql("SELECT name FROM \"Partner\" WHERE id = ?").param(other).query(String.class).single(),
                java.nio.charset.StandardCharsets.UTF_8), null);
        assertThat(list.get("data")).hasSize(1);
        assertThat(list.get("data").get(0).get("lifecycleStatus").asString()).isEqualTo("suspended");
    }

    @Test
    @DisplayName("certificates: a required, valid certificate is needed to order controlled items")
    void requiredCertificates() {
        newBranch();
        String partner = supplier();
        String product = product();
        String category = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProductCategory\" (id, \"organizationId\", name, \"updatedAt\") VALUES (?, ?, ?, now())")
                .params(category, demoOrg, "Explosives " + suffix()).update();
        jdbc.sql("UPDATE \"Product\" SET \"categoryId\" = ? WHERE id = ?").params(category, product).update();
        JsonNode draft = ok(201, "POST", "/procurement/policies", Map.of("key", "supplier.certificatesRequired",
                "scopeType", "category", "scopeId", category, "value", List.of("explosives_licence")));
        call("POST", "/procurement/policies/" + draft.get("id").asString() + "/approve", null, checkerToken);
        ok(200, "POST", "/procurement/policies/" + draft.get("id").asString() + "/activate", null);

        Res none = po(partner, product, 1, 10, null);
        assertThat(none.body().get("message").asString())
                .isEqualTo("Cannot order from this supplier: Supplier has no valid explosives_licence certificate");
        ok(201, "POST", "/procurement/suppliers/" + partner + "/certificates", Map.of("type", "explosives_licence",
                "number", "EXP-1", "validFrom", "2020-01-01", "validTo", LocalDate.now().minusDays(1).toString()));
        assertThat(po(partner, product, 1, 10, null).status()).isEqualTo(400);
        ok(201, "POST", "/procurement/suppliers/" + partner + "/certificates", Map.of("type", "explosives_licence",
                "number", "EXP-2", "validFrom", today, "validTo", LocalDate.now().plusDays(20).toString()));
        assertThat(po(partner, product, 1, 10, null).status()).isEqualTo(201);
        JsonNode expiring = ok(200, "GET", "/procurement/suppliers/certificates/expiring?days=30", null);
        assertThat(expiring.findValuesAsString("number")).contains("EXP-1", "EXP-2");
    }

    @Test
    @DisplayName("route + receiving policies: requisition threshold and over-receipt tolerance")
    void requisitionThresholdAndTolerance() {
        newBranch();
        String partner = supplier();
        String product = product();
        activeBranchPolicy("route.requisitionRequiredAbove", 100);
        Res direct = po(partner, product, 10, 50, null);
        assertThat(direct.body().get("message").asString())
                .isEqualTo("Orders above 100 must be raised from an approved purchase request");
        assertThat(po(partner, product, 1, 50, null).status()).isEqualTo(201);
        String pr = ok(201, "POST", "/procurement/purchase-requests", Map.of("lines", List.of(Map.of("description",
                "Cement", "quantity", 10)))).get("id").asString();
        assertThat(po(partner, product, 10, 50, pr).status()).as("draft request does not count").isEqualTo(400);
        ok(200, "PATCH", "/procurement/purchase-requests/" + pr + "/submit", null);
        ok(200, "PATCH", "/procurement/purchase-requests/" + pr + "/approve", null);
        Res fromPr = po(partner, product, 10, 50, pr);
        assertThat(fromPr.status()).isEqualTo(201);

        activeBranchPolicy("receiving.overReceiptTolerancePct", 10);
        String poId = fromPr.body().get("id").asString();
        Res within = call("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("productId", product, "description", "Cement", "quantity", 11))), as(adminToken));
        assertThat(within.status()).as(within.raw().body()).isEqualTo(200);
        assertThat(within.body().get("status").asString()).isEqualTo("received");
        // A second over-receipt pass would exceed the tolerance once the order is received — new order for that.
        String po2 = po(partner, product, 1, 50, null).body().get("id").asString();
        Res over = call("POST", "/procurement/purchase-orders/" + po2 + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("productId", product, "description", "Cement", "quantity", 1.2))), as(adminToken));
        assertThat(over.body().get("message").asString()).isEqualTo("Over-receiving PO line \"Cement\": ordered 1,"
                + " already received 0, trying to receive 1.2 (tolerance 10%)");
    }

    // ------------------------------------------------------------------ supplier master

    @Test
    @DisplayName("bank accounts: encrypted at rest, masked in responses, verified by a second person only")
    void bankAccountDualControl() {
        String partner = supplier();
        JsonNode acc = ok(201, "POST", "/procurement/suppliers/" + partner + "/bank-accounts", Map.of("bankName",
                "Stanbic", "accountName", "Cement Co Ltd", "accountNumber", "9030 0123 45678", "isDefault", true));
        String id = acc.get("id").asString();
        assertThat(acc.get("status").asString()).isEqualTo("pending_verification");
        assertThat(acc.get("accountNumberMasked").asString()).isEqualTo("•••• 5678");
        assertThat(acc.toString()).doesNotContain("903001234");
        String stored = jdbc.sql("SELECT \"accountNumberEnc\"::text FROM \"SupplierBankAccount\" WHERE id = ?").param(id)
                .query(String.class).single();
        assertThat(stored).doesNotContain("9030012345678").contains("ciphertext");
        Res self = as("POST", "/procurement/suppliers/bank-accounts/" + id + "/verify", null);
        assertThat(self.status()).isEqualTo(403);
        Res verified = call("POST", "/procurement/suppliers/bank-accounts/" + id + "/verify", null, checkerToken);
        assertThat(verified.status()).isEqualTo(200);
        assertThat(verified.body().get("status").asString()).isEqualTo("verified");
        assertThat(ok(200, "POST", "/procurement/suppliers/bank-accounts/" + id + "/revoke", null).get("status")
                .asString()).isEqualTo("revoked");
        JsonNode detail = ok(200, "GET", "/procurement/suppliers/" + partner, null);
        assertThat(detail.get("bankAccounts")).hasSize(1);
        assertThat(detail.get("eligibility").get("eligible").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("catalogue + price history: PO prices are recorded per supplier and summarised")
    void catalogueAndPriceHistory() {
        String a = supplier();
        String b = supplier();
        String product = product();
        ok(200, "PUT", "/procurement/suppliers/" + a + "/products", Map.of("productId", product, "supplierSku", "CEM-42",
                "leadTimeDays", 3, "preferred", true));
        ok(200, "PUT", "/procurement/suppliers/" + a + "/products", Map.of("productId", product, "supplierSku", "CEM-42N",
                "leadTimeDays", 2));
        JsonNode detail = ok(200, "GET", "/procurement/suppliers/" + a, null);
        assertThat(detail.get("products")).hasSize(1);
        assertThat(detail.get("products").get(0).get("supplierSku").asString()).isEqualTo("CEM-42N");
        ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", a, "warehouseId", warehouse, "currencyCode",
                "UGX", "lines", List.of(Map.of("productId", product, "description", "Cement", "quantity", 10, "unitPrice",
                        45000))));
        ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", b, "warehouseId", warehouse, "currencyCode",
                "UGX", "lines", List.of(Map.of("productId", product, "description", "Cement", "quantity", 10, "unitPrice",
                        42000))));
        ok(201, "POST", "/procurement/suppliers/" + b + "/prices", Map.of("productId", product, "unitPrice", 41000,
                "validFrom", today));
        JsonNode history = ok(200, "GET", "/procurement/suppliers/price-history?productId=" + product, null);
        assertThat(history.get("history")).hasSize(3);
        assertThat(history.get("suppliers")).hasSize(2);
        assertThat(history.get("suppliers").get(0).get("partnerId").asString()).isEqualTo(b);
    }

    // ------------------------------------------------------------------ approvals

    @Test
    @DisplayName("approvals: a larger amount needs a new decision; a bill's creator cannot self-approve on post")
    void approvalAmountRecheckAndBillSegregation() {
        String entity = UUID.randomUUID().toString();
        String wf = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ApprovalWorkflow\" (id, \"organizationId\", name, \"entityType\", \"updatedAt\")"
                + " VALUES (?, ?, 'Recheck', 'supplier_payment', now())").params(wf, demoOrg).update();
        jdbc.sql("INSERT INTO \"ApprovalStep\" (id, \"organizationId\", \"workflowId\", \"stepOrder\", name,"
                        + " \"approverPermissions\", \"updatedAt\") VALUES (?, ?, ?, 1, 'Finance', ?, now())")
                .params(UUID.randomUUID().toString(), demoOrg, wf, new String[] {"approvals:decide"}).update();
        try {
            Map<String, Object> first = TenantContext.run(TenantStore.user(demoOrg, adminId),
                    () -> approvals.checkOrRequestApproval("supplier_payment", entity, Map.of("amount", 100)));
            assertThat(first.get("needsApproval")).isEqualTo(true);
            jdbc.sql("UPDATE \"ApprovalRequest\" SET status = 'approved' WHERE id = ?").param(first.get("requestId"))
                    .update();
            Map<String, Object> same = TenantContext.run(TenantStore.user(demoOrg, adminId),
                    () -> approvals.checkOrRequestApproval("supplier_payment", entity, Map.of("amount", 100)));
            assertThat(same.get("needsApproval")).isEqualTo(false);
            Map<String, Object> more = TenantContext.run(TenantStore.user(demoOrg, adminId),
                    () -> approvals.checkOrRequestApproval("supplier_payment", entity, Map.of("amount", 5000)));
            assertThat(more.get("needsApproval")).isEqualTo(true);
            assertThat(more.get("requestId")).isNotEqualTo(first.get("requestId"));
        } finally {
            jdbc.sql("UPDATE \"ApprovalWorkflow\" SET \"isActive\" = false WHERE id = ?").param(wf).update();
        }

        String billWf = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ApprovalWorkflow\" (id, \"organizationId\", name, \"entityType\", \"updatedAt\")"
                + " VALUES (?, ?, 'Bills', 'vendor_bill', now())").params(billWf, demoOrg).update();
        jdbc.sql("INSERT INTO \"ApprovalStep\" (id, \"organizationId\", \"workflowId\", \"stepOrder\", name,"
                        + " \"approverPermissions\", \"updatedAt\") VALUES (?, ?, ?, 1, 'Finance', ?, now())")
                .params(UUID.randomUUID().toString(), demoOrg, billWf, new String[] {"approvals:decide"}).update();
        try {
            String partner = supplier();
            JsonNode bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", partner, "issueDate", today, "lines",
                    List.of(Map.of("description", "Haulage", "quantity", 1, "unitPrice", 500))));
            Res self = as("POST", "/vendor-bills/" + bill.get("id").asString() + "/post", Map.of("overrideApproval", true));
            assertThat(self.status()).isEqualTo(403);
            assertThat(self.body().get("message").asString())
                    .isEqualTo("You cannot approve a bill you created — another approver must decide");
            Res other = call("POST", "/vendor-bills/" + bill.get("id").asString() + "/post", Map.of("overrideApproval",
                    true), as(checkerToken));
            assertThat(other.status()).as(other.raw().body()).isEqualTo(200);
        } finally {
            jdbc.sql("UPDATE \"ApprovalWorkflow\" SET \"isActive\" = false WHERE id = ?").param(billWf).update();
            jdbc.sql("UPDATE \"ApprovalRequest\" SET status = 'cancelled' WHERE \"workflowId\" = ? AND status = 'pending'")
                    .param(billWf).update();
        }
    }

    // ------------------------------------------------------------------ isolation + authz

    @Test
    @DisplayName("tenant isolation + authorization matrix for suppliers and policies")
    void isolationAndAuthorization() {
        String partner = supplier();
        ok(200, "PUT", "/procurement/suppliers/" + partner + "/profile", Map.of("riskClass", "high"));
        assertThat(call("GET", "/procurement/suppliers/" + partner, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/procurement/policies?scopeId=" + branch, null, otherToken).body()).isEmpty();
        assertThat(call("GET", "/procurement/suppliers", null, otherToken).body().get("data").findValuesAsString("id"))
                .doesNotContain(partner);
        assertThat(assertAuthorizationMatrix(SupplierController.class, ProcurementPolicyController.class))
                .isEqualTo(25);
    }
}
