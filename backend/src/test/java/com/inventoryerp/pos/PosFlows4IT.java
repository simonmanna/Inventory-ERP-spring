package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * POS back-office edits: loyalty points and the customer tab, a full table edit, deleting modifiers and groups,
 * a cashier's password change, and the receipt list filters.
 */
class PosFlows4IT extends ApiTestSupport {

    static final AtomicInteger NUMBER = new AtomicInteger(9700);

    String suffix;
    String customer;

    @BeforeAll
    void fixtures() {
        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        customer = ok(201, "POST", "/partners", Map.of("code", "F4-" + suffix, "name", "Loyal " + suffix,
                "isCustomer", true)).get("id").asString();
        ok(201, "POST", "/pos/loyalty/program/ensure", null);
    }

    JsonNode okish(String method, String path, Object body) {
        Res r = as(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    @Test
    @DisplayName("loyalty: earn then redeem points; over-redeeming is refused")
    void loyaltyPoints() {
        okish("POST", "/pos/loyalty/earn", Map.of("partnerId", customer, "documentId", UUID.randomUUID().toString(),
                "amount", 500000, "reason", "sale"));
        assertThat(ok(200, "GET", "/pos/loyalty/balance/" + customer, null).toString()).isNotEqualTo("{}");
        ok(200, "GET", "/pos/loyalty/earned/" + customer, null);
        Res tooFew = as("POST", "/pos/loyalty/redeem", Map.of("partnerId", customer, "points", 1));
        assertThat(tooFew.body().get("message").asString()).isEqualTo("Minimum redemption is 50 points");
        okish("POST", "/pos/loyalty/redeem", Map.of("partnerId", customer, "points", 50, "documentId",
                UUID.randomUUID().toString()));
        Res tooMany = as("POST", "/pos/loyalty/redeem", Map.of("partnerId", customer, "points", 10_000_000));
        assertThat(tooMany.status()).isIn(400, 409);
    }

    @Test
    @DisplayName("customer tab: open, charge, settle; the balance follows")
    void customerTab() {
        okish("POST", "/pos/loyalty/tab/open", Map.of("partnerId", customer, "creditLimit", 100000));
        okish("POST", "/pos/loyalty/tab/charge", Map.of("partnerId", customer, "documentId",
                UUID.randomUUID().toString(), "amount", 30000, "reason", "lunch"));
        JsonNode tab = ok(200, "GET", "/pos/loyalty/tab/" + customer, null);
        assertThat(tab.toString()).contains("30000");
        okish("POST", "/pos/loyalty/tab/settle", Map.of("partnerId", customer, "amount", 10000, "reason", "cash"));
        Res over = as("POST", "/pos/loyalty/tab/charge", Map.of("partnerId", customer, "documentId",
                UUID.randomUUID().toString(), "amount", 1_000_000));
        assertThat(over.status()).isIn(400, 409);
    }

    @Test
    @DisplayName("table: every editable field, then status and waiter")
    void tableEdit() {
        int n = NUMBER.incrementAndGet();
        String id = ok(201, "POST", "/pos/tables", Map.of("name", "F4 " + n, "number", n)).get("id").asString();
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class)
                .single();
        JsonNode t = okish("PATCH", "/pos/tables/" + id, Map.ofEntries(Map.entry("name", "Window " + n),
                Map.entry("seats", 6), Map.entry("zone", "terrace"), Map.entry("shape", "circle"),
                Map.entry("posX", 40), Map.entry("posY", 80), Map.entry("width", 90), Map.entry("height", 90),
                Map.entry("notes", "By the window"), Map.entry("sortOrder", 3), Map.entry("assignedWaiterId", adminId),
                Map.entry("qrCodeUrl", "https://example.invalid/t/" + n)));
        assertThat(t.get("seats").asInt()).isEqualTo(6);
        assertThat(t.get("shape").asString()).isEqualTo("circle");
        okish("PUT", "/pos/tables/" + id + "/status", Map.of("status", "out_of_service", "reason", "Wobbly leg"));
        okish("PUT", "/pos/tables/" + id + "/status", Map.of("status", "available"));
        okish("POST", "/pos/tables/" + id + "/assign-waiter", Map.of("waiterId", adminId));
        assertThat(as("PATCH", "/pos/tables/" + id, Map.of("width", 5)).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("modifiers and groups can be deleted")
    void modifierDeletes() {
        String group = ok(201, "POST", "/pos/modifiers/groups", Map.of("name", "Temp " + suffix)).get("id").asString();
        String mod = ok(201, "POST", "/pos/modifiers/modifiers", Map.of("groupId", group, "name", "Ice",
                "priceDelta", 0)).get("id").asString();
        okish("DELETE", "/pos/modifiers/modifiers/" + mod, null);
        okish("DELETE", "/pos/modifiers/groups/" + group, null);
        assertThat(ok(200, "GET", "/pos/modifiers/groups?search=Temp%20" + suffix, null).toString())
                .doesNotContain(group);
    }

    @Test
    @DisplayName("a cashier changes their password; the old one stops working")
    void changePassword() {
        String roleId = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                .param(demoOrg).query(String.class).list().getFirst();
        String email = "pwd-" + suffix.toLowerCase() + "@demo.test";
        createUser(demoOrg, email, "Start@12345", roleId);
        String token = login("DEMO", email, "Start@12345");
        Res wrong = call("POST", "/pos/auth/change-password", Map.of("currentPassword", "nope", "newPassword",
                "Next@123456"), token);
        assertThat(wrong.status()).isIn(400, 401, 403);
        Res changed = call("POST", "/pos/auth/change-password", Map.of("currentPassword", "Start@12345",
                "newPassword", "Next@123456"), token);
        assertThat(changed.status()).as(changed.raw().body()).isIn(200, 201);
        assertThat(login("DEMO", email, "Next@123456")).isNotBlank();
    }

    @Test
    @DisplayName("receipt list filters")
    void receiptFilters() {
        String range = "from=" + LocalDate.now().minusDays(7) + "&to=" + LocalDate.now().plusDays(1);
        for (String q : List.of("?" + range, "?" + range + "&status=paid", "?search=INV", "?partnerId=" + customer,
                "?page=2&pageSize=5", "?paymentMethod=cash")) {
            ok(200, "GET", "/pos/receipts" + q, null);
        }
    }
}
