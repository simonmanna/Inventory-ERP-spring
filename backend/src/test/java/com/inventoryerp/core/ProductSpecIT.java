package com.inventoryerp.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.core.web.ProductSpecController;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Product grades, attributes and specifications (INV-2). */
class ProductSpecIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String category;
    String product;

    @BeforeAll
    void fixtures() {
        category = ok(201, "POST", "/product-categories", Map.of("name", "Aggregates " + sfx)).get("id").asString();
        product = ok(201, "POST", "/products", Map.of("code", "SP-" + sfx, "name", "Ballast " + sfx, "categoryId", category,
                "salesPrice", 1)).get("id").asString();
    }

    String grade(String code) {
        return ok(201, "POST", "/product-grades", Map.of("code", code + sfx, "name", "Grade " + code)).get("id").asString();
    }

    @Test
    @DisplayName("grades: code upper-cased and unique; a grade in use cannot be deleted, only deactivated")
    void grades() {
        JsonNode g = ok(201, "POST", "/product-grades", Map.of("code", "c25" + sfx.toLowerCase(), "name", "C25"));
        assertThat(g.get("code").asString()).isEqualTo("C25" + sfx);
        assertThat(as("POST", "/product-grades", Map.of("code", "C25" + sfx, "name", "Again")).status()).isEqualTo(400);
        String id = g.get("id").asString();
        ok(200, "PUT", "/product-specifications/" + product, Map.of("gradeId", id));
        assertThat(as("DELETE", "/product-grades/" + id, null).status()).isEqualTo(400);
        ok(200, "PUT", "/product-specifications/" + product, Map.of());
        ok(200, "DELETE", "/product-grades/" + id, null);
        assertThat(ok(200, "GET", "/product-grades", null).toString()).doesNotContain(id);
    }

    @Test
    @DisplayName("attribute values are checked against their type, options and category; required ones must be given")
    void attributeValues() {
        String strength = ok(201, "POST", "/product-attributes", Map.of("code", "STR" + sfx, "name", "Strength", "dataType",
                "number", "unit", "MPa", "categoryId", category)).get("id").asString();
        String size = ok(201, "POST", "/product-attributes", Map.of("code", "SIZ" + sfx, "name", "Size", "dataType", "option",
                "options", List.of("10 mm", "20 mm"), "categoryId", category)).get("id").asString();
        String washed = ok(201, "POST", "/product-attributes", Map.of("code", "WAS" + sfx, "name", "Washed", "dataType",
                "boolean", "categoryId", category)).get("id").asString();
        assertThat(as("POST", "/product-attributes", Map.of("code", "BAD" + sfx, "name", "Bad", "dataType", "option"))
                .status()).isEqualTo(400);
        assertThat(as("PUT", "/product-specifications/" + product, Map.of("attributes", List.of(Map.of("attributeId",
                strength, "value", "hard")))).status()).isEqualTo(400);
        assertThat(as("PUT", "/product-specifications/" + product, Map.of("attributes", List.of(Map.of("attributeId", size,
                "value", "30 mm")))).status()).isEqualTo(400);
        JsonNode saved = ok(200, "PUT", "/product-specifications/" + product, Map.of("standardRef", "US 1:" + sfx,
                "attributes", List.of(Map.of("attributeId", strength, "value", 25), Map.of("attributeId", size, "value",
                        "20 mm"), Map.of("attributeId", washed, "value", true))));
        assertThat(saved.get("specification").get("standardRef").asString()).isEqualTo("US 1:" + sfx);
        assertThat(saved.get("values")).hasSize(3);
        assertThat(saved.get("attributes").size()).isGreaterThanOrEqualTo(3);
        JsonNode bySize = ok(200, "GET", "/product-specifications?attributeId=" + size + "&value=20%20mm", null);
        assertThat(bySize.toString()).contains(product);
        assertThat(ok(200, "GET", "/product-specifications?attributeId=" + size + "&value=10%20mm", null).toString())
                .doesNotContain(product);
        // Saving without a value clears it; a required attribute must then be given.
        ok(200, "PATCH", "/product-attributes/" + strength, Map.of("isRequired", true));
        assertThat(as("PUT", "/product-specifications/" + product, Map.of()).status()).isEqualTo(400);
        ok(200, "PATCH", "/product-attributes/" + strength, Map.of("isRequired", false));
        assertThat(ok(200, "PUT", "/product-specifications/" + product, Map.of()).get("values")).isEmpty();
    }

    @Test
    @DisplayName("an attribute of another category does not apply; a product's specification lists by grade")
    void scope() {
        String other = ok(201, "POST", "/product-categories", Map.of("name", "Blocks " + sfx)).get("id").asString();
        String attribute = ok(201, "POST", "/product-attributes", Map.of("code", "OTH" + sfx, "name", "Other", "categoryId",
                other)).get("id").asString();
        assertThat(as("PUT", "/product-specifications/" + product, Map.of("attributes", List.of(Map.of("attributeId",
                attribute, "value", "x")))).status()).isEqualTo(400);
        String g = grade("M");
        ok(200, "PUT", "/product-specifications/" + product, Map.of("gradeId", g));
        assertThat(ok(200, "GET", "/product-specifications?gradeId=" + g, null).get(0).get("productName").asString())
                .startsWith("Ballast");
        ok(200, "PUT", "/product-specifications/" + product, Map.of());
    }

    @Test
    @DisplayName("POS and catalogue product JSON gains no field")
    void productShapeUnchanged() {
        JsonNode p = ok(200, "GET", "/products/" + product, null);
        assertThat(p.has("gradeId")).isFalse();
        assertThat(p.has("specification")).isFalse();
    }

    @Test
    @DisplayName("another organization cannot read or write a product's specification")
    void tenantIsolation() {
        assertThat(call("GET", "/product-specifications/" + product, null, otherToken).status()).isEqualTo(404);
        assertThat(call("PUT", "/product-specifications/" + product, Map.of(), otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/product-grades", null, otherToken).raw().body()).doesNotContain(sfx);
    }

    @Test
    @DisplayName("authorization matrix on all 11 routes")
    void authorization() {
        assertThat(assertAuthorizationMatrix(ProductSpecController.class)).isEqualTo(11);
    }
}
