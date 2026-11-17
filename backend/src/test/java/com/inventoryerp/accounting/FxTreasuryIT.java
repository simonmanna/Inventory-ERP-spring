package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * R-8 foreign-currency treasury (ADR-S076, docs/accounting/FX-TREASURY-PLAN.md): full lifecycles on isolated books —
 * locks, legacy designation, foreign deposits / withdrawals with realised FX, same- and cross-currency transfers,
 * reconciliation in the account's currency, period-end revaluation without double counting, and the FX summary.
 */
class FxTreasuryIT extends AccountingFixtures {

    record Setup(Books b, String base, String ccy, String today) {}

    Setup setup(String rate) {
        Books b = freshBooks();
        String ccy = testCurrency();
        String today = LocalDate.now().toString();
        assertThat(rate(b, ccy, baseCurrency(b.org()), LocalDate.now().minusDays(10).toString(), rate).status())
                .isEqualTo(201);
        return new Setup(b, baseCurrency(b.org()), ccy, today);
    }

    String bank(Books b, String currency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "12" + UUID.randomUUID().toString().substring(0, 6));
        body.put("name", "Bank " + (currency == null ? "functional" : currency) + " " + UUID.randomUUID()
                .toString().substring(0, 4));
        body.put("accountType", "bank");
        if (currency != null) {
            body.put("currencyId", currency);
        }
        return okAs(b, 201, "POST", "/accounts/cash-flow", body).get("id").asString();
    }

    /** First counterpart account eligible for a deposit / withdrawal operation type. */
    String counterpart(Books b, String direction, String op) {
        for (JsonNode o : okAs(b, 200, "GET", "/accounts/cash-flow/operation-types", null).get(direction)) {
            if (op.equals(o.get("key").asString())) {
                return o.get("accounts").get(0).get("id").asString();
            }
        }
        throw new AssertionError("no operation " + op);
    }

    Map<String, Object> op(String account, String counterpart, String type, Object amount, Object rate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", account);
        m.put("counterpartAccountId", counterpart);
        m.put("operationType", type);
        m.put("amount", amount);
        m.put("description", "FX test " + type);
        if (rate != null) {
            m.put("exchangeRate", rate);
        }
        return m;
    }

    Res deposit(Setup s, String account, Object amount, Object rate) {
        return moneyAs(s.b(), "POST", "/accounts/cash-flow/deposit", op(account, counterpart(s.b(), "deposit",
                "owner_contribution"), "owner_contribution", amount, rate));
    }

    Res withdraw(Setup s, String account, Object amount, Object rate) {
        return moneyAs(s.b(), "POST", "/accounts/cash-flow/withdraw", op(account, counterpart(s.b(), "withdrawal",
                "expense"), "expense", amount, rate));
    }

    Res transfer(Setup s, String from, String to, Object amount, Object received, Object rate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fromAccountId", from);
        m.put("toAccountId", to);
        m.put("amount", amount);
        m.put("date", s.today());
        if (received != null) {
            m.put("receivedAmount", received);
        }
        if (rate != null) {
            m.put("exchangeRate", rate);
        }
        return moneyAs(s.b(), "POST", "/treasury/transfer", m);
    }

    /** {foreign, base} of the account's lines in {@code currency}. */
    BigDecimal[] fx(String org, String account, String currency) {
        return jdbc.sql("SELECT coalesce(sum(debit - credit), 0) AS f, coalesce(sum(\"baseDebit\" - \"baseCredit\"), 0)"
                        + " AS b FROM \"JournalLine\" WHERE \"organizationId\" = ? AND \"accountId\" = ? AND"
                        + " upper(\"currencyId\") = ?")
                .params(org, account, currency)
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("f"), rs.getBigDecimal("b")}).single();
    }

    BigDecimal base(String org, String account) {
        return jdbc.sql("SELECT coalesce(sum(\"baseDebit\" - \"baseCredit\"), 0) FROM \"JournalLine\" WHERE"
                + " \"organizationId\" = ? AND \"accountId\" = ?").params(org, account).query(BigDecimal.class).single();
    }

    /** Net realised + unrealised FX in the books (positive = gain). */
    BigDecimal fxGain(String org) {
        return base(org, mapped(org, "fx_gain")).add(base(org, mapped(org, "fx_loss"))).negate();
    }

    void assertBooksBalance(String org) {
        BigDecimal net = jdbc.sql("SELECT coalesce(sum(\"baseDebit\" - \"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND e.status::text"
                + " IN ('posted', 'reversed')").param(org).query(BigDecimal.class).single();
        assertThat(net).as("functional trial balance").isEqualByComparingTo("0");
    }

    String entryOf(Res r) {
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        return r.body().get("id").asString();
    }

    long snapshots(String entryId) {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntryFxRate\" WHERE \"journalEntryId\" = ?").param(entryId)
                .query(Long.class).single();
    }

    // ------------------------------------------------------------------ deposits and withdrawals

    @Test
    @DisplayName("[R-8] foreign deposit and withdrawals: carried at the deposit rate, realised FX on the way out")
    void depositWithdrawRealisedFx() {
        Setup s = setup("3700");
        String usd = bank(s.b(), s.ccy());
        String dep = entryOf(deposit(s, usd, 100, null));
        assertThat(snapshots(dep)).isEqualTo(1);
        assertThat(fx(s.b().org(), usd, s.ccy())).satisfiesExactly(f -> assertThat(f).isEqualByComparingTo("100"),
                b -> assertThat(b).isEqualByComparingTo("370000"));

        Res tooPrecise = deposit(s, usd, new BigDecimal("1.234"), null);
        assertThat(tooPrecise.status()).isEqualTo(400);
        assertThat(tooPrecise.raw().body()).contains(s.ccy() + " amounts take at most 2 decimals");

        BigDecimal fx0 = fxGain(s.b().org());
        entryOf(withdraw(s, usd, 40, 3800)); // expense 152,000 at the day's rate; cash relieved at 148,000
        assertThat(fxGain(s.b().org()).subtract(fx0)).isEqualByComparingTo("4000");
        assertThat(fx(s.b().org(), usd, s.ccy())[1]).isEqualByComparingTo("222000");

        Res over = withdraw(s, usd, 100, 3800);
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.raw().body()).contains("insufficient funds (available " + s.ccy() + " 60.00)");

        entryOf(withdraw(s, usd, 60, 3650)); // empties the balance: relieves exactly the remaining 222,000
        assertThat(fx(s.b().org(), usd, s.ccy())).satisfiesExactly(f -> assertThat(f).isEqualByComparingTo("0"),
                b -> assertThat(b).isEqualByComparingTo("0"));
        assertThat(fxGain(s.b().org()).subtract(fx0)).isEqualByComparingTo("1000"); // +4,000 − 3,000
        assertBooksBalance(s.b().org());

        JsonNode acct = okAs(s.b(), 200, "GET", "/fx-treasury/accounts", null).get(0);
        assertThat(acct.get("state").asString()).isEqualTo("designated");
        assertThat(acct.get("foreignBalance").asString()).isEqualTo("0");
    }

    // ------------------------------------------------------------------ transfers

    @Test
    @DisplayName("[R-8] transfers: same currency at carrying value, across currencies with realised FX and two snapshots")
    void transfers() {
        Setup s = setup("3700");
        String ccy2 = testCurrency();
        assertThat(rate(s.b(), ccy2, s.base(), LocalDate.now().minusDays(10).toString(), "4000").status())
                .isEqualTo(201);
        String usdA = bank(s.b(), s.ccy());
        String usdB = bank(s.b(), s.ccy());
        String eur = bank(s.b(), ccy2);
        String local = bank(s.b(), null);
        entryOf(deposit(s, usdA, 100, null));
        entryOf(deposit(s, local, 1000000, null));
        BigDecimal fx0 = fxGain(s.b().org());

        entryOf(transfer(s, usdA, usdB, 30, null, null));
        assertThat(fx(s.b().org(), usdB, s.ccy())[1]).isEqualByComparingTo("111000");
        assertThat(fxGain(s.b().org())).isEqualByComparingTo(fx0);

        Res noReceived = transfer(s, usdA, eur, 10, null, null);
        assertThat(noReceived.status()).isEqualTo(400);
        assertThat(noReceived.raw().body()).contains("State the amount received in " + ccy2);

        entryOf(transfer(s, usdA, local, 20, 76000, null)); // relieves 74,000; receives 76,000 → +2,000
        assertThat(fxGain(s.b().org()).subtract(fx0)).isEqualByComparingTo("2000");

        entryOf(transfer(s, local, usdB, 380000, 100, null)); // sends 380,000; 100 at the table rate is 370,000
        assertThat(fxGain(s.b().org()).subtract(fx0)).isEqualByComparingTo("-8000");
        assertThat(fx(s.b().org(), usdB, s.ccy())).satisfiesExactly(f -> assertThat(f).isEqualByComparingTo("130"),
                b -> assertThat(b).isEqualByComparingTo("481000"));

        String cross = entryOf(transfer(s, usdA, eur, 10, 9, null)); // relieves 37,000; 9 × 4,000 = 36,000 → −1,000
        assertThat(fxGain(s.b().org()).subtract(fx0)).isEqualByComparingTo("-9000");
        assertThat(snapshots(cross)).isEqualTo(2);
        assertThat(fx(s.b().org(), eur, ccy2)[0]).isEqualByComparingTo("9");
        assertThat(fx(s.b().org(), usdA, s.ccy())[0]).isEqualByComparingTo("40");
        assertBooksBalance(s.b().org());
    }

    // ------------------------------------------------------------------ locks and legacy designation

    @Test
    @DisplayName("[R-8] legacy foreign account: undesignated until designated, no silent conversion; locks hold")
    void legacyDesignationAndLocks() {
        Setup s = setup("3700");
        String acct = bank(s.b(), null);
        String equity = counterpart(s.b(), "deposit", "owner_contribution");
        entryOf(moneyAs(s.b(), "POST", "/accounts/cash-flow/deposit", op(acct, equity, "owner_contribution", 500000,
                null)));
        // the denomination lock: the API refuses a currency change once the account has postings
        Res change = as(s.b(), "PATCH", "/accounts/cash-flow/" + acct, Map.of("currencyId", s.ccy()));
        assertThat(change.status()).isEqualTo(400);
        assertThat(change.raw().body()).contains("cannot change once it has postings");
        // legacy data: a foreign account whose history was posted as functional amounts before FX treasury
        jdbc.sql("UPDATE \"Account\" SET \"currencyId\" = ? WHERE id = ?").params(s.ccy(), acct).update();
        JsonNode row = okAs(s.b(), 200, "GET", "/fx-treasury/accounts", null).get(0);
        assertThat(row.get("state").asString()).isEqualTo("undesignated");
        assertThat(row.get("unknownCurrencyBalance").asString()).isEqualTo("500000");
        Res refused = deposit(s, acct, 10, null);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.raw().body()).contains("has balances of unknown currency; designate its " + s.ccy());

        Res wrongSign = moneyAs(s.b(), "POST", "/fx-treasury/designations", Map.of("accountId", acct,
                "foreignBalance", -5));
        assertThat(wrongSign.status()).isEqualTo(400);
        Res designated = moneyAs(s.b(), "POST", "/fx-treasury/designations", Map.of("accountId", acct,
                "foreignBalance", 130, "note", "Bank letter 31 Oct"));
        assertThat(designated.status()).as(designated.raw().body()).isIn(200, 201);
        JsonNode d = designated.body();
        assertThat(d.get("state").asString()).isEqualTo("designated");
        assertThat(base(s.b().org(), acct)).as("functional value unchanged").isEqualByComparingTo("500000");
        assertThat(fx(s.b().org(), acct, s.ccy())).satisfiesExactly(f -> assertThat(f).isEqualByComparingTo("130"),
                b -> assertThat(b).isEqualByComparingTo("500000"));
        row = okAs(s.b(), 200, "GET", "/fx-treasury/accounts", null).get(0);
        assertThat(row.get("unknownCurrencyBalance").asString()).isEqualTo("0");
        assertThat(moneyAs(s.b(), "POST", "/fx-treasury/designations", Map.of("accountId", acct, "foreignBalance",
                130)).raw().body()).contains("already designated");
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"entityId\" = ? AND action = 'update' AND"
                + " \"newValues\"->>'change' = 'fx_designation'")
                .param(acct).query(Long.class).single()).isEqualTo(1);

        // the engine refuses an untagged (functional-only) line on a designated foreign account
        String code = jdbc.sql("SELECT code FROM \"Account\" WHERE id = ?").param(acct).query(String.class).single();
        assertThatThrownBy(() -> postJe(s.b(), s.today(), List.of(JeLine.dr(code, 10),
                JeLine.cr(accountCodeOfSection(s.b().org(), "revenue"), 10))))
                .hasMessageContaining("is held in " + s.ccy() + "; post it in " + s.ccy());
        BigDecimal fx0 = fxGain(s.b().org());
        entryOf(withdraw(s, acct, 30, 3900)); // relieves 30 × 500,000 / 130 = 115,384.615385; expense 117,000
        assertThat(fxGain(s.b().org()).subtract(fx0)).isEqualByComparingTo("1615.384615");
        assertBooksBalance(s.b().org());

        // the functional-currency lock: refused once entries are posted
        Res fc = as(s.b(), "PATCH", "/organizations/me/settings", Map.of("currencyCode", s.ccy()));
        assertThat(fc.status()).isEqualTo(400);
        assertThat(fc.raw().body()).contains("The functional currency cannot change once entries are posted");
    }

    @Test
    @DisplayName("[R-8] the functional currency may change before any posting")
    void functionalCurrencyChangeableWhenEmpty() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(b.org())
                .query(Long.class).single()).isZero();
        String other = testCurrency();
        assertThat(as(b, "PATCH", "/organizations/me/settings", Map.of("currencyCode", other)).status()).isEqualTo(200);
        assertThat(as(b, "PATCH", "/organizations/me/settings", Map.of("currencyCode", base)).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ reconciliation

    @Test
    @DisplayName("[R-8] bank reconciliation of a foreign account runs in its currency; other currencies refused")
    void reconciliationInDenomination() {
        Setup s = setup("3700");
        String usd = bank(s.b(), s.ccy());
        entryOf(deposit(s, usd, 250, null));
        entryOf(withdraw(s, usd, 50, 3800));
        Res wrong = as(s.b(), "POST", "/bank-reconciliation/import", Map.of("bankAccountId", usd, "currencyCode",
                s.base(), "lines", List.of(Map.of("postedAt", s.today(), "description", "x", "amount", 250))));
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.raw().body()).contains("cannot be imported into account");
        okAs(s.b(), 200, "POST", "/bank-reconciliation/import", Map.of("bankAccountId", usd, "currencyCode", s.ccy(),
                "lines", List.of(Map.of("postedAt", s.today(), "externalRef", "D1", "description", "Deposit",
                        "amount", 250), Map.of("postedAt", s.today(), "externalRef", "W1", "description", "Expense",
                        "amount", -50))));
        JsonNode summary = okAs(s.b(), 200, "GET", "/bank-reconciliation/summary?bankAccountId=" + usd, null);
        assertThat(summary.get("bookBalance").asString()).isEqualTo("200");
        assertThat(summary.get("currencyCode").asString()).isEqualTo(s.ccy());
        assertThat(summary.get("statementTotal").asString()).isEqualTo("200");
        JsonNode st = okAs(s.b(), 200, "GET", "/bank-reconciliation/statement?bankAccountId=" + usd
                + "&closingBalance=200", null);
        assertThat(st.get("ledgerBalance").asString()).isEqualTo("200");
        assertThat(st.get("currencyState").asString()).isEqualTo("designated");
    }

    // ------------------------------------------------------------------ revaluation

    @Test
    @DisplayName("[R-8] period-end revaluation of a foreign bank reverses next day; settlement afterwards is not double counted")
    void revaluationThenSettlement() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        LocalDate d0 = LocalDate.now().minusDays(2);
        LocalDate d1 = LocalDate.now().minusDays(1);
        assertThat(rate(b, ccy, base, d0.minusDays(5).toString(), "3700").status()).isEqualTo(201);
        assertThat(rate(b, ccy, base, d1.toString(), "3800").status()).isEqualTo(201);
        Setup s = new Setup(b, base, ccy, LocalDate.now().toString());
        String usd = bank(b, ccy);
        Map<String, Object> dep = op(usd, counterpart(b, "deposit", "owner_contribution"), "owner_contribution", 100,
                null);
        dep.put("date", d0.toString());
        entryOf(moneyAs(b, "POST", "/accounts/cash-flow/deposit", dep));
        BigDecimal fx0 = fxGain(b.org());

        Res run = moneyAs(b, "POST", "/fx-revaluations", Map.of("asOf", d1.toString()));
        assertThat(run.status()).as(run.raw().body()).isEqualTo(200);
        BigDecimal atD1 = jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l"
                + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"accountId\" = ? AND"
                + " e.\"postingDate\" < ?::date + 1").params(usd, d1.toString()).query(BigDecimal.class).single();
        assertThat(atD1).as("revalued at 3,800 at period end").isEqualByComparingTo("380000");

        entryOf(withdraw(s, usd, 100, 3800)); // carrying excludes the reversed revaluation: realised 10,000
        assertThat(fxGain(b.org()).subtract(fx0)).as("revaluation reversed + realised = one gain")
                .isEqualByComparingTo("10000");
        assertThat(base(b.org(), usd)).isEqualByComparingTo("0");
        assertBooksBalance(b.org());

        JsonNode sum = okAs(b, 200, "GET", "/fx-treasury/summary?from=" + d0 + "&to=" + LocalDate.now(), null);
        assertThat(new BigDecimal(sum.get("realised").asString())).isEqualByComparingTo("10000");
        assertThat(new BigDecimal(sum.get("unrealised").asString())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("[R-8] two currencies on one account are revalued and recorded separately")
    void twoCurrenciesOneAccount() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String c1 = testCurrency();
        String c2 = testCurrency();
        String day = LocalDate.now().minusDays(1).toString();
        rate(b, c1, base, LocalDate.now().minusDays(10).toString(), "3700");
        rate(b, c2, base, LocalDate.now().minusDays(10).toString(), "4000");
        String ar = mappedCode(b.org(), "accounts_receivable");
        String revenue = accountCodeOfSection(b.org(), "revenue");
        postJe(b, "GEN", day, c1, 3600, null, null, List.of(JeLine.dr(ar, 10), JeLine.cr(revenue, 10)));
        postJe(b, "GEN", day, c2, 3900, null, null, List.of(JeLine.dr(ar, 10), JeLine.cr(revenue, 10)));
        assertThat(moneyAs(b, "POST", "/fx-revaluations", Map.of("asOf", day)).status()).isEqualTo(200);
        List<String> rows = jdbc.sql("SELECT \"currencyCode\" FROM \"FxRevaluation\" WHERE \"organizationId\" = ? AND"
                + " \"accountId\" = ? ORDER BY 1").params(b.org(), account(b.org(), ar)).query(String.class).list();
        assertThat(rows).containsExactlyInAnyOrder(c1, c2);
    }
}
