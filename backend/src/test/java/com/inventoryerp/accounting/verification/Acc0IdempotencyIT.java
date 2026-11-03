package com.inventoryerp.accounting.verification;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ACC-0 reproduction: idempotency, concurrency and failure recovery of postings (P1-a … P1-e). */
class Acc0IdempotencyIT extends AccountingFixtures {

    /** Run {@code n} copies of {@code task} released at the same instant; returns each outcome (value or error). */
    List<Object> race(int n, java.util.concurrent.Callable<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                try {
                    return task.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        go.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : fs) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }

    long entriesWithKey(String org, String key) {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"postingKey\" = ?")
                .params(org, key).query(Long.class).single();
    }

    @Test
    @DisplayName("[P1-a][owner:accounting][ACC-3] two concurrent posts with one postingKey → one entry, both get it")
    void concurrentSameKey() throws Exception {
        Books b = freshBooks();
        String cash = mappedCode(b.org(), "default_cash");
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String key = "acc0:race:" + UUID.randomUUID();
        String day = LocalDate.now().minusDays(1).toString();
        List<Object> results = race(2, () -> postJe(b, "GEN", day, null, null, null, key,
                List.of(dr(cash, 100), cr(revenue, 100))));
        assertThat(results).as("both callers succeed").allSatisfy(r -> assertThat(r).isInstanceOf(String.class));
        assertThat(new java.util.HashSet<>(results)).as("same entry returned").hasSize(1);
        assertThat(entriesWithKey(b.org(), key)).isEqualTo(1);
    }

    @Test
    @DisplayName("[P1-d][owner:accounting][ACC-3] concurrent posts of different documents to one account all land")
    void concurrentDifferentDocuments() throws Exception {
        Books b = freshBooks();
        String cash = mappedCode(b.org(), "default_cash");
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String day = LocalDate.now().minusDays(1).toString();
        List<Object> results = race(6, () -> postJe(b, "GEN", day, null, null, null, "acc0:many:" + UUID.randomUUID(),
                List.of(dr(cash, 10), cr(revenue, 10))));
        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(String.class));
        Map<String, BigDecimal> tb = tbAsOf(b.org(), day, TbView.POST_CLOSING);
        assertThat(tb.get(cash)).isEqualByComparingTo("60");
        LedgerInvariants.trialBalanceBalanced(tb);
        LedgerInvariants.entriesBalanced(jdbc, b.org(), null);
    }

    @Test
    @DisplayName("[P1-e][owner:accounting][ACC-3] a posting that fails before commit leaves nothing; the retry posts once")
    void failureThenRetry() {
        Books b = freshBooks();
        String cash = mappedCode(b.org(), "default_cash");
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String day = LocalDate.now().minusDays(1).toString();
        String key = "acc0:retry:" + UUID.randomUUID();
        jdbc.sql("UPDATE \"Account\" SET \"isActive\" = false WHERE \"organizationId\" = ? AND code = ?")
                .params(b.org(), revenue).update();
        long before = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(b.org())
                .query(Long.class).single();
        assertThatThrownBy(() -> postJe(b, "GEN", day, null, null, null, key, List.of(dr(cash, 100), cr(revenue, 100))))
                .as("posting to an inactive account fails");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(b.org())
                .query(Long.class).single()).as("no partial entry").isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalLine\" l WHERE l.\"organizationId\" = ? AND NOT EXISTS"
                + " (SELECT 1 FROM \"JournalEntry\" e WHERE e.id = l.\"journalEntryId\")").param(b.org())
                .query(Long.class).single()).as("no orphan lines").isZero();
        jdbc.sql("UPDATE \"Account\" SET \"isActive\" = true WHERE \"organizationId\" = ? AND code = ?")
                .params(b.org(), revenue).update();
        String first = postJe(b, "GEN", day, null, null, null, key, List.of(dr(cash, 100), cr(revenue, 100)));
        String again = postJe(b, "GEN", day, null, null, null, key, List.of(dr(cash, 100), cr(revenue, 100)));
        assertThat(again).isEqualTo(first);
        assertThat(entriesWithKey(b.org(), key)).isEqualTo(1);
    }

    @Test
    @DisplayName("[P1-b/P1-c][owner:accounting][ACC-3] opening balance retried with the same Idempotency-Key posts once")
    void openingBalanceRetrySameKey() {
        Books b = freshBooks();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + b.token());
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("date", LocalDate.now().minusDays(1).toString(), "accounts", List.of(
                Map.of("accountId", account(b.org(), mappedCode(b.org(), "default_cash")), "debit", 500)));
        Res first = call("POST", "/opening-balances", body, h);
        assertThat(first.status()).as(first.raw().body()).isLessThan(300);
        Res retry = call("POST", "/opening-balances", body, h);
        assertThat(retry.status()).as(retry.raw().body()).isLessThan(300);
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"postingKey\" LIKE"
                + " 'opening_balance:%'").param(b.org()).query(Long.class).single()).as("opening-balance entries")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("[P1-b][owner:accounting][ACC-3] opening balance without an Idempotency-Key is refused (no blind retry)")
    void openingBalanceRequiresKey() {
        Books b = freshBooks();
        Map<String, Object> body = Map.of("date", LocalDate.now().minusDays(1).toString(), "accounts", List.of(
                Map.of("accountId", account(b.org(), mappedCode(b.org(), "default_cash")), "debit", 500)));
        Res r = as(b, "POST", "/opening-balances", body);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"postingKey\" LIKE"
                + " 'opening_balance:%'").param(b.org()).query(Long.class).single()).isZero();
    }
}
