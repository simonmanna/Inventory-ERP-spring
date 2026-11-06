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

/** Daily 05:40: quotations past their validity expire and their owners are told (SG-3). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SalesQuotationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(SalesQuotationExpiryJob.class);

    private final JdbcClient jdbc;
    private final SalesQuotationService quotations;

    public SalesQuotationExpiryJob(JdbcClient jdbc, SalesQuotationService quotations) {
        this.jdbc = jdbc;
        this.quotations = quotations;
    }

    @Scheduled(cron = "0 40 5 * * *")
    @SchedulerLock(name = "sales-quotation-expiry", lockAtMostFor = "PT30M")
    public void daily() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"SalesQuotation\" WHERE status IN ('draft',"
                + " 'approved', 'sent') AND \"validUntil\" < CURRENT_DATE AND \"deletedAt\" IS NULL").query(String.class).list();
        for (String org : orgs) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(org), quotations::expireAndNotify);
            } catch (RuntimeException e) {
                log.warn("Quotation expiry failed for {}: {}", org, e.toString());
            }
        }
    }
}
