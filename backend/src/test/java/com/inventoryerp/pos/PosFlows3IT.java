package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Manager override checks (PIN, password, wrong / missing credentials), menu-item lines with an accompaniment and a
 * variant, and the POS reports with every filter.
 */
class PosFlows3IT extends ApiTestSupport {

    static final String PIN = "4321";

    String suffix;
    String registerId;
    String sessionId;
    String itemId;
    String optionId;
    String variantId;

    @BeforeAll
    void fixtures() {
        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        ok(201, "POST", "/pos/override/pin", Map.of("pin", PIN));
        itemId = ok(201, "POST", "/pos/menu/items", Map.of("name", "Bowl " + suffix, "basePrice", 8000,
                "isInventoryTracked", false)).get("id").asString();
        String groupId = ok(201, "POST", "/pos/menu/accompaniments/groups", Map.of("name", "Base " + suffix))
                .get("id").asString();
        optionId = ok(201, "POST", "/pos/menu/accompaniments/groups/" + groupId + "/options", Map.of("name",
                "Rice", "priceImpact", 1000)).get("id").asString();
        ok(201, "POST", "/pos/menu/items/" + itemId + "/accompaniments", Map.of("accompanimentGroupId", groupId));
        variantId = ok(201, "POST", "/pos/menu/items/" + itemId + "/variants", Map.of("name", "Large", "price",
                10000)).get("id").asString();
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "F3-" + suffix, "name", "Flow 3 till"))
                .get("id").asString();
        Res opened = call("POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId, "openingFloat", 0),
                Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(opened.status()).as(opened.raw().body()).isEqualTo(200);
        sessionId = opened.body().get("id").asString();
    }

    @AfterAll
    void cleanup() {
        forceCloseSessions(List.of(registerId));
    }

    @Test
    @DisplayName("override verify: PIN, password, and the refusals")
    void overrides() {
        JsonNode pin = ok(201, "POST", "/pos/override/verify", Map.of("email", "admin@demo.test", "pin", PIN,
                "overrideKind", "void"));
        assertThat(pin.toString()).contains("admin@demo.test");
        ok(201, "POST", "/pos/override/verify", Map.of("email", "admin@demo.test", "password", "Admin@123",
                "overrideKind", "manual_refund"));
        Res wrong = as("POST", "/pos/override/verify", Map.of("email", "admin@demo.test", "pin", "0000",
                "overrideKind", "discount"));
        assertThat(wrong.status()).isEqualTo(401);
        Res none = as("POST", "/pos/override/verify", Map.of("overrideKind", "discount"));
        assertThat(none.body().get("message").asString()).isEqualTo("pin or password is required");
        Res noEmail = as("POST", "/pos/override/verify", Map.of("password", "x", "overrideKind", "discount"));
        assertThat(noEmail.body().get("message").asString())
                .isEqualTo("email is required to verify a manager password");
        Res unknown = as("POST", "/pos/override/verify", Map.of("email", "nobody-" + suffix + "@demo.test",
                "password", "x", "overrideKind", "discount"));
        assertThat(unknown.status()).isEqualTo(404);
        Res pinOnly = as("POST", "/pos/override/verify-pin", Map.of("pin", "0000"));
        assertThat(pinOnly.status()).isIn(401, 403);
    }

    @Test
    @DisplayName("menu-item line with a variant and an accompaniment is priced and stored with both")
    void menuItemSelections() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("menuItemId", itemId);
        line.put("description", "Bowl");
        line.put("quantity", 1);
        line.put("unitPrice", 10000);
        line.put("variantId", variantId);
        line.put("variantName", "Large");
        line.put("variantPrice", 10000);
        line.put("accompanimentOptionIds", List.of(optionId));
        line.put("accompanimentNames", List.of("Rice"));
        line.put("accompanimentPriceImpact", 1000);
        Res sale = call("POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "orderType", "takeaway",
                "lines", List.of(line), "tenders", List.of(Map.of("method", "cash", "amount", 11000))),
                Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(sale.status()).as(sale.raw().body()).isEqualTo(200);
        assertThat(sale.body().get("total").asInt()).isEqualTo(11000);
        String invoiceId = sale.body().get("invoiceId").asString();
        assertThat(jdbc.sql("SELECT \"accompanimentOptionIds\"::text FROM \"InvoiceItem\" WHERE \"invoiceId\" = ?")
                .param(invoiceId).query(String.class).single()).contains(optionId);
        Map<String, Object> bad = new LinkedHashMap<>(line);
        bad.put("accompanimentOptionIds", List.of(UUID.randomUUID().toString()));
        Res refused = call("POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines", List.of(bad),
                "tenders", List.of(Map.of("method", "cash", "amount", 11000))), Map.of("Authorization", "Bearer "
                        + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(refused.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("POS reports with every filter")
    void filteredReports() {
        String range = "fromDate=" + LocalDate.now().minusDays(1) + "&toDate=" + LocalDate.now().plusDays(1);
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class)
                .single();
        String category = jdbc.sql("SELECT id FROM \"ProductCategory\" WHERE \"organizationId\" = ? LIMIT 1")
                .param(demoOrg).query(String.class).optional().orElse(UUID.randomUUID().toString());
        for (String filter : List.of("", "&orderType=takeaway", "&waiterId=" + adminId, "&paymentMethod=cash",
                "&categoryId=" + category, "&limit=5")) {
            ok(200, "GET", "/pos/reports/top-items?" + range + filter, null);
        }
        for (String path : List.of("sales-by-hour", "sales-report", "order-report")) {
            ok(200, "GET", "/pos/reports/" + path + "?" + range + "&orderType=takeaway&waiterId=" + adminId
                    + "&paymentMethod=cash", null);
        }
    }
}
