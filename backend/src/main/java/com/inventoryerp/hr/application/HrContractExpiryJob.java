package com.inventoryerp.hr.application;

import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every day at 01:20: active employment contracts past their end date expire (HR-2). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class HrContractExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HrContractExpiryJob.class);

    private final JdbcClient jdbc;
    private final HrContractService contracts;
    private final JobMetrics metrics;

    public HrContractExpiryJob(JdbcClient jdbc, HrContractService contracts, JobMetrics metrics) {
        this.jdbc = jdbc;
        this.contracts = contracts;
        this.metrics = metrics;
    }

    @Scheduled(cron = "0 20 1 * * *")
    @SchedulerLock(name = "hr-contract-expiry", lockAtMostFor = "PT10M")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"HrEmployeeContract\" WHERE status ="
                + " 'active' AND \"endDate\" < CURRENT_DATE AND \"deletedAt\" IS NULL").query(String.class).list();
        for (String orgId : orgs) {
            try {
                int expired = TenantContext.run(TenantContext.TenantStore.organization(orgId), contracts::expireDue);
                log.info("Employment contracts expired for {}: {}", orgId, expired);
            } catch (RuntimeException e) {
                log.warn("Contract expiry failed for {}: {}", orgId, e.toString());
                metrics.jobFailed("hr-contract-expiry");
            }
        }
    }
}
