package com.inventoryerp.crm.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.tenancy.TenantContext;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional outbox for CRM e-mails (production-readiness fix P0-6). {@link CrmEmailService#send} writes the
 * {@code CrmEmailOutbox} row together with the email activity it logs, in the request's transaction; this worker sends
 * it after the commit (straight away for the request, and every 30 s for anything due) and records the outcome on the
 * row and as the activity's {@code outcome}: {@code Sent}, {@code Retrying} with the error, or {@code Failed}. A
 * transport error is retried with backoff up to {@link #MAX_ATTEMPTS} attempts, then the sender gets an in-app
 * notification. Without outgoing mail configured (no {@code SMTP_HOST}) the row fails at once with that reason, and
 * nothing ever says "sent". Rows survive a restart, so nothing is lost when the API stops mid-way. Delivery is
 * at-least-once: a crash between the SMTP hand-off and the commit can send a message twice.
 */
@Component
public class CrmEmailOutbox {

    private static final Logger log = LoggerFactory.getLogger(CrmEmailOutbox.class);

    static final int MAX_ATTEMPTS = 5;
    /** Wait before attempt n+1 (seconds), n = attempts so far. */
    private static final long[] BACKOFF_S = {60, 300, 1_800, 7_200};
    static final String NOT_CONFIGURED = "Outgoing email is not set up on this server (SMTP)";

    /** Outcome of one delivery attempt. */
    public enum Result { SENT, RETRY, FAILED, NOT_CONFIGURED, SKIPPED }

    private final JdbcClient jdbc;
    private final NotificationsService notifications;
    private final TransactionTemplate tx;

    public CrmEmailOutbox(JdbcClient jdbc, NotificationsService notifications, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.tx = new TransactionTemplate(txm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Send every row that is due; returns how many were processed. */
    public int sendDue() {
        List<String> due = TenantContext.unscoped(() -> jdbc.sql("SELECT id FROM \"CrmEmailOutbox\" WHERE status ="
                + " 'pending' AND \"nextAttemptAt\" <= now() AT TIME ZONE 'UTC' ORDER BY \"nextAttemptAt\" LIMIT 200")
                .query(String.class).list());
        for (String id : due) {
            try {
                deliver(id);
            } catch (RuntimeException e) {
                log.warn("CRM email outbox row {} could not be processed: {}", id, e.toString());
            }
        }
        return due.size();
    }

    /** Every 30 s: send what is due (one instance at a time). */
    @Component
    @ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class Job {

        private final CrmEmailOutbox outbox;

        Job(CrmEmailOutbox outbox) {
            this.outbox = outbox;
        }

        @Scheduled(fixedDelayString = "${app.crm.email-outbox-delay-ms:30000}", initialDelay = 30_000)
        @SchedulerLock(name = "crm-email-outbox", lockAtMostFor = "PT10M")
        public void run() {
            outbox.sendDue();
        }
    }

    /**
     * One attempt for a pending row, in its own transaction in the row's organization. A row another worker holds,
     * or that is no longer pending, is skipped.
     */
    public Result deliver(String id) {
        Map<String, Object> head = TenantContext.unscoped(() -> jdbc.sql("SELECT \"organizationId\" FROM"
                + " \"CrmEmailOutbox\" WHERE id = ?").param(id).query().listOfRows().stream().findFirst().orElse(null));
        if (head == null) {
            return Result.SKIPPED;
        }
        String org = (String) head.get("organizationId");
        return TenantContext.run(TenantContext.TenantStore.organization(org), () -> tx.execute(s -> attempt(id, org)));
    }

    private Result attempt(String id, String org) {
        Map<String, Object> row = jdbc.sql("SELECT * FROM \"CrmEmailOutbox\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " status = 'pending' FOR UPDATE SKIP LOCKED").params(id, org).query().listOfRows().stream()
                .findFirst().orElse(null);
        if (row == null) {
            return Result.SKIPPED;
        }
        int attempts = ((Number) row.get("attempts")).intValue() + 1;
        boolean delivered;
        String error;
        try {
            delivered = notifications.sendExternalEmail((String) row.get("toAddress"), (String) row.get("subject"),
                    (String) row.get("body"), null);
            error = delivered ? null : NOT_CONFIGURED;
        } catch (RuntimeException e) {
            delivered = false;
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        String activityId = (String) row.get("activityId");
        if (delivered) {
            jdbc.sql("UPDATE \"CrmEmailOutbox\" SET status = 'sent', attempts = ?, \"sentAt\" = " + CrmDb.NOW + ","
                    + " \"lastError\" = NULL, \"updatedAt\" = " + CrmDb.NOW + " WHERE id = ?").params(attempts, id).update();
            outcome(activityId, org, "Sent");
            return Result.SENT;
        }
        boolean notConfigured = NOT_CONFIGURED.equals(error);
        String shortError = error.length() > 500 ? error.substring(0, 500) : error;
        if (notConfigured || attempts >= MAX_ATTEMPTS) {
            jdbc.sql("UPDATE \"CrmEmailOutbox\" SET status = 'failed', attempts = ?, \"lastError\" = ?, \"updatedAt\" = "
                    + CrmDb.NOW + " WHERE id = ?").params(attempts, shortError, id).update();
            outcome(activityId, org, "Not sent: " + shortError);
            notifySender(row, org, shortError);
            return notConfigured ? Result.NOT_CONFIGURED : Result.FAILED;
        }
        long wait = BACKOFF_S[Math.min(attempts - 1, BACKOFF_S.length - 1)];
        jdbc.sql("UPDATE \"CrmEmailOutbox\" SET attempts = ?, \"lastError\" = ?, \"nextAttemptAt\" = " + CrmDb.NOW
                + " + make_interval(secs => ?), \"updatedAt\" = " + CrmDb.NOW + " WHERE id = ?")
                .params(attempts, shortError, wait, id).update();
        outcome(activityId, org, "Retrying (attempt " + attempts + " of " + MAX_ATTEMPTS + " failed: " + shortError + ")");
        return Result.RETRY;
    }

    private void outcome(String activityId, String org, String text) {
        if (activityId != null) {
            jdbc.sql("UPDATE \"Activity\" SET outcome = ?, \"updatedAt\" = " + CrmDb.NOW + " WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(text, activityId, org).update();
        }
    }

    private void notifySender(Map<String, Object> row, String org, String error) {
        String to = (String) row.get("createdById");
        if (to == null) {
            return;
        }
        try {
            notifications.send(new SendInput(org, to, "in_app", "crm", "Email not sent: " + row.get("subject"),
                    "To " + row.get("toAddress") + ": " + error, Obj.of("kind", "crm.email.failed", "dealId",
                            row.get("dealId"), "link", row.get("dealId") == null ? "/crm" : "/crm/opportunities/"
                                    + row.get("dealId"))));
        } catch (RuntimeException e) {
            log.warn("CRM email failure notice could not be sent: {}", e.toString());
        }
    }
}
