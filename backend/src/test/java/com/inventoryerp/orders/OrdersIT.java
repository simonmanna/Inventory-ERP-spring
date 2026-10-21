package com.inventoryerp.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Back-office orders on Testcontainers Postgres (ENABLE_ORDERS on in the test profile): line-source settings,
 * catalog-resolved pricing, auto-save / append / versioning, cancel / reopen through the shared order workflow,
 * billing, DTO rules from {@code orders.dto.spec.ts}, tenant isolation and the authorization matrix.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrdersIT extends ApiTestSupport {

    String productId;
    String customerId;

    @BeforeAll
    void fixtures() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        productId = ok(201, "POST", "/products", Map.of("code", "ORD-SVC-" + suffix, "name", "Delivery fee " + suffix,
                "productType", "service", "salesPrice", 100, "costPrice", 0)).get("id").asString();
        customerId = ok(201, "POST", "/partners", Map.of("code", "ORD-C-" + suffix, "name", "Orders Cust " + suffix,
                "isCustomer", true)).get("id").asString();
    }

    final List<String> created = new java.util.ArrayList<>();

    JsonNode create(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("lines", List.of(Map.of("productId", productId, "quantity", 2))));
        body.putAll(extra);
        JsonNode o = ok(200, "POST", "/orders", body);
        created.add(o.get("id").asString());
        return o;
    }

    /** Unbilled open orders block every later POS shift close in the shared DEMO org (Nest rule) — cancel ours. */
    @AfterAll
    void cancelLeftovers() {
        jdbc.sql("UPDATE \"Order\" SET status = 'cancelled', \"cancelledAt\" = now() WHERE id = ANY(?)"
                + " AND \"invoiceId\" IS NULL AND status::text <> 'cancelled'").param(created.toArray(String[]::new))
                .update();
    }

    @Test
    @DisplayName("settings: auto follows posMode; explicit lineSource wins; unknown values 400")
    void settings() {
        JsonNode s = ok(200, "GET", "/orders/settings", null);
        assertThat(s.has("lineSource")).isTrue();
        assertThat(s.has("resolved")).isTrue();
        assertThat(s.has("posMode")).isTrue();
        assertThat(ok(200, "PATCH", "/orders/settings", Map.of("lineSource", "products")).get("lineSource").asString())
                .isEqualTo("products");
        assertThat(ok(200, "GET", "/orders/settings", null).get("resolved").asString()).isEqualTo("products");
        assertThat(jdbc.sql("SELECT value #>> '{}' FROM \"Setting\" WHERE \"organizationId\" = ? AND key = 'orders.lineSource'")
                .param(demoOrg).query(String.class).single()).isEqualTo("products");
        Res bad = as("PATCH", "/orders/settings", Map.of("lineSource", "products2"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("message").get(0).asString())
                .isEqualTo("lineSource must be one of the following values: auto, menu, products, both");
        ok(200, "PATCH", "/orders/settings", Map.of("lineSource", "auto"));
        assertThat(ok(200, "GET", "/orders/settings", null).get("lineSource").asString()).isEqualTo("auto");
    }

    @Test
    @DisplayName("create resolves name + price from the catalog, defaults walk-in partner, takeaway and sale")
    void createAndRead() {
        JsonNode o = create(Map.of());
        assertThat(o.get("orderNumber").asString()).startsWith("ORD-");
        assertThat(o.get("status").asString()).isEqualTo("confirmed");
        assertThat(o.get("orderType").asString()).isEqualTo("takeaway");
        assertThat(o.get("transactionKind").asString()).isEqualTo("sale");
        assertThat(o.get("partnerName").asString()).isEqualTo("Walk-in Customer");
        assertThat(o.get("totalAmount").asDouble()).isGreaterThanOrEqualTo(200.0);
        assertThat(o.get("items")).hasSize(1);
        JsonNode item = o.get("items").get(0);
        assertThat(item.get("description").asString()).startsWith("Delivery fee");
        assertThat(item.get("unitPrice").asDouble()).isEqualTo(100.0);
        assertThat(item.get("quantity").asDouble()).isEqualTo(2.0);
        assertThat(item.get("modifiers")).isEmpty();
        String id = o.get("id").asString();

        JsonNode one = ok(200, "GET", "/orders/" + id, null);
        assertThat(one.get("invoice").isNull()).isTrue();
        assertThat(one.get("waiterName").isNull()).isTrue();

        JsonNode list = ok(200, "GET", "/orders?search=" + o.get("orderNumber").asString(), null);
        assertThat(list.get("total").asInt()).isEqualTo(1);
        assertThat(list.get("rows").get(0).get("id").asString()).isEqualTo(id);
        assertThat(list.get("meta").get("pageSize").asInt()).isEqualTo(25);
        assertThat(list.get("rows").get(0).get("totalAmount").isNumber()).isTrue();
        Res badPage = as("GET", "/orders?page=x", null);
        assertThat(badPage.status()).isEqualTo(400);
        assertThat(badPage.body().get("message").asString()).isEqualTo("Validation failed (numeric string is expected)");
        assertThat(ok(200, "GET", "/orders?status=cancelled&orderType=delivery&dateFrom=2000-01-01&dateTo=2000-01-02",
                null).get("total").asInt()).isZero();

        Res missing = as("GET", "/orders/" + UUID.randomUUID(), null);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body().get("message").asString()).isEqualTo("Order not found");
        Res foreign = call("GET", "/orders/" + id, null, otherToken);
        assertThat(foreign.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("DTO rules (orders.dto.spec.ts): line ref, quantity ≥ 1, non-empty append, no unknown fields")
    void dtoRules() {
        Res noRef = as("POST", "/orders", Map.of("orderType", "delivery",
                "lines", List.of(Map.of("description", "freeform text only", "quantity", 1))));
        assertThat(noRef.status()).isEqualTo(400);
        assertThat(noRef.body().get("message").toString())
                .contains("lines.0.line must reference a menuItemId or a productId");
        Res zero = as("POST", "/orders", Map.of("lines", List.of(Map.of("menuItemId", "m-1", "quantity", 0))));
        assertThat(zero.status()).isEqualTo(400);
        assertThat(zero.body().get("message").toString()).contains("lines.0.quantity must not be less than 1");
        String id = create(Map.of()).get("id").asString();
        Res empty = as("POST", "/orders/" + id + "/items", Map.of("lines", List.of()));
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.body().get("message").toString()).contains("lines must contain at least 1 elements");
        Res unknown = as("PUT", "/orders/" + id + "/items", Map.of("version", 42, "expectedVersion", 1,
                "lines", List.of(Map.of("productId", productId, "quantity", 1))));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body().get("message").toString()).contains("property version should not exist");
    }

    @Test
    @DisplayName("save replaces with version check; append keeps lines; header patch")
    void editItems() {
        JsonNode o = create(Map.of("partnerId", customerId, "orderType", "delivery", "guestCount", 3));
        String id = o.get("id").asString();
        int version = o.get("version").asInt();
        Res stale = as("PUT", "/orders/" + id + "/items", Map.of("expectedVersion", version + 5,
                "lines", List.of(Map.of("productId", productId, "quantity", 1))));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("message").asString()).isEqualTo("Order was modified by someone else (expected v"
                + (version + 5) + ", found v" + version + "). Reload and retry.");
        JsonNode saved = ok(200, "PUT", "/orders/" + id + "/items", Map.of("expectedVersion", version,
                "lines", List.of(Map.of("productId", productId, "quantity", 3, "unitPrice", 50))));
        assertThat(saved.get("items")).hasSize(1);
        assertThat(saved.get("items").get(0).get("unitPrice").asDouble()).isEqualTo(50.0);
        assertThat(saved.get("version").asInt()).isEqualTo(version + 1);
        JsonNode appended = ok(201, "POST", "/orders/" + id + "/items", Map.of("lines",
                List.of(Map.of("productId", productId, "quantity", 1))));
        assertThat(appended.get("items")).hasSize(2);
        assertThat(appended.get("items").get(0).get("quantity").asDouble()).isEqualTo(3.0);
        assertThat(appended.get("items").get(1).get("lineNumber").asInt()).isEqualTo(2);
        JsonNode header = ok(200, "PATCH", "/orders/" + id, Map.of("notes", "Ring twice", "paymentMethod", "credit"));
        assertThat(header.get("notes").asString()).isEqualTo("Ring twice");
        assertThat(header.get("orderType").asString()).isEqualTo("delivery");
        assertThat(header.get("guestCount").asInt()).isEqualTo(3);
        assertThat(header.get("items")).isEmpty();
        assertThat(header.get("partnerName").asString()).startsWith("Orders Cust");
    }

    @Test
    @DisplayName("cancel / reopen through the order workflow; cancelled orders are not editable")
    void cancelReopen() {
        String id = create(Map.of()).get("id").asString();
        Res notCancelled = as("POST", "/orders/" + id + "/reopen", null);
        assertThat(notCancelled.status()).isEqualTo(400);
        assertThat(notCancelled.body().get("message").asString()).isEqualTo("Only a cancelled order can be reopened");
        JsonNode cancelled = ok(200, "POST", "/orders/" + id + "/cancel", Map.of("reason", "Customer left"));
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        assertThat(cancelled.get("cancelReason").asString()).isEqualTo("Customer left");
        assertThat(ok(200, "POST", "/orders/" + id + "/cancel", null).get("status").asString()).isEqualTo("cancelled");
        Res edit = as("PATCH", "/orders/" + id, Map.of("notes", "x"));
        assertThat(edit.status()).isEqualTo(409);
        assertThat(edit.body().get("message").asString()).isEqualTo("Order is cancelled");
        JsonNode reopened = ok(200, "POST", "/orders/" + id + "/reopen", null);
        assertThat(reopened.get("status").asString()).isNotEqualTo("cancelled");
        assertThat(reopened.get("cancelReason").isNull()).isTrue();
    }

    @Test
    @DisplayName("invoice bills through the POS spine; billed orders cannot be cancelled, reopened or edited")
    void billing() {
        String id = create(Map.of("partnerId", customerId)).get("id").asString();
        Res billed = as("POST", "/orders/" + id + "/invoice", Map.of("paymentMode", "credit"));
        assertThat(billed.status()).as(billed.raw().body()).isIn(200, 201);
        String invoiceId = jdbc.sql("SELECT \"invoiceId\" FROM \"Order\" WHERE id = ?").param(id).query(String.class)
                .single();
        assertThat(invoiceId).isNotNull();
        Res cancel = as("POST", "/orders/" + id + "/cancel", Map.of());
        assertThat(cancel.status()).isEqualTo(409);
        assertThat(cancel.body().get("message").asString())
                .isEqualTo("Order already billed — refund/void the invoice instead");
        Res reopen = as("POST", "/orders/" + id + "/reopen", null);
        assertThat(reopen.status()).isEqualTo(409);
        assertThat(reopen.body().get("message").asString()).isEqualTo("Billed orders cannot be reopened");
        Res edit = as("POST", "/orders/" + id + "/items", Map.of("lines", List.of(Map.of("productId", productId,
                "quantity", 1))));
        assertThat(edit.status()).isEqualTo(409);
        assertThat(edit.body().get("message").asString()).isEqualTo("Order is already billed and cannot be edited");
        assertThat(ok(200, "GET", "/orders/" + id, null).get("invoice").get("id").asString()).isEqualTo(invoiceId);
    }

    @Test
    @DisplayName("authorization matrix: 401 anonymous, 403 without the orders permission (11 routes)")
    void authorization() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.orders")).isEqualTo(11);
    }
}
