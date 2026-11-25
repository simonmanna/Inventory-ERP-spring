package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily (03:25 UTC): next year's fiscal periods for every organisation that keeps fiscal periods (finance readiness
 * audit B1, {@link FiscalPeriodCoverageService}).
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class FiscalPeriodCoverageJob {

    static final String JOB = "fiscal-period-coverage";

    private static final Logger log = LoggerFactory.getLogger(FiscalPeriodCoverageJob.class);

    private final JdbcClient jdbc;
    private final FiscalPeriodCoverageService coverage;
    private final JobMetrics metrics;

    public FiscalPeriodCoverageJob(JdbcClient jdbc, FiscalPeriodCoverageService coverage, JobMetrics metrics) {
        this.jdbc = jdbc;
        this.coverage = coverage;
        this.metrics = metrics;
    }

    @Scheduled(cron = "0 25 3 * * *", zone = "UTC")
    @SchedulerLock(name = JOB, lockAtMostFor = "PT30M")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT o.id FROM \"Organization\" o WHERE o.\"deletedAt\" IS NULL AND EXISTS"
                + " (SELECT 1 FROM \"FiscalPeriod\" p WHERE p.\"organizationId\" = o.id AND p.\"deletedAt\" IS NULL)")
                .query(String.class).list();
        for (String orgId : orgs) {
            try {
                FiscalPeriodCoverageService.Result r = TenantContext.run(
                        TenantContext.TenantStore.organization(orgId), coverage::run);
                if (!"covered".equals(r.outcome())) {
                    log.info("Fiscal period coverage for {}: {} {}", orgId, r.outcome(), r.detail());
                }
            } catch (RuntimeException e) {
                log.warn("Fiscal period coverage could not run for {}: {}", orgId, e.toString());
                metrics.jobFailed(JOB);
            }
        }
    }
}
