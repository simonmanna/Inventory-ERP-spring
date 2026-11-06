package com.inventoryerp.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.core.web.ProductVariantController;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Product attributes &amp; variants (ADR-S058): generation, prices, and variants on sales, purchases and stock. */
class ProductVariantIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String color;
    String white;
    String black;
    String size;
    String small;
    String large;
    String warehouse;
    String adminId;

    @BeforeAll
    void fixtures() {
        JsonNode c = ok(201, "POST", "/variant-attributes", Map.of("name", "Color " + sfx, "displayType", "color",
                "values", List.of(Map.of("name", "White", "htmlColor", "#FFFFFF"), Map.of("name", "Black", "htmlColor",
                        "#111111"))));
        color = c.get("id").asString();
        white = c.get("values").get(0).get("id").asString();
        black = c.get("values").get(1).get("id").asString();
        JsonNode s = ok(201, "POST", "/variant-attributes", Map.of("name", "Size " + sfx, "values", List.of(Map.of(
                "name", "Small"), Map.of("name", "Large"))));
        size = s.get("id").asString();
        small = s.get("values").get(0).get("id").asString();
        large = s.get("values").get(1).get("id").asString();
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "PV-" + sfx, "name", "Variant yard " + sfx,
                "type", "warehouse")).get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
    }

    String product(int price, int cost) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true,"
                + " 'AVCO', ?, ?, now())").params(id, demoOrg, "PV-" + UUID.randomUUID().toString().substring(0, 8),
                        "Screen " + sfx, cost, price).update();
        return id;
    }

    String partner(boolean customer) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isSupplier\","
                + " \"isCompany\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, true, now())").params(id, demoOrg, "PV-"
                        + UUID.randomUUID().toString().substring(0, 8), "Partner " + sfx, customer, !customer).update();
        return id;
    }

    JsonNode configure(String product) {
        return ok(200, "PUT", "/products/" + product + "/attribute-lines", Map.of("lines", List.of(Map.of("attributeId",
                color, "valueIds", List.of(white, black)), Map.of("attributeId", size, "valueIds", List.of(small,
                        large)))));
    }

    static JsonNode byName(JsonNode variants, String name) {
        for (JsonNode v : variants) {
            if (v.get("name").asString().equals(name)) {
                return v;
            }
        }
        throw new AssertionError("no variant " + name + " in " + variants);
    }

    @Test
    @DisplayName("attribute lines generate every combination; dropped ones are archived and come back when re-added")
    void generation() {
        String p = product(100, 40);
        JsonNode cfg = configure(p);
        assertThat(cfg.get("hasVariants").asBoolean()).isTrue();
        assertThat(cfg.get("lines")).hasSize(2);
        JsonNode variants = cfg.get("variants");
        assertThat(variants).hasSize(4);
        assertThat(byName(variants, "White, Small").get("isActive").asBoolean()).isTrue();
        String blackLarge = byName(variants, "Black, Large").get("id").asString();

        // Drop Black: its two variants are archived, never deleted.
        JsonNode less = ok(200, "PUT", "/products/" + p + "/attribute-lines", Map.of("lines", List.of(Map.of(
                "attributeId", color, "valueIds", List.of(white)), Map.of("attributeId", size, "valueIds", List.of(small,
                        large)))));
        assertThat(less.get("variants")).hasSize(4);
        assertThat(byName(less.get("variants"), "Black, Large").get("isActive").asBoolean()).isFalse();
        assertThat(ok(200, "GET", "/products/" + p + "/variants?activeOnly=true", null)).hasSize(2);

        // Re-adding Black re-activates the same variant row.
        JsonNode back = configure(p);
        assertThat(byName(back.get("variants"), "Black, Large").get("id").asString()).isEqualTo(blackLarge);
        assertThat(byName(back.get("variants"), "Black, Large").get("isActive").asBoolean()).isTrue();

        // A value in use cannot be removed from the attribute; an attribute in use cannot be deleted.
        assertThat(as("DELETE", "/variant-attributes/" + color, null).status()).isEqualTo(400);
        assertThat(as("PATCH", "/variant-attributes/" + size, Map.of("values", List.of(Map.of("id", small, "name",
                "Small")))).status()).isEqualTo(400);

        // No lines → no variants on the product.
        JsonNode none = ok(200, "PUT", "/products/" + p + "/attribute-lines", Map.of("lines", List.of()));
        assertThat(none.get("hasVariants").asBoolean()).isFalse();
        assertThat(ok(200, "GET", "/products/" + p + "/variants?activeOnly=true", null)).isEmpty();
    }

    @Test
    @DisplayName("price = product price + value extras, unless the variant has a fixed price; renames follow values")
    void pricing() {
        String p = product(100, 40);
        JsonNode cfg = configure(p);
        String colorLine = cfg.get("lines").get(0).get("id").asString();
        JsonNode priced = ok(200, "PATCH", "/products/" + p + "/attribute-lines/" + colorLine + "/values", Map.of(
                "values", List.of(Map.of("valueId", black, "priceExtra", 25))));
        assertThat(byName(priced.get("variants"), "Black, Small").get("salesPrice").asDouble()).isEqualTo(125);
        assertThat(byName(priced.get("variants"), "White, Small").get("salesPrice").asDouble()).isEqualTo(100);
        String whiteLarge = byName(priced.get("variants"), "White, Large").get("id").asString();
        JsonNode fixed = ok(200, "PATCH", "/products/" + p + "/variants/" + whiteLarge, Map.of("salesPrice", 180,
                "costPrice", 70, "sku", "SCR-WL-" + sfx));
        assertThat(fixed.get("salesPrice").asDouble()).isEqualTo(180);
        assertThat(fixed.get("costPrice").asDouble()).isEqualTo(70);
        assertThat(fixed.get("sku").asString()).isEqualTo("SCR-WL-" + sfx);

        JsonNode search = ok(200, "GET", "/product-variants?productId=" + p, null);
        assertThat(search).hasSize(4);
        assertThat(search.toString()).contains("(White, Large)");

        JsonNode res = ok(200, "POST", "/sales/pricing/resolve", Map.of("lines", List.of(Map.of("productId", p,
                "variantId", byName(priced.get("variants"), "Black, Large").get("id").asString(), "quantity", 1))));
        assertThat(res.toString()).contains("125");
    }

    @Test
    @DisplayName("sales order: variant required, priced per variant; purchase receipt stocks the variant at its cost")
    void documents() {
        String p = product(100, 40);
        JsonNode cfg = configure(p);
        String colorLine = cfg.get("lines").get(0).get("id").asString();
        ok(200, "PATCH", "/products/" + p + "/attribute-lines/" + colorLine + "/values", Map.of("values", List.of(Map.of(
                "valueId", black, "priceExtra", 25))));
        JsonNode variants = ok(200, "GET", "/products/" + p + "/variants", null);
        String blackSmall = byName(variants, "Black, Small").get("id").asString();
        String whiteSmall = byName(variants, "White, Small").get("id").asString();

        String customer = partner(true);
        Res missing = as("POST", "/sales/orders", Map.of("partnerId", customer, "lines", List.of(Map.of("productId", p,
                "quantity", 2))));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.raw().body()).contains("Choose a variant for");
        assertThat(as("POST", "/sales/orders", Map.of("partnerId", customer, "lines", List.of(Map.of("productId", p,
                "variantId", UUID.randomUUID().toString(), "quantity", 2)))).status()).isEqualTo(400);
        JsonNode so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "lines", List.of(Map.of("productId",
                p, "variantId", blackSmall, "quantity", 2))));
        JsonNode line = so.get("lines").get(0);
        assertThat(line.get("variantId").asString()).isEqualTo(blackSmall);
        assertThat(line.get("variantName").asString()).isEqualTo("Black, Small");
        assertThat(line.get("unitPrice").asDouble()).isEqualTo(125);
        assertThat(line.get("description").asString()).contains("Black, Small");

        // Purchase order + receipt: stock lands on the variant, valued at the receipt cost.
        String supplier = partner(false);
        Res noVariant = as("POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "paymentType", "credit", "lines", List.of(Map.of("productId", p, "description", "Screen",
                        "quantity", 5, "unitPrice", 30))));
        assertThat(noVariant.status()).isEqualTo(400);
        JsonNode po = ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "paymentType", "credit", "lines", List.of(Map.of("productId", p, "description", "Screen",
                        "quantity", 5, "unitPrice", 30, "variantId", whiteSmall))));
        assertThat(po.get("lines").get(0).get("variantId").asString()).isEqualTo(whiteSmall);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res rec = call("POST", "/procurement/purchase-orders/" + po.get("id").asString() + "/receive", Map.of(
                "warehouseId", warehouse, "lines", List.of(Map.of("purchaseOrderLineId", po.get("lines").get(0).get("id")
                        .asString(), "productId", p, "description", "Screen", "quantity", 5))), h);
        assertThat(rec.status()).as(rec.raw().body()).isIn(200, 201);
        BigDecimal onHand = jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockItem\" WHERE \"variantId\" = ?")
                .param(whiteSmall).query(BigDecimal.class).single();
        assertThat(onHand).isEqualByComparingTo("5");
        BigDecimal productLevel = jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ?"
                + " AND \"variantId\" IS NULL").param(p).query(BigDecimal.class).single();
        assertThat(productLevel).isEqualByComparingTo("0");
        JsonNode after = byName(ok(200, "GET", "/products/" + p + "/variants", null), "White, Small");
        assertThat(after.get("onHand").asDouble()).isEqualTo(5);
        assertThat(after.get("stockValue").asDouble()).as(after.toString()).isEqualTo(150);
    }

    @Test
    @DisplayName("stock moves need a variant; SKU is unique; an archived variant cannot be used on new lines")
    void stockGuardsAndArchive() {
        String p = product(100, 40);
        JsonNode variants = configure(p).get("variants");
        String whiteSmall = byName(variants, "White, Small").get("id").asString();
        String blackSmall = byName(variants, "Black, Small").get("id").asString();

        // Counting stock of a variant product without naming the variant is refused at approval.
        JsonNode noVariant = ok(200, "POST", "/inventory/adjustments", Map.of("locationId", warehouse,
                "responsibleById", adminId, "approvedById", adminId, "items", List.of(Map.of("productId", p,
                        "qtyActual", 3))));
        Res refused = as("POST", "/inventory/adjustments/" + noVariant.get("id").asString() + "/approve", Map.of());
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.raw().body()).contains("Choose a variant for");
        JsonNode adj = ok(200, "POST", "/inventory/adjustments", Map.of("locationId", warehouse, "responsibleById",
                adminId, "approvedById", adminId, "items", List.of(Map.of("productId", p, "variantId", whiteSmall,
                        "qtyActual", 3))));
        ok(200, "POST", "/inventory/adjustments/" + adj.get("id").asString() + "/approve", Map.of());
        assertThat(byName(ok(200, "GET", "/products/" + p + "/variants", null), "White, Small").get("onHand")
                .asDouble()).isEqualTo(3);

        // SKU / barcode unique across variants.
        ok(200, "PATCH", "/products/" + p + "/variants/" + whiteSmall, Map.of("sku", "DUP-" + sfx, "barcode",
                "BC-" + sfx));
        assertThat(as("PATCH", "/products/" + p + "/variants/" + blackSmall, Map.of("sku", "DUP-" + sfx)).status())
                .isEqualTo(409);
        assertThat(as("PATCH", "/products/" + p + "/variants/" + blackSmall, Map.of("barcode", "BC-" + sfx)).status())
                .isEqualTo(409);

        // Archived by hand: hidden from pickers, refused on new lines, but still shown on old ones.
        ok(200, "PATCH", "/products/" + p + "/variants/" + blackSmall, Map.of("isActive", false));
        assertThat(ok(200, "GET", "/product-variants?productId=" + p, null).toString()).doesNotContain(blackSmall);
        Res archived = as("POST", "/sales/orders", Map.of("partnerId", partner(true), "lines", List.of(Map.of(
                "productId", p, "variantId", blackSmall, "quantity", 1))));
        assertThat(archived.status()).isEqualTo(400);
        assertThat(archived.raw().body()).contains("Variant not found");
        // Regeneration does not re-activate a variant archived by hand.
        assertThat(byName(configure(p).get("variants"), "Black, Small").get("isActive").asBoolean()).isFalse();
    }

    BigDecimal variantOnHand(String variantId) {
        return jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockItem\" WHERE \"variantId\" = ?").param(variantId)
                .query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("bill, invoice and credit note keep the variant: receive, issue and restock move the variant's stock")
    void invoicingDocuments() {
        String p = product(100, 40);
        JsonNode variants = configure(p).get("variants");
        String blackLarge = byName(variants, "Black, Large").get("id").asString();
        String today = java.time.LocalDate.now().toString();

        String supplier = partner(false);
        JsonNode bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "lines",
                List.of(Map.of("productId", p, "variantId", blackLarge, "description", "Screen", "quantity", 4,
                        "unitPrice", 30))));
        assertThat(bill.get("lines").get(0).get("variantId").asString()).isEqualTo(blackLarge);
        assertThat(bill.get("lines").get(0).get("variantName").asString()).isEqualTo("Black, Large");
        ok(200, "POST", "/vendor-bills/" + bill.get("id").asString() + "/post", Map.of());
        assertThat(variantOnHand(blackLarge)).isEqualByComparingTo("4");

        String customer = partner(true);
        JsonNode inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", p, "variantId", blackLarge, "quantity", 1))));
        JsonNode line = inv.get("lines").get(0);
        assertThat(line.get("variantName").asString()).isEqualTo("Black, Large");
        assertThat(line.get("unitPrice").asDouble()).isEqualTo(100);
        ok(200, "POST", "/invoices/" + inv.get("id").asString() + "/post", null);
        assertThat(variantOnHand(blackLarge)).isEqualByComparingTo("3");

        // The credit-note line names no variant: the restock takes the credited invoice's variant. Restock goes to the
        // POS stock location, which must be unambiguous (pinned) when the org has several warehouses.
        pinPosWarehouse();
        JsonNode cn = ok(200, "POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today,
                "reversedDocumentId", inv.get("id").asString(), "lines", List.of(Map.of("productId", p, "quantity", 1,
                        "unitPrice", 100))));
        ok(200, "POST", "/credit-notes/" + cn.get("id").asString() + "/post", null);
        assertThat(variantOnHand(blackLarge)).isEqualByComparingTo("4");

        // A variant of another product is refused; lines of products without variants keep their exact shape.
        String other = product(10, 5);
        assertThat(as("POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines", List.of(Map.of(
                "productId", p, "variantId", UUID.randomUUID().toString(), "quantity", 1)))).status()).isEqualTo(400);
        JsonNode plain = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", other, "quantity", 1, "unitPrice", 10))));
        assertThat(plain.get("lines").get(0).has("variantId")).isFalse();
    }

    @Test
    @DisplayName("three-way match: two variants of one product at different prices match per variant")
    void threeWayMatchPerVariant() {
        String p = product(100, 40);
        JsonNode variants = configure(p).get("variants");
        String ws = byName(variants, "White, Small").get("id").asString();
        String bl = byName(variants, "Black, Large").get("id").asString();
        String supplier = partner(false);
        JsonNode po = ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "paymentType", "credit", "lines", List.of(Map.of("productId", p, "variantId", ws,
                        "description", "WS", "quantity", 10, "unitPrice", 30), Map.of("productId", p, "variantId", bl,
                                "description", "BL", "quantity", 5, "unitPrice", 90))));
        String poId = po.get("id").asString();
        List<Map<String, Object>> rec = new java.util.ArrayList<>();
        for (JsonNode l : po.get("lines")) {
            rec.add(Map.of("purchaseOrderLineId", l.get("id").asString(), "productId", p, "description", "x",
                    "quantity", l.get("quantity").asDouble()));
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res r = call("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", rec), h);
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        String today = java.time.LocalDate.now().toString();
        JsonNode bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today,
                "purchaseOrderIds", List.of(poId), "lines", List.of(Map.of("productId", p, "variantId", ws,
                        "description", "WS", "quantity", 10, "unitPrice", 30), Map.of("productId", p, "variantId", bl,
                                "description", "BL", "quantity", 5, "unitPrice", 90))));
        Res posted = as("POST", "/vendor-bills/" + bill.get("id").asString() + "/post", Map.of());
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        assertThat(variantOnHand(ws)).isEqualByComparingTo("10");
        assertThat(variantOnHand(bl)).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("tenant isolation and authorization of every variant route")
    void isolationAndAuthorization() {
        String p = product(10, 5);
        configure(p);
        Res other = call("GET", "/products/" + p + "/variant-config", null, otherToken);
        assertThat(other.status()).isEqualTo(404);
        assertThat(call("GET", "/variant-attributes", null, otherToken).body().toString()).doesNotContain(color);
        assertThat(assertAuthorizationMatrix(ProductVariantController.class)).isGreaterThan(0);
    }
}
