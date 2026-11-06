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

/** Daily 06:00: tell salespeople about targets reached or missed; each alert fires once (SG-12). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SalesTargetAlertJob {

    private static final Logger log = LoggerFactory.getLogger(SalesTargetAlertJob.class);

    private final JdbcClient jdbc;
    private final SalesTargetService targets;

    public SalesTargetAlertJob(JdbcClient jdbc, SalesTargetService targets) {
        this.jdbc = jdbc;
        this.targets = targets;
    }

    @Scheduled(cron = "0 0 6 * * *")
    @SchedulerLock(name = "sales-target-alerts", lockAtMostFor = "PT30M")
    public void daily() {
        List<String> orgs = jdbc.sql("SELECT id FROM \"Organization\" WHERE \"deletedAt\" IS NULL").query(String.class).list();
        for (String org : orgs) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(org), () -> targets.runAlerts(LocalDate.now()));
            } catch (RuntimeException e) {
                log.warn("Sales target alerts failed for {}: {}", org, e.toString());
            }
        }
    }
}
