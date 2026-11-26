package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Sales readiness R6 / R7 (2026-11-26): delivery losses out of COGS and rejected goods into quarantine (DEL-9), legal
 * invoice numbers at post (INV-11), firm reservations honoured at issue (DEL-8), delivery-note views not counted as
 * prints (DEL-12), returnable = accepted (DEL-10), the order-approve permission (SO-10), server-paged delivery list
 * (X-7), and the races of R5 (double issue, double receipt of one invoice).
 */
class SalesReadinessR6IT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    PlatformTransactionManager txm;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    final String today = LocalDate.now(java.time.ZoneId.of("Africa/Kampala")).toString();
    String customer;

    @BeforeAll
    void fixtures() {
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "R6C-" + sfx, "R6 cust " + sfx).update();
    }

    @AfterEach
    void reset() {
        ok(200, "PUT", "/sales/settings", Map.of("deliveryLossPosting", true, "rejectedToQuarantine", true));
    }

    /** A stocked product with {@code qty} on hand at cost 20 in a new yard; returns {product, yard}. */
    String[] stocked(int qty) {
        String code = UUID.randomUUID().toString().substring(0, 8);
        String product = ok(201, "POST", "/products", Map.of("code", "R6P-" + code, "name", "R6 stone " + code,
                "productType", "stockable", "trackInventory", true, "salesPrice", 50, "costPrice", 20)).get("id").asString();
        String yard = ok(201, "POST", "/inventory/locations", Map.of("code", "R6Y-" + code, "name", "R6 yard " + code,
                "type", "warehouse")).get("id").asString();
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> stock.receive(StockMove.of(product, yard, new BigDecimal(qty)).unitCost(
                        new BigDecimal("20")))));
        return new String[] {product, yard};
    }

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

    String confirmed(String product, String yard, int qty) {
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yard, "lines",
                List.of(Map.of("productId", product, "quantity", qty, "unitPrice", 50)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        return so;
    }

    JsonNode delivery(String so, Integer qty) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("vehiclePlate", "UAR 6" + sfx.substring(0, 2)));
        if (qty != null) {
            String line = ok(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("id").asString();
            body.put("lines", List.of(Map.of("lineId", line, "quantity", qty)));
        }
        return ok(201, "POST", "/sales/orders/" + so + "/deliveries", body);
    }

    BigDecimal onHand(String product, String location) {
        return jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(product, location).query(BigDecimal.class).single();
    }

    BigDecimal lossDebits(String tripId) {
        return jdbc.sql("SELECT COALESCE(sum(l.debit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                + " l.\"journalEntryId\" JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE e.\"sourceId\" = ? AND a.code = '5190'")
                .param(tripId).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("DEL-9: damaged cost moves from COGS to Delivery losses; rejected goods go to quarantine, not the yard")
    void lossAndQuarantine() {
        String[] p = stocked(50);
        String so = confirmed(p[0], p[1], 10);
        JsonNode d = delivery(so, null);
        String id = d.get("id").asString();
        money("/sales/deliveries/" + id + "/issue", null);
        money("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Agent", "lines", List.of(Map.of(
                "loadLineId", d.get("lines").get(0).get("id").asString(), "deliveredQty", 7, "damagedQty", 1,
                "rejectedQty", 2))));
        assertThat(onHand(p[0], p[1])).as("10 left the yard; nothing came back to it").isEqualByComparingTo("40");
        String quarantine = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code ="
                + " 'SALES-QUARANTINE'").param(demoOrg).query(String.class).single();
        assertThat(onHand(p[0], quarantine)).as("the 2 rejected are in quarantine").isEqualByComparingTo("2");
        assertThat(lossDebits(id)).as("1 damaged × cost 20").isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("DEL-9: switched off, rejected goods return to the yard and no loss is posted")
    void lossAndQuarantineOff() {
        ok(200, "PUT", "/sales/settings", Map.of("deliveryLossPosting", false, "rejectedToQuarantine", false));
        String[] p = stocked(50);
        String so = confirmed(p[0], p[1], 10);
        JsonNode d = delivery(so, null);
        String id = d.get("id").asString();
        money("/sales/deliveries/" + id + "/issue", null);
        money("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Agent", "lines", List.of(Map.of(
                "loadLineId", d.get("lines").get(0).get("id").asString(), "deliveredQty", 7, "damagedQty", 1,
                "rejectedQty", 2))));
        assertThat(onHand(p[0], p[1])).isEqualByComparingTo("42");
        assertThat(lossDebits(id)).isZero();
    }

    @Test
    @DisplayName("INV-11: drafts carry a temporary reference; numbers are taken at post with no gap for unposted drafts")
    void numberAtPost() {
        String svc = ok(201, "POST", "/products", Map.of("code", "R6S-" + sfx, "name", "R6 svc " + sfx, "productType",
                "service", "salesPrice", 100)).get("id").asString();
        Map<String, Object> body = Map.of("partnerId", customer, "issueDate", today, "lines", List.of(Map.of("productId",
                svc, "quantity", 1, "unitPrice", 100)));
        JsonNode a = ok(200, "POST", "/invoices", body);
        assertThat(a.get("documentNumber").asString()).startsWith("DRAFT-INV-");
        String first = ok(200, "POST", "/invoices/" + a.get("id").asString() + "/post", null).get("documentNumber")
                .asString();
        ok(200, "POST", "/invoices", body); // never posted: takes no number
        JsonNode c = ok(200, "POST", "/invoices", body);
        String second = ok(200, "POST", "/invoices/" + c.get("id").asString() + "/post", null).get("documentNumber")
                .asString();
        String prefix = "INV-" + LocalDate.parse(today).getYear() + "-";
        assertThat(first).startsWith(prefix);
        assertThat(Long.parseLong(second.substring(prefix.length())))
                .isEqualTo(Long.parseLong(first.substring(prefix.length())) + 1);
    }

    @Test
    @DisplayName("DEL-8: a delivery cannot issue stock firmly reserved for another order")
    void reservationsHonoured() {
        String[] p = stocked(10);
        confirmed(p[0], p[1], 8); // reserves 8 of the 10
        String other = confirmed(p[0], p[1], 5); // can reserve only 2
        JsonNode d = delivery(other, 5);
        Res r = call("POST", "/sales/deliveries/" + d.get("id").asString() + "/issue", null, key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.raw().body()).contains("reserved for other orders");
        assertThat(onHand(p[0], p[1])).isEqualByComparingTo("10");
        money("/sales/deliveries/" + d.get("id").asString() + "/cancel", Map.of("reason", "too much"));
        JsonNode small = delivery(other, 2);
        money("/sales/deliveries/" + small.get("id").asString() + "/issue", null);
        assertThat(onHand(p[0], p[1])).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("DEL-12: viewing the delivery note does not count as a print; printing does")
    void noteViewNotCounted() {
        String[] p = stocked(20);
        String so = confirmed(p[0], p[1], 3);
        String id = delivery(so, null).get("id").asString();
        money("/sales/deliveries/" + id + "/issue", null);
        int before = prints(id);
        assertThat(downloadBytes("/sales/deliveries/" + id + "/note").statusCode()).isEqualTo(200);
        assertThat(downloadBytes("/sales/deliveries/" + id + "/note").statusCode()).isEqualTo(200);
        assertThat(prints(id)).isEqualTo(before);
        assertThat(downloadBytes("/sales/deliveries/" + id + "/note?print=true").statusCode()).isEqualTo(200);
        assertThat(prints(id)).isEqualTo(Math.max(before, 0) + 1);
    }

    int prints(String tripId) {
        return jdbc.sql("SELECT \"deliveryNotePrints\" FROM \"FleetLoad\" WHERE id = ?").param(tripId).query(Integer.class)
                .single();
    }

    @Test
    @DisplayName("DEL-10: only what the customer accepted can be returned, not what was rejected or damaged")
    void returnableIsAccepted() {
        String[] p = stocked(20);
        String so = confirmed(p[0], p[1], 10);
        JsonNode d = delivery(so, null);
        String id = d.get("id").asString();
        money("/sales/deliveries/" + id + "/issue", null);
        money("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Agent", "lines", List.of(Map.of(
                "loadLineId", d.get("lines").get(0).get("id").asString(), "deliveredQty", 6, "damagedQty", 1,
                "rejectedQty", 3))));
        String line = ok(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("id").asString();
        Res tooMuch = call("POST", "/sales/returns", Map.of("orderId", so, "reason", "other", "lines", List.of(Map.of(
                "orderLineId", line, "quantity", 7))), key());
        assertThat(tooMuch.status()).as(tooMuch.raw().body()).isEqualTo(400);
        Res fine = call("POST", "/sales/returns", Map.of("orderId", so, "reason", "other", "lines", List.of(Map.of(
                "orderLineId", line, "quantity", 6))), key());
        assertThat(fine.status()).as(fine.raw().body()).isLessThan(300);
    }

    @Test
    @DisplayName("X-7: the delivery list pages on the server and reports the filter's total")
    void deliveryListPaged() {
        String[] p = stocked(20);
        String so = confirmed(p[0], p[1], 6);
        delivery(so, 2);
        delivery(so, 2);
        delivery(so, 2);
        Res r = call("GET", "/sales/deliveries?orderId=" + so + "&limit=2", null, key());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().size()).isEqualTo(2);
        assertThat(r.raw().headers().firstValue("X-Total-Count")).hasValue("3");
        assertThat(ok(200, "GET", "/sales/deliveries?orderId=" + so + "&limit=2&offset=2", null).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("SO-10: order approval needs sales:order:approve, not the quotation permission")
    void orderApprovePermission() {
        assertThat(jdbc.sql("SELECT count(*) FROM \"Permission\" WHERE key = 'sales:order:approve'").query(Long.class)
                .single()).isEqualTo(1);
        String token = tokenHolding("sales:order:read", "sales:quotation:approve");
        Res r = call("POST", "/sales/orders/" + UUID.randomUUID() + "/approve", Map.of(), token);
        assertThat(r.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("R5: two issues of one delivery at once take the stock once")
    void doubleIssue() throws Exception {
        String[] p = stocked(30);
        String so = confirmed(p[0], p[1], 5);
        String id = delivery(so, null).get("id").asString();
        List<Integer> codes = race(2, () -> call("POST", "/sales/deliveries/" + id + "/issue", null, key()).status());
        assertThat(codes.stream().filter(c -> c < 300).count()).as(codes.toString()).isEqualTo(1);
        assertThat(onHand(p[0], p[1])).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("R5: two receipts racing for one invoice never over-settle it")
    void doubleReceipt() throws Exception {
        String svc = ok(201, "POST", "/products", Map.of("code", "R6R-" + sfx, "name", "R6 rcpt " + sfx, "productType",
                "service", "salesPrice", 100)).get("id").asString();
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines", List.of(Map.of(
                "productId", svc, "quantity", 1, "unitPrice", 1000)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        List<Integer> codes = race(2, () -> call("POST", "/payments", Map.of("partnerId", customer, "paymentDate", today,
                "amount", 1000, "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", inv, "amount", 1000))),
                key()).status());
        assertThat(codes.stream().filter(c -> c < 300).count()).as(codes.toString()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?").param(inv)
                .query(BigDecimal.class).single()).isEqualByComparingTo("0");
    }

    static List<Integer> race(int n, Callable<Integer> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(task));
            }
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : fs) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
