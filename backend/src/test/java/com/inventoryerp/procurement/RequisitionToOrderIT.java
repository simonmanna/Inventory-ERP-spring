package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.procurement.web.RequisitionToOrderController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ADR-PROCUREMENT-001 wave 3: approved request lines → orders (partial, multi-supplier, locked against double
 * ordering), request conversion / cancellation, and the order lifecycle after approval (send, acknowledge,
 * short-close, close) with the open-orders report.
 */
class RequisitionToOrderIT extends ApiTestSupport {

    String warehouse;
    String supplierA;
    String supplierB;

    @BeforeAll
    void fixtures() {
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "RW-" + suffix(), "name", "Req wh " + suffix(),
                "type", "warehouse")).get("id").asString();
        supplierA = supplier("Quarry Parts");
        supplierB = supplier("Cement Depot");
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String supplier(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "SUP-" + suffix(), name + " " + suffix())
                .update();
        return id;
    }

    String product(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true,"
                + " 'AVCO', 0, 0, now())").params(id, demoOrg, "RQ-" + suffix(), name + " " + suffix()).update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    /** Draft → submitted → approved request; returns the request detail. */
    JsonNode approvedRequest(List<Map<String, Object>> lines) {
        String id = ok(201, "POST", "/procurement/purchase-requests", Map.of("description", "Crusher spares", "lines",
                lines)).get("id").asString();
        ok(200, "PATCH", "/procurement/purchase-requests/" + id + "/submit", null);
        ok(200, "PATCH", "/procurement/purchase-requests/" + id + "/approve", null);
        return ok(200, "GET", "/procurement/purchase-requests/" + id, null);
    }

    Res order(String partner, List<Map<String, Object>> lines) {
        return call("POST", "/procurement/sourcing/orders", Map.of("partnerId", partner, "warehouseId", warehouse,
                "currencyCode", "UGX", "lines", lines), key());
    }

    String lineId(JsonNode pr, int idx) {
        return pr.get("lines").get(idx).get("id").asString();
    }

    @Test
    @DisplayName("workbench: approved lines are ordered partially from two suppliers; the request converts when done")
    void requestLinesBecomeOrders() {
        String bearing = product("Bearing");
        String belt = product("Conveyor belt");
        String need = LocalDate.now().plusDays(7).toString();
        JsonNode pr = approvedRequest(List.of(
                Map.of("productId", bearing, "description", "Bearing 6205", "quantity", 4, "estimatedUnitPrice", 50000,
                        "requiredDate", need, "preferredSupplierId", supplierA),
                Map.of("productId", belt, "description", "Belt 800mm", "quantity", 10, "estimatedUnitPrice", 90000)));
        String prId = pr.get("id").asString();

        JsonNode open = ok(200, "GET", "/procurement/sourcing/open-lines?requestId=" + prId, null);
        assertThat(open).hasSize(2);
        assertThat(open.get(0).get("suggestedSupplier").get("id").asString()).isEqualTo(supplierA);
        assertThat(open.get(0).get("requiredDate").asString()).isEqualTo(need);

        Res first = order(supplierA, List.of(Map.of("requestLineId", lineId(pr, 0)), Map.of("requestLineId",
                lineId(pr, 1), "quantity", 4, "unitPrice", 88000)));
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        assertThat(first.body().get("requestId").asString()).isEqualTo(prId);
        String po1 = first.body().get("id").asString();
        JsonNode life = ok(200, "GET", "/procurement/purchase-orders/" + po1 + "/lifecycle", null);
        assertThat(life.get("lines").findValuesAsString("requestLineId")).containsExactlyInAnyOrder(lineId(pr, 0),
                lineId(pr, 1));
        assertThat(ok(200, "GET", "/procurement/purchase-requests/" + prId, null).get("status").asString())
                .isEqualTo("approved");

        JsonNode rest = ok(200, "GET", "/procurement/sourcing/open-lines?requestId=" + prId, null);
        assertThat(rest).hasSize(1);
        assertThat(new BigDecimal(rest.get(0).get("openQuantity").asString())).isEqualByComparingTo("6");
        Res over = order(supplierB, List.of(Map.of("requestLineId", lineId(pr, 1), "quantity", 7)));
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().get("message").asString()).startsWith("Only 6 of \"Belt 800mm\"");
        assertThat(order(supplierB, List.of(Map.of("requestLineId", lineId(pr, 1), "quantity", 6))).status())
                .isEqualTo(200);
        assertThat(ok(200, "GET", "/procurement/purchase-requests/" + prId, null).get("status").asString())
                .isEqualTo("converted");
        assertThat(ok(200, "GET", "/procurement/sourcing/open-lines?requestId=" + prId, null)).isEmpty();

        // A draft request cannot be ordered.
        String draft = ok(201, "POST", "/procurement/purchase-requests", Map.of("lines", List.of(Map.of("description",
                "x", "quantity", 1)))).get("id").asString();
        String draftLine = ok(200, "GET", "/procurement/purchase-requests/" + draft, null).get("lines").get(0).get("id")
                .asString();
        Res fromDraft = order(supplierA, List.of(Map.of("requestLineId", draftLine)));
        assertThat(fromDraft.body().get("message").asString()).endsWith("only approved requests can be ordered");
        assertThat(ok(200, "POST", "/procurement/purchase-requests/" + draft + "/cancel", Map.of("reason", "dup"))
                .get("status").asString()).isEqualTo("cancelled");
        Res cancelOrdered = as("POST", "/procurement/purchase-requests/" + prId + "/cancel", Map.of());
        assertThat(cancelOrdered.body().get("message").asString())
                .isEqualTo("This request already has orders — cancel or short-close the orders instead");
    }

    @Test
    @DisplayName("concurrency: two buyers ordering the same request line at once — only one order is created")
    void requestLineCannotBeOrderedTwice() throws Exception {
        String bolt = product("Bolt");
        JsonNode pr = approvedRequest(List.of(Map.of("productId", bolt, "description", "Bolt M12", "quantity", 100,
                "estimatedUnitPrice", 500)));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<Res>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                results.add(pool.submit(() -> order(supplierA, List.of(Map.of("requestLineId", lineId(pr, 0))))));
            }
            int ok = 0;
            for (Future<Res> f : results) {
                if (f.get().status() == 200) {
                    ok++;
                }
            }
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM \"PurchaseOrder\" WHERE \"requestId\" = ?")
                .param(pr.get("id").asString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("lifecycle: send, acknowledge (overdue report), short-close returns the balance to the request")
    void orderLifecycle() {
        String chain = product("Drill bit");
        JsonNode pr = approvedRequest(List.of(Map.of("productId", chain, "description", "Drill bit 45mm", "quantity", 10,
                "estimatedUnitPrice", 120000)));
        String po = order(supplierA, List.of(Map.of("requestLineId", lineId(pr, 0)))).body().get("id").asString();
        JsonNode sent = ok(200, "POST", "/procurement/purchase-orders/" + po + "/send", null);
        assertThat(sent.get("stage").asString()).isEqualTo("sent");
        String past = LocalDate.now().minusDays(3).toString();
        JsonNode ack = ok(200, "POST", "/procurement/purchase-orders/" + po + "/acknowledge", Map.of("supplierReference",
                "SO-77", "promisedDate", past));
        assertThat(ack.get("stage").asString()).isEqualTo("confirmed");
        assertThat(ack.get("supplierReference").asString()).isEqualTo("SO-77");
        JsonNode overdue = ok(200, "GET", "/procurement/reports/open-orders?overdueOnly=true&partnerId=" + supplierA,
                null);
        assertThat(overdue.findValuesAsString("purchaseOrderId")).contains(po);
        JsonNode late = null;
        for (JsonNode r : overdue) {
            if (po.equals(r.get("purchaseOrderId").asString())) {
                late = r;
            }
        }
        assertThat(late.get("daysLate").asInt()).isEqualTo(3);

        Res recv = call("POST", "/procurement/purchase-orders/" + po + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("productId", chain, "description", "Drill bit", "quantity", 6))), key());
        assertThat(recv.status()).as(recv.raw().body()).isEqualTo(200);
        Res noReason = call("POST", "/procurement/purchase-orders/" + po + "/short-close", Map.of(), key());
        assertThat(noReason.body().get("message").asString()).isEqualTo("A reason is required to short-close an order");
        JsonNode closed = call("POST", "/procurement/purchase-orders/" + po + "/short-close", Map.of("reason",
                "Supplier out of stock"), key()).body();
        assertThat(closed.get("status").asString()).isEqualTo("closed");
        assertThat(new BigDecimal(closed.get("lines").get(0).get("cancelledQuantity").asString())).isEqualByComparingTo("4");
        // The undelivered 4 go back to the request for sourcing elsewhere.
        JsonNode reopened = ok(200, "GET", "/procurement/sourcing/open-lines?requestId=" + pr.get("id").asString(), null);
        assertThat(new BigDecimal(reopened.get(0).get("openQuantity").asString())).isEqualByComparingTo("4");
        Res recvClosed = call("POST", "/procurement/purchase-orders/" + po + "/receive", Map.of("warehouseId",
                warehouse, "lines", List.of(Map.of("productId", chain, "description", "Drill bit", "quantity", 1))), key());
        assertThat(recvClosed.body().get("message").asString()).isEqualTo("PO is in status \"closed\" — cannot receive");
        Res sendClosed = as("POST", "/procurement/purchase-orders/" + po + "/send", null);
        assertThat(sendClosed.status()).isEqualTo(400);

        // A fully received order can be closed.
        JsonNode pr2 = approvedRequest(List.of(Map.of("productId", chain, "description", "Drill bit", "quantity", 1,
                "estimatedUnitPrice", 1)));
        String po2 = order(supplierA, List.of(Map.of("requestLineId", lineId(pr2, 0)))).body().get("id").asString();
        Res openClose = as("POST", "/procurement/purchase-orders/" + po2 + "/close", null);
        assertThat(openClose.body().get("message").asString())
                .isEqualTo("Only a fully received order can be closed — short-close an open order instead");
        call("POST", "/procurement/purchase-orders/" + po2 + "/receive", Map.of("warehouseId", warehouse, "lines",
                List.of(Map.of("productId", chain, "description", "Drill bit", "quantity", 1))), key());
        assertThat(ok(200, "POST", "/procurement/purchase-orders/" + po2 + "/close", null).get("status").asString())
                .isEqualTo("closed");
    }

    @Test
    @DisplayName("tenant isolation + authorization matrix for the requisition-to-order routes")
    void isolationAndAuthorization() {
        String p = product("Isolated");
        JsonNode pr = approvedRequest(List.of(Map.of("productId", p, "description", "x", "quantity", 1)));
        assertThat(call("GET", "/procurement/sourcing/open-lines?requestId=" + pr.get("id").asString(), null, otherToken)
                .body()).isEmpty();
        Map<String, String> other = new LinkedHashMap<>(Map.of("Authorization", "Bearer " + otherToken,
                "Idempotency-Key", UUID.randomUUID().toString()));
        Res foreign = call("POST", "/procurement/sourcing/orders", Map.of("partnerId", supplierA, "warehouseId",
                warehouse, "lines", List.of(Map.of("requestLineId", lineId(pr, 0)))), other);
        assertThat(foreign.status()).isIn(400, 404);
        assertThat(assertAuthorizationMatrix(RequisitionToOrderController.class)).isEqualTo(9);
    }
}
