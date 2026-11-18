package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Computes the operations metrics of dirty days (LP6a). ShedLock-guarded, gated by {@code app.jobs.enabled} and
 * {@code app.modules.lossprevention}. Only organizations with a dirty day are visited; one failing organization never
 * stops the others, and a failing day stays dirty with its error recorded.
 */
@Component
@ConditionalOnExpression("${app.jobs.enabled:true} and ${app.modules.lossprevention:false}")
public class LpInsightsJob {

    private static final Logger log = LoggerFactory.getLogger(LpInsightsJob.class);

    private final JdbcClient jdbc;
    private final LpInsightsService insights;
    private final JobMetrics metrics;

    public LpInsightsJob(JdbcClient jdbc, LpInsightsService insights, JobMetrics metrics) {
        this.jdbc = jdbc;
        this.insights = insights;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${app.lossprevention.insights-cron:40 */5 * * * *}")
    @SchedulerLock(name = "loss-prevention-insights", lockAtMostFor = "PT20M")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT d.\"organizationId\" FROM \"LpMetricDay\" d JOIN"
                + " \"Organization\" o ON o.id = d.\"organizationId\" WHERE d.state = 'dirty' AND o.\"deletedAt\" IS"
                + " NULL AND o.status::text = 'active'").query(String.class).list();
        for (String orgId : orgs) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
                    int done = insights.computeDue();
                    if (done > 0) {
                        log.info("loss prevention insights org {}: {} day(s) computed", orgId, done);
                    }
                });
            } catch (RuntimeException err) {
                log.error("loss prevention insights failed for org {}: {}", orgId, err.toString());
                metrics.jobFailed("loss-prevention-insights");
            }
        }
    }
}
