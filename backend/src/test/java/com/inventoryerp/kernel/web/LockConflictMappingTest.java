package com.inventoryerp.kernel.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;

/** Deadlocks, serialization failures and lock timeouts are recognised however deep Spring wrapped them. */
class LockConflictMappingTest {

    @Test
    void recognisesLockConflictStates() {
        for (String state : new String[] {"40P01", "40001", "55P03"}) {
            SQLException sql = new SQLException("lock", state);
            assertThat(GlobalExceptionHandler.isLockConflict(new UncategorizedSQLException("tx", "SELECT 1", sql)))
                    .as(state).isTrue();
            assertThat(GlobalExceptionHandler.isLockConflict(new RuntimeException(new IllegalStateException(sql))))
                    .as(state + " nested").isTrue();
        }
        assertThat(GlobalExceptionHandler.isLockConflict(new UncategorizedSQLException("tx", "SELECT 1",
                new SQLException("unique", "23505")))).isFalse();
        assertThat(GlobalExceptionHandler.isLockConflict(new DataAccessResourceFailureException("down"))).isFalse();
    }
}
