package com.inventoryerp.reporting.application;

import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers due report schedules every 15 minutes (one node at a time). */
@Component
public class ReportScheduleJob {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduleJob.class);

    private final ReportScheduleService schedules;

    public ReportScheduleJob(ReportScheduleService schedules) {
        this.schedules = schedules;
    }

    @Scheduled(cron = "${app.reports.schedule-cron:0 */15 * * * *}")
    @SchedulerLock(name = "report-schedules", lockAtMostFor = "PT14M")
    public void deliverDue() {
        int sent = schedules.runDue(Instant.now());
        if (sent > 0) {
            log.info("report schedules delivered: {}", sent);
        }
    }
}
