package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily (02:40 UTC): inventory-to-GL tie-out check of every organisation that holds stock. */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class InventoryTieOutJob {

    private static final Logger log = LoggerFactory.getLogger(InventoryTieOutJob.class);

    private final JdbcClient jdbc;
    private final InventoryTieOutMonitor monitor;
    private final JobMetrics metrics;

    public InventoryTieOutJob(JdbcClient jdbc, InventoryTieOutMonitor monitor, JobMetrics metrics) {
        this.jdbc = jdbc;
        this.monitor = monitor;
        this.metrics = metrics;
    }

    @Scheduled(cron = "0 40 2 * * *", zone = "UTC")
    @SchedulerLock(name = "inventory-gl-tieout-check", lockAtMostFor = "PT2H")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT o.id FROM \"Organization\" o WHERE EXISTS (SELECT 1 FROM \"StockItem\" s"
                + " WHERE s.\"organizationId\" = o.id)").query(String.class).list();
        for (String orgId : orgs) {
            try {
                Map<String, Object> out = TenantContext.run(TenantContext.TenantStore.organization(orgId), monitor::check);
                if (out != null && !Boolean.TRUE.equals(out.get("withinTolerance"))) {
                    log.warn("Inventory GL tie-out variance for {}: {}", orgId, out.get("variance"));
                }
            } catch (RuntimeException e) {
                log.warn("Inventory GL tie-out check failed for {}: {}", orgId, e.toString());
                metrics.jobFailed("inventory-gl-tieout-check");
            }
        }
    }
}
