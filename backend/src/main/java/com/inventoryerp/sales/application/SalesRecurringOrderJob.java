package com.inventoryerp.sales.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily 05:50: draft the recurring sales orders that are due (SG-9). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SalesRecurringOrderJob {

    private static final Logger log = LoggerFactory.getLogger(SalesRecurringOrderJob.class);

    private final JdbcClient jdbc;
    private final SalesRecurringOrderService recurring;

    public SalesRecurringOrderJob(JdbcClient jdbc, SalesRecurringOrderService recurring) {
        this.jdbc = jdbc;
        this.recurring = recurring;
    }

    @Scheduled(cron = "0 50 5 * * *")
    @SchedulerLock(name = "sales-recurring-orders", lockAtMostFor = "PT30M")
    public void daily() {
        List<String> orgs = jdbc.sql("SELECT id FROM \"Organization\" WHERE \"deletedAt\" IS NULL").query(String.class).list();
        for (String org : orgs) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(org), () -> recurring.runDue(LocalDate.now()));
            } catch (RuntimeException e) {
                log.warn("Recurring sales orders failed for {}: {}", org, e.toString());
            }
        }
    }
}
