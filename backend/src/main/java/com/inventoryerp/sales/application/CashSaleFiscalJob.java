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

/**
 * Branch cash sales background work (ADR-S056): every minute, send open fiscal operations (outbox) to their
 * provider (a claim left by a worker that stopped is taken over once it expires, lookup first); every 10 minutes,
 * alert on fiscal operations that stay unconfirmed; hourly, raise the overdue alert of rejected sales left unresolved
 * past their branch threshold.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CashSaleFiscalJob {

    private static final Logger log = LoggerFactory.getLogger(CashSaleFiscalJob.class);

    private final JdbcClient jdbc;
    private final CashSaleFiscalService fiscal;
    private final CashSaleService sales;

    public CashSaleFiscalJob(JdbcClient jdbc, CashSaleFiscalService fiscal, CashSaleService sales) {
        this.jdbc = jdbc;
        this.fiscal = fiscal;
        this.sales = sales;
    }

    @Scheduled(cron = "30 * * * * *")
    @SchedulerLock(name = "cash-sale-fiscal-outbox", lockAtMostFor = "PT55S")
    public void fiscalOutbox() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"FiscalDocument\" WHERE status IN"
                + " ('pending', 'sent', 'unknown')").query(String.class).list();
        for (String orgId : orgs) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(orgId), fiscal::processAll);
            } catch (RuntimeException e) {
                log.warn("Fiscal outbox failed for {}: {}", orgId, e.toString());
            }
        }
    }

    /**
     * ADR-S094 C3b (§5.5): every 10 minutes, alert once on fiscal operations still not confirmed half an hour after
     * they were asked for (unknown, in review, or left by a worker that stopped).
     */
    @Scheduled(cron = "15 */10 * * * *")
    @SchedulerLock(name = "cash-sale-fiscal-alerts", lockAtMostFor = "PT9M")
    public void fiscalAlerts() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"FiscalDocument\" WHERE status IN"
                + " ('sent', 'unknown', 'review') AND \"alertedAt\" IS NULL").query(String.class).list();
        for (String orgId : orgs) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(orgId), fiscal::alertStuck);
            } catch (RuntimeException e) {
                log.warn("Fiscal alerts failed for {}: {}", orgId, e.toString());
            }
        }
    }

    @Scheduled(cron = "0 5 * * * *")
    @SchedulerLock(name = "cash-sale-rejected-alerts", lockAtMostFor = "PT30M")
    public void rejectedAlerts() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"SalesSubmission\" WHERE"
                + " \"processingStatus\" = 'rejected'").query(String.class).list();
        for (String orgId : orgs) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(orgId), sales::alertOverdueRejections);
            } catch (RuntimeException e) {
                log.warn("Rejected cash-sale alerts failed for {}: {}", orgId, e.toString());
            }
        }
    }
}
