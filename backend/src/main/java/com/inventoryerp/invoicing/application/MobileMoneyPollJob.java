package com.inventoryerp.invoicing.application;

import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls pending mobile money collections for providers whose callback did not arrive (INT-1, ShedLock). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class MobileMoneyPollJob {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyPollJob.class);

    private final JdbcClient jdbc;
    private final MobileMoneyService collections;
    private final JobMetrics metrics;

    public MobileMoneyPollJob(JdbcClient jdbc, MobileMoneyService collections, JobMetrics metrics) {
        this.jdbc = jdbc;
        this.collections = collections;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${app.mobile-money.poll-cron:${MOBILE_MONEY_POLL_CRON:0 */2 * * * *}}")
    @SchedulerLock(name = "mobile-money-poll", lockAtMostFor = "PT5M")
    public void run() {
        List<String> orgs = jdbc.sql("SELECT id FROM \"Organization\" WHERE \"deletedAt\" IS NULL")
                .query(String.class).list();
        for (String orgId : orgs) {
            try {
                TenantContext.run(TenantStore.organization(orgId), () -> {
                    collections.pollPending();
                });
            } catch (RuntimeException e) {
                log.error("mobile money poll failed for org {}: {}", orgId, e.toString());
                metrics.jobFailed("mobile-money-poll");
            }
        }
    }
}
