package com.inventoryerp.hr.application;

import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled HR & payroll work (HP-1, HP-4, HP-6): base salary sync for revisions that became effective, monthly leave
 * accrual and the year-end leave rollover, and the monthly gratuity provision. Each runs per organisation that uses
 * the feature, under that organisation's tenant context.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class HrPayrollJobs {

    private static final Logger log = LoggerFactory.getLogger(HrPayrollJobs.class);

    private final JdbcClient jdbc;
    private final HrCompensationService compensation;
    private final HrLeavePolicyService leave;
    private final HrSeparationService separations;
    private final JobMetrics metrics;

    public HrPayrollJobs(JdbcClient jdbc, HrCompensationService compensation, HrLeavePolicyService leave,
            HrSeparationService separations, JobMetrics metrics) {
        this.jdbc = jdbc;
        this.compensation = compensation;
        this.leave = leave;
        this.separations = separations;
        this.metrics = metrics;
    }

    @Scheduled(cron = "0 30 1 * * *")
    @SchedulerLock(name = "hr-compensation-sync", lockAtMostFor = "PT10M")
    public void compensationSync() {
        forOrgs("hr-compensation-sync", "SELECT DISTINCT \"organizationId\" FROM \"HrEmployeeCompensation\" WHERE"
                + " status = 'ACTIVE' AND \"deletedAt\" IS NULL", compensation::syncDueBaseSalaries);
    }

    /** First day of each month, 02:00: accrue monthly leave up to yesterday; on 1 January also roll the year over. */
    @Scheduled(cron = "0 0 2 1 * *")
    @SchedulerLock(name = "hr-leave-accrual", lockAtMostFor = "PT30M")
    public void leaveAccrual() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String sql = "SELECT DISTINCT \"organizationId\" FROM \"HrLeavePolicy\" WHERE \"deletedAt\" IS NULL";
        if (today.getDayOfYear() == 1) {
            forOrgs("hr-leave-year-end", sql, () -> leave.yearEnd(today.getYear() - 1));
        }
        forOrgs("hr-leave-accrual", sql, () -> leave.accrue(today.minusDays(1)));
    }

    /** Last day of each month, 23:00: gratuity provision for organisations on a pack with end-of-service benefit. */
    @Scheduled(cron = "0 0 23 L * *")
    @SchedulerLock(name = "hr-gratuity-provision", lockAtMostFor = "PT30M")
    public void gratuityProvision() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        forOrgs("hr-gratuity-provision", "SELECT \"organizationId\" FROM \"HrPayrollSettings\" WHERE"
                + " \"statutoryPack\" = 'AE' OR \"gratuityDaysPerYear\" > 0", () -> separations.accrueProvision(today));
    }

    private void forOrgs(String job, String sql, Supplier<Object> work) {
        List<String> orgs = jdbc.sql(sql).query(String.class).list();
        for (String orgId : orgs) {
            try {
                Object r = TenantContext.run(TenantContext.TenantStore.organization(orgId), work::get);
                log.info("{} for {}: {}", job, orgId, r);
            } catch (RuntimeException e) {
                log.warn("{} failed for {}: {}", job, orgId, e.toString());
                metrics.jobFailed(job);
            }
        }
    }
}
