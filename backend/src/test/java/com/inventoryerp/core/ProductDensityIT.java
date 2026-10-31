package com.inventoryerp.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.core.web.ProductUnitProfileController;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** UOM-1: m³, bag and truckload units with product-specific density, through the shared conversion engine. */
class ProductDensityIT extends ApiTestSupport {

    String ton;
    String kg;
    String m3;
    String liter;
    String bag;
    String truck;

    @BeforeAll
    void fixtures() {
        ton = uom("TON");
        kg = uom("KG");
        m3 = uom("M3");
        liter = uom("L");
        bag = uom("BAG");
        truck = uom("TRUCK");
    }

    String uom(String code) {
        return jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = ?")
                .params(demoOrg, code).query(String.class).single();
    }

    String product(String baseUomId) {
        String id = UUID.randomUUID().toString();
        String s = id.substring(0, 8);
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, 100, ?, ?, now())")
                .params(id, demoOrg, "DP-" + s, "Density " + s, baseUomId, baseUomId).update();
        return id;
    }

    BigDecimal convert(String productId, Object qty, String from, String to) {
        return new BigDecimal(ok(201, "POST", "/uoms/convert", Map.of("quantity", qty, "fromUomId", from, "toUomId", to,
                "productId", productId)).get("result").asString());
    }

    @Test
    @DisplayName("seeded units: M3 in Volume at 1000 L, BAG and TRUCK in Quantity")
    void seeds() {
        assertThat(jdbc.sql("SELECT factor FROM \"UnitOfMeasure\" WHERE id = ?").param(m3).query(BigDecimal.class)
                .single()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("density differs per product: 10 t is 6.25 m³ of sand and 5 m³ of ballast")
    void perProductDensity() {
        String sand = product(ton);
        String ballast = product(ton);
        ok(200, "PUT", "/products/" + sand + "/unit-profile", Map.of("densityTonnesPerM3", 1.6));
        ok(200, "PUT", "/products/" + ballast + "/unit-profile", Map.of("densityTonnesPerM3", 2));
        assertThat(convert(sand, 10, ton, m3)).isEqualByComparingTo("6.25");
        assertThat(convert(ballast, 10, ton, m3)).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("a bridge covers the whole category: litres to kilograms through m³ to tonnes")
    void categoryBridge() {
        String sand = product(ton);
        ok(200, "PUT", "/products/" + sand + "/unit-profile", Map.of("densityTonnesPerM3", 1.6));
        assertThat(convert(sand, 1000, liter, kg)).isEqualByComparingTo("1600");
    }

    @Test
    @DisplayName("no density: cross-category conversion is still a 400")
    void noDensity() {
        String sand = product(ton);
        Res r = as("POST", "/uoms/convert", Map.of("quantity", 1, "fromUomId", m3, "toUomId", ton, "productId", sand));
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("truckload and bag: sized per product, converted on document lines, stored as ProductUom")
    void truckAndBag() {
        String sand = product(ton);
        JsonNode p = ok(200, "PUT", "/products/" + sand + "/unit-profile", Map.of("densityTonnesPerM3", 1.5,
                "kgPerBag", 50, "tonnesPerTruckload", 30));
        assertThat(p.get("tonnesPerTruckload").asDouble()).isEqualTo(30);
        assertThat(convert(sand, 2, truck, m3)).isEqualByComparingTo("40");
        assertThat(convert(sand, 1, ton, bag)).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("an m³-based product can still be sized in tonnes once it has a density")
    void volumeBase() {
        String concrete = product(m3);
        ok(200, "PUT", "/products/" + concrete + "/unit-profile", Map.of("densityTonnesPerM3", 2.4,
                "tonnesPerTruckload", 12));
        assertThat(convert(concrete, 1, truck, m3)).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("a pack cannot be sized in weight before the product has one")
    void packNeedsWeight() {
        String piece = product(uom("UNIT"));
        Res r = as("PUT", "/products/" + piece + "/unit-profile", Map.of("kgPerBag", 25));
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("0 removes a fact")
    void zeroRemoves() {
        String sand = product(ton);
        ok(200, "PUT", "/products/" + sand + "/unit-profile", Map.of("densityTonnesPerM3", 1.6));
        JsonNode p = ok(200, "PUT", "/products/" + sand + "/unit-profile", Map.of("densityTonnesPerM3", 0));
        assertThat(p.get("densityTonnesPerM3").isNull()).isTrue();
    }

    @Test
    @DisplayName("equivalents list every unit the product converts to")
    void equivalents() {
        String sand = product(ton);
        ok(200, "PUT", "/products/" + sand + "/unit-profile", Map.of("densityTonnesPerM3", 1.5,
                "tonnesPerTruckload", 30));
        JsonNode units = ok(200, "GET", "/products/" + sand + "/unit-equivalents?quantity=60", null).get("units");
        java.util.Set<String> codes = new java.util.HashSet<>();
        units.forEach(u -> codes.add(u.get("code").asString()));
        assertThat(codes).contains("TON", "M3", "TRUCK");
    }

    @Test
    @DisplayName("sales invoice line in truckloads snapshots the conversion in base units")
    void invoiceLine() {
        String sand = product(ton);
        ok(200, "PUT", "/products/" + sand + "/unit-profile", Map.of("tonnesPerTruckload", 28));
        String customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "DPC-" + customer.substring(0, 8),
                "Density customer " + customer.substring(0, 8)).update();
        JsonNode inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate",
                java.time.LocalDate.now().toString(), "lines", List.of(Map.of("productId", sand, "uomId", truck,
                        "quantity", 2, "description", "Sand"))));
        JsonNode line = inv.get("lines").get(0);
        assertThat(new BigDecimal(line.get("uomFactor").asString())).isEqualByComparingTo("28");
    }

    @Test
    @DisplayName("authorization matrix for the unit profile routes")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix(ProductUnitProfileController.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("tenant isolation: another organization cannot read a product's profile")
    void tenantIsolation() {
        String sand = product(ton);
        assertThat(call("GET", "/products/" + sand + "/unit-profile", null, otherToken).status()).isEqualTo(404);
    }
}
