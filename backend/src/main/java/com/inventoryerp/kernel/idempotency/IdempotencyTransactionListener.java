package com.inventoryerp.kernel.idempotency;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Watches the write transactions of keyed requests (registered on the transaction managers directly, because the
 * application's tenant-aware manager is not customised by Boot). Only new transactions count (releasing a savepoint is
 * not a commit) and read-only ones write nothing.
 *
 * <ul>
 *   <li>Gaps h-06 F7: tells {@link IdempotencyInterceptor} whether the handler committed or rolled back a write
 *       transaction, so an operation that failed and committed nothing can release its Idempotency-Key.
 *   <li>ADR-S094 C1a: fences every write transaction of an attempt just before it commits. The outermost one stamps
 *       its attempt on the record under the row lock ({@link BusinessOutcome#fence}); one opened inside another
 *       transaction of the same attempt leaves an evidence row ({@link BusinessOutcome#nestedEvidence}). An attempt
 *       whose key a retry took over fails here, so its transaction rolls back.
 *   <li>Sales readiness R2 (P1-3): on a route of {@link IdempotencyService#EVIDENCE_ROUTES}, the outermost
 *       transaction also stamps {@code {"committed": true}} on the record, so a crash after the commit leaves
 *       evidence that the money moved.
 * </ul>
 */
@Component
class IdempotencyTransactionListener implements TransactionExecutionListener, SmartInitializingSingleton {

    /** Placeholder frame for a counted transaction opened outside any operation. */
    private static final BusinessOutcome.Operation NONE = new BusinessOutcome.Operation(null, null, null, null, null,
            null);

    /** The write transactions open on this thread, innermost first, each with the operation it was opened under. */
    private static final ThreadLocal<Deque<BusinessOutcome.Operation>> OPEN = ThreadLocal.withInitial(ArrayDeque::new);

    private final ObjectProvider<PlatformTransactionManager> managers;
    private final ObjectProvider<JdbcClient> jdbc;

    IdempotencyTransactionListener(ObjectProvider<PlatformTransactionManager> managers, ObjectProvider<JdbcClient> jdbc) {
        this.managers = managers;
        this.jdbc = jdbc;
    }

    @Override
    public void afterSingletonsInstantiated() {
        managers.orderedStream().forEach(m -> {
            if (m instanceof ConfigurableTransactionManager c && !c.getTransactionExecutionListeners().contains(this)) {
                c.addListener(this);
            }
        });
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        if (beginFailure == null && fenceable(transaction)) {
            BusinessOutcome.Operation op = BusinessOutcome.current();
            OPEN.get().push(op == null ? NONE : op);
        }
    }

    @Override
    public void beforeCommit(TransactionExecution transaction) {
        if (!fenceable(transaction)) {
            return;
        }
        BusinessOutcome.Operation op = BusinessOutcome.current();
        if (op == null) {
            return;
        }
        boolean nested = op.fenced() && outerTransactionOf(op);
        if (op.fenced()) {
            if (nested) {
                BusinessOutcome.nestedEvidence(jdbc.getObject(), op);
            } else {
                BusinessOutcome.fence(jdbc.getObject(), op);
            }
        }
        if (!nested && op.path() != null && IdempotencyService.needsCommitEvidence(op.path())) {
            BusinessOutcome.record(jdbc.getObject(), Map.of("committed", true), false);
        }
    }

    @Override
    public void afterCommit(TransactionExecution transaction, Throwable commitFailure) {
        if (fenceable(transaction)) {
            close();
        }
        // A failed commit may still have committed (a connection lost mid-commit): it counts, so the key is kept.
        if (transaction.isNewTransaction() && !transaction.isReadOnly()
                && !BusinessOutcome.RETRY_SAFE_TX.equals(transaction.getTransactionName())
                && !BusinessOutcome.LEDGER_TX.equals(transaction.getTransactionName())) {
            IdempotencyInterceptor.noteTransaction(true);
        }
    }

    @Override
    public void afterRollback(TransactionExecution transaction, Throwable rollbackFailure) {
        if (fenceable(transaction)) {
            close();
        }
        if (transaction.isNewTransaction() && !transaction.isReadOnly()
                && !BusinessOutcome.LEDGER_TX.equals(transaction.getTransactionName())) {
            IdempotencyInterceptor.noteTransaction(false);
        }
    }

    /** A new write transaction that is neither retry-safe nor the idempotency service's own bookkeeping. */
    private static boolean fenceable(TransactionExecution transaction) {
        return transaction.isNewTransaction() && !transaction.isReadOnly()
                && !BusinessOutcome.RETRY_SAFE_TX.equals(transaction.getTransactionName())
                && !BusinessOutcome.LEDGER_TX.equals(transaction.getTransactionName());
    }

    /** True when a transaction further out on this thread, still open, belongs to the same attempt as {@code op}. */
    private static boolean outerTransactionOf(BusinessOutcome.Operation op) {
        Iterator<BusinessOutcome.Operation> frames = OPEN.get().iterator();
        if (frames.hasNext()) {
            frames.next(); // the committing transaction itself
        }
        while (frames.hasNext()) {
            BusinessOutcome.Operation outer = frames.next();
            if (op.ownerToken().equals(outer.ownerToken())) {
                return true;
            }
        }
        return false;
    }

    private static void close() {
        Deque<BusinessOutcome.Operation> open = OPEN.get();
        open.poll();
        if (open.isEmpty()) {
            OPEN.remove();
        }
    }
}
