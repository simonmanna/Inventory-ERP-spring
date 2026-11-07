package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.sales.application.SalesRecurringOrderService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
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

/** SG-9: deliveries through /sales/deliveries only (no Fleet route), milestones, recurring orders, tax, numbering. */
class SalesDeliveryIT extends ApiTestSupport {

    static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    @Autowired
    SalesRecurringOrderService recurring;

    @Autowired
    StockService stock;

    @Autowired
    PlatformTransactionManager txm;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String stone;
    String yard;
    String customer;

    @BeforeAll
    void fixtures() {
        stone = ok(201, "POST", "/products", Map.of("code", "G9P-" + sfx, "name", "SG9 stone " + sfx, "productType", "stockable",
                "trackInventory", true, "salesPrice", 50, "costPrice", 20)).get("id").asString();
        yard = ok(201, "POST", "/inventory/locations", Map.of("code", "G9Y-" + sfx, "name", "SG9 yard", "type", "warehouse"))
                .get("id").asString();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "G9C-" + sfx, "SG9 cust " + sfx).update();
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> stock.receive(StockMove.of(stone, yard, new BigDecimal("1000")).unitCost(
                        new BigDecimal("20")))));
    }

    @AfterEach
    void reset() {
        ok(200, "PUT", "/sales/settings", Map.of("deliveryPostingApproval", false, "requireLineTax", false,
                "requireTaxNumberAbove", 0, "numberingScope", "organization"));
    }

    JsonNode money(String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res r = call("POST", path, body, h);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    JsonNode confirmed(int qty) {
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yard, "lines", List.of(Map.of(
                "productId", stone, "quantity", qty, "unitPrice", 50)))).get("id").asString();
        return ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
    }

    JsonNode deliver(String so, double gross, double tare, double delivered) {
        JsonNode d = ok(201, "POST", "/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "UAX 123", "driverName", "Okello",
                "gross", gross, "tare", tare));
        String id = d.get("id").asString();
        money("/sales/deliveries/" + id + "/issue", null);
        return money("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Site agent", "lines", List.of(Map.of("loadLineId",
                d.get("lines").get(0).get("id").asString(), "deliveredQty", delivered))));
    }

    @Test
    @DisplayName("40 + 35 + 25 t delivered on one order reaches 100%; net = gross − tare; stock issues and the order bills")
    void threeTrips() {
        JsonNode o = confirmed(100);
        String so = o.get("id").asString();
        JsonNode first = deliver(so, 55, 15, 40);
        assertThat(first.get("dispatchedQty").asDouble()).as("net = gross − tare").isEqualTo(40);
        deliver(so, 50, 15, 35);
        JsonNode last = deliver(so, 40, 15, 25);
        assertThat(last.get("status").asString()).isEqualTo("delivered");
        JsonNode line = ok(200, "GET", "/sales/deliveries/reconciliation?orderId=" + so, null).get(0);
        assertThat(line.get("percentDelivered").asDouble()).isEqualTo(100);
        assertThat(line.get("toInvoice").asDouble()).isEqualTo(100);
        assertThat(ok(200, "GET", "/sales/orders/" + so, null).get("deliveryStatus").asString()).isEqualTo("full");
        assertThat(jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(stone, yard).query(BigDecimal.class).single()).isEqualByComparingTo("900");
        JsonNode invoice = money("/sales/orders/" + so + "/invoices", Map.of());
        assertThat(invoice.get("totalAmount").asDouble()).isEqualTo(5000);
    }

    @Test
    @DisplayName("damaged and rejected quantities are recorded, rejected goes back to stock, damaged needs no second entry")
    void damagedRejected() {
        JsonNode o = confirmed(10);
        String so = o.get("id").asString();
        JsonNode d = ok(201, "POST", "/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "UAX 9"));
        String id = d.get("id").asString();
        BigDecimal before = stockNow();
        money("/sales/deliveries/" + id + "/issue", null);
        JsonNode done = money("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Agent", "lines", List.of(Map.of(
                "loadLineId", d.get("lines").get(0).get("id").asString(), "deliveredQty", 7, "damagedQty", 1, "rejectedQty", 2))));
        assertThat(done.get("damagedQty").asDouble()).isEqualTo(1);
        assertThat(done.get("rejectedQty").asDouble()).isEqualTo(2);
        assertThat(stockNow()).as("10 out, 2 rejected back").isEqualByComparingTo(before.subtract(new BigDecimal("8")));
        JsonNode failed = ok(200, "GET", "/sales/deliveries?failed=true&orderId=" + so, null);
        assertThat(failed.size()).isEqualTo(1);
        money("/sales/deliveries/" + id + "/photos", Map.of("dataUrl", PNG, "caption", "one"));
        JsonNode photos = money("/sales/deliveries/" + id + "/photos", Map.of("dataUrl", PNG, "caption", "two"));
        assertThat(photos.get("photos").size()).isEqualTo(2);
    }

    BigDecimal stockNow() {
        return jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(stone, yard).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("posting approval holds the stock issue until someone approves, not the creator")
    void postingApproval() {
        ok(200, "PUT", "/sales/settings", Map.of("deliveryPostingApproval", true));
        String so = confirmed(1).get("id").asString();
        String id = ok(201, "POST", "/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "UAX 7")).get("id").asString();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res r = call("POST", "/sales/deliveries/" + id + "/issue", null, h);
        assertThat(r.status()).isEqualTo(400);
        assertThat(as("POST", "/sales/deliveries/" + id + "/approve", null).status()).as("own delivery").isEqualTo(403);
        jdbc.sql("UPDATE \"FleetLoad\" SET \"createdBy\" = 'someone-else' WHERE id = ?").param(id).update();
        ok(200, "POST", "/sales/deliveries/" + id + "/approve", null);
        money("/sales/deliveries/" + id + "/issue", null);
    }

    @Test
    @DisplayName("milestones bill through down-payment invoices and cannot exceed the order total")
    void milestones() {
        JsonNode o = confirmed(10);
        String so = o.get("id").asString();
        assertThat(as("PUT", "/sales/orders/" + so + "/milestones", Map.of("milestones", List.of(Map.of("name", "A", "percent", 70),
                Map.of("name", "B", "percent", 40)))).status()).isEqualTo(400);
        JsonNode ms = ok(200, "PUT", "/sales/orders/" + so + "/milestones", Map.of("milestones", List.of(Map.of("name", "Mobilise",
                "percent", 30), Map.of("name", "Finish", "amount", 350))));
        assertThat(ms.get(0).get("amount").asDouble()).isEqualTo(150);
        String mid = ms.get(0).get("id").asString();
        JsonNode inv = money("/sales/orders/" + so + "/milestones/" + mid + "/invoice", null);
        assertThat(inv.get("totalAmount").asDouble()).isEqualTo(150);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", "/sales/orders/" + so + "/milestones/" + mid + "/invoice", null, h).status()).as("once").isEqualTo(400);
    }

    @Test
    @DisplayName("a recurring order drafts a copy when due and moves to the next date")
    void recurringOrders() {
        String so = confirmed(1).get("id").asString();
        String r = ok(201, "POST", "/sales/orders/" + so + "/recurring", Map.of("frequency", "monthly", "nextRunDate", LocalDate.now()
                .toString())).get("id").asString();
        int made = TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> recurring.runDue(LocalDate.now()));
        assertThat(made).isGreaterThanOrEqualTo(1);
        Map<String, Object> row = TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> new TransactionTemplate(txm).execute(
                t -> jdbc.sql("SELECT \"lastOrderId\", \"nextRunDate\" FROM \"SalesRecurringOrder\" WHERE id = ?").param(r).query()
                        .singleRow()));
        assertThat(ok(200, "GET", "/sales/orders/" + row.get("lastOrderId"), null).get("status").asString()).isEqualTo("draft");
        assertThat(row.get("nextRunDate").toString()).isEqualTo(LocalDate.now().plusMonths(1).toString());
    }

    @Test
    @DisplayName("tax validation blocks an invoice without tax or without the customer's tax number")
    void taxValidation() {
        JsonNode o = confirmed(1);
        String so = o.get("id").asString();
        jdbc.sql("UPDATE \"SalesOrderLine\" SET \"qtyBillable\" = 1 WHERE \"orderId\" = ?").param(so).update();
        ok(200, "PUT", "/sales/settings", Map.of("requireLineTax", true));
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", "/sales/orders/" + so + "/invoices", Map.of(), h).raw().body()).contains("needs a tax");
        ok(200, "PUT", "/sales/settings", Map.of("requireLineTax", false, "requireTaxNumberAbove", 1));
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", "/sales/orders/" + so + "/invoices", Map.of(), h).raw().body()).contains("tax number");
    }

    @Test
    @DisplayName("numbers per branch never repeat under concurrent creation")
    void numbering() throws Exception {
        String branch = ok(201, "POST", "/branches", Map.of("code", "G9" + sfx.substring(0, 4), "name", "SG9 B")).get("id").asString();
        ok(200, "PUT", "/sales/settings", Map.of("numberingScope", "branch"));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<String>> jobs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            jobs.add(() -> ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "branchId", branch, "lines", List.of()))
                    .get("number").asString());
        }
        List<String> numbers = new ArrayList<>();
        for (Future<String> f : pool.invokeAll(jobs)) {
            numbers.add(f.get());
        }
        pool.shutdown();
        assertThat(new HashSet<>(numbers)).hasSize(12);
        assertThat(numbers).allMatch(n -> n.startsWith("SO-G9" + sfx.substring(0, 4) + "-"));
    }

    @Test
    @DisplayName("delivery routes need their permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(com.inventoryerp.sales.web.SalesDeliveryController.class)).isGreaterThan(10);
    }
}
