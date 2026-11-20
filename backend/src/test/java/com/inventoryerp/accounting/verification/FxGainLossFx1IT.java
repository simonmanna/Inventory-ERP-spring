package com.inventoryerp.accounting.verification;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * FX-1 (FX gain/loss audit 2026-11-20): correctness fixes found by the code audit, plus the external reviewer's
 * revaluation → reversal → partial settlement → revaluation sequence. Test-only currency codes throughout.
 */
class FxGainLossFx1IT extends AccountingFixtures {

    // ------------------------------------------------------------------ helpers

    Books demo() {
        return new Books(demoOrg, "DEMO", adminToken, demoAdminId());
    }

    String demoRate(String ccy, int rate) {
        ok(201, "POST", "/currencies/rates", Map.of("fromCode", ccy, "toCode", baseCurrency(demoOrg), "asOf",
                LocalDate.now().minusDays(1).toString(), "rate", rate));
        return ccy;
    }

    String serviceProduct() {
        return demoProduct("FX1 service " + UUID.randomUUID().toString().substring(0, 4), false, 100, 0);
    }

    BigDecimal[] taggedOnPartner(String org, String accountId, String partnerId, String ccy) {
        return jdbc.sql("SELECT coalesce(sum(debit - credit), 0) AS f, coalesce(sum(\"baseDebit\" - \"baseCredit\"), 0)"
                        + " AS b FROM \"JournalLine\" WHERE \"organizationId\" = ? AND \"accountId\" = ? AND"
                        + " \"partnerId\" = ? AND upper(\"currencyId\") = ?")
                .params(org, accountId, partnerId, ccy)
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("f"), rs.getBigDecimal("b")}).single();
    }

    /** Net base of revaluation entries and their reversals on {@code accountId} up to the end of {@code day}. */
    BigDecimal revaluedOn(String org, String accountId, LocalDate day) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                        + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND"
                        + " l.\"accountId\" = ? AND e.\"postingDate\" <= ?::timestamp AND (e.\"sourceType\" ="
                        + " 'fx_revaluation' OR e.\"reversalOfId\" IN (SELECT x.id FROM \"JournalEntry\" x WHERE"
                        + " x.\"sourceType\" = 'fx_revaluation'))")
                .params(org, accountId, day + " 23:59:59").query(BigDecimal.class).single();
    }

    BigDecimal fxNet(String org) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l WHERE"
                        + " l.\"organizationId\" = ? AND l.\"accountId\" IN (?, ?)")
                .params(org, mapped(org, "fx_gain"), mapped(org, "fx_loss")).query(BigDecimal.class).single();
    }

    // ------------------------------------------------------------------ rates

    @Test
    @DisplayName("a zero exchange rate is refused")
    void zeroRateRefused() {
        String ccy = testCurrency();
        Res r = call("POST", "/currencies/rates", Map.of("fromCode", ccy, "toCode", baseCurrency(demoOrg), "asOf",
                today(), "rate", 0), adminToken);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).isEqualTo("rate must be greater than zero");
    }

    // ------------------------------------------------------------------ expenses

    @Test
    @DisplayName("a foreign accrued expense paid from a functional bank: bank line untagged, payable cleared in currency")
    void foreignExpensePaidFromFunctionalBank() {
        String ccy = demoRate(testCurrency(), 2);
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        String bank = account(demoOrg, "1200");
        String equity = accountCodeOfCategory(demoOrg, "equity");
        // The shared demo bank may be overdrawn by earlier classes on a reused database: fund it generously.
        postJe(demo(), today(), List.of(dr("1200", 10_000_000), cr(equity, 10_000_000)));
        String expenseAcc = account(demoOrg, accountCodeOfSection(demoOrg, "operating_expense"));
        String category = ok(201, "POST", "/expense-categories", Map.of("name", "FX1 " + sfx, "ledgerAccountId",
                expenseAcc)).get("id").asString();
        String supplier = demoPartner("FX1 supplier", true);
        String id = demoMoney("POST", "/expenses", Map.of("title", "FX1 spares " + sfx, "amount", 100, "expenseDate",
                today(), "paymentType", "CREDIT", "categoryId", category, "supplierId", supplier, "currencyCode", ccy,
                "exchangeRate", 2, "foreignAmount", 50)).get("id").asString();
        demoMoney("POST", "/expenses/" + id + "/pay", Map.of("paymentMethod", "BANK_TRANSFER", "accountId", bank,
                "amount", 100));

        String payment = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'expense_payment' AND \"sourceId\" = ?").params(demoOrg, id).query(String.class).single();
        LedgerInvariants.entriesBalanced(jdbc, demoOrg, List.of(payment));
        List<Map<String, Object>> lines = lines(payment);
        assertThat(lines).filteredOn(l -> "1200".equals(l.get("code"))).singleElement().satisfies(l -> {
            assertThat(l.get("currencyId")).as("functional bank stays untagged").isNull();
            assertThat((BigDecimal) l.get("baseCredit")).isEqualByComparingTo("100");
        });
        String payable = jdbc.sql("SELECT \"accountId\" FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                + " l.\"journalEntryId\" WHERE e.\"sourceType\" = 'expense_accrual' AND e.\"sourceId\" = ? AND l.credit"
                + " > 0").param(id).query(String.class).single();
        BigDecimal[] open = taggedOnPartner(demoOrg, payable, supplier, ccy);
        assertThat(open[0]).as("payable cleared in " + ccy).isEqualByComparingTo("0");
        assertThat(open[1]).as("payable cleared in base").isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ credit notes

    @Test
    @DisplayName("credit-note FX keeps the receivable's currency, so the open bucket ties to residual × carrying rate")
    void creditNoteFxStaysInCurrencyBucket() {
        String ccy = demoRate(testCurrency(), 3800);
        String customer = demoPartner("FX1 customer", false);
        String service = serviceProduct();
        String inv = demoMoney("POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(), "currencyId", ccy,
                "exchangeRate", 3700, "lines", List.of(Map.of("productId", service, "quantity", 1, "unitPrice", 100))))
                .get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        String cn = ok(200, "POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today(), "currencyId",
                ccy, "reversedDocumentId", inv, "lines", List.of(Map.of("productId", service, "quantity", 1,
                        "unitPrice", 40)))).get("id").asString();
        ok(200, "POST", "/credit-notes/" + cn + "/post", null);

        String fxEntry = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"sourceType\" = 'credit_note_fx' AND"
                + " \"sourceId\" = ?").param(cn).query(String.class).single();
        String ar = mapped(demoOrg, "accounts_receivable");
        assertThat(jdbc.sql("SELECT upper(\"currencyId\") FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND"
                + " \"accountId\" = ?").params(fxEntry, ar).query(String.class).single()).isEqualTo(ccy);
        BigDecimal[] bucket = taggedOnPartner(demoOrg, ar, customer, ccy);
        assertThat(bucket[0]).as("60 " + ccy + " open").isEqualByComparingTo("60");
        assertThat(bucket[1]).as("at the invoice's 3,700, so revaluation starts from the right base")
                .isEqualByComparingTo("222000");
    }

    @Test
    @DisplayName("an open foreign-currency credit is not refunded at face value in the functional currency")
    void foreignCreditRefundRefused() {
        String ccy = demoRate(testCurrency(), 3800);
        String customer = demoPartner("FX1 refund customer", false);
        String cn = ok(200, "POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today(), "currencyId",
                ccy, "lines", List.of(Map.of("productId", serviceProduct(), "quantity", 1, "unitPrice", 30))))
                .get("id").asString();
        ok(200, "POST", "/credit-notes/" + cn + "/post", null);
        Res r = call("POST", "/sales/credit-notes/" + cn + "/refund", Map.of("paymentMethod", "bank"), demoKey());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("is in " + ccy)
                .contains("cannot be refunded here yet");
        assertThat(jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?").param(cn).query(BigDecimal.class)
                .single()).as("credit still open").isEqualByComparingTo("30");
    }

    // ------------------------------------------------------------------ foreign cash

    @Test
    @DisplayName("a supplier payment cannot overdraw the foreign-currency account it is paid from")
    void foreignPaymentFundsChecked() {
        String ccy = demoRate(testCurrency(), 3700);
        String supplier = demoPartner("FX1 overdraw supplier", true);
        String usdBank = ok(201, "POST", "/accounts/cash-flow", Map.of("code", "12" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "FX1 bank " + ccy, "accountType", "bank", "currencyId", ccy))
                .get("id").asString();
        String bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today(), "currencyId",
                ccy, "lines", List.of(Map.of("description", "FX1 service", "quantity", 1, "unitPrice", 10))))
                .get("id").asString();
        ok(200, "POST", "/vendor-bills/" + bill + "/post", Map.of());
        Res r = call("POST", "/supplier-payments", Map.of("partnerId", supplier, "paymentDate", today(), "amount", 10,
                "paymentMethod", "bank", "accountId", usdBank, "currencyCode", ccy, "allocations",
                List.of(Map.of("documentId", bill, "amount", 10))), demoKey());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("has insufficient funds (available " + ccy + " 0.00)");
    }

    // ------------------------------------------------------------------ revaluation

    @Test
    @DisplayName("reviewer sequence: revalue 1,000 AP → reverse → pay 400 → revalue the 600 → reverse; ties at every step")
    void revaluationReversalPartialSettlement() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        LocalDate d0 = LocalDate.now().minusDays(20);
        LocalDate d1 = LocalDate.now().minusDays(15);
        LocalDate d2 = LocalDate.now().minusDays(10);
        LocalDate d3 = LocalDate.now().minusDays(5);
        rate(b, ccy, base, d0.toString(), "3800");
        String supplier = okAs(b, 201, "POST", "/partners", Map.of("code", "FX1-" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "FX1 reval supplier", "isSupplier", true)).get("id").asString();
        String ap = mapped(b.org(), "accounts_payable");
        String bill = okAs(b, 200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", d0.toString(),
                "currencyId", ccy, "lines", List.of(Map.of("description", "Imported service", "quantity", 1,
                        "unitPrice", 1000)))).get("id").asString();
        okAs(b, 200, "POST", "/vendor-bills/" + bill + "/post", Map.of());
        assertThat(taggedOnPartner(b.org(), ap, supplier, ccy)[1]).isEqualByComparingTo("-3800000");

        // 30 Nov: revalue at 3,900 → AP grows by 100,000 (loss); 1 Dec: reversed.
        rate(b, ccy, base, d1.toString(), "3900");
        okAs(b, 200, "POST", "/fx-revaluations", Map.of("asOf", d1.toString()));
        assertThat(revaluedOn(b.org(), ap, d1)).isEqualByComparingTo("-100000");
        assertThat(revaluedOn(b.org(), ap, d1.plusDays(1))).as("reversed next day").isEqualByComparingTo("0");

        // 5 Dec: pay 400 at 3,950 → relieved at the bill's 3,800, realised loss 60,000.
        BigDecimal fx0 = fxNet(b.org());
        Res paid = moneyAs(b, "POST", "/supplier-payments", Map.of("partnerId", supplier, "paymentDate", d2.toString(),
                "amount", 400, "paymentMethod", "bank", "currencyCode", ccy, "exchangeRate", 3950, "allocations",
                List.of(Map.of("documentId", bill, "amount", 400))));
        assertThat(paid.status()).as(paid.raw().body()).isIn(200, 201);
        assertThat(fxNet(b.org()).subtract(fx0)).as("realised loss 400 × 150, no double count")
                .isEqualByComparingTo("60000");
        BigDecimal[] open = taggedOnPartner(b.org(), ap, supplier, ccy);
        assertThat(open[0]).isEqualByComparingTo("-600");
        assertThat(open[1]).as("600 at the carrying 3,800").isEqualByComparingTo("-2280000");

        // 31 Dec: revalue the 600 at 4,000 → 120,000 loss on the remaining balance only; 1 Jan: reversed.
        rate(b, ccy, base, d3.toString(), "4000");
        JsonNode preview = okAs(b, 200, "POST", "/fx-revaluations/preview", Map.of("asOf", d3.toString()));
        JsonNode line = null;
        for (JsonNode l : preview) {
            if (ap.equals(l.get("accountId").asString()) && ccy.equals(l.get("currencyCode").asString())) {
                line = l;
            }
        }
        assertThat(line).as("AP " + ccy + " preview line").isNotNull();
        assertThat(new BigDecimal(line.get("carryingBase").asString())).isEqualByComparingTo("-2280000");
        assertThat(new BigDecimal(line.get("toPost").asString())).isEqualByComparingTo("-120000");
        okAs(b, 200, "POST", "/fx-revaluations", Map.of("asOf", d3.toString()));
        assertThat(revaluedOn(b.org(), ap, d3)).isEqualByComparingTo("-120000");
        assertThat(revaluedOn(b.org(), ap, d3.plusDays(1))).isEqualByComparingTo("0");
        LedgerInvariants.entriesBalanced(jdbc, b.org(), null);

        // The automatic reversal cannot be reversed by hand (that would make the unrealised loss permanent).
        String reversal = jdbc.sql("SELECT e.id FROM \"JournalEntry\" e JOIN \"JournalEntry\" o ON o.id ="
                + " e.\"reversalOfId\" WHERE o.\"organizationId\" = ? AND o.\"sourceType\" = 'fx_revaluation'"
                + " ORDER BY e.\"postingDate\" DESC LIMIT 1").param(b.org()).query(String.class).single();
        Res manual = as(b, "POST", "/journal-entries/" + reversal + "/reverse", Map.of());
        assertThat(manual.status()).as(manual.raw().body()).isEqualTo(400);
        assertThat(manual.body().get("message").asString()).contains("automatic reversal of an FX revaluation");
    }

    @Test
    @DisplayName("prepaid expenses are non-monetary: a foreign prepaid balance is not revalued")
    void prepaidNotRevalued() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        LocalDate d0 = LocalDate.now().minusDays(10);
        rate(b, ccy, base, d0.toString(), "2");
        String prepaid = "14" + (int) (Math.random() * 9000 + 1000);
        String category = jdbc.sql("SELECT id FROM \"AccountCategory\" WHERE key = 'prepaid_expense'")
                .query(String.class).single();
        okAs(b, 201, "POST", "/accounts", Map.of("code", prepaid, "name", "Prepaid FX1", "categoryId", category));
        String equity = accountCodeOfCategory(b.org(), "equity");
        postJe(b, "GEN", d0.toString(), ccy, "2", null, null, List.of(dr(prepaid, 100), cr(equity, 100)));
        LocalDate d1 = LocalDate.now().minusDays(2);
        rate(b, ccy, base, d1.toString(), "3");
        JsonNode preview = okAs(b, 200, "POST", "/fx-revaluations/preview", Map.of("asOf", d1.toString()));
        for (JsonNode l : preview) {
            assertThat(l.get("accountCode").asString()).as("prepaid never in the revaluation").isNotEqualTo(prepaid);
        }
    }
}
