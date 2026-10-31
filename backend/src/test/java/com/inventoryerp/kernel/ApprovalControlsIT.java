package com.inventoryerp.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.approvals.ApprovalControlsController;
import com.inventoryerp.kernel.approvals.ControlledActionService;
import com.inventoryerp.kernel.approvals.ControlledActionService.Action;
import com.inventoryerp.kernel.approvals.ControlledActionService.Outcome;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.events.OutboxWorker;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
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
 * CTRL-1 on the other organization of the suite, so workflows and rules never reach the shared DEMO data: workflow and
 * policy config routes, branch and department conditions, department-manager steps, transaction limits, the
 * separation-of-duties pairs and the controlled-action hook, and held configuration changes. Only the stock adjustment
 * case runs in DEMO, because it posts, and it switches its rule off again.
 */
class ApprovalControlsIT extends ApiTestSupport {

    @Autowired
    ControlledActionService controlled;

    @Autowired
    OutboxWorker outbox;

    String role;
    User maker;
    User checker;
    User senior;

    record User(String token, String id) {}

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeAll
    void fixtures() {
        role = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ?").param(otherOrg).query(String.class)
                .single();
        maker = user();
        checker = user();
        senior = user();
    }

    User user() {
        String email = "ctrl-" + sfx() + "@other.test";
        String id = createUser(otherOrg, email, "Ctrl@12345", role);
        return new User(login(otherOrgCode, email, "Ctrl@12345"), id);
    }

    JsonNode okAs(User u, int status, String method, String path, Object body) {
        Res r = call(method, path, body, u.token());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(status);
        return r.body();
    }

    void drainOutbox() {
        drainOutboxFully();
    }

    Map<String, Object> step(int order, String... perms) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("stepOrder", order);
        s.put("name", "Step " + order);
        s.put("approverPermissions", List.of(perms));
        s.put("requiredCount", 1);
        return s;
    }

    String workflow(String entityType, Map<String, Object> extra, Map<String, Object> step) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "WF " + sfx(), "entityType", entityType, "steps",
                List.of(step)));
        body.putAll(extra);
        return okAs(senior, 201, "POST", "/approval-workflows", body).get("id").asString();
    }

    JsonNode request(User u, String entityType, Map<String, Object> snapshot) {
        return okAs(u, 201, "POST", "/approvals/request", Map.of("entityType", entityType, "entityId",
                UUID.randomUUID().toString(), "snapshot", snapshot));
    }

    @Test
    @DisplayName("workflow and policy config routes: create, read, update, delete, validation")
    void configRoutes() {
        String type = "cfg_" + sfx();
        JsonNode w = okAs(senior, 201, "POST", "/approval-workflows", Map.of("name", "Spend", "entityType", type,
                "minAmount", 100, "enforceDistinctApprovers", true, "steps", List.of(step(1, "approvals:decide"))));
        String id = w.get("id").asString();
        assertThat(w.get("steps").get(0).get("approverScope").asString()).isEqualTo("permission");
        assertThat(okAs(senior, 200, "GET", "/approval-workflows/" + id, null).get("entityType").asString())
                .isEqualTo(type);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("minAmount", null);
        patch.put("branchId", "branch-1");
        patch.put("steps", List.of(step(1, "approvals:decide"), step(2, "approvals:manage")));
        JsonNode updated = okAs(senior, 200, "PATCH", "/approval-workflows/" + id, patch);
        assertThat(updated.get("minAmount").isNull()).isTrue();
        assertThat(updated.get("branchId").asString()).isEqualTo("branch-1");
        assertThat(updated.get("steps")).hasSize(2);

        Res unknown = call("PATCH", "/approval-workflows/" + id, Map.of("nope", 1), senior.token());
        assertThat(unknown.status()).isEqualTo(400);
        Res duplicate = call("POST", "/approval-workflows", Map.of("name", "Dup", "entityType", type, "steps",
                List.of(step(1), step(1))), senior.token());
        assertThat(duplicate.status()).isEqualTo(400);
        assertThat(duplicate.body().get("message").asString()).isEqualTo("Duplicate stepOrder values");
        assertThat(as("GET", "/approval-workflows/" + id, null).status()).isEqualTo(404);

        okAs(senior, 200, "DELETE", "/approval-workflows/" + id, null);
        assertThat(call("GET", "/approval-workflows/" + id, null, senior.token()).status()).isEqualTo(404);

        JsonNode p = okAs(senior, 201, "POST", "/approval-policies", Map.of("name", "Legacy", "entityType", type,
                "approverPermissions", List.of("approvals:decide"), "requiredCount", 1));
        String policy = p.get("id").asString();
        assertThat(okAs(senior, 200, "PATCH", "/approval-policies/" + policy, Map.of("requiredCount", 2))
                .get("requiredCount").asInt()).isEqualTo(2);
        okAs(senior, 200, "DELETE", "/approval-policies/" + policy, null);
    }

    @Test
    @DisplayName("conditions: a branch workflow beats the general one and steps follow the department")
    void conditions() {
        String type = "cond_" + sfx();
        String general = workflow(type, Map.of(), step(1, "approvals:decide"));
        String yard = workflow(type, Map.of("branchId", "yard"), step(1, "approvals:manage"));
        assertThat(request(maker, type, Map.of("amount", 5, "branchId", "yard")).get("workflowId").asString())
                .isEqualTo(yard);
        assertThat(request(maker, type, Map.of("amount", 5, "branchId", "office")).get("workflowId").asString())
                .isEqualTo(general);
        assertThat(request(maker, type, Map.of("amount", 5)).get("workflowId").asString()).isEqualTo(general);

        String other = "dept_" + sfx();
        Map<String, Object> onlyLogistics = step(1, "approvals:decide");
        onlyLogistics.put("departmentId", "logistics");
        workflow(other, Map.of(), onlyLogistics);
        // No step applies to this department, so nothing needs approval.
        call("POST", "/approvals/request", Map.of("entityType", other, "entityId", UUID.randomUUID().toString(),
                "snapshot", Map.of("amount", 5, "departmentId", "stores")), maker.token());
        assertThat(jdbc.sql("SELECT count(*) FROM \"ApprovalRequest\" WHERE \"organizationId\" = ? AND"
                + " \"entityType\" = ?").params(otherOrg, other).query(Long.class).single()).isZero();
        assertThat(request(maker, other, Map.of("amount", 5, "departmentId", "logistics")).get("status").asString())
                .isEqualTo("pending");
    }

    @Test
    @DisplayName("hierarchy: a department-manager step takes only the manager, the requester's department is looked up")
    void departmentManager() {
        String department = UUID.randomUUID().toString();
        String managerEmployee = employee(senior.id(), null);
        jdbc.sql("INSERT INTO \"HrDepartment\" (id, \"organizationId\", code, name, \"managerId\", \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Stores', ?, now())").params(department, otherOrg, "D-" + sfx(), managerEmployee).update();
        employee(maker.id(), department);

        String type = "mgr_" + sfx();
        Map<String, Object> managerStep = step(1);
        managerStep.put("approverScope", "department_manager");
        workflow(type, Map.of("departmentId", department), managerStep);

        JsonNode req = request(maker, type, Map.of("amount", 5));
        String id = req.get("id").asString();
        Res wrong = call("POST", "/approvals/" + id + "/decide", Map.of("status", "approved"), checker.token());
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.body().get("message").asString()).isEqualTo("Only the department manager can decide on this step");
        assertThat(okAs(senior, 201, "POST", "/approvals/" + id + "/decide", Map.of("status", "approved")).get("status")
                .asString()).isEqualTo("approved");
    }

    String employee(String userId, String departmentId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrEmployee\" (id, \"organizationId\", \"employeeCode\", \"firstName\", \"userId\","
                + " \"departmentId\", \"updatedAt\") VALUES (?, ?, ?, 'T', ?, ?, now())")
                .params(id, otherOrg, "E-" + sfx(), userId, departmentId).update();
        return id;
    }

    @Test
    @DisplayName("limits: an approver above their limit is refused and the step stays open; own limit beats the role's")
    void limits() {
        String type = "lim_" + sfx();
        workflow(type, Map.of(), step(1, "approvals:decide"));
        okAs(senior, 201, "POST", "/approval-controls/limits", Map.of("name", "Deciders", "entityType", type,
                "permission", "approvals:decide", "maxAmount", 100));
        okAs(senior, 201, "POST", "/approval-controls/limits", Map.of("name", "Senior", "entityType", type, "userId",
                senior.id(), "maxAmount", 5000));

        String id = request(maker, type, Map.of("amount", 1000)).get("id").asString();
        Res over = call("POST", "/approvals/" + id + "/decide", Map.of("status", "approved"), checker.token());
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().get("message").asString()).isEqualTo("This amount is above your approval limit of 100");
        assertThat(okAs(senior, 201, "POST", "/approvals/" + id + "/decide", Map.of("status", "approved")).get("status")
                .asString()).isEqualTo("approved");

        String rejected = request(maker, type, Map.of("amount", 1000)).get("id").asString();
        assertThat(okAs(checker, 201, "POST", "/approvals/" + rejected + "/decide", Map.of("status", "rejected"))
                .get("status").asString()).isEqualTo("rejected");

        Res both = call("POST", "/approval-controls/limits", Map.of("name", "Bad", "userId", "u", "permission", "p",
                "maxAmount", 1), senior.token());
        assertThat(both.status()).isEqualTo(400);
        Res negative = call("POST", "/approval-controls/limits", Map.of("name", "Bad", "userId", "u", "maxAmount", -1),
                senior.token());
        assertThat(negative.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("separation of duties: the catalogue lists the five pairs; weighbridge cannot be switched off")
    void sodCatalogue() {
        JsonNode rules = okAs(senior, 200, "GET", "/approval-controls/sod-rules", null);
        assertThat(rules).hasSize(5);
        Res off = call("PUT", "/approval-controls/sod-rules/" + SegregationOfDutiesService.WEIGHBRIDGE_EXCEPTION,
                Map.of("isActive", false), senior.token());
        assertThat(off.status()).isEqualTo(400);
        assertThat(call("PUT", "/approval-controls/sod-rules/nope", Map.of("isActive", true), senior.token()).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("hook: credit override and supplier bill pass through approval, then separation of duties, with audit")
    void controlledActionHook() {
        String entity = UUID.randomUUID().toString();
        Action action = new Action(SegregationOfDutiesService.CREDIT_OVERRIDE, "Invoice", entity,
                new BigDecimal("2500"), null, null, List.of(maker.id()), Map.of("invoice", "INV-1"));

        // No rule and no workflow: nothing to clear.
        assertThat(as(maker, action).allowed()).isTrue();

        okAs(senior, 200, "PUT", "/approval-controls/sod-rules/" + SegregationOfDutiesService.CREDIT_OVERRIDE,
                Map.of("isActive", true));
        assertThatThrownBy(() -> as(maker, action)).isInstanceOf(ApiException.class)
                .hasMessageContaining("Separation of duties");
        assertThat(as(checker, action).allowed()).isTrue();
        detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"organizationId\" = ? AND entity = ? AND"
                + " \"entityId\" = ? AND action = 'reject'").params(otherOrg, SegregationOfDutiesService.AUDIT_ENTITY,
                        entity).query(Long.class).single()).isEqualTo(1);

        // With a workflow the action waits, and the maker cannot decide even when another user raised the request.
        workflow(SegregationOfDutiesService.SUPPLIER_BILL_APPROVAL, Map.of(), step(1));
        okAs(senior, 200, "PUT", "/approval-controls/sod-rules/" + SegregationOfDutiesService.SUPPLIER_BILL_APPROVAL,
                Map.of("isActive", true));
        Action bill = new Action(SegregationOfDutiesService.SUPPLIER_BILL_APPROVAL, "VendorBill",
                UUID.randomUUID().toString(), new BigDecimal("900"), null, null, List.of(maker.id()), null);
        Outcome pending = as(checker, bill);
        assertThat(pending.allowed()).isFalse();
        Res byMaker = call("POST", "/approvals/" + pending.approvalRequestId() + "/decide",
                Map.of("status", "approved"), maker.token());
        assertThat(byMaker.status()).isEqualTo(403);
        okAs(senior, 201, "POST", "/approvals/" + pending.approvalRequestId() + "/decide", Map.of("status", "approved"));
        // Cleared by someone else's approval, the maker may now finish the action.
        assertThat(as(maker, bill).allowed()).isTrue();
    }

    Outcome as(User u, Action a) {
        return TenantContext.run(TenantStore.user(otherOrg, u.id()), () -> controlled.authorize(a));
    }

    @Test
    @DisplayName("stock adjustment: with the rule on the maker cannot post it, someone else can (DEMO)")
    void stockAdjustmentPair() {
        String adminRole = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id JOIN \"User\""
                + " u ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' LIMIT 1").query(String.class).single();
        String email = "sod-" + sfx() + "@demo.test";
        String id = createUser(demoOrg, email, "Sod@123456", adminRole);
        String token = login("DEMO", email, "Sod@123456");
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        String location = ok(201, "POST", "/inventory/locations", Map.of("code", "SOD-" + sfx(), "name", "SoD " + sfx(),
                "type", "warehouse")).get("id").asString();
        String uom = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'TON'")
                .param(demoOrg).query(String.class).single();
        String product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"purchaseUomId\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, 0, ?, ?, ?, now())")
                .params(product, demoOrg, "SOD-" + sfx(), "SoD item " + sfx(), uom, uom, uom).update();

        Map<String, Object> body = Map.of("locationId", location, "responsibleById", id, "approvedById", adminId,
                "reason", "found", "items", List.of(Map.of("productId", product, "qtyActual", 5)));
        Res created = call("POST", "/inventory/adjustments", body, Map.of("Authorization", "Bearer " + token,
                "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(created.status()).as(created.raw().body()).isLessThan(300);
        String adjustment = created.body().get("id").asString();

        ok(200, "PUT", "/approval-controls/sod-rules/" + SegregationOfDutiesService.STOCK_ADJUSTMENT,
                Map.of("isActive", true));
        try {
            Res byMaker = call("POST", "/inventory/adjustments/" + adjustment + "/approve", Map.of(), Map.of(
                    "Authorization", "Bearer " + token, "Idempotency-Key", UUID.randomUUID().toString()));
            assertThat(byMaker.status()).isEqualTo(403);
            Res byOther = call("POST", "/inventory/adjustments/" + adjustment + "/approve", Map.of(), Map.of(
                    "Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
            assertThat(byOther.status()).as(byOther.raw().body()).isLessThan(300);
            assertThat(byOther.body().get("status").asString()).isEqualTo("completed");
        } finally {
            ok(200, "PUT", "/approval-controls/sod-rules/" + SegregationOfDutiesService.STOCK_ADJUSTMENT,
                    Map.of("isActive", false));
        }
    }

    @Test
    @DisplayName("controlled config: with a config_change workflow a change waits, the maker cannot approve it, approval applies it")
    void controlledConfigChange() {
        Res before = call("GET", "/approval-controls/sod-rules", null, senior.token());
        assertThat(before.status()).isEqualTo(200);
        String workflowId = workflow("config_change", Map.of(), step(1, "approvals:manage"));

        JsonNode held = okAs(senior, 200, "PUT", "/approval-controls/sod-rules/"
                + SegregationOfDutiesService.PRODUCTION_LOSS_WRITE_OFF, Map.of("isActive", true, "notes", "from audit"));
        assertThat(held.get("pendingApproval").asBoolean()).isTrue();
        String requestId = held.get("requestId").asString();
        assertThat(rule(SegregationOfDutiesService.PRODUCTION_LOSS_WRITE_OFF).get("isActive").asBoolean()).isFalse();

        Res self = call("POST", "/approvals/" + requestId + "/decide", Map.of("status", "approved"), senior.token());
        assertThat(self.status()).isEqualTo(400);
        assertThat(self.body().get("message").asString()).isEqualTo("You cannot approve a request you created");

        okAs(checker, 201, "POST", "/approvals/" + requestId + "/decide", Map.of("status", "approved"));
        drainOutbox();
        assertThat(rule(SegregationOfDutiesService.PRODUCTION_LOSS_WRITE_OFF).get("isActive").asBoolean()).isTrue();
        JsonNode changes = okAs(senior, 200, "GET", "/approval-controls/config-changes?status=applied", null);
        assertThat(changes).isNotEmpty();

        // Removing the control is itself controlled.
        JsonNode lift = okAs(senior, 200, "DELETE", "/approval-workflows/" + workflowId, null);
        assertThat(lift.get("pendingApproval").asBoolean()).isTrue();
        okAs(checker, 201, "POST", "/approvals/" + lift.get("requestId").asString() + "/decide",
                Map.of("status", "approved"));
        drainOutbox();
        assertThat(call("GET", "/approval-workflows/" + workflowId, null, senior.token()).status()).isEqualTo(404);
    }

    JsonNode rule(String pairKey) {
        for (JsonNode r : okAs(senior, 200, "GET", "/approval-controls/sod-rules", null)) {
            if (pairKey.equals(r.get("pairKey").asString())) {
                return r;
            }
        }
        throw new AssertionError(pairKey);
    }

    @Test
    @DisplayName("tenancy: another organization cannot see or change a limit")
    void tenantIsolation() {
        String id = okAs(senior, 201, "POST", "/approval-controls/limits", Map.of("name", "Mine", "userId", senior.id(),
                "entityType", "tenancy_" + sfx(), "maxAmount", 10)).get("id").asString(); // scoped: no leak into other tests
        assertThat(as("DELETE", "/approval-controls/limits/" + id, null).status()).isEqualTo(404);
        for (JsonNode l : ok(200, "GET", "/approval-controls/limits", null)) {
            assertThat(l.get("id").asString()).isNotEqualTo(id);
        }
    }

    @Test
    @DisplayName("authorization: every approval-controls route answers 401 without a token and 403 without its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(ApprovalControlsController.class)).isEqualTo(7);
    }
}
