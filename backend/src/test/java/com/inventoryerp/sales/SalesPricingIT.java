package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Phase S2 of ADR-S011: pricelists and rules, the resolution tiers of the pricing engine (customer rule → CRM rule →
 * customer pricelist → category pricelist → default → standing discount → list), quantity breaks and delivery
 * zones, margin floors, commercial approval of quotations and sales orders, and the CRM resolve facade.
 */
class SalesPricingIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String category;
    String customer;
    String other;
    String aggregate;
    String sand;
    String repToken;

    @BeforeAll
    void fixtures() {
        category = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"PartnerCategory\" (id, \"organizationId\", name, \"updatedAt\") VALUES (?, ?, ?, now())")
                .params(category, demoOrg, "Contractors " + sfx).update();
        customer = partner("Builder " + sfx, category);
        other = partner("Walk-in " + sfx, null);
        aggregate = product("Agg " + sfx, 50000, 30000);
        sand = product("Sand " + sfx, 20000, 15000);
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Pricing rep " + sfx, new String[] {
                    "sales:order:read", "sales:order:write", "sales:order:confirm", "sales:quotation:read",
                    "sales:quotation:write", "sales:pricing:read"}).update();
        String email = "pr-rep-" + sfx.toLowerCase() + "@demo.test";
        createUser(demoOrg, email, "Sales@12345", role);
        repToken = login("DEMO", email, "Sales@12345");
    }

    String partner(String name, String cat) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\", \"categoryId\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, true, true, ?, now())")
                .params(id, demoOrg, "PR-" + UUID.randomUUID().toString().substring(0, 8), name, cat).update();
        return id;
    }

    String product(String name, int price, int cost) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service'::\"ProductType\", ?, ?,"
                + " false, now())").params(id, demoOrg, "PP-" + UUID.randomUUID().toString().substring(0, 8), name, price,
                cost).update();
        return id;
    }

    JsonNode price(String partnerId, String productId, int qty, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("lines", List.of(Map.of("productId", productId,
                "quantity", qty))));
        if (partnerId != null) {
            body.put("partnerId", partnerId);
        }
        body.putAll(extra);
        return ok(200, "POST", "/sales/pricing/resolve", body).get("lines").get(0);
    }

    JsonNode pricelist(String code, String type) {
        return ok(201, "POST", "/sales/pricelists", Map.of("code", code + sfx, "name", code + " " + sfx, "type", type));
    }

    void item(String pricelistId, Map<String, Object> body) {
        ok(201, "POST", "/sales/pricelists/" + pricelistId + "/items", body);
    }

    @Test
    @DisplayName("resolution tiers, quantity breaks and delivery zones")
    void tiers() {
        assertThat(price(customer, aggregate, 10, Map.of()).get("source").asString()).isEqualTo("list");
        // category pricelist: 5 % off everything
        String catList = pricelist("CAT", "contractor").get("id").asString();
        item(catList, Map.of("scope", "all", "compute", "discount", "value", 5));
        ok(200, "POST", "/sales/pricelists/" + catList + "/assign", Map.of("partnerCategoryIds", List.of(category)));
        JsonNode p = price(customer, aggregate, 10, Map.of());
        assertThat(p.get("source").asString()).isEqualTo("pricelist");
        assertThat(p.get("net").asDouble()).isEqualTo(47500);
        assertThat(price(other, aggregate, 10, Map.of()).get("source").asString()).isEqualTo("list");
        // customer pricelist with a quantity break and a zone price beats the category pricelist
        String custList = pricelist("CUST", "contract").get("id").asString();
        item(custList, Map.of("scope", "product", "productId", aggregate, "compute", "fixed", "value", 46000));
        item(custList, Map.of("scope", "product", "productId", aggregate, "compute", "fixed", "value", 44000,
                "minQuantity", 500));
        item(custList, Map.of("scope", "product", "productId", aggregate, "compute", "fixed", "value", 48000,
                "siteZone", "Far " + sfx, "priority", 10));
        ok(200, "POST", "/sales/pricelists/" + custList + "/assign", Map.of("partnerIds", List.of(customer)));
        assertThat(price(customer, aggregate, 10, Map.of()).get("net").asDouble()).isEqualTo(46000);
        assertThat(price(customer, aggregate, 600, Map.of()).get("net").asDouble()).isEqualTo(44000);
        String site = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetCustomerSite\" (id, \"organizationId\", code, \"partnerId\", name, zone, \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, ?, ?, now())").params(site, demoOrg, "S-" + sfx, customer, "Far site", "Far " + sfx)
                .update();
        assertThat(price(customer, aggregate, 10, Map.of("siteId", site)).get("net").asDouble()).isEqualTo(48000);
        // sand is not in the customer pricelist → category pricelist 5 %
        assertThat(price(customer, sand, 1, Map.of()).get("net").asDouble()).isEqualTo(19000);
        // a customer-bound rule in any pricelist wins over the customer's pricelist
        String promo = pricelist("PROMO", "other").get("id").asString();
        item(promo, Map.of("scope", "product", "productId", aggregate, "partnerId", customer, "compute", "fixed",
                "value", 45000));
        assertThat(price(customer, aggregate, 10, Map.of()).get("net").asDouble()).isEqualTo(45000);
        // the pricelist shows its customers and categories
        JsonNode cl = ok(200, "GET", "/sales/pricelists/" + custList, null);
        assertThat(cl.get("customers").get(0).get("id").asString()).isEqualTo(customer);
        assertThat(cl.get("items").size()).isEqualTo(3);
        // unassign and delete fall back
        ok(200, "DELETE", "/sales/pricelists/" + promo, null);
        ok(200, "POST", "/sales/pricelists/" + custList + "/assign", Map.of("partnerIds", List.of(customer), "unassign",
                true));
        assertThat(price(customer, aggregate, 10, Map.of()).get("net").asDouble()).isEqualTo(47500);
        ok(200, "DELETE", "/sales/pricelists/" + catList, null);
        assertThat(price(customer, aggregate, 10, Map.of()).get("source").asString()).isEqualTo("list");
    }

    @Test
    @DisplayName("validation: duplicate code, discount over 100 %, product rule without product")
    void validation() {
        String id = pricelist("VAL", "retail").get("id").asString();
        assertThat(as("POST", "/sales/pricelists", Map.of("code", "VAL" + sfx, "name", "dup")).status()).isEqualTo(409);
        assertThat(as("POST", "/sales/pricelists/" + id + "/items", Map.of("scope", "all", "compute", "discount", "value",
                150)).status()).isEqualTo(400);
        assertThat(as("POST", "/sales/pricelists/" + id + "/items", Map.of("scope", "product", "compute", "fixed",
                "value", 1)).status()).isEqualTo(400);
        assertThat(as("POST", "/sales/margin-floors", Map.of("minMarginPercent", 100)).status()).isEqualTo(400);
        ok(200, "DELETE", "/sales/pricelists/" + id, null);
    }

    @Test
    @DisplayName("margin floor and discount limit send quotations and orders to approval; approve / reject")
    void approvals() {
        String floor = ok(201, "POST", "/sales/margin-floors", Map.of("productId", sand, "minMarginPercent", 20))
                .get("id").asString();
        try {
            // sand cost 15 000, floor 20 % → min price 18 750; quote at 17 000 needs approval
            JsonNode q = ok(201, "POST", "/sales/quotations", Map.of("partnerId", other, "lines", List.of(Map.of(
                    "productId", sand, "quantity", 10, "unitPrice", 17000))));
            JsonNode submitted = ok(200, "POST", "/sales/quotations/" + q.get("id").asString() + "/submit", null);
            assertThat(submitted.get("status").asString()).isEqualTo("pending_approval");
            assertThat(submitted.get("approvalReasons").toString()).contains("margin floor");
            JsonNode r = price(other, sand, 1, Map.of());
            assertThat(r.get("floorPrice").asDouble()).isEqualTo(18750);
            assertThat(r.get("marginPercent").asDouble()).isEqualTo(25);

            // order by a rep with a 30 % discount → pending approval; rep cannot approve
            JsonNode o = call("POST", "/sales/orders", Map.of("partnerId", other, "lines", List.of(Map.of("productId",
                    aggregate, "quantity", 5, "discountPercent", 30))), repToken).body();
            String oid = o.get("id").asString();
            JsonNode pending = call("POST", "/sales/orders/" + oid + "/confirm", null, repToken).body();
            assertThat(pending.get("status").asString()).isEqualTo("pending_approval");
            assertThat(pending.get("approvalReasons").get(0).asString()).contains("Discount 30");
            assertThat(call("POST", "/sales/orders/" + oid + "/approve", Map.of(), repToken).status()).isEqualTo(403);
            JsonNode rejected = ok(200, "POST", "/sales/orders/" + oid + "/reject", Map.of("reason", "Too cheap"));
            assertThat(rejected.get("status").asString()).isEqualTo("draft");
            assertThat(rejected.get("approvalReasons").get(0).asString()).contains("Too cheap");
            call("POST", "/sales/orders/" + oid + "/confirm", null, repToken);
            JsonNode approved = ok(200, "POST", "/sales/orders/" + oid + "/approve", Map.of("comment", "Strategic"));
            assertThat(approved.get("status").asString()).isEqualTo("confirmed");
            // within policy: confirmed at once
            JsonNode plain = call("POST", "/sales/orders", Map.of("partnerId", other, "lines", List.of(Map.of(
                    "productId", aggregate, "quantity", 5))), repToken).body();
            assertThat(call("POST", "/sales/orders/" + plain.get("id").asString() + "/confirm", null, repToken).body()
                    .get("status").asString()).isEqualTo("confirmed");
        } finally {
            ok(200, "DELETE", "/sales/margin-floors/" + floor, null);
        }
    }

    @Test
    @DisplayName("CRM price rules stay a tier; /crm/pricing/resolve answers from the sales engine")
    void crmFacade() {
        JsonNode rule = ok(201, "POST", "/crm/pricing/rules", Map.of("name", "Legacy " + sfx, "partnerId", other,
                "productId", sand, "kind", "fixed_price", "value", 18000));
        JsonNode r = ok(200, "POST", "/crm/pricing/resolve", Map.of("partnerId", other, "lines", List.of(Map.of(
                "productId", sand, "quantity", 1)))).get("lines").get(0);
        assertThat(r.get("source").asString()).isEqualTo("rule");
        assertThat(r.get("netPrice").asDouble()).isEqualTo(18000);
        assertThat(r.get("ruleId").asString()).isEqualTo(rule.get("id").asString());
        ok(200, "DELETE", "/crm/pricing/rules/" + rule.get("id").asString(), null);
    }
}
