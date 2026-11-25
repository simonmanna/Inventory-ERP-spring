package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.shared.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Finance readiness audit B5 (cash and bank dates): a shift's cash postings fall on its business date, and a locked
 * bank reconciliation freezes the bank account's ledger through the statement day.
 */
class CashBankDatesB5IT extends AccountingFixtures {

    private static final String BANK = "1100";

    @Autowired
    CashSessionService sessions;

    @Test
    @DisplayName("B5: a locked bank reconciliation refuses postings to the account on or before its day;"
            + " a default-dated reversal moves to today")
    void reconciledBankDaysAreFrozen() {
        Books b = freshBooks();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String early = postManualJe(b, today.minusDays(2).toString(), List.of(dr(BANK, 100), cr(revenue, 100)));
        Res lock = moneyAs(b, "POST", "/bank-reconciliation/statement", Map.of("bankAccountId", account(b.org(), BANK),
                "asOf", today.minusDays(1).toString(), "closingBalance", 100));
        assertThat(lock.status()).as(lock.raw().body()).isIn(200, 201);

        assertThatThrownBy(() -> postManualJe(b, today.minusDays(1).toString(), List.of(dr(BANK, 5), cr(revenue, 5))))
                .isInstanceOf(ApiException.class)
                .hasMessage("Bank account " + BANK + " is reconciled through " + today.minusDays(1)
                        + "; nothing can be posted to it on " + today.minusDays(1) + ". Date it after "
                        + today.minusDays(1) + ".");
        assertThat(postManualJe(b, today.toString(), List.of(dr(BANK, 5), cr(revenue, 5)))).isNotNull();

        Res reversed = as(b, "POST", "/journal-entries/" + early + "/reverse", null);
        assertThat(reversed.status()).as(reversed.raw().body()).isIn(200, 201);
        String day = jdbc.sql("SELECT to_char(\"postingDate\", 'YYYY-MM-DD') FROM \"JournalEntry\" WHERE"
                + " \"reversalOfId\" = ?").param(early).query(String.class).single();
        assertThat(day).as("not into the reconciled range").isEqualTo(today.toString());
    }

    @Test
    @DisplayName("B5: a shift's cash posts on its business date: now for today, the end of an earlier day otherwise")
    void shiftPostsOnItsBusinessDate() {
        Books b = freshBooks();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant forToday = inTenant(b.org(), b.ownerId(), () -> sessions.shiftPostingDate(Map.of("businessDate",
                today)));
        assertThat(Duration.between(forToday, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        if (today.getDayOfYear() > 1) {
            // the fixture's yearly period covers yesterday
            Instant forYesterday = inTenant(b.org(), b.ownerId(), () -> sessions.shiftPostingDate(Map.of(
                    "businessDate", today.minusDays(1))));
            assertThat(forYesterday).isEqualTo(AccountingDay.endInclusive(today.minusDays(1), ZoneOffset.UTC));
        }
    }
}
