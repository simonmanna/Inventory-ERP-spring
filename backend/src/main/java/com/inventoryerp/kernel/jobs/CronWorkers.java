package com.inventoryerp.kernel.jobs;

import com.inventoryerp.kernel.recurring.RecurringService;
import com.inventoryerp.kernel.webhooks.WebhooksService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron-driven background workers (port of {@code cron-workers.service.ts}). ShedLock guarantees
 * at-most-one execution across instances. (The outbox has its own 1 s claim loop: {@code OutboxWorker}.)
 * Disable with {@code app.jobs.enabled=false} (tests).
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CronWorkers {

    private static final Logger log = LoggerFactory.getLogger(CronWorkers.class);

    private final WebhooksService webhooks;
    private final RecurringService recurring;
    private final JdbcClient jdbc;

    public CronWorkers(WebhooksService webhooks, RecurringService recurring, JdbcClient jdbc) {
        this.webhooks = webhooks;
        this.recurring = recurring;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "webhooks-retry", lockAtMostFor = "PT5M")
    public void retryWebhooks() {
        try {
            Map<String, Integer> r = webhooks.tick();
            if (r.get("attempted") > 0) {
                log.info("Webhooks: {}/{} succeeded, {} failed", r.get("succeeded"), r.get("attempted"), r.get("failed"));
            }
        } catch (RuntimeException err) {
            log.warn("Webhook tick failed: {}", err.toString());
        }
    }

    @Scheduled(cron = "0 */10 * * * *")
    @SchedulerLock(name = "recurring-tick", lockAtMostFor = "PT9M")
    public void generateRecurring() {
        try {
            Map<String, Integer> r = recurring.tick(Instant.now());
            if (r.get("processed") + r.get("errors") > 0) {
                log.info("Recurring: {} generated, {} errors", r.get("processed"), r.get("errors"));
            }
        } catch (RuntimeException err) {
            log.warn("Recurring tick failed: {}", err.toString());
        }
    }

    /** Prune read/failed in-app notifications older than 90 days. */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "cleanup-notifications", lockAtMostFor = "PT30M")
    public void cleanupNotifications() {
        int n = jdbc.sql("""
                        DELETE FROM "Notification" WHERE channel = 'in_app' AND "createdAt" < ? AND status IN ('read', 'failed')""")
                .param(Timestamp.from(Instant.now().minus(Duration.ofDays(90))))
                .update();
        if (n > 0) {
            log.info("Pruned {} old notifications", n);
        }
    }

    /** Expired one-time tokens, idempotency records > 90 d, refresh tokens revoked > 30 d. */
    @Scheduled(cron = "0 0 0 * * *")
    @SchedulerLock(name = "cleanup-tokens", lockAtMostFor = "PT1H")
    public void cleanupTokens() {
        Instant now = Instant.now();
        int tokens = jdbc.sql("DELETE FROM \"OneTimeToken\" WHERE \"expiresAt\" < ?").param(Timestamp.from(now)).update();
        int idem = jdbc.sql("DELETE FROM \"IdempotencyRecord\" WHERE \"createdAt\" < ?")
                .param(Timestamp.from(now.minus(Duration.ofDays(90)))).update();
        int refresh = jdbc.sql("DELETE FROM \"RefreshToken\" WHERE \"revokedAt\" < ?")
                .param(Timestamp.from(now.minus(Duration.ofDays(30)))).update();
        log.info("Daily cleanup: {} expired tokens, {} idempotency rows (>90d), {} refresh tokens", tokens, idem, refresh);
    }

    /** ShedLock wiring (table {@code shedlock}, created by V3). */
    @Configuration(proxyBeanMethods = false)
    @EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
    static class ShedLockConfig {
        @Bean
        LockProvider lockProvider(DataSource dataSource) {
            return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                    .withJdbcTemplate(new JdbcTemplate(dataSource))
                    .usingDbTime()
                    .build());
        }
    }
}
