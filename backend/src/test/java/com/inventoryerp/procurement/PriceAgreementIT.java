package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.procurement.web.PriceAgreementController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** PROC-2: agreements (overlap, end, cancel, lookup by quantity) and price history against what was agreed. */
class PriceAgreementIT extends ApiTestSupport {

    String warehouse;
    LocalDate today = LocalDate.now();

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeAll
    void fixtures() {
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "PA-" + suffix(), "name", "Prices " + suffix(),
                "type", "warehouse")).get("id").asString();
    }

    String supplier() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, true, now())")
                .params(id, demoOrg, "PA-" + suffix(), "Price Supplier " + suffix()).update();
        return id;
    }

    String product() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true,"
                        + " 'AVCO'::\"CostingMethod\", 0, 0, now())")
                .params(id, demoOrg, "PA-" + suffix(), "Cement " + suffix()).update();
        return id;
    }

    Map<String, Object> agreement(String partner, String product, int price, int minQuantity, LocalDate from, LocalDate to) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("partnerId", partner);
        m.put("productId", product);
        m.put("unitPrice", price);
        m.put("currencyCode", "UGX");
        m.put("minQuantity", minQuantity);
        m.put("validFrom", from.toString());
        if (to != null) {
            m.put("validTo", to.toString());
        }
        return m;
    }

    @Test
    @DisplayName("the same price point cannot overlap itself; ending it makes room for the new price")
    void overlap() {
        String partner = supplier();
        String product = product();
        String id = ok(200, "POST", "/procurement/price-agreements",
                agreement(partner, product, 100, 0, today.minusDays(10), null)).get("id").asString();
        Res clash = as("POST", "/procurement/price-agreements", agreement(partner, product, 90, 0, today, null));
        assertThat(clash.status()).isEqualTo(409);

        ok(200, "PATCH", "/procurement/price-agreements/" + id, Map.of("validTo", today.minusDays(1).toString()));
        JsonNode second = ok(200, "POST", "/procurement/price-agreements", agreement(partner, product, 90, 0, today, null));
        assertThat(second.get("state").asString()).isEqualTo("active");
        assertThat(ok(200, "GET", "/procurement/price-agreements/" + id, null).get("state").asString()).isEqualTo("expired");

        JsonNode expired = ok(200, "GET", "/procurement/price-agreements?status=expired&partnerId=" + partner, null);
        assertThat(expired.get("data")).hasSize(1);
        assertThat(expired.get("data").get(0).get("id").asString()).isEqualTo(id);
    }

    @Test
    @DisplayName("validTo before validFrom, an unknown supplier and changes to a cancelled agreement are refused")
    void validation() {
        String product = product();
        assertThat(as("POST", "/procurement/price-agreements", agreement(supplier(), product, 1, 0, today, today.minusDays(1)))
                .status()).isEqualTo(400);
        assertThat(as("POST", "/procurement/price-agreements",
                agreement(UUID.randomUUID().toString(), product, 1, 0, today, null)).status()).isEqualTo(404);
        String id = ok(200, "POST", "/procurement/price-agreements", agreement(supplier(), product, 1, 0, today, null))
                .get("id").asString();
        ok(200, "POST", "/procurement/price-agreements/" + id + "/cancel", null);
        assertThat(as("POST", "/procurement/price-agreements/" + id + "/cancel", null).status()).isEqualTo(400);
        assertThat(as("PATCH", "/procurement/price-agreements/" + id, Map.of("notes", "x")).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("lookup takes the highest quantity break that applies; a cancelled agreement no longer applies")
    void lookup() {
        String partner = supplier();
        String product = product();
        ok(200, "POST", "/procurement/price-agreements", agreement(partner, product, 100, 0, today.minusDays(5), null));
        String volume = ok(200, "POST", "/procurement/price-agreements",
                agreement(partner, product, 80, 50, today.minusDays(5), null)).get("id").asString();
        String path = "/procurement/price-agreements/lookup?partnerId=" + partner + "&productId=" + product;

        assertThat(new BigDecimal(ok(200, "GET", path + "&quantity=10", null).get("agreement").get("unitPrice").asString()))
                .isEqualByComparingTo("100");
        assertThat(new BigDecimal(ok(200, "GET", path + "&quantity=50", null).get("agreement").get("unitPrice").asString()))
                .isEqualByComparingTo("80");
        ok(200, "POST", "/procurement/price-agreements/" + volume + "/cancel", null);
        assertThat(new BigDecimal(ok(200, "GET", path + "&quantity=50", null).get("agreement").get("unitPrice").asString()))
                .isEqualByComparingTo("100");
        assertThat(ok(200, "GET", path + "&date=2001-01-01", null).get("agreement").isNull()).isTrue();
    }

    @Test
    @DisplayName("price history: order lines by supplier, stats, and the variance against the agreed price")
    void history() {
        String partner = supplier();
        String other = supplier();
        String product = product();
        ok(200, "POST", "/procurement/price-agreements", agreement(partner, product, 100, 0, today.minusDays(1), null));
        order(partner, product, 10, 110);
        order(other, product, 30, 90);

        JsonNode all = ok(200, "GET", "/procurement/reports/price-history?productId=" + product, null);
        assertThat(all.get("rows")).hasSize(2);
        assertThat(all.get("suppliers")).hasSize(2);
        JsonNode summary = all.get("summary");
        assertThat(new BigDecimal(summary.get("minPrice").asString())).isEqualByComparingTo("90");
        assertThat(new BigDecimal(summary.get("maxPrice").asString())).isEqualByComparingTo("110");
        // (10*110 + 30*90) / 40
        assertThat(new BigDecimal(summary.get("averagePrice").asString())).isEqualByComparingTo("95");

        JsonNode mine = ok(200, "GET", "/procurement/reports/price-history?productId=" + product + "&partnerId=" + partner,
                null);
        JsonNode row = mine.get("rows").get(0);
        assertThat(new BigDecimal(row.get("agreedPrice").asString())).isEqualByComparingTo("100");
        assertThat(new BigDecimal(row.get("variance").asString())).isEqualByComparingTo("10");

        assertThat(as("GET", "/procurement/reports/price-history", null).status()).isEqualTo(400);
    }

    void order(String partner, String product, int qty, int price) {
        ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", partner, "warehouseId", warehouse,
                "paymentType", "credit", "currencyCode", "UGX", "lines",
                List.of(Map.of("productId", product, "description", "Cement", "quantity", qty, "unitPrice", price))));
    }

    @Test
    @DisplayName("tenant isolation and authorization")
    void isolation() {
        String id = ok(200, "POST", "/procurement/price-agreements", agreement(supplier(), product(), 1, 0, today, null))
                .get("id").asString();
        assertThat(call("GET", "/procurement/price-agreements/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/procurement/price-agreements/" + id + "/cancel", null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/procurement/price-agreements", null, otherToken).body().get("data")).isEmpty();
        assertThat(assertAuthorizationMatrix(PriceAgreementController.class)).isEqualTo(7);
    }
}
