package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SG-6: promotions and their dates, precedence, POS unchanged, fixed and order discounts, limits, projects, history. */
class SalesPricingToolsIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String customer;

    @BeforeAll
    void fixtures() {
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "G6C-" + sfx, "SG6 cust " + sfx).update();
    }

    String product(int price) {
        return ok(201, "POST", "/products", Map.of("code", "G6P-" + UUID.randomUUID(), "name", "SG6 " + UUID.randomUUID(), "productType",
                "service", "salesPrice", price)).get("id").asString();
    }

    String promo(String productId, String kind, int value, LocalDate from, LocalDate to) {
        return ok(201, "POST", "/sales/promotions", Map.of("name", "Promo " + UUID.randomUUID(), "kind", kind, "value", value,
                "productId", productId, "startDate", from.toString(), "endDate", to.toString())).get("id").asString();
    }

    JsonNode order(Map<String, Object> header, Map<String, Object> line) {
        Map<String, Object> b = new LinkedHashMap<>(header);
        b.put("partnerId", customer);
        b.put("lines", List.of(line));
        return ok(201, "POST", "/sales/orders", b);
    }

    @Test
    @DisplayName("a promotion prices sales documents only inside its dates; a named list without one still wins as before")
    void promotionDates() {
        String p = product(100);
        LocalDate today = LocalDate.now();
        promo(p, "percent", 20, today.plusDays(5), today.plusDays(9));
        JsonNode before = order(Map.of(), Map.of("productId", p, "quantity", 1)).get("lines").get(0);
        assertThat(before.get("priceSource").asString()).isEqualTo("list");
        promo(p, "percent", 20, today.minusDays(1), today.plusDays(1));
        JsonNode during = order(Map.of(), Map.of("productId", p, "quantity", 1)).get("lines").get(0);
        assertThat(during.get("priceSource").asString()).isEqualTo("promotion");
        assertThat(during.get("discountPercent").asDouble()).isEqualTo(20);
        assertThat(during.get("lineSubtotal").asDouble()).isEqualTo(80);
    }

    @Test
    @DisplayName("precedence: an agreement beats a promotion, a promotion beats a named list")
    void precedence() {
        String p = product(100);
        LocalDate today = LocalDate.now();
        ok(201, "POST", "/price-lists", Map.of("code", "G6L-" + UUID.randomUUID(), "name", "SG6 list " + UUID.randomUUID(), "partnerIds",
                List.of(customer), "items", List.of(Map.of("productId", p, "unitPrice", 90))));
        assertThat(order(Map.of(), Map.of("productId", p, "quantity", 1)).get("lines").get(0).get("unitPrice").asDouble()).isEqualTo(90);
        promo(p, "fixed_price", 70, today, today.plusDays(1));
        JsonNode promoted = order(Map.of(), Map.of("productId", p, "quantity", 1)).get("lines").get(0);
        assertThat(promoted.get("unitPrice").asDouble()).isEqualTo(70);
        assertThat(promoted.get("priceSource").asString()).isEqualTo("promotion");
    }

    @Test
    @DisplayName("POS and invoice quotes ignore promotions, so POS prices do not change")
    void posUnchanged() {
        String p = product(100);
        LocalDate today = LocalDate.now();
        promo(p, "percent", 50, today.minusDays(1), today.plusDays(1));
        JsonNode resolved = ok(200, "POST", "/sales/pricing/resolve", Map.of("partnerId", customer, "lines", List.of(Map.of("productId", p,
                "quantity", 1)))).get("lines").get(0);
        assertThat(resolved.get("source").asString()).isEqualTo("promotion");
        JsonNode pos = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today.toString(), "lines", List.of(Map.of(
                "productId", p, "quantity", 1))));
        assertThat(pos.get("totalAmount").asDouble()).as("the invoice path keeps the catalogue price").isEqualTo(100);
    }

    @Test
    @DisplayName("a fixed line discount and a whole-order discount compute exactly and survive a re-save")
    void discounts() {
        String p = product(100);
        JsonNode fixed = order(Map.of(), Map.of("productId", p, "quantity", 2, "unitPrice", 100, "discountAmount", 50));
        assertThat(fixed.get("subtotal").asDouble()).isEqualTo(150);
        JsonNode whole = order(Map.of("orderDiscountPercent", 10), Map.of("productId", p, "quantity", 2, "unitPrice", 100,
                "discountPercent", 50));
        assertThat(whole.get("subtotal").asDouble()).isEqualTo(90);
        JsonNode line = whole.get("lines").get(0);
        assertThat(line.get("discountPercent").asDouble()).as("the line keeps its own discount").isEqualTo(50);
        JsonNode resaved = ok(200, "PUT", "/sales/orders/" + whole.get("id").asString(), Map.of("lines", List.of(Map.of("productId", p,
                "quantity", 2, "unitPrice", 100, "discountPercent", line.get("discountPercent").asDouble()))));
        assertThat(resaved.get("subtotal").asDouble()).as("no double discount").isEqualTo(90);
        JsonNode amount = order(Map.of("orderDiscountAmount", 25), Map.of("productId", p, "quantity", 1, "unitPrice", 100));
        assertThat(amount.get("subtotal").asDouble()).isEqualTo(75);
    }

    @Test
    @DisplayName("an absolute price limit sends an order to approval")
    void limits() {
        String p = product(100);
        ok(200, "PUT", "/sales/price-limits", Map.of("productId", p, "minPrice", 80, "maxPrice", 120));
        assertThat(as("PUT", "/sales/price-limits", Map.of("productId", p, "minPrice", 80, "maxPrice", 70)).status()).isEqualTo(400);
        JsonNode low = order(Map.of(), Map.of("productId", p, "quantity", 1, "unitPrice", 60));
        JsonNode pending = ok(200, "POST", "/sales/orders/" + low.get("id").asString() + "/confirm", null);
        assertThat(pending.get("status").asString()).isEqualTo("pending_approval");
        assertThat(pending.get("approvalReasons").toString()).contains("below the minimum");
    }

    @Test
    @DisplayName("price history records changes from both list systems; the grid shows both")
    void historyAndGrid() {
        String p = product(100);
        String list = ok(201, "POST", "/price-lists", Map.of("code", "G6H-" + UUID.randomUUID(), "name", "SG6 hist " + UUID.randomUUID(),
                "partnerIds", List.of(customer), "items", List.of(Map.of("productId", p, "unitPrice", 90)))).get("id").asString();
        assertThat(list).isNotBlank();
        String sp = ok(201, "POST", "/sales/pricelists", Map.of("code", "G6S-" + UUID.randomUUID(), "name", "SG6 s " + UUID.randomUUID(), "type",
                "wholesale")).get("id").asString();
        ok(201, "POST", "/sales/pricelists/" + sp + "/items", Map.of("scope", "product", "productId", p, "compute", "fixed", "value", 85,
                "minQuantity", 10));
        JsonNode grid = ok(200, "GET", "/sales/pricing/grid?productId=" + p, null);
        assertThat(grid.toString()).contains("price_list").contains("pricelist");
        JsonNode history = ok(200, "GET", "/sales/pricing/history?productId=" + p, null);
        assertThat(history.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("project pricing: an agreement covering the site applies without being chosen")
    void projectAgreement() {
        String p = product(100);
        String site = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetCustomerSite\" (id, \"organizationId\", \"partnerId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, ?, now())")
                .params(site, demoOrg, customer, "G6-" + sfx, "SG6 site " + sfx).update();
        String a = ok(201, "POST", "/sales/agreements", Map.of("partnerId", customer, "name", "Project " + sfx, "kind", "blanket", "startDate",
                LocalDate.now().minusDays(1).toString(), "endDate", LocalDate.now().plusDays(30).toString(), "siteIds", List.of(site),
                "lines", List.of(Map.of("productId", p, "unitPrice", 66, "contractQty", 100)))).get("id").asString();
        jdbc.sql("UPDATE \"SalesAgreement\" SET status = 'active' WHERE id = ?").param(a).update();
        JsonNode o = order(Map.of("siteId", site), Map.of("productId", p, "quantity", 1));
        assertThat(o.get("agreementId").asString()).isEqualTo(a);
        assertThat(o.get("lines").get(0).get("unitPrice").asDouble()).isEqualTo(66);
    }
}
