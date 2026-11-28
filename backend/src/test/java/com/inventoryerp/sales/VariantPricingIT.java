package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Phase I3/I4 pricing: owner decision D1 (salesPrice is per default sales unit, audit P-2), variant price over the
 * product's unit price (P-3), rules that name a variant (P-1) and the quotation approval re-price (S-7).
 */
class VariantPricingIT extends ApiTestSupport {

    String customer;
    String piece;
    String box;
    String pack;

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeAll
    void fixtures() {
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "VPC-" + sfx(), "VP cust " + sfx())
                .update();
        piece = unit("PC");
        box = unit("BOX");
        pack = unit("PACK");
    }

    String unit(String code) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, category, factor, ratio,"
                + " \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, ?, 'unit', 1, 1, 1, now())")
                .params(id, demoOrg, code + "-" + sfx(), code).update();
        return id;
    }

    /** Counted in pieces, sold by the box of 24 at 240 a box; a pack of 6 has its own price of 70. */
    String boxed(String name) {
        String p = ok(201, "POST", "/products", Map.of("code", "VP-" + sfx(), "name", name + " " + sfx(),
                "productType", "service", "salesPrice", 240, "costPrice", 0)).get("id").asString();
        jdbc.sql("UPDATE \"Product\" SET \"uomId\" = ?, \"salesUomId\" = ? WHERE id = ?").params(piece, box, p).update();
        ok(200, "PATCH", "/products/" + p, Map.of("units", List.of(
                Map.of("uomId", box, "factor", 24, "isPurchase", true, "isSales", true),
                Map.of("uomId", pack, "factor", 6, "isPurchase", true, "isSales", true, "salePrice", 70))));
        return p;
    }

    /** Variants Red (product price) and Blue (fixed 300 a box) of {@code product}. */
    Map<String, String> variants(String product) {
        JsonNode attr = ok(201, "POST", "/variant-attributes", Map.of("name", "Shade " + sfx(), "values",
                List.of(Map.of("name", "Red"), Map.of("name", "Blue"))));
        JsonNode cfg = ok(200, "PUT", "/products/" + product + "/attribute-lines", Map.of("lines", List.of(Map.of(
                "attributeId", attr.get("id").asString(), "valueIds", List.of(attr.get("values").get(0).get("id")
                        .asString(), attr.get("values").get(1).get("id").asString())))));
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode v : cfg.get("variants")) {
            out.put(v.get("name").asString(), v.get("id").asString());
        }
        ok(200, "PATCH", "/products/" + product + "/variants/" + out.get("Blue"), Map.of("salesPrice", 300));
        return out;
    }

    JsonNode resolve(String product, String variant, String uom) {
        Map<String, Object> line = new LinkedHashMap<>(Map.of("productId", product, "quantity", 1));
        if (variant != null) {
            line.put("variantId", variant);
        }
        if (uom != null) {
            line.put("uomId", uom);
        }
        return ok(200, "POST", "/sales/pricing/resolve", Map.of("partnerId", customer, "lines", List.of(line)))
                .get("lines").get(0);
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    @Test
    @DisplayName("D1: a sales order line in BOX where salesUomId = BOX is priced at salesPrice")
    void salesPriceIsPerSalesUnit() {
        String p = boxed("Boxed soap");
        assertThat(dec(resolve(p, null, box).get("unitPrice"))).as("the box is the sales unit").isEqualByComparingTo("240");
        assertThat(dec(resolve(p, null, null).get("unitPrice"))).as("no unit = the sales unit").isEqualByComparingTo("240");
        assertThat(dec(resolve(p, null, piece).get("unitPrice"))).as("scaled to a piece").isEqualByComparingTo("10");
        assertThat(dec(resolve(p, null, pack).get("unitPrice"))).as("the pack's own price").isEqualByComparingTo("70");

        JsonNode order = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "lines", List.of(
                Map.of("productId", p, "quantity", 1, "uomId", box))));
        assertThat(dec(order.get("lines").get(0).get("unitPrice"))).isEqualByComparingTo("240");
        assertThat(order.get("total").asDouble()).isEqualTo(240);
    }

    @Test
    @DisplayName("P-3: a variant's price beats the product's own unit price; it is scaled to the line's unit")
    void variantPriceBeatsTheUnitPrice() {
        String p = boxed("Boxed paint");
        Map<String, String> v = variants(p);
        assertThat(dec(resolve(p, v.get("Blue"), box).get("unitPrice"))).isEqualByComparingTo("300");
        assertThat(dec(resolve(p, v.get("Blue"), pack).get("unitPrice"))).as("300 × 6 / 24").isEqualByComparingTo("75");
        assertThat(dec(resolve(p, v.get("Blue"), piece).get("unitPrice"))).isEqualByComparingTo("12.5");
        // The invoice builder follows the same rule (D1 / P-3).
        JsonNode inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate",
                java.time.LocalDate.now().toString(), "lines", List.of(Map.of("productId", p, "variantId", v.get("Blue"),
                        "quantity", 1, "uomId", pack))));
        assertThat(dec(inv.get("lines").get(0).get("unitPrice"))).isEqualByComparingTo("75");
    }

    @Test
    @DisplayName("P-1: a pricelist rule or promotion naming a variant prices that variant only and beats the product's")
    void variantRules() {
        String p = boxed("Boxed tiles");
        Map<String, String> v = variants(p);
        String list = ok(201, "POST", "/sales/pricelists", Map.of("code", "VPL" + sfx(), "name", "Variant list " + sfx()))
                .get("id").asString();
        ok(201, "POST", "/sales/pricelists/" + list + "/items", Map.of("scope", "product", "productId", p,
                "partnerId", customer, "compute", "discount", "value", 10));
        ok(201, "POST", "/sales/pricelists/" + list + "/items", Map.of("scope", "product", "productId", p,
                "variantId", v.get("Blue"), "partnerId", customer, "compute", "discount", "value", 20));
        assertThat(dec(resolve(p, v.get("Blue"), box).get("discountPercent"))).isEqualByComparingTo("20");
        assertThat(dec(resolve(p, v.get("Red"), box).get("discountPercent"))).as("the product rule")
                .isEqualByComparingTo("10");
        // A variant of another product is refused.
        String other = boxed("Other");
        Map<String, String> ov = variants(other);
        Res wrong = as("POST", "/sales/pricelists/" + list + "/items", Map.of("scope", "product", "productId", p,
                "variantId", ov.get("Red"), "compute", "discount", "value", 5));
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.raw().body()).contains("Variant not found");

        String promoProduct = boxed("Promo tiles");
        Map<String, String> pv = variants(promoProduct);
        ok(201, "POST", "/sales/promotions", Map.of("name", "Red week " + sfx(), "kind", "percent", "value", 15,
                "productId", promoProduct, "variantId", pv.get("Red"), "startDate", java.time.LocalDate.now().toString(),
                "endDate", java.time.LocalDate.now().plusDays(7).toString()));
        assertThat(resolve(promoProduct, pv.get("Red"), box).get("source").asString()).isEqualTo("promotion");
        assertThat(resolve(promoProduct, pv.get("Blue"), box).get("source").asString()).isEqualTo("list");
    }

    @Test
    @DisplayName("S-7: quotation approval re-prices a line in its own unit, so a unit price is not taken for a discount")
    void approvalRepricesInTheLineUnit() {
        JsonNode before = ok(200, "GET", "/crm/config/settings", null);
        try {
            ok(200, "PUT", "/crm/config/settings", Map.of("quoteMaxDiscountPercent", 10, "creditCheck", false));
            String p = boxed("Quoted soap");
            JsonNode q = ok(201, "POST", "/sales/quotations", Map.of("partnerId", customer, "lines", List.of(Map.of(
                    "productId", p, "quantity", 2, "uomId", pack, "unitPrice", 70))));
            JsonNode submitted = ok(200, "POST", "/sales/quotations/" + q.get("id").asString() + "/submit", null);
            List<String> reasons = new ArrayList<>();
            if (submitted.get("approvalReasons") != null) {
                submitted.get("approvalReasons").forEach(r -> reasons.add(r.asString()));
            }
            assertThat(reasons).as(submitted.toString()).noneMatch(r -> r.startsWith("Discount"));
        } finally {
            ok(200, "PUT", "/crm/config/settings", Map.of("quoteMaxDiscountPercent",
                    before.get("quoteMaxDiscountPercent").asDouble(), "creditCheck", before.get("creditCheck").asBoolean()));
        }
    }

    @Test
    @DisplayName("I4: a variant's agreement line is drawn on by that variant's releases only")
    void agreementReleasePerVariant() {
        String p = ok(201, "POST", "/products", Map.of("code", "VA-" + sfx(), "name", "Agreed paint " + sfx(),
                "productType", "service", "salesPrice", 300, "costPrice", 0)).get("id").asString();
        Map<String, String> v = variants(p);
        String id = ok(201, "POST", "/sales/agreements", Map.of("partnerId", customer, "name", "Paint deal " + sfx(),
                "kind", "contract", "startDate", java.time.LocalDate.now().minusDays(1).toString(), "endDate",
                java.time.LocalDate.now().plusMonths(3).toString(), "lines", List.of(Map.of("productId", p,
                        "variantId", v.get("Red"), "contractQty", 100, "unitPrice", 200)))).get("id").asString();
        ok(200, "POST", "/sales/agreements/" + id + "/activate", null);

        JsonNode so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "agreementId", id, "lines", List.of(
                Map.of("productId", p, "variantId", v.get("Red"), "quantity", 30),
                Map.of("productId", p, "variantId", v.get("Blue"), "quantity", 10))));
        Map<String, String> source = new LinkedHashMap<>();
        so.get("lines").forEach(l -> source.put(l.get("variantId").asString(), l.get("priceSource").asString()));
        assertThat(source.get(v.get("Red"))).isEqualTo("agreement");
        assertThat(source.get(v.get("Blue"))).as("the Red line does not price Blue").isNotEqualTo("agreement");
        assertThat(ok(200, "POST", "/sales/orders/" + so.get("id").asString() + "/confirm", null).get("status")
                .asString()).isEqualTo("confirmed");

        JsonNode line = ok(200, "GET", "/sales/agreements/" + id, null).get("lines").get(0);
        assertThat(line.get("releasedQty").asDouble()).as("Red's 30 only, not Blue's 10").isEqualTo(30);
        assertThat(line.get("balanceQty").asDouble()).isEqualTo(70);

        String blue = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "agreementId", id, "lines",
                List.of(Map.of("productId", p, "variantId", v.get("Blue"), "quantity", 500)))).get("id").asString();
        assertThat(ok(200, "POST", "/sales/orders/" + blue + "/confirm", null).get("approvalReasons").toString())
                .as("Blue does not draw on Red's balance").doesNotContain("agreement balance");
        String over = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "agreementId", id, "lines",
                List.of(Map.of("productId", p, "variantId", v.get("Red"), "quantity", 75)))).get("id").asString();
        JsonNode pending = ok(200, "POST", "/sales/orders/" + over + "/submit-approval", null);
        assertThat(pending.get("approvalStatus").asString()).isEqualTo("pending");
        assertThat(pending.get("approvalReasons").toString()).contains("exceeds the agreement balance of 70");
    }
}
