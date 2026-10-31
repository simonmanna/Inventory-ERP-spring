package com.inventoryerp.portal;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * PORTAL-1 — staff open a login for a customer; the customer sees only its own orders, deliveries, invoices and
 * statement; a login cannot reach anything else, a disabled account stops at once, and the other organization sees
 * nothing.
 */
class PortalIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    int seq;
    String customerA;
    String customerB;
    String accountId;
    String tokenA;
    String tokenB;
    String orderA;
    String orderB;
    String invoiceA;
    String invoiceB;
    String draftInvoiceA;
    String tripA;
    String tripB;

    String partner(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "PC-" + sfx + "-" + (++seq), name + sfx)
                .update();
        return id;
    }

    String order(String partnerId, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Order\" (id, \"organizationId\", \"orderNumber\", \"partnerId\", status,"
                + " \"totalAmount\", \"updatedAt\") VALUES (?, ?, ?, ?, ?::\"OrderStatus\", 1000, now())")
                .params(id, demoOrg, "PO-" + sfx + "-" + (++seq), partnerId, status).update();
        return id;
    }

    String invoice(String partnerId, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Invoice\" (id, \"organizationId\", \"invoiceNumber\", \"partnerId\", status,"
                + " \"totalAmount\", \"amountResidual\", \"updatedAt\") VALUES (?, ?, ?, ?, ?::\"InvoiceStatus\", 500,"
                + " 500, now())").params(id, demoOrg, "PI-" + sfx + "-" + (++seq), partnerId, status).update();
        return id;
    }

    String trip(String partnerId, String location) {
        String delivery = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetDeliveryOrder\" (id, \"organizationId\", code, \"partnerId\", \"locationId\","
                + " \"requestedDate\") VALUES (?, ?, ?, ?, ?, CURRENT_DATE)")
                .params(delivery, demoOrg, "PD-" + sfx + "-" + (++seq), partnerId, location).update();
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetLoad\" (id, \"organizationId\", code, \"deliveryOrderId\", sequence,"
                + " \"plannedDate\", \"locationId\", \"freightAmount\") VALUES (?, ?, ?, ?, 1, CURRENT_DATE, ?, 77777)")
                .params(id, demoOrg, "PT-" + sfx + "-" + (++seq), delivery, location).update();
        return id;
    }

    String account(String partnerId, String prefix) {
        String email = prefix + "-" + sfx.toLowerCase() + "@customer.test";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", partnerId);
        body.put("email", email);
        body.put("password", "Portal@12345");
        body.put("firstName", "Cora");
        return ok(201, "POST", "/portal-accounts", body).get("id").asString();
    }

    String login(String prefix) {
        return login("DEMO", prefix + "-" + sfx.toLowerCase() + "@customer.test", "Portal@12345");
    }

    @BeforeAll
    void fixtures() {
        customerA = partner("Acme Build ");
        customerB = partner("Beta Build ");
        accountId = account(customerA, "cora");
        account(customerB, "bob");
        tokenA = login("cora");
        tokenB = login("bob");
        orderA = order(customerA, "confirmed");
        orderB = order(customerB, "confirmed");
        invoiceA = invoice(customerA, "posted");
        invoiceB = invoice(customerB, "posted");
        draftInvoiceA = invoice(customerA, "draft");
        String location = ok(201, "POST", "/inventory/locations", Map.of("code", "PY-" + sfx, "name",
                "Portal yard " + sfx, "type", "warehouse")).get("id").asString();
        tripA = trip(customerA, location);
        tripB = trip(customerB, location);
    }

    @Test
    @DisplayName("home: the customer and its open balance")
    void home() {
        JsonNode home = call("GET", "/portal/home", null, tokenA).body();
        assertThat(home.get("customer").get("id").asString()).isEqualTo(customerA);
        assertThat(home.get("liveDeliveries").asInt()).isEqualTo(1);
        assertThat(home.get("openOrders").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("scope: lists show only my documents; another customer's document answers 404")
    void partnerScope() {
        JsonNode orders = call("GET", "/portal/orders", null, tokenA).body();
        assertThat(orders.get("data").toString()).contains(orderA).doesNotContain(orderB);
        assertThat(call("GET", "/portal/orders/" + orderA, null, tokenA).status()).isEqualTo(200);
        assertThat(call("GET", "/portal/orders/" + orderB, null, tokenA).status()).isEqualTo(404);

        JsonNode invoices = call("GET", "/portal/invoices", null, tokenA).body();
        assertThat(invoices.get("data").toString()).contains(invoiceA).doesNotContain(invoiceB, draftInvoiceA);
        assertThat(call("GET", "/portal/invoices/" + invoiceA, null, tokenA).body().get("amountResidual").asString())
                .isNotBlank();
        assertThat(call("GET", "/portal/invoices/" + invoiceB, null, tokenA).status()).isEqualTo(404);
        assertThat(call("GET", "/portal/invoices/" + draftInvoiceA, null, tokenA).status()).isEqualTo(404);
        assertThat(call("GET", "/portal/invoices/" + invoiceB + "/pdf", null, tokenA).status()).isEqualTo(404);

        JsonNode deliveries = call("GET", "/portal/deliveries", null, tokenA).body();
        assertThat(deliveries.get("data").toString()).contains(tripA).doesNotContain(tripB);
        assertThat(call("GET", "/portal/deliveries/" + tripB, null, tokenA).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("delivery: no freight or other internal figures reach the customer")
    void deliveryDetail() {
        Res r = call("GET", "/portal/deliveries/" + tripA, null, tokenA);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status").asString()).isEqualTo("planned");
        assertThat(r.raw().body()).doesNotContain("77777").doesNotContain("freight");
        assertThat(r.body().get("position").isNull()).isTrue();
    }

    @Test
    @DisplayName("statement: the customer's own statement")
    void statement() {
        Res r = call("GET", "/portal/statement", null, tokenA);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("partner").get("id").asString()).isEqualTo(customerA);
    }

    @Test
    @DisplayName("lockdown: a portal login keeps its session routes and reaches nothing else")
    void lockdown() {
        assertThat(call("GET", "/auth/me", null, tokenA).status()).isEqualTo(200);
        assertThat(call("GET", "/auth/permissions", null, tokenA).status()).isEqualTo(403);
        assertThat(call("GET", "/partners", null, tokenA).status()).isEqualTo(403);
        assertThat(call("GET", "/portal-accounts", null, tokenA).status()).isEqualTo(403);
        assertThat(call("GET", "/auth/permissions", null, adminToken).status()).isEqualTo(200);
        // A staff user without an account has no customer to act as.
        assertThat(as("GET", "/portal/orders", null).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("accounts: a non-customer is refused; a duplicate e-mail conflicts; disable stops the login at once")
    void accounts() {
        String supplier = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(supplier, demoOrg, "PS-" + sfx, "Supplier " + sfx)
                .update();
        assertThat(as("POST", "/portal-accounts", Map.of("partnerId", supplier, "email", "s" + sfx + "@customer.test",
                "password", "Portal@12345", "firstName", "S")).status()).isEqualTo(400);
        assertThat(as("POST", "/portal-accounts", Map.of("partnerId", customerA, "email",
                "cora-" + sfx.toLowerCase() + "@customer.test", "password", "Portal@12345", "firstName", "C"))
                .status()).isEqualTo(409);

        String id = account(customerA, "dora");
        String token = login("dora");
        assertThat(call("GET", "/portal/home", null, token).status()).isEqualTo(200);
        assertThat(ok(200, "POST", "/portal-accounts/" + id + "/disable", null).get("status").asString())
                .isEqualTo("disabled");
        assertThat(call("GET", "/portal/home", null, token).status()).isEqualTo(403);
        assertThat(ok(200, "POST", "/portal-accounts/" + id + "/enable", null).get("status").asString())
                .isEqualTo("active");
        JsonNode list = ok(200, "GET", "/portal-accounts?partnerId=" + customerA, null);
        assertThat(list.get("meta").get("total").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("tenant isolation: another organization sees no accounts and cannot touch one")
    void tenantIsolation() {
        assertThat(call("GET", "/portal-accounts?partnerId=" + customerA, null, otherToken).body().get("meta")
                .get("total").asLong()).isEqualTo(0);
        assertThat(call("POST", "/portal-accounts/" + accountId + "/disable", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/portal/orders/" + orderA, null, otherToken).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("authorization matrix: 401 / 403 on every portal route")
    void authorization() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.portal.web")).isEqualTo(16);
    }
}
