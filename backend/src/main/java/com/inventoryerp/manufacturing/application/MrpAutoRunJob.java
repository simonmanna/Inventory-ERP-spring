package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every night at 02:30 UTC: MRP runs for the organizations that switched it on (PR-5). Nothing is firmed. */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class MrpAutoRunJob {

    private static final Logger log = LoggerFactory.getLogger(MrpAutoRunJob.class);

    private final MrpSettingsService settings;
    private final JobMetrics metrics;

    public MrpAutoRunJob(MrpSettingsService settings, JobMetrics metrics) {
        this.settings = settings;
        this.metrics = metrics;
    }

    @Scheduled(cron = "0 30 2 * * *", zone = "UTC")
    @SchedulerLock(name = "manufacturing-mrp-auto-run", lockAtMostFor = "PT1H")
    public void tick() {
        for (String orgId : settings.autoRunOrganizations()) {
            try {
                Map<String, Object> run = TenantContext.run(TenantContext.TenantStore.organization(orgId),
                        settings::autoRun);
                log.info("Nightly MRP for {}: {} {}", orgId, run.get("runNumber"), run.get("summary"));
            } catch (RuntimeException e) {
                log.warn("Nightly MRP failed for {}: {}", orgId, e.toString());
                metrics.jobFailed("manufacturing-mrp-auto-run");
            }
        }
    }
}
