package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.LedgerIntegrityService.IntegrityCheck;
import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily (03:10 UTC): ledger integrity checks of every organisation with journal entries (finance audit A0). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class LedgerIntegrityJob {

    static final String JOB = "ledger-integrity-check";

    private static final Logger log = LoggerFactory.getLogger(LedgerIntegrityJob.class);

    private final JdbcClient jdbc;
    private final LedgerIntegrityMonitor monitor;
    private final JobMetrics metrics;

    public LedgerIntegrityJob(JdbcClient jdbc, LedgerIntegrityMonitor monitor, JobMetrics metrics) {
        this.jdbc = jdbc;
        this.monitor = monitor;
        this.metrics = metrics;
    }

    @Scheduled(cron = "0 10 3 * * *", zone = "UTC")
    @SchedulerLock(name = JOB, lockAtMostFor = "PT2H")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT o.id FROM \"Organization\" o WHERE EXISTS (SELECT 1 FROM \"JournalEntry\" e"
                + " WHERE e.\"organizationId\" = o.id)").query(String.class).list();
        for (String orgId : orgs) {
            try {
                LedgerIntegrityMonitor.Outcome out = TenantContext.run(TenantContext.TenantStore.organization(orgId),
                        monitor::check);
                if (!out.report().ok()) {
                    log.warn("Ledger integrity check failed for {}: {}", orgId, out.report().failing().stream()
                            .map(IntegrityCheck::key).collect(Collectors.joining(", ")));
                }
            } catch (RuntimeException e) {
                log.warn("Ledger integrity check could not run for {}: {}", orgId, e.toString());
                metrics.jobFailed(JOB);
            }
        }
    }
}
