package com.inventoryerp.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.idempotency.IdempotencyService;
import com.inventoryerp.kernel.idempotency.IdempotencyService.Result;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S094 C1a (D-IDEM-1): idempotency lease and fencing, tests IL-1…IL-10 and TP-6 of the ADR. The service-level tests
 * run real transactions through the production transaction manager and listener; IL-7, IL-9 (HTTP) and TP-6 go through
 * the HTTP interceptor on a test-only money route ({@link TestIdempotencyLeaseController}).
 */
class IdempotencyLeaseIT extends ApiTestSupport {

    private static final String PATH = "/api/v1/test-kernel/lease/payments";

    @Autowired
    IdempotencyService idempotency;

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterAll
    void stopPool() {
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------------------------------------ IL-1, IL-2

    @Test
    @DisplayName("IL-1: a retry inside the lease is refused; after it, the retry takes over and the stalled original is fenced out")
    void stalledInsideTransaction() throws Exception {
        String key = "il1-" + UUID.randomUUID();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        Future<Object> original = pool.submit(() -> attempt(() -> idempotency.executeWithKey(key, "body", "POST", PATH,
                null, () -> {
                    new TransactionTemplate(txm).executeWithoutResult(s -> {
                        TestIdempotencyLeaseController.marker(jdbc, key + "-A");
                        inside.countDown();
                        await(resume);
                    });
                    return new Result(false, 201, Map.of("by", "A"));
                })));
        assertThat(inside.await(30, TimeUnit.SECONDS)).isTrue();

        assertPending(() -> tenant(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null, this::mustNotRun)));

        expireLease(key);
        Result retry = tenant(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null, () -> {
            new TransactionTemplate(txm).executeWithoutResult(s -> TestIdempotencyLeaseController.marker(jdbc, key + "-B"));
            return new Result(false, 201, Map.of("by", "B"));
        }));
        assertThat(retry.replayed()).isFalse();
        assertThat(attemptOf(key)).isEqualTo(2);

        resume.countDown();
        Object first = original.get(30, TimeUnit.SECONDS);
        // The original's commit was refused by the fence; it answers with the result of the attempt that won.
        assertThat(first).isInstanceOfSatisfying(Result.class, r -> {
            assertThat(r.replayed()).isTrue();
            assertThat(((JsonNode) r.body()).get("by").asString()).isEqualTo("B");
        });
        assertThat(markers(key)).containsExactly(key + "-B");
        Result third = tenant(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null, this::mustNotRun));
        assertThat(third.replayed()).isTrue();
        assertThat(((JsonNode) third.body()).get("by").asString()).isEqualTo("B");
    }

    @Test
    @DisplayName("IL-2: a worker stopped before its transaction loses the key after the lease and can no longer commit")
    void stoppedBeforeTransaction() throws Exception {
        String key = "il2-" + UUID.randomUUID();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        Future<Object> stale = pool.submit(() -> attempt(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null,
                () -> {
                    claimed.countDown();
                    await(resume);
                    new TransactionTemplate(txm).executeWithoutResult(s -> TestIdempotencyLeaseController.marker(jdbc,
                            key + "-stale"));
                    return new Result(false, 201, Map.of("by", "stale"));
                })));
        assertThat(claimed.await(30, TimeUnit.SECONDS)).isTrue();
        expireLease(key);
        Result taken = tenant(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null, () -> {
            new TransactionTemplate(txm).executeWithoutResult(s -> TestIdempotencyLeaseController.marker(jdbc, key + "-new"));
            return new Result(false, 201, Map.of("by", "new"));
        }));
        assertThat(taken.replayed()).isFalse();
        assertThat(attemptOf(key)).isEqualTo(2);

        resume.countDown();
        assertThat(stale.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(Result.class,
                r -> assertThat(((JsonNode) r.body()).get("by").asString()).isEqualTo("new"));
        assertThat(markers(key)).containsExactly(key + "-new");
    }

    @Test
    @DisplayName("IL-2b: a stale worker whose key is still pending is told the key was taken over, and commits nothing")
    void staleWorkerWhileTheRetryRuns() throws Exception {
        String key = "il2b-" + UUID.randomUUID();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch resumeStale = new CountDownLatch(1);
        CountDownLatch retryInside = new CountDownLatch(1);
        CountDownLatch resumeRetry = new CountDownLatch(1);
        Future<Object> stale = pool.submit(() -> attempt(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null,
                () -> {
                    claimed.countDown();
                    await(resumeStale);
                    new TransactionTemplate(txm).executeWithoutResult(s -> TestIdempotencyLeaseController.marker(jdbc,
                            key + "-stale"));
                    return new Result(false, 201, Map.of("by", "stale"));
                })));
        assertThat(claimed.await(30, TimeUnit.SECONDS)).isTrue();
        expireLease(key);
        Future<Object> retry = pool.submit(() -> attempt(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null,
                () -> {
                    retryInside.countDown();
                    await(resumeRetry);
                    new TransactionTemplate(txm).executeWithoutResult(s -> TestIdempotencyLeaseController.marker(jdbc,
                            key + "-retry"));
                    return new Result(false, 201, Map.of("by", "retry"));
                })));
        assertThat(retryInside.await(30, TimeUnit.SECONDS)).isTrue();

        resumeStale.countDown();
        assertThat(stale.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(mapper.valueToTree(e.body()).get("code").asString()).isEqualTo("OPERATION_PENDING");
            assertThat(mapper.valueToTree(e.body()).get("message").asString()).contains("took this operation over");
        });
        resumeRetry.countDown();
        assertThat(retry.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(Result.class,
                r -> assertThat(r.replayed()).isFalse());
        assertThat(markers(key)).containsExactly(key + "-retry");
    }

    // ------------------------------------------------------------------------------------------------ IL-3 … IL-6

    @Test
    @DisplayName("IL-3: a crash after the commit and before completion never runs the operation again")
    void crashAfterCommit() {
        String key = "il3-" + UUID.randomUUID();
        IdempotencyService crashing = new IdempotencyService(jdbc, mapper) {
            @Override
            public void complete(BusinessOutcome.Operation op, int statusCode, Object body) {
                throw new IllegalStateException("connection lost after business commit");
            }
        };
        assertThatThrownBy(() -> tenant(() -> crashing.executeWithKey(key, "body", "POST", PATH, null, () -> {
            new TransactionTemplate(txm).executeWithoutResult(s -> TestIdempotencyLeaseController.marker(jdbc, key + "-1"));
            return new Result(false, 201, Map.of("by", "first"));
        }))).hasMessageContaining("connection lost");
        assertThat(jdbc.sql("SELECT \"committedAttempt\" FROM \"IdempotencyRecord\" WHERE key = ?").param(key)
                .query(Integer.class).single()).isEqualTo(1);

        assertPending(() -> tenant(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null, this::mustNotRun)));
        expireLease(key);
        // Before ADR-S094 the record was deleted and the operation ran a second time here.
        assertPending(() -> tenant(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null, this::mustNotRun)));
        assertThat(markers(key)).containsExactly(key + "-1");
    }

    @Test
    @DisplayName("IL-4: simultaneous first requests with one key: one runs, the other is refused")
    void simultaneousFirstRequests() throws Exception {
        String key = "il4-" + UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CyclicBarrier start = new CyclicBarrier(2);
        List<Future<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(pool.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                return attempt(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null, () -> {
                    runs.incrementAndGet();
                    await(release);
                    new TransactionTemplate(txm).executeWithoutResult(s -> TestIdempotencyLeaseController.marker(jdbc,
                            key + "-run"));
                    return new Result(false, 201, Map.of("ok", true));
                }));
            }));
        }
        // the refused call returns at once; the owner waits for the release
        Thread.sleep(500);
        release.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : calls) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        assertThat(runs.get()).isEqualTo(1);
        assertThat(outcomes).filteredOn(o -> o instanceof Result).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof ApiException e && e.status() == HttpStatus.CONFLICT).hasSize(1);
        assertThat(markers(key)).hasSize(1);
    }

    @Test
    @DisplayName("IL-5: the same key with a different body is refused (unchanged)")
    void differentBody() {
        String key = "il5-" + UUID.randomUUID();
        tenant(() -> idempotency.executeWithKey(key, "body-1", "POST", PATH, null,
                () -> new Result(false, 201, Map.of("ok", true))));
        assertThatThrownBy(() -> tenant(() -> idempotency.executeWithKey(key, "body-2", "POST", PATH, null,
                this::mustNotRun)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("different request body");
    }

    @Test
    @DisplayName("IL-6: two retries racing to take over an abandoned key: exactly one wins")
    void racingTakeovers() throws Exception {
        String key = "il6-" + UUID.randomUUID();
        assertThatThrownBy(() -> tenant(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null, () -> {
            throw new IllegalStateException("worker died before writing");
        }))).hasMessageContaining("worker died");
        expireLease(key);
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CyclicBarrier start = new CyclicBarrier(2);
        List<Future<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(pool.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                return attempt(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null, () -> {
                    runs.incrementAndGet();
                    await(release);
                    return new Result(false, 201, Map.of("ok", true));
                }));
            }));
        }
        Thread.sleep(500);
        release.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : calls) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        assertThat(runs.get()).isEqualTo(1);
        assertThat(outcomes).filteredOn(o -> o instanceof ApiException e && e.status() == HttpStatus.CONFLICT).hasSize(1);
        assertThat(attemptOf(key)).isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------------------ IL-7, IL-9, TP-6

    @Test
    @DisplayName("IL-7: a failed attempt whose work rolled back releases the key; an immediate retry runs once")
    void rollbackReleasesTheKey() {
        String tag = "il7-" + UUID.randomUUID();
        Map<String, String> headers = Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", tag);
        Res failed = call("POST", "/test-kernel/lease/payments", Map.of("tag", tag, "mode", "rollback"), headers);
        assertThat(failed.status()).isEqualTo(500);
        Res retried = call("POST", "/test-kernel/lease/payments", Map.of("tag", tag, "mode", "rollback"), headers);
        assertThat(retried.status()).as(retried.raw().body()).isEqualTo(200);
        assertThat(retried.body().get("run").asInt()).isEqualTo(2);
        assertThat(markers(tag)).containsExactly(tag + "-2");
    }

    @Test
    @DisplayName("IL-9: a nested REQUIRES_NEW write inside a fenced attempt: no self-deadlock, evidence kept, no re-run")
    void nestedTransactionLeavesEvidence() {
        String key = "il9-" + UUID.randomUUID();
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertThatThrownBy(() -> tenant(() ->
                idempotency.executeWithKey(key, "body", "POST", PATH, null, () -> {
                    new TransactionTemplate(txm).executeWithoutResult(s -> {
                        // the outer transaction writes (and so locks) the record before the nested one commits
                        BusinessOutcome.record(jdbc, Map.of("step", 1), false);
                        TransactionTemplate inner = new TransactionTemplate(txm);
                        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                        inner.executeWithoutResult(t -> TestIdempotencyLeaseController.marker(jdbc, key + "-inner"));
                        throw new IllegalStateException("outer work failed");
                    });
                    return mustNotRun();
                }))).hasMessageContaining("outer work failed"));
        assertThat(jdbc.sql("SELECT count(*) FROM \"IdempotencyEvidence\" WHERE key = ? AND attempt = 1").param(key)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT \"responseJson\"::text FROM \"IdempotencyRecord\" WHERE key = ?").param(key)
                .query(String.class).single()).isEqualTo("{}");
        expireLease(key);
        assertPending(() -> tenant(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null, this::mustNotRun)));
        assertThat(markers(key)).containsExactly(key + "-inner");
    }

    @Test
    @DisplayName("IL-9 (HTTP): a nested commit keeps the key after the request fails; the retry is told to recover")
    void nestedCommitOverHttpKeepsTheKey() {
        String tag = "il9h-" + UUID.randomUUID();
        Map<String, String> headers = Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", tag);
        assertThat(call("POST", "/test-kernel/lease/payments", Map.of("tag", tag, "mode", "nested"), headers).status())
                .isEqualTo(500);
        Res retried = call("POST", "/test-kernel/lease/payments", Map.of("tag", tag, "mode", "nested"), headers);
        assertThat(retried.status()).isEqualTo(409);
        assertThat(retried.body().get("code").asString()).isEqualTo("OPERATION_PENDING");
        assertThat(markers(tag)).containsExactly(tag + "-inner");
    }

    @Test
    @DisplayName("TP-6: creating a location inside a failed keyed request does not hold the key (retry-safe, TA-6)")
    void retrySafeLocationDoesNotHoldTheKey() {
        String tag = "tp6-" + UUID.randomUUID();
        Map<String, String> headers = Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", tag);
        assertThat(call("POST", "/test-kernel/lease/payments", Map.of("tag", tag, "mode", "quarantine"), headers)
                .status()).isEqualTo(500);
        assertThat(jdbc.sql("SELECT count(*) FROM \"IdempotencyEvidence\" WHERE key = ?").param(tag)
                .query(Long.class).single()).isZero();
        Res retried = call("POST", "/test-kernel/lease/payments", Map.of("tag", tag, "mode", "quarantine"), headers);
        assertThat(retried.status()).as(retried.raw().body()).isEqualTo(200);
        assertThat(markers(tag)).containsExactly(tag + "-2");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code = ?")
                .params(demoOrg, "ILQ-" + tag).query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------------ IL-10

    @Test
    @DisplayName("IL-10: a connection killed inside the transaction: nothing commits; the key is taken over after the lease")
    void connectionKilledInsideTransaction() throws Exception {
        String key = "il10-" + UUID.randomUUID();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicInteger backend = new AtomicInteger();
        Future<Object> victim = pool.submit(() -> attempt(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null,
                () -> {
                    new TransactionTemplate(txm).executeWithoutResult(s -> {
                        TestIdempotencyLeaseController.marker(jdbc, key + "-victim");
                        backend.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                        inside.countDown();
                        await(resume);
                    });
                    return new Result(false, 201, Map.of("by", "victim"));
                })));
        assertThat(inside.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.sql("SELECT pg_terminate_backend(?)").param(backend.get()).query(Boolean.class).single()).isTrue();
        resume.countDown();
        assertThat(victim.get(30, TimeUnit.SECONDS)).isInstanceOf(RuntimeException.class);

        assertPending(() -> tenant(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null, this::mustNotRun)));
        expireLease(key);
        Result rerun = tenant(() -> idempotency.executeWithKey(key, "body", "POST", PATH, null, () -> {
            new TransactionTemplate(txm).executeWithoutResult(s -> TestIdempotencyLeaseController.marker(jdbc, key + "-rerun"));
            return new Result(false, 201, Map.of("by", "rerun"));
        }));
        assertThat(rerun.replayed()).isFalse();
        assertThat(markers(key)).containsExactly(key + "-rerun");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private <T> T tenant(Supplier<T> body) {
        return TenantContext.run(TenantStore.user(demoOrg, "lease-test"), body);
    }

    /** Run {@code body} as the tenant; an exception is returned instead of thrown. */
    private Object attempt(Supplier<Object> body) {
        try {
            return tenant(body);
        } catch (RuntimeException e) {
            return e;
        }
    }

    private Result mustNotRun() {
        throw new AssertionError("the handler must not run");
    }

    private void assertPending(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(mapper.valueToTree(e.body()).get("code").asString()).isEqualTo("OPERATION_PENDING");
        });
    }

    private void expireLease(String key) {
        assertThat(jdbc.sql("UPDATE \"IdempotencyRecord\" SET \"leaseExpiresAt\" = now() - interval '1 second'"
                + " WHERE key = ?").param(key).update()).isEqualTo(1);
    }

    private int attemptOf(String key) {
        return jdbc.sql("SELECT attempt FROM \"IdempotencyRecord\" WHERE key = ?").param(key).query(Integer.class).single();
    }

    private List<String> markers(String prefix) {
        return jdbc.sql("SELECT code FROM \"CostCenter\" WHERE \"organizationId\" = ? AND code LIKE ? ORDER BY code")
                .params(demoOrg, prefix + "-%").query(String.class).list();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
