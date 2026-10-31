package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Phase S6 of ADR-S011 (sales-postings.md §4-§5): invoicing policy (ordered / delivered), regular invoices that never
 * re-issue stock, gross invoiced projection, deposits without invoice (D1), full and partial application (D4, D5),
 * refund (D6), down-payment invoice registering a deposit (D2), oldest-first auto application, mobile money (mock),
 * credit exposure with deposits, deposit and AR subledgers tying out to the ledger.
 */
class SalesBillingIT extends ApiTestSupport {

    @Autowired
    AccountResolverService accounts;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    final String today = LocalDate.now().toString();
    String ordered;
    String delivered;
    SalesLedgerAssertions ledger;

    @BeforeAll
    void fixtures() {
        ordered = product("Blocks " + sfx, "ordered");
        delivered = product("Aggregate " + sfx, "delivered");
        ledger = new SalesLedgerAssertions(jdbc, demoOrg);
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).executeWithoutResult(
                s -> accounts.ensureByCode("1060", "Mobile Money Wallet", "mobile_money", "mobile_money")));
    }

    String product(String name, String policy) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"invoicePolicy\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'service'::\"ProductType\", 1000000, 0, false, ?, now())").params(id, demoOrg, "BI-" + UUID.randomUUID()
                        .toString().substring(0, 8), name, policy).update();
        return id;
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, true, now())").params(id, demoOrg, "BC-" + UUID.randomUUID().toString()
                        .substring(0, 8), "Billing cust " + sfx).update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String method, String path, Object body, int status) {
        Res r = call(method, path, body, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    String order(String partner, String product, int qty) {
        String id = ok(201, "POST", "/sales/orders", Map.of("partnerId", partner, "lines", List.of(Map.of("productId",
                product, "quantity", qty)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + id + "/confirm", null);
        return id;
    }

    static void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(150);
        }
        assertThat(cond.getAsBoolean()).as("condition within 15 s").isTrue();
    }

    double invoiced(String so) {
        return ok(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("qty").get("grossInvoiced").asDouble();
    }

    BigDecimal depositBalance(String partner) {
        return ledger.balance("customer_deposit", partner).negate();
    }

    BigDecimal depositSubledger(String partner) {
        return jdbc.sql("SELECT COALESCE(sum(amount - \"appliedAmount\" - \"refundedAmount\"), 0) FROM \"CustomerDeposit\""
                + " WHERE \"partnerId\" = ? AND status = 'open'").param(partner).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("D1/D4/D6: deposit 30M, ordered-policy invoice 100M, apply 30M, refund rest of a second deposit")
    void depositAppliedToFinalInvoice() throws Exception {
        String c = customer();
        String so = order(c, ordered, 100);
        JsonNode dep = money("POST", "/sales/orders/" + so + "/deposits", Map.of("amount", 30_000_000, "paymentMethod",
                "bank"), 200);
        assertThat(dep.get("number").asString()).startsWith("DEP-");
        assertThat(depositBalance(c)).isEqualByComparingTo("30000000");
        assertThat(ok(200, "GET", "/credit/partners/" + c + "/exposure", null).get("exposure").asDouble())
                .isEqualTo(70_000_000);
        JsonNode preview = ok(200, "GET", "/sales/orders/" + so + "/billing", null);
        assertThat(preview.get("lines").get(0).get("quantity").asDouble()).isEqualTo(100);
        JsonNode inv = money("POST", "/sales/orders/" + so + "/invoices", Map.of("mode", "regular"), 200);
        String invId = inv.get("id").asString();
        assertThat(inv.get("totalAmount").asDouble()).isEqualTo(100_000_000);
        assertThat(ok(200, "GET", "/sales/orders/" + so + "/billing", null).get("lines").size()).isZero();
        assertThat(call("POST", "/sales/orders/" + so + "/invoices", Map.of("mode", "regular"), key()).status())
                .isEqualTo(400);
        ok(200, "POST", "/invoices/" + invId + "/post", null);
        await(() -> invoiced(so) == 100);
        JsonNode applied = money("POST", "/customer-deposits/" + dep.get("id").asString() + "/apply", Map.of(
                "documentId", invId), 200);
        assertThat(applied.get("status").asString()).isEqualTo("closed");
        assertThat(ok(200, "GET", "/invoices/" + invId, null).get("amountResidual").asDouble()).isEqualTo(70_000_000);
        assertThat(depositBalance(c)).isEqualByComparingTo("0");
        ledger.receivableTiesOut(c);
        // D6: a second deposit, partly refunded
        JsonNode dep2 = money("POST", "/sales/orders/" + so + "/deposits", Map.of("amount", 10_000_000), 200);
        JsonNode ref = money("POST", "/customer-deposits/" + dep2.get("id").asString() + "/refund", Map.of("amount",
                4_000_000, "reason", "Over-paid"), 200);
        assertThat(ref.get("available").asDouble()).isEqualTo(6_000_000);
        assertThat(depositBalance(c)).isEqualByComparingTo(depositSubledger(c));
        assertThat(depositSubledger(c)).isEqualByComparingTo("6000000");
        assertThat(call("POST", "/customer-deposits/" + dep2.get("id").asString() + "/refund", Map.of("amount",
                7_000_000), key()).status()).isEqualTo(400);
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("D5: delivered policy, two partial invoices, oldest-first auto application of the deposit")
    void partialInvoicesAutoApply() throws Exception {
        ok(200, "PUT", "/sales/settings", Map.of("depositApplication", "oldest_first"));
        try {
            String c = customer();
            String so = order(c, delivered, 100);
            money("POST", "/sales/orders/" + so + "/deposits", Map.of("amount", 50_000_000), 200);
            assertThat(call("POST", "/sales/orders/" + so + "/invoices", Map.of(), key()).status()).isEqualTo(400);
            String line = ok(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("id").asString();
            jdbc.sql("UPDATE \"SalesOrderLine\" SET \"qtyDelivered\" = 40, \"qtyDispatched\" = 40, \"qtyBillable\" = 40"
                    + " WHERE id = ?").param(line).update();
            String i1 = money("POST", "/sales/orders/" + so + "/invoices", Map.of(), 200).get("id").asString();
            ok(200, "POST", "/invoices/" + i1 + "/post", null);
            await(() -> ok(200, "GET", "/invoices/" + i1, null).get("amountResidual").asDouble() == 0);
            jdbc.sql("UPDATE \"SalesOrderLine\" SET \"qtyDelivered\" = 70, \"qtyDispatched\" = 70, \"qtyBillable\" = 70"
                    + " WHERE id = ?").param(line).update();
            JsonNode second = money("POST", "/sales/orders/" + so + "/invoices", Map.of(), 200);
            assertThat(second.get("totalAmount").asDouble()).isEqualTo(30_000_000);
            ok(200, "POST", "/invoices/" + second.get("id").asString() + "/post", null);
            await(() -> ok(200, "GET", "/invoices/" + second.get("id").asString(), null).get("amountResidual")
                    .asDouble() == 20_000_000);
            assertThat(depositSubledger(c)).isEqualByComparingTo("0");
            assertThat(depositBalance(c)).isEqualByComparingTo("0");
            await(() -> invoiced(so) == 70);
            JsonNode o = ok(200, "GET", "/sales/orders/" + so, null);
            assertThat(o.get("invoiceStatus").asString()).isEqualTo("partial");
            ledger.receivableTiesOut(c);
        } finally {
            ok(200, "PUT", "/sales/settings", Map.of("depositApplication", "manual"));
        }
    }

    @Test
    @DisplayName("D2: down-payment invoice posts to deposits, registers the deposit when posted, then settles the final")
    void downPaymentInvoice() throws Exception {
        String c = customer();
        String so = order(c, ordered, 10);
        JsonNode dp = money("POST", "/sales/orders/" + so + "/invoices", Map.of("mode", "down_payment", "percent", 30), 200);
        assertThat(dp.get("totalAmount").asDouble()).isEqualTo(3_000_000);
        assertThat(call("POST", "/sales/orders/" + so + "/invoices", Map.of("mode", "down_payment", "amount",
                20_000_000), key()).status()).isEqualTo(400);
        ok(200, "POST", "/invoices/" + dp.get("id").asString() + "/post", null);
        await(() -> depositSubledger(c).compareTo(new BigDecimal("3000000")) == 0);
        assertThat(depositBalance(c)).isEqualByComparingTo("3000000");
        money("POST", "/payments", Map.of("partnerId", c, "paymentDate", today, "amount", 3_000_000, "paymentMethod",
                "bank", "allocations", List.of(Map.of("documentId", dp.get("id").asString(), "amount", 3_000_000))), 200);
        String fin = money("POST", "/sales/orders/" + so + "/invoices", Map.of("mode", "regular"), 200).get("id")
                .asString();
        ok(200, "POST", "/invoices/" + fin + "/post", null);
        JsonNode applied = money("POST", "/sales/orders/" + so + "/apply-deposits", Map.of("documentId", fin), 200);
        assertThat(applied.get(0).get("status").asString()).isEqualTo("closed");
        assertThat(ok(200, "GET", "/invoices/" + fin, null).get("amountResidual").asDouble()).isEqualTo(7_000_000);
        assertThat(depositBalance(c)).isEqualByComparingTo("0");
        ledger.receivableTiesOut(c);
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("mobile money (mock): deposit for an order and an invoice payment; declined phone fails; void")
    void mobileMoney() {
        String c = customer();
        String so = order(c, ordered, 2);
        JsonNode providers = ok(200, "GET", "/payments/mobile-money/providers", null);
        assertThat(providers.toString()).contains("mock").contains("mtn_momo");
        JsonNode r = money("POST", "/payments/mobile-money", Map.of("provider", "mock", "partnerId", c, "phone",
                "+256771234567", "amount", 500_000, "purpose", "deposit", "salesOrderId", so), 200);
        JsonNode done = ok(200, "POST", "/payments/mobile-money/" + r.get("id").asString() + "/refresh", null);
        assertThat(done.get("status").asString()).isEqualTo("succeeded");
        assertThat(ok(200, "GET", "/customer-deposits/" + done.get("depositId").asString(), null).get("paymentMethod")
                .asString()).isEqualTo("mobile_money");
        JsonNode fail = money("POST", "/payments/mobile-money", Map.of("provider", "mock", "partnerId", c, "phone",
                "+256771234560", "amount", 1000, "purpose", "on_account"), 200);
        assertThat(ok(200, "POST", "/payments/mobile-money/" + fail.get("id").asString() + "/refresh", null).get(
                "status").asString()).isEqualTo("failed");
        assertThat(call("POST", "/payments/mobile-money", Map.of("provider", "mtn_momo", "partnerId", c, "phone",
                "+256771234567", "amount", 1000), key()).status()).isEqualTo(400);
        JsonNode dep = money("POST", "/sales/orders/" + so + "/deposits", Map.of("amount", 1000), 200);
        assertThat(money("POST", "/customer-deposits/" + dep.get("id").asString() + "/void", Map.of("reason", "typo"),
                200).get("status").asString()).isEqualTo("void");
        assertThat(depositBalance(c)).isEqualByComparingTo(depositSubledger(c));
        assertThat(call("POST", "/payments", Map.of("partnerId", c, "paymentDate", today, "amount", 1, "paymentMethod",
                "customer_deposit"), key()).status()).isEqualTo(400);
    }
}
