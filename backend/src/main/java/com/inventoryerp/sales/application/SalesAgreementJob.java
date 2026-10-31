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

/**
 * Agreements and rebates schedule (ADR-S011 S9), daily 05:30: active agreements past their end date expire, and every
 * active rebate programme accrues as of yesterday — so the run on the first day of a period closes the accrual of the
 * period just ended.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SalesAgreementJob {

    private static final Logger log = LoggerFactory.getLogger(SalesAgreementJob.class);

    private final JdbcClient jdbc;
    private final SalesAgreementService agreements;
    private final SalesRebateService rebates;

    public SalesAgreementJob(JdbcClient jdbc, SalesAgreementService agreements, SalesRebateService rebates) {
        this.jdbc = jdbc;
        this.agreements = agreements;
        this.rebates = rebates;
    }

    @Scheduled(cron = "0 30 5 * * *")
    @SchedulerLock(name = "sales-agreements", lockAtMostFor = "PT1H")
    public void daily() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"SalesAgreement\" WHERE status = 'active'"
                + " UNION SELECT DISTINCT \"organizationId\" FROM \"RebateProgram\" WHERE status = 'active'")
                .query(String.class).list();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        for (String orgId : orgs) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
                    agreements.expireDue(LocalDate.now());
                    rebates.accrue(yesterday);
                });
            } catch (RuntimeException e) {
                log.warn("Agreement / rebate run failed for {}: {}", orgId, e.toString());
            }
        }
    }
}
