package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.TestApprovers;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Readiness gaps h-04 (procurement controls and sourcing), stream gE: sealed bids, no quotes after the deadline and
 * no zero-quantity offers (F6), supplier approval is maker-checker (F11), and a draft policy is approved only while it
 * is a draft (F11).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcurementControlsGapsIT extends ApiTestSupport {

    String approverToken;

    @BeforeAll
    void fixtures() {
        approverToken = TestApprovers.secondAdmin(this);
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String supplier(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "SUP-" + suffix(), name + " " + suffix())
                .update();
        return id;
    }

    String product(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true,"
                + " 'AVCO', 0, 0, now())").params(id, demoOrg, "PC-" + suffix(), name + " " + suffix()).update();
        return id;
    }

    /** A sent RFQ for 10 of one product, offered to {@code suppliers}; returns the RFQ. */
    JsonNode sentRfq(List<String> suppliers) {
        String prId = ok(201, "POST", "/procurement/purchase-requests", Map.of("description", "Gaps", "lines",
                List.of(Map.of("productId", product("Cement"), "description", "Cement", "quantity", 10))))
                .get("id").asString();
        ok(200, "PATCH", "/procurement/purchase-requests/" + prId + "/submit", null);
        assertThat(call("PATCH", "/procurement/purchase-requests/" + prId + "/approve", null, approverToken).status())
                .isEqualTo(200);
        String reqLine = ok(200, "GET", "/procurement/purchase-requests/" + prId, null).get("lines").get(0).get("id")
                .asString();
        JsonNode rfq = ok(201, "POST", "/procurement/rfqs", Map.of("title", "Gaps " + suffix(), "requestLineIds",
                List.of(reqLine), "supplierIds", suppliers, "deadline", LocalDate.now().plusDays(3).toString()));
        ok(200, "POST", "/procurement/rfqs/" + rfq.get("id").asString() + "/send", null);
        return rfq;
    }

    Map<String, Object> quote(String partner, String rfqLineId, Object qty, Object price) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("rfqLineId", rfqLineId);
        line.put("unitPrice", price);
        if (qty != null) {
            line.put("quantity", qty);
        }
        return new LinkedHashMap<>(Map.of("partnerId", partner, "currencyCode", "UGX", "lines", List.of(line)));
    }

    @Test
    @DisplayName("h-04 F6: a zero-quantity offer is refused; no award while bids are sealed; no quote after the deadline")
    void sealedBidsAndDeadline() {
        String a = supplier("Alpha");
        String b = supplier("Bravo");
        JsonNode rfq = sentRfq(List.of(a, b));
        String rfqId = rfq.get("id").asString();
        String line = rfq.get("lines").get(0).get("id").asString();

        Res zero = as("POST", "/procurement/rfqs/" + rfqId + "/quotations", quote(a, line, 0, 5));
        assertThat(zero.status()).as(zero.raw().body()).isEqualTo(400);
        assertThat(zero.raw().body()).contains("positive");

        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/quotations", quote(a, line, null, 5));
        Res sealed = as("POST", "/procurement/rfqs/" + rfqId + "/award", Map.of("lines", List.of(Map.of("rfqLineId",
                line, "partnerId", a, "quantity", 10))));
        assertThat(sealed.status()).as(sealed.raw().body()).isEqualTo(400);
        assertThat(sealed.body().get("message").asString()).startsWith("An RFQ cannot be awarded while its bids are sealed");

        // The deadline passes: the tender is closed to new quotations.
        jdbc.sql("UPDATE \"Rfq\" SET deadline = now() - interval '1 day' WHERE id = ?").param(rfqId).update();
        Res late = as("POST", "/procurement/rfqs/" + rfqId + "/quotations", quote(b, line, null, 4));
        assertThat(late.status()).as(late.raw().body()).isEqualTo(400);
        assertThat(late.body().get("message").asString()).startsWith("Quotations cannot be recorded after the RFQ deadline");
    }

    @Test
    @DisplayName("h-04 F3: no orders while the award awaits approval or after it was rejected")
    void awardApprovalGatesOrders() {
        String a = supplier("Award");
        JsonNode rfq = sentRfq(List.of(a));
        String rfqId = rfq.get("id").asString();
        String line = rfq.get("lines").get(0).get("id").asString();
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/quotations", quote(a, line, null, 5));
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/close", null);
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/award", Map.of("lines", List.of(Map.of("rfqLineId", line,
                "partnerId", a, "quantity", 10))));
        String warehouse = warehouse();
        // What an award under an approval workflow leaves: the RFQ stays closed with a pending rfq_award request.
        jdbc.sql("UPDATE \"Rfq\" SET status = 'closed' WHERE id = ?").param(rfqId).update();
        String request = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ApprovalRequest\" (id, \"organizationId\", \"entityType\", \"entityId\", snapshot,"
                + " status, \"updatedAt\") VALUES (?, ?, 'rfq_award', ?, '{}'::jsonb, 'pending', now())")
                .params(request, demoOrg, rfqId).update();
        Res pending = call("POST", "/procurement/rfqs/" + rfqId + "/orders", Map.of("warehouseId", warehouse),
                keyed(adminToken));
        assertThat(pending.status()).as(pending.raw().body()).isEqualTo(400);
        assertThat(pending.body().get("message").asString()).isEqualTo("The award is awaiting approval (request "
                + request + ")");

        jdbc.sql("UPDATE \"ApprovalRequest\" SET status = 'rejected', \"decidedAt\" = now() WHERE id = ?").param(request)
                .update();
        Res rejected = call("POST", "/procurement/rfqs/" + rfqId + "/orders", Map.of("warehouseId", warehouse),
                keyed(adminToken));
        assertThat(rejected.status()).as(rejected.raw().body()).isEqualTo(400);
        assertThat(rejected.body().get("message").asString()).startsWith("Orders cannot be created for a rejected award");
        assertThat(jdbc.sql("SELECT count(*) FROM \"RfqAward\" WHERE \"rfqId\" = ? AND \"purchaseOrderId\" IS NOT NULL")
                .param(rfqId).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("h-04 F8: two buyers ordering the same award at once create one order")
    void concurrentAwardOrders() throws Exception {
        String a = supplier("Race");
        JsonNode rfq = sentRfq(List.of(a));
        String rfqId = rfq.get("id").asString();
        String line = rfq.get("lines").get(0).get("id").asString();
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/quotations", quote(a, line, null, 5));
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/close", null);
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/award", Map.of("lines", List.of(Map.of("rfqLineId", line,
                "partnerId", a, "quantity", 10))));
        String warehouse = warehouse();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Callable<Integer>> tasks = List.of(
                    () -> call("POST", "/procurement/rfqs/" + rfqId + "/orders", Map.of("warehouseId", warehouse),
                            keyed(adminToken)).status(),
                    () -> call("POST", "/procurement/rfqs/" + rfqId + "/orders", Map.of("warehouseId", warehouse),
                            keyed(approverToken)).status());
            List<Integer> statuses = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Integer> f : pool.invokeAll(tasks)) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(DISTINCT \"purchaseOrderId\") FROM \"RfqAward\" WHERE \"rfqId\" = ?")
                .param(rfqId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("h-04 F11: the person who set up a supplier cannot approve it; another user can")
    void supplierApprovalMakerChecker() {
        String partner = supplier("Maker");
        ok(200, "PUT", "/procurement/suppliers/" + partner + "/profile", Map.of("notes", "Set up by admin"));
        Res self = as("POST", "/procurement/suppliers/" + partner + "/profile/approve", Map.of());
        assertThat(self.status()).as(self.raw().body()).isEqualTo(403);
        assertThat(self.body().get("message").asString())
                .isEqualTo("A supplier must be approved by someone other than the person who set it up or last edited it");

        Res other = call("POST", "/procurement/suppliers/" + partner + "/profile/approve", Map.of(), approverToken);
        assertThat(other.status()).as(other.raw().body()).isEqualTo(200);
        assertThat(other.body().get("profile").get("lifecycleStatus").asString()).isEqualTo("approved");
    }

    @Test
    @DisplayName("h-04 F11: whoever last edited a draft policy cannot approve it, even if someone else wrote it")
    void policyApproverIsNotLastEditor() {
        String branch = plainBranch();
        String id = ok(201, "POST", "/procurement/policies", Map.of("key", "receiving.inspectionRequired", "scopeType",
                "branch", "scopeId", branch, "value", true)).get("id").asString();
        Res edit = call("PATCH", "/procurement/policies/" + id, Map.of("notes", "Edited by the second admin"),
                approverToken);
        assertThat(edit.status()).as(edit.raw().body()).isEqualTo(200);
        Res approve = call("POST", "/procurement/policies/" + id + "/approve", null, approverToken);
        assertThat(approve.status()).as(approve.raw().body()).isEqualTo(403);
        assertThat(approve.body().get("message").asString())
                .isEqualTo("A policy must be approved by someone other than the person who last edited it");
    }

    // ------------------------------------------------------------------ F2 / F9 budgets, F4 bank details

    String plainBranch() {
        String branch = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(branch, demoOrg, "BB-" + suffix(), "Budget " + suffix()).update();
        return branch;
    }

    String budget(String branch, Object amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Gaps budget " + suffix());
        body.put("startDate", LocalDate.now().toString());
        body.put("endDate", LocalDate.now().toString());
        body.put("branchId", branch);
        body.put("amount", amount);
        body.put("controlMode", "block");
        String id = ok(201, "POST", "/procurement/budgets", body).get("id").asString();
        ok(200, "POST", "/procurement/budgets/" + id + "/activate", null);
        return id;
    }

    java.math.BigDecimal committed(String budgetId) {
        return new java.math.BigDecimal(ok(200, "GET", "/procurement/budgets/" + budgetId, null).get("committed")
                .asString());
    }

    @Test
    @DisplayName("h-04 F2: an order sourced from a branch's request carries that branch and counts against its budget")
    void sourcedOrderCarriesBranch() {
        String branch = plainBranch();
        String budget = budget(branch, 100000);
        String prId = ok(201, "POST", "/procurement/purchase-requests", Map.of("branchId", branch, "description", "Gaps",
                "lines", List.of(Map.of("productId", product("Cement"), "description", "Cement", "quantity", 2,
                        "estimatedUnitPrice", 300)))).get("id").asString();
        ok(200, "PATCH", "/procurement/purchase-requests/" + prId + "/submit", null);
        assertThat(call("PATCH", "/procurement/purchase-requests/" + prId + "/approve", null, approverToken).status())
                .isEqualTo(200);
        String reqLine = ok(200, "GET", "/procurement/purchase-requests/" + prId, null).get("lines").get(0).get("id")
                .asString();
        Res order = call("POST", "/procurement/sourcing/orders", Map.of("partnerId", supplier("Sourced"), "warehouseId",
                warehouse(), "currencyCode", "UGX", "lines", List.of(Map.of("requestLineId", reqLine))),
                keyed(adminToken));
        assertThat(order.status()).as(order.raw().body()).isIn(200, 201);
        assertThat(jdbc.sql("SELECT count(*) FROM \"PurchaseOrder\" o JOIN \"PurchaseOrderLine\" l ON"
                + " l.\"purchaseOrderId\" = o.id JOIN \"PurchaseRequestLine\" rl ON rl.id = l.\"requestLineId\" WHERE"
                + " rl.id = ? AND o.\"branchId\" = ?").params(reqLine, branch).query(Long.class).single()).isEqualTo(1);
        assertThat(committed(budget)).isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("h-04 F9: budget consumption counts only what an order can still cost, not cancelled quantities")
    void budgetIgnoresCancelledQuantity() {
        String branch = plainBranch();
        String budget = budget(branch, 100000);
        String poId = po(supplier("Budget"), branch, product("Cement"), 4, 250);
        assertThat(committed(budget)).isEqualByComparingTo("1000");
        jdbc.sql("UPDATE \"PurchaseOrderLine\" SET \"cancelledQuantity\" = 3 WHERE \"purchaseOrderId\" = ?").param(poId)
                .update();
        assertThat(committed(budget)).isEqualByComparingTo("250");
    }

    @Test
    @DisplayName("h-04 F4: a supplier's bank details come back masked, keep their stored value, and cannot be changed")
    void supplierBankDetails() {
        String partner = supplier("Banked");
        jdbc.sql("UPDATE \"Partner\" SET \"bankAccounts\" = ?::jsonb WHERE id = ?").params(
                "[{\"bankName\": \"Stanbic\", \"accountNumber\": \"0012345\"}]", partner).update();
        JsonNode profile = ok(200, "GET", "/partners/" + partner + "/profile", null);
        assertThat(profile.get("bankAccounts").get(0).get("accountNumber").asString()).isEqualTo("•••• 2345");

        // The masked row sent back unchanged keeps the stored number.
        ok(200, "PATCH", "/partners/" + partner + "/profile", Map.of("bankAccounts", List.of(Map.of("bankName",
                "Stanbic", "accountNumber", "•••• 2345"))));
        assertThat(jdbc.sql("SELECT \"bankAccounts\"->0->>'accountNumber' FROM \"Partner\" WHERE id = ?").param(partner)
                .query(String.class).single()).isEqualTo("0012345");

        Res added = as("PATCH", "/partners/" + partner + "/profile", Map.of("bankAccounts", List.of(Map.of("bankName",
                "Stanbic", "accountNumber", "•••• 2345"), Map.of("bankName", "Other", "accountNumber", "99887766"))));
        assertThat(added.status()).as(added.raw().body()).isEqualTo(400);
        assertThat(added.body().get("message").asString()).startsWith("Supplier bank accounts cannot be added or changed");
    }

    // ------------------------------------------------------------------ F5 incoming inspection, F7 portal

    String warehouse;

    String warehouse() {
        if (warehouse == null) {
            warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "PG-" + suffix(), "name", "Gaps yard "
                    + suffix(), "type", "warehouse")).get("id").asString();
        }
        return warehouse;
    }

    Map<String, String> keyed(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    /** A new branch whose inspection policy is on (approved by the second administrator). */
    String inspectedBranch() {
        String branch = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(branch, demoOrg, "BG-" + suffix(), "Gaps " + suffix()).update();
        String id = ok(201, "POST", "/procurement/policies", Map.of("key", "receiving.inspectionRequired", "scopeType",
                "branch", "scopeId", branch, "value", true)).get("id").asString();
        assertThat(call("POST", "/procurement/policies/" + id + "/approve", null, approverToken).status()).isEqualTo(200);
        ok(200, "POST", "/procurement/policies/" + id + "/activate", null);
        return branch;
    }

    String po(String supplier, String branch, String productId, Object qty, Object price) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", supplier);
        body.put("warehouseId", warehouse());
        if (branch != null) {
            body.put("branchId", branch);
        }
        body.put("paymentType", "credit");
        body.put("currencyCode", "UGX");
        body.put("lines", List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty, "unitPrice",
                price)));
        return ok(201, "POST", "/procurement/purchase-orders", body).get("id").asString();
    }

    void receive(String poId, String productId, Object qty) {
        Res r = call("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse(),
                "lines", List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty))),
                keyed(adminToken));
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
    }

    /** A received order of 10 under inspection: {order, inspection, supplier, product, branch}. */
    String[] inspected() {
        String branch = inspectedBranch();
        String supplier = supplier("Inspected");
        String cement = product("Cement");
        String poId = po(supplier, branch, cement, 10, 30000);
        receive(poId, cement, 10);
        JsonNode list = ok(200, "GET", "/procurement/inspections?purchaseOrderId=" + poId, null);
        assertThat(list).hasSize(1);
        return new String[] {poId, list.get(0).get("id").asString(), supplier, cement, branch};
    }

    @Test
    @DisplayName("h-04 F5: cancelling an inspection needs a reason and someone other than the receiver; both are kept")
    void inspectionCancel() {
        String[] x = inspected();
        String path = "/procurement/inspections/" + x[1] + "/cancel";
        Res noReason = call("POST", path, Map.of(), approverToken);
        assertThat(noReason.status()).as(noReason.raw().body()).isEqualTo(400);
        assertThat(noReason.body().get("message").asString()).isEqualTo("A reason is required to cancel an inspection");

        Res receiver = as("POST", path, Map.of("reason", "Wrong truck"));
        assertThat(receiver.status()).as(receiver.raw().body()).isEqualTo(403);
        assertThat(receiver.body().get("message").asString())
                .startsWith("An inspection cannot be cancelled by the person who received the goods");

        Res other = call("POST", path, Map.of("reason", "Wrong truck"), approverToken);
        assertThat(other.status()).as(other.raw().body()).isEqualTo(200);
        Map<String, Object> row = jdbc.sql("SELECT status, \"cancelReason\", \"cancelledBy\" FROM \"IncomingInspection\""
                + " WHERE id = ?").param(x[1]).query().singleRow();
        assertThat(row.get("status")).isEqualTo("cancelled");
        assertThat(row.get("cancelReason")).isEqualTo("Wrong truck");
        assertThat(row.get("cancelledBy")).isNotNull();
    }

    @Test
    @DisplayName("h-04 F5: a bill without purchaseOrderIds is still held while its goods are under inspection")
    void billHoldByLines() {
        String[] x = inspected();
        String bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", x[2], "issueDate", LocalDate.now().toString(),
                "branchId", x[4], "lines", List.of(Map.of("productId", x[3], "description", "Cement", "quantity", 10,
                        "unitPrice", 30000)))).get("id").asString();
        Res held = as("POST", "/vendor-bills/" + bill + "/post", Map.of());
        assertThat(held.status()).as(held.raw().body()).isEqualTo(400);
        assertThat(held.body().get("message").asString()).startsWith("Incoming inspection QC-");
    }

    @Test
    @DisplayName("h-04 F5: rejected goods go on the quality claim as quantities, so they leave stock when settled")
    void rejectedQuantitiesOnClaim() {
        String[] x = inspected();
        JsonNode insp = ok(200, "GET", "/procurement/inspections/" + x[1], null);
        String line = insp.get("lines").get(0).get("id").asString();
        ok(200, "POST", "/procurement/inspections/" + x[1] + "/results", Map.of("lines", List.of(Map.of("lineId", line,
                "rejectedQuantity", 3, "defectNotes", "wet"))));
        JsonNode done = call("POST", "/procurement/inspections/" + x[1] + "/complete", null, keyed(adminToken)).body();
        String claimId = done.get("claimId").asString();
        java.math.BigDecimal qty = jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"SupplierClaimLine\" WHERE"
                + " \"claimId\" = ?").param(claimId).query(java.math.BigDecimal.class).single();
        assertThat(qty).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("h-04 F7: a bad portal date is a 400; a supplier confirms once; the first promised date is kept")
    void portalConfirm() {
        String supplier = supplier("Portal");
        String poId = po(supplier, null, product("Cement"), 5, 1000);
        String token = ok(201, "POST", "/procurement/portal-links", Map.of("documentType", "purchase_order",
                "documentId", poId)).get("token").asString();
        Res bad = call("POST", "/supplier-portal/" + token + "/confirm", Map.of("promisedDate", "next tuesday"),
                Map.of());
        assertThat(bad.status()).as(bad.raw().body()).isEqualTo(400);

        String first = LocalDate.now().plusDays(5).toString();
        Res confirmed = call("POST", "/supplier-portal/" + token + "/confirm", Map.of("supplierReference", "SO-1",
                "promisedDate", first), Map.of());
        assertThat(confirmed.status()).as(confirmed.raw().body()).isEqualTo(200);
        Res again = call("POST", "/supplier-portal/" + token + "/confirm", Map.of("supplierReference", "SO-1",
                "promisedDate", LocalDate.now().plusDays(20).toString()), Map.of());
        assertThat(again.status()).as(again.raw().body()).isEqualTo(400);
        assertThat(again.body().get("message").asString()).startsWith("This order is already confirmed");

        // The buyer records a later date: the order moves, the first promise stays for the on-time KPI.
        String later = LocalDate.now().plusDays(9).toString();
        ok(200, "POST", "/procurement/purchase-orders/" + poId + "/acknowledge", Map.of("supplierReference", "SO-1",
                "promisedDate", later));
        Map<String, Object> row = jdbc.sql("SELECT \"promisedDate\"::text AS p, \"firstPromisedDate\"::text AS f FROM"
                + " \"PurchaseOrder\" WHERE id = ?").param(poId).query().singleRow();
        assertThat(row.get("p")).isEqualTo(later);
        assertThat(row.get("f")).isEqualTo(first);
    }
}
