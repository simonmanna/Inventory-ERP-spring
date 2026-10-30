package com.inventoryerp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.sales.SalesLedgerAssertions;
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
 * Phase S3 of ADR-S011 (docs/spec/credit.md): exposure buckets through the order-to-cash transitions (worked
 * example), the AR tie-out, limits by branch, group limits, check points (quote, order confirm, amendment, dispatch),
 * credit holds released only by {@code credit:override}, immutable decisions, policy and the review queue.
 */
class CreditIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    final String today = LocalDate.now().toString();
    String product;
    String repToken;

    @BeforeAll
    void fixtures() {
        product = UUID.randomUUID().toString();
        // price 1 000 000, no tax: an order of N units is worth N million
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service'::\"ProductType\","
                + " 1000000, 0, false, now())").params(product, demoOrg, "CR-" + sfx, "Credit item " + sfx).update();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Credit rep " + sfx, new String[] {
                    "sales:order:read", "sales:order:write", "sales:order:confirm", "sales:order:hold_release",
                    "credit:read"}).update();
        String email = "cr-rep-" + sfx.toLowerCase() + "@demo.test";
        createUser(demoOrg, email, "Sales@12345", role);
        repToken = login("DEMO", email, "Sales@12345");
    }

    String customer(long limit, String parentId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\","
                + " \"creditLimit\", \"parentId\", \"updatedAt\") VALUES (?, ?, ?, ?, true, true, ?, ?, now())")
                .params(id, demoOrg, "CC-" + UUID.randomUUID().toString().substring(0, 8), "Credit cust " + sfx, limit,
                        parentId).update();
        return id;
    }

    JsonNode order(String partner, int units, String token) {
        return call("POST", "/sales/orders", Map.of("partnerId", partner, "lines", List.of(Map.of("productId", product,
                "quantity", units))), token).body();
    }

    JsonNode exposure(String partner) {
        return ok(200, "GET", "/credit/partners/" + partner + "/exposure", null);
    }

    double m(JsonNode n) {
        return n.asDouble() / 1_000_000;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    @Test
    @DisplayName("worked example: SO 100M, deliver 40M, invoice 40M, pay 10M, cancel rest — buckets and AR tie-out")
    void workedExample() {
        String c = customer(100_000_000, null);
        String so = order(c, 100, adminToken).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        JsonNode e = exposure(c);
        assertThat(m(e.get("components").get("undelivered"))).isEqualTo(100);
        assertThat(m(e.get("exposure"))).isEqualTo(100);
        assertThat(m(e.get("headroom"))).isZero();
        String line = ok(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("id").asString();
        // E4: 40 units delivered and billable (projection written by fleet in S5)
        jdbc.sql("UPDATE \"SalesOrderLine\" SET \"qtyDelivered\" = 40, \"qtyBillable\" = 40 WHERE id = ?").param(line)
                .update();
        e = exposure(c);
        assertThat(m(e.get("components").get("undelivered"))).isEqualTo(60);
        assertThat(m(e.get("components").get("deliveredUninvoiced"))).isEqualTo(40);
        assertThat(m(e.get("exposure"))).isEqualTo(100);
        // E5: invoice 40M posted (billing projection written by invoicing in S6)
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", today, "dueDate", today, "lines",
                List.of(Map.of("productId", product, "quantity", 40, "unitPrice", 1000000)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        jdbc.sql("UPDATE \"SalesOrderLine\" SET \"qtyInvoiced\" = 40 WHERE id = ?").param(line).update();
        e = exposure(c);
        assertThat(m(e.get("components").get("deliveredUninvoiced"))).isZero();
        assertThat(m(e.get("components").get("receivables"))).isEqualTo(40);
        assertThat(m(e.get("exposure"))).isEqualTo(100);
        new SalesLedgerAssertions(jdbc, demoOrg).receivableTiesOut(c);
        // E8: payment 10M
        assertThat(call("POST", "/payments", Map.of("partnerId", c, "paymentDate", today, "amount", 10_000_000,
                "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", inv, "amount", 10_000_000))), key())
                .status()).isEqualTo(200);
        e = exposure(c);
        assertThat(m(e.get("components").get("receivables"))).isEqualTo(30);
        assertThat(m(e.get("exposure"))).isEqualTo(90);
        assertThat(m(e.get("headroom"))).isEqualTo(10);
        new SalesLedgerAssertions(jdbc, demoOrg).receivableTiesOut(c);
        // on-account payment 5M = unapplied credit
        call("POST", "/payments", Map.of("partnerId", c, "paymentDate", today, "amount", 5_000_000, "paymentMethod",
                "bank"), key());
        assertThat(m(exposure(c).get("exposure"))).isEqualTo(85);
        // E11: close the rest short → only the receivable and the credit remain
        ok(200, "POST", "/sales/orders/" + so + "/lines/" + line + "/close-short", Map.of("reason", "Project ended"));
        e = exposure(c);
        assertThat(m(e.get("components").get("undelivered"))).isZero();
        assertThat(m(e.get("exposure"))).isEqualTo(25);
    }

    @Test
    @DisplayName("order confirm over the limit → credit hold; rep cannot release; credit:override releases with a decision")
    void confirmHoldAndOverride() {
        String c = customer(50_000_000, null);
        String so = order(c, 60, repToken).get("id").asString();
        JsonNode confirmed = call("POST", "/sales/orders/" + so + "/confirm", null, repToken).body();
        assertThat(confirmed.get("status").asString()).isEqualTo("confirmed");
        assertThat(confirmed.get("onHold").asBoolean()).isTrue();
        assertThat(confirmed.get("holdKind").asString()).isEqualTo("credit");
        assertThat(confirmed.get("holdReason").asString()).contains("credit limit would be exceeded");
        assertThat(call("POST", "/sales/orders/" + so + "/release", null, repToken).status()).isEqualTo(403);
        assertThat(ok(200, "POST", "/sales/orders/" + so + "/release", null).get("onHold").asBoolean()).isFalse();
        JsonNode decisions = ok(200, "GET", "/credit/decisions?documentId=" + so, null);
        assertThat(decisions.get(0).get("result").asString()).isEqualTo("override");
        assertThat(decisions.get(1).get("result").asString()).isEqualTo("hold");
        assertThat(decisions.get(0).get("overridesId").asString()).isEqualTo(decisions.get(1).get("id").asString());
        assertThatThrownBy(() -> jdbc.sql("UPDATE \"CreditDecision\" SET result = 'pass' WHERE id = ?").param(
                decisions.get(1).get("id").asString()).update()).hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("credit limit record beats the partner field; branch-specific limit; group limit of a parent")
    void limitsAndGroups() {
        String c = customer(0, null);
        assertThat(exposure(c).get("limitSource").asString()).isEqualTo("none");
        JsonNode lim = ok(201, "POST", "/credit/limits", Map.of("partnerId", c, "amount", 20_000_000, "riskCategory",
                "medium"));
        assertThat(exposure(c).get("limit").asDouble()).isEqualTo(20_000_000);
        assertThat(exposure(c).get("riskCategory").asString()).isEqualTo("medium");
        assertThat(as("POST", "/credit/limits", Map.of("partnerId", c, "amount", 1)).status()).isEqualTo(409);
        ok(200, "PUT", "/credit/limits/" + lim.get("id").asString(), Map.of("riskCategory", "blocked"));
        String so = order(c, 1, adminToken).get("id").asString();
        JsonNode blocked = ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        assertThat(blocked.get("holdKind").asString()).isEqualTo("customer");
        assertThat(blocked.get("holdReason").asString()).contains("risk is blocked");

        String parent = customer(30_000_000, null);
        String child1 = customer(0, parent);
        String child2 = customer(0, parent);
        ok(200, "POST", "/sales/orders/" + order(child1, 20, adminToken).get("id").asString() + "/confirm", null);
        JsonNode second = ok(200, "POST", "/sales/orders/" + order(child2, 15, adminToken).get("id").asString()
                + "/confirm", null);
        assertThat(second.get("onHold").asBoolean()).isTrue();
        assertThat(second.get("holdReason").asString()).contains("group credit limit");
        assertThat(m(exposure(child2).get("group").get("exposure"))).isEqualTo(35);
    }

    @Test
    @DisplayName("customer hard hold blocks; hold endpoint audited; quote submit reports credit reasons")
    void hardHoldAndQuote() {
        String c = customer(0, null);
        assertThat(as("POST", "/credit/partners/" + c + "/hold", Map.of("hold", true)).status()).isEqualTo(400);
        assertThat(ok(200, "POST", "/credit/partners/" + c + "/hold", Map.of("hold", true, "reason", "Bounced cheque"))
                .get("creditHold").asBoolean()).isTrue();
        JsonNode q = ok(201, "POST", "/sales/quotations", Map.of("partnerId", c, "lines", List.of(Map.of("productId",
                product, "quantity", 1))));
        JsonNode sub = ok(200, "POST", "/sales/quotations/" + q.get("id").asString() + "/submit", null);
        assertThat(sub.get("status").asString()).isEqualTo("pending_approval");
        assertThat(sub.get("approvalReasons").toString()).contains("Customer is on credit hold");
        String so = order(c, 1, adminToken).get("id").asString();
        JsonNode held = ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        assertThat(held.get("holdKind").asString()).isEqualTo("customer");
        assertThat(as("POST", "/sales/orders/" + so + "/release", null).body().get("message").asString())
                .contains("lift the hold on the customer");
        ok(200, "POST", "/credit/partners/" + c + "/hold", Map.of("hold", false, "reason", "Cleared"));
        assertThat(ok(200, "POST", "/sales/orders/" + so + "/release", null).get("onHold").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("amendment raising the value over the limit puts the order on credit hold")
    void amendmentCheck() {
        String c = customer(10_000_000, null);
        String so = order(c, 8, adminToken).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        String line = ok(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("id").asString();
        JsonNode cr = ok(201, "POST", "/sales/orders/" + so + "/change-requests", Map.of("reason", "More", "changes",
                Map.of("lines", List.of(Map.of("lineId", line, "quantity", 12)))));
        ok(200, "POST", "/sales/change-requests/" + cr.get("id").asString() + "/approve", Map.of());
        JsonNode after = ok(200, "GET", "/sales/orders/" + so, null);
        assertThat(after.get("onHold").asBoolean()).isTrue();
        assertThat(after.get("holdKind").asString()).isEqualTo("credit");
    }

    @Test
    @DisplayName("policy: overdue receivables hold orders (approval) or block them (hard); review queue lists them")
    void overduePolicy() {
        String c = customer(0, null);
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", today, "lines", List.of(Map.of(
                "productId", product, "quantity", 1, "unitPrice", 1000)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        jdbc.sql("UPDATE \"Document\" SET \"dueDate\" = now() - interval '45 days' WHERE id = ?").param(inv).update();
        assertThat(exposure(c).get("overdue").get("maxDays").asInt()).isGreaterThanOrEqualTo(44);
        try {
            ok(200, "PUT", "/credit/policy", Map.of("overdueDays", 30, "overdueAction", "approval"));
            JsonNode held = ok(200, "POST", "/sales/orders/" + order(c, 1, adminToken).get("id").asString() + "/confirm",
                    null);
            assertThat(held.get("holdKind").asString()).isEqualTo("credit");
            assertThat(held.get("holdReason").asString()).contains("overdue");
            ok(200, "PUT", "/credit/policy", Map.of("overdueAction", "hard"));
            JsonNode blocked = ok(200, "POST", "/sales/orders/" + order(c, 1, adminToken).get("id").asString()
                    + "/confirm", null);
            assertThat(blocked.get("holdKind").asString()).isEqualTo("customer");
            List<String> queue = new java.util.ArrayList<>();
            ok(200, "GET", "/credit/review-queue", null).forEach(x -> queue.add(x.get("partnerId").asString()));
            assertThat(queue).contains(c);
            assertThat(as("PUT", "/credit/policy", Map.of("checkpoints", List.of("nope"))).status()).isEqualTo(400);
            JsonNode p = ok(200, "PUT", "/credit/policy", Map.of("checkpoints", List.of("quote_submit")));
            assertThat(p.get("checkpoints").toString()).contains("order_confirm");
        } finally {
            ok(200, "PUT", "/credit/policy", Map.of("overdueAction", "approval", "checkpoints", List.of("quote_submit",
                    "order_confirm", "amendment", "trip_dispatch")));
        }
    }

    @Test
    @DisplayName("authorization matrix of /credit routes and tenant isolation")
    void matrixAndIsolation() {
        String c = customer(0, null);
        assertThat(call("GET", "/credit/partners/" + c + "/exposure", null, otherToken).status()).isEqualTo(404);
        assertThat(assertAuthorizationMatrix("com.inventoryerp.credit")).isGreaterThanOrEqualTo(10);
    }
}
