package com.inventoryerp.sales.application;

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

/** Nightly 03:10: rebuild sales fulfilment projections from fleet records and record drift (ADR-S011 S5). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SalesReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(SalesReconcileJob.class);

    private final JdbcClient jdbc;
    private final SalesFulfilmentService fulfilment;

    public SalesReconcileJob(JdbcClient jdbc, SalesFulfilmentService fulfilment) {
        this.jdbc = jdbc;
        this.fulfilment = fulfilment;
    }

    @Scheduled(cron = "0 10 3 * * *")
    @SchedulerLock(name = "sales-fulfilment-reconcile", lockAtMostFor = "PT1H")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"SalesOrder\" WHERE status IN ('confirmed',"
                + " 'in_fulfilment')").query(String.class).list();
        for (String orgId : orgs) {
            try {
                Map<String, Object> r = TenantContext.run(TenantContext.TenantStore.organization(orgId),
                        fulfilment::reconcile);
                if (((Number) r.get("linesDrifted")).intValue() > 0) {
                    log.warn("Sales fulfilment drift repaired for {}: {} lines", orgId, r.get("linesDrifted"));
                }
            } catch (RuntimeException e) {
                log.warn("Sales reconciliation failed for {}: {}", orgId, e.toString());
            }
        }
    }
}
