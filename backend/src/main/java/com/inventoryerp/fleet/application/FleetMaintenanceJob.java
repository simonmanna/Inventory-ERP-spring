package com.inventoryerp.fleet.application;

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
 * Daily preventive-maintenance run (fleet 1.6): for every organization, raise a work order for each due / overdue
 * service schedule that has no live one ({@code source = schedule_job}). ShedLock-guarded, gated by
 * {@code app.jobs.enabled} and {@code app.modules.fleet}; one failing organization never stops the others.
 */
@Component
@ConditionalOnExpression("${app.jobs.enabled:true} and ${app.modules.fleet:false}")
public class FleetMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(FleetMaintenanceJob.class);

    private final FleetDb db;
    private final FleetServiceScheduleService schedules;

    public FleetMaintenanceJob(FleetDb db, FleetServiceScheduleService schedules) {
        this.db = db;
        this.schedules = schedules;
    }

    @Scheduled(cron = "${app.fleet.maintenance-due-cron:0 30 5 * * *}")
    @SchedulerLock(name = "fleet-maintenance-due", lockAtMostFor = "PT30M")
    public void run() {
        List<String> orgs = db.jdbc().sql("SELECT DISTINCT \"organizationId\" FROM \"FleetServiceSchedule\""
                + " WHERE status = 'active'").query(String.class).list();
        for (String orgId : orgs) {
            try {
                runForOrg(orgId);
            } catch (RuntimeException err) {
                log.error("fleet maintenance-due run failed for org {}: {}", orgId, err.toString());
            }
        }
    }

    /** One organization's pass in its tenant scope (also driven directly by the integration tests). */
    public Map<String, Object> runForOrg(String orgId) {
        return TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
            Map<String, Object> result = schedules.generateDue("schedule_job");
            if (((Number) result.get("created")).intValue() > 0) {
                log.info("raised {} preventive work order(s) in org {}", result.get("created"), orgId);
            }
            return result;
        });
    }
}
