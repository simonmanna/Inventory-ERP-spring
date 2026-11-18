package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
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
 * The camera platform's schedule (LP1). Every minute: agents that stopped sending heartbeats go offline, commands past
 * their expiry lapse (a lapsed upload waits on the box again), and on every instance finished live views end. Every
 * night: evidence retention and heartbeat history. Only organizations with an edge agent are visited; one failing
 * organization never stops the others.
 */
@Component
@ConditionalOnExpression("${app.jobs.enabled:true} and ${app.modules.lossprevention:false}")
public class LpVisionJob {

    private static final Logger log = LoggerFactory.getLogger(LpVisionJob.class);

    private final JdbcClient jdbc;
    private final LpAgentService agents;
    private final LpAgentCommandService commands;
    private final LpEvidenceService evidence;
    private final LpLiveService live;
    private final JobMetrics metrics;

    public LpVisionJob(JdbcClient jdbc, LpAgentService agents, LpAgentCommandService commands,
            LpEvidenceService evidence, LpLiveService live, JobMetrics metrics) {
        this.jdbc = jdbc;
        this.agents = agents;
        this.commands = commands;
        this.evidence = evidence;
        this.live = live;
        this.metrics = metrics;
    }

    private List<String> organizations() {
        return jdbc.sql("SELECT DISTINCT a.\"organizationId\" FROM \"LpAgent\" a JOIN \"Organization\" o ON o.id ="
                + " a.\"organizationId\" WHERE o.\"deletedAt\" IS NULL AND o.status::text = 'active'")
                .query(String.class).list();
    }

    @Scheduled(cron = "${app.lossprevention.vision-cron:20 * * * * *}")
    @SchedulerLock(name = "loss-prevention-vision", lockAtMostFor = "PT5M")
    public void tick() {
        for (String orgId : organizations()) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
                    int silent = agents.markSilent();
                    List<String> lapsed = commands.expire();
                    lapsed.forEach(evidence::uploadLapsed);
                    if (silent > 0 || !lapsed.isEmpty()) {
                        log.info("loss prevention org {}: {} agent(s) offline, {} upload(s) lapsed", orgId, silent,
                                lapsed.size());
                    }
                });
            } catch (RuntimeException err) {
                log.error("loss prevention vision tick failed for org {}: {}", orgId, err.toString());
                metrics.jobFailed("loss-prevention-vision");
            }
        }
    }

    /** Live views are in this instance's memory: every instance ends its own, so there is no lock. */
    @Scheduled(fixedDelayString = "${app.lossprevention.live-sweep:PT30S}")
    public void liveSweep() {
        try {
            live.sweep();
        } catch (RuntimeException err) {
            log.warn("loss prevention live view sweep failed: {}", err.toString());
        }
    }

    @Scheduled(cron = "${app.lossprevention.vision-housekeeping-cron:0 17 3 * * *}")
    @SchedulerLock(name = "loss-prevention-vision-housekeeping", lockAtMostFor = "PT1H")
    public void housekeeping() {
        for (String orgId : organizations()) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
                    Map<String, Object> done = evidence.retention();
                    int pruned = agents.pruneHeartbeats();
                    log.info("loss prevention org {}: evidence retention {}, {} heartbeat(s) pruned", orgId, done,
                            pruned);
                });
            } catch (RuntimeException err) {
                log.error("loss prevention housekeeping failed for org {}: {}", orgId, err.toString());
                metrics.jobFailed("loss-prevention-vision-housekeeping");
            }
        }
    }
}
