package com.inventoryerp.crm.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute: in-app reminders for planned CRM activities whose {@code remindAt} has passed (ADR-S010). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CrmReminderJob {

    private static final Logger log = LoggerFactory.getLogger(CrmReminderJob.class);

    private final JdbcClient jdbc;
    private final CrmPlannerService planner;

    public CrmReminderJob(JdbcClient jdbc, CrmPlannerService planner) {
        this.jdbc = jdbc;
        this.planner = planner;
    }

    @Scheduled(cron = "30 * * * * *")
    @SchedulerLock(name = "crm-activity-reminders", lockAtMostFor = "PT5M")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"Activity\" WHERE \"remindAt\" <= now()"
                + " AT TIME ZONE 'UTC' AND \"reminderSentAt\" IS NULL AND \"deletedAt\" IS NULL").query(String.class)
                .list();
        for (String orgId : orgs) {
            try {
                int sent = TenantContext.run(TenantContext.TenantStore.organization(orgId), planner::sendDueReminders);
                if (sent > 0) {
                    log.info("CRM reminders sent for {}: {}", orgId, sent);
                }
            } catch (RuntimeException e) {
                log.warn("CRM reminders failed for {}: {}", orgId, e.toString());
            }
        }
    }
}
