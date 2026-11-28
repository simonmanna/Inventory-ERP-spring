package com.inventoryerp.kernel.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * IPR review 3 (#6) follow-up: after-commit callbacks registered inside a {@link Savepoints#runIsolated} block that the
 * savepoint rolled back must not fire when the outer transaction commits; callbacks registered before the block stay.
 */
class SavepointsIsolationTest {

    private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);

    @BeforeEach
    void openTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void closeTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void failedBlockDropsItsAfterCommitCallbacks() {
        TransactionSynchronization outer = new TransactionSynchronization() {};
        TransactionSynchronizationManager.registerSynchronization(outer);

        assertThatThrownBy(() -> Savepoints.runIsolated(jdbc, () -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {});
            throw new IllegalStateException("posting refused");
        })).hasMessage("posting refused");

        assertThat(TransactionSynchronizationManager.getSynchronizations()).containsExactly(outer);
    }

    @Test
    void successfulBlockKeepsItsAfterCommitCallbacks() {
        TransactionSynchronization outer = new TransactionSynchronization() {};
        TransactionSynchronization inner = new TransactionSynchronization() {};
        TransactionSynchronizationManager.registerSynchronization(outer);

        Savepoints.runIsolated(jdbc, () -> TransactionSynchronizationManager.registerSynchronization(inner));

        assertThat(TransactionSynchronizationManager.getSynchronizations()).containsExactlyInAnyOrder(outer, inner);
    }
}
