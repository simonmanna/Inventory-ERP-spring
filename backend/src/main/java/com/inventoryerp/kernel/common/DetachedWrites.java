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

    public DetachedWrites(PlatformTransactionManager txm) {
        this.flushTx = new TransactionTemplate(txm);
    }

    /** Run {@code write} now (no transaction) or after the current transaction completes. */
    public void submit(Runnable write) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            List<Write> collecting = batch.get();
            if (collecting != null) {
                collecting.add(new Write(TenantContext.current().orElse(null), write));
                return;
            }
            write.run();
            return;
        }
        buffer().add(write);
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
    private List<Runnable> buffer() {
        List<Runnable> pending = (List<Runnable>) TransactionSynchronizationManager.getResource(this);
        if (pending != null) {
            return pending;
        }
        List<Runnable> created = new ArrayList<>();
        TransactionSynchronizationManager.bindResource(this, created);
        TenantStore tenant = TenantContext.current().orElse(null);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(DetachedWrites.this);
                if (created.isEmpty()) {
                    return;
                }
                List<Write> collecting = batch.get();
                if (collecting != null) {
                    created.forEach(w -> collecting.add(new Write(tenant, w)));
                } else {
                    runDetached(tenant, List.copyOf(created));
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
