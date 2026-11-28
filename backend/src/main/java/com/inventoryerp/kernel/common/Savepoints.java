package com.inventoryerp.kernel.common;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Prisma-style nested write: inside an open transaction the body runs under a JDBC {@code SAVEPOINT}, so a failure
 * rolls back only the body and the caller may catch it and carry on. {@code PROPAGATION_NESTED} is not an option —
 * the Hibernate {@code JpaDialect} does not expose savepoints. Without a transaction the body just runs.
 */
public final class Savepoints {

    private Savepoints() {}

    public static void run(JdbcClient jdbc, Runnable body) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            body.run();
            return;
        }
        String name = "sp_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("SAVEPOINT " + name).update();
        int mark = DetachedWrites.mark();
        try {
            body.run();
        } catch (RuntimeException | Error e) {
            jdbc.sql("ROLLBACK TO SAVEPOINT " + name).update();
            // Events announcing what the savepoint just undid must not go out when the outer transaction commits.
            DetachedWrites.rewind(mark);
            throw e;
        }
        jdbc.sql("RELEASE SAVEPOINT " + name).update();
    }

    /** State of one {@link #runIsolated} block on this thread. */
    private static final class Isolation {
        /** Transactions begun (REQUIRES_NEW) inside the block and not yet completed: their participants are theirs. */
        int newTransactions;
        /** Participant failures absorbed (not marked rollback-only) inside the block. */
        int absorbed;
    }

    private static final ThreadLocal<Isolation> ISOLATION = new ThreadLocal<>();

    /**
     * As {@link #run}, but a failure of a proxied {@code @Transactional} bean called inside {@code body} (a participant of
     * the caller's transaction) does not mark that transaction rollback-only: the savepoint rolls back what the body did
     * and the caller's transaction stays committable (IPR review 3 #6: a late count correction that cannot post must not
     * fail the late movement). A participant failure the body swallowed still fails the block (the savepoint rolls back),
     * so nothing half-done is kept. Requires the {@code TenantAwareTransactionManager} hooks
     * ({@link #absorbParticipantRollback}). Without a transaction the body just runs.
     */
    public static void runIsolated(JdbcClient jdbc, Runnable body) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            body.run();
            return;
        }
        Isolation previous = ISOLATION.get();
        Isolation mine = new Isolation();
        ISOLATION.set(mine);
        try {
            run(jdbc, () -> {
                body.run();
                if (mine.absorbed > 0) {
                    throw new IllegalStateException("A transactional call failed inside the isolated block and its"
                            + " failure was swallowed; the block is rolled back");
                }
            });
        } finally {
            if (previous == null) {
                ISOLATION.remove();
            } else {
                ISOLATION.set(previous);
            }
        }
    }

    /**
     * Transaction-manager hook: a participant of the current transaction failed. True = inside a {@link #runIsolated}
     * block of this transaction (not of a transaction begun inside the block), so the failure is absorbed: the block's
     * savepoint rolls it back instead of the whole transaction being marked rollback-only.
     */
    public static boolean absorbParticipantRollback() {
        Isolation i = ISOLATION.get();
        if (i == null || i.newTransactions > 0) {
            return false;
        }
        i.absorbed++;
        return true;
    }

    /** Transaction-manager hook: a new physical transaction began on this thread. */
    public static void transactionBegun() {
        Isolation i = ISOLATION.get();
        if (i != null) {
            i.newTransactions++;
        }
    }

    /** Transaction-manager hook: a new physical transaction of this thread completed. */
    public static void transactionCompleted() {
        Isolation i = ISOLATION.get();
        if (i != null && i.newTransactions > 0) {
            i.newTransactions--;
        }
    }
}
