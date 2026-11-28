package com.inventoryerp.accounting;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Finance readiness audit 2026-11-23, fix step A3: the close process. Each test reproduces one finding:
 * <ul>
 *   <li>P2-1: a period whose profit and loss nets to zero could not be closed ("Nothing to close", or a zero
 *       retained-earnings line the engine refuses), and the year close then stayed blocked;</li>
 *   <li>P2-3: the close skipped an account missing from the 30-second chart cache (another API instance created it);</li>
 *   <li>P2-4: a VAT settlement could not be undone;</li>
 *   <li>P2-13: a sale's stock-posting job queued after its period ended (an offline till synced late) did not hold
 *       that period's close;</li>
 *   <li>lock race: locking a period did not wait for a concurrent reopen.</li>
 * </ul>
 * Periods are past months, so nothing dated today lands in them; postings are on the 10th–25th.
 */
class CloseProcessIT extends AccountingFixtures {

    private static final String CASH = "1100";
    private static final String BANK = "1200";

    @Autowired
    AccountResolverService chart;

    @Autowired
    DataSource dataSource;

    /** First day of the month {@code back} months ago. */
    private static LocalDate month(int back) {
        return LocalDate.now().withDayOfMonth(1).minusMonths(back);
    }

    private static LocalDate lastDay(LocalDate month) {
        return month.plusMonths(1).minusDays(1);
    }

    private String periodStatus(String id) {
        return jdbc.sql("SELECT status::text FROM \"FiscalPeriod\" WHERE id = ?").param(id).query(String.class).single();
    }

    private String entryStatus(String id) {
        return jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(id).query(String.class).single();
    }

    private long closingEntries(String org, String periodId) {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'period_close' AND \"sourceId\" = ?").params(org, periodId).query(Long.class).single();
    }

    /** Profit and loss accounts that do not net to zero over {@code [from, to]} (UTC days), closing entries included. */
    private Map<String, BigDecimal> openProfitAndLoss(String org, LocalDate from, LocalDate to) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.sql("SELECT a.code, sum(l.\"baseDebit\" - l.\"baseCredit\") AS net FROM"
                        + " \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" JOIN \"Account\" a ON"
                        + " a.id = l.\"accountId\" LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                        + " l.\"organizationId\" = ? AND e.status IN ('posted', 'reversed') AND e.\"postingDate\" >= ?::date"
                        + " AND e.\"postingDate\" < (?::date + 1) AND coalesce(a.\"reportSection\"::text,"
                        + " c.\"reportSection\"::text) IN ('revenue', 'other_income', 'contra_revenue', 'operating_expense',"
                        + " 'other_expense', 'cogs') GROUP BY a.code HAVING sum(l.\"baseDebit\" - l.\"baseCredit\") <> 0")
                .params(org, from.toString(), to.toString()).query().listOfRows()) {
            out.put((String) r.get("code"), (BigDecimal) r.get("net"));
        }
        return out;
    }

    private JsonNode checklist(Books b, String periodId) {
        return okAs(b, 200, "GET", "/fiscal-periods/" + periodId + "/close-checklist", null);
    }

    private static JsonNode check(JsonNode checklist, String key) {
        for (JsonNode c : checklist.get("checks")) {
            if (key.equals(c.get("key").asString())) {
                return c;
            }
        }
        throw new AssertionError("no check " + key);
    }

    private Res withKey(Books b, String method, String path, String key) {
        return call(method, path, null, Map.of("Authorization", "Bearer " + b.token(), "Idempotency-Key", key));
    }

    @Test
    @DisplayName("A3 (P2-1): a period with no profit and loss to close is closed without a closing entry")
    void zeroProfitAndLossPeriodCloses() {
        Books b = freshBooks();
        LocalDate m = month(2);
        List<String> periods = monthlyPeriods(b, month(3), m);
        String empty = periods.get(0);
        String period = periods.get(1);
        // Balance-sheet movements only: cash paid into the bank.
        postJe(b, m.withDayOfMonth(10).toString(), List.of(dr(BANK, 500), cr(CASH, 500)));

        Res close = as(b, "POST", "/fiscal-periods/" + period + "/close", null);
        assertThat(close.status()).as(close.raw().body()).isEqualTo(200);
        assertThat(close.body().get("journalEntryId").isNull()).isTrue();
        assertThat(close.body().get("netIncome").asString()).isEqualTo("0");
        assertThat(periodStatus(period)).isEqualTo("closed");
        assertThat(closingEntries(b.org(), period)).isZero();
        Map<String, Object> audit = jdbc.sql("SELECT \"newValues\"->>'status' AS status, \"newValues\"->>'netIncome' AS net,"
                        + " \"newValues\"->>'closingEntryId' AS entry FROM \"AuditLog\" WHERE \"organizationId\" = ? AND"
                        + " entity = 'FiscalPeriod' AND \"entityId\" = ? ORDER BY \"createdAt\" DESC LIMIT 1")
                .params(b.org(), period).query().singleRow();
        assertThat(audit.get("status")).isEqualTo("closed");
        assertThat(audit.get("net")).isEqualTo("0");
        assertThat(audit.get("entry")).isNull();

        // A month with no postings at all closes the same way; both reopen and close again.
        assertThat(okAs(b, 200, "POST", "/fiscal-periods/" + empty + "/close", null).get("journalEntryId").isNull())
                .isTrue();
        okAs(b, 200, "POST", "/fiscal-periods/" + period + "/reopen", null);
        assertThat(periodStatus(period)).isEqualTo("open");
        okAs(b, 200, "POST", "/fiscal-periods/" + period + "/close", null);
        assertThat(periodStatus(period)).isEqualTo("closed");
        LedgerInvariants.entriesBalanced(jdbc, b.org(), null);
    }

    @Test
    @DisplayName("A3 (P2-1): revenue equal to expenses is closed account against account, with no retained-earnings line")
    void breakEvenPeriodCloses() {
        Books b = freshBooks();
        LocalDate m = month(2);
        String period = monthlyPeriods(b, m).get(0);
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String expense = accountCodeOfSection(b.org(), "operating_expense");
        String day = m.withDayOfMonth(10).toString();
        postJe(b, day, List.of(dr(CASH, 300), cr(revenue, 300)));
        postJe(b, day, List.of(dr(expense, 300), cr(CASH, 300)));

        Res close = as(b, "POST", "/fiscal-periods/" + period + "/close", null);
        assertThat(close.status()).as(close.raw().body()).isEqualTo(200);
        assertThat(close.body().get("netIncome").asString()).isEqualTo("0");
        List<Map<String, Object>> lines = lines(close.body().get("journalEntryId").asString());
        assertThat(lines).extracting(l -> l.get("code")).containsExactlyInAnyOrder(revenue, expense);
        assertThat(lines).extracting(l -> l.get("code")).doesNotContain(mappedCode(b.org(), "retained_earnings"));
        assertThat(openProfitAndLoss(b.org(), m, lastDay(m))).isEmpty();
        LedgerInvariants.entriesBalanced(jdbc, b.org(), null);
    }

    @Test
    @DisplayName("IPR R2: an expense account with a net credit (a refund) is closed on the debit side, not refused")
    void abnormalBalanceAccountCloses() {
        Books b = freshBooks();
        LocalDate m = month(2);
        String period = monthlyPeriods(b, m).get(0);
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String expense = accountCodeOfSection(b.org(), "operating_expense");
        String day = m.withDayOfMonth(10).toString();
        postJe(b, day, List.of(dr(CASH, 500), cr(revenue, 500)));
        // Only a refund hits the expense account this period: its balance sits on the credit (abnormal) side.
        postJe(b, day, List.of(dr(CASH, 100), cr(expense, 100)));

        Res close = as(b, "POST", "/fiscal-periods/" + period + "/close", null);
        assertThat(close.status()).as(close.raw().body()).isEqualTo(200);
        assertThat(close.body().get("netIncome").asString()).isEqualTo("600");
        List<Map<String, Object>> lines = lines(close.body().get("journalEntryId").asString());
        assertThat(lines).extracting(l -> l.get("code")).contains(revenue, expense);
        assertThat(openProfitAndLoss(b.org(), m, lastDay(m))).isEmpty();
        assertThat(periodStatus(period)).isEqualTo("closed");
        LedgerInvariants.entriesBalanced(jdbc, b.org(), null);
    }

    @Test
    @DisplayName("A3 (P2-1): the year close closes periods with no profit and loss to close; only open P&L holds it")
    void yearCloseClosesZeroProfitAndLossPeriods() {
        Books b = freshBooks();
        for (String id : jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE \"organizationId\" = ?").param(b.org())
                .query(String.class).list()) {
            okAs(b, 204, "DELETE", "/fiscal-periods/" + id, null);
        }
        int y = LocalDate.now().getYear() - 2;
        JsonNode fy = okAs(b, 201, "POST", "/fiscal-years", Map.of("startDate", y + "-01-01", "periodicity",
                "quarterly"));
        String fyId = fy.get("id").asString();
        assertThat(fy.get("periods")).hasSize(4);
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String expense = accountCodeOfSection(b.org(), "operating_expense");
        postJe(b, y + "-02-10", List.of(dr(CASH, 1000), cr(revenue, 1000)));
        postJe(b, y + "-02-11", List.of(dr(expense, 400), cr(CASH, 400)));
        // Q2: a bank deposit only. Q3 and Q4: nothing.
        postJe(b, y + "-05-10", List.of(dr(BANK, 500), cr(CASH, 500)));
        String q1 = periodFor(b.org(), y + "-02-10");
        String q2 = periodFor(b.org(), y + "-05-10");
        okAs(b, 200, "POST", "/fiscal-periods/" + q1 + "/close", null);

        JsonNode list = okAs(b, 200, "GET", "/fiscal-years/" + fyId + "/close-checklist", null);
        JsonNode periodsClosed = check(list, "periods_closed");
        assertThat(periodsClosed.get("count").asInt()).as("Q2 has no profit and loss to close").isZero();
        assertThat(periodsClosed.get("label").asString()).isEqualTo("Every period with profit and loss closed");
        for (JsonNode s : list.get("signOffs")) {
            okAs(b, 200, "PUT", "/fiscal-years/" + fyId + "/close-checklist/" + s.get("key").asString(),
                    Map.of("signed", true, "note", "ok"));
        }
        JsonNode closed = okAs(b, 200, "POST", "/fiscal-years/" + fyId + "/close", Map.of("lockPeriods", false));
        assertThat(new BigDecimal(closed.get("netIncome").asString())).isEqualByComparingTo("600");
        assertThat(closed.get("zeroProfitAndLossPeriodsClosed").asInt()).isEqualTo(1);
        assertThat(closed.get("emptyPeriodsClosed").asInt()).isEqualTo(2);
        assertThat(periodStatus(q2)).isEqualTo("closed");
        assertThat(closingEntries(b.org(), q2)).isZero();
    }

    @Test
    @DisplayName("A3 (P2-1): a year whose periods still carry profit and loss stays blocked by them")
    void openProfitAndLossBlocksTheYear() {
        Books b = freshBooks();
        for (String id : jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE \"organizationId\" = ?").param(b.org())
                .query(String.class).list()) {
            okAs(b, 204, "DELETE", "/fiscal-periods/" + id, null);
        }
        int y = LocalDate.now().getYear() - 2;
        String fyId = okAs(b, 201, "POST", "/fiscal-years", Map.of("startDate", y + "-01-01", "periodicity",
                "quarterly")).get("id").asString();
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String expense = accountCodeOfSection(b.org(), "operating_expense");
        // Break-even, but each account still has to be closed: the period close must run.
        postJe(b, y + "-08-10", List.of(dr(CASH, 70), cr(revenue, 70)));
        postJe(b, y + "-08-11", List.of(dr(expense, 70), cr(CASH, 70)));
        JsonNode list = okAs(b, 200, "GET", "/fiscal-years/" + fyId + "/close-checklist", null);
        assertThat(check(list, "periods_closed").get("count").asInt()).isEqualTo(1);
        assertThat(list.get("readyToClose").asBoolean()).isFalse();
        okAs(b, 200, "POST", "/fiscal-periods/" + periodFor(b.org(), y + "-08-10") + "/close", null);
        assertThat(check(okAs(b, 200, "GET", "/fiscal-years/" + fyId + "/close-checklist", null), "periods_closed")
                .get("count").asInt()).isZero();
    }

    @Test
    @DisplayName("A3 (P2-3): the close reads an account this instance has not cached instead of leaving it out")
    void closeReadsAccountsMissingFromTheCache() {
        Books b = freshBooks();
        LocalDate m = month(2);
        String period = monthlyPeriods(b, m).get(0);
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String expense = accountCodeOfSection(b.org(), "operating_expense");
        String day = m.withDayOfMonth(10).toString();
        postJe(b, day, List.of(dr(expense, 100), cr(CASH, 100)));
        // This instance caches the chart; another instance then creates a revenue account (no invalidation reaches
        // this one) and a sale is posted to it.
        inTenant(b.org(), b.ownerId(), () -> chart.allMeta());
        jdbc.sql("INSERT INTO \"Account\" SELECT (jsonb_populate_record(NULL::\"Account\", to_jsonb(a)"
                        + " || jsonb_build_object('id', ?::text, 'code', '4199', 'name', 'Revenue added on another instance'))).*"
                        + " FROM \"Account\" a WHERE a.id = ?")
                .params(UUID.randomUUID().toString(), account(b.org(), revenue)).update();
        postJe(b, day, List.of(dr(CASH, 250), cr("4199", 250)));

        JsonNode closed = okAs(b, 200, "POST", "/fiscal-periods/" + period + "/close", null);
        assertThat(closed.get("netIncome").asString()).isEqualTo("150");
        assertThat(openProfitAndLoss(b.org(), m, lastDay(m))).isEmpty();
    }

    @Test
    @DisplayName("A3: locking a period waits for a concurrent reopen, then refuses the reopened period")
    void lockWaitsForAConcurrentReopen() throws Exception {
        Books b = freshBooks();
        LocalDate m = month(2);
        String period = monthlyPeriods(b, m).get(0);
        postJe(b, m.withDayOfMonth(10).toString(), List.of(dr(CASH, 40), cr(accountCodeOfSection(b.org(), "revenue"),
                40)));
        okAs(b, 200, "POST", "/fiscal-periods/" + period + "/close", null);

        // Another transaction holds the period row, as a reopen does, and reopens it.
        try (Connection held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            try (PreparedStatement ps = held.prepareStatement(
                    "SELECT id FROM \"FiscalPeriod\" WHERE id = ? FOR UPDATE")) {
                ps.setString(1, period);
                ps.executeQuery().close();
            }
            try (PreparedStatement ps = held.prepareStatement(
                    "UPDATE \"FiscalPeriod\" SET status = 'open', \"closedAt\" = NULL WHERE id = ?")) {
                ps.setString(1, period);
                ps.executeUpdate();
            }
            CompletableFuture<Res> lock = CompletableFuture.supplyAsync(() -> as(b, "POST",
                    "/fiscal-periods/" + period + "/lock", null));
            Thread.sleep(1500);
            assertThat(lock).as("the lock waits for the reopen to commit").isNotDone();
            held.commit();
            Res res = lock.get(30, TimeUnit.SECONDS);
            assertThat(res.status()).as(res.raw().body()).isEqualTo(400);
            assertThat(res.body().get("message").asString())
                    .isEqualTo("Fiscal period 'P " + m + "' is open; close it before locking");
        }
        assertThat(periodStatus(period)).isEqualTo("open");
    }

    @Test
    @DisplayName("A3 (P2-4): the latest VAT settlement can be undone; the period is corrected and settled again")
    void vatSettlementUndo() {
        Books b = freshBooks();
        LocalDate m = month(2);
        monthlyPeriods(b, m);
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String expense = accountCodeOfSection(b.org(), "operating_expense");
        String payable = mappedCode(b.org(), "tax_payable");
        String receivable = mappedCode(b.org(), "tax_receivable");
        String aFrom = m.toString();
        String aTo = m.withDayOfMonth(15).toString();
        String bFrom = m.withDayOfMonth(16).toString();
        String bTo = m.withDayOfMonth(25).toString();
        String day10 = m.withDayOfMonth(10).toString();
        String day12 = m.withDayOfMonth(12).toString();
        // Sale 100 + 18 VAT, purchase 50 + 9 VAT.
        postJe(b, day10, List.of(dr(CASH, 118), cr(revenue, 100), cr(payable, 18)));
        postJe(b, day10, List.of(dr(expense, 50), dr(receivable, 9), cr(CASH, 59)));

        JsonNode first = okAs(b, 200, "POST", "/tax-settlements", Map.of("from", aFrom, "to", aTo));
        String settlementA = first.get("id").asString();
        String entryA = first.get("journalEntryId").asString();
        // A purchase invoice found after filing cannot be booked into the settled period.
        List<JeLine> late = List.of(dr(expense, 10), dr(receivable, 2), cr(CASH, 12));
        assertThatThrownBy(() -> postJe(b, day12, late)).hasMessageStartingWith("VAT is settled through");

        Res undo = moneyAs(b, "POST", "/tax-settlements/" + settlementA + "/reverse", null);
        assertThat(undo.status()).as(undo.raw().body()).isEqualTo(200);
        assertThat(undo.body().get("id").asString()).isEqualTo(settlementA);
        assertThat(undo.body().get("reversedAt").isNull()).isFalse();
        assertThat(undo.body().get("reversedBy").asString()).isEqualTo(b.ownerId());
        String reversal = undo.body().get("reversalJournalEntryId").asString();
        assertThat(entryStatus(entryA)).isEqualTo("reversed");
        assertThat(jdbc.sql("SELECT \"reversalOfId\" FROM \"JournalEntry\" WHERE id = ?").param(reversal)
                .query(String.class).single()).isEqualTo(entryA);
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"organizationId\" = ? AND entity = 'TaxSettlement'"
                + " AND \"entityId\" = ? AND \"newValues\"->>'status' = 'reversed'").params(b.org(), settlementA)
                .query(Long.class).single()).isEqualTo(1);

        // The period's VAT measures as before: neither the settlement nor its reversal is VAT of the period.
        JsonNode preview = okAs(b, 200, "GET", "/tax-settlements/preview?from=" + aFrom + "&to=" + aTo, null);
        assertThat(new BigDecimal(preview.get("outputTax").asString())).isEqualByComparingTo("18");
        assertThat(new BigDecimal(preview.get("inputTax").asString())).isEqualByComparingTo("9");
        assertThat(preview.get("settledThrough").isNull()).isTrue();

        // The lock is lifted: the late purchase is booked and the period settled again.
        postJe(b, day12, late);
        JsonNode again = okAs(b, 200, "POST", "/tax-settlements", Map.of("from", aFrom, "to", aTo));
        assertThat(new BigDecimal(again.get("inputTax").asString())).isEqualByComparingTo("11");
        Map<String, BigDecimal> tb = tbAsOf(b.org(), aTo, TbView.POST_CLOSING);
        assertThat(tb.getOrDefault(receivable, BigDecimal.ZERO)).as("input VAT cleared").isEqualByComparingTo("0");
        assertThat(tb.get(payable)).as("net VAT due 18 - 11").isEqualByComparingTo("-7");

        // Only the latest settlement can be undone, and only once.
        String settlementA2 = again.get("id").asString();
        postJe(b, m.withDayOfMonth(20).toString(), List.of(dr(CASH, 59), cr(revenue, 50), cr(payable, 9)));
        JsonNode second = okAs(b, 200, "POST", "/tax-settlements", Map.of("from", bFrom, "to", bTo));
        assertThat(second.get("journalEntryId").isNull()).as("no input VAT: nothing to offset").isTrue();
        Res notLatest = moneyAs(b, "POST", "/tax-settlements/" + settlementA2 + "/reverse", null);
        assertThat(notLatest.status()).isEqualTo(400);
        assertThat(notLatest.body().get("message").asString()).isEqualTo("Only the latest VAT settlement can be"
                + " reversed: VAT is settled through " + bTo + "; reverse that settlement first");
        String key = UUID.randomUUID().toString();
        Res undoB = withKey(b, "POST", "/tax-settlements/" + second.get("id").asString() + "/reverse", key);
        assertThat(undoB.status()).as(undoB.raw().body()).isEqualTo(200);
        assertThat(undoB.body().get("reversalJournalEntryId").isNull()).isTrue();
        Res replay = withKey(b, "POST", "/tax-settlements/" + second.get("id").asString() + "/reverse", key);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("reversedAt").asString()).isEqualTo(undoB.body().get("reversedAt").asString());
        assertThat(moneyAs(b, "POST", "/tax-settlements/" + settlementA2 + "/reverse", null).status()).isEqualTo(200);
        Res twice = moneyAs(b, "POST", "/tax-settlements/" + settlementA2 + "/reverse", null);
        assertThat(twice.status()).isEqualTo(400);
        assertThat(twice.body().get("message").asString()).isEqualTo("This VAT settlement is already reversed");
        assertThat(moneyAs(b, "POST", "/tax-settlements/" + UUID.randomUUID() + "/reverse", null).status())
                .isEqualTo(404);
        Books other = freshBooks();
        assertThat(moneyAs(other, "POST", "/tax-settlements/" + settlementA + "/reverse", null).status())
                .as("another organisation's settlement").isEqualTo(404);

        // Every settlement stays listed; the reversed ones say so.
        JsonNode list = okAs(b, 200, "GET", "/tax-settlements", null);
        assertThat(list).hasSize(3);
        list.forEach(s -> assertThat(s.get("reversedAt").isNull()).isFalse());
        LedgerInvariants.entriesBalanced(jdbc, b.org(), null);
        // The ledger integrity check (A0, I11) holds undone settlements to their reversal, not to a posted entry.
        assertThat(taxLockCheck(b).get("ok").asBoolean()).as(taxLockCheck(b).toString()).isTrue();
        jdbc.sql("UPDATE \"TaxSettlement\" SET \"reversalJournalEntryId\" = NULL WHERE id = ?").param(settlementA)
                .update();
        JsonNode broken = taxLockCheck(b);
        assertThat(broken.get("ok").asBoolean()).isFalse();
        assertThat(broken.get("rows").get(0).get("kind").asString()).isEqualTo("settlement_reversal_not_posted");
    }

    @Test
    @DisplayName("A3 (P2-13): a sale's unposted stock holds the close of the sale's period, whenever its job was queued")
    void lateQueuedSaleCostHoldsTheClose() {
        Books b = freshBooks();
        LocalDate m = month(2);
        String period = monthlyPeriods(b, m).get(0);
        String name = "P " + m;
        // A till sale of the 12th (an offline checkout keeps its own time as the POS invoice's issue date) ...
        String partner = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                + " \"updatedAt\") VALUES (?, ?, ?, 'A3 customer', false, true, now())").params(partner, b.org(),
                "A3-" + partner.substring(0, 8)).update();
        String invoice = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Invoice\" (id, \"organizationId\", \"invoiceNumber\", \"partnerId\", status, \"issueDate\","
                + " \"totalAmount\", \"amountResidual\", \"updatedAt\") VALUES (?, ?, ?, ?, 'posted'::\"InvoiceStatus\","
                + " ?::timestamp, 100, 0, now())").params(invoice, b.org(), "A3-" + invoice.substring(0, 8), partner,
                m.withDayOfMonth(12) + " 18:30:00").update();
        // ... synced after the month ended: its stock-posting job was queued today, and failed.
        String job = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"StockPostingJob\" (id, \"organizationId\", \"invoiceId\", \"invoiceNumber\", status,"
                + " attempts, \"lastError\", \"idempotencyKey\") VALUES (?, ?, ?, 'A3-INV', 'failed', 5,"
                + " 'Product deleted', ?)").params(job, b.org(), invoice, "a3:" + job).update();

        Res held = as(b, "POST", "/fiscal-periods/" + period + "/close", null);
        assertThat(held.status()).as(held.raw().body()).isEqualTo(400);
        assertThat(held.body().get("message").asString()).isEqualTo("Cannot close '" + name + "': 1 stock-posting"
                + " job(s) in this period have not posted COGS yet. Drain the stock-posting queue, and resolve any"
                + " failed jobs in the Posting Monitor, before closing.");
        assertThat(check(checklist(b, period), "stock_posting").get("count").asInt()).isEqualTo(1);

        // The job is resolved; a sale-line exception raised today for that sale holds the close the same way.
        jdbc.sql("UPDATE \"StockPostingJob\" SET status = 'done' WHERE id = ?").param(job).update();
        String exception = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", \"invoiceId\", description, quantity,"
                + " kind, reason, status) VALUES (?, ?, ?, 'A3 sale line', 1, 'missing_recipe', 'test', 'open')")
                .params(exception, b.org(), invoice).update();
        Res stillHeld = as(b, "POST", "/fiscal-periods/" + period + "/close", null);
        assertThat(stillHeld.status()).as(stillHeld.raw().body()).isEqualTo(400);
        assertThat(stillHeld.body().get("message").asString()).isEqualTo("Cannot close '" + name + "': 1 open"
                + " inventory exception(s) in this period — sale lines whose stock/COGS was not posted. Resolve them in"
                + " the Posting Monitor before closing.");
        assertThat(check(checklist(b, period), "inventory_exceptions").get("count").asInt()).isEqualTo(1);

        jdbc.sql("UPDATE \"InventoryException\" SET status = 'resolved' WHERE id = ?").param(exception).update();
        okAs(b, 200, "POST", "/fiscal-periods/" + period + "/close", null);
        assertThat(periodStatus(period)).isEqualTo("closed");
    }

    /** The VAT check (I11, {@code tax_lock}) of {@code GET /reports/accounting/integrity}. */
    private JsonNode taxLockCheck(Books b) {
        for (JsonNode check : okAs(b, 200, "GET", "/reports/accounting/integrity", null).get("checks")) {
            if ("tax_lock".equals(check.get("key").asString())) {
                return check;
            }
        }
        throw new AssertionError("no tax_lock check");
    }
}
