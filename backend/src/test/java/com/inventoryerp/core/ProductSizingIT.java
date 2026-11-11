package com.inventoryerp.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Sell by size (ADR-S066): granite sold by the metre / square metre, with length variants carrying their own price,
 * size and description, through quotation → sales order → invoice and direct invoices.
 */
class ProductSizingIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

    String product(String name, int price, String policy) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"invoicePolicy\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'service'::\"ProductType\", ?, 0, false, ?, now())").params(id, demoOrg, "GR-" + UUID.randomUUID()
                        .toString().substring(0, 8), name, price, policy).update();
        return id;
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, true, now())").params(id, demoOrg, "GC-" + UUID.randomUUID().toString()
                        .substring(0, 8), "Granite buyer " + sfx).update();
        return id;
    }

    static Map<String, Object> line(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("sizing config: modes, units, defaults validated; a plain product refuses a sized line")
    void config() {
        String p = product("Granite kerb " + sfx, 50_000, "ordered");
        JsonNode cfg = ok(200, "GET", "/products/" + p + "/sizing", null);
        assertThat(cfg.get("sizingMode").isNull()).isTrue();
        assertThat(cfg.get("sizingUnit").asString()).isEqualTo("m");

        assertThat(as("PUT", "/products/" + p + "/sizing", Map.of("sizingMode", "weight")).status()).isEqualTo(400);
        assertThat(as("PUT", "/products/" + p + "/sizing", Map.of("sizingUnit", "in")).status()).isEqualTo(400);
        assertThat(as("PUT", "/products/" + p + "/sizing", Map.of("defaultWidth", 0)).status()).isEqualTo(400);
        assertThat(as("PUT", "/products/" + p + "/sizing", Map.of("color", "red")).status()).isEqualTo(400);

        // not sold by size yet: a sized line is refused
        Res plain = as("POST", "/sales/orders", Map.of("partnerId", customer(), "lines", List.of(line("productId", p,
                "pieces", 2, "length", 3))));
        assertThat(plain.status()).isEqualTo(400);
        assertThat(plain.raw().body()).contains("is not sold by size");

        JsonNode saved = ok(200, "PUT", "/products/" + p + "/sizing", Map.of("sizingMode", "area", "sizingUnit", "cm",
                "defaultWidth", 60));
        assertThat(saved.get("sizingMode").asString()).isEqualTo("area");
        assertThat(saved.get("defaultWidth").asDouble()).isEqualTo(60);
        // width is required for an area product
        Res noWidth = as("POST", "/sales/orders", Map.of("partnerId", customer(), "lines", List.of(line("productId", p,
                "pieces", 2, "length", 300))));
        assertThat(noWidth.status()).isEqualTo(400);
        assertThat(noWidth.raw().body()).contains("width is required");
    }

    @Test
    @DisplayName("length variants (1 m / 3 m) carry price, size and description; quotation → order → invoice keep the size")
    void variantsThroughTheSalesFlow() {
        String p = product("Granite step " + sfx, 40, "ordered");
        ok(200, "PUT", "/products/" + p + "/sizing", Map.of("sizingMode", "length"));
        JsonNode attr = ok(201, "POST", "/variant-attributes", Map.of("name", "Length " + sfx, "values", List.of(Map.of(
                "name", "1 m"), Map.of("name", "3 m"))));
        ok(200, "PUT", "/products/" + p + "/attribute-lines", Map.of("lines", List.of(Map.of("attributeId", attr.get("id")
                .asString(), "valueIds", List.of(attr.get("values").get(0).get("id").asString(), attr.get("values").get(1)
                        .get("id").asString())))));
        JsonNode variants = ok(200, "GET", "/products/" + p + "/variants", null);
        String threeM = ProductVariantIT.byName(variants, "3 m").get("id").asString();
        JsonNode edited = ok(200, "PATCH", "/products/" + p + "/variants/" + threeM, Map.of("salesPrice", 35,
                "length", 3, "description", "Polished black granite step, 3 m"));
        assertThat(edited.get("length").asDouble()).isEqualTo(3);
        assertThat(edited.get("description").asString()).isEqualTo("Polished black granite step, 3 m");
        assertThat(as("PATCH", "/products/" + p + "/variants/" + threeM, Map.of("length", -1)).status()).isEqualTo(400);
        JsonNode option = ok(200, "GET", "/product-variants?productId=" + p, null);
        JsonNode opt = ProductVariantIT.byName(option, "3 m");
        assertThat(opt.get("length").asDouble()).isEqualTo(3);
        assertThat(opt.get("salesPrice").asDouble()).isEqualTo(35);

        // quotation: 4 pieces × 3 m = 12 m at the variant's price per metre
        String c = customer();
        JsonNode q = ok(201, "POST", "/sales/quotations", Map.of("partnerId", c, "lines", List.of(line("productId", p,
                "variantId", threeM, "pieces", 4, "length", 3, "quantity", 999))));
        JsonNode ql = q.get("lines").get(0);
        assertThat(ql.get("quantity").asDouble()).isEqualTo(12);
        assertThat(ql.get("pieces").asDouble()).isEqualTo(4);
        assertThat(ql.get("length").asDouble()).isEqualTo(3);
        assertThat(ql.get("sizingMode").asString()).isEqualTo("length");
        assertThat(ql.get("unitPrice").asDouble()).isEqualTo(35);
        assertThat(ql.get("lineSubtotal").asDouble()).isEqualTo(420);
        String qid = q.get("id").asString();

        ok(200, "POST", "/sales/quotations/" + qid + "/submit", null);
        String so = ok(200, "POST", "/sales/quotations/" + qid + "/convert", Map.of("target", "sales_order"))
                .get("convertedId").asString();
        JsonNode order = ok(200, "GET", "/sales/orders/" + so, null);
        JsonNode ol = order.get("lines").get(0);
        assertThat(ol.get("pieces").asDouble()).isEqualTo(4);
        assertThat(ol.get("length").asDouble()).isEqualTo(3);
        assertThat(ol.get("quantity").asDouble()).isEqualTo(12);
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);

        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res inv = call("POST", "/sales/orders/" + so + "/invoices", Map.of("mode", "regular"), h);
        assertThat(inv.status()).as(inv.raw().body()).isEqualTo(200);
        JsonNode il = inv.body().get("lines").get(0);
        assertThat(il.get("quantity").asDouble()).isEqualTo(12);
        assertThat(il.get("pieces").asDouble()).isEqualTo(4);
        assertThat(il.get("length").asDouble()).isEqualTo(3);
        assertThat(il.get("variantName").asString()).isEqualTo("3 m");
    }

    @Test
    @DisplayName("direct invoice and sales order by area in cm: the size decides the quantity; plain lines keep their shape")
    void directDocuments() {
        String slab = product("Granite slab " + sfx, 120_000, "ordered");
        ok(200, "PUT", "/products/" + slab + "/sizing", Map.of("sizingMode", "area", "sizingUnit", "cm"));
        String c = customer();
        String today = LocalDate.now().toString();
        JsonNode inv = ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", today, "lines", List.of(line(
                "productId", slab, "pieces", 2, "length", 240, "width", 120, "quantity", 5.76))));
        JsonNode il = inv.get("lines").get(0);
        assertThat(il.get("quantity").asDouble()).isEqualTo(5.76);
        assertThat(il.get("width").asDouble()).isEqualTo(120);
        assertThat(il.get("subtotal").asDouble()).isEqualTo(691_200);
        // re-read keeps the size
        JsonNode again = ok(200, "GET", "/invoices/" + inv.get("id").asString(), null).get("lines").get(0);
        assertThat(again.get("pieces").asDouble()).isEqualTo(2);

        JsonNode so = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "lines", List.of(line("productId", slab,
                "pieces", 3, "length", 200, "width", 60))));
        assertThat(so.get("lines").get(0).get("quantity").asDouble()).isEqualTo(3.6);

        String plain = product("Grout " + sfx, 1_000, "ordered");
        JsonNode p = ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", today, "lines", List.of(line(
                "productId", plain, "quantity", 2, "unitPrice", 1_000))));
        assertThat(p.get("lines").get(0).has("pieces")).isFalse();
    }

    @Test
    @DisplayName("tenant isolation of the sizing routes")
    void isolation() {
        String p = product("Isolated " + sfx, 1, "ordered");
        assertThat(call("GET", "/products/" + p + "/sizing", null, otherToken).status()).isEqualTo(404);
        assertThat(call("PUT", "/products/" + p + "/sizing", Map.of("sizingMode", "length"), otherToken).status())
                .isIn(403, 404);
    }
}
