package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily GL automation (ADR-S073): generates due recurring journal entries and releases due deferral slices for every
 * active organization, each in its own tenant scope; one failing organization never stops the others. Both bodies
 * are idempotent (posting keys + unique run / release rows), so a second run the same day books nothing.
 * ShedLock-guarded, gated by {@code app.jobs.enabled}.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class GlAutomationJob {

    private static final Logger log = LoggerFactory.getLogger(GlAutomationJob.class);

    private final JdbcClient jdbc;
    private final RecurringJournalService recurring;
    private final DeferralService deferrals;
    private final JobMetrics metrics;

    public GlAutomationJob(JdbcClient jdbc, RecurringJournalService recurring, DeferralService deferrals,
            JobMetrics metrics) {
        this.jdbc = jdbc;
        this.recurring = recurring;
        this.deferrals = deferrals;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${app.accounting.recurring-cron:0 30 1 * * *}")
    @SchedulerLock(name = "accounting-recurring-entries", lockAtMostFor = "PT1H")
    public void recurringEntries() {
        each("accounting-recurring-entries", recurring::runDue);
    }

    @Scheduled(cron = "${app.accounting.deferral-cron:0 40 1 * * *}")
    @SchedulerLock(name = "accounting-deferral-releases", lockAtMostFor = "PT1H")
    public void deferralReleases() {
        each("accounting-deferral-releases", deferrals::releaseDue);
    }

    private void each(String job, Function<LocalDate, Map<String, Object>> body) {
        List<String> orgs = jdbc.sql("SELECT id FROM \"Organization\" WHERE \"deletedAt\" IS NULL AND status::text ="
                + " 'active'").query(String.class).list();
        for (String orgId : orgs) {
            try {
                Map<String, Object> result = TenantContext.run(TenantContext.TenantStore.organization(orgId),
                        () -> body.apply(LocalDate.now(AccountingDay.zone(jdbc))));
                log.debug("{} org {}: {}", job, orgId, result);
            } catch (RuntimeException err) {
                log.error("{} failed for org {}: {}", job, orgId, err.toString());
                metrics.jobFailed(job);
            }
        }
    }
}
