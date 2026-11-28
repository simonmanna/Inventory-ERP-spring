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
    private final SalesBillingService billing;

    private final SalesOrderService orders;
    private final org.springframework.transaction.support.TransactionTemplate perOrder;

    public SalesReconcileJob(JdbcClient jdbc, SalesFulfilmentService fulfilment, SalesBillingService billing,
            SalesOrderService orders, org.springframework.transaction.PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.fulfilment = fulfilment;
        this.billing = billing;
        this.orders = orders;
        this.perOrder = new org.springframework.transaction.support.TransactionTemplate(txm);
    }

    @Scheduled(cron = "0 10 3 * * *")
    @SchedulerLock(name = "sales-fulfilment-reconcile", lockAtMostFor = "PT1H")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"SalesOrder\" WHERE status IN ('confirmed'"
                + ")").query(String.class).list();
        for (String orgId : orgs) {
            try {
                Map<String, Object> r = TenantContext.run(TenantContext.TenantStore.organization(orgId),
                        fulfilment::reconcile);
                if (((Number) r.get("linesDrifted")).intValue() > 0) {
                    log.warn("Sales fulfilment drift repaired for {}: {} lines", orgId, r.get("linesDrifted"));
                }
                Map<String, Object> f = TenantContext.run(TenantContext.TenantStore.organization(orgId),
                        billing::financialReconcile);
                if (!((List<?>) f.get("findings")).isEmpty()) {
                    log.warn("Sales financial reconciliation for {}: {} findings", orgId, ((List<?>) f.get("findings"))
                            .size());
                }
            } catch (RuntimeException e) {
                log.warn("Sales reconciliation failed for {}: {}", orgId, e.toString());
            }
        }
        refreshOutcomes();
    }

    /**
     * ADR-S094 §4.4: recompute the stored outcome of every sale not settled yet (or never computed), one transaction
     * per sale, so a receipt, allocation or credit that raised no event (or whose event was lost) is caught up.
     */
    void refreshOutcomes() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"SalesOrder\" WHERE status <> 'draft' AND"
                + " \"deletedAt\" IS NULL AND (\"outcomeAt\" IS NULL OR \"settlementStatus\" = 'open')").query(String.class)
                .list();
        for (String orgId : orgs) {
            try {
                int n = TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
                    List<String> ids = jdbc.sql("SELECT id FROM \"SalesOrder\" WHERE \"organizationId\" = ? AND status <>"
                            + " 'draft' AND \"deletedAt\" IS NULL AND (\"outcomeAt\" IS NULL OR \"settlementStatus\" ="
                            + " 'open')").param(orgId).query(String.class).list();
                    int done = 0;
                    for (String id : ids) {
                        try {
                            perOrder.executeWithoutResult(t -> orders.refreshStatus(id));
                            done++;
                        } catch (RuntimeException e) {
                            // one sale that cannot be recomputed must not hold back the others
                            log.warn("Sales outcome refresh failed for order {}: {}", id, e.toString());
                        }
                    }
                    return done;
                });
                log.debug("Sales outcomes refreshed for {}: {}", orgId, n);
            } catch (RuntimeException e) {
                log.warn("Sales outcome refresh failed for {}: {}", orgId, e.toString());
            }
        }
    }
}
