package com.inventoryerp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * R-8 foreign-currency treasury, phase 3 (ADR-S076): customer receipts into and supplier payments out of a bank account
 * held in the payment currency. The cash line carries that currency; money paid out is relieved at the account's
 * carrying value; document and cash FX net into one realised FX line; a void reverses everything.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FxPaymentsIT extends ApiTestSupport {

    String base;
    String cur;
    String other;
    String today;
    String customer;
    String supplier;
    String service;
    String usdBank;

    @BeforeAll
    void fixtures() {
        base = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(demoOrg)
                .query(String.class).optional().orElse("USD");
        today = LocalDate.now().toString();
        cur = currency();
        other = currency();
        for (String c : List.of(cur, other)) {
            ok(201, "POST", "/currencies/rates", Map.of("fromCode", c, "toCode", base, "asOf",
                    LocalDate.now().minusDays(1).toString(), "rate", 3700));
        }
        customer = partner("FXP customer", false);
        supplier = partner("FXP supplier", true);
        service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                        + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, 'FXP advisory', 'service',"
                        + " 100, 0, false, now())")
                .params(service, demoOrg, "FXP-" + UUID.randomUUID().toString().substring(0, 6)).update();
        usdBank = ok(201, "POST", "/accounts/cash-flow", Map.of("code", "12" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "FXP bank " + cur, "accountType", "bank", "currencyId", cur))
                .get("id").asString();
    }

    String currency() {
        while (true) {
            String code = "Q" + (char) ('A' + (int) (Math.random() * 26)) + (char) ('A' + (int) (Math.random() * 26));
            if (jdbc.sql("SELECT count(*) FROM \"Currency\" WHERE code = ?").param(code).query(Long.class).single() == 0) {
                ok(201, "POST", "/currencies", Map.of("code", code, "symbol", code, "name", "FXP " + code));
                return code;
            }
        }
    }

    String partner(String name, boolean isSupplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())")
                .params(id, demoOrg, "FXP-" + UUID.randomUUID().toString().substring(0, 8), name + " "
                        + UUID.randomUUID().toString().substring(0, 4), isSupplier, !isSupplier).update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?"
                + " AND \"deletedAt\" IS NULL").params(demoOrg, key).query(String.class).single();
    }

    BigDecimal balance(String accountId) {
        return jdbc.sql("SELECT coalesce(sum(\"baseDebit\" - \"baseCredit\"), 0) FROM \"JournalLine\" WHERE"
                + " \"organizationId\" = ? AND \"accountId\" = ?").params(demoOrg, accountId).query(BigDecimal.class)
                .single();
    }

    /** Net realised FX in the books (positive = gain). */
    BigDecimal fxGain() {
        return balance(mapped("fx_gain")).add(balance(mapped("fx_loss"))).negate();
    }

    /** {foreign, base} of the bank's lines in {@code cur}. */
    BigDecimal[] bank() {
        return jdbc.sql("SELECT coalesce(sum(debit - credit), 0) AS f, coalesce(sum(\"baseDebit\" - \"baseCredit\"), 0)"
                        + " AS b FROM \"JournalLine\" WHERE \"organizationId\" = ? AND \"accountId\" = ? AND"
                        + " upper(\"currencyId\") = ?")
                .params(demoOrg, usdBank, cur)
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("f"), rs.getBigDecimal("b")}).single();
    }

    Res pay(String path, String partner, String docId, String currency, Object rate, int amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", partner);
        body.put("paymentDate", today);
        body.put("amount", amount);
        // purchase readiness H7: a supplier bank transfer names a verified account; a cheque does not
        body.put("paymentMethod", path.startsWith("/supplier-payments") ? "cheque" : "bank");
        body.put("accountId", usdBank);
        if (currency != null) {
            body.put("currencyCode", currency);
        }
        if (rate != null) {
            body.put("exchangeRate", rate);
        }
        if (docId != null) {
            body.put("allocations", List.of(Map.of("documentId", docId, "amount", amount)));
        }
        return call("POST", path, body, key());
    }

    @Test
    @DisplayName("[R-8] customer receipt into a foreign bank: cash line in its currency, realised FX on the receivable")
    void customerReceipt() {
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "currencyId", cur,
                "exchangeRate", 3700, "lines", List.of(Map.of("productId", service, "quantity", 1, "unitPrice", 100))))
                .get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        BigDecimal[] b0 = bank();
        BigDecimal fx0 = fxGain();
        Res r = pay("/payments", customer, inv, cur, 3750, 100);
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        BigDecimal[] b1 = bank();
        assertThat(b1[0].subtract(b0[0])).isEqualByComparingTo("100");
        assertThat(b1[1].subtract(b0[1])).isEqualByComparingTo("375000");
        assertThat(fxGain().subtract(fx0)).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("[R-8] supplier payment from a foreign bank: relieved at carrying value; FX nets; a void restores all")
    void supplierPaymentAndVoid() {
        JsonNode types = ok(200, "GET", "/accounts/cash-flow/operation-types", null);
        String equity = null;
        for (JsonNode o : types.get("deposit")) {
            if ("owner_contribution".equals(o.get("key").asString())) {
                equity = o.get("accounts").get(0).get("id").asString();
            }
        }
        Res dep = call("POST", "/accounts/cash-flow/deposit", Map.of("accountId", usdBank, "counterpartAccountId",
                equity, "operationType", "owner_contribution", "amount", 200, "description", "FXP funding",
                "exchangeRate", 3600), key());
        assertThat(dep.status()).as(dep.raw().body()).isIn(200, 201);
        String bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "currencyId",
                cur, "lines", List.of(Map.of("description", "FXP service", "quantity", 1, "unitPrice", 100))))
                .get("id").asString();
        ok(200, "POST", "/vendor-bills/" + bill + "/post", null);
        BigDecimal[] b0 = bank();
        BigDecimal fx0 = fxGain();
        Res p = pay("/supplier-payments", supplier, bill, cur, 3800, 100);
        assertThat(p.status()).as(p.raw().body()).isIn(200, 201);
        BigDecimal[] b1 = bank();
        assertThat(b1[0].subtract(b0[0])).isEqualByComparingTo("-100");
        // the bill was carried at 3,700 (370,000); the cash cost 100 × the account's carrying rate
        BigDecimal carrying = b0[1].divide(b0[0], 10, java.math.RoundingMode.HALF_UP);
        BigDecimal relieved = carrying.multiply(new BigDecimal(100)).setScale(6, java.math.RoundingMode.HALF_UP);
        assertThat(b1[1].subtract(b0[1])).isEqualByComparingTo(relieved.negate());
        assertThat(fxGain().subtract(fx0)).isEqualByComparingTo(new BigDecimal("370000").subtract(relieved));

        Res v = call("POST", "/payments/" + p.body().get("id").asString() + "/void", Map.of("reason", "FXP test"),
                key());
        assertThat(v.status()).as(v.raw().body()).isIn(200, 201);
        BigDecimal[] b2 = bank();
        assertThat(b2[0]).isEqualByComparingTo(b0[0]);
        assertThat(b2[1]).isEqualByComparingTo(b0[1]);
        assertThat(fxGain()).isEqualByComparingTo(fx0);
    }

    @Test
    @DisplayName("[R-8] a foreign bank only takes payments in its own currency")
    void currencyMustMatchAccount() {
        Res functional = pay("/payments", customer, null, null, null, 10);
        assertThat(functional.status()).isEqualTo(400);
        assertThat(functional.raw().body()).contains("POS tenders must use the organization currency");
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "currencyId",
                other, "exchangeRate", 3700, "lines", List.of(Map.of("productId", service, "quantity", 1,
                        "unitPrice", 10)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        Res mismatch = pay("/payments", customer, inv, other, 3700, 10);
        assertThat(mismatch.status()).isEqualTo(400);
        assertThat(mismatch.raw().body()).contains("is held in " + cur + "; a payment through it must be in " + cur);
    }
}
