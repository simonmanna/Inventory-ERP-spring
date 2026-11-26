package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Sales readiness R5 (reviewer's lifecycle list): one sales order taken through two partial deliveries, two partial
 * invoices, a deposit applied across both, a partial payment, a credit note on the partly paid invoice, a cancel refused
 * after allocation, a refund and a payment void. After every money step the order-line buckets (ordered, delivered,
 * invoiced), stock, the deposit subledger, the AR subledger and the trial balance must agree with the ledger,
 * and each invoice posts exactly one journal entry. The race tests (P1-8) fire two money requests at once with different
 * Idempotency-Keys, so only row locks and status guards can stop the second one.
 */
class SalesLifecycleIntegrityIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    final String today = LocalDate.now(ZoneId.of("Africa/Kampala")).toString();
    SalesLedgerAssertions ledger;
    String stone;
    String yard;

    @BeforeAll
    void fixtures() {
        ledger = new SalesLedgerAssertions(jdbc, demoOrg);
        stone = ok(201, "POST", "/products", Map.of("code", "LC-" + sfx, "name", "Lifecycle stone " + sfx, "productType",
                "stockable", "trackInventory", true, "salesPrice", 50, "costPrice", 20)).get("id").asString();
        yard = ok(201, "POST", "/inventory/locations", Map.of("code", "LCY-" + sfx, "name", "Lifecycle yard " + sfx,
                "type", "warehouse")).get("id").asString();
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> stock.receive(StockMove.of(stone, yard, new BigDecimal("1000")).unitCost(
                        new BigDecimal("20")))));
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\") VALUES (?, ?,"
                + " ?, ?, true, now())").params(id, demoOrg, "LCC-" + UUID.randomUUID().toString().substring(0, 8),
                        "Lifecycle cust " + sfx).update();
        return id;
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

    JsonNode deliver(String so, double gross, double tare, double delivered) {
        JsonNode d = ok(201, "POST", "/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "UAL 1", "driverName",
                "Okello", "gross", gross, "tare", tare));
        String id = d.get("id").asString();
        money("/sales/deliveries/" + id + "/issue", null);
        return money("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Site agent", "lines", List.of(Map.of(
                "loadLineId", d.get("lines").get(0).get("id").asString(), "deliveredQty", delivered))));
    }

    JsonNode qty(String so) {
        return ok(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("qty");
    }

    void await(String so, BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(150);
        }
        assertThat(cond.getAsBoolean()).as("condition within 15 s; qty " + qty(so)).isTrue();
    }

    double residual(String doc) {
        return ok(200, "GET", "/invoices/" + doc, null).get("amountResidual").asDouble();
    }

    BigDecimal depositSubledger(String partner) {
        return jdbc.sql("SELECT COALESCE(sum(amount - \"appliedAmount\" - \"refundedAmount\"), 0) FROM \"CustomerDeposit\""
                + " WHERE \"partnerId\" = ? AND status = 'open'").param(partner).query(BigDecimal.class).single();
    }

    /** Deposit GL (credit balance) = open deposits; AR GL = open documents; whole ledger balanced. */
    void tiesOut(String c) {
        assertThat(ledger.balance("customer_deposit", c).negate()).as("deposit GL = subledger")
                .isEqualByComparingTo(depositSubledger(c));
        ledger.receivableTiesOut(c);
        ledger.trialBalanceZero();
    }

    long entries(String sourceId) {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceId\" = ?")
                .params(demoOrg, sourceId).query(Long.class).single();
    }

    BigDecimal onHand() {
        return (BigDecimal) ledger.stock(stone, yard).get("qty");
    }

    @Test
    @DisplayName("SO 100 → deliver 40 + 60 → invoice 40 + 60 → deposit across both → part pay → credit 10 → refund → void")
    void fullLifecycle() throws Exception {
        String previous = ok(200, "GET", "/sales/settings", null).get("depositApplication").asString();
        ok(200, "PUT", "/sales/settings", Map.of("depositApplication", "manual"));
        try {
            String c = customer();
            BigDecimal stock0 = onHand();
            String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "locationId", yard, "lines", List.of(Map.of(
                    "productId", stone, "quantity", 100, "unitPrice", 50)))).get("id").asString();
            ok(200, "POST", "/sales/orders/" + so + "/confirm", null);

            // deposit on the order: no AR, deposit liability only
            String dep = money("/sales/orders/" + so + "/deposits", Map.of("amount", 3000, "paymentMethod", "bank"))
                    .get("id").asString();
            assertThat(depositSubledger(c)).isEqualByComparingTo("3000");
            tiesOut(c);

            // first partial delivery: ordered ≠ delivered ≠ invoiced
            deliver(so, 55, 15, 40);
            JsonNode q = qty(so);
            assertThat(q.get("ordered").asDouble()).isEqualTo(100);
            assertThat(q.get("delivered").asDouble()).isEqualTo(40);
            assertThat(q.get("grossInvoiced").asDouble()).isZero();
            assertThat(q.get("remaining").asDouble()).isEqualTo(60);
            assertThat(onHand()).isEqualByComparingTo(stock0.subtract(new BigDecimal("40")));

            // first partial invoice: only what was delivered
            JsonNode inv1 = money("/sales/orders/" + so + "/invoices", Map.of("mode", "regular"));
            String i1 = inv1.get("id").asString();
            assertThat(inv1.get("totalAmount").asDouble()).isEqualTo(2000);
            money("/invoices/" + i1 + "/post", null);
            await(so, () -> qty(so).get("grossInvoiced").asDouble() == 40);
            assertThat(entries(i1)).as("one journal entry for invoice 1").isEqualTo(1);
            assertThat(onHand()).as("posting a delivered invoice does not issue stock again")
                    .isEqualByComparingTo(stock0.subtract(new BigDecimal("40")));
            assertThat(call("POST", "/sales/orders/" + so + "/invoices", Map.of("mode", "regular"), key()).status())
                    .as("nothing more to bill before the next delivery").isEqualTo(400);

            // deposit applied to invoice 1, 1,000 left for invoice 2
            money("/sales/orders/" + so + "/apply-deposits", Map.of("documentId", i1));
            assertThat(residual(i1)).isZero();
            assertThat(depositSubledger(c)).isEqualByComparingTo("1000");
            tiesOut(c);

            // second delivery completes the order; second invoice bills the rest
            deliver(so, 75, 15, 60);
            assertThat(qty(so).get("delivered").asDouble()).isEqualTo(100);
            assertThat(onHand()).isEqualByComparingTo(stock0.subtract(new BigDecimal("100")));
            String i2 = money("/sales/orders/" + so + "/invoices", Map.of("mode", "regular")).get("id").asString();
            assertThat(ok(200, "GET", "/invoices/" + i2, null).get("totalAmount").asDouble()).isEqualTo(3000);
            money("/invoices/" + i2 + "/post", null);
            await(so, () -> qty(so).get("grossInvoiced").asDouble() == 100);
            assertThat(entries(i2)).as("one journal entry for invoice 2").isEqualTo(1);
            money("/sales/orders/" + so + "/apply-deposits", Map.of("documentId", i2));
            assertThat(residual(i2)).isEqualTo(2000);
            assertThat(depositSubledger(c)).isZero();
            JsonNode deposit = ok(200, "GET", "/customer-deposits/" + dep, null);
            assertThat(deposit.get("status").asString()).as("deposit used up across two invoices").isEqualTo("closed");
            tiesOut(c);

            // partial payment on invoice 2
            String pay = money("/payments", Map.of("partnerId", c, "paymentDate", today, "amount", 500, "paymentMethod",
                    "bank", "allocations", List.of(Map.of("documentId", i2, "amount", 500)))).get("id").asString();
            assertThat(residual(i2)).isEqualTo(1500);
            tiesOut(c);

            // cancelling an invoice that carries allocations is refused and writes nothing
            long before = entries(i2);
            Res cancel = call("POST", "/invoices/" + i2 + "/cancel", Map.of("reason", "test"), key());
            assertThat(cancel.status()).as(cancel.raw().body()).isGreaterThanOrEqualTo(400);
            assertThat(ok(200, "GET", "/invoices/" + i2, null).get("status").asString()).isEqualTo("posted");
            assertThat(entries(i2)).isEqualTo(before);

            // credit note for 10 units on the partly paid invoice
            String cn = ok(200, "POST", "/credit-notes", Map.of("partnerId", c, "issueDate", today, "reversedDocumentId",
                    i2, "lines", List.of(Map.of("productId", stone, "quantity", 10, "unitPrice", 50)))).get("id")
                    .asString();
            money("/credit-notes/" + cn + "/post", null);
            // a credit note without a sales return is a value credit: AR drops, the order's quantity buckets do not
            // (only a closed return moves "credited", SalesReturnService.close)
            assertThat(qty(so).get("credited").asDouble()).isZero();
            assertThat(qty(so).get("netInvoiced").asDouble()).isEqualTo(100);
            assertThat(ledger.balance("accounts_receivable", c)).as("5,000 − 3,000 deposit − 500 paid − 500 credited")
                    .isEqualByComparingTo("1000");
            tiesOut(c);

            // an over-payment kept as a deposit, partly refunded
            String extra = money("/customer-deposits", Map.of("partnerId", c, "amount", 800, "paymentMethod", "cash"))
                    .get("id").asString();
            money("/customer-deposits/" + extra + "/refund", Map.of("amount", 300, "paymentMethod", "cash", "reason",
                    "Over-paid"));
            assertThat(depositSubledger(c)).isEqualByComparingTo("500");
            tiesOut(c);

            // voiding the payment reopens invoice 2 by exactly what it settled
            money("/payments/" + pay + "/void", Map.of("reason", "Bounced cheque"));
            assertThat(ledger.balance("accounts_receivable", c)).isEqualByComparingTo("1500");
            tiesOut(c);
        } finally {
            ok(200, "PUT", "/sales/settings", Map.of("depositApplication", previous));
        }
    }

    // ------------------------------------------------------------------ P1-8 races (different keys, same operation)

    /** Runs both requests at the same instant, each with its own Idempotency-Key; returns the two statuses. */
    int[] race(String pathA, Object bodyA, String pathB, Object bodyB) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> a = pool.submit(() -> {
                go.await();
                return call("POST", pathA, bodyA, key()).status();
            });
            Future<Integer> b = pool.submit(() -> {
                go.await();
                return call("POST", pathB, bodyB, key()).status();
            });
            go.countDown();
            return new int[] {a.get(), b.get()};
        } finally {
            pool.shutdownNow();
        }
    }

    static long successes(int[] statuses) {
        return java.util.Arrays.stream(statuses).filter(s -> s < 300).count();
    }

    String draftInvoice(String c, int amount) {
        return ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", today, "lines", List.of(Map.of(
                "description", "Haulage", "quantity", 1, "unitPrice", amount)))).get("id").asString();
    }

    String postedInvoice(String c, int amount) {
        String id = draftInvoice(c, amount);
        money("/invoices/" + id + "/post", null);
        return id;
    }

    @Test
    @DisplayName("P1-8: two posts of one draft invoice at once → one journal entry, one success")
    void doublePost() throws Exception {
        String c = customer();
        String inv = draftInvoice(c, 1000);
        int[] r = race("/invoices/" + inv + "/post", null, "/invoices/" + inv + "/post", null);
        assertThat(successes(r)).as(java.util.Arrays.toString(r)).isEqualTo(1);
        assertThat(entries(inv)).isEqualTo(1);
        assertThat(ledger.balance("accounts_receivable", c)).isEqualByComparingTo("1000");
        tiesOut(c);
    }

    @Test
    @DisplayName("P1-8: deposit apply and refund of the whole deposit at once → only one takes it; subledger = GL")
    void depositApplyVersusRefund() throws Exception {
        String c = customer();
        String dep = money("/customer-deposits", Map.of("partnerId", c, "amount", 1000, "paymentMethod", "cash"))
                .get("id").asString();
        String inv = postedInvoice(c, 1000);
        int[] r = race("/customer-deposits/" + dep + "/apply", Map.of("documentId", inv, "amount", 1000),
                "/customer-deposits/" + dep + "/refund", Map.of("amount", 1000, "paymentMethod", "cash", "reason", "race"));
        assertThat(successes(r)).as(java.util.Arrays.toString(r)).isEqualTo(1);
        assertThat(depositSubledger(c)).isZero();
        double residual = residual(inv);
        assertThat(residual).as("applied → 0, refunded → 1,000").isIn(0.0, 1000.0);
        tiesOut(c);
    }

    @Test
    @DisplayName("P1-8: payment void racing its allocation → the invoice ends open by exactly what a live payment settles")
    void voidVersusAllocation() throws Exception {
        String c = customer();
        String inv = postedInvoice(c, 1000);
        String pay = money("/payments", Map.of("partnerId", c, "paymentDate", today, "amount", 1000, "paymentMethod",
                "bank")).get("id").asString();
        int[] r = race("/payments/" + pay + "/void", Map.of("reason", "race"), "/payments/" + pay + "/allocations",
                Map.of("allocations", List.of(Map.of("documentId", inv, "amount", 1000))));
        boolean voided = jdbc.sql("SELECT \"voidedAt\" IS NOT NULL FROM \"Payment\" WHERE id = ?").param(pay)
                .query(Boolean.class).single();
        assertThat(r[0]).as("the void itself never fails: " + java.util.Arrays.toString(r)).isLessThan(300);
        assertThat(voided).isTrue();
        assertThat(residual(inv)).as("a voided payment settles nothing, whichever ran first").isEqualTo(1000);
        assertThat(ledger.balance("accounts_receivable", c)).isEqualByComparingTo("1000");
        tiesOut(c);
    }
}
