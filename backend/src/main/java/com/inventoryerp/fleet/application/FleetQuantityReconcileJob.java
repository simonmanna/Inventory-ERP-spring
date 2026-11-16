package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly 03:40: operational reconciliation of delivery lines (Phase A A3, plan §10), per organization. */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class FleetQuantityReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(FleetQuantityReconcileJob.class);

    private final JdbcClient jdbc;
    private final FleetQuantityReconciliation reconciliation;

    public FleetQuantityReconcileJob(JdbcClient jdbc, FleetQuantityReconciliation reconciliation) {
        this.jdbc = jdbc;
        this.reconciliation = reconciliation;
    }

    @Scheduled(cron = "0 40 3 * * *")
    @SchedulerLock(name = "fleet-quantity-reconcile", lockAtMostFor = "PT1H")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"FleetLoad\" WHERE status IN ('delivered',"
                + " 'closed', 'returned')").query(String.class).list();
        for (String orgId : orgs) {
            try {
                Map<String, Object> r = TenantContext.run(TenantContext.TenantStore.organization(orgId),
                        reconciliation::run);
                if (((Number) r.get("openFindings")).intValue() > 0) {
                    log.warn("Fleet quantity reconciliation for {}: {} open findings, {} repaired", orgId,
                            r.get("openFindings"), r.get("repaired"));
                }
            } catch (RuntimeException e) {
                log.warn("Fleet quantity reconciliation failed for {}: {}", orgId, e.toString());
            }
        }
    }
}
