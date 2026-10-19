package com.inventoryerp.kernel.seed;

import com.inventoryerp.kernel.auth.PasswordService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Test bridge: runs the demo seed without the {@code seed} profile. */
public final class SeedRunnerAccess {

    private SeedRunnerAccess() {}

    public static void seed(JdbcClient jdbc, PasswordService passwords, PlatformTransactionManager txm) {
        SeedRunner runner = new SeedRunner(jdbc, passwords, txm, "");
        new TransactionTemplate(txm).executeWithoutResult(s -> runner.seed());
    }
}
