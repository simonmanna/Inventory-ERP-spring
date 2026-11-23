package com.inventoryerp.inventory.application;

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

/** Daily (03:10 UTC): inventory integrity check (hardening R0) of every organisation that holds stock. */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class InventoryIntegrityJob {

    private static final Logger log = LoggerFactory.getLogger(InventoryIntegrityJob.class);

    private final JdbcClient jdbc;
    private final InventoryIntegrityMonitor monitor;
    private final JobMetrics metrics;

    public InventoryIntegrityJob(JdbcClient jdbc, InventoryIntegrityMonitor monitor, JobMetrics metrics) {
        this.jdbc = jdbc;
        this.monitor = monitor;
        this.metrics = metrics;
    }

    @Scheduled(cron = "0 10 3 * * *", zone = "UTC")
    @SchedulerLock(name = "inventory-integrity-check", lockAtMostFor = "PT2H")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT o.id FROM \"Organization\" o WHERE EXISTS (SELECT 1 FROM \"StockItem\" s"
                + " WHERE s.\"organizationId\" = o.id)").query(String.class).list();
        for (String orgId : orgs) {
            try {
                InventoryIntegrityMonitor.Result out = TenantContext.run(
                        TenantContext.TenantStore.organization(orgId), monitor::check);
                if (!out.raised().isEmpty()) {
                    log.warn("Inventory integrity findings for {}: {}", orgId, out.raised());
                }
            } catch (RuntimeException e) {
                log.warn("Inventory integrity check failed for {}: {}", orgId, e.toString());
                metrics.jobFailed("inventory-integrity-check");
            }
        }
    }
}
