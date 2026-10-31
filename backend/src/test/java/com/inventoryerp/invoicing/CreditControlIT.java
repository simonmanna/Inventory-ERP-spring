package com.inventoryerp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.invoicing.web.CreditControlController;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SALES-1: limit, hold and exposure on orders and invoice posting. */
class CreditControlIT extends ApiTestSupport {

    String service;
    String sellerToken;
    final java.util.List<String> created = new java.util.ArrayList<>();

    @BeforeAll
    void fixtures() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        service = ok(201, "POST", "/products", Map.of("code", "CC-" + s, "name", "Credit service " + s, "productType",
                "service", "salesPrice", 100, "costPrice", 0)).get("id").asString();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "CC seller " + s,
                new String[] {"orders:read", "orders:create", "orders:update", "invoice:post"}).update();
        String email = "cc-" + s + "@demo.test";
        createUser(demoOrg, email, "Seller@12345", role);
        sellerToken = login("DEMO", email, "Seller@12345");
    }

    @AfterAll
    void cancelLeftovers() {
        jdbc.sql("UPDATE \"Order\" SET status = 'cancelled', \"cancelledAt\" = now() WHERE id = ANY(?)"
                + " AND \"invoiceId\" IS NULL AND status::text <> 'cancelled'").param(created.toArray(String[]::new))
                .update();
    }

    String customer(int limit, boolean hold) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"creditLimit\","
                + " \"creditHold\", \"updatedAt\") VALUES (?, ?, ?, ?, true, ?, ?, now())")
                .params(id, demoOrg, "CCC-" + id.substring(0, 8), "Credit customer " + id.substring(0, 8), limit, hold)
                .update();
        return id;
    }

    Map<String, Object> order(String customer, int qty) {
        return new HashMap<>(Map.of("partnerId", customer, "lines", List.of(Map.of("productId", service, "quantity",
                qty, "unitPrice", 100))));
    }

    JsonNode placed(String customer, int qty) {
        JsonNode o = ok(200, "POST", "/orders", order(customer, qty));
        created.add(o.get("id").asString());
        return o;
    }

    @Test
    @DisplayName("no limit and no hold: unlimited, as before")
    void unlimited() {
        placed(customer(0, false), 50);
    }

    @Test
    @DisplayName("a hold blocks a credit order and leaves nothing behind; cash still sells")
    void hold() {
        String c = customer(0, true);
        Res r = as("POST", "/orders", order(c, 1));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).isEqualTo("Customer is on credit hold.");
        assertThat(jdbc.sql("SELECT count(*) FROM \"Order\" WHERE \"partnerId\" = ?").param(c).query(Long.class)
                .single()).isZero();
        Map<String, Object> cash = order(c, 1);
        cash.put("paymentMethod", "cash");
        JsonNode o = ok(200, "POST", "/orders", cash);
        created.add(o.get("id").asString());
    }

    @Test
    @DisplayName("undelivered orders count: the second order passes the limit")
    void unbilledOrdersCount() {
        String c = customer(1000, false);
        placed(c, 6);
        Res r = as("POST", "/orders", order(c, 6));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).isEqualTo("Credit limit exceeded: limit 1000, would become 1200.");
        JsonNode pos = ok(200, "GET", "/credit-control/customers/" + c, null);
        assertThat(pos.get("unbilledOrders").decimalValue()).isEqualByComparingTo("600");
        assertThat(pos.get("headroom").decimalValue()).isEqualByComparingTo("400");
    }

    @Test
    @DisplayName("editing an order does not count it twice")
    void editDoesNotDoubleCount() {
        String c = customer(1000, false);
        String id = placed(c, 6).get("id").asString();
        ok(200, "PUT", "/orders/" + id + "/items", Map.of("lines", List.of(Map.of("productId", service, "quantity", 8,
                "unitPrice", 100))));
        Res r = as("PUT", "/orders/" + id + "/items", Map.of("lines", List.of(Map.of("productId", service, "quantity", 11,
                "unitPrice", 100))));
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("invoice post is checked; open invoices then count")
    void invoicePost() {
        String c = customer(1000, false);
        String today = LocalDate.now().toString();
        JsonNode first = ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", today, "lines",
                List.of(Map.of("productId", service, "quantity", 6, "unitPrice", 100))));
        ok(200, "POST", "/invoices/" + first.get("id").asString() + "/post", null);
        JsonNode second = ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", today, "lines",
                List.of(Map.of("productId", service, "quantity", 6, "unitPrice", 100))));
        assertThat(as("POST", "/invoices/" + second.get("id").asString() + "/post", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("an override lets someone else sell, not the person who granted it; revoke ends it")
    void overrideSegregation() {
        String c = customer(0, true);
        JsonNode granted = ok(200, "POST", "/credit-control/customers/" + c + "/overrides", Map.of("coversHold", true,
                "reason", "Paid by bank transfer", "expiresAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()));
        assertThat(as("POST", "/orders", order(c, 1)).status()).isEqualTo(400);
        Res seller = call("POST", "/orders", order(c, 1), sellerToken);
        assertThat(seller.status()).isEqualTo(200);
        created.add(seller.body().get("id").asString());
        ok(200, "POST", "/credit-control/overrides/" + granted.get("overrides").get(0).get("id").asString() + "/revoke",
                null);
        assertThat(call("POST", "/orders", order(c, 1), sellerToken).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("limit and hold are set through the policy route and audited")
    void policy() {
        String c = customer(0, false);
        JsonNode p = ok(200, "PUT", "/credit-control/customers/" + c + "/policy", Map.of("creditLimit", 500,
                "creditHold", true, "reason", "Slow payer"));
        assertThat(p.get("creditHold").asBoolean()).isTrue();
        assertThat(p.get("creditLimit").decimalValue()).isEqualByComparingTo("500");
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"entityId\" = ?").param(c).query(Long.class)
                .single()).isPositive();
    }

    @Test
    @DisplayName("customers list filters by hold")
    void list() {
        String c = customer(0, true);
        JsonNode rows = ok(200, "GET", "/credit-control/customers?status=hold&pageSize=200", null).get("data");
        java.util.Set<String> ids = new java.util.HashSet<>();
        rows.forEach(r -> ids.add(r.get("partnerId").asString()));
        assertThat(ids).contains(c);
    }

    @Test
    @DisplayName("tenant isolation and authorization matrix")
    void guards() {
        String c = customer(100, false);
        assertThat(call("GET", "/credit-control/customers/" + c, null, otherToken).status()).isEqualTo(404);
        assertThat(assertAuthorizationMatrix(CreditControlController.class)).isEqualTo(5);
    }
}
