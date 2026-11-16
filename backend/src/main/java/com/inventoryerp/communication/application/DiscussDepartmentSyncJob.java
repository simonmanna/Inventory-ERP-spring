package com.inventoryerp.communication.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly: keeps department channel members in line with HR, for organizations that ran the department sync at least
 * once (ADR-S064). Off with {@code app.jobs.enabled=false}.
 */
@Component
@ConditionalOnExpression("${app.modules.communication:false} and ${app.jobs.enabled:true}")
public class DiscussDepartmentSyncJob {

    private static final Logger log = LoggerFactory.getLogger(DiscussDepartmentSyncJob.class);

    private final JdbcClient jdbc;
    private final DepartmentChannelSync sync;

    public DiscussDepartmentSyncJob(JdbcClient jdbc, DepartmentChannelSync sync) {
        this.jdbc = jdbc;
        this.sync = sync;
    }

    @Scheduled(cron = "0 17 * * * *")
    @SchedulerLock(name = "discuss-department-sync", lockAtMostFor = "PT30M")
    public void hourly() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"Conversation\""
                        + " WHERE \"departmentId\" IS NOT NULL AND \"deletedAt\" IS NULL")
                .query(String.class).list();
        for (String org : orgs) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(org), sync::sync);
            } catch (RuntimeException e) {
                log.warn("Department channel sync failed for {}: {}", org, e.toString());
            }
        }
    }
}
