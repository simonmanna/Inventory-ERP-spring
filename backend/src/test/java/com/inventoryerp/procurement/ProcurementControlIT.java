package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountingSeedContributor;
import com.inventoryerp.kernel.events.OutboxWorker;
import com.inventoryerp.procurement.web.ProcurementControlController;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * PROC-3 and PROC-5 on the other organization of the suite, so its budgets, rules and workflows never reach DEMO:
 * budgets (scope, reservation handed from request to order, warn and block, concurrency, lifecycle), delegated
 * purchasing, and purchase requests through the approval engine.
 */
class ProcurementControlIT extends ApiTestSupport {

    @Autowired
    AccountingSeedContributor accounting;

    @Autowired
    OutboxWorker outbox;

    String role;
    String warehouse;
    String supplier;
    String branch;
    String buyerToken;
    String buyerId;
    String checkerToken;
    String today = LocalDate.now().toString();

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeAll
    void fixtures() {
        role = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ?").param(otherOrg).query(String.class)
                .single();
        warehouse = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Main', 'warehouse', now())").params(warehouse, otherOrg, "W-" + sfx()).update();
        branch = branch();
        // Purchase orders resolve an expense account (default_expense) for non-stock lines: give the org a chart.
        new org.springframework.transaction.support.TransactionTemplate(txm).executeWithoutResult(
                st -> accounting.seed(otherOrg));
        supplier = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, 'Fuel supplier', true, now())").params(supplier, otherOrg, "S-" + sfx()).update();
        String email = "buyer-" + sfx() + "@other.test";
        buyerId = createUser(otherOrg, email, "Buyer@12345", role);
        buyerToken = login(otherOrgCode, email, "Buyer@12345");
        String check = "check-" + sfx() + "@other.test";
        createUser(otherOrg, check, "Check@12345", role);
        checkerToken = login(otherOrgCode, check, "Check@12345");
    }

    String branch() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, 'Yard',"
                + " now())").params(id, otherOrg, "B-" + sfx()).update();
        return id;
    }

    Res by(String token, String method, String path, Object body) {
        return call(method, path, body, token);
    }

    JsonNode okBy(String token, int status, String method, String path, Object body) {
        Res r = by(token, method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    String budget(String branchId, Object amount, String mode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Budget " + sfx());
        body.put("startDate", today);
        body.put("endDate", today);
        body.put("branchId", branchId);
        body.put("amount", amount);
        body.put("controlMode", mode);
        String id = okBy(otherToken, 201, "POST", "/procurement/budgets", body).get("id").asString();
        okBy(otherToken, 200, "POST", "/procurement/budgets/" + id + "/activate", null);
        return id;
    }

    Res order(String token, String branchId, Object value, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", supplier);
        body.put("warehouseId", warehouse);
        body.put("branchId", branchId);
        body.put("paymentType", "credit");
        body.put("lines", List.of(Map.of("description", "Diesel", "quantity", 1, "unitPrice", value)));
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return by(token, "POST", "/procurement/purchase-orders", body);
    }

    String request(String branchId, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branchId", branchId);
        body.put("lines", List.of(Map.of("description", "Diesel", "quantity", 1, "estimatedUnitPrice", value)));
        return okBy(buyerToken, 201, "POST", "/procurement/purchase-requests", body).get("id").asString();
    }

    @Test
    @DisplayName("budget: a request reserves, its order takes over the reservation, the next document is refused")
    void reservationHandover() {
        String yard = branch();
        String id = budget(yard, 1000, "block");
        String pr = request(yard, 600);
        JsonNode draft = okBy(otherToken, 200, "GET", "/procurement/budgets/" + id, null);
        assertThat(draft.get("reserved").asDouble()).isZero();

        okBy(buyerToken, 200, "PATCH", "/procurement/purchase-requests/" + pr + "/submit", null);
        assertThat(okBy(otherToken, 200, "GET", "/procurement/budgets/" + id, null).get("reserved").asDouble())
                .isEqualTo(600);

        Res over = order(buyerToken, yard, 500, null);
        assertThat(over.status()).isEqualTo(409);
        assertThat(over.body().get("code").asString()).isEqualTo("PROCUREMENT_BUDGET_EXCEEDED");
        assertThat(over.body().get("budgetId").asString()).isEqualTo(id);

        okBy(buyerToken, 200, "PATCH", "/procurement/purchase-requests/" + pr + "/approve", null);
        assertThat(order(buyerToken, yard, 600, pr).status()).isEqualTo(201);
        JsonNode after = okBy(otherToken, 200, "GET", "/procurement/budgets/" + id, null);
        assertThat(after.get("reserved").asDouble()).isZero();
        assertThat(after.get("committed").asDouble()).isEqualTo(600);
        assertThat(after.get("orders")).hasSize(1);

        Res second = by(buyerToken, "PATCH", "/procurement/purchase-requests/" + request(yard, 500) + "/submit", null);
        assertThat(second.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("budget: warn mode lets an overrun through and records it; another branch is out of scope")
    void warnAndScope() {
        String yard = branch();
        String id = budget(yard, 100, "warn");
        assertThat(order(buyerToken, yard, 500, null).status()).isEqualTo(201);
        assertThat(order(buyerToken, branch(), 5000, null).status()).isEqualTo(201);
        detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"organizationId\" = ? AND entity ="
                + " 'ProcurementBudget' AND \"entityId\" = ? AND action = 'update'").params(otherOrg, id)
                .query(Long.class).single()).isEqualTo(1);

        JsonNode check = okBy(buyerToken, 200, "POST", "/procurement/budget-check", Map.of("kind", "purchase_order",
                "branchId", yard, "lines", List.of(Map.of("amount", 10))));
        assertThat(check.get(0).get("exceeded").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("budget: of two concurrent orders for the last of a budget only one is accepted")
    void concurrentOrders() throws Exception {
        String yard = branch();
        budget(yard, 1000, "block");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                tasks.add(() -> order(buyerToken, yard, 700, null).status());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("budget lifecycle: only a draft is edited or deleted, a revision needs a reason, closed stops counting")
    void lifecycle() {
        String yard = branch();
        String id = budget(yard, 100, "block");
        Map<String, Object> edit = Map.of("name", "Edited", "startDate", today, "endDate", today, "amount", 1);
        assertThat(by(otherToken, "PATCH", "/procurement/budgets/" + id, edit).status()).isEqualTo(400);
        assertThat(by(otherToken, "DELETE", "/procurement/budgets/" + id, null).status()).isEqualTo(400);
        assertThat(by(otherToken, "POST", "/procurement/budgets/" + id + "/revise", Map.of("amount", 200)).status())
                .isEqualTo(400);
        assertThat(okBy(otherToken, 200, "POST", "/procurement/budgets/" + id + "/revise", Map.of("amount", 200,
                "reason", "Second plant")).get("amount").asDouble()).isEqualTo(200);
        assertThat(order(buyerToken, yard, 300, null).status()).isEqualTo(409);
        okBy(otherToken, 200, "POST", "/procurement/budgets/" + id + "/close", null);
        assertThat(order(buyerToken, yard, 300, null).status()).isEqualTo(201);

        Res backwards = by(otherToken, "POST", "/procurement/budgets", Map.of("name", "Bad", "startDate", today,
                "endDate", "2000-01-01", "amount", 1));
        assertThat(backwards.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("delegation: a branch rule stops other buyers and caps the delegate; central buyers pass")
    void delegation() {
        String central = branch();
        okBy(otherToken, 201, "POST", "/procurement/delegations", Map.of("branchId", central, "mode", "central"));
        // The Administrator role holds procurement_delegation:central.
        assertThat(order(buyerToken, central, 10, null).status()).isEqualTo(201);

        String role2 = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\") VALUES"
                + " (?, ?, ?, false, ?, now())").params(role2, otherOrg, "Branch buyer " + role2,
                        new String[] {"purchase_order:create"}).update();
        String email = "local-" + sfx() + "@other.test";
        String localId = createUser(otherOrg, email, "Local@12345", role2);
        String local = login(otherOrgCode, email, "Local@12345");

        Res refused = order(local, central, 10, null);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.body().get("message").asString()).isEqualTo("Purchasing for this branch is handled centrally");

        String yard = branch();
        okBy(otherToken, 201, "POST", "/procurement/delegations", Map.of("branchId", yard, "mode", "delegated",
                "delegateUserId", localId, "maxOrderAmount", 100));
        assertThat(order(local, yard, 100, null).status()).isEqualTo(201);
        assertThat(order(local, yard, 101, null).status()).isEqualTo(403);
    }

    /** A workflow for purchase requests over this amount, removed after the test. */
    String prWorkflow(Object minAmount) {
        return okBy(otherToken, 201, "POST", "/approval-workflows", Map.of("name", "PR " + sfx(), "entityType",
                "purchase_request", "minAmount", minAmount, "steps", List.of(Map.of("stepOrder", 1, "name", "Buyer",
                        "approverPermissions", List.of("purchase_request:approve"), "requiredCount", 1),
                        Map.of("stepOrder", 2, "name", "Finance", "approverPermissions",
                                List.of("purchase_request:approve"), "requiredCount", 1)))).get("id").asString();
    }

    void drainOutbox() {
        drainOutboxFully();
    }

    @Test
    @DisplayName("request approval: below the workflow it is a status change; above it every step must clear")
    void requestThroughEngine() {
        String wf = prWorkflow(1000);
        try {
            String small = request(branch, 10);
            okBy(buyerToken, 200, "PATCH", "/procurement/purchase-requests/" + small + "/submit", null);
            assertThat(by(buyerToken, "GET", "/procurement/purchase-requests/" + small + "/approval", null).raw().body())
                    .isEmpty();
            assertThat(okBy(buyerToken, 200, "PATCH", "/procurement/purchase-requests/" + small + "/approve", null)
                    .get("status").asString()).isEqualTo("approved");

            String big = request(branch, 5000);
            okBy(buyerToken, 200, "PATCH", "/procurement/purchase-requests/" + big + "/submit", null);
            Res own = by(buyerToken, "PATCH", "/procurement/purchase-requests/" + big + "/approve", null);
            assertThat(own.status()).isEqualTo(400);
            assertThat(own.body().get("message").asString()).isEqualTo("You cannot approve a request you created");

            JsonNode first = okBy(checkerToken, 200, "PATCH", "/procurement/purchase-requests/" + big + "/approve", null);
            assertThat(first.get("status").asString()).isEqualTo("submitted");
            assertThat(okBy(otherToken, 200, "PATCH", "/procurement/purchase-requests/" + big + "/approve", null)
                    .get("status").asString()).isEqualTo("approved");
        } finally {
            okBy(otherToken, 200, "DELETE", "/approval-workflows/" + wf, null);
        }
    }

    @Test
    @DisplayName("request approval: a decision in the inbox reaches the request, once, and a rejection keeps its reason")
    void inboxDecision() {
        String wf = okBy(otherToken, 201, "POST", "/approval-workflows", Map.of("name", "PR " + sfx(), "entityType",
                "purchase_request", "steps", List.of(Map.of("stepOrder", 1, "name", "Buyer", "approverPermissions",
                        List.of("purchase_request:approve"), "requiredCount", 1)))).get("id").asString();
        try {
            String id = request(branch, 50);
            okBy(buyerToken, 200, "PATCH", "/procurement/purchase-requests/" + id + "/submit", null);
            String requestId = okBy(buyerToken, 200, "GET", "/procurement/purchase-requests/" + id + "/approval", null)
                    .get("id").asString();
            okBy(checkerToken, 201, "POST", "/approvals/" + requestId + "/decide", Map.of("status", "approved"));
            drainOutbox();
            assertThat(okBy(buyerToken, 200, "GET", "/procurement/purchase-requests/" + id, null).get("status")
                    .asString()).isEqualTo("approved");

            String other = request(branch, 50);
            okBy(buyerToken, 200, "PATCH", "/procurement/purchase-requests/" + other + "/submit", null);
            String otherRequest = okBy(buyerToken, 200, "GET", "/procurement/purchase-requests/" + other + "/approval",
                    null).get("id").asString();
            okBy(checkerToken, 201, "POST", "/approvals/" + otherRequest + "/decide", Map.of("status", "rejected",
                    "comment", "No budget this quarter"));
            drainOutbox();
            JsonNode rejected = okBy(buyerToken, 200, "GET", "/procurement/purchase-requests/" + other, null);
            assertThat(rejected.get("status").asString()).isEqualTo("rejected");
            assertThat(rejected.get("rejectedReason").asString()).isEqualTo("No budget this quarter");
        } finally {
            okBy(otherToken, 200, "DELETE", "/approval-workflows/" + wf, null);
        }
    }

    @Test
    @DisplayName("tenancy: DEMO sees none of this organization's budgets or rules")
    void tenantIsolation() {
        String id = budget(branch(), 5, "warn");
        assertThat(as("GET", "/procurement/budgets/" + id, null).status()).isEqualTo(404);
        assertThat(as("POST", "/procurement/budgets/" + id + "/close", null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("authorization: every budget and delegation route answers 401 without a token and 403 without its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(ProcurementControlController.class)).isEqualTo(13);
    }
}
