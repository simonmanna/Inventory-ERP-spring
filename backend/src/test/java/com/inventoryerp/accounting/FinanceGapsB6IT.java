package com.inventoryerp.accounting;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Finance readiness audit B6 (test gaps G3 / G4): cross-tenant refusals, HTTP replay and concurrency on the money
 * routes the earlier suites did not cover. Each test runs in its own fresh organization(s).
 */
class FinanceGapsB6IT extends AccountingFixtures {

    private static final String BANK = "1200";

    private String day0() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private Map<String, String> keyed(Books b, String key) {
        return keyed(b.token(), key);
    }

    private Map<String, String> keyed(String token, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", key);
        return h;
    }

    private String userHolding(Books b, String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, ?, false, ?, now())")
                .params(role, b.org(), "B6-" + role, permissions).update();
        String email = "approver-" + UUID.randomUUID().toString().substring(0, 8) + "@" + b.code() + ".test";
        createUser(b.org(), email, "Approver@12345", role);
        return login(b.code(), email, "Approver@12345");
    }

    private long entries(String org) {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(org)
                .query(Long.class).single();
    }

    private String status(String table, String id) {
        return jdbc.sql("SELECT status::text FROM \"" + table + "\" WHERE id = ?").param(id).query(String.class).single();
    }

    /** Fund the bank account so transfers out of it pass the funds check. */
    private void fund(Books b, int amount) {
        postManualJe(b, day0(), List.of(dr(BANK, amount), cr("3100", amount)));
    }

    /** Another postable account of the bank account's category, not a register drawer: a transfer destination. */
    private String otherCashAccount(Books b) {
        return jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"Account\" bank ON bank.\"organizationId\" ="
                + " a.\"organizationId\" AND bank.code = ? AND bank.\"categoryId\" = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND a.id <> bank.id AND a.\"isPostable\" AND a.\"isActive\" AND a.\"deletedAt\""
                + " IS NULL AND NOT EXISTS (SELECT 1 FROM \"CashRegister\" r WHERE r.\"defaultAccountId\" = a.id)"
                + " ORDER BY a.code LIMIT 1").params(BANK, b.org()).query(String.class).single();
    }

    /** Run {@code n} copies of {@code work} released at the same moment; returns their statuses. */
    private List<Integer> race(int n, Callable<Res> work) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Res>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return work.call();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Res> f : futures) {
                statuses.add(f.get().status());
            }
            return statuses;
        }
    }

    @Test
    @DisplayName("B6: another organization cannot read, reverse, close or move money in these books")
    void crossTenantRefusals() {
        Books a = freshBooks();
        Books b = freshBooks();
        String entry = postManualJe(a, day0(), List.of(dr(BANK, 40), cr("3100", 40)));
        String period = periodFor(a.org(), day0());
        long before = entries(a.org());

        // not found answers 200 with an empty body (Nest parity); either way nothing of the entry is shown
        Res read = as(b, "GET", "/journal-entries/" + entry, null);
        assertThat(read.status()).isIn(200, 403, 404);
        assertThat(read.raw().body()).doesNotContain(entry);
        assertThat(moneyAs(b, "POST", "/journal-entries/" + entry + "/reverse", Map.of("description", "x")).status())
                .isIn(400, 403, 404);
        assertThat(status("JournalEntry", entry)).isEqualTo("posted");

        assertThat(moneyAs(b, "POST", "/fiscal-periods/" + period + "/close", null).status()).isIn(400, 403, 404);
        assertThat(moneyAs(b, "POST", "/fiscal-periods/" + period + "/reopen", null).status()).isIn(400, 403, 404);
        assertThat(status("FiscalPeriod", period)).isEqualTo("open");

        Res transfer = moneyAs(b, "POST", "/treasury/transfer", Map.of("fromAccountId", account(a.org(), BANK),
                "toAccountId", account(a.org(), "1100"), "amount", 5, "date", day0()));
        assertThat(transfer.status()).as(transfer.raw().body()).isIn(400, 403, 404);
        Res withdraw = moneyAs(b, "POST", "/accounts/cash-flow/withdraw", Map.of("accountId", account(a.org(), BANK),
                "counterpartAccountId", account(a.org(), "3100"), "operationType", "owner_drawing", "amount", 5,
                "description", "B6"));
        assertThat(withdraw.status()).as(withdraw.raw().body()).isIn(400, 403, 404);
        assertThat(moneyAs(b, "POST", "/tax-settlements/" + UUID.randomUUID() + "/reverse", Map.of()).status())
                .isIn(400, 403, 404);
        assertThat(moneyAs(b, "POST", "/cash-sessions/" + UUID.randomUUID() + "/force-close", Map.of()).status())
                .isIn(400, 403, 404);

        assertThat(entries(a.org())).as("nothing was written to the other books").isEqualTo(before);
    }

    @Test
    @DisplayName("B6: replaying a reversal or a period close with the same key returns the first answer, once")
    void replays() {
        Books a = freshBooks();
        String entry = postManualJe(a, day0(), List.of(dr(BANK, 30), cr("3100", 30)));
        String key = UUID.randomUUID().toString();
        Res first = call("POST", "/journal-entries/" + entry + "/reverse", Map.of("description", "B6"), keyed(a, key));
        assertThat(first.status()).as(first.raw().body()).isIn(200, 201);
        Res again = call("POST", "/journal-entries/" + entry + "/reverse", Map.of("description", "B6"), keyed(a, key));
        // the replay answers from the cache (jsonb: same content, keys reordered, decimals as numbers)
        assertThat(again.status()).isEqualTo(first.status());
        assertThat(again.body().get("id").asString()).isEqualTo(first.body().get("id").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"reversalOfId\" = ?").param(entry)
                .query(Long.class).single()).isEqualTo(1);

        String old = LocalDate.now(ZoneOffset.UTC).minusYears(3).withDayOfMonth(15).toString();
        String period = jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND \"startDate\" <= ?::timestamp AND \"endDate\" >= ?::timestamp LIMIT 1").params(a.org(), old, old)
                .query(String.class).optional().orElse(null);
        if (period != null) {
            String closeKey = UUID.randomUUID().toString();
            Res close = call("POST", "/fiscal-periods/" + period + "/close", null, keyed(a, closeKey));
            Res closeAgain = call("POST", "/fiscal-periods/" + period + "/close", null, keyed(a, closeKey));
            assertThat(closeAgain.status()).isEqualTo(close.status());
            assertThat(String.valueOf(closeAgain.body() == null ? null : closeAgain.body().get("id")))
                    .isEqualTo(String.valueOf(close.body() == null ? null : close.body().get("id")));
        }
    }

    @Test
    @DisplayName("B6: parallel transfers never overdraw the source account")
    void parallelTransfersRespectFunds() throws Exception {
        Books a = freshBooks();
        fund(a, 100);
        String from = account(a.org(), BANK);
        String to = otherCashAccount(a);
        String dest = to;
        List<Integer> statuses = race(6, () -> moneyAs(a, "POST", "/treasury/transfer", Map.of("fromAccountId", from,
                "toAccountId", dest, "amount", 30, "date", day0())));
        long succeeded = statuses.stream().filter(s -> s == 200).count();
        assertThat(succeeded).as(statuses.toString()).isBetween(1L, 3L);
        assertThat(jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND"
                + " e.status::text IN ('posted', 'reversed') AND l.\"accountId\" = ?").params(a.org(), from)
                .query(java.math.BigDecimal.class).single()).as("never below zero").isNotNegative();
    }

    @Test
    @DisplayName("B6: two parallel posts of the same draft post it once")
    void parallelDraftPostsOnce() throws Exception {
        Books a = freshBooks();
        Map<String, Object> debit = new LinkedHashMap<>();
        debit.put("accountId", account(a.org(), BANK));
        debit.put("debit", 12);
        Map<String, Object> credit = new LinkedHashMap<>();
        credit.put("accountId", account(a.org(), "3100"));
        credit.put("credit", 12);
        Res draft = moneyAs(a, "POST", "/journal-entries", Map.of("journalCode", "GEN", "date", day0(),
                "description", "B6 draft", "lines", List.of(debit, credit)));
        assertThat(draft.status()).as(draft.raw().body()).isEqualTo(200);
        String id = draft.body().get("id").asString();
        // the maker may not approve their own draft: a second user with journal_entry:post races
        String approver = userHolding(a, "journal_entry:read", "journal_entry:post", "account:read");
        List<Integer> statuses = race(4, () -> call("POST", "/journal-entries/" + id + "/post", null,
                keyed(approver, UUID.randomUUID().toString())));
        assertThat(statuses.stream().filter(s -> s == 200 || s == 201).count()).as(statuses.toString())
                .isEqualTo(1);
        assertThat(status("JournalEntry", id)).isEqualTo("posted");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalLine\" WHERE \"journalEntryId\" = ?").param(id)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("B6: parallel opens of one register open a single cash session")
    void parallelSessionOpenOnce() throws Exception {
        Books a = freshBooks();
        String code = "REG-" + UUID.randomUUID().toString().substring(0, 6);
        Res register = moneyAs(a, "POST", "/cash-registers", Map.of("code", code, "name", "B6 till"));
        assertThat(register.status()).as(register.raw().body()).isEqualTo(201);
        String registerId = register.body().get("id").asString();
        List<Integer> statuses = race(4, () -> moneyAs(a, "POST", "/cash-sessions/open", Map.of("cashRegisterId",
                registerId, "openingFloat", 0)));
        assertThat(statuses.stream().filter(s -> s == 200 || s == 201).count()).as(statuses.toString())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"CashSession\" WHERE \"cashRegisterId\" = ? AND status::text ="
                + " 'open'").param(registerId).query(Long.class).single()).isEqualTo(1);
    }
}
