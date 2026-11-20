package com.inventoryerp.accounting.fx2;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FX-2 E: expenses paid from functional or foreign accounts with realised FX (owner decision 5). */
class FxExpenseFundingIT extends AccountingFixtures {

    record Setup(Books b, String base, String ccy, String usdBank, String usdBankId, String category, String supplier) {}

    /** Fresh books; a bank held in the test currency carrying 1,000 at 5,000; the table rate is 5,000. */
    Setup setup() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        LocalDate d0 = LocalDate.now().minusDays(10);
        rate(b, ccy, base, d0.toString(), "5000");
        String usdBank = "12" + UUID.randomUUID().toString().substring(0, 6);
        String usdBankId = okAs(b, 201, "POST", "/accounts/cash-flow", Map.of("code", usdBank, "name", "FX2 USD cash",
                "accountType", "bank", "currencyId", ccy)).get("id").asString();
        String equity = accountCodeOfCategory(b.org(), "equity");
        postJe(b, "GEN", d0.toString(), ccy, "5000", null, null, List.of(dr(usdBank, 1000), cr(equity, 1000)));
        String expenseAcc = account(b.org(), accountCodeOfSection(b.org(), "operating_expense"));
        String category = okAs(b, 201, "POST", "/expense-categories", Map.of("name", "FX2 " + UUID.randomUUID()
                .toString().substring(0, 6), "ledgerAccountId", expenseAcc)).get("id").asString();
        String supplier = okAs(b, 201, "POST", "/partners", Map.of("code", "E-" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "FX2 expense supplier", "isSupplier", true)).get("id").asString();
        return new Setup(b, base, ccy, usdBank, usdBankId, category, supplier);
    }

    Map<String, Object> expense(Setup s, String type, int foreign, int rate, String payFrom) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "FX2 expense " + UUID.randomUUID().toString().substring(0, 4));
        body.put("amount", foreign * rate);
        body.put("expenseDate", today());
        body.put("paymentType", type);
        body.put("categoryId", s.category());
        body.put("supplierId", s.supplier());
        body.put("currencyCode", s.ccy());
        body.put("exchangeRate", rate);
        body.put("foreignAmount", foreign);
        if (payFrom != null) {
            body.put("paymentMethod", "BANK_TRANSFER");
            body.put("accountId", payFrom);
        }
        return body;
    }

    String paymentEntry(String org, String expenseId) {
        return jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'expense_payment' AND \"sourceId\" = ? ORDER BY \"createdAt\" DESC LIMIT 1").params(org, expenseId)
                .query(String.class).single();
    }

    BigDecimal onAccount(String entry, String accountId) {
        return jdbc.sql("SELECT coalesce(sum(\"baseDebit\" - \"baseCredit\"), 0) FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ? AND \"accountId\" = ?").params(entry, accountId).query(BigDecimal.class).single();
    }

    BigDecimal fx(String entry, String org) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseCredit\" - l.\"baseDebit\"), 0) FROM \"JournalLine\" l WHERE"
                + " l.\"journalEntryId\" = ? AND l.\"accountId\" IN (SELECT \"accountId\" FROM \"AccountMapping\" WHERE"
                + " \"organizationId\" = ? AND key IN ('fx_gain', 'fx_loss'))").params(entry, org)
                .query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("reviewer example: USD cash carried at 5,000 pays a $500 expense at 5,500 → gain 250,000")
    void immediateFromForeignCash() {
        Setup s = setup();
        Res r = moneyAs(s.b(), "POST", "/expenses", expense(s, "CASH", 500, 5500, s.usdBankId()));
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        String id = r.body().get("id").asString();
        String je = paymentEntry(s.b().org(), id);
        LedgerInvariants.entriesBalanced(jdbc, s.b().org(), List.of(je));
        String expenseAcc = jdbc.sql("SELECT \"ledgerAccountId\" FROM \"ExpenseCategory\" WHERE id = ?")
                .param(s.category()).query(String.class).single();
        assertThat(onAccount(je, expenseAcc)).as("expense at 5,500").isEqualByComparingTo("2750000");
        assertThat(onAccount(je, s.usdBankId())).as("cash relieved at carrying 5,000").isEqualByComparingTo("-2500000");
        assertThat(fx(je, s.b().org())).as("cash-disposal gain").isEqualByComparingTo("250000");
        assertThat(jdbc.sql("SELECT sum(credit) FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?"
                + " AND upper(\"currencyId\") = ?").params(je, s.usdBankId(), s.ccy()).query(BigDecimal.class).single())
                .as("cash line carries 500 in the currency").isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("accrued $200 at 5,500 paid from USD cash: payable relieved at 5,500, cash at 5,000, gain 100,000")
    void accruedFromForeignCash() {
        Setup s = setup();
        String id = moneyAs(s.b(), "POST", "/expenses", expense(s, "CREDIT", 200, 5500, null)).body().get("id")
                .asString();
        Res paid = moneyAs(s.b(), "POST", "/expenses/" + id + "/pay", Map.of("paymentMethod", "BANK_TRANSFER",
                "accountId", s.usdBankId()));
        assertThat(paid.status()).as(paid.raw().body()).isEqualTo(200);
        String je = paymentEntry(s.b().org(), id);
        assertThat(onAccount(je, s.usdBankId())).isEqualByComparingTo("-1000000");
        assertThat(fx(je, s.b().org())).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("accrued $100 at 5,500 paid later from a functional bank at 5,800: loss 30,000, bank untagged; void")
    void accruedFromFunctionalBankLater() {
        Setup s = setup();
        String bank = accountCodeOfCategory(s.b().org(), "bank");
        String bankId = account(s.b().org(), bank);
        String equity = accountCodeOfCategory(s.b().org(), "equity");
        postJe(s.b(), today(), List.of(dr(bank, 10_000_000), cr(equity, 10_000_000)));
        String id = moneyAs(s.b(), "POST", "/expenses", expense(s, "CREDIT", 100, 5500, null)).body().get("id")
                .asString();
        // partial: $40 at 5,800
        Res part = moneyAs(s.b(), "POST", "/expenses/" + id + "/pay", Map.of("paymentMethod", "BANK_TRANSFER",
                "accountId", bankId, "foreignAmount", 40, "exchangeRate", 5800));
        assertThat(part.status()).as(part.raw().body()).isEqualTo(200);
        String je1 = paymentEntry(s.b().org(), id);
        assertThat(onAccount(je1, bankId)).isEqualByComparingTo("-232000");
        assertThat(fx(je1, s.b().org())).as("40 × (5,500 − 5,800)").isEqualByComparingTo("-12000");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ? AND"
                + " \"currencyId\" IS NOT NULL").params(je1, bankId).query(Long.class).single()).isZero();
        // the rest: $60 at 5,800
        Res rest = moneyAs(s.b(), "POST", "/expenses/" + id + "/pay", Map.of("paymentMethod", "BANK_TRANSFER",
                "accountId", bankId, "exchangeRate", 5800));
        assertThat(rest.status()).as(rest.raw().body()).isEqualTo(200);
        assertThat(fx(paymentEntry(s.b().org(), id), s.b().org())).isEqualByComparingTo("-18000");
        Res v = moneyAs(s.b(), "POST", "/expenses/" + id + "/void", Map.of("voidReason", "FX2 test"));
        assertThat(v.status()).as(v.raw().body()).isIn(200, 201);
    }

    @Test
    @DisplayName("a foreign account pays only expenses in its own currency")
    void thirdCurrencyRefused() {
        Setup s = setup();
        Map<String, Object> functional = expense(s, "CASH", 10, 1, s.usdBankId());
        functional.remove("currencyCode");
        functional.remove("exchangeRate");
        functional.remove("foreignAmount");
        Res r = moneyAs(s.b(), "POST", "/expenses", functional);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw().body()).contains("it can only pay " + s.ccy() + " expenses");
    }

    @Test
    @DisplayName("funds are checked in the account currency")
    void fundsChecked() {
        Setup s = setup();
        Res r = moneyAs(s.b(), "POST", "/expenses", expense(s, "CASH", 1500, 5000, s.usdBankId()));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw().body()).contains("insufficient funds");
    }
}
