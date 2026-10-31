package com.inventoryerp.inventory.application;

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

/** Every hour: replenishment rules are checked against available stock (INV-3). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ReplenishmentScanJob {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentScanJob.class);

    private final JdbcClient jdbc;
    private final ReplenishmentService replenishment;

    public ReplenishmentScanJob(JdbcClient jdbc, ReplenishmentService replenishment) {
        this.jdbc = jdbc;
        this.replenishment = replenishment;
    }

    @Scheduled(cron = "0 15 * * * *")
    @SchedulerLock(name = "inventory-replenishment-scan", lockAtMostFor = "PT15M")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"InventoryReplenishmentRule\" WHERE"
                + " \"isActive\" AND \"deletedAt\" IS NULL").query(String.class).list();
        for (String orgId : orgs) {
            try {
                Map<String, Object> out = TenantContext.run(TenantContext.TenantStore.organization(orgId),
                        replenishment::scan);
                log.info("Replenishment scan for {}: {}", orgId, out);
            } catch (RuntimeException e) {
                log.warn("Replenishment scan failed for {}: {}", orgId, e.toString());
            }
        }
    }
}
