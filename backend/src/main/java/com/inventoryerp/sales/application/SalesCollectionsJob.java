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
 * Collections schedule (ADR-S011 S8): daily 06:00 — promises to pay past their date are settled kept / broken and,
 * where {@code dunningAutoRun}, the dunning run executes; 1st of the month 07:00 — statements e-mailed where
 * {@code monthlyStatements}.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SalesCollectionsJob {

    private static final Logger log = LoggerFactory.getLogger(SalesCollectionsJob.class);

    private final JdbcClient jdbc;
    private final SalesCollectionsService collections;

    public SalesCollectionsJob(JdbcClient jdbc, SalesCollectionsService collections) {
        this.jdbc = jdbc;
        this.collections = collections;
    }

    @Scheduled(cron = "0 0 6 * * *")
    @SchedulerLock(name = "sales-dunning", lockAtMostFor = "PT1H")
    public void daily() {
        List<String> orgs = jdbc.sql("SELECT \"organizationId\" FROM \"SalesSettings\" WHERE \"dunningAutoRun\" UNION"
                + " SELECT DISTINCT \"organizationId\" FROM \"PromiseToPay\" WHERE status = 'open'").query(String.class).list();
        for (String orgId : orgs) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
                    collections.expirePromises(LocalDate.now());
                    Boolean auto = jdbc.sql("SELECT \"dunningAutoRun\" FROM \"SalesSettings\" WHERE \"organizationId\" = ?")
                            .param(orgId).query(Boolean.class).optional().orElse(false);
                    if (Boolean.TRUE.equals(auto)) {
                        collections.run(LocalDate.now(), "scheduled");
                    }
                });
            } catch (RuntimeException e) {
                log.warn("Collections run failed for {}: {}", orgId, e.toString());
            }
        }
    }

    @Scheduled(cron = "0 0 7 1 * *")
    @SchedulerLock(name = "sales-statements", lockAtMostFor = "PT2H")
    public void monthly() {
        List<String> orgs = jdbc.sql("SELECT \"organizationId\" FROM \"SalesSettings\" WHERE \"monthlyStatements\"")
                .query(String.class).list();
        for (String orgId : orgs) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
                    collections.statementRun(LocalDate.now());
                });
            } catch (RuntimeException e) {
                log.warn("Statement run failed for {}: {}", orgId, e.toString());
            }
        }
    }
}
