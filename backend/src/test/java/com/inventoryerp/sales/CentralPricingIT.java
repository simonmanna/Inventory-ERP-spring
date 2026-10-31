package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** ADR-S022: one sales pricing engine for quotations, sales orders, POS orders, invoices and delivery orders. */
class CentralPricingIT extends ApiTestSupport {

    String product;

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeAll
    void fixtures() {
        product = ok(201, "POST", "/products", Map.of("code", "CPR-" + sfx(), "name", "Central priced " + sfx(),
                "productType", "service", "salesPrice", 100, "costPrice", 0)).get("id").asString();
    }

    @AfterEach
    void defaultPrecedence() {
        jdbc.sql("DELETE FROM \"PricingPolicy\" WHERE \"organizationId\" = ?").param(demoOrg).update();
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "CPC-" + sfx(), "Priced cust " + sfx()).update();
        return id;
    }

    String namedList(String customer, int price) {
        return ok(201, "POST", "/price-lists", Map.of("code", "CPL-" + sfx(), "name", "Contract " + sfx(), "partnerIds",
                List.of(customer), "items", List.of(Map.of("productId", product, "unitPrice", price)))).get("id")
                .asString();
    }

    void crmRule(String customer, int price) {
        jdbc.sql("INSERT INTO \"CrmPriceRule\" (id, \"organizationId\", name, \"partnerId\", \"productId\", kind, value,"
                + " \"minQuantity\") VALUES (?, ?, ?, ?, ?, 'fixed_price', ?, 0)").params(UUID.randomUUID().toString(),
                demoOrg, "Rule " + sfx(), customer, product, price).update();
    }

    JsonNode engine(String customer) {
        return ok(200, "POST", "/sales/pricing/resolve", Map.of("partnerId", customer, "lines", List.of(Map.of(
                "productId", product, "quantity", 2)))).get("lines").get(0);
    }

    BigDecimal invoicePrice(String customer) {
        JsonNode inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", LocalDate.now().toString(),
                "lines", List.of(Map.of("productId", product, "quantity", 2))));
        return new BigDecimal(inv.get("lines").get(0).get("unitPrice").asString());
    }

    BigDecimal orderPrice(String customer) {
        JsonNode o = ok(200, "POST", "/orders", Map.of("partnerId", customer, "paymentMethod", "cash", "lines",
                List.of(Map.of("productId", product, "quantity", 2))));
        BigDecimal p = new BigDecimal(o.get("items").get(0).get("unitPrice").asString());
        jdbc.sql("UPDATE \"Order\" SET status = 'cancelled', \"cancelledAt\" = now() WHERE id = ?")
                .param(o.get("id").asString()).update();
        return p;
    }

    @Test
    @DisplayName("every channel gets the same price from the same tiers")
    void oneEngine() {
        String c = customer();
        namedList(c, 70);
        JsonNode sales = engine(c);
        assertThat(sales.get("source").asString()).isEqualTo("price_list");
        assertThat(new BigDecimal(sales.get("unitPrice").asString())).isEqualByComparingTo("70");
        assertThat(invoicePrice(c)).isEqualByComparingTo("70");
        assertThat(orderPrice(c)).isEqualByComparingTo("70");

        // A CRM customer rule outranks named lists in the default precedence — in every channel.
        crmRule(c, 60);
        assertThat(new BigDecimal(engine(c).get("unitPrice").asString())).isEqualByComparingTo("60");
        assertThat(invoicePrice(c)).isEqualByComparingTo("60");
        assertThat(orderPrice(c)).isEqualByComparingTo("60");
    }

    @Test
    @DisplayName("precedence is configurable and deterministic")
    void precedence() {
        String c = customer();
        namedList(c, 70);
        crmRule(c, 60);
        JsonNode policy = ok(200, "GET", "/sales/pricing/policy", null);
        assertThat(policy.get("precedence").get(0).asString()).isEqualTo("agreement");
        ok(200, "PUT", "/sales/pricing/policy", Map.of("precedence", List.of("agreement", "price_list", "crm_rule",
                "customer_rule", "customer_pricelist", "category_pricelist", "branch_default", "org_default",
                "standing_discount")));
        assertThat(new BigDecimal(engine(c).get("unitPrice").asString())).isEqualByComparingTo("70");
        assertThat(engine(c).toString()).isEqualTo(engine(c).toString());
        assertThat(as("PUT", "/sales/pricing/policy", Map.of("precedence", List.of("crm_rule", "crm_rule"))).status())
                .isEqualTo(400);
        assertThat(as("PUT", "/sales/pricing/policy", Map.of("precedence", List.of("magic"))).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("the result carries the breakdown: base, discount, net, tax and final price")
    void breakdown() {
        String c = customer();
        JsonNode b = engine(c).get("breakdown");
        assertThat(b.get("source").asString()).isEqualTo("list");
        assertThat(new BigDecimal(b.get("basePrice").asString())).isEqualByComparingTo("100");
        assertThat(new BigDecimal(b.get("netPrice").asString())).isEqualByComparingTo("100");
        assertThat(new BigDecimal(b.get("taxAmount").asString())).isEqualByComparingTo("0");
        assertThat(new BigDecimal(b.get("finalPrice").asString())).isEqualByComparingTo("100");
        assertThat(new BigDecimal(b.get("lineTotal").asString())).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("posting snapshots the price; later list changes never touch the invoice or its snapshot")
    void immutableSnapshots() {
        String c = customer();
        String list = namedList(c, 70);
        JsonNode inv = ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", LocalDate.now().toString(),
                "lines", List.of(Map.of("productId", product, "quantity", 2))));
        String id = inv.get("id").asString();
        ok(200, "POST", "/invoices/" + id + "/post", null);
        Map<String, Object> snap = jdbc.sql("SELECT \"unitPrice\", breakdown::text AS b, \"manualPrice\" FROM"
                + " \"PriceSnapshot\" WHERE \"documentType\" = 'invoice' AND \"documentId\" = ?").param(id).query()
                .singleRow();
        assertThat((BigDecimal) snap.get("unitPrice")).isEqualByComparingTo("70");
        assertThat((String) snap.get("b")).contains("\"source\": \"price_list\"");
        assertThat(snap.get("manualPrice")).isEqualTo(false);

        ok(200, "PUT", "/price-lists/" + list, Map.of("code", "CPL-" + sfx(), "name", "Raised", "partnerIds",
                List.of(c), "items", List.of(Map.of("productId", product, "unitPrice", 90))));
        JsonNode again = ok(200, "GET", "/invoices/" + id, null);
        assertThat(new BigDecimal(again.get("lines").get(0).get("unitPrice").asString())).isEqualByComparingTo("70");
        assertThat(jdbc.sql("SELECT \"unitPrice\" FROM \"PriceSnapshot\" WHERE \"documentId\" = ?").param(id)
                .query(BigDecimal.class).single()).isEqualByComparingTo("70");
        assertThatThrownBy(() -> jdbc.sql("UPDATE \"PriceSnapshot\" SET \"unitPrice\" = 1 WHERE \"documentId\" = ?")
                .param(id).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM \"PriceSnapshot\" WHERE \"documentId\" = ?").param(id).update())
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("a typed price is kept and flagged as manual in the snapshot")
    void manualPriceKept() {
        String c = customer();
        JsonNode inv = ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", LocalDate.now().toString(),
                "lines", List.of(Map.of("productId", product, "quantity", 1, "unitPrice", 95))));
        String id = inv.get("id").asString();
        ok(200, "POST", "/invoices/" + id + "/post", null);
        assertThat(jdbc.sql("SELECT \"manualPrice\" FROM \"PriceSnapshot\" WHERE \"documentId\" = ?").param(id)
                .query(Boolean.class).single()).isTrue();
    }
}
