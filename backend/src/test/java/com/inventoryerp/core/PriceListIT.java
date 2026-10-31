package com.inventoryerp.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.core.web.PriceListController;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SALES-3: named lists, tiers and unit prices, applied only to lines without a price. */
class PriceListIT extends ApiTestSupport {

    String ton;
    String truck;
    String sand;
    String customer;
    String other;

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeAll
    void fixtures() {
        ton = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'TON'").param(demoOrg)
                .query(String.class).single();
        truck = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'TRUCK'").param(demoOrg)
                .query(String.class).single();
        sand = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service',"
                + " false, 0, 100, ?, ?, now())").params(sand, demoOrg, "PL-" + sfx(), "List sand " + sfx(), ton, ton)
                .update();
        ok(200, "PUT", "/products/" + sand + "/unit-profile", Map.of("tonnesPerTruckload", 30));
        customer = partner();
        other = partner();
    }

    String partner() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "PLC-" + sfx(), "List cust " + sfx()).update();
        return id;
    }

    JsonNode resolve(String partner, Object qty, String uomId) {
        return ok(200, "POST", "/price-lists/resolve", uomId == null ? Map.of("partnerId", partner, "productId", sand,
                "quantity", qty) : Map.of("partnerId", partner, "productId", sand, "quantity", qty, "uomId", uomId));
    }

    @Test
    @DisplayName("a contract list prices its customer, a quantity break wins, others keep the catalogue price")
    void tiersAndBreaks() {
        ok(201, "POST", "/price-lists", Map.of("code", "CON-" + sfx(), "name", "Contract", "tier", "contract",
                "partnerIds", List.of(customer), "items", List.of(
                        Map.of("productId", sand, "minQuantity", 0, "unitPrice", 90),
                        Map.of("productId", sand, "minQuantity", 100, "unitPrice", 80))));
        assertThat(resolve(customer, 10, null).get("unitPrice").decimalValue()).isEqualByComparingTo("90");
        assertThat(resolve(customer, 100, null).get("unitPrice").decimalValue()).isEqualByComparingTo("80");
        assertThat(resolve(other, 10, null).get("matched").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("an item in the sales unit scales to the line's unit; an item in that unit is exact")
    void unitPrices() {
        String c = partner();
        ok(201, "POST", "/price-lists", Map.of("code", "UOM-" + sfx(), "name", "Units", "partnerIds", List.of(c),
                "items", List.of(Map.of("productId", sand, "unitPrice", 90))));
        assertThat(resolve(c, 1, truck).get("unitPrice").decimalValue()).isEqualByComparingTo("2700");
        String d = partner();
        ok(201, "POST", "/price-lists", Map.of("code", "UOT-" + sfx(), "name", "Truck price", "partnerIds", List.of(d),
                "items", List.of(Map.of("productId", sand, "uomId", truck, "unitPrice", 2500))));
        assertThat(resolve(d, 1, truck).get("unitPrice").decimalValue()).isEqualByComparingTo("2500");
    }

    @Test
    @DisplayName("invoices and orders: a line with no price takes the list, a typed price wins")
    void applied() {
        String c = partner();
        ok(201, "POST", "/price-lists", Map.of("code", "APP-" + sfx(), "name", "Applied", "partnerIds", List.of(c),
                "items", List.of(Map.of("productId", sand, "unitPrice", 70))));
        JsonNode inv = ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", LocalDate.now().toString(),
                "lines", List.of(Map.of("productId", sand, "quantity", 2), Map.of("productId", sand, "quantity", 2,
                        "unitPrice", 95))));
        assertThat(inv.get("lines").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("70");
        assertThat(inv.get("lines").get(1).get("unitPrice").decimalValue()).isEqualByComparingTo("95");
        JsonNode order = ok(200, "POST", "/orders", Map.of("partnerId", c, "lines", List.of(Map.of("productId", sand,
                "quantity", 2))));
        assertThat(order.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("70");
        ok(200, "POST", "/orders/" + order.get("id").asString() + "/cancel", Map.of("reason", "test"));
    }

    @Test
    @DisplayName("an expired list and a line with neither price nor discount are refused or ignored")
    void validation() {
        String c = partner();
        ok(201, "POST", "/price-lists", Map.of("code", "OLD-" + sfx(), "name", "Old", "validTo", "2020-01-01",
                "partnerIds", List.of(c), "items", List.of(Map.of("productId", sand, "unitPrice", 1))));
        assertThat(resolve(c, 1, null).get("matched").asBoolean()).isFalse();
        assertThat(as("POST", "/price-lists", Map.of("code", "BAD-" + sfx(), "name", "Bad", "items",
                List.of(Map.of("productId", sand)))).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("tenant isolation and authorization matrix")
    void guards() {
        String id = ok(201, "POST", "/price-lists", Map.of("code", "ISO-" + sfx(), "name", "Iso")).get("id").asString();
        assertThat(call("GET", "/price-lists/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(assertAuthorizationMatrix(PriceListController.class)).isEqualTo(6);
    }
}
