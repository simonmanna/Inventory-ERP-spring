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
 * Foreign-currency customer deposits (ADR-S080): a deposit received into an account held in its currency, booked at its
 * own rate on the customer-deposit liability (tagged with the currency, never revalued), applied to invoices of the same
 * currency through the foreign-payment FX settlement (deposit relieved at its rate, receivable at the invoice rate,
 * difference realised FX), refunded at the account's carrying value with realised FX, and reported in functional value.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FxDepositsIT extends ApiTestSupport {

    String base;
    String cur;
    String today;
    String service;
    String fxBank;

    @BeforeAll
    void fixtures() {
        base = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(demoOrg)
                .query(String.class).optional().orElse("USD");
        today = LocalDate.now().toString();
        cur = currency();
        ok(201, "POST", "/currencies/rates", Map.of("fromCode", cur, "toCode", base, "asOf",
                LocalDate.now().minusDays(1).toString(), "rate", 3700));
        service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                        + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, 'FXD service', 'service',"
                        + " 100, 0, false, now())")
                .params(service, demoOrg, "FXD-" + UUID.randomUUID().toString().substring(0, 6)).update();
        fxBank = ok(201, "POST", "/accounts/cash-flow", Map.of("code", "13" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "FXD bank " + cur, "accountType", "bank", "currencyId", cur))
                .get("id").asString();
    }

    String currency() {
        while (true) {
            String code = "D" + (char) ('A' + (int) (Math.random() * 26)) + (char) ('A' + (int) (Math.random() * 26));
            if (jdbc.sql("SELECT count(*) FROM \"Currency\" WHERE code = ?").param(code).query(Long.class).single() == 0) {
                ok(201, "POST", "/currencies", Map.of("code", code, "symbol", code, "name", "FXD " + code));
                return code;
            }
        }
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, true, now())")
                .params(id, demoOrg, "FXD-" + UUID.randomUUID().toString().substring(0, 8), "FXD customer "
                        + UUID.randomUUID().toString().substring(0, 4)).update();
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
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    String mapped(String k) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?"
                + " AND \"deletedAt\" IS NULL").params(demoOrg, k).query(String.class).single();
    }

    BigDecimal baseBalance(String accountId, String partnerId) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND l.\"accountId\" = ?"
                + " AND e.status::text IN ('posted', 'reversed') AND (?::text IS NULL OR l.\"partnerId\" = ?)")
                .params(demoOrg, accountId, partnerId, partnerId).query(BigDecimal.class).single();
    }

    /** Foreign balance of the account's lines in the test currency (optionally one partner's). */
    BigDecimal foreignBalance(String accountId, String partnerId) {
        return jdbc.sql("SELECT coalesce(sum(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON"
                + " e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND l.\"accountId\" = ? AND"
                + " upper(l.\"currencyId\") = ? AND e.status::text IN ('posted', 'reversed') AND (?::text IS NULL OR"
                + " l.\"partnerId\" = ?)").params(demoOrg, accountId, cur, partnerId, partnerId).query(BigDecimal.class)
                .single();
    }

    /** Net realised FX in the books (positive = gain). */
    BigDecimal fxGain() {
        return baseBalance(mapped("fx_gain"), null).add(baseBalance(mapped("fx_loss"), null)).negate();
    }

    String depositAccount() {
        return mapped("customer_deposit");
    }

    JsonNode deposit(String partner, int amount, Object rate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", partner);
        body.put("amount", amount);
        body.put("paymentMethod", "bank");
        body.put("accountId", fxBank);
        body.put("currencyCode", cur);
        body.put("receivedDate", today);
        if (rate != null) {
            body.put("exchangeRate", rate);
        }
        return money("/customer-deposits", body);
    }

    String invoice(String partner, String currency, Object rate, int qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", partner);
        body.put("issueDate", today);
        if (currency != null) {
            body.put("currencyId", currency);
            body.put("exchangeRate", rate);
        }
        body.put("lines", List.of(Map.of("productId", service, "quantity", qty, "unitPrice", 100)));
        String id = ok(200, "POST", "/invoices", body).get("id").asString();
        ok(200, "POST", "/invoices/" + id + "/post", null);
        return id;
    }

    @Test
    @DisplayName("[ADR-S080] receipt in DXX at its rate, applied to a DXX invoice with realised FX, refund, reversal, reports")
    void foreignDepositLifecycle() throws Exception {
        String c = customer();
        String liability = null;
        BigDecimal fx0 = fxGain();

        // receipt: 1,000 at a stated 3,600 → bank and liability both carry DXX 1,000 / base 3,600,000
        JsonNode dep = deposit(c, 1000, 3600);
        String depId = dep.get("id").asString();
        assertThat(dep.get("currencyCode").asString()).isEqualTo(cur);
        assertThat(dep.get("exchangeRate").asDouble()).isEqualTo(3600);
        assertThat(dep.get("baseAmount").asDouble()).isEqualTo(3_600_000);
        assertThat(dep.get("availableBase").asDouble()).isEqualTo(3_600_000);
        liability = depositAccount();
        assertThat(baseBalance(liability, c)).isEqualByComparingTo("-3600000");
        assertThat(foreignBalance(liability, c)).isEqualByComparingTo("-1000");
        assertThat(foreignBalance(fxBank, null)).isEqualByComparingTo("1000");

        // refused: a foreign deposit into a functional account; a functional deposit into the foreign account
        Map<String, Object> wrong = new LinkedHashMap<>(Map.of("partnerId", c, "amount", 10, "paymentMethod", "bank",
                "currencyCode", cur));
        Res r = call("POST", "/customer-deposits", wrong, key());
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw().body()).contains("must be received into a bank, cash or mobile-money account held in " + cur);
        r = call("POST", "/customer-deposits", Map.of("partnerId", c, "amount", 10, "paymentMethod", "bank",
                "accountId", fxBank), key());
        assertThat(r.status()).isEqualTo(400);

        // apply 600 to a DXX invoice of 1,000 at 3,700: AR relieved 2,220,000, deposit 2,160,000, FX loss 60,000
        String inv = invoice(c, cur, 3700, 10);
        String functionalInv = invoice(c, null, null, 1);
        r = call("POST", "/customer-deposits/" + depId + "/apply", Map.of("documentId", functionalInv), key());
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw().body()).contains("it can only settle " + cur + " invoices");
        JsonNode applied = money("/customer-deposits/" + depId + "/apply", Map.of("documentId", inv, "amount", 600));
        assertThat(applied.get("available").asDouble()).isEqualTo(400);
        assertThat(ok(200, "GET", "/invoices/" + inv, null).get("amountResidual").asDouble()).isEqualTo(400);
        assertThat(fxGain().subtract(fx0)).isEqualByComparingTo("-60000");
        assertThat(baseBalance(liability, c)).isEqualByComparingTo("-1440000");
        assertThat(foreignBalance(liability, c)).isEqualByComparingTo("-400");
        String paymentId = applied.get("applications").get(0).get("paymentId").asString();
        Map<String, Object> pay = jdbc.sql("SELECT upper(\"currencyId\") AS c, \"exchangeRate\" AS r FROM \"Payment\""
                + " WHERE id = ?").param(paymentId).query().singleRow();
        assertThat(pay.get("c")).isEqualTo(cur);
        assertThat((BigDecimal) pay.get("r")).isEqualByComparingTo("3600");

        // a second deposit at 3,800 lifts the bank's carrying rate to 3,700
        deposit(c, 1000, 3800);
        // refund the remaining 400 of the first deposit: liability 1,440,000, bank relieved 1,480,000 → FX loss 40,000
        BigDecimal fx1 = fxGain();
        JsonNode refunded = money("/customer-deposits/" + depId + "/refund", Map.of("amount", 400, "paymentMethod",
                "bank", "accountId", fxBank));
        assertThat(refunded.get("status").asString()).isEqualTo("closed");
        assertThat(fxGain().subtract(fx1)).isEqualByComparingTo("-40000");
        assertThat(foreignBalance(fxBank, null)).isEqualByComparingTo("1600");

        // reverse the application: invoice back to 1,000 open, deposit re-opens with 600 held at its own rate
        BigDecimal fx2 = fxGain();
        money("/customer-deposits/" + depId + "/applications/" + paymentId + "/reverse", Map.of("reason", "Wrong"));
        assertThat(ok(200, "GET", "/invoices/" + inv, null).get("amountResidual").asDouble()).isEqualTo(1000);
        assertThat(fxGain().subtract(fx2)).isEqualByComparingTo("60000");
        JsonNode reopened = ok(200, "GET", "/customer-deposits/" + depId, null);
        assertThat(reopened.get("available").asDouble()).isEqualTo(600);
        assertThat(reopened.get("availableBase").asDouble()).isEqualTo(2_160_000);

        // subledger = ledger, per partner, in both currencies: 600 @ 3,600 + 1,000 @ 3,800
        assertThat(baseBalance(liability, c)).isEqualByComparingTo("-5960000");
        assertThat(foreignBalance(liability, c)).isEqualByComparingTo("-1600");
        BigDecimal subBase = jdbc.sql("SELECT sum((amount - \"appliedAmount\" - \"refundedAmount\") * \"exchangeRate\")"
                + " FROM \"CustomerDeposit\" WHERE \"partnerId\" = ? AND status = 'open'").param(c)
                .query(BigDecimal.class).single();
        assertThat(subBase).isEqualByComparingTo("5960000");

        // statement and credit exposure in functional value
        JsonNode st = ok(200, "GET", "/sales/statements/" + c, null);
        assertThat(st.get("deposits").get("closingBalance").asDouble()).isEqualTo(5_960_000);
        List<String> descriptions = new java.util.ArrayList<>();
        st.get("deposits").get("lines").forEach(l -> descriptions.add(l.get("description").asString()));
        assertThat(descriptions).anyMatch(d -> d.contains("(" + cur + " 1,000.00 @ 3600)"))
                .anyMatch(d -> d.contains("(" + cur + " 1,000.00 @ 3800)"));

        // the receipt shows the currency and the rate
        var pdf = downloadBytes("/customer-deposits/" + depId + "/pdf");
        String text = new org.openpdf.text.pdf.parser.PdfTextExtractor(new org.openpdf.text.pdf.PdfReader(pdf.body()))
                .getTextFromPage(1);
        assertThat(text).as(text).contains(cur + " 1,000", "1 " + cur + " = 3,600 " + base);
    }
}
