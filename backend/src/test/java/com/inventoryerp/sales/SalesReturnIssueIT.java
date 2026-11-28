package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S094 C2, §7.2: a return names the delivery (load line) its goods come back from, comes back at that delivery's
 * issue cost, by serial number for serial-tracked goods, and records the condition found (RT group).
 */
class SalesReturnIssueIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    PlatformTransactionManager txm;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String item;
    String yard;
    String quarantine;
    String customer;
    String adminId;

    String product(String code, boolean serials) {
        Map<String, Object> p = new LinkedHashMap<>(Map.of("code", code + sfx, "name", "RT " + code + " " + sfx,
                "productType", "stockable", "trackInventory", true, "salesPrice", 100, "costPrice", 30));
        if (serials) {
            p.put("serialTracking", true);
        }
        return ok(201, "POST", "/products", p).get("id").asString();
    }

    void receive(String product, BigDecimal qty, BigDecimal cost, List<String> serials) {
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> {
                    StockMove m = StockMove.of(product, yard, qty).unitCost(cost);
                    stock.receive(serials.isEmpty() ? m : m.serials(serials));
                }));
    }

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        yard = ok(201, "POST", "/inventory/locations", Map.of("code", "RTY-" + sfx, "name", "RT yard " + sfx, "type",
                "warehouse")).get("id").asString();
        quarantine = ok(201, "POST", "/inventory/locations", Map.of("code", "RTQ-" + sfx, "name", "RT quarantine " + sfx,
                "type", "warehouse")).get("id").asString();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "RTC-" + sfx, "RT cust " + sfx).update();
        item = product("RTI-", false);
        receive(item, new BigDecimal("1000"), new BigDecimal("30"), List.of());
    }

    // ------------------------------------------------------------------ helpers

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    String refused(String path, Object body) {
        Res r = call("POST", path, body, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(400);
        return r.body().get("message").asString();
    }

    String order(String product, int qty) {
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yard, "lines",
                List.of(Map.of("productId", product, "quantity", qty, "unitPrice", 100)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        return so;
    }

    String lineId(String so) {
        return ok(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("id").asString();
    }

    String description(String so) {
        return ok(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("description").asString();
    }

    /** One delivery of {@code qty}, issued (with serials) and accepted in full; returns its load line. */
    String deliver(String so, int qty, List<String> serials) {
        JsonNode d = ok(201, "POST", "/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "UAR 2", "lines",
                List.of(Map.of("lineId", lineId(so), "quantity", qty))));
        String id = d.get("id").asString();
        String loadLine = d.get("lines").get(0).get("id").asString();
        money("/sales/deliveries/" + id + "/issue", serials.isEmpty() ? null : Map.of("lines", List.of(Map.of(
                "loadLineId", loadLine, "serialNumbers", serials))));
        money("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Agent", "lines", List.of(Map.of(
                "loadLineId", loadLine, "deliveredQty", qty))));
        drainOutboxFully();
        return loadLine;
    }

    Map<String, Object> request(String so, int qty, String loadLineId) {
        Map<String, Object> line = new LinkedHashMap<>(Map.of("orderLineId", lineId(so), "quantity", qty));
        if (loadLineId != null) {
            line.put("loadLineId", loadLineId);
        }
        return Map.of("orderId", so, "reason", "damaged", "lines", List.of(line));
    }

    /** An approved return of {@code qty} from {@code loadLineId} (null: let the server choose). */
    String approvedReturn(String so, int qty, String loadLineId) {
        String id = ok(201, "POST", "/sales/returns", request(so, qty, loadLineId)).get("id").asString();
        ok(200, "POST", "/sales/returns/" + id + "/approve", null);
        return id;
    }

    JsonNode returnLine(String returnId) {
        return ok(200, "GET", "/sales/returns/" + returnId, null).get("lines").get(0);
    }

    Map<String, Double> returnableByLoadLine(String so) {
        Map<String, Double> out = new LinkedHashMap<>();
        JsonNode lines = ok(200, "GET", "/sales/returns/returnable?orderId=" + so, null);
        for (JsonNode d : lines.get(0).get("deliveries")) {
            out.put(d.get("loadLineId").asString(), d.get("returnable").asDouble());
        }
        return out;
    }

    // ------------------------------------------------------------------ RT

    @Test
    @DisplayName("RT-1 / RT-2 a return names the delivery its goods come back from: the only one by default, a choice"
            + " when there are several, never more than is left on it")
    void deliveryReference() {
        String so = order(item, 10);
        String first = deliver(so, 4, List.of());
        String second = deliver(so, 6, List.of());
        assertThat(returnableByLoadLine(so)).containsEntry(first, 4.0).containsEntry(second, 6.0);
        String desc = description(so);

        assertThat(call("POST", "/sales/returns", request(so, 1, null), key()).body().get("message").asString())
                .isEqualTo("Choose the delivery " + desc + " comes back from");
        assertThat(call("POST", "/sales/returns", request(so, 1, UUID.randomUUID().toString()), key()).body()
                .get("message").asString()).isEqualTo(desc + " was not delivered on that delivery");
        assertThat(call("POST", "/sales/returns", request(so, 5, first), key()).body().get("message").asString())
                .startsWith("Quantity of " + desc + " must not exceed 4 (delivered on ");

        String r = approvedReturn(so, 3, first);
        JsonNode line = returnLine(r);
        assertThat(line.get("loadLineId").asString()).isEqualTo(first);
        assertThat(line.get("loadCode").isNull()).isFalse();
        assertThat(returnableByLoadLine(so)).containsEntry(first, 1.0).containsEntry(second, 6.0);
        assertThat(call("POST", "/sales/returns", request(so, 2, first), key()).status()).isEqualTo(400);

        // received short: what did not come back can come back on another return
        String line1 = line.get("id").asString();
        money("/sales/returns/" + r + "/receive", Map.of("lines", List.of(Map.of("lineId", line1, "receivedQty", 2))));
        assertThat(returnableByLoadLine(so)).containsEntry(first, 2.0);

        String only = order(item, 5);
        String loadLine = deliver(only, 5, List.of());
        assertThat(returnLine(approvedReturn(only, 2, null)).get("loadLineId").asString())
                .as("the only delivery is chosen by default").isEqualTo(loadLine);
    }

    @Test
    @DisplayName("RT-4 the goods go back at the cost of the delivery they left on, not today's average")
    void issueCostPerDelivery() {
        String p = product("RTC-", false);
        receive(p, new BigDecimal("100"), new BigDecimal("30"), List.of());
        String so = order(p, 10);
        String cheap = deliver(so, 5, List.of());
        receive(p, new BigDecimal("100"), new BigDecimal("90"), List.of());
        String dear = deliver(so, 5, List.of());

        String fromDear = approvedReturn(so, 2, dear);
        money("/sales/returns/" + fromDear + "/receive", Map.of());
        // AVCO at the second issue: (95 × 30 + 100 × 90) / 195
        assertThat(returnLine(fromDear).get("issueUnitCost").asDouble()).isCloseTo(60.77, within(0.01));

        String fromCheap = approvedReturn(so, 2, cheap);
        money("/sales/returns/" + fromCheap + "/receive", Map.of());
        assertThat(returnLine(fromCheap).get("issueUnitCost").asDouble()).isEqualTo(30);
    }

    @Test
    @DisplayName("RT-3 serial-tracked goods come back by serial number: each issued on that delivery, and only once")
    void serials() {
        String p = product("RTS-", true);
        List<String> serials = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            serials.add("RTS-" + sfx + "-" + i);
        }
        receive(p, new BigDecimal("3"), new BigDecimal("300"), serials);
        String s1 = serials.get(0);
        String s2 = serials.get(1);
        String s3 = serials.get(2);
        String so = order(p, 2);
        String loadLine = deliver(so, 2, List.of(s1, s2));
        assertThat(ok(200, "GET", "/sales/returns/returnable?orderId=" + so, null).get(0).get("deliveries").get(0)
                .get("serialNumbers").toString()).contains(s1, s2).doesNotContain(s3);
        String desc = description(so);

        String r = approvedReturn(so, 1, loadLine);
        String line = returnLine(r).get("id").asString();
        assertThat(refused("/sales/returns/" + r + "/receive", Map.of())).isEqualTo("Name the serial number of " + desc
                + " that came back");
        assertThat(refused("/sales/returns/" + r + "/receive", Map.of("lines", List.of(Map.of("lineId", line,
                "serialNumbers", List.of(s3)))))).isEqualTo("Serial number " + s3 + " was not delivered on this delivery");
        money("/sales/returns/" + r + "/receive", Map.of("lines", List.of(Map.of("lineId", line, "serialNumbers",
                List.of(s1)))));
        assertThat(returnLine(r).get("serialNumbers").toString()).contains(s1).doesNotContain(s2);

        String again = approvedReturn(so, 1, loadLine);
        String againLine = returnLine(again).get("id").asString();
        assertThat(refused("/sales/returns/" + again + "/receive", Map.of("lines", List.of(Map.of("lineId", againLine,
                "serialNumbers", List.of(s1)))))).isEqualTo("Serial number " + s1 + " was already returned");
        money("/sales/returns/" + again + "/receive", Map.of("lines", List.of(Map.of("lineId", againLine,
                "serialNumbers", List.of(s2)))));
        assertThat(returnableByLoadLine(so)).containsEntry(loadLine, 0.0);
    }

    @Test
    @DisplayName("RT-6 inspection records the condition found; damaged or defective goods never go back to sellable"
            + " stock")
    void condition() {
        String so = order(item, 3);
        deliver(so, 3, List.of());
        String r = approvedReturn(so, 3, null);
        money("/sales/returns/" + r + "/receive", Map.of());
        String line = returnLine(r).get("id").asString();
        String desc = description(so);
        assertThat(refused("/sales/returns/" + r + "/inspect", Map.of("lines", List.of(Map.of("lineId", line,
                "disposition", "restock", "condition", "damaged"))))).isEqualTo("Damaged or defective goods cannot go back"
                        + " to sellable stock: send " + desc + " to quarantine or scrap");
        assertThat(refused("/sales/returns/" + r + "/inspect", Map.of("lines", List.of(Map.of("lineId", line,
                "disposition", "restock", "condition", "broken"))))).isEqualTo("condition must be one of: damaged,"
                        + " defective, usable");
        money("/sales/returns/" + r + "/inspect", Map.of("lines", List.of(Map.of("lineId", line, "disposition",
                "damaged", "locationId", quarantine))));
        assertThat(returnLine(r).get("condition").asString()).as("from the disposition").isEqualTo("damaged");

        String so2 = order(item, 1);
        deliver(so2, 1, List.of());
        String r2 = approvedReturn(so2, 1, null);
        money("/sales/returns/" + r2 + "/receive", Map.of());
        money("/sales/returns/" + r2 + "/inspect", Map.of());
        assertThat(returnLine(r2).get("condition").asString()).as("restocked goods are usable").isEqualTo("usable");
    }

    @Test
    @DisplayName("authorization: what can come back is read with the return permission")
    void authorization() {
        String so = order(item, 1);
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "RT reader " + sfx,
                new String[] {"sales:order:read", "sales:order:read_all"}).update();
        String email = "rt-reader-" + sfx + "@demo.test";
        createUser(demoOrg, email, "Returns@12345", role);
        String token = login("DEMO", email, "Returns@12345");
        assertThat(call("GET", "/sales/returns/returnable?orderId=" + so, null, token).status()).isEqualTo(403);
        assertThat(ok(200, "GET", "/sales/returns/returnable?orderId=" + so, null).get(0).get("deliveries").size())
                .isZero();
    }
}
