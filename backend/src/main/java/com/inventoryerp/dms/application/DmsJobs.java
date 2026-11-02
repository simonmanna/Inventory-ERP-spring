package com.inventoryerp.dms.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.events.DomainEvents;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DMS background work (one node at a time): expiry reminders and expiry facts, overdue document-request reminders,
 * and surfacing module-owned attachments (kernel {@code File} rows of known owner types) as read-only linked documents.
 */
@Component
public class DmsJobs {

    private static final Logger log = LoggerFactory.getLogger(DmsJobs.class);

    /** File owner type → (workspace code, record type or null). Only these are surfaced (never chat attachments). */
    static final Map<String, String[]> LEGACY = Map.ofEntries(
            Map.entry("partner", new String[] {"general", "partner"}),
            Map.entry("product", new String[] {"general", "product"}),
            Map.entry("expense", new String[] {"expenses", "expense"}),
            Map.entry("invoice", new String[] {"finance", "invoice"}),
            Map.entry("payment", new String[] {"finance", null}),
            Map.entry("document", new String[] {"finance", null}),
            Map.entry("asset", new String[] {"finance", "asset"}),
            Map.entry("fleet_vehicle", new String[] {"general", null}),
            Map.entry("repair_order", new String[] {"general", null}),
            Map.entry("hr_employee", new String[] {"hr", "employee"}));

    private final JdbcClient jdbc;
    private final DmsWorkspaceService workspaces;
    private final NotificationsService notifications;
    private final EventOutboxService outbox;
    private final TransactionTemplate tx;

    public DmsJobs(JdbcClient jdbc, DmsWorkspaceService workspaces, NotificationsService notifications,
            EventOutboxService outbox, org.springframework.transaction.PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.notifications = notifications;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txm);
    }

    @Scheduled(cron = "${app.dms.expiry-cron:0 15 6 * * *}")
    @SchedulerLock(name = "dms-expiry", lockAtMostFor = "PT30M")
    public void daily() {
        int a = remindExpiring();
        int b = remindOverdueRequests();
        if (a + b > 0) {
            log.info("dms reminders: {} expiry, {} overdue requests", a, b);
        }
    }

    @Scheduled(fixedDelayString = "${app.dms.legacy-index-interval-ms:600000}", initialDelay = 90_000)
    @SchedulerLock(name = "dms-legacy-index", lockAtMostFor = "PT15M")
    public void legacyIndexJob() {
        int n = indexLegacy();
        if (n > 0) {
            log.info("dms surfaced {} module attachment(s)", n);
        }
    }

    /** Expiring (inside the reminder lead time) and expired documents: notify the owner once each, emit facts. */
    public int remindExpiring() {
        int count = 0;
        List<Map<String, Object>> expiring = SqlRows.list(jdbc.sql("""
                SELECT id, "organizationId", name, "expiresOn", coalesce("ownerUserId", "createdById") AS owner
                FROM "DmsDocument" WHERE "expiresOn" IS NOT NULL AND "deletedAt" IS NULL AND status <> 'archived'
                  AND "expiryNotifiedAt" IS NULL AND "expiresOn" >= current_date
                  AND "expiresOn" - "reminderLeadDays" <= current_date
                ORDER BY "expiresOn" LIMIT 1000"""));
        for (Map<String, Object> d : expiring) {
            count += notifyExpiry(d, false) ? 1 : 0;
        }
        List<Map<String, Object>> expired = SqlRows.list(jdbc.sql("""
                SELECT id, "organizationId", name, "expiresOn", coalesce("ownerUserId", "createdById") AS owner
                FROM "DmsDocument" WHERE "expiresOn" IS NOT NULL AND "deletedAt" IS NULL AND status <> 'archived'
                  AND "expiredNotifiedAt" IS NULL AND "expiresOn" < current_date
                ORDER BY "expiresOn" LIMIT 1000"""));
        for (Map<String, Object> d : expired) {
            count += notifyExpiry(d, true) ? 1 : 0;
        }
        return count;
    }

    private boolean notifyExpiry(Map<String, Object> d, boolean expired) {
        String org = (String) d.get("organizationId");
        String id = (String) d.get("id");
        try {
            TenantContext.run(TenantStore.organization(org), () -> tx.executeWithoutResult(s -> {
                String column = expired ? "\"expiredNotifiedAt\"" : "\"expiryNotifiedAt\"";
                int n = jdbc.sql("UPDATE \"DmsDocument\" SET " + column + " = now() WHERE id = ? AND " + column
                        + " IS NULL").param(id).update();
                if (n == 0) {
                    return;
                }
                if (expired) {
                    jdbc.sql("UPDATE \"DmsDocument\" SET \"expiryNotifiedAt\" = coalesce(\"expiryNotifiedAt\", now())"
                            + " WHERE id = ?").param(id).update();
                }
                String title = (expired ? "Document expired: " : "Document expiring: ") + d.get("name");
                String body = (expired ? "Expired on " : "Expires on ") + d.get("expiresOn") + ".";
                if (d.get("owner") != null) {
                    notifications.send(new SendInput(org, (String) d.get("owner"), "in_app", "dms", title, body,
                            Map.of("documentId", id, "path", "/dms?document=" + id)));
                }
                outbox.publish(expired ? DomainEvents.DMS_DOCUMENT_EXPIRED : DomainEvents.DMS_DOCUMENT_EXPIRING,
                        Map.of("organizationId", org, "documentId", id, "expiresOn", String.valueOf(d.get("expiresOn"))));
            }));
            return true;
        } catch (RuntimeException e) {
            log.warn("dms expiry reminder {} failed: {}", id, e.toString());
            return false;
        }
    }

    /** Open requests past their due date: remind the assignee once. */
    public int remindOverdueRequests() {
        int count = 0;
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("""
                SELECT id, "organizationId", title, "dueOn", "requestedFromUserId" FROM "DmsRequest"
                WHERE status = 'open' AND "dueOn" < current_date AND "overdueNotifiedAt" IS NULL LIMIT 1000"""))) {
            String org = (String) r.get("organizationId");
            try {
                TenantContext.run(TenantStore.organization(org), () -> tx.executeWithoutResult(s -> {
                    jdbc.sql("UPDATE \"DmsRequest\" SET \"overdueNotifiedAt\" = now() WHERE id = ?").param(r.get("id"))
                            .update();
                    notifications.send(new SendInput(org, (String) r.get("requestedFromUserId"), "in_app", "dms",
                            "Overdue document request: " + r.get("title"), "It was due on " + r.get("dueOn") + ".",
                            Map.of("requestId", r.get("id"), "path", "/dms/requests")));
                }));
                count++;
            } catch (RuntimeException e) {
                log.warn("dms request reminder {} failed: {}", r.get("id"), e.toString());
            }
        }
        return count;
    }

    /**
     * Surface module-owned attachments as read-only linked documents (idempotent: one linked document per file) and
     * mirror their soft deletes. Returns how many documents were created.
     */
    public int indexLegacy() {
        List<String> orgs = jdbc.sql("""
                SELECT DISTINCT f."organizationId" FROM "File" f
                WHERE f."deletedAt" IS NULL AND f."ownerType" IN (:types)
                  AND NOT EXISTS (SELECT 1 FROM "DmsDocument" d WHERE d."fileId" = f.id)""")
                .param("types", List.copyOf(LEGACY.keySet())).query(String.class).list();
        int created = 0;
        for (String org : orgs) {
            workspaces.ensureSystemWorkspaces(org);
            for (Map.Entry<String, String[]> e : LEGACY.entrySet()) {
                String resType = e.getValue()[1];
                created += tx.execute(s -> {
                    int n = jdbc.sql("""
                            INSERT INTO "DmsDocument" (id, "organizationId", "workspaceId", name, "fileId", "contentType",
                              "byteSize", "ownerUserId", "sourceKind", "legacyOwnerType", "createdById", "createdAt",
                              "updatedAt")
                            SELECT gen_random_uuid()::text, f."organizationId", w.id, f.filename, f.id, f."contentType",
                              f."byteSize", f."uploadedById", 'linked', f."ownerType", f."uploadedById", f."createdAt",
                              f."createdAt"
                            FROM "File" f JOIN "DmsWorkspace" w ON w."organizationId" = f."organizationId" AND w.code = ?
                            WHERE f."organizationId" = ? AND f."ownerType" = ? AND f."deletedAt" IS NULL
                              AND NOT EXISTS (SELECT 1 FROM "DmsDocument" d WHERE d."fileId" = f.id)
                            LIMIT 5000
                            ON CONFLICT DO NOTHING""").params(e.getValue()[0], org, e.getKey()).update();
                    if (resType != null) {
                        jdbc.sql("""
                                INSERT INTO "DmsDocumentLink" (id, "organizationId", "documentId", "resType", "resId")
                                SELECT gen_random_uuid()::text, d."organizationId", d.id, ?, f."ownerId"
                                FROM "DmsDocument" d JOIN "File" f ON f.id = d."fileId"
                                WHERE d."organizationId" = ? AND d."sourceKind" = 'linked' AND d."legacyOwnerType" = ?
                                  AND f."ownerId" IS NOT NULL
                                  AND NOT EXISTS (SELECT 1 FROM "DmsDocumentLink" l WHERE l."documentId" = d.id)
                                ON CONFLICT DO NOTHING""").params(resType, org, e.getKey()).update();
                    }
                    return n;
                });
            }
        }
        tx.executeWithoutResult(s -> jdbc.sql("""
                UPDATE "DmsDocument" d SET "deletedAt" = now(), "updatedAt" = now() FROM "File" f
                WHERE f.id = d."fileId" AND d."sourceKind" = 'linked' AND d."deletedAt" IS NULL
                  AND f."deletedAt" IS NOT NULL""").update());
        return created;
    }
}
