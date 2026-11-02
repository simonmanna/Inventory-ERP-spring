package com.inventoryerp.expenses.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily expense schedules (EX-5, ADR-S052): recurring expenses, reminders, accrual reversals and prepaid releases for
 * every active organization, each in its own tenant scope; one failing organization never stops the others.
 * ShedLock-guarded, gated by {@code app.jobs.enabled}; cron {@code app.expenses.schedule-cron}.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ExpenseScheduleJob {

    private static final Logger log = LoggerFactory.getLogger(ExpenseScheduleJob.class);

    private final JdbcClient jdbc;
    private final ExpenseScheduleService schedules;
    private final JobMetrics metrics;

    public ExpenseScheduleJob(JdbcClient jdbc, ExpenseScheduleService schedules, JobMetrics metrics) {
        this.jdbc = jdbc;
        this.schedules = schedules;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${app.expenses.schedule-cron:0 20 1 * * *}")
    @SchedulerLock(name = "expense-schedules", lockAtMostFor = "PT1H")
    public void run() {
        List<String> orgs = jdbc.sql("SELECT id FROM \"Organization\" WHERE \"deletedAt\" IS NULL AND status::text ="
                + " 'active'").query(String.class).list();
        for (String orgId : orgs) {
            try {
                Map<String, Object> result = TenantContext.run(TenantContext.TenantStore.organization(orgId),
                        () -> schedules.runDue(LocalDate.now(BusinessZone.get())));
                log.info("expense schedules org {}: {}", orgId, result);
            } catch (RuntimeException err) {
                log.error("expense schedules failed for org {}: {}", orgId, err.toString());
                metrics.jobFailed("expense-schedules");
            }
        }
    }
}
