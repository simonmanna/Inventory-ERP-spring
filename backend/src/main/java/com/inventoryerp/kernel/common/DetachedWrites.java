package com.inventoryerp.kernel.common;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fire-and-forget writes that run in their own transaction (Nest: an un-awaited {@code void x.record(...)} on
 * the root Prisma client). Outside a transaction the write runs at once on the caller's thread. Inside one, the
 * writes are buffered and run in order on a virtual thread after the caller's transaction completes (commit or
 * rollback), with the caller's tenant context. Running them synchronously would hold the caller's pooled
 * connection while waiting for a second one, which deadlocks the pool once every connection is held that way.
 *
 * <p>Writes must open their transaction with {@code PROPAGATION_REQUIRED}: they never run inside a caller's
 * transaction, but inside {@link #batch} they join the single flush transaction.
 *
 * <p>{@link #submitOnCommit} is the variant for writes that announce a fact of the caller's transaction (a stock
 * movement event): it runs only when that transaction commits, and {@link Savepoints} discards it when the savepoint
 * it was submitted under rolls back. {@link #submit} keeps the Nest semantics (runs on commit and on rollback), which
 * audit / failure notifications rely on.
 */
@Component
public class DetachedWrites {

    private static final Logger log = LoggerFactory.getLogger(DetachedWrites.class);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object idle = new Object();
    private final ThreadLocal<List<Write>> batch = new ThreadLocal<>();
    private final TransactionTemplate flushTx;

    /** A deferred write and the tenant it was submitted under. */
    private record Write(TenantStore tenant, Runnable body) {}

    /** A write buffered in a transaction; {@code commitOnly} = dropped unless that transaction commits. */
    private record Pending(Runnable body, boolean commitOnly) {}

    /** Transaction resource key of the buffer (static so {@link Savepoints} can rewind it). */
    private static final Object BUFFER_KEY = new Object();

    public DetachedWrites(PlatformTransactionManager txm) {
        this.flushTx = new TransactionTemplate(txm);
    }

    /** Run {@code write} now (no transaction) or after the current transaction completes (commit or rollback). */
    public void submit(Runnable write) {
        submit(write, false);
    }

    /**
     * Run {@code write} now (no transaction) or after the current transaction commits; it is dropped when that
     * transaction rolls back, or when the {@link Savepoints} block it was submitted in rolls back.
     */
    public void submitOnCommit(Runnable write) {
        submit(write, true);
    }

    private void submit(Runnable write, boolean commitOnly) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            List<Write> collecting = batch.get();
            if (collecting != null) {
                collecting.add(new Write(TenantContext.current().orElse(null), write));
                return;
            }
            write.run();
            return;
        }
        buffer().add(new Pending(write, commitOnly));
    }

    /** Size of the current transaction's buffer (a {@link Savepoints} mark); 0 outside a transaction. */
    static int mark() {
        List<?> pending = TransactionSynchronizationManager.isSynchronizationActive()
                ? (List<?>) TransactionSynchronizationManager.getResource(BUFFER_KEY) : null;
        return pending == null ? 0 : pending.size();
    }

    /** A savepoint rolled back: drop the commit-only writes buffered since {@code mark}. */
    static void rewind(int mark) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Pending> pending = (List<Pending>) TransactionSynchronizationManager.getResource(BUFFER_KEY);
        if (pending == null || pending.size() <= mark) {
            return;
        }
        List<Pending> tail = pending.subList(mark, pending.size());
        List<Pending> kept = tail.stream().filter(p -> !p.commitOnly()).toList();
        tail.clear();
        pending.addAll(kept);
    }

    /**
     * Run {@code body} and collect every fire-and-forget write it submits (directly or after one of its
     * transactions completes); when it returns or throws, run them all in submission order in one transaction on a
     * virtual thread, like any detached write. One commit instead of one per write; if that transaction fails, each
     * write is retried on its own, so a failing write still cannot take the others down. Nested calls join.
     */
    public <T> T batch(Supplier<T> body) {
        if (batch.get() != null) {
            return body.get();
        }
        List<Write> writes = new ArrayList<>();
        batch.set(writes);
        try {
            return body.get();
        } finally {
            batch.remove();
            if (!writes.isEmpty()) {
                runBatch(List.copyOf(writes));
            }
        }
    }

    /**
     * Wait until every deferred write has run (tests: makes "call, then read the row" deterministic).
     * Returns false on timeout.
     */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (idle) {
            while (inFlight.get() > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    idle.wait(Math.max(1, left / 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private List<Pending> buffer() {
        List<Pending> pending = (List<Pending>) TransactionSynchronizationManager.getResource(BUFFER_KEY);
        if (pending != null) {
            return pending;
        }
        List<Pending> created = new ArrayList<>();
        TransactionSynchronizationManager.bindResource(BUFFER_KEY, created);
        TenantStore tenant = TenantContext.current().orElse(null);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(BUFFER_KEY);
                boolean committed = status == STATUS_COMMITTED;
                List<Runnable> due = created.stream().filter(p -> committed || !p.commitOnly()).map(Pending::body)
                        .toList();
                if (due.isEmpty()) {
                    return;
                }
                List<Write> collecting = batch.get();
                if (collecting != null) {
                    due.forEach(w -> collecting.add(new Write(tenant, w)));
                } else {
                    runDetached(tenant, due);
                }
            }
        });
        return created;
    }

    private void runDetached(TenantStore tenant, List<Runnable> writes) {
        inFlight.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    TenantContext.run(tenant, () -> {
                        for (Runnable w : writes) {
                            try {
                                w.run();
                            } catch (RuntimeException err) {
                                log.error("Detached write failed: {}", err.toString());
                            }
                        }
                    });
                } finally {
                    done();
                }
            });
        } catch (RuntimeException rejected) {
            done();
            log.error("Failed to schedule {} detached write(s): {}", writes.size(), rejected.toString());
        }
    }

    private void runBatch(List<Write> writes) {
        TenantStore tenant = writes.getFirst().tenant();
        boolean oneTenant = writes.stream().allMatch(w -> sameOrganization(w.tenant(), tenant));
        inFlight.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    if (oneTenant && flushTogether(tenant, writes)) {
                        return;
                    }
                    for (Write w : writes) {
                        TenantContext.run(w.tenant(), () -> {
                            try {
                                w.body().run();
                            } catch (RuntimeException err) {
                                log.error("Detached write failed: {}", err.toString());
                            }
                        });
                    }
                } finally {
                    done();
                }
            });
        } catch (RuntimeException rejected) {
            done();
            log.error("Failed to schedule {} detached write(s): {}", writes.size(), rejected.toString());
        }
    }

    /** All writes in one transaction; false (nothing committed) when any of them failed. */
    private boolean flushTogether(TenantStore tenant, List<Write> writes) {
        try {
            TenantContext.run(tenant, () -> flushTx.executeWithoutResult(st -> {
                for (Write w : writes) {
                    TenantContext.run(w.tenant(), w.body());
                    if (st.isRollbackOnly()) {
                        throw new IllegalStateException("a batched detached write failed");
                    }
                }
            }));
            return true;
        } catch (RuntimeException err) {
            log.debug("Batched detached writes failed, retrying one by one: {}", err.toString());
            return false;
        }
    }

    private static boolean sameOrganization(TenantStore a, TenantStore b) {
        String oa = a == null ? null : a.organizationId();
        String ob = b == null ? null : b.organizationId();
        return oa == null ? ob == null : oa.equals(ob);
    }

    private void done() {
        if (inFlight.decrementAndGet() == 0) {
            synchronized (idle) {
                idle.notifyAll();
            }
        }
    }
}
