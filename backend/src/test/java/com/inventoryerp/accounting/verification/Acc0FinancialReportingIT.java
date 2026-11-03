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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ACC-0 reproduction: financial reporting and period / year close (findings B1, B2, B10). Every test asserts the
 * correct accounting outcome; a failing test is the evidence for a confirmed defect (docs/accounting/ACC-0-verification.md).
 */
class Acc0FinancialReportingIT extends AccountingFixtures {

    record Coa(String cash, String revenue, String cogs, String opex, String otherIncome, String re) {}

    Coa coa(Books b) {
        return new Coa(mappedCode(b.org(), "default_cash"), accountCodeOfSection(b.org(), "revenue"),
                accountCodeOfSection(b.org(), "cogs"), accountCodeOfSection(b.org(), "operating_expense"),
                accountCodeOfSection(b.org(), "other_income"), mappedCode(b.org(), "retained_earnings"));
    }

    @Test
    @DisplayName("[B1][owner:accounting][ACC-1] P&L of a closed month still shows its operating result")
    void pnlOfClosedMonth() {
        Books b = freshBooks();
        LocalDate[] months = lastAndThisMonth();
        monthlyPeriods(b, months);
        Coa c = coa(b);
        String day = months[0].plusDays(14).toString();
        postJe(b, day, List.of(dr(c.cash(), 100000), cr(c.revenue(), 100000)));
        postJe(b, day, List.of(dr(c.cogs(), 55000), cr(c.cash(), 55000)));
        postJe(b, day, List.of(dr(c.opex(), 25000), cr(c.cash(), 25000)));
        String from = months[0].toString();
        String to = months[0].plusMonths(1).minusDays(1).toString();
        assertThat(new BigDecimal(pnl(b, from, to).get("netProfit").asString())).as("P&L before close")
                .isEqualByComparingTo("20000");

        String closing = closePeriod(b, day);
        assertThat(closing).as("closing entry").isNotNull();
        LedgerInvariants.postedEntryImmutable(jdbc, txm, closing);

        // Balance sheet side: closing moved the result into retained earnings; both TB views balance.
        Map<String, BigDecimal> post = tbAsOf(b.org(), to, TbView.POST_CLOSING);
        Map<String, BigDecimal> pre = tbAsOf(b.org(), to, TbView.PRE_CLOSING);
        LedgerInvariants.trialBalanceBalanced(post);
        LedgerInvariants.trialBalanceBalanced(pre);
        assertThat(post.get(c.re())).as("retained earnings after close").isEqualByComparingTo("-20000");
        assertThat(pre.get(c.revenue())).as("pre-closing revenue").isEqualByComparingTo("-100000");
        JsonNode bs = balanceSheet(b, to);
        LedgerInvariants.balanceSheetBalanced(bs);
        LedgerInvariants.entriesBalanced(jdbc, b.org(), null);

        // Profit and loss must still report the month's operating revenue and expenses.
        JsonNode p = pnl(b, from, to);
        assertThat(new BigDecimal(p.get("revenue").asString())).as("revenue of the closed month")
                .isEqualByComparingTo("100000");
        assertThat(new BigDecimal(p.get("netProfit").asString())).as("net profit of the closed month")
                .isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("[B2][owner:accounting][ACC-1] fiscal-year close uses base-currency amounts")
    void fiscalYearCloseInBaseCurrency() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        rate(b, ccy, base, LocalDate.now().minusDays(10).toString(), "2");
        Coa c = coa(b);
        int y = LocalDate.now().getYear();
        String fyId = okAs(b, 201, "POST", "/fiscal-years", Map.of("startDate", y + "-01-01", "periodicity",
                "monthly")).get("id").asString();
        String day = LocalDate.now().minusDays(1).toString();
        // Foreign sale 1,000 ccy @2 = 2,000 base; base-currency expense 500.
        postJe(b, "GEN", day, ccy, "2", null, null, List.of(dr(c.cash(), 1000), cr(c.revenue(), 1000)));
        postJe(b, day, List.of(dr(c.opex(), 500), cr(c.cash(), 500)));
        LedgerInvariants.fxLinesValid(jdbc, b.org(), base, null);
        for (String pid : jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE \"organizationId\" = ?"
                + " ORDER BY \"startDate\"").param(b.org()).query(String.class).list()) {
            as(b, "POST", "/fiscal-periods/" + pid + "/close", null);
        }
        for (JsonNode s : okAs(b, 200, "GET", "/fiscal-years/" + fyId + "/close-checklist", null).get("signOffs")) {
            okAs(b, 200, "PUT", "/fiscal-years/" + fyId + "/close-checklist/" + s.get("key").asString(),
                    Map.of("signed", true));
        }
        Res closed = as(b, "POST", "/fiscal-years/" + fyId + "/close", Map.of("lockPeriods", false));
        assertThat(closed.status()).as("year close → " + closed.raw().body()).isEqualTo(200);
        assertThat(new BigDecimal(closed.body().get("netIncome").asString())).as("net income in base currency")
                .isEqualByComparingTo("1500");
        Map<String, BigDecimal> byCode = new java.util.HashMap<>();
        for (JsonNode a : okAs(b, 200, "GET", "/fiscal-years/" + fyId + "/closing-balances", null).get("accounts")) {
            byCode.put(a.get("code").asString(), new BigDecimal(a.get("balance").asString()));
        }
        assertThat(byCode.get(c.cash())).as("closing cash balance (base)").isEqualByComparingTo("1500");
        assertThat(byCode.get(c.re())).as("closing retained earnings (base)").isEqualByComparingTo("1500");
    }

    @Test
    @DisplayName("[B10][owner:accounting][ACC-1] snapshot P&L keeps other income and honours the from date")
    void snapshotPnl() {
        Books b = freshBooks();
        Coa c = coa(b);
        String day = LocalDate.now().minusDays(1).toString();
        postJe(b, day, List.of(dr(c.cash(), 800), cr(c.revenue(), 800)));
        postJe(b, day, List.of(dr(c.cash(), 300), cr(c.otherIncome(), 300)));
        String tomorrow = LocalDate.now().plusDays(1).toString();
        JsonNode live = pnl(b, day, tomorrow);
        assertThat(live.get("source").asString()).isEqualTo("live");
        assertThat(new BigDecimal(live.get("netProfit").asString())).isEqualByComparingTo("1100");
        okAs(b, 201, "POST", "/reports/accounting/rebuild-snapshots", null);
        JsonNode snap = pnl(b, day, tomorrow);
        assertThat(new BigDecimal(snap.get("otherIncome").asString())).as("other income, source "
                + snap.get("source").asString()).isEqualByComparingTo("300");
        assertThat(new BigDecimal(snap.get("netProfit").asString())).as("net profit, source "
                + snap.get("source").asString()).isEqualByComparingTo("1100");
        JsonNode empty = pnl(b, tomorrow, tomorrow);
        assertThat(new BigDecimal(empty.get("revenue").asString())).as("revenue for a range with no activity, source "
                + empty.get("source").asString()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("[B10][owner:accounting][ACC-1] balance sheet includes an inactive account that still has a balance")
    void balanceSheetInactiveAccount() {
        Books b = freshBooks();
        Coa c = coa(b);
        String asset = accountCodeOfSection(b.org(), "non_current_assets");
        String day = LocalDate.now().minusDays(1).toString();
        postJe(b, day, List.of(dr(c.cash(), 1000), cr(c.revenue(), 1000)));
        postJe(b, day, List.of(dr(asset, 300), cr(c.cash(), 300)));
        jdbc.sql("UPDATE \"Account\" SET \"isActive\" = false WHERE \"organizationId\" = ? AND code = ?")
                .params(b.org(), asset).update();
        jdbc.sql("DELETE FROM \"ReportBalanceSheetSnapshot\" WHERE \"organizationId\" = ?").param(b.org()).update();
        JsonNode bs = balanceSheet(b, LocalDate.now().toString());
        assertThat(new BigDecimal(bs.get("totalAssets").asString())).as("total assets incl. inactive account")
                .isEqualByComparingTo("1000");
        LedgerInvariants.balanceSheetBalanced(bs);
    }

    @Test
    @DisplayName("[B10][owner:accounting][ACC-1] close → reopen → post → reclose zeroes P&L once, no duplicate closing")
    void reopenAndReclose() {
        Books b = freshBooks();
        LocalDate[] months = lastAndThisMonth();
        monthlyPeriods(b, months);
        Coa c = coa(b);
        String day = months[0].plusDays(9).toString();
        String to = months[0].plusMonths(1).minusDays(1).toString();
        postJe(b, day, List.of(dr(c.cash(), 400), cr(c.revenue(), 400)));
        closePeriod(b, day);
        okAs(b, 200, "POST", "/fiscal-periods/" + periodFor(b.org(), day) + "/reopen", null);
        postJe(b, day, List.of(dr(c.cash(), 100), cr(c.revenue(), 100)));
        Res reclose = as(b, "POST", "/fiscal-periods/" + periodFor(b.org(), day) + "/close", null);
        assertThat(reclose.status()).as("reclose → " + reclose.raw().body()).isEqualTo(200);
        Map<String, BigDecimal> post = tbAsOf(b.org(), to, TbView.POST_CLOSING);
        assertThat(post.getOrDefault(c.revenue(), BigDecimal.ZERO)).as("revenue after reclose (post-closing)")
                .isEqualByComparingTo("0");
        assertThat(post.get(c.re())).as("retained earnings after reclose").isEqualByComparingTo("-500");
        LedgerInvariants.trialBalanceBalanced(post);
        long activeClosings = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = 'period_close' AND status = 'posted' AND \"reversalOfId\" IS NULL")
                .param(b.org()).query(Long.class).single();
        assertThat(activeClosings).as("active closing entries").isLessThanOrEqualTo(2);
    }
}
