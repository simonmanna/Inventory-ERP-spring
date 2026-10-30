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
 * Daily fleet reminder run (fleet 1.9): expiry reminders for vehicle compliance documents, driver licences / medicals /
 * permits and stored weighbridge tares, and service-due alerts — see {@link FleetReminderService}. ShedLock-guarded
 * ({@code fleet-reminders}), gated by {@code app.jobs.enabled} and {@code app.modules.fleet}, cron
 * {@code FLEET_REMINDERS_CRON} (default 06:00); every organization with fleet units runs in its own tenant scope and
 * one failing organization never stops the others. Re-running the same day sends nothing twice.
 */
@Component
@ConditionalOnExpression("${app.jobs.enabled:true} and ${app.modules.fleet:false}")
public class FleetReminderJob {

    private static final Logger log = LoggerFactory.getLogger(FleetReminderJob.class);

    private final FleetDb db;
    private final FleetReminderService reminders;

    public FleetReminderJob(FleetDb db, FleetReminderService reminders) {
        this.db = db;
        this.reminders = reminders;
    }

    @Scheduled(cron = "${app.fleet.reminders-cron:0 0 6 * * *}")
    @SchedulerLock(name = "fleet-reminders", lockAtMostFor = "PT30M")
    public void run() {
        List<String> orgs = db.jdbc().sql("SELECT DISTINCT \"organizationId\" FROM \"FleetVehicle\" WHERE"
                + " \"deletedAt\" IS NULL UNION SELECT DISTINCT \"organizationId\" FROM \"FleetDriver\" WHERE"
                + " \"deletedAt\" IS NULL").query(String.class).list();
        for (String orgId : orgs) {
            try {
                runForOrg(orgId);
            } catch (RuntimeException err) {
                log.error("fleet reminders failed for org {}: {}", orgId, err.toString());
            }
        }
    }

    /** One organization's pass in its tenant scope (also driven directly by the integration tests). */
    public Map<String, Object> runForOrg(String orgId) {
        return TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
            Map<String, Object> result = reminders.run(null);
            log.info("fleet reminders org {}: {}", orgId, result.get("sent"));
            return result;
        });
    }
}
