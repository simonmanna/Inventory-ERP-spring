package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Delivery &amp; fulfilment handover of sales orders and invoices (same flow as the branch cash sale delivery). */
class SalesHandoverIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String customer;
    String product;
    String store;

    @BeforeAll
    void fixtures() {
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, phone, \"isCustomer\", \"isCompany\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, '0700111222', true, true, now())")
                .params(customer, demoOrg, "HO-" + sfx, "Handover customer " + sfx).update();
        product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service'::\"ProductType\","
                + " 100, 50, false, now())").params(product, demoOrg, "HOP-" + sfx, "Cement " + sfx).update();
        store = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Main store', 'storage_room', now())").params(store, demoOrg, "HOS-" + sfx).update();
    }

    String order(String method) {
        Map<String, Object> b = new HashMap<>(Map.of("partnerId", customer, "requestedDate", "2026-11-20",
                "deliveryMethod", method, "lines", List.of(Map.of("productId", product, "quantity", 4))));
        if ("delivery".equals(method)) {
            b.put("deliveryAddress", "Plot 7, Site road");
        }
        return ok(201, "POST", "/sales/orders", b).get("id").asString();
    }

    @Test
    @DisplayName("defaults come from the order; collect flow: plan on draft, collect needs confirm, receipt fields stay open")
    void collectFlow() {
        String id = order("collect");
        String path = "/sales/orders/" + id + "/handover";
        JsonNode h = ok(200, "GET", path, null);
        assertThat(h.get("method").asString()).isEqualTo("pickup");
        assertThat(h.get("status").asString()).isEqualTo("pending");
        assertThat(h.get("stage").asString()).isEqualTo("plan");
        assertThat(h.get("contactName").asString()).isEqualTo("Handover customer " + sfx);
        assertThat(h.get("contactPhone").asString()).isEqualTo("0700111222");
        assertThat(h.get("scheduledDate").asString()).isEqualTo("2026-11-20");
        assertThat(h.get("saved").asBoolean()).isFalse();
        assertThat(h.get("lines").get(0).get("quantity").asDouble()).isEqualTo(4);

        assertThat(as("PATCH", path, Map.of("status", "scheduled")).status()).isEqualTo(400);
        JsonNode planned = ok(200, "PATCH", path, Map.of("status", "scheduled", "locationId", store,
                "contactName", "Driver Joe"));
        assertThat(planned.get("locationName").asString()).isEqualTo("Main store");
        assertThat(planned.get("saved").asBoolean()).isTrue();
        assertThat(as("PATCH", path, Map.of("status", "delivered")).status()).isEqualTo(400);
        assertThat(as("PATCH", path, Map.of("bogus", "x")).status()).isEqualTo(400);

        ok(200, "POST", "/sales/orders/" + id + "/confirm", null);
        JsonNode done = ok(200, "PATCH", path, Map.of("status", "delivered", "receivedBy", "Joe"));
        assertThat(done.get("status").asString()).isEqualTo("delivered");
        assertThat(done.get("deliveredAt").isNull()).isFalse();
        assertThat(done.get("deliveredByName").isNull()).isFalse();
        assertThat(done.get("lines").get(0).get("handedOverQty").asDouble()).isEqualTo(4);
        assertThat(ok(200, "PATCH", path, Map.of("notes", "Signed slip")).get("notes").asString())
                .isEqualTo("Signed slip");
        assertThat(as("PATCH", path, Map.of("method", "delivery")).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("delivery flow writes method and address through to the order; isolation")
    void deliveryFlowAndIsolation() {
        String id = order("delivery");
        String path = "/sales/orders/" + id + "/handover";
        JsonNode h = ok(200, "GET", path, null);
        assertThat(h.get("method").asString()).isEqualTo("delivery");
        assertThat(h.get("address").asString()).isEqualTo("Plot 7, Site road");
        ok(200, "PATCH", path, Map.of("method", "pickup", "locationId", store));
        assertThat(ok(200, "GET", "/sales/orders/" + id, null).get("deliveryMethod").asString()).isEqualTo("collect");
        Map<String, Object> clearAddress = new HashMap<>();
        clearAddress.put("method", "delivery");
        clearAddress.put("address", null);
        clearAddress.put("status", "scheduled");
        assertThat(as("PATCH", path, clearAddress).status()).isEqualTo(400);

        assertThat(call("GET", path, null, otherToken).status()).isEqualTo(404);
        assertThat(as("GET", "/sales/invoices/" + UUID.randomUUID() + "/handover", null).status()).isEqualTo(404);
    }
}
