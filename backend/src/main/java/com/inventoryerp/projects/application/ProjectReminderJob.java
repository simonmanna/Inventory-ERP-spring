package com.inventoryerp.projects.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily deadline reminders (ADR-S062): assignees of open project tasks due today or tomorrow, or overdue, get one
 * in-app notification per task and day.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ProjectReminderJob {

    private static final Logger log = LoggerFactory.getLogger(ProjectReminderJob.class);

    private final JdbcClient jdbc;
    private final PmSupport pm;
    private final TransactionTemplate tx;

    public ProjectReminderJob(JdbcClient jdbc, PmSupport pm, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.pm = pm;
        this.tx = new TransactionTemplate(txm);
    }

    @Scheduled(cron = "${app.projects.reminder-cron:0 0 6 * * *}")
    @SchedulerLock(name = "project-deadline-reminders", lockAtMostFor = "PT15M")
    public void tick() {
        List<String> orgs = jdbc.sql("SELECT DISTINCT \"organizationId\" FROM \"ProjectTask\" WHERE \"deletedAt\" IS NULL"
                + " AND NOT \"isClosed\" AND deadline <= CURRENT_DATE + 1 AND (\"lastReminderOn\" IS NULL OR"
                + " \"lastReminderOn\" < CURRENT_DATE)").query(String.class).list();
        for (String org : orgs) {
            try {
                int sent = TenantContext.run(TenantStore.organization(org), () -> tx.execute(s -> remind()));
                if (sent > 0) {
                    log.info("Project deadline reminders for {}: {}", org, sent);
                }
            } catch (RuntimeException e) {
                log.warn("Project deadline reminders failed for {}: {}", org, e.toString());
            }
        }
    }

    /** Reminders of the current organisation; returns the number of tasks reminded. */
    int remind() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Map<String, Object>> due = SqlRows.list(jdbc.sql("SELECT t.id, t.number, t.title, t.deadline, t.\"projectId\""
                + " FROM \"ProjectTask\" t LEFT JOIN \"Project\" p ON p.id = t.\"projectId\" WHERE t.\"organizationId\" = ?"
                + " AND t.\"deletedAt\" IS NULL AND NOT t.\"isClosed\" AND t.deadline <= CURRENT_DATE + 1 AND"
                + " (t.\"lastReminderOn\" IS NULL OR t.\"lastReminderOn\" < CURRENT_DATE) AND (p.id IS NULL OR NOT"
                + " p.\"isTemplate\") ORDER BY t.deadline LIMIT 2000 FOR UPDATE OF t SKIP LOCKED")
                .param(TenantContext.organizationId()));
        for (Map<String, Object> t : due) {
            LocalDate deadline = (LocalDate) t.get("deadline");
            String when = deadline.isBefore(today) ? "Overdue since " + deadline : deadline.equals(today) ? "Due today"
                    : "Due tomorrow";
            List<String> users = jdbc.sql("SELECT \"userId\" FROM \"ProjectTaskAssignee\" WHERE \"taskId\" = ?")
                    .param(t.get("id")).query(String.class).list();
            pm.notifyUsers(users, when + ": " + t.get("number"), String.valueOf(t.get("title")),
                    ProjectTaskService.path(t));
            jdbc.sql("UPDATE \"ProjectTask\" SET \"lastReminderOn\" = CURRENT_DATE WHERE id = ?").param(t.get("id"))
                    .update();
        }
        return due.size();
    }
}
