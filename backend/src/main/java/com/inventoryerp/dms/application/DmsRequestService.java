package com.inventoryerp.dms.application;

import com.inventoryerp.dms.application.DmsDocumentService.FilePart;
import com.inventoryerp.dms.spi.DmsResourceProvider;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Document requests: ask a colleague for a file (optionally for a record, e.g. "signed contract" on a sales order),
 * with a due date. The assignee fulfils it by uploading; the request grants that upload. Overdue requests are
 * reminded once by {@link DmsJobs}.
 */
@Service
public class DmsRequestService {

    private final JdbcClient jdbc;
    private final DmsWorkspaceService workspaces;
    private final DmsResourceRegistry resources;
    private final DmsDocumentService documents;
    private final NotificationsService notifications;
    private final EventOutboxService outbox;

    public DmsRequestService(JdbcClient jdbc, DmsWorkspaceService workspaces, DmsResourceRegistry resources,
            DmsDocumentService documents, NotificationsService notifications, EventOutboxService outbox) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.resources = resources;
        this.documents = documents;
        this.notifications = notifications;
        this.outbox = outbox;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    public record RequestInput(String title, String note, String requestedFromUserId, String workspaceId,
            String resType, String resId, LocalDate dueOn) {}

    private Map<String, Object> row(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("""
                SELECT r.*, trim(coalesce(a."firstName", '') || ' ' || coalesce(a."lastName", '')) AS "requestedFromName",
                  trim(coalesce(b."firstName", '') || ' ' || coalesce(b."lastName", '')) AS "requestedByName",
                  w.name AS "workspaceName"
                FROM "DmsRequest" r LEFT JOIN "User" a ON a.id = r."requestedFromUserId"
                LEFT JOIN "User" b ON b.id = r."requestedById" LEFT JOIN "DmsWorkspace" w ON w.id = r."workspaceId"
                WHERE r.id = ? AND r."organizationId" = ?""").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Document request not found");
        }
        return r;
    }

    @Transactional
    public Map<String, Object> create(RequestInput in) {
        if (in.title() == null || in.title().isBlank()) {
            throw ApiException.badRequest("title should not be empty");
        }
        if (in.requestedFromUserId() == null || jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(in.requestedFromUserId(), org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("User not found");
        }
        DmsResourceProvider p = null;
        if (in.resType() != null || in.resId() != null) {
            p = resources.requireRecord(in.resType(), in.resId());
        }
        String ws = in.workspaceId() != null ? (String) workspaces.row(in.workspaceId()).get("id")
                : workspaces.idOfCode(p == null ? "general" : p.workspaceCode());
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO "DmsRequest" (id, "organizationId", title, note, "workspaceId", "requestedFromUserId",
                  "requestedById", "resType", "resId", "dueOn") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, org(), in.title().trim(), in.note(), ws, in.requestedFromUserId(), TenantContext.userId(),
                        p == null ? null : in.resType(), p == null ? null : in.resId(),
                        in.dueOn() == null ? null : java.sql.Date.valueOf(in.dueOn()))
                .update();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", id);
        payload.put("path", "/dms/requests");
        notifications.send(new SendInput(org(), in.requestedFromUserId(), "in_app", "dms",
                "Document requested: " + in.title().trim(),
                (in.dueOn() == null ? "" : "Due " + in.dueOn() + ". ") + (in.note() == null ? "" : in.note()),
                payload));
        outbox.publish(DomainEvents.DMS_REQUEST_CREATED, Map.of("organizationId", org(), "requestId", id,
                "requestedFromUserId", in.requestedFromUserId()));
        return row(id);
    }

    /** {@code scope}: {@code assigned} (to me, default), {@code sent} (by me), {@code all} (workspace managers). */
    @Transactional
    public List<Map<String, Object>> list(String scope, String status) {
        String s = scope == null ? "assigned" : scope;
        StringBuilder where = new StringBuilder(" WHERE r.\"organizationId\" = :org");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("org", org());
        switch (s) {
            case "assigned" -> {
                where.append(" AND r.\"requestedFromUserId\" = :me");
                p.put("me", TenantContext.userId());
            }
            case "sent" -> {
                where.append(" AND r.\"requestedById\" = :me");
                p.put("me", TenantContext.userId());
            }
            case "all" -> {
                if (!TenantContext.has(Permissions.DMS__WORKSPACE_MANAGE)) {
                    throw ApiException.forbidden("Missing required permission(s): " + Permissions.DMS__WORKSPACE_MANAGE);
                }
            }
            default -> throw ApiException.badRequest("scope must be one of: all, assigned, sent");
        }
        if (status != null) {
            where.append(" AND r.status = :st");
            p.put("st", status);
        }
        return SqlRows.list(jdbc.sql("""
                SELECT r.*, trim(coalesce(a."firstName", '') || ' ' || coalesce(a."lastName", '')) AS "requestedFromName",
                  trim(coalesce(b."firstName", '') || ' ' || coalesce(b."lastName", '')) AS "requestedByName",
                  w.name AS "workspaceName", (r.status = 'open' AND r."dueOn" < current_date) AS overdue
                FROM "DmsRequest" r LEFT JOIN "User" a ON a.id = r."requestedFromUserId"
                LEFT JOIN "User" b ON b.id = r."requestedById" LEFT JOIN "DmsWorkspace" w ON w.id = r."workspaceId"
                """ + where + " ORDER BY (r.status = 'open') DESC, r.\"dueOn\" NULLS LAST, r.\"createdAt\" DESC")
                .params(p));
    }

    @Transactional
    public Map<String, Object> fulfil(String id, List<FilePart> parts) {
        Map<String, Object> r = row(id);
        if (!"open".equals(r.get("status"))) {
            throw ApiException.badRequest("Document request is not open");
        }
        if (!java.util.Objects.equals(r.get("requestedFromUserId"), TenantContext.userId())
                && !TenantContext.has(Permissions.DMS__WORKSPACE_MANAGE)) {
            throw ApiException.forbidden("Only the requested user can fulfil this request");
        }
        if (parts == null || parts.isEmpty()) {
            throw ApiException.badRequest("Missing file");
        }
        String firstDoc = null;
        for (FilePart part : parts) {
            Map<String, Object> doc = documents.createInternal((String) r.get("workspaceId"), part,
                    (String) r.get("resType"), (String) r.get("resId"));
            if (firstDoc == null) {
                firstDoc = (String) doc.get("id");
            }
        }
        jdbc.sql("UPDATE \"DmsRequest\" SET status = 'fulfilled', \"fulfilledDocumentId\" = ?, \"fulfilledAt\" = now(),"
                + " \"updatedAt\" = now() WHERE id = ?").params(firstDoc, id).update();
        if (r.get("requestedById") != null) {
            notifications.send(new SendInput(org(), (String) r.get("requestedById"), "in_app", "dms",
                    "Document received: " + r.get("title"), "Your document request was fulfilled.",
                    Map.of("requestId", id, "documentId", firstDoc, "path", "/dms?document=" + firstDoc)));
        }
        outbox.publish(DomainEvents.DMS_REQUEST_FULFILLED, Map.of("organizationId", org(), "requestId", id,
                "documentId", firstDoc));
        return row(id);
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> r = row(id);
        if (!"open".equals(r.get("status"))) {
            throw ApiException.badRequest("Document request is not open");
        }
        if (!java.util.Objects.equals(r.get("requestedById"), TenantContext.userId())
                && !TenantContext.has(Permissions.DMS__WORKSPACE_MANAGE)) {
            throw ApiException.forbidden("Only the requester can cancel this request");
        }
        jdbc.sql("UPDATE \"DmsRequest\" SET status = 'cancelled', \"updatedAt\" = now() WHERE id = ?").param(id)
                .update();
        return row(id);
    }
}
