package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.procurement.web.ProcurementControlsController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ADR-PROCUREMENT-001 wave 5: budget commitments and budget control, purchase-order change requests with revisions
 * and maker-checker re-approval, per-branch matching tolerances (quantity, two-way), duplicate-bill detection and
 * supplier claims settled by debit note. Every policy is branch-scoped so other suites in the DEMO org are untouched.
 */
class ProcurementControlsIT extends ApiTestSupport {

    String checkerToken;
    String warehouse;
    String supplier;
    String branch;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        String role = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id JOIN \"User\" u"
                + " ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' LIMIT 1").query(String.class).single();
        String email = "controls-checker-" + suffix() + "@demo.test";
        createUser(demoOrg, email, "Checker@12345", role);
        checkerToken = login("DEMO", email, "Checker@12345");
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "CT-" + suffix(), "name", "Controls wh "
                + suffix(), "type", "warehouse")).get("id").asString();
        supplier = supplier();
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String newBranch() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(id, demoOrg, "BC-" + suffix(), "Block plant " + suffix()).update();
        branch = id;
        return id;
    }

    String supplier() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "SUP-" + suffix(), "Aggregates " + suffix())
                .update();
        return id;
    }

    /** Haulage-type service: commits against an expense account (stock accounts cannot carry a budget). */
    String service() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service', false,"
                + " 'AVCO', 0, 0, now())").params(id, demoOrg, "CS-" + suffix(), "Haulage " + suffix()).update();
        return id;
    }

    String product() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true,"
                + " 'AVCO', 0, 0, now())").params(id, demoOrg, "CP-" + suffix(), "Sand " + suffix()).update();
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
        Res approved = call("POST", "/procurement/policies/" + id + "/approve", null, checkerToken);
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(200);
        ok(200, "POST", "/procurement/policies/" + id + "/activate", null);
    }

    Res po(String productId, Object qty, Object price) {
        return as("POST", "/procurement/purchase-orders", new LinkedHashMap<>(Map.of("partnerId", supplier,
                "warehouseId", warehouse, "branchId", branch, "paymentType", "credit", "currencyCode", "UGX", "lines",
                List.of(Map.of("productId", productId, "description", "Sand", "quantity", qty, "unitPrice", price)))));
    }

    JsonNode activePo(String productId, Object qty, Object price) {
        Res r = po(productId, qty, price);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        assertThat(r.body().get("status").asString()).isEqualTo("active");
        return r.body();
    }

    void receive(String poId, String productId, Object qty) {
        Res r = call("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("productId", productId, "description", "Sand", "quantity", qty))), key(adminToken));
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
    }

    JsonNode commitments(String poId) {
        return ok(200, "GET", "/procurement/commitments?sourceType=purchase_order&sourceId=" + poId, null).get("data");
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    @Test
    @DisplayName("budget: approved order commits, receipt relieves, cancel releases; warn then block on overrun")
    void budgetControl() {
        newBranch();
        String sand = service();
        String po1 = activePo(sand, 10, 1000).get("id").asString();
        JsonNode c1 = commitments(po1);
        assertThat(c1).hasSize(1);
        assertThat(dec(c1.get(0).get("openAmount"))).isEqualByComparingTo("10000");
        assertThat(c1.get(0).get("branchId").asString()).isEqualTo(branch);
        String account = c1.get(0).get("accountId").asString();

        receive(po1, sand, 4);
        assertThat(dec(commitments(po1).get(0).get("openAmount"))).isEqualByComparingTo("6000");

        String month = YearMonth.now().toString();
        String budget = ok(201, "POST", "/budgets", Map.of("name", "Plant " + suffix(), "fiscalYear",
                LocalDate.now().getYear(), "startDate", LocalDate.now().withDayOfYear(1).toString(), "endDate",
                LocalDate.now().withDayOfYear(LocalDate.now().lengthOfYear()).toString(), "branchId", branch, "lines",
                List.of(Map.of("accountId", account, "period", month, "amount", 15000)))).get("id").asString();
        ok(200, "POST", "/budgets/" + budget + "/approve", null);

        // No policy yet: the overrun is allowed silently (today's behaviour).
        assertThat(po(sand, 1, 1).body().has("warnings")).isFalse();

        policy("budget.control", "warn");
        // Budget 15000 − posted actuals − committed (6000 + 1) is at most 8999: a 10000 order overruns it.
        Res warn = po(sand, 10, 1000);
        assertThat(warn.status()).isEqualTo(201);
        assertThat(warn.body().get("warnings").get(0).asString()).startsWith("Budget exceeded for ")
                .contains("this order needs 10000");

        policy("budget.control", "block");
        Res blocked = po(sand, 1, 1000);
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(blocked.body().get("message").asString()).startsWith("Budget exceeded for ");

        JsonNode vs = ok(200, "GET", "/budgets/" + budget + "/vs-actual", null);
        JsonNode row = null;
        for (JsonNode r : vs.get("rows")) {
            if (account.equals(r.get("accountId").asString())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(dec(row.get("committed"))).isEqualByComparingTo("16001");

        ok(201, "POST", "/procurement/purchase-orders/" + po1 + "/cancel", Map.of("reason", "plan changed"));
        assertThat(dec(commitments(po1).get(0).get("openAmount"))).isEqualByComparingTo("0");
        assertThat(commitments(po1).get(0).get("status").asString()).isEqualTo("closed");
    }

    @Test
    @DisplayName("changes: decrease applies at once, increase waits for a second person; revisions and guards")
    void changeRequests() {
        newBranch();
        String sand = product();
        JsonNode po = activePo(sand, 10, 100);
        String poId = po.get("id").asString();
        String lineId = po.get("lines").get(0).get("id").asString();

        JsonNode down = call("POST", "/procurement/purchase-orders/" + poId + "/changes", Map.of("reason", "less needed",
                "lines", List.of(Map.of("lineId", lineId, "quantity", 8))), key(adminToken)).body();
        assertThat(down.get("status").asString()).isEqualTo("applied");
        assertThat(down.get("needsApproval").asBoolean()).isFalse();
        JsonNode order = ok(200, "GET", "/procurement/purchase-orders/" + poId, null);
        assertThat(dec(order.get("totalAmount"))).isEqualByComparingTo("800");
        assertThat(dec(commitments(poId).get(0).get("openAmount"))).isEqualByComparingTo("800");

        Map<String, Object> up = Map.of("reason", "price rise", "lines", List.of(Map.of("lineId", lineId, "unitPrice",
                120)));
        JsonNode pending = call("POST", "/procurement/purchase-orders/" + poId + "/changes", up, key(adminToken)).body();
        assertThat(pending.get("status").asString()).isEqualTo("pending");
        assertThat(dec(pending.get("newTotal"))).isEqualByComparingTo("960");
        Res second = call("POST", "/procurement/purchase-orders/" + poId + "/changes", up, key(adminToken));
        assertThat(second.status()).isEqualTo(409);
        String changeId = pending.get("id").asString();
        Res self = call("POST", "/procurement/po-changes/" + changeId + "/approve", Map.of(), key(adminToken));
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.body().get("message").asString())
                .isEqualTo("You cannot approve a change you requested — another approver must decide");
        assertThat(ok(200, "GET", "/procurement/po-changes/pending", null).findValuesAsString("id")).contains(changeId);
        Res approved = call("POST", "/procurement/po-changes/" + changeId + "/approve", Map.of("note", "ok"),
                key(checkerToken));
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(200);
        assertThat(approved.body().get("status").asString()).isEqualTo("applied");

        JsonNode history = ok(200, "GET", "/procurement/purchase-orders/" + poId + "/changes", null);
        assertThat(history.get("revision").asInt()).isEqualTo(2);
        assertThat(history.get("revisions")).hasSize(3);
        assertThat(history.get("revisions").get(2).get("snapshot").get("totalAmount").asString()).isEqualTo("1000");
        assertThat(dec(ok(200, "GET", "/procurement/purchase-orders/" + poId, null).get("totalAmount")))
                .isEqualByComparingTo("960");

        receive(poId, sand, 5);
        Res belowReceived = call("POST", "/procurement/purchase-orders/" + poId + "/changes", Map.of("reason", "x",
                "lines", List.of(Map.of("lineId", lineId, "quantity", 4))), key(adminToken));
        assertThat(belowReceived.body().get("message").asString()).isEqualTo(
                "Quantity of \"Sand\" cannot be below 5 (already received)");
        Res priceAfterReceipt = call("POST", "/procurement/purchase-orders/" + poId + "/changes", Map.of("reason", "x",
                "lines", List.of(Map.of("lineId", lineId, "unitPrice", 90))), key(adminToken));
        assertThat(priceAfterReceipt.body().get("message").asString()).isEqualTo("The price of \"Sand\" cannot change"
                + " after goods were received — raise a supplier claim or debit note instead");
        Res noKey = as("POST", "/procurement/purchase-orders/" + poId + "/changes", Map.of("reason", "x"));
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(call("GET", "/procurement/purchase-orders/" + poId + "/changes", null, otherToken).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("matching: branch quantity tolerance and two-way mode change the match result")
    void matchingTolerances() {
        newBranch();
        String sand = product();
        String poId = activePo(sand, 10, 50).get("id").asString();
        receive(poId, sand, 10);
        String bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "branchId",
                branch, "purchaseOrderIds", List.of(poId), "lines", List.of(Map.of("productId", sand, "description",
                        "Sand", "quantity", 10.5, "unitPrice", 50)))).get("id").asString();
        JsonNode strict = ok(201, "POST", "/procurement/three-way-match/validate-bill/" + bill, null);
        assertThat(strict.get("ok").asBoolean()).isFalse();

        policy("match.qtyTolerancePct", 10);
        JsonNode tolerant = ok(201, "POST", "/procurement/three-way-match/validate-bill/" + bill, null);
        assertThat(tolerant.get("ok").asBoolean()).isTrue();

        // Two-way: a service-style order billed before anything is received matches the ordered quantity.
        policy("match.mode", "two_way");
        String po2 = activePo(sand, 5, 20).get("id").asString();
        String bill2 = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "branchId",
                branch, "purchaseOrderIds", List.of(po2), "lines", List.of(Map.of("productId", sand, "description",
                        "Sand", "quantity", 5, "unitPrice", 20)))).get("id").asString();
        JsonNode twoWay = ok(201, "POST", "/procurement/three-way-match/validate-bill/" + bill2, null);
        assertThat(twoWay.get("matches").get(0).get("status").asString()).isEqualTo("matched");
    }

    @Test
    @DisplayName("duplicate bills: warn flags the second bill, block refuses it")
    void duplicateBills() {
        newBranch();
        String ref = "INV-" + suffix();
        Map<String, Object> body = Map.of("partnerId", supplier, "issueDate", today, "branchId", branch, "reference", ref,
                "lines", List.of(Map.of("description", "Haulage", "quantity", 1, "unitPrice", 700)));
        String first = ok(200, "POST", "/vendor-bills", body).get("id").asString();
        assertThat(ok(200, "POST", "/vendor-bills/" + first + "/post", Map.of()).has("warnings")).isFalse();

        policy("bill.duplicateCheck", "warn");
        String second = ok(200, "POST", "/vendor-bills", body).get("id").asString();
        assertThat(ok(200, "GET", "/procurement/bills/" + second + "/duplicates", null).findValuesAsString("id"))
                .containsExactly(first);
        JsonNode warned = ok(200, "POST", "/vendor-bills/" + second + "/post", Map.of());
        assertThat(warned.get("warnings").get(0).asString()).startsWith("Possible duplicate bill");

        policy("bill.duplicateCheck", "block");
        String third = ok(200, "POST", "/vendor-bills", body).get("id").asString();
        Res blocked = as("POST", "/vendor-bills/" + third + "/post", Map.of());
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(ok(200, "GET", "/vendor-bills/" + third, null).get("status").asString()).isEqualTo("draft");
    }

    @Test
    @DisplayName("claims: raise → submit → accept (agreed amount) → settle by draft debit note, idempotent; tenancy")
    void supplierClaims() {
        newBranch();
        String sand = product();
        String poId = activePo(sand, 10, 100).get("id").asString();
        Res wrongSupplier = as("POST", "/procurement/claims", Map.of("partnerId", supplier(), "type", "shortage",
                "amount", 100, "description", "x", "purchaseOrderId", poId));
        assertThat(wrongSupplier.status()).isEqualTo(404);
        JsonNode claim = ok(201, "POST", "/procurement/claims", Map.of("partnerId", supplier, "type", "shortage",
                "amount", 300, "description", "3 t short on delivery", "purchaseOrderId", poId));
        String id = claim.get("id").asString();
        assertThat(claim.get("claimNumber").asString()).startsWith("CLM-");
        assertThat(claim.get("orderNumber").asString()).isEqualTo(ok(200, "GET", "/procurement/purchase-orders/" + poId,
                null).get("orderNumber").asString());
        Res early = call("POST", "/procurement/claims/" + id + "/settle", null, key(adminToken));
        assertThat(early.status()).isEqualTo(400);
        ok(200, "POST", "/procurement/claims/" + id + "/submit", null);
        ok(200, "POST", "/procurement/claims/" + id + "/accept", Map.of("amount", 250, "response", "agreed 2.5 t"));
        Map<String, String> h = key(adminToken);
        JsonNode settled = call("POST", "/procurement/claims/" + id + "/settle", null, h).body();
        assertThat(settled.get("status").asString()).isEqualTo("settled");
        assertThat(settled.get("settledAmount").asString()).isEqualTo("250");
        assertThat(settled.get("debitNoteStatus").asString()).isEqualTo("draft");
        JsonNode replay = call("POST", "/procurement/claims/" + id + "/settle", null, h).body();
        assertThat(replay.get("debitNoteId").asString()).isEqualTo(settled.get("debitNoteId").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM \"DebitNote\" WHERE \"organizationId\" = ? AND \"reasonNote\" = ?")
                .params(demoOrg, "Claim " + claim.get("claimNumber").asString()).query(Long.class).single())
                .isEqualTo(1L);
        assertThat(as("POST", "/procurement/claims/" + id + "/cancel", null).status()).isEqualTo(400);
        assertThat(call("GET", "/procurement/claims/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(ok(200, "GET", "/procurement/claims?purchaseOrderId=" + poId, null).get("data")).hasSize(1);
    }

    @Test
    @DisplayName("authorization matrix: every controls route refuses a token without its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(ProcurementControlsController.class)).isEqualTo(16);
    }
}
