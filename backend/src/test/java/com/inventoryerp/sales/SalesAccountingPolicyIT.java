package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Sales readiness R6 (B2): the accountant-decided policies are settings whose defaults are the shipped behaviour
 * (V683). Each test switches one policy on, proves the alternative treatment, and the settings are put back after.
 * The showroom VAT tax point is in {@code CashSaleIT} (it needs the branch profile fixtures).
 */
class SalesAccountingPolicyIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    AccountResolverService accounts;

    @Autowired
    com.inventoryerp.sales.application.SalesFulfilmentService fulfilment;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    final String today = LocalDate.now(ZoneId.of("Africa/Kampala")).toString();
    SalesLedgerAssertions ledger;
    String stone;
    String yard;
    String adminId;

    @BeforeAll
    void fixtures() {
        ledger = new SalesLedgerAssertions(jdbc, demoOrg);
        stone = ok(201, "POST", "/products", Map.of("code", "AP-" + sfx, "name", "Policy stone " + sfx, "productType",
                "stockable", "trackInventory", true, "salesPrice", 50, "costPrice", 20)).get("id").asString();
        yard = ok(201, "POST", "/inventory/locations", Map.of("code", "APY-" + sfx, "name", "Policy yard " + sfx, "type",
                "warehouse")).get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> stock.receive(StockMove.of(stone, yard, new BigDecimal("1000")).unitCost(
                        new BigDecimal("20")))));
    }

    @AfterEach
    void reset() {
        ok(200, "PUT", "/sales/settings", defaults());
    }

    static Map<String, Object> defaults() {
        Map<String, Object> m = new HashMap<>();
        m.put("depositTaxPoint", "delivery");
        m.put("showroomTaxPoint", "at_confirm");
        m.put("overOrderExcess", "bill");
        m.put("rebateTaxId", null);
        return m;
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\") VALUES (?, ?,"
                + " ?, ?, true, now())").params(id, demoOrg, "APC-" + UUID.randomUUID().toString().substring(0, 8),
                        "Policy cust " + sfx).update();
        return id;
    }

    String liability(String name) {
        String[] id = new String[1];
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> id[0] = accounts.ensureByCode("22" + (1000 + (int) (Math.random() * 8999)),
                        name + " " + sfx, "current_liability", null)));
        return id[0];
    }

    String tax(int rate, String accountId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Tax\" (id, \"organizationId\", name, code, rate, \"accountId\", \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, ?, ?, now())").params(id, demoOrg, "Policy tax " + rate + " " + UUID.randomUUID().toString().substring(0, 8), "PT" + UUID
                        .randomUUID().toString().substring(0, 6), rate, accountId).update();
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

    BigDecimal balance(String accountId) {
        return jdbc.sql("SELECT COALESCE(sum(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON"
                + " e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed') AND"
                + " l.\"accountId\" = ?").params(demoOrg, accountId).query(BigDecimal.class).single();
    }

    // ------------------------------------------------------------------ settings

    @Test
    @DisplayName("R6: defaults are the shipped behaviour; invalid policy values and rebate taxes are refused")
    void settingsValidation() {
        JsonNode s = ok(200, "GET", "/sales/settings", null);
        assertThat(s.get("showroomTaxPoint").asString()).isEqualTo("at_confirm");
        assertThat(s.get("overOrderExcess").asString()).isEqualTo("bill");
        assertThat(s.get("depositTaxPoint").asString()).isEqualTo("delivery");
        assertThat(s.get("rebateTaxId").isNull()).isTrue();
        assertThat(as("PUT", "/sales/settings", Map.of("showroomTaxPoint", "at_delivery")).status()).isEqualTo(400);
        assertThat(as("PUT", "/sales/settings", Map.of("overOrderExcess", "refund")).status()).isEqualTo(400);
        assertThat(as("PUT", "/sales/settings", Map.of("rebateTaxId", UUID.randomUUID().toString())).status())
                .isEqualTo(400);
        Res zero = as("PUT", "/sales/settings", Map.of("rebateTaxId", tax(0, null)));
        assertThat(zero.status()).isEqualTo(400);
        assertThat(zero.raw().body()).contains("positive rate");
        String vat = tax(18, liability("Rebate VAT"));
        assertThat(ok(200, "PUT", "/sales/settings", Map.of("rebateTaxId", vat)).get("rebateTaxId").asString())
                .isEqualTo(vat);
    }

    // ------------------------------------------------------------------ Q5 weighbridge excess

    /**
     * A 100 t order delivered in full, then its trip corrected to 105 t accepted (a load within the line's 10 %
     * tolerance, as a weighed dispatch would record), and the billable quantities recomputed.
     */
    String overDeliveredOrder(String c) {
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "locationId", yard, "lines", List.of(Map.of(
                "productId", stone, "quantity", 100, "unitPrice", 50, "tolerancePct", 10)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        JsonNode d = ok(201, "POST", "/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "UAP 1", "driverName",
                "Okello"));
        String id = d.get("id").asString();
        money("/sales/deliveries/" + id + "/issue", null);
        money("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Site agent", "lines", List.of(Map.of(
                "loadLineId", d.get("lines").get(0).get("id").asString(), "deliveredQty", 100))));
        jdbc.sql("UPDATE \"FleetLoadLine\" SET \"dispatchedQty\" = 105, \"deliveredQty\" = 105, \"billableQty\" = 105"
                + " WHERE id = ?").param(d.get("lines").get(0).get("id").asString()).update();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> fulfilment.rebuild(so)));
        return so;
    }

    JsonNode qty(String so) {
        return ok(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("qty");
    }

    @Test
    @DisplayName("R6 Q5: 105 t delivered on a 100 t order — 'bill' invoices 105, 'absorb' invoices the 100 ordered")
    void overOrderExcess() {
        String billed = overDeliveredOrder(customer());
        assertThat(qty(billed).get("delivered").asDouble()).isEqualTo(105);
        assertThat(qty(billed).get("billable").asDouble()).isEqualTo(105);
        assertThat(money("/sales/orders/" + billed + "/invoices", Map.of("mode", "regular")).get("totalAmount")
                .asDouble()).isEqualTo(5250);

        ok(200, "PUT", "/sales/settings", Map.of("overOrderExcess", "absorb"));
        String absorbed = overDeliveredOrder(customer());
        assertThat(qty(absorbed).get("delivered").asDouble()).as("stock still leaves for 105").isEqualTo(105);
        assertThat(qty(absorbed).get("billable").asDouble()).isEqualTo(100);
        assertThat(money("/sales/orders/" + absorbed + "/invoices", Map.of("mode", "regular")).get("totalAmount")
                .asDouble()).isEqualTo(5000);
    }

    // ------------------------------------------------------------------ Q6 rebate VAT

    String accrual(String c, int amount) {
        String program = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"RebateProgram\" (id, \"organizationId\", name, \"startDate\", tiers, \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, '[]'::jsonb, now())").params(program, demoOrg, "Volume rebate " + sfx,
                        LocalDate.now().minusMonths(6)).update();
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"RebateAccrual\" (id, \"organizationId\", \"programId\", \"partnerId\", \"periodStart\","
                + " \"periodEnd\", amount, \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, now())").params(id, demoOrg, program,
                        c, LocalDate.now().minusMonths(4), LocalDate.now().minusMonths(1), amount).update();
        return id;
    }

    @Test
    @DisplayName("R6 Q6: with a rebate tax the settlement credit note is VAT-inclusive, reverses output VAT, clears the accrual")
    void rebateVat() {
        String vatAccount = liability("Output VAT rebate");
        String vat = tax(18, vatAccount);
        String rebates = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '4190'").param(demoOrg)
                .query(String.class).optional().orElse(null);
        BigDecimal rebates0 = rebates == null ? BigDecimal.ZERO : balance(rebates);

        // default: no VAT on the credit note
        String c1 = customer();
        JsonNode plain = money("/sales/rebates/accruals/" + accrual(c1, 1180) + "/settle", null);
        String cn1 = plain.get("creditNoteId").asString();
        assertThat(ok(200, "GET", "/credit-notes/" + cn1, null).get("taxAmount").asDouble()).isZero();

        ok(200, "PUT", "/sales/settings", Map.of("rebateTaxId", vat));
        String c2 = customer();
        String acc = accrual(c2, 1180);
        String accrualAccount = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '2360'")
                .param(demoOrg).query(String.class).single();
        BigDecimal accrual0 = balance(accrualAccount);
        BigDecimal vat0 = balance(vatAccount);
        JsonNode settled = money("/sales/rebates/accruals/" + acc + "/settle", null);
        JsonNode cn = ok(200, "GET", "/credit-notes/" + settled.get("creditNoteId").asString(), null);
        assertThat(cn.get("totalAmount").asDouble()).as("the customer's credit is the accrued amount").isEqualTo(1180);
        assertThat(cn.get("taxAmount").asDouble()).isEqualTo(180);
        assertThat(balance(vatAccount).subtract(vat0)).as("output VAT reduced").isEqualByComparingTo("180");
        assertThat(balance(accrualAccount).subtract(accrual0)).as("accrual cleared by the full 1,180")
                .isEqualByComparingTo("1180");
        String rebatesNow = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '4190'")
                .param(demoOrg).query(String.class).single();
        assertThat(balance(rebatesNow).subtract(rebates0)).as("rebate cost lowered by the VAT recovered")
                .isEqualByComparingTo("-180");
        assertThat(ledger.balance("accounts_receivable", c2)).isEqualByComparingTo("-1180");
        ledger.receivableTiesOut(c2);
        ledger.trialBalanceZero();
    }

    // ------------------------------------------------------------------ Q1 VAT on advances

    @Test
    @DisplayName("R6 Q1: deposit tax point 'receipt' refuses plain advances on taxed orders (all three routes); down payment works")
    void vatOnAdvances() {
        String c = customer();
        String vat = tax(18, liability("Output VAT advance"));
        String taxed = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "locationId", yard, "lines", List.of(Map.of(
                "productId", stone, "quantity", 10, "unitPrice", 50, "taxId", vat)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + taxed + "/confirm", null);
        // default (delivery): a plain advance is fine
        money("/sales/orders/" + taxed + "/deposits", Map.of("amount", 100, "paymentMethod", "cash"));

        ok(200, "PUT", "/sales/settings", Map.of("depositTaxPoint", "receipt"));
        Res order = call("POST", "/sales/orders/" + taxed + "/deposits", Map.of("amount", 100, "paymentMethod", "cash"),
                key());
        assertThat(order.status()).as(order.raw().body()).isEqualTo(400);
        assertThat(order.raw().body()).contains("down-payment invoice");
        Res direct = call("POST", "/customer-deposits", Map.of("partnerId", c, "salesOrderId", taxed, "amount", 100,
                "paymentMethod", "cash"), key());
        assertThat(direct.status()).as(direct.raw().body()).isEqualTo(400);
        Res momo = call("POST", "/payments/mobile-money", Map.of("provider", "mock", "partnerId", c, "phone",
                "256700000001", "amount", 100, "purpose", "deposit", "salesOrderId", taxed), key());
        assertThat(momo.status()).as(momo.raw().body()).isEqualTo(400);
        assertThat(momo.raw().body()).contains("down-payment invoice");
        // the down-payment invoice charges the VAT on receipt
        JsonNode dp = money("/sales/orders/" + taxed + "/invoices", Map.of("mode", "down_payment", "amount", 118));
        assertThat(dp.get("taxAmount").asDouble()).isGreaterThan(0);
        // an untaxed order still takes a plain advance; so does an advance without an order
        String untaxed = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "locationId", yard, "lines", List.of(
                Map.of("productId", stone, "quantity", 10, "unitPrice", 50)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + untaxed + "/confirm", null);
        money("/sales/orders/" + untaxed + "/deposits", Map.of("amount", 100, "paymentMethod", "cash"));
        money("/customer-deposits", Map.of("partnerId", c, "amount", 100, "paymentMethod", "cash"));
    }
}
