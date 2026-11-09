package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every hour (ADR-S063): apply the approved ECOs that were queued (use-up of stock) or are date-effective, once their
 * effective date has come on the organization's own calendar and no stock is left to use up. Each ECO is applied in
 * its own transaction, so one failure (e.g. a BOM activation awaiting approval) does not hold back the others.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PlmEffectivityJob {

    private static final Logger log = LoggerFactory.getLogger(PlmEffectivityJob.class);

    private final PlmChangeService changes;
    private final JobMetrics metrics;

    public PlmEffectivityJob(PlmChangeService changes, JobMetrics metrics) {
        this.changes = changes;
        this.metrics = metrics;
    }

    @Scheduled(cron = "0 5 * * * *", zone = "UTC")
    @SchedulerLock(name = "manufacturing-plm-effectivity", lockAtMostFor = "PT30M")
    public void tick() {
        for (String orgId : changes.dueOrganizations()) {
            TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
                List<String> applied = new ArrayList<>();
                for (String id : changes.dueChanges()) {
                    try {
                        String number = changes.autoApply(id);
                        if (number != null) {
                            applied.add(number);
                        }
                    } catch (RuntimeException e) {
                        log.warn("PLM switch-over of {} failed for {}: {}", id, orgId, e.toString());
                        metrics.jobFailed("manufacturing-plm-effectivity");
                    }
                }
                if (!applied.isEmpty()) {
                    log.info("PLM switch-over for {}: applied {}", orgId, applied);
                }
                return applied;
            });
        }
    }
}
