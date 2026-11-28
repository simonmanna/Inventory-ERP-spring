package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S094 C1 (§4.2): a sale's approval is its own dimension. A draft that needs approval is submitted explicitly
 * (confirm never submits), cannot change its commercial content while it waits, is approved by someone other than its
 * makers (OD-8), and the approval is bound to the content and reasons approved. Every step goes through the HTTP API.
 */
class SalesApprovalIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String product;
    String customer;
    String yard;
    /** Sales rep: write + confirm, no approval. */
    User rep;
    /** Sales manager: write + confirm + approve. */
    User manager;
    /** Approver only. */
    User checker;
    String readerToken;

    record User(String id, String token) {}

    User user(String name, String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, name + " " + sfx, permissions).update();
        String email = name + "-" + sfx + "@demo.test";
        String id = createUser(demoOrg, email, "Approve@12345", role);
        return new User(id, login("DEMO", email, "Approve@12345"));
    }

    @BeforeAll
    void fixtures() {
        product = ok(201, "POST", "/products", Map.of("code", "APR-" + sfx, "name", "Approval item " + sfx,
                "productType", "stockable", "trackInventory", true, "salesPrice", 100, "costPrice", 40)).get("id")
                .asString();
        yard = ok(201, "POST", "/inventory/locations", Map.of("code", "APY-" + sfx, "name", "Approval yard " + sfx,
                "type", "warehouse")).get("id").asString();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "APC-" + sfx, "Approval cust " + sfx)
                .update();
        jdbc.sql("INSERT INTO \"StockItem\" (id, \"organizationId\", \"productId\", \"locationId\", \"variantKey\","
                + " quantity, \"updatedAt\") VALUES (?, ?, ?, ?, '', ?, now())").params(UUID.randomUUID().toString(),
                        demoOrg, product, yard, new BigDecimal("1000")).update();
        rep = user("apr-rep", "sales:order:read", "sales:order:write", "sales:order:confirm");
        // approvers see every order (sales:order:read_all), as an approver role does
        manager = user("apr-mgr", "sales:order:read", "sales:order:read_all", "sales:order:write", "sales:order:confirm",
                "sales:order:approve");
        checker = user("apr-chk", "sales:order:read", "sales:order:read_all", "sales:order:approve");
        readerToken = user("apr-read", "sales:order:read").token();
    }

    /** Orders above 500 need approval (10 units = 1,000). */
    @BeforeEach
    void policy() {
        ok(200, "PUT", "/sales/settings", Map.of("orderApprovalAmount", 500));
    }

    @AfterEach
    void reset() {
        ok(200, "PUT", "/sales/settings", Map.of("orderApprovalAmount", 0, "orderApprovalAlways", false,
                "confirmOnApproval", true, "allowSelfApproval", false, "stockCheckPolicy", "warn"));
    }

    // ------------------------------------------------------------------ helpers

    Map<String, Object> body(int qty) {
        return Map.of("partnerId", customer, "locationId", yard, "lines", List.of(Map.of("productId", product,
                "quantity", qty)));
    }

    Map<String, Object> lines(int qty) {
        return Map.of("lines", List.of(Map.of("productId", product, "quantity", qty)));
    }

    JsonNode by(User u, int status, String method, String path, Object body) {
        Res r = call(method, path, body, u.token());
        assertThat(r.status()).as(method + " " + path + ": " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    JsonNode order(User u, int qty) {
        return by(u, 201, "POST", "/sales/orders", body(qty));
    }

    static String path(JsonNode o) {
        return "/sales/orders/" + o.get("id").asString();
    }

    static String message(Res r) {
        return r.body().get("message").asString();
    }

    static List<String> actions(JsonNode o) {
        List<String> out = new ArrayList<>();
        o.get("actions").forEach(a -> out.add(a.asString()));
        return out;
    }

    List<String> listed(String filter) {
        List<String> ids = new ArrayList<>();
        ok(200, "GET", "/sales/orders?partnerId=" + customer + "&pageSize=100&" + filter, null).get("data")
                .forEach(o -> ids.add(o.get("id").asString()));
        return ids;
    }

    /** Runs inside a tenant transaction (row-level security applies to raw SQL in CI). */
    <T> T tenant(Supplier<T> work) {
        List<T> out = new ArrayList<>(1);
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> out.add(work.get())));
        return out.getFirst();
    }

    /** Audit rows of the order with this action and {@code newValues.key = value}. */
    long audits(JsonNode o, String action, String key, String value) {
        return tenant(() -> jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'SalesOrder' AND \"entityId\" = ?"
                + " AND action::text = ? AND \"newValues\"->>? = ?").params(o.get("id").asString(), action, key, value)
                .query(Long.class).single());
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("AP-1 / AP-10: only a sale that needs approval is submitted; confirm never submits it")
    void submitOnlyWhenRequired() {
        JsonNode small = order(rep, 1);
        assertThat(small.get("approvalStatus").asString()).isEqualTo("not_required");
        assertThat(actions(small)).contains("confirm").doesNotContain("submit_approval");
        assertThat(message(call("POST", path(small) + "/submit-approval", null, rep.token())))
                .isEqualTo("This sale needs no approval; confirm it");

        JsonNode big = order(rep, 10);
        assertThat(big.get("status").asString()).isEqualTo("draft");
        assertThat(big.get("approvalStatus").asString()).isEqualTo("required");
        assertThat(big.get("approvalReasons").toString()).contains("Total exceeds the approval amount of 500");
        assertThat(actions(big)).contains("submit_approval").doesNotContain("confirm");
        Res refused = call("POST", path(big) + "/confirm", null, rep.token());
        assertThat(refused.status()).isEqualTo(400);
        assertThat(message(refused)).isEqualTo("This sale needs approval: submit it for approval first");
        assertThat(by(rep, 200, "GET", path(big), null).get("approvalStatus").asString()).isEqualTo("required");

        JsonNode pending = by(rep, 200, "POST", path(big) + "/submit-approval", null);
        assertThat(pending.get("status").asString()).isEqualTo("draft");
        assertThat(pending.get("approvalStatus").asString()).isEqualTo("pending");
        assertThat(pending.get("stage").asString()).isEqualTo("pending_approval");
        assertThat(pending.get("approvalSubmittedById").asString()).isEqualTo(rep.id());
        assertThat(pending.get("approvalSubmittedHash").asString()).hasSize(64);
        assertThat(actions(pending)).containsExactly("withdraw_approval");
        assertThat(message(call("POST", path(big) + "/submit-approval", null, rep.token())))
                .isEqualTo("This sale is already waiting for approval");
        assertThat(message(call("POST", path(big) + "/confirm", null, rep.token())))
                .isEqualTo("This sale is waiting for approval");
        // ADR-S094 §4.6: the legacy status value still filters (through the derived stage), and so does the new one
        assertThat(listed("status=pending_approval")).contains(big.get("id").asString())
                .doesNotContain(small.get("id").asString());
        assertThat(listed("approvalStatus=pending")).contains(big.get("id").asString());
        assertThat(listed("status=draft")).contains(big.get("id").asString(), small.get("id").asString());
    }

    @Test
    @DisplayName("AP-2 / AP-3: commercial edits are refused while pending; the submitter withdraws to edit")
    void pendingLocksContent() {
        JsonNode o = order(rep, 10);
        by(rep, 200, "POST", path(o) + "/submit-approval", null);
        Res edit = call("PUT", path(o), lines(12), rep.token());
        assertThat(edit.status()).isEqualTo(400);
        assertThat(message(edit)).isEqualTo("This sale is waiting for approval: withdraw it before changing its"
                + " customer, lines, prices or terms");
        assertThat(by(rep, 200, "GET", path(o), null).get("lines").get(0).get("quantity").asDouble()).isEqualTo(10);
        JsonNode noted = by(rep, 200, "PUT", path(o), Map.of("notes", "Deliver after 2pm"));
        assertThat(noted.get("approvalStatus").asString()).as("notes are not approvable content").isEqualTo("pending");

        Res foreign = call("POST", path(o) + "/withdraw-approval", null, manager.token());
        assertThat(foreign.status()).isEqualTo(403);
        assertThat(message(foreign)).isEqualTo("Only the person who submitted this sale for approval can withdraw it");
        JsonNode back = by(rep, 200, "POST", path(o) + "/withdraw-approval", Map.of("reason", "Customer wants more"));
        assertThat(back.get("approvalStatus").asString()).isEqualTo("required");
        assertThat(back.get("stage").asString()).isEqualTo("draft");
        assertThat(by(rep, 200, "PUT", path(o), lines(12)).get("approvalStatus").asString()).isEqualTo("required");
        assertThat(message(call("POST", path(o) + "/withdraw-approval", null, rep.token())))
                .isEqualTo("This sale is not waiting for approval");
        assertThat(audits(o, "update", "approval", "withdrawn")).isEqualTo(1);
        assertThat(audits(o, "update", "approval", "submitted")).isEqualTo(1);
    }

    @Test
    @DisplayName("AP-4 / OD-8: the creator, the submitter and the last editor of its content cannot approve")
    void separationOfDuties() {
        JsonNode own = order(manager, 10);
        JsonNode ownPending = by(manager, 200, "POST", path(own) + "/submit-approval", null);
        assertThat(actions(ownPending)).contains("withdraw_approval", "reject").doesNotContain("approve");
        Res self = call("POST", path(own) + "/approve", Map.of(), manager.token());
        assertThat(self.status()).isEqualTo(403);
        assertThat(message(self)).isEqualTo("You cannot approve your own order");
        JsonNode approved = by(checker, 200, "POST", path(own) + "/approve", Map.of("comment", "OK"));
        assertThat(approved.get("status").asString()).isEqualTo("confirmed");
        assertThat(approved.get("approvedById").asString()).isEqualTo(checker.id());

        // the manager edits the rep's quantities, the rep submits: the manager is a maker of that content
        JsonNode edited = order(rep, 10);
        by(manager, 200, "PUT", path(edited), lines(11));
        by(rep, 200, "POST", path(edited) + "/submit-approval", null);
        assertThat(message(call("POST", path(edited) + "/approve", Map.of(), manager.token())))
                .isEqualTo("You cannot approve your own order");
        assertThat(by(checker, 200, "POST", path(edited) + "/approve", Map.of()).get("approvalStatus").asString())
                .isEqualTo("approved");

        // a notes-only edit does not make someone a maker
        JsonNode noted = order(rep, 10);
        by(manager, 200, "PUT", path(noted), Map.of("notes", "Call before delivery"));
        by(rep, 200, "POST", path(noted) + "/submit-approval", null);
        assertThat(by(manager, 200, "POST", path(noted) + "/approve", Map.of()).get("approvalStatus").asString())
                .isEqualTo("approved");

        // a sales administrator approves their own order only when the organization allows it, and it is audited
        JsonNode admins = ok(201, "POST", "/sales/orders", body(10));
        ok(200, "POST", path(admins) + "/submit-approval", null);
        assertThat(as("POST", path(admins) + "/approve", Map.of()).status()).isEqualTo(403);
        ok(200, "PUT", "/sales/settings", Map.of("allowSelfApproval", true));
        assertThat(ok(200, "POST", path(admins) + "/approve", Map.of()).get("approvalStatus").asString())
                .isEqualTo("approved");
        assertThat(audits(admins, "approve", "selfApproval", "true")).isEqualTo(1);
        assertThat(audits(own, "approve", "selfApproval", "false")).isEqualTo(1);
        // ... never someone without sales:configure
        JsonNode mine = order(manager, 10);
        by(manager, 200, "POST", path(mine) + "/submit-approval", null);
        assertThat(call("POST", path(mine) + "/approve", Map.of(), manager.token()).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("AP-5 / AP-9: an approval is bound to the content approved; an order confirms on approval per setting")
    void approvalBoundToContent() {
        JsonNode auto = order(rep, 10);
        by(rep, 200, "POST", path(auto) + "/submit-approval", null);
        JsonNode confirmed = by(checker, 200, "POST", path(auto) + "/approve", Map.of());
        assertThat(confirmed.get("status").asString()).isEqualTo("confirmed");
        assertThat(confirmed.get("approvalStatus").asString()).isEqualTo("approved");
        assertThat(confirmed.get("approvedHash").asString()).isEqualTo(confirmed.get("approvalSubmittedHash")
                .asString());

        ok(200, "PUT", "/sales/settings", Map.of("confirmOnApproval", false));
        JsonNode o = order(rep, 10);
        by(rep, 200, "POST", path(o) + "/submit-approval", null);
        JsonNode approved = by(checker, 200, "POST", path(o) + "/approve", Map.of("comment", "Good customer"));
        assertThat(approved.get("status").asString()).isEqualTo("draft");
        assertThat(approved.get("approvalStatus").asString()).isEqualTo("approved");
        assertThat(actions(by(rep, 200, "GET", path(o), null))).containsExactly("confirm");
        assertThat(by(rep, 200, "PUT", path(o), Map.of("notes", "Gate 3")).get("approvalStatus").asString())
                .isEqualTo("approved");
        JsonNode changed = by(rep, 200, "PUT", path(o), lines(11));
        assertThat(changed.get("approvalStatus").asString()).isEqualTo("required");
        assertThat(message(call("POST", path(o) + "/confirm", null, rep.token())))
                .isEqualTo("This sale needs approval: submit it for approval first");
        // once lapsed, an approval does not come back by restoring the content: it is submitted again
        assertThat(by(rep, 200, "PUT", path(o), lines(10)).get("approvalStatus").asString()).isEqualTo("required");
        by(rep, 200, "POST", path(o) + "/submit-approval", null);
        by(checker, 200, "POST", path(o) + "/approve", Map.of());
        // an edit that takes the sale out of policy scope needs no approval at all
        assertThat(by(rep, 200, "PUT", path(o), lines(4)).get("approvalStatus").asString()).isEqualTo("not_required");
        JsonNode done = by(rep, 200, "POST", path(o) + "/confirm", null);
        assertThat(done.get("status").asString()).isEqualTo("confirmed");
        assertThat(done.get("approvalStatus").asString()).isEqualTo("not_required");
    }

    @Test
    @DisplayName("AP-6: a rejected sale is resubmitted unchanged only with a justification")
    void rejectAndResubmit() {
        JsonNode o = order(rep, 10);
        by(rep, 200, "POST", path(o) + "/submit-approval", null);
        JsonNode rejected = by(checker, 200, "POST", path(o) + "/reject", Map.of("reason", "Price too low"));
        assertThat(rejected.get("status").asString()).isEqualTo("draft");
        assertThat(rejected.get("approvalStatus").asString()).isEqualTo("rejected");
        assertThat(rejected.get("approvalReasons").get(0).asString()).isEqualTo("Rejected: Price too low");
        assertThat(actions(by(rep, 200, "GET", path(o), null))).containsExactly("submit_approval");
        assertThat(message(call("POST", path(o) + "/submit-approval", null, rep.token())))
                .isEqualTo("This sale was rejected and has not changed since: give a justification to submit it again");
        JsonNode again = by(rep, 200, "POST", path(o) + "/submit-approval", Map.of("justification",
                "Customer pays upfront"));
        assertThat(again.get("approvalStatus").asString()).isEqualTo("pending");
        assertThat(again.get("approvalJustification").asString()).isEqualTo("Customer pays upfront");
        assertThat(again.get("approvalReasons").toString()).contains("Total exceeds the approval amount");

        by(checker, 200, "POST", path(o) + "/reject", Map.of("reason", "Still too low"));
        JsonNode changed = by(rep, 200, "PUT", path(o), lines(9));
        assertThat(changed.get("approvalStatus").asString()).as("changed content is evaluated afresh")
                .isEqualTo("required");
        assertThat(by(rep, 200, "POST", path(o) + "/submit-approval", null).get("approvalStatus").asString())
                .isEqualTo("pending");
    }

    @Test
    @DisplayName("AP-7: a stricter rule asks for approval of its new reason only; a relaxed rule revokes nothing")
    void ruleChanges() {
        ok(200, "PUT", "/sales/settings", Map.of("confirmOnApproval", false));
        JsonNode o = order(rep, 10);
        by(rep, 200, "POST", path(o) + "/submit-approval", null);
        by(checker, 200, "POST", path(o) + "/approve", Map.of());
        ok(200, "PUT", "/sales/settings", Map.of("orderApprovalAlways", true));
        JsonNode stricter = by(rep, 200, "GET", path(o), null);
        assertThat(stricter.get("approvalStatus").asString()).isEqualTo("required");
        assertThat(stricter.get("approvalReasons").toString()).contains("Every sales order needs approval");
        assertThat(message(call("POST", path(o) + "/confirm", null, rep.token())))
                .isEqualTo("This sale needs approval: submit it for approval first");
        by(rep, 200, "POST", path(o) + "/submit-approval", null);
        JsonNode reapproved = by(checker, 200, "POST", path(o) + "/approve", Map.of());
        assertThat(reapproved.get("approvedReasons").toString()).contains("Every sales order needs approval",
                "Total exceeds the approval amount of 500");

        ok(200, "PUT", "/sales/settings", Map.of("orderApprovalAlways", false, "orderApprovalAmount", 0));
        assertThat(by(rep, 200, "GET", path(o), null).get("approvalStatus").asString()).isEqualTo("approved");
        assertThat(by(rep, 200, "POST", path(o) + "/confirm", null).get("status").asString()).isEqualTo("confirmed");
    }

    @Test
    @DisplayName("AP-8: a cash sale is never confirmed by its approval")
    void cashSaleNeverAutoConfirms() {
        JsonNode o = order(rep, 10);
        tenant(() -> jdbc.sql("UPDATE \"SalesOrder\" SET \"saleType\" = 'cash', \"paymentMode\" = 'cash' WHERE id = ?")
                .param(o.get("id").asString()).update());
        by(rep, 200, "POST", path(o) + "/submit-approval", null);
        JsonNode approved = by(checker, 200, "POST", path(o) + "/approve", Map.of());
        assertThat(approved.get("status").asString()).isEqualTo("draft");
        assertThat(approved.get("approvalStatus").asString()).isEqualTo("approved");
        assertThat(by(rep, 200, "POST", path(o) + "/confirm", null).get("status").asString()).isEqualTo("confirmed");
    }

    @Test
    @DisplayName("AP-9: when the confirm after an approval is refused, the approval stands and the refusal is returned")
    void confirmRefusedAfterApproval() {
        ok(200, "PUT", "/sales/settings", Map.of("stockCheckPolicy", "block"));
        JsonNode o = order(rep, 5000);
        by(rep, 200, "POST", path(o) + "/submit-approval", null);
        JsonNode approved = by(checker, 200, "POST", path(o) + "/approve", Map.of());
        assertThat(approved.get("status").asString()).isEqualTo("draft");
        assertThat(approved.get("approvalStatus").asString()).isEqualTo("approved");
        assertThat(approved.get("confirmError").asString()).contains("Not enough stock");
        ok(200, "PUT", "/sales/settings", Map.of("stockCheckPolicy", "warn"));
        assertThat(by(rep, 200, "POST", path(o) + "/confirm", null).get("status").asString()).isEqualTo("confirmed");
    }

    @Test
    @DisplayName("approvals inbox: a decision there approves the sale; withdrawing cancels it; a maker's decision does not count")
    void approvalsInbox() {
        String wf = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ApprovalWorkflow\" (id, \"organizationId\", name, \"entityType\", \"updatedAt\")"
                + " VALUES (?, ?, ?, 'sales_order', now())").params(wf, demoOrg, "Sale approval " + sfx).update();
        jdbc.sql("INSERT INTO \"ApprovalStep\" (id, \"organizationId\", \"workflowId\", \"stepOrder\", name,"
                + " \"approverPermissions\", \"updatedAt\") VALUES (?, ?, ?, 1, 'Sales manager', ?, now())").params(UUID
                        .randomUUID().toString(), demoOrg, wf, new String[] {"sales:order:approve"}).update();
        try {
            JsonNode o = order(rep, 10);
            String request = by(rep, 200, "POST", path(o) + "/submit-approval", null).get("approvalRequestId")
                    .asString();
            Res decided = call("POST", "/approvals/" + request + "/decide", Map.of("status", "approved"),
                    checker.token());
            assertThat(decided.status()).as(decided.raw().body()).isIn(200, 201);
            drainOutboxFully();
            JsonNode after = by(rep, 200, "GET", path(o), null);
            assertThat(after.get("approvalStatus").asString()).isEqualTo("approved");
            assertThat(after.get("status").asString()).isEqualTo("confirmed");
            assertThat(after.get("approvedById").asString()).isEqualTo(checker.id());

            JsonNode w = order(rep, 10);
            String wr = by(rep, 200, "POST", path(w) + "/submit-approval", null).get("approvalRequestId").asString();
            by(rep, 200, "POST", path(w) + "/withdraw-approval", null);
            assertThat(tenant(() -> jdbc.sql("SELECT status::text FROM \"ApprovalRequest\" WHERE id = ?").param(wr)
                    .query(String.class).single())).isEqualTo("cancelled");

            // the manager changed the quantities, the rep submitted: the manager's inbox approval does not count
            JsonNode made = order(rep, 10);
            by(manager, 200, "PUT", path(made), lines(12));
            String mr = by(rep, 200, "POST", path(made) + "/submit-approval", null).get("approvalRequestId")
                    .asString();
            Res byMaker = call("POST", "/approvals/" + mr + "/decide", Map.of("status", "approved"), manager.token());
            assertThat(byMaker.status()).as(byMaker.raw().body()).isIn(200, 201);
            drainOutboxFully();
            JsonNode refused = by(rep, 200, "GET", path(made), null);
            assertThat(refused.get("status").asString()).isEqualTo("draft");
            assertThat(refused.get("approvalStatus").asString()).isEqualTo("required");
            assertThat(audits(made, "update", "approval", "refused")).isEqualTo(1);
        } finally {
            jdbc.sql("UPDATE \"ApprovalWorkflow\" SET \"isActive\" = false WHERE id = ?").param(wf).update();
            tenant(() -> jdbc.sql("UPDATE \"ApprovalRequest\" SET status = 'cancelled' WHERE \"workflowId\" = ? AND"
                    + " status = 'pending'").param(wf).update());
        }
    }

    @Test
    @DisplayName("I1 and the state checks are enforced by the database")
    void databaseChecks() {
        String id = order(rep, 1).get("id").asString();
        assertThatThrownBy(() -> tenant(() -> jdbc.sql("UPDATE \"SalesOrder\" SET status = 'pending_approval' WHERE"
                + " id = ?").param(id).update())).hasMessageContaining("SalesOrder_status_check");
        assertThatThrownBy(() -> tenant(() -> jdbc.sql("UPDATE \"SalesOrder\" SET status = 'confirmed',"
                + " \"approvalStatus\" = 'pending' WHERE id = ?").param(id).update()))
                .hasMessageContaining("SalesOrder_approval_state_check");
        assertThatThrownBy(() -> tenant(() -> jdbc.sql("UPDATE \"SalesOrder\" SET \"saleType\" = 'cash' WHERE id = ?")
                .param(id).update())).hasMessageContaining("SalesOrder_cash_check");
        assertThatThrownBy(() -> tenant(() -> jdbc.sql("UPDATE \"SalesOrder\" SET \"approvalStatus\" = 'maybe' WHERE"
                + " id = ?").param(id).update())).hasMessageContaining("SalesOrder_approvalStatus_check");
    }

    @Test
    @DisplayName("authorization and tenant isolation of the approval routes")
    void authorizationAndTenancy() {
        JsonNode o = order(rep, 10);
        assertThat(call("POST", path(o) + "/submit-approval", null, readerToken).status()).isEqualTo(403);
        by(rep, 200, "POST", path(o) + "/submit-approval", null);
        assertThat(call("POST", path(o) + "/withdraw-approval", null, readerToken).status()).isEqualTo(403);
        assertThat(call("POST", path(o) + "/approve", Map.of(), rep.token()).status()).isEqualTo(403);
        assertThat(call("POST", path(o) + "/reject", Map.of("reason", "No"), rep.token()).status()).isEqualTo(403);
        assertThat(call("POST", path(o) + "/withdraw-approval", null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", path(o) + "/approve", Map.of(), otherToken).status()).isEqualTo(404);
        assertThat(call("GET", path(o), null, otherToken).status()).isEqualTo(404);
        assertThat(by(rep, 200, "GET", path(o), null).get("approvalStatus").asString()).isEqualTo("pending");
    }
}
