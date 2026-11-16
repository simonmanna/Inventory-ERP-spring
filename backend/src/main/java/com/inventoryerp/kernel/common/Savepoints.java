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
}
