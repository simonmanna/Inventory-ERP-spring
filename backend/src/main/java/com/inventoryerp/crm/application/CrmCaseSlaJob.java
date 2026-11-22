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

/** Every five minutes: response-time warnings and overdue alerts for live CRM cases (CRM enhancements T1). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CrmCaseSlaJob {

    private static final Logger log = LoggerFactory.getLogger(CrmCaseSlaJob.class);

    private final JdbcClient jdbc;
    private final CrmCaseService cases;

    public CrmCaseSlaJob(JdbcClient jdbc, CrmCaseService cases) {
        this.jdbc = jdbc;
        this.cases = cases;
    }

    @Scheduled(cron = "15 */5 * * * *")
    @SchedulerLock(name = "crm-case-sla", lockAtMostFor = "PT10M")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"CrmCase\" WHERE status IN ('open',"
                + " 'in_progress') AND \"dueAt\" IS NOT NULL AND \"slaBreachedAt\" IS NULL").query(String.class).list();
        for (String orgId : orgs) {
            try {
                int sent = TenantContext.run(TenantContext.TenantStore.organization(orgId), cases::sendSlaAlerts);
                if (sent > 0) {
                    log.info("CRM case alerts sent for {}: {}", orgId, sent);
                }
            } catch (RuntimeException e) {
                log.warn("CRM case alerts failed for {}: {}", orgId, e.toString());
            }
        }
    }
}
