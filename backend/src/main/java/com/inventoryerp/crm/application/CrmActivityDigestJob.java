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

/**
 * Daily 07:00 business time: each person with overdue CRM activities gets one in-app digest (CRM enhancements T1).
 * Reminders only fire for activities that have a {@code remindAt}; the digest catches the rest.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CrmActivityDigestJob {

    private static final Logger log = LoggerFactory.getLogger(CrmActivityDigestJob.class);

    private final JdbcClient jdbc;
    private final CrmPlannerService planner;

    public CrmActivityDigestJob(JdbcClient jdbc, CrmPlannerService planner) {
        this.jdbc = jdbc;
        this.planner = planner;
    }

    @Scheduled(cron = "0 0 7 * * *", zone = "${app.timezone:UTC}")
    @SchedulerLock(name = "crm-activity-overdue-digest", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void daily() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"Activity\" WHERE \"dueAt\" < now() AT"
                + " TIME ZONE 'UTC' AND status::text IN ('todo', 'in_progress') AND \"deletedAt\" IS NULL")
                .query(String.class).list();
        for (String orgId : orgs) {
            try {
                int sent = TenantContext.run(TenantContext.TenantStore.organization(orgId), planner::sendOverdueDigest);
                if (sent > 0) {
                    log.info("CRM overdue digests sent for {}: {}", orgId, sent);
                }
            } catch (RuntimeException e) {
                log.warn("CRM overdue digest failed for {}: {}", orgId, e.toString());
            }
        }
    }
}
