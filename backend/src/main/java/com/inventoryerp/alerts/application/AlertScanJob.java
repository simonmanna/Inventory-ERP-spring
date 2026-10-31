package com.inventoryerp.alerts.application;

import com.inventoryerp.alerts.spi.AlertTypes;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The alert scans (ADR-S021): {@code ALERTS_HOURLY_CRON} for what the yard acts on today, {@code ALERTS_DAILY_CRON} for
 * the rest. ShedLock-guarded, gated by {@code app.jobs.enabled} and {@code app.modules.alerts}. Every active
 * organization runs in its own tenant scope and one failing organization never stops the others. Re-running sends
 * nothing twice.
 */
@Component
@ConditionalOnExpression("${app.jobs.enabled:true} and ${app.modules.alerts:false}")
public class AlertScanJob {

    private static final Logger log = LoggerFactory.getLogger(AlertScanJob.class);

    private final JdbcClient jdbc;
    private final AlertEngine engine;

    public AlertScanJob(JdbcClient jdbc, AlertEngine engine) {
        this.jdbc = jdbc;
        this.engine = engine;
    }

    @Scheduled(cron = "${app.alerts.hourly-cron:0 10 * * * *}")
    @SchedulerLock(name = "alerts-hourly-scan", lockAtMostFor = "PT30M")
    public void hourly() {
        scan(AlertTypes.HOURLY);
    }

    @Scheduled(cron = "${app.alerts.daily-cron:0 30 6 * * *}")
    @SchedulerLock(name = "alerts-daily-scan", lockAtMostFor = "PT1H")
    public void daily() {
        scan(AlertTypes.DAILY);
    }

    private void scan(String cadence) {
        List<String> orgs = jdbc.sql("SELECT id FROM \"Organization\" WHERE \"deletedAt\" IS NULL AND status::text ="
                + " 'active'").query(String.class).list();
        for (String orgId : orgs) {
            try {
                Map<String, Object> result = engine.runForOrg(orgId, cadence);
                log.info("alert scan {} org {}: {}", cadence, orgId, result.get("raised"));
            } catch (RuntimeException err) {
                log.error("alert scan {} failed for org {}: {}", cadence, orgId, err.toString());
            }
        }
    }
}
