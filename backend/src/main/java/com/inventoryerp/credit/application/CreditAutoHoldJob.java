package com.inventoryerp.credit.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily 02:15: automatic credit hold of customers overdue beyond the credit policy (credit.md §7). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CreditAutoHoldJob {

    private static final Logger log = LoggerFactory.getLogger(CreditAutoHoldJob.class);

    private final JdbcClient jdbc;
    private final CreditService credit;

    public CreditAutoHoldJob(JdbcClient jdbc, CreditService credit) {
        this.jdbc = jdbc;
        this.credit = credit;
    }

    @Scheduled(cron = "0 15 2 * * *")
    @SchedulerLock(name = "credit-auto-hold", lockAtMostFor = "PT30M")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT \"organizationId\" FROM \"CreditPolicy\" WHERE \"autoHold\" AND"
                + " \"overdueAction\" = 'hard'").query(String.class).list();
        for (String orgId : orgs) {
            try {
                List<String> held = TenantContext.run(TenantContext.TenantStore.organization(orgId), credit::autoHold);
                if (!held.isEmpty()) {
                    log.info("Credit auto-hold for {}: {} customers", orgId, held.size());
                }
            } catch (RuntimeException e) {
                log.warn("Credit auto-hold failed for {}: {}", orgId, e.toString());
            }
        }
    }
}
