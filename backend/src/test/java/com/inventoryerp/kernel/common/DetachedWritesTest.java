package com.inventoryerp.kernel.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Commit-only detached writes: dropped on rollback and on a rolled-back savepoint; plain writes always run. */
class DetachedWritesTest {

    private final DetachedWrites writes = new DetachedWrites(mock(PlatformTransactionManager.class));
    private final List<String> ran = new CopyOnWriteArrayList<>();

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private void complete(int status) {
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        syncs.forEach(s -> s.afterCompletion(status));
        assertThat(writes.awaitIdle(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    @DisplayName("a rolled-back transaction runs plain writes only")
    void rollback() {
        TransactionSynchronizationManager.initSynchronization();
        writes.submit(() -> ran.add("always"));
        writes.submitOnCommit(() -> ran.add("commit-only"));
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(ran).containsExactly("always");
    }

    @Test
    @DisplayName("a committed transaction runs both, in submission order")
    void commit() {
        TransactionSynchronizationManager.initSynchronization();
        writes.submitOnCommit(() -> ran.add("first"));
        writes.submit(() -> ran.add("second"));
        complete(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(ran).containsExactly("first", "second");
    }

    @Test
    @DisplayName("a rolled-back savepoint drops the commit-only writes made inside it")
    void savepoint() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        writes.submitOnCommit(() -> ran.add("before"));
        assertThatThrownBy(() -> Savepoints.run(jdbc, () -> {
            writes.submitOnCommit(() -> ran.add("undone"));
            writes.submit(() -> ran.add("audit"));
            throw new IllegalStateException("line failed");
        })).hasMessage("line failed");
        Savepoints.run(jdbc, () -> writes.submitOnCommit(() -> ran.add("kept")));
        complete(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(ran).containsExactly("before", "audit", "kept");
    }

    @Test
    @DisplayName("outside a transaction a commit-only write runs at once")
    void noTransaction() {
        writes.submitOnCommit(() -> ran.add("now"));
        assertThat(ran).containsExactly("now");
    }
}
