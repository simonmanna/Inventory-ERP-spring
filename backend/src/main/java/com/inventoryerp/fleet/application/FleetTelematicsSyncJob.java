package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Telematics pull (fleet 1.8): every 5 minutes by default ({@code app.fleet.telematics.sync-cron}, env
 * {@code FLEET_TELEMATICS_SYNC_CRON}), one instance cluster-wide (ShedLock), each organization with an active device
 * in its own tenant scope; one failing organization never stops the rest. Off with {@code app.jobs.enabled=false}
 * or without the fleet module.
 */
@Component
@ConditionalOnExpression("${app.jobs.enabled:true} and ${app.modules.fleet:false}")
public class FleetTelematicsSyncJob {

    private static final Logger log = LoggerFactory.getLogger(FleetTelematicsSyncJob.class);

    private final FleetDb db;
    private final FleetTelematicsService telematics;
    private final JobMetrics metrics;

    public FleetTelematicsSyncJob(FleetDb db, FleetTelematicsService telematics, JobMetrics metrics) {
        this.db = db;
        this.telematics = telematics;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${app.fleet.telematics.sync-cron:${FLEET_TELEMATICS_SYNC_CRON:0 */5 * * * *}}")
    @SchedulerLock(name = "fleet-telematics-sync", lockAtMostFor = "PT10M")
    public void sync() {
        List<String> orgs = db.jdbc().sql("SELECT id FROM \"Organization\" WHERE \"deletedAt\" IS NULL")
                .query(String.class).list();
        for (String orgId : orgs) {
            try {
                TenantContext.run(TenantStore.organization(orgId), () -> {
                    if (db.jdbc().sql("SELECT count(*) FROM \"FleetTelematicsDevice\" WHERE \"organizationId\" = ?"
                            + " AND \"isActive\"").param(orgId).query(Long.class).single() > 0) {
                        var r = telematics.syncOrganization(Instant.now());
                        log.info("telematics sync org {}: {}", orgId, r);
                    }
                });
            } catch (RuntimeException e) {
                log.error("telematics sync failed for org {}: {}", orgId, e.toString());
                metrics.jobFailed("fleet-telematics-sync");
            }
        }
    }
}
