package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Curing tick (ADR-S014, Phase 2.4), every 15 minutes by default: per organization, passes the stage gates whose
 * minimum curing time has ended (a pass recorded while curing) and opens final inspection lots for freshly released
 * precast batches. ShedLock-guarded, gated by {@code app.jobs.enabled} and {@code app.modules.manufacturing}; one
 * failing organization never stops the others.
 */
@Component
@ConditionalOnExpression("${app.jobs.enabled:true} and ${app.modules.manufacturing:false}")
public class PrecastCuringJob {

    private static final Logger log = LoggerFactory.getLogger(PrecastCuringJob.class);

    private final MfgDb db;
    private final PrecastProductionService precast;

    public PrecastCuringJob(MfgDb db, PrecastProductionService precast) {
        this.db = db;
        this.precast = precast;
    }

    @Scheduled(cron = "${app.manufacturing.precast-curing-cron:0 */15 * * * *}")
    @SchedulerLock(name = "precast-curing-tick", lockAtMostFor = "PT10M")
    public void run() {
        List<String> orgs = db.jdbc().sql("SELECT DISTINCT \"organizationId\" FROM \"ProductionOrderStage\""
                + " WHERE \"qcStatus\" = 'curing' UNION SELECT DISTINCT \"organizationId\" FROM \"PrecastCuringBatch\""
                + " WHERE status = 'released' AND \"finalLotId\" IS NULL").query(String.class).list();
        for (String orgId : orgs) {
            try {
                runForOrg(orgId);
            } catch (RuntimeException err) {
                log.error("precast curing tick failed for org {}: {}", orgId, err.toString());
            }
        }
    }

    /** One organization's pass in its tenant scope (also driven directly by the integration tests). */
    public Map<String, Object> runForOrg(String orgId) {
        return TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
            return precast.tick();
        });
    }
}
