package com.inventoryerp.kernel.auth;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Race-free lockout for short secrets (PINs). Each attempt is counted with one atomic upsert on
 * {@code CredentialThrottle} <em>before</em> the hash is compared, in its own short transaction:
 *
 * <ul>
 *   <li>parallel guesses cannot overwrite each other's increments (the upsert serialises on the row), so a policy of
 *       {@code n} attempts allows exactly {@code n} hash compares per window however the requests are timed;
 *   <li>a failure survives the rollback of the caller's business transaction (void, cancel, close).
 * </ul>
 *
 * <p>A locked subject is rejected by a plain read on the caller's connection, so an attacker who keeps hammering a
 * locked PIN never needs a second pooled connection. A correct credential resets the counter.
 */
@Component
public class CredentialGuard {

    /** What is guessed, how many attempts a window allows, and how long the lock lasts. */
    public record Policy(String scope, int maxAttempts, Duration window, Duration lockout) {}

    /** A user's own POS PIN (PIN login, change-PIN, own-PIN verify). Same limits as Nest's PIN login. */
    public static final Policy POS_PIN = new Policy("pos_pin", 10, Duration.ofMinutes(15), Duration.ofMinutes(15));

    /** An approver's PIN or password (manager override, operation approval, cash-session approval). */
    public static final Policy MANAGER_PIN = new Policy("manager_pin", 5, Duration.ofMinutes(10), Duration.ofMinutes(10));

    /** Outcome of {@link #attempt}: {@code locked} with the remaining lock when the compare must not run. */
    public record Attempt(boolean locked, Duration remaining) {
        static Attempt allowed() {
            return new Attempt(false, Duration.ZERO);
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate independent;

    public CredentialGuard(JdbcClient jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.independent = new TransactionTemplate(txm);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Remaining lock, if the subject is locked (read only, on the caller's connection). */
    public Optional<Duration> lockedFor(String organizationId, Policy policy, String subject) {
        List<Long> ms = jdbc.sql("SELECT ceil(EXTRACT(EPOCH FROM (\"lockedUntil\" - CURRENT_TIMESTAMP::timestamp(3))) * 1000)::bigint"
                        + " FROM \"CredentialThrottle\" WHERE \"organizationId\" = ? AND scope = ? AND subject = ?"
                        + " AND \"lockedUntil\" > CURRENT_TIMESTAMP::timestamp(3)")
                .params(organizationId, policy.scope(), subject).query(Long.class).list();
        return ms.isEmpty() ? Optional.empty() : Optional.of(Duration.ofMillis(Math.max(1, ms.getFirst())));
    }

    /**
     * Count one attempt. When the result is not {@code locked} the caller may compare the secret and must call
     * {@link #succeeded} on a match; a mismatch needs no further call (it is already counted).
     */
    public Attempt attempt(String organizationId, Policy policy, String subject) {
        Optional<Duration> locked = lockedFor(organizationId, policy, subject);
        if (locked.isPresent()) {
            return new Attempt(true, locked.get());
        }
        Long failures = independent.execute(st -> jdbc.sql("""
                        INSERT INTO "CredentialThrottle" AS t
                            ("organizationId", scope, subject, failures, "windowStart", "lockedUntil", "updatedAt")
                        VALUES (:org, :scope, :subject, 1, CURRENT_TIMESTAMP,
                                CASE WHEN 1 >= :max THEN CURRENT_TIMESTAMP + :lockMs * INTERVAL '1 millisecond' END,
                                CURRENT_TIMESTAMP)
                        ON CONFLICT ("organizationId", scope, subject) DO UPDATE SET
                            failures = CASE WHEN t."lockedUntil" > CURRENT_TIMESTAMP THEN t.failures + 1
                                            WHEN t."lockedUntil" IS NOT NULL
                                                 OR t."windowStart" < CURRENT_TIMESTAMP - :windowMs * INTERVAL '1 millisecond'
                                            THEN 1 ELSE t.failures + 1 END,
                            "windowStart" = CASE WHEN t."lockedUntil" > CURRENT_TIMESTAMP THEN t."windowStart"
                                            WHEN t."lockedUntil" IS NOT NULL
                                                 OR t."windowStart" < CURRENT_TIMESTAMP - :windowMs * INTERVAL '1 millisecond'
                                            THEN CURRENT_TIMESTAMP ELSE t."windowStart" END,
                            "lockedUntil" = CASE WHEN t."lockedUntil" > CURRENT_TIMESTAMP THEN t."lockedUntil"
                                            WHEN (CASE WHEN t."lockedUntil" IS NOT NULL
                                                       OR t."windowStart" < CURRENT_TIMESTAMP - :windowMs * INTERVAL '1 millisecond'
                                                  THEN 1 ELSE t.failures + 1 END) >= :max
                                            THEN CURRENT_TIMESTAMP + :lockMs * INTERVAL '1 millisecond' END,
                            "updatedAt" = CURRENT_TIMESTAMP
                        RETURNING failures""")
                .param("org", organizationId).param("scope", policy.scope()).param("subject", subject)
                .param("max", policy.maxAttempts()).param("lockMs", policy.lockout().toMillis())
                .param("windowMs", policy.window().toMillis())
                .query(Long.class).single());
        if (failures != null && failures > policy.maxAttempts()) {
            return new Attempt(true, lockedFor(organizationId, policy, subject).orElse(policy.lockout()));
        }
        return Attempt.allowed();
    }

    /** A correct credential: reset the subject's counter for this scope. */
    public void succeeded(String organizationId, Policy policy, String subject) {
        boolean counted = !jdbc.sql("SELECT 1 FROM \"CredentialThrottle\" WHERE \"organizationId\" = ? AND scope = ?"
                        + " AND subject = ? AND (failures <> 0 OR \"lockedUntil\" IS NOT NULL)")
                .params(organizationId, policy.scope(), subject).query(Integer.class).list().isEmpty();
        if (!counted) {
            return;
        }
        independent.executeWithoutResult(st -> jdbc.sql("UPDATE \"CredentialThrottle\" SET failures = 0,"
                        + " \"lockedUntil\" = NULL, \"windowStart\" = CURRENT_TIMESTAMP, \"updatedAt\" = CURRENT_TIMESTAMP"
                        + " WHERE \"organizationId\" = ? AND scope = ? AND subject = ? AND (failures <> 0 OR \"lockedUntil\" IS NOT NULL)")
                .params(organizationId, policy.scope(), subject).update());
    }

    /** Administrative unlock: clear every PIN counter of the subject. */
    public void clear(String organizationId, String subject) {
        jdbc.sql("DELETE FROM \"CredentialThrottle\" WHERE \"organizationId\" = ? AND subject = ?")
                .params(organizationId, subject).update();
    }

    /** Whole minutes left, rounded up (the wording of the Nest lockout messages). */
    public static long minutes(Duration remaining) {
        return Math.max(1, (long) Math.ceil(remaining.toMillis() / 60_000.0));
    }
}
