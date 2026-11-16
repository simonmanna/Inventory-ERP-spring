package com.inventoryerp.projects.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily (ADR-S074): live bonds and guarantees past their expiry date become {@code expired}, per organisation. */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ProjectBondJob {

    private static final Logger log = LoggerFactory.getLogger(ProjectBondJob.class);

    private final JdbcClient jdbc;
    private final ProjectBondService bonds;

    public ProjectBondJob(JdbcClient jdbc, ProjectBondService bonds) {
        this.jdbc = jdbc;
        this.bonds = bonds;
    }

    @Scheduled(cron = "${app.projects.bond-expiry-cron:0 15 0 * * *}")
    @SchedulerLock(name = "project-bond-expiry", lockAtMostFor = "PT15M")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"ProjectBond\" WHERE status IN"
                + " ('active', 'extended', 'reduced') AND \"expiryDate\" < CURRENT_DATE").query(String.class).list();
        for (String org : orgs) {
            try {
                int n = TenantContext.run(TenantStore.organization(org), bonds::expireDue);
                if (n > 0) {
                    log.info("Bonds expired for {}: {}", org, n);
                }
            } catch (RuntimeException e) {
                log.warn("Bond expiry failed for {}: {}", org, e.toString());
            }
        }
    }
}
