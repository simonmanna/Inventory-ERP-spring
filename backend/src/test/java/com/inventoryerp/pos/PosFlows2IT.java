package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.pos.application.PosStockPostingService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * More POS flows: stock-tracked lines (availability check, restock on refund), lines given by SKU, the public QR
 * order, an additional bill after new items, voiding a fired line (cancel KOT) and a KDS ticket's lifecycle.
 */
class PosFlows2IT extends ApiTestSupport {

    static final AtomicInteger NUMBER = new AtomicInteger(9400);
    static final String PIN = "4321";

    @Autowired
    StockService stock;

    @Autowired
    PosStockPostingService postings;

    String suffix;
    String adminId;
    String warehouseId;
    String registerId;
    String sessionId;
    String tea;
    String beans;

    @BeforeAll
    void fixtures() {
        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        warehouseId = pinPosWarehouse();
        ok(201, "POST", "/pos/override/pin", Map.of("pin", PIN));
        tea = ok(201, "POST", "/products", Map.of("code", "F2-TEA-" + suffix, "name", "Tea " + suffix, "sku",
                "SKU-TEA-" + suffix, "productType", "service", "salesPrice", 3000)).get("id").asString();
        beans = ok(201, "POST", "/products", Map.of("code", "F2-BEAN-" + suffix, "name", "Beans " + suffix,
                "productType", "stockable", "trackInventory", true, "costingMethod", "AVCO", "costPrice", 0,
                "salesPrice", 5000)).get("id").asString();
        TenantContext.run(TenantStore.organization(demoOrg), () -> stock.receive(StockMove.of(beans, warehouseId,
                BigDecimal.valueOf(50)).unitCost(BigDecimal.TEN)));
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "F2-" + suffix, "name", "Flow 2 till"))
                .get("id").asString();
        sessionId = money(200, "POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId, "openingFloat", 0))
                .get("id").asString();
    }

    @AfterAll
    void cleanup() {
        as("PUT", "/settings/inventory.atpMode", Map.of("value", "post_commit"));
        for (String id : jdbc.sql("SELECT id FROM \"Order\" WHERE \"cashSessionId\" = ? AND status::text NOT IN"
                + " ('closed', 'cancelled', 'completed')").param(sessionId).query(String.class).list()) {
            keyed("POST", "/pos/orders/" + id + "/cancel", Map.of("reason", "flow test cleanup", "overrideById",
                    adminId, "overridePin", PIN));
        }
        forceCloseSessions(List.of(registerId));
    }

    Res keyed(String method, String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    JsonNode money(int status, String method, String path, Object body) {
        Res r = keyed(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ?").params(demoOrg, productId).query(BigDecimal.class).single();
    }

    static Map<String, Object> line(String productId, String description, int qty, int price) {
        return Map.of("productId", productId, "description", description, "quantity", qty, "unitPrice", price);
    }

    JsonNode sale(List<Map<String, Object>> lines, int amount) {
        return money(200, "POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines", lines, "tenders",
                List.of(Map.of("method", "cash", "amount", amount))));
    }

    String table() {
        int n = NUMBER.incrementAndGet();
        return ok(201, "POST", "/pos/tables", Map.of("name", "Flow2 " + n, "number", n)).get("id").asString();
    }

    @Test
    @DisplayName("availability check: pre-invoice sells what is there, strict refuses an oversell")
    void availabilityCheck() {
        ok(200, "PUT", "/settings/inventory.atpMode", Map.of("value", "pre_invoice"));
        try {
            assertThat(sale(List.of(line(beans, "Beans", 2, 5000)), 10000).get("total").asInt()).isEqualTo(10000);
            ok(200, "PUT", "/settings/inventory.atpMode", Map.of("value", "strict"));
            Res over = keyed("POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines",
                    List.of(line(beans, "Beans", 100_000, 5000)), "tenders", List.of(Map.of("method", "cash",
                            "amount", 500_000_000))));
            assertThat(over.status()).as(over.raw().body()).isIn(400, 409);
            // The refused sale leaves its order open (created before invoicing); it would block every shift close.
            for (String id : jdbc.sql("SELECT id FROM \"Order\" WHERE \"cashSessionId\" = ? AND status::text NOT IN"
                    + " ('closed', 'cancelled', 'completed')").param(sessionId).query(String.class).list()) {
                money(200, "POST", "/pos/orders/" + id + "/cancel", Map.of("reason", "oversell refused",
                        "overrideById", adminId, "overridePin", PIN));
            }
        } finally {
            ok(200, "PUT", "/settings/inventory.atpMode", Map.of("value", "post_commit"));
        }
    }

    @Test
    @DisplayName("a refund with restock puts the stock back once the sale's postings are done")
    void refundRestock() {
        JsonNode sold = sale(List.of(line(beans, "Beans", 3, 5000)), 15000);
        String invoiceId = sold.get("invoiceId").asString();
        TenantContext.run(TenantStore.organization(demoOrg), () -> postings.drainSession(sessionId));
        BigDecimal before = onHand(beans);
        String lineId = jdbc.sql("SELECT id FROM \"InvoiceItem\" WHERE \"invoiceId\" = ?").param(invoiceId)
                .query(String.class).single();
        JsonNode refund = money(200, "POST", "/pos/invoices/" + invoiceId + "/refund", Map.of("reason",
                "Wrong grind", "overrideById", adminId, "overridePin", PIN, "stockDisposition", "restock",
                "cashSessionId", sessionId, "lines", List.of(Map.of("lineId", lineId, "quantity", 2))));
        assertThat(refund.toString()).isNotEmpty();
        assertThat(onHand(beans)).isEqualByComparingTo(before.add(BigDecimal.valueOf(2)));
    }

    @Test
    @DisplayName("a line given only by SKU is resolved to its product")
    void lineBySku() {
        JsonNode sold = sale(List.of(Map.of("sku", "SKU-TEA-" + suffix, "description", "Tea", "quantity", 2,
                "unitPrice", 3000)), 6000);
        assertThat(jdbc.sql("SELECT \"productId\" FROM \"InvoiceItem\" WHERE \"invoiceId\" = ?")
                .param(sold.get("invoiceId").asString()).query(String.class).single()).isEqualTo(tea);
    }

    @Test
    @DisplayName("public QR order: validated, then refused like Nest (checkout gets no register session)")
    void qrOrder() {
        String branchId = jdbc.sql("SELECT id FROM \"Branch\" WHERE \"organizationId\" = ? LIMIT 1").param(demoOrg)
                .query(String.class).single();
        String token = ok(201, "POST", "/pos/menu/sessions", Map.of("branchId", branchId, "tableNumber", "12"))
                .get("token").asString();
        Res empty = as("POST", "/menu/public/orders", Map.of("token", token, "customerName", "Rita", "lines",
                List.of(), "tenders", List.of()));
        assertThat(empty.body().get("message").asString()).isEqualTo("Cart is empty");
        Res shortPay = as("POST", "/menu/public/orders", Map.of("token", token, "customerName", "Rita", "lines",
                List.of(Map.of("productId", tea, "quantity", 1, "unitPrice", 3000, "description", "Tea")),
                "tenders", List.of(Map.of("method", "card", "amount", 100))));
        assertThat(shortPay.body().get("message").asString()).isEqualTo("Tenders do not cover the total");
        // Nest's placeOrder calls checkout without a cashSessionId, so a covered order always ends here (kept for
        // parity; listed for the owner in docs/audits/2026-11-01-pr1-parity-run.md).
        Res placed = as("POST", "/menu/public/orders", Map.of("token", token, "customerName", "Rita",
                "customerPhone", "+256700111", "orderType", "dine_in", "notes", "No sugar", "lines",
                List.of(Map.of("productId", tea, "quantity", 2, "unitPrice", 3000, "description", "Tea")),
                "tenders", List.of(Map.of("method", "cash_on_pickup", "amount", 6000))));
        assertThat(placed.status()).isEqualTo(400);
        assertThat(placed.body().get("message").asString())
                .isEqualTo("Select an open register before completing any POS sale");
    }

    @Test
    @DisplayName("tab: bill, more items, additional bill, void a fired line (cancel slip), KDS lifecycle")
    void tabAdditionalBillAndVoid() {
        String tableId = table();
        ok(201, "POST", "/pos/tabs/" + tableId + "/save", Map.of("cashSessionId", sessionId, "guestCount", 2,
                "newRound", true, "lines", List.of(line(tea, "Tea", 1, 3000))));
        String orderId = ok(200, "GET", "/pos/tabs/" + tableId, null).get("id").asString();
        ok(201, "POST", "/pos/receipts/" + orderId + "/print-bill", null);
        money(200, "POST", "/pos/tabs/" + tableId + "/items", Map.of("cashSessionId", sessionId, "lines",
                List.of(line(tea, "Tea", 2, 3000)), "sendToKitchen", true));
        JsonNode extra = ok(201, "POST", "/pos/receipts/" + orderId + "/print-additional-bill", null);
        assertThat(extra.toString()).isNotEmpty();

        ok(201, "POST", "/pos/tabs/" + tableId + "/fire-kitchen", null);
        String itemId = jdbc.sql("SELECT id FROM \"OrderItem\" WHERE \"orderId\" = ? AND cancelled = false ORDER BY"
                + " \"lineNumber\" DESC LIMIT 1").param(orderId).query(String.class).single();
        Res voided = keyed("DELETE", "/pos/orders/" + orderId + "/items/" + itemId, Map.of("reason", "Spilled",
                "quantity", 1, "overrideById", adminId, "overridePin", PIN));
        assertThat(voided.status()).as(voided.raw().body()).isIn(200, 201);
        Res kot = as("POST", "/pos/receipts/" + orderId + "/print-kot", null);
        assertThat(kot.status()).as(kot.raw().body()).isIn(200, 201);

        JsonNode ids = ok(201, "POST", "/pos/kds/send-to-kitchen", Map.of("label", "Flow2", "items",
                List.of(Map.of("productId", tea, "productName", "Tea", "quantity", 1))));
        if (!ids.isEmpty()) {
            String ticket = ids.get(0).asString();
            for (String action : List.of("start", "ready", "recall", "ready", "serve")) {
                Res moved = as("POST", "/pos/kds/tickets/" + ticket + "/transition", Map.of("action", action));
                assertThat(moved.status()).as(action + " → " + moved.raw().body()).isEqualTo(201);
            }
            assertThat(ok(200, "GET", "/pos/kds/tickets/" + ticket, null).get("status").asString())
                    .isEqualTo("served");
        }
        JsonNode paid = money(200, "POST", "/pos/tabs/" + tableId + "/settle", Map.of("cashSessionId", sessionId,
                "tenders", List.of(Map.of("method", "cash", "amount", 6000))));
        assertThat(paid.get("total").asInt()).isEqualTo(6000);
    }
}
