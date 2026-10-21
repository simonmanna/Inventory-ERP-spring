package com.inventoryerp.kernel.common;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fire-and-forget writes that run in their own transaction (Nest: an un-awaited {@code void x.record(...)} on
 * the root Prisma client). Outside a transaction the write runs at once on the caller's thread. Inside one, the
 * writes are buffered and run in order on a virtual thread after the caller's transaction completes (commit or
 * rollback), with the caller's tenant context. Running them synchronously would hold the caller's pooled
 * connection while waiting for a second one, which deadlocks the pool once every connection is held that way.
 */
@Component
public class DetachedWrites {

    private static final Logger log = LoggerFactory.getLogger(DetachedWrites.class);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object idle = new Object();

    /** Run {@code write} now (no transaction) or after the current transaction completes. */
    public void submit(Runnable write) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            write.run();
            return;
        }
        buffer().add(write);
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
                if (!created.isEmpty()) {
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

    private void done() {
        if (inFlight.decrementAndGet() == 0) {
            synchronized (idle) {
                idle.notifyAll();
            }
        }
    }
}
