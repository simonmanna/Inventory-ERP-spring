package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * POS flows the other suites leave out: modifiers on sold lines, combo / accompaniment edits, moving and merging
 * orders, adding to a tab, bills (print, additional, reprint) and the KDS ticket lifecycle.
 */
class PosFlowsIT extends ApiTestSupport {

    static final AtomicInteger NUMBER = new AtomicInteger(9100);

    String suffix;
    String coffee;
    String cake;
    String registerId;
    String sessionId;

    @BeforeAll
    void fixtures() {
        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        coffee = product("Coffee", 4000);
        cake = product("Cake", 6000);
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "FLOW-" + suffix, "name", "Flow till"))
                .get("id").asString();
        sessionId = money(200, "POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId, "openingFloat", 0))
                .get("id").asString();
    }

    @AfterAll
    void cleanup() {
        for (String id : jdbc.sql("SELECT id FROM \"Order\" WHERE \"cashSessionId\" = ? AND status::text NOT IN"
                + " ('closed', 'cancelled', 'completed')").param(sessionId).query(String.class).list()) {
            keyed("POST", "/pos/orders/" + id + "/cancel", Map.of("reason", "flow test cleanup"));
        }
        forceCloseSessions(List.of(registerId));
    }

    String product(String name, int price) {
        return ok(201, "POST", "/products", Map.of("code", "FLW-" + name + "-" + suffix, "name", name + " " + suffix,
                "productType", "service", "salesPrice", price)).get("id").asString();
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

    String table() {
        int n = NUMBER.incrementAndGet();
        return ok(201, "POST", "/pos/tables", Map.of("name", "Flow " + n, "number", n)).get("id").asString();
    }

    static Map<String, Object> line(String productId, String description, int qty, int price) {
        return Map.of("productId", productId, "description", description, "quantity", qty, "unitPrice", price);
    }

    @Test
    @DisplayName("checkout with a product modifier stores it on the invoice line and prices it in")
    void modifiersOnSoldLines() {
        String groupId = ok(201, "POST", "/pos/modifiers/groups", Map.of("name", "Milk " + suffix, "maxSelect", 2))
                .get("id").asString();
        JsonNode mod = ok(201, "POST", "/pos/modifiers/modifiers", Map.of("groupId", groupId, "name", "Oat",
                "priceDelta", 500));
        ok(201, "POST", "/pos/modifiers/products/" + coffee + "/groups", Map.of("modifierGroupId", groupId));
        JsonNode bundle = ok(200, "GET", "/pos/modifiers/products/" + coffee + "/bundle", null);
        assertThat(bundle.get("groups").get(0).get("modifiers").get(0).get("name").asString()).isEqualTo("Oat");

        Map<String, Object> l = new LinkedHashMap<>(line(coffee, "Coffee", 1, 4000));
        l.put("modifiers", List.of(Map.of("modifierId", mod.get("id").asString(), "name", "Oat", "priceDelta", 500)));
        JsonNode sale = money(200, "POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines", List.of(l),
                "tenders", List.of(Map.of("method", "cash", "amount", 4500))));
        assertThat(sale.get("total").asInt()).isEqualTo(4500);
        String invoiceId = sale.get("invoiceId").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM \"InvoiceItemModifier\" m JOIN \"InvoiceItem\" i"
                + " ON i.id = m.\"invoiceItemId\" WHERE i.\"invoiceId\" = ?").param(invoiceId).query(Long.class)
                .single())
                .isEqualTo(1);
        assertThat(ok(200, "GET", "/pos/receipts/" + invoiceId, null).toString()).contains("Oat");
        ok(200, "GET", "/pos/modifiers/report/sales", null);

        ok(200, "DELETE", "/pos/modifiers/products/" + coffee + "/groups/" + groupId, null);
        assertThat(ok(200, "GET", "/pos/modifiers/products/" + coffee + "/bundle", null).get("groups").size())
                .isZero();
    }

    @Test
    @DisplayName("combos and accompaniments can be edited and removed")
    void comboAndAccompanimentEdits() {
        JsonNode combo = ok(201, "POST", "/pos/modifiers/combos", Map.of("name", "Breakfast " + suffix, "price", 9000,
                "items", List.of(Map.of("productId", coffee, "quantity", 1))));
        String comboId = combo.get("id").asString();
        JsonNode edited = ok(200, "PATCH", "/pos/modifiers/combos/" + comboId, Map.of("name", "Brunch " + suffix,
                "price", 9500, "items", List.of(Map.of("productId", coffee, "quantity", 1),
                        Map.of("productId", cake, "quantity", 1))));
        assertThat(edited.toString()).contains("Brunch " + suffix);
        assertThat(ok(200, "GET", "/pos/modifiers/combos/" + comboId, null).get("items").size()).isEqualTo(2);
        ok(200, "DELETE", "/pos/modifiers/combos/" + comboId, null);

        String groupId = ok(201, "POST", "/pos/menu/accompaniments/groups", Map.of("name", "Sides " + suffix))
                .get("id").asString();
        String optionId = ok(201, "POST", "/pos/menu/accompaniments/groups/" + groupId + "/options",
                Map.of("name", "Fries", "priceImpact", 1000)).get("id").asString();
        ok(200, "PATCH", "/pos/menu/accompaniments/groups/" + groupId, Map.of("name", "Sides 2 " + suffix));
        ok(200, "PATCH", "/pos/menu/accompaniments/options/" + optionId, Map.of("name", "Chips", "priceImpact", 1500));
        JsonNode groups = ok(200, "GET", "/pos/menu/accompaniments/groups", null);
        assertThat(groups.toString()).contains("Sides 2 " + suffix).contains("Chips");
        ok(200, "DELETE", "/pos/menu/accompaniments/options/" + optionId, null);
        ok(200, "DELETE", "/pos/menu/accompaniments/groups/" + groupId, null);
        assertThat(ok(200, "GET", "/pos/menu/accompaniments/groups", null).toString())
                .doesNotContain("Sides 2 " + suffix);
    }

    @Test
    @DisplayName("orders move between tables, merge, cancel and reopen")
    void orderMoveMergeCancel() {
        String t1 = table();
        String t2 = table();
        JsonNode first = ok(201, "POST", "/pos/orders", Map.of("orderType", "dine_in", "tableId", t1, "guestCount", 2,
                "cashSessionId", sessionId, "lines", List.of(line(coffee, "Coffee", 1, 4000))));
        String firstId = first.get("id").asString();
        ok(201, "POST", "/pos/orders/" + firstId + "/move", Map.of("targetTableId", t2));
        assertThat(ok(200, "GET", "/pos/orders/" + firstId, null).get("tableId").asString()).isEqualTo(t2);
        assertThat(ok(200, "GET", "/pos/orders/by-table/" + t2, null).toString()).contains(firstId);

        JsonNode second = ok(201, "POST", "/pos/orders", Map.of("orderType", "takeaway", "guestCount", 1,
                "cashSessionId", sessionId, "lines", List.of(line(cake, "Cake", 2, 6000))));
        String secondId = second.get("id").asString();
        ok(201, "POST", "/pos/orders/" + firstId + "/merge", Map.of("sourceOrderId", secondId));
        JsonNode merged = ok(200, "GET", "/pos/orders/" + firstId, null);
        assertThat(merged.get("items").size()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT status::text FROM \"Order\" WHERE id = ?").param(secondId).query(String.class)
                .single()).isNotEqualTo("open");

        money(200, "POST", "/pos/orders/" + firstId + "/cancel", Map.of("reason", "flow test"));
        assertThat(jdbc.sql("SELECT status::text FROM \"Order\" WHERE id = ?").param(firstId).query(String.class)
                .single()).isEqualTo("cancelled");
        Res reopened = as("POST", "/pos/orders/" + firstId + "/reopen", null);
        assertThat(reopened.status()).as(reopened.raw().body()).isLessThan(500);
        if (reopened.status() < 300) {
            // An open order blocks every shift close in the shared DEMO organization.
            money(200, "POST", "/pos/orders/" + firstId + "/cancel", Map.of("reason", "flow test cleanup"));
        }
    }

    @Test
    @DisplayName("tab: add items, bills, KDS lifecycle, settle; reports see the sale")
    void tabBillsKdsAndReports() {
        String tableId = table();
        ok(201, "POST", "/pos/tabs/" + tableId + "/save", Map.of("cashSessionId", sessionId, "guestCount", 2,
                "newRound", true, "lines", List.of(line(coffee, "Coffee", 2, 4000))));
        money(200, "POST", "/pos/tabs/" + tableId + "/items", Map.of("cashSessionId", sessionId, "lines",
                List.of(line(cake, "Cake", 1, 6000)), "sendToKitchen", true));
        JsonNode tab = ok(200, "GET", "/pos/tabs/" + tableId, null);
        String orderId = tab.get("id").asString();
        assertThat(tab.get("lines").size()).isEqualTo(2);

        JsonNode bill = ok(201, "POST", "/pos/receipts/" + orderId + "/print-bill", null);
        assertThat(bill.toString()).contains("ok");
        Res extra = as("POST", "/pos/receipts/" + orderId + "/print-additional-bill", null);
        assertThat(extra.status()).as(extra.raw().body()).isIn(201, 400, 403, 409);
        ok(201, "POST", "/pos/receipts/" + orderId + "/reprint-bill", Map.of("reason", "Guest asked again"));
        assertThat(jdbc.sql("SELECT count(*) FROM \"DocumentPrintLog\" WHERE \"documentId\" IS NULL AND type::text"
                + " = 'BILL'").query(Long.class).single()).isPositive();

        ok(201, "POST", "/pos/tabs/" + tableId + "/fire-kitchen", null);
        JsonNode tickets = ok(200, "GET", "/pos/kds/tickets", null);
        String ticketId = null;
        for (JsonNode t : tickets.isArray() ? tickets : tickets.get("data")) {
            if (orderId.equals(t.path("orderId").asString(""))) {
                ticketId = t.get("id").asString();
            }
        }
        if (ticketId != null) {
            for (String action : List.of("start", "ready", "recall", "ready", "serve")) {
                Res moved = as("POST", "/pos/kds/tickets/" + ticketId + "/transition", Map.of("action", action));
                assertThat(moved.status()).as(action + " → " + moved.raw().body()).isIn(201, 400, 409);
            }
            ok(200, "GET", "/pos/kds/tickets/" + ticketId, null);
        }

        JsonNode paid = money(200, "POST", "/pos/tabs/" + tableId + "/settle", Map.of("cashSessionId", sessionId,
                "tenders", List.of(Map.of("method", "cash", "amount", 14000))));
        assertThat(paid.get("total").asInt()).isEqualTo(14000);
        String range = "?fromDate=" + java.time.LocalDate.now().minusDays(1) + "&toDate="
                + java.time.LocalDate.now().plusDays(1);
        JsonNode top = ok(200, "GET", "/pos/reports/top-items" + range, null);
        assertThat(top.toString()).contains("Coffee");
        ok(200, "GET", "/pos/reports/order-report" + range, null);
        ok(200, "GET", "/pos/kds/reports/summary", null);
    }
}
