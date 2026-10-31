package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Phase S5 of ADR-S011: sales order → fleet delivery order (only deliverable while released) → trips by a
 * transporter → dispatch issues stock and shrinks the reservation → POD shortage → approved discrepancy → accepted
 * and billable quantity with an immutable decision → manual billable override → fulfilment events in the inbox →
 * reconciliation repairs drift.
 */
class SalesFulfilmentIT extends ApiTestSupport {

    static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYP"
            + "hfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    @Autowired
    StockService stock;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String yard;
    String aggregate;
    String customer;
    String transporter;
    String rate;
    String approverToken;

    @BeforeAll
    void fixtures() {
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        yard = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Sales yard', 'warehouse', now())").params(yard, demoOrg, "SY-" + sfx).update();
        aggregate = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'stockable'::\"ProductType\", true, 'AVCO'::\"CostingMethod\", 30000, 50000, now())")
                .params(aggregate, demoOrg, "SF-" + sfx, "Aggregate " + sfx).update();
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).executeWithoutResult(
                s -> stock.receive(StockMove.of(aggregate, yard, new BigDecimal("100")).unitCost(new BigDecimal(
                        "30000")))));
        ok(200, "PUT", "/fleet/product-load-factors/" + aggregate, Map.of("tonnesPerUnit", 1));
        customer = partner("Contractor", false);
        transporter = partner("Haulier", true);
        rate = ok(201, "POST", "/fleet/transporter-rates", Map.of("partnerId", transporter, "name", "Run " + sfx,
                "basis", "per_trip", "rate", 100000)).get("id").asString();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Dispatch mgr " + sfx, new String[] {
                    "fleet_dispatch:read", "fleet_dispatch:approve_discrepancy", "fleet_trip:read"}).update();
        String email = "sf-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Disp@12345", role);
        approverToken = login("DEMO", email, "Disp@12345");
    }

    String partner(String name, boolean supplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, ?, ?, now())").params(id, demoOrg, "SF-" + UUID.randomUUID().toString()
                        .substring(0, 8), name + " " + sfx, supplier, !supplier).update();
        return id;
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key(adminToken));
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    Map<String, Object> pod(JsonNode trip, Object delivered, String reason) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("loadLineId", trip.get("lines").get(0).get("id").asString());
        l.put("deliveredQty", delivered);
        if (reason != null) {
            l.put("reason", reason);
        }
        return new LinkedHashMap<>(Map.of("receiverName", "Site agent", "receiverPhone", "+256700000001", "signature",
                PNG, "lines", List.of(l)));
    }

    JsonNode qty(String orderId) {
        return ok(200, "GET", "/sales/orders/" + orderId + "/fulfilment", null).get("lines").get(0);
    }

    BigDecimal reserved() {
        return jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockReservation\" WHERE \"productId\" = ? AND status ="
                + " 'active'").param(aggregate).query(BigDecimal.class).single();
    }

    String assign(String trip) {
        ok(201, "POST", "/fleet/trips/" + trip + "/assign", Map.of("carrier", "transporter", "transporterPartnerId",
                transporter, "transporterRateId", rate, "transporterPlate", "UAX 1" + sfx.substring(0, 2),
                "transporterDriverName", "Okello"));
        return trip;
    }

    @Test
    @DisplayName("SO → DO → dispatch (stock + reservation) → POD shortage → approved → accepted / billable → override")
    void endToEnd() throws Exception {
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yard, "lines", List.of(
                Map.of("productId", aggregate, "quantity", 50)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        assertThat(reserved()).isEqualByComparingTo("50");
        JsonNode d = ok(201, "POST", "/sales/orders/" + so + "/delivery-orders", Map.of());
        String doId = d.get("id").asString();
        assertThat(d.get("sourceType").asString()).isEqualTo("sales_order");
        assertThat(d.get("lines").get(0).get("orderedQty").asDouble()).isEqualTo(50);
        assertThat(as("POST", "/sales/orders/" + so + "/delivery-orders", Map.of()).status()).isEqualTo(409);
        assertThat(ok(200, "GET", "/sales/orders/" + so, null).get("relations").get("deliveries").size()).isEqualTo(1);
        JsonNode planned = ok(201, "POST", "/fleet/delivery-orders/" + doId + "/plan-loads", Map.of("payloadT", 30));
        String t1 = assign(planned.get("createdTripIds").get(0).asString());
        String t2 = assign(planned.get("createdTripIds").get(1).asString());

        // on hold: fleet refuses to dispatch
        ok(200, "POST", "/sales/orders/" + so + "/hold", Map.of("reason", "Site closed"));
        Res held = call("POST", "/fleet/trips/" + t1 + "/dispatch", null, key(adminToken));
        assertThat(held.status()).isEqualTo(400);
        assertThat(held.body().get("message").asString()).contains("on hold");
        ok(200, "POST", "/sales/orders/" + so + "/release", null);

        JsonNode d1 = money("/fleet/trips/" + t1 + "/dispatch", null);
        ok(200, "POST", "/sales/orders/" + so + "/rebuild", null);
        JsonNode q = qty(so);
        assertThat(q.get("qtyDispatched").asDouble()).isEqualTo(30);
        assertThat(q.get("qtyBillable").asDouble()).isZero();
        assertThat(reserved()).isEqualByComparingTo("20");
        JsonNode o = ok(200, "GET", "/sales/orders/" + so, null);
        assertThat(o.get("status").asString()).isEqualTo("in_fulfilment");
        assertThat(o.get("deliveryStatus").asString()).isEqualTo("partial");

        JsonNode delivered = money("/fleet/trips/" + t1 + "/proof-of-delivery", pod(d1, 29.5, "spillage"));
        String disc = delivered.get("discrepancies").get(0).get("id").asString();
        assertThat(call("POST", "/fleet/delivery-discrepancies/" + disc + "/approve", null, key(approverToken)).status())
                .isEqualTo(200);
        ok(200, "POST", "/sales/orders/" + so + "/rebuild", null);
        q = qty(so);
        assertThat(q.get("qtyReceived").asDouble()).isEqualTo(29.5);
        assertThat(q.get("qtyAccepted").asDouble()).isEqualTo(29.5);
        assertThat(q.get("qtyBillable").asDouble()).isEqualTo(29.5);
        assertThat(q.get("decisions").get(0).get("basis").asString()).isEqualTo("accepted");

        // manual override (needs reason), recorded and sticky
        assertThat(as("POST", "/sales/orders/" + so + "/lines/" + q.get("id").asString() + "/billable", Map.of(
                "quantity", 30)).status()).isEqualTo(400);
        JsonNode over = ok(200, "POST", "/sales/orders/" + so + "/lines/" + q.get("id").asString() + "/billable", Map.of(
                "quantity", 30, "reason", "Customer agreed to weighbridge weight"));
        assertThat(over.get("lines").get(0).get("qtyBillable").asDouble()).isEqualTo(30);
        assertThat(over.get("lines").get(0).get("decisions").get(0).get("basis").asString()).isEqualTo("manual");
        ok(200, "POST", "/sales/orders/" + so + "/rebuild", null);
        assertThat(qty(so).get("qtyBillable").asDouble()).isEqualTo(30);

        JsonNode d2 = money("/fleet/trips/" + t2 + "/dispatch", null);
        money("/fleet/trips/" + t2 + "/proof-of-delivery", pod(d2, 20, null));
        // event-driven projection (outbox worker): wait for the inbox
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && qty(so).get("qtyDispatched").asDouble() < 50) {
            Thread.sleep(200);
        }
        q = qty(so);
        assertThat(q.get("qtyDispatched").asDouble()).isEqualTo(50);
        assertThat(reserved()).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM \"SalesProcessedEvent\" WHERE \"orderId\" = ?").param(so)
                .query(Long.class).single()).isPositive();
        JsonNode done = ok(200, "GET", "/sales/orders/" + so, null);
        assertThat(done.get("deliveryStatus").asString()).isEqualTo("full");
        assertThat(done.get("invoiceStatus").asString()).isEqualTo("to_invoice");
    }

    @Test
    @DisplayName("reconciliation repairs a tampered projection and records the drift; policy guard")
    void reconciliation() {
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yard, "lines", List.of(
                Map.of("productId", aggregate, "quantity", 5)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        String doId = ok(201, "POST", "/sales/orders/" + so + "/delivery-orders", Map.of()).get("id").asString();
        String t = assign(ok(201, "POST", "/fleet/delivery-orders/" + doId + "/plan-loads", Map.of("payloadT", 30))
                .get("createdTripIds").get(0).asString());
        money("/fleet/trips/" + t + "/dispatch", null);
        // The dispatch reaches the order through the outbox: wait for the projection before tampering with it.
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && qty(so).get("qtyDispatched").asDouble() < 5) {
            drainOutboxFully();
        }
        ok(200, "POST", "/sales/orders/" + so + "/rebuild", null);
        jdbc.sql("UPDATE \"SalesOrderLine\" SET \"qtyDispatched\" = 1 WHERE \"orderId\" = ?").param(so).update();
        JsonNode fixed = ok(200, "POST", "/sales/orders/" + so + "/rebuild", null);
        assertThat(fixed.size()).isEqualTo(1);
        assertThat(qty(so).get("qtyDispatched").asDouble()).isEqualTo(5);
        assertThat(as("PUT", "/sales/settings", Map.of("physicalMovement", "via_transit", "ownershipTransfer",
                "at_delivery")).body().get("message").asString()).contains("accountant");
    }
}
