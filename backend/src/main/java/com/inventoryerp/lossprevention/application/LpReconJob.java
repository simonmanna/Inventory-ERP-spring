package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The loss-prevention schedule: first the read-only sweep of the ERP's own records into the ledger, then the windows
 * that are due. ShedLock-guarded, gated by {@code app.jobs.enabled} and {@code app.modules.lossprevention}. Every
 * active organization runs in its own tenant scope and one failing organization never stops the others.
 */
@Component
@ConditionalOnExpression("${app.jobs.enabled:true} and ${app.modules.lossprevention:false}")
public class LpReconJob {

    private static final Logger log = LoggerFactory.getLogger(LpReconJob.class);

    private final JdbcClient jdbc;
    private final LpProjectionService projection;
    private final LpReconService recon;
    private final JobMetrics metrics;

    public LpReconJob(JdbcClient jdbc, LpProjectionService projection, LpReconService recon, JobMetrics metrics) {
        this.jdbc = jdbc;
        this.projection = projection;
        this.recon = recon;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${app.lossprevention.cron:0 */2 * * * *}")
    @SchedulerLock(name = "loss-prevention-sweep-and-run", lockAtMostFor = "PT20M")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT id FROM \"Organization\" WHERE \"deletedAt\" IS NULL AND status::text ="
                + " 'active'").query(String.class).list();
        for (String orgId : orgs) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
                    projection.sweepAll();
                    int ran = recon.runDue();
                    if (ran > 0) {
                        log.info("loss prevention org {}: {} window(s) reconciled", orgId, ran);
                    }
                });
            } catch (RuntimeException err) {
                log.error("loss prevention tick failed for org {}: {}", orgId, err.toString());
                metrics.jobFailed("loss-prevention-sweep-and-run");
            }
        }
    }
}
