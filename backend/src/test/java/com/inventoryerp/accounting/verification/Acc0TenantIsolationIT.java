package com.inventoryerp.accounting.verification;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** ACC-0: tenant isolation, period locks and branch dimensions of the ledger. */
class Acc0TenantIsolationIT extends AccountingFixtures {

    String branch(Books b, String code) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(id, b.org(), code, "Branch " + code).update();
        return id;
    }

    @Test
    @DisplayName("[iso][owner:accounting][ACC-0] another organization cannot read a journal entry or ledger")
    void crossTenantReadsRefused() {
        Books a = freshBooks();
        Books other = freshBooks();
        String cash = mappedCode(a.org(), "default_cash");
        String entry = postJe(a, LocalDate.now().minusDays(1).toString(),
                List.of(dr(cash, 250), cr(accountCodeOfSection(a.org(), "revenue"), 250)));
        Res foreign = as(other, "GET", "/journal-entries/" + entry, null);
        // Nest answers 200 with an empty body for an unknown id (parity); what matters is that nothing leaks.
        assertThat(foreign.raw().body()).as("org B reading org A's entry → " + foreign.status() + " "
                + foreign.raw().body()).doesNotContain(entry).doesNotContain(a.org());
        JsonNode list = okAs(other, 200, "GET", "/journal-entries", null);
        assertThat(list.toString()).doesNotContain(entry);
        Res ledger = as(other, "GET", "/reports/accounting/account-ledger/" + account(a.org(), cash), null);
        assertThat(ledger.status() == 404 || !ledger.raw().body().contains(entry)).as(ledger.raw().body()).isTrue();
        LedgerInvariants.postedEntryImmutable(jdbc, txm, entry);
    }

    @Test
    @DisplayName("[iso][owner:accounting][ACC-0] nothing can be posted into a locked period")
    void lockedPeriodRefusesPostings() {
        Books b = freshBooks();
        LocalDate[] months = lastAndThisMonth();
        monthlyPeriods(b, months);
        String cash = mappedCode(b.org(), "default_cash");
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String day = months[0].plusDays(3).toString();
        postJe(b, day, List.of(dr(cash, 10), cr(revenue, 10)));
        closePeriod(b, day);
        okAs(b, 200, "POST", "/fiscal-periods/" + periodFor(b.org(), day) + "/lock", null);
        assertThatThrownBy(() -> postJe(b, day, List.of(dr(cash, 5), cr(revenue, 5)))).as("post into locked period");
        assertThat(as(b, "POST", "/fiscal-periods/" + periodFor(b.org(), day) + "/reopen", null).status())
                .as("locked period cannot reopen").isGreaterThanOrEqualTo(400);
    }

    @Test
    @DisplayName("[iso][owner:accounting][ACC-0] two branches, one customer: branch ledgers add up to the organization")
    void branchesReconcile() {
        Books b = freshBooks();
        String b1 = branch(b, "B1");
        String b2 = branch(b, "B2");
        String customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Shared customer', true, now())").params(customer, b.org(), "C-" + customer.substring(0, 6))
                .update();
        String ar = mappedCode(b.org(), "accounts_receivable");
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String day = LocalDate.now().minusDays(1).toString();
        postJe(b, day, List.of(dr(ar, 300).partner(customer).branch(b1), cr(revenue, 300).branch(b1)));
        postJe(b, day, List.of(dr(ar, 200).partner(customer).branch(b2), cr(revenue, 200).branch(b2)));
        Map<String, BigDecimal> perBranch = new java.util.HashMap<>();
        for (Map<String, Object> r : jdbc.sql("SELECT l.\"branchId\", sum(l.\"baseDebit\" - l.\"baseCredit\") AS net"
                + " FROM \"JournalLine\" l JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"organizationId\" = ?"
                + " AND a.code = ? GROUP BY l.\"branchId\"").params(b.org(), ar).query().listOfRows()) {
            perBranch.put((String) r.get("branchId"), (BigDecimal) r.get("net"));
        }
        assertThat(perBranch).as("branch dimension kept on every line").containsOnlyKeys(b1, b2);
        assertThat(perBranch.get(b1).add(perBranch.get(b2))).isEqualByComparingTo(
                tbAsOf(b.org(), day, TbView.POST_CLOSING).get(ar));
        assertThat(perBranch.get(b1)).isEqualByComparingTo("300");
        LedgerInvariants.trialBalanceBalanced(tbAsOf(b.org(), day, TbView.POST_CLOSING));
    }
}
