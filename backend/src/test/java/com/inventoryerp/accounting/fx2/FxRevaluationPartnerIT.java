package com.inventoryerp.accounting.fx2;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** FX-2 C: monetary scope per account, partner-level AR/AP revaluation, engine validation of foreign lines. */
class FxRevaluationPartnerIT extends AccountingFixtures {

    String partner(Books b, String name, boolean customer) {
        return okAs(b, 201, "POST", "/partners", Map.of("code", "C-" + UUID.randomUUID().toString().substring(0, 6),
                "name", name, customer ? "isCustomer" : "isSupplier", true)).get("id").asString();
    }

    String newAccount(Books b, String categoryKey, String name) {
        String code = "19" + UUID.randomUUID().toString().substring(0, 5);
        String category = jdbc.sql("SELECT id FROM \"AccountCategory\" WHERE key = ?").param(categoryKey)
                .query(String.class).single();
        okAs(b, 201, "POST", "/accounts", Map.of("code", code, "name", name, "categoryId", category));
        return code;
    }

    String fxBank(Books b, String ccy) {
        String code = "12" + UUID.randomUUID().toString().substring(0, 6);
        okAs(b, 201, "POST", "/accounts/cash-flow", Map.of("code", code, "name", "FX2 bank " + ccy, "accountType", "bank",
                "currencyId", ccy));
        return code;
    }

    /** Net revaluation (adjustment, before its reversal) on an account for one partner at {@code day}. */
    BigDecimal revalued(String org, String accountId, String partnerId, LocalDate day) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                        + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND"
                        + " l.\"accountId\" = ? AND l.\"partnerId\" IS NOT DISTINCT FROM ? AND e.\"sourceType\" ="
                        + " 'fx_revaluation' AND e.\"postingDate\" <= ?::timestamp")
                .params(org, accountId, partnerId, day + " 23:59:59").query(BigDecimal.class).single();
    }

    /** Partner's base balance on an account at {@code day}, revaluations (not yet reversed) included. */
    BigDecimal partnerBalance(String org, String accountId, String partnerId, LocalDate day) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                        + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND"
                        + " l.\"accountId\" = ? AND l.\"partnerId\" = ? AND e.status::text IN ('posted', 'reversed') AND"
                        + " e.\"postingDate\" <= ?::timestamp")
                .params(org, accountId, partnerId, day + " 23:59:59").query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("two customers in one currency: one revaluation line each, subledger per partner equals the GL")
    void perPartnerReceivables() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        LocalDate d0 = LocalDate.now().minusDays(20);
        LocalDate d1 = LocalDate.now().minusDays(5);
        rate(b, ccy, base, d0.toString(), "100");
        String ar = mappedCode(b.org(), "accounts_receivable");
        String arId = account(b.org(), ar);
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String alpha = partner(b, "FX2 Alpha", true);
        String beta = partner(b, "FX2 Beta", true);
        postJe(b, "GEN", d0.toString(), ccy, "100", null, null, List.of(dr(ar, 10).partner(alpha), cr(revenue, 10)));
        postJe(b, "GEN", d0.toString(), ccy, "110", null, null, List.of(dr(ar, 20).partner(beta), cr(revenue, 20)));
        rate(b, ccy, base, d1.toString(), "120");

        JsonNode preview = okAs(b, 200, "POST", "/fx-revaluations/preview", Map.of("asOf", d1.toString()));
        assertThat(preview).hasSize(2);
        okAs(b, 200, "POST", "/fx-revaluations", Map.of("asOf", d1.toString()));
        assertThat(revalued(b.org(), arId, alpha, d1)).as("10 × (120 − 100)").isEqualByComparingTo("200");
        assertThat(revalued(b.org(), arId, beta, d1)).as("20 × (120 − 110)").isEqualByComparingTo("200");
        assertThat(partnerBalance(b.org(), arId, alpha, d1)).as("Alpha at the closing rate").isEqualByComparingTo("1200");
        assertThat(partnerBalance(b.org(), arId, beta, d1)).as("Beta at the closing rate").isEqualByComparingTo("2400");
        assertThat(jdbc.sql("SELECT count(*) FROM \"FxRevaluation\" WHERE \"organizationId\" = ? AND \"accountId\" = ?"
                + " AND \"partnerId\" IS NOT NULL").params(b.org(), arId).query(Long.class).single()).isEqualTo(2);

        okAs(b, 200, "POST", "/fx-revaluations", Map.of("asOf", d1.toString()));
        assertThat(revalued(b.org(), arId, alpha, d1)).as("same-day re-run posts nothing").isEqualByComparingTo("200");
        assertThat(partnerBalance(b.org(), arId, alpha, d1.plusDays(1))).as("reversed next day")
                .isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("a foreign loan is revalued; prepaid, the deposit account and accounts set non-monetary are not")
    void monetaryScope() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        LocalDate d0 = LocalDate.now().minusDays(20);
        LocalDate d1 = LocalDate.now().minusDays(5);
        rate(b, ccy, base, d0.toString(), "100");
        String bank = fxBank(b, ccy);
        String loan = newAccount(b, "non_current_liability", "FX2 USD loan");
        String prepaid = newAccount(b, "prepaid_expense", "FX2 prepaid");
        String other = newAccount(b, "current_liability", "FX2 accrual");
        postJe(b, "GEN", d0.toString(), ccy, "100", null, null, List.of(dr(bank, 50), cr(loan, 50)));
        postJe(b, "GEN", d0.toString(), ccy, "100", null, null, List.of(dr(prepaid, 5), cr(bank, 5)));
        postJe(b, "GEN", d0.toString(), ccy, "100", null, null, List.of(dr(bank, 7), cr(other, 7)));
        okAs(b, 200, "PUT", "/fx-revaluations/accounts/" + account(b.org(), other), Map.of("fxRevaluation",
                "non_monetary"));
        rate(b, ccy, base, d1.toString(), "110");

        JsonNode preview = okAs(b, 200, "POST", "/fx-revaluations/preview", Map.of("asOf", d1.toString()));
        List<String> codes = new java.util.ArrayList<>();
        preview.forEach(l -> codes.add(l.get("accountCode").asString()));
        assertThat(codes).contains(loan, bank).doesNotContain(prepaid, other);
        okAs(b, 200, "POST", "/fx-revaluations", Map.of("asOf", d1.toString()));
        assertThat(revalued(b.org(), account(b.org(), loan), null, d1)).as("loan 50 × 10 = 500 more owed")
                .isEqualByComparingTo("-500");

        JsonNode treatments = okAs(b, 200, "GET", "/fx-revaluations/accounts", null);
        for (JsonNode t : treatments) {
            if (t.get("code").asString().equals(prepaid)) {
                assertThat(t.get("monetary").asBoolean()).isFalse();
            }
            if (t.get("code").asString().equals(loan)) {
                assertThat(t.get("monetary").asBoolean()).isTrue();
            }
        }
        assertThat(as(b, "PUT", "/fx-revaluations/accounts/" + account(b.org(), loan), Map.of("fxRevaluation", "maybe"))
                .status()).isEqualTo(400);
    }

    @Test
    @DisplayName("engine: no foreign line on a functional bank; foreign receivable and payable lines need a partner")
    void engineValidation() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        String day = LocalDate.now().minusDays(2).toString();
        rate(b, ccy, base, LocalDate.now().minusDays(10).toString(), "100");
        String cash = accountCodeOfCategory(b.org(), "cash");
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String ar = mappedCode(b.org(), "accounts_receivable");
        String ap = mappedCode(b.org(), "accounts_payable");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> postJe(b, "GEN", day, ccy, "100", null, null,
                List.of(dr(cash, 10), cr(revenue, 10))))
                .hasMessageContaining("is held in " + base + "; a " + ccy + " line cannot post to it");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> postJe(b, "GEN", day, ccy, "100", null, null,
                List.of(dr(ar, 10), cr(revenue, 10))))
                .hasMessageContaining("Foreign-currency receivable lines need a partner");
        String expense = accountCodeOfSection(b.org(), "operating_expense");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> postJe(b, "GEN", day, ccy, "100", null, null,
                List.of(dr(expense, 10), cr(ap, 10))))
                .hasMessageContaining("Foreign-currency payable lines need a partner");
    }

    @Test
    @DisplayName("legacy partnerless foreign receivable lines block the run until acknowledged")
    void warningsBlockUntilAcknowledged() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        LocalDate d0 = LocalDate.now().minusDays(20);
        LocalDate d1 = LocalDate.now().minusDays(5);
        rate(b, ccy, base, d0.toString(), "100");
        String ar = mappedCode(b.org(), "accounts_receivable");
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String c = partner(b, "FX2 legacy", true);
        String je = postJe(b, "GEN", d0.toString(), ccy, "100", null, null, List.of(dr(ar, 10).partner(c),
                cr(revenue, 10)));
        // Simulate a pre-FX-2 entry: a copy of it whose receivable line has no partner. Posted lines cannot be changed,
        // so the copy is built as a draft and then marked posted in the same transaction (as the engine does), with no
        // superuser bypass: this also runs as the application role.
        new org.springframework.transaction.support.TransactionTemplate(txm).executeWithoutResult(t -> {
            String copy = UUID.randomUUID().toString();
            jdbc.sql("CREATE TEMP TABLE fx2_entry ON COMMIT DROP AS SELECT * FROM \"JournalEntry\" WHERE id = ?")
                    .param(je).update();
            jdbc.sql("UPDATE fx2_entry SET id = ?, \"entryNumber\" = \"entryNumber\" || '-L', status = 'draft'")
                    .param(copy).update();
            jdbc.sql("INSERT INTO \"JournalEntry\" SELECT * FROM fx2_entry").update();
            jdbc.sql("CREATE TEMP TABLE fx2_lines ON COMMIT DROP AS SELECT * FROM \"JournalLine\" WHERE"
                    + " \"journalEntryId\" = ?").param(je).update();
            jdbc.sql("UPDATE fx2_lines SET id = gen_random_uuid()::text, \"journalEntryId\" = ?, \"partnerId\" = NULL")
                    .param(copy).update();
            jdbc.sql("INSERT INTO \"JournalLine\" SELECT * FROM fx2_lines").update();
            jdbc.sql("UPDATE \"JournalEntry\" SET status = 'posted' WHERE id = ?").param(copy).update();
        });
        rate(b, ccy, base, d1.toString(), "120");
        JsonNode w = okAs(b, 200, "POST", "/fx-revaluations/warnings", Map.of("asOf", d1.toString()));
        assertThat(w).hasSize(1);
        assertThat(w.get(0).get("code").asString()).isEqualTo("partnerless_foreign_lines");
        Res refused = moneyAs(b, "POST", "/fx-revaluations", Map.of("asOf", d1.toString()));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.raw().body()).contains("Resolve or acknowledge the revaluation warnings first");
        Res ok = moneyAs(b, "POST", "/fx-revaluations", Map.of("asOf", d1.toString(), "acknowledgeWarnings", true));
        assertThat(ok.status()).as(ok.raw().body()).isEqualTo(200);
        assertThat(revalued(b.org(), account(b.org(), ar), null, d1)).isEqualByComparingTo("200");
    }
}
