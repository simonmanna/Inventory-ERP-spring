package com.inventoryerp.accounting.verification;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.RevaluationService;
import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** ACC-0 reproduction: foreign currency (findings B3, B4, B14). */
class Acc0FxIT extends AccountingFixtures {

    @Autowired
    RevaluationService revaluation;

    /** A postable, active, monetary ("allowReconciliation") account of the given classification, by code. */
    String monetary(Books b, String classification) {
        return jdbc.sql("SELECT a.code FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                        + " a.\"organizationId\" = ? AND c.\"allowReconciliation\" = true AND c.classification::text = ?"
                        + " AND a.\"isPostable\" AND a.\"isActive\" AND a.\"deletedAt\" IS NULL ORDER BY a.code LIMIT 1")
                .params(b.org(), classification).query(String.class).optional().orElse(null);
    }

    /** Net base movement the revaluation runs posted on {@code code}. */
    BigDecimal revaluedOn(Books b, String code) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE"
                + " e.\"organizationId\" = ? AND e.\"sourceType\" = 'fx_revaluation' AND e.status = 'posted' AND a.code = ?")
                .params(b.org(), code).query(BigDecimal.class).single();
    }

    void run(Books b, LocalDate asOf) {
        inTenant(b.org(), b.ownerId(), () -> revaluation.run(Instant.parse(asOf + "T20:00:00Z")));
    }

    @Test
    @Tag("acc-known-defect")
    @DisplayName("[B3][owner:accounting][ACC-2] revaluation: +400 once, same-day rerun posts nothing, later run "
            + "adjusts to the new rate only")
    void receivableRevaluation() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        LocalDate d0 = LocalDate.now().minusDays(20);
        rate(b, ccy, base, d0.toString(), "1.08");
        String asset = monetary(b, "asset");
        String revenue = accountCodeOfSection(b.org(), "revenue");
        // EUR-style receivable 10,000 @1.08 = 10,800 base.
        String sale = postJe(b, "GEN", d0.plusDays(1).toString(), ccy, "1.08", null, null,
                List.of(dr(asset, 10000), cr(revenue, 10000)));
        LedgerInvariants.fxLinesValid(jdbc, b.org(), base, List.of(sale));

        LocalDate d1 = LocalDate.now().minusDays(5);
        rate(b, ccy, base, d1.toString(), "1.12");
        run(b, d1);
        assertThat(revaluedOn(b, asset)).as("unrealised gain after first run (10,000 × 1.12 − 10,800)")
                .isEqualByComparingTo("400");
        run(b, d1);
        assertThat(revaluedOn(b, asset)).as("same-date rerun must post nothing more").isEqualByComparingTo("400");

        LocalDate d2 = LocalDate.now().minusDays(1);
        rate(b, ccy, base, d2.toString(), "1.15");
        run(b, d2);
        assertThat(revaluedOn(b, asset)).as("cumulative revaluation at 1.15 (10,000 × 1.15 − 10,800)")
                .isEqualByComparingTo("700");
        LedgerInvariants.entriesBalanced(jdbc, b.org(), null);
    }

    @Test
    @DisplayName("[B3][owner:accounting][ACC-2] payables in foreign currency are revalued too (loss when rate rises)")
    void payableRevaluation() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        LocalDate d0 = LocalDate.now().minusDays(20);
        rate(b, ccy, base, d0.toString(), "1.08");
        String liability = monetary(b, "liability");
        assertThat(liability).as("a monetary (revaluable) liability account exists in the template").isNotNull();
        String expense = accountCodeOfSection(b.org(), "operating_expense");
        postJe(b, "GEN", d0.plusDays(1).toString(), ccy, "1.08", null, null,
                List.of(dr(expense, 5000), cr(liability, 5000)));
        LocalDate d1 = LocalDate.now().minusDays(5);
        rate(b, ccy, base, d1.toString(), "1.12");
        run(b, d1);
        assertThat(revaluedOn(b, liability)).as("payable grows by 5,000 × 0.04 = 200 (credit)")
                .isEqualByComparingTo("-200");
    }

    @Test
    @Tag("acc-known-defect")
    @DisplayName("[B4][owner:accounting][ACC-2] one organization's exchange rate never changes another's")
    void ratesAreTenantScoped() {
        Books a = freshBooks();
        Books other = freshBooks();
        String ccy = testCurrency();
        String base = baseCurrency(a.org());
        String day = LocalDate.now().minusDays(2).toString();
        Res mine = rate(a, ccy, base, day, "5");
        assertThat(mine.status()).as(mine.raw().body()).isLessThan(300);
        Res theirs = rate(other, ccy, baseCurrency(other.org()), day, "7");
        assertThat(theirs.status()).as("other org records its own rate → " + theirs.raw().body()).isLessThan(300);
        assertThat(resolvedRate(a, ccy, base, day)).as("org A's own rate after org B wrote one")
                .isEqualByComparingTo("5");
        assertThat(resolvedRate(other, ccy, baseCurrency(other.org()), day)).as("org B's rate")
                .isEqualByComparingTo("7");
    }

    @Test
    @Tag("acc-known-defect")
    @DisplayName("[B14][owner:accounting][ACC-2] a foreign-currency expense posts with its currency and rate")
    void foreignExpense() {
        String ccy = testCurrency();
        String base = baseCurrency(demoOrg);
        ok(201, "POST", "/currencies/rates", Map.of("fromCode", ccy, "toCode", base, "asOf",
                LocalDate.now().minusDays(3).toString(), "rate", 2));
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        String expenseAcc = account(demoOrg, accountCodeOfSection(demoOrg, "operating_expense"));
        String category = ok(201, "POST", "/expense-categories", Map.of("name", "ACC0 FX " + sfx, "ledgerAccountId",
                expenseAcc)).get("id").asString();
        String supplier = demoPartner("ACC0 FX supplier", true);
        JsonNode exp = demoMoney("POST", "/expenses", Map.of("title", "Foreign spares " + sfx, "amount", 100,
                "expenseDate", today(), "paymentType", "CREDIT", "categoryId", category, "supplierId", supplier,
                "currencyCode", ccy, "exchangeRate", 2, "foreignAmount", 50));
        String id = exp.get("id").asString();
        List<String> entries = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceId\" = ?")
                .params(demoOrg, id).query(String.class).list();
        assertThat(entries).as("expense accrual entry").isNotEmpty();
        LedgerInvariants.entriesBalanced(jdbc, demoOrg, entries);
        List<Map<String, Object>> lines = lines(entries.getFirst());
        assertThat(lines).as("expense lines carry the document currency").allSatisfy(l ->
                assertThat(l.get("currencyId")).isEqualTo(ccy));
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntryFxRate\" WHERE \"journalEntryId\" = ?")
                .param(entries.getFirst()).query(Long.class).single()).as("rate snapshot").isEqualTo(1);
        LedgerInvariants.fxLinesValid(jdbc, demoOrg, base, entries);
    }
}
