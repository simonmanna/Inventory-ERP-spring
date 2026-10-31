package com.inventoryerp.sales.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every 15 minutes: retry reservations of backordered sales-order lines by priority (ADR-S011 S4). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SalesBackorderJob {

    private static final Logger log = LoggerFactory.getLogger(SalesBackorderJob.class);

    private final JdbcClient jdbc;
    private final SalesAtpService atp;

    public SalesBackorderJob(JdbcClient jdbc, SalesAtpService atp) {
        this.jdbc = jdbc;
        this.atp = atp;
    }

    @Scheduled(cron = "0 */15 * * * *")
    @SchedulerLock(name = "sales-backorder-reserve", lockAtMostFor = "PT14M")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT o.\"organizationId\" FROM \"SalesOrder\" o JOIN \"SalesSettings\" s ON"
                + " s.\"organizationId\" = o.\"organizationId\" WHERE s.\"reservationTrigger\" = 'confirm' AND o.status IN"
                + " ('confirmed', 'in_fulfilment')").query(String.class).list();
        for (String orgId : orgs) {
            try {
                int n = TenantContext.run(TenantContext.TenantStore.organization(orgId), atp::retryBackorders);
                if (n > 0) {
                    log.debug("Backorder reservations retried for {}: {} orders", orgId, n);
                }
            } catch (RuntimeException e) {
                log.warn("Backorder reservations failed for {}: {}", orgId, e.toString());
            }
        }
    }
}
