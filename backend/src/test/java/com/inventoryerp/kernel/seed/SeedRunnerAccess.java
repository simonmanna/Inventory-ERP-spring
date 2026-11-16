package com.inventoryerp.kernel.seed;

import com.inventoryerp.kernel.auth.PasswordService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Test bridge: runs the demo seed without the {@code seed} profile. */
public final class SeedRunnerAccess {

    private SeedRunnerAccess() {}

    public static void seed(JdbcClient jdbc, PasswordService passwords, PlatformTransactionManager txm) {
        seed(jdbc, passwords, txm, java.util.List.of());
    }

    public static void seed(JdbcClient jdbc, PasswordService passwords, PlatformTransactionManager txm,
            java.util.List<SeedContributor> contributors) {
        SeedRunner runner = new SeedRunner(jdbc, passwords, txm, "", contributors, false, java.util.List.of(),
                java.util.List.of());
        new TransactionTemplate(txm).executeWithoutResult(s -> runner.seed());
    }

    /** The construction demo organization (idempotent); returns its id. */
    public static String seedConstruction(JdbcClient jdbc, PasswordService passwords, PlatformTransactionManager txm,
            java.util.List<SeedContributor> contributors, java.util.List<ConstructionDemoPart> parts) {
        return new SeedRunner(jdbc, passwords, txm, "", contributors, true, parts, java.util.List.of())
                .seedConstructionOrganization();
    }

    /** A standalone demo organization (idempotent per code); returns its id. */
    public static String seedDemoOrganization(JdbcClient jdbc, PasswordService passwords,
            PlatformTransactionManager txm, java.util.List<SeedContributor> contributors, DemoOrganization demo) {
        return new SeedRunner(jdbc, passwords, txm, "", contributors, false, java.util.List.of(),
                java.util.List.of(demo)).seedDemoOrganization(demo);
    }
}
