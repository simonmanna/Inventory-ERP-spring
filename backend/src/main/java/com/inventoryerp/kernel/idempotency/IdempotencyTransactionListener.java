package com.inventoryerp.kernel.idempotency;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Gaps h-06 F7: tells {@link IdempotencyInterceptor} whether the handler of a keyed request committed or rolled back a
 * write transaction, so an operation that failed and committed nothing can release its Idempotency-Key. Registered on
 * the transaction managers directly, because the application's tenant-aware manager is not customised by Boot. Only
 * new transactions count (releasing a savepoint is not a commit) and read-only ones write nothing.
 */
@Component
class IdempotencyTransactionListener implements TransactionExecutionListener, SmartInitializingSingleton {

    private final ObjectProvider<PlatformTransactionManager> managers;

    IdempotencyTransactionListener(ObjectProvider<PlatformTransactionManager> managers) {
        this.managers = managers;
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
    public void afterCommit(TransactionExecution transaction, Throwable commitFailure) {
        // A failed commit may still have committed (a connection lost mid-commit): it counts, so the key is kept.
        if (transaction.isNewTransaction() && !transaction.isReadOnly()
                && !BusinessOutcome.RETRY_SAFE_TX.equals(transaction.getTransactionName())) {
            IdempotencyInterceptor.noteTransaction(true);
        }
    }

    @Override
    public void afterRollback(TransactionExecution transaction, Throwable rollbackFailure) {
        if (transaction.isNewTransaction() && !transaction.isReadOnly()) {
            IdempotencyInterceptor.noteTransaction(false);
        }
    }
}
