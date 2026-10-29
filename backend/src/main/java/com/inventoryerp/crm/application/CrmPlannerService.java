package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.NOW;
import static com.inventoryerp.crm.application.CrmDb.blank;
import static com.inventoryerp.crm.application.CrmDb.org;
import static com.inventoryerp.crm.application.CrmDb.str;
import static com.inventoryerp.crm.application.CrmDb.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Planned sales activities (ADR-S010): schedule a call / meeting / email / task on an opportunity or a customer for a
 * salesperson with a deadline and an optional reminder; "My activities" (overdue / today / upcoming); mark done with
 * an outcome and an optional follow-up. Reminders are delivered as in-app notifications by {@link CrmReminderJob}.
 */
@Service
public class CrmPlannerService {

    private static final Logger log = LoggerFactory.getLogger(CrmPlannerService.class);
    static final Set<String> TYPES = Set.of("call", "email", "meeting", "task", "note");

    private final CrmDb db;
    private final AuditService audit;
    private final NotificationsService notifications;

    public CrmPlannerService(CrmDb db, AuditService audit, NotificationsService notifications) {
        this.db = db;
        this.audit = audit;
        this.notifications = notifications;
    }

    private static final String SELECT = """
            SELECT a.id, a.type::text AS type, a.title, a.body, a.status::text AS status, a.outcome, a."dueAt",
                   a."remindAt", a.duration, a."completedAt", a."dealId", a."partnerId", a."assignedToId",
                   a."createdById", a."createdAt", d.name AS "dealName", d.kind AS "dealKind",
                   COALESCE(p.name, d."companyName", d."contactName") AS "partnerName",
                   COALESCE(NULLIF(trim(concat_ws(' ', u."firstName", u."lastName")), ''), u.email) AS "assignedToName"
              FROM "Activity" a
              LEFT JOIN "Deal" d ON d.id = a."dealId"
              LEFT JOIN "Partner" p ON p.id = COALESCE(a."partnerId", d."partnerId")
              LEFT JOIN "User" u ON u.id = a."assignedToId"
            """;

    /**
     * Planned activities. {@code scope}: mine (default) / all (needs view-all); {@code state}: overdue / today /
     * upcoming / open (default) / done.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> list(Map<String, String> p) {
        ZoneId zone = BusinessZone.get();
        LocalDate today = LocalDate.now(zone);
        Instant start = today.atStartOfDay(zone).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();
        StringBuilder where = new StringBuilder(" WHERE a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL AND"
                + " a.type::text NOT IN ('deal_stage_change', 'note')");
        List<Object> params = new ArrayList<>(List.of(org()));
        String scope = blank(p.get("scope")) ? "mine" : p.get("scope");
        if ("all".equals(scope) && TenantContext.has(Permissions.CRM__DEAL_VIEW_ALL)) {
            if (!blank(p.get("assignedToId"))) {
                where.append(" AND a.\"assignedToId\" = ?");
                params.add(p.get("assignedToId"));
            }
        } else {
            where.append(" AND COALESCE(a.\"assignedToId\", a.\"createdById\") = ?");
            params.add(user());
        }
        String state = blank(p.get("state")) ? "open" : p.get("state");
        switch (state) {
            case "done" -> where.append(" AND a.status::text IN ('done', 'cancelled')");
            case "overdue" -> {
                where.append(" AND a.status::text IN ('todo', 'in_progress') AND a.\"dueAt\" < ?");
                params.add(SqlRows.ts(start));
            }
            case "today" -> {
                where.append(" AND a.status::text IN ('todo', 'in_progress') AND a.\"dueAt\" >= ? AND a.\"dueAt\" < ?");
                params.add(SqlRows.ts(start));
                params.add(SqlRows.ts(end));
            }
            case "upcoming" -> {
                where.append(" AND a.status::text IN ('todo', 'in_progress') AND (a.\"dueAt\" >= ? OR a.\"dueAt\" IS"
                        + " NULL)");
                params.add(SqlRows.ts(end));
            }
            case "open" -> where.append(" AND a.status::text IN ('todo', 'in_progress')");
            default -> throw ApiException.badRequest("state must be one of: overdue, today, upcoming, open, done");
        }
        if (!blank(p.get("type"))) {
            where.append(" AND a.type::text = ?");
            params.add(p.get("type"));
        }
        if (!blank(p.get("dealId"))) {
            where.append(" AND a.\"dealId\" = ?");
            params.add(p.get("dealId"));
        }
        String order = "done".equals(state) ? " ORDER BY a.\"completedAt\" DESC NULLS LAST" : " ORDER BY a.\"dueAt\""
                + " NULLS LAST";
        List<Map<String, Object>> rows = db.rows(SELECT + where + order + " LIMIT 300", params);
        for (Map<String, Object> r : rows) {
            Instant due = r.get("dueAt") instanceof Instant i ? i : null;
            boolean open = "todo".equals(r.get("status")) || "in_progress".equals(r.get("status"));
            r.put("state", !open ? "done" : due == null ? "planned" : due.isBefore(start) ? "overdue"
                    : due.isBefore(end) ? "today" : "planned");
        }
        List<Object> mineParams = List.of(org(), user(), SqlRows.ts(start), SqlRows.ts(start), SqlRows.ts(end),
                SqlRows.ts(end));
        Map<String, Object> counts = db.row("SELECT count(*) FILTER (WHERE a.\"dueAt\" < ?) AS overdue, count(*) FILTER"
                + " (WHERE a.\"dueAt\" >= ? AND a.\"dueAt\" < ?) AS today, count(*) FILTER (WHERE a.\"dueAt\" >= ? OR"
                + " a.\"dueAt\" IS NULL) AS upcoming FROM \"Activity\" a WHERE a.\"organizationId\" = ? AND"
                + " a.\"deletedAt\" IS NULL AND a.type::text NOT IN ('deal_stage_change', 'note') AND a.status::text IN"
                + " ('todo', 'in_progress') AND COALESCE(a.\"assignedToId\", a.\"createdById\") = ?",
                mineParams.get(2), mineParams.get(3), mineParams.get(4), mineParams.get(5), mineParams.get(0),
                mineParams.get(1));
        return Obj.of("data", rows, "counts", counts);
    }

    /** Schedule an activity (or log a note when {@code type = note}). */
    @Transactional
    public Map<String, Object> schedule(Map<String, Object> in) {
        String type = str(in.get("type"));
        if (type == null || !TYPES.contains(type)) {
            throw ApiException.badRequest("type must be one of: call, email, meeting, task, note");
        }
        String title = str(in.get("title"));
        if (blank(title)) {
            throw ApiException.badRequest("title should not be empty");
        }
        String dealId = str(in.get("dealId"));
        String partnerId = str(in.get("partnerId"));
        if (!blank(dealId)) {
            Map<String, Object> deal = db.require("Deal", dealId, "Opportunity not found");
            if (blank(partnerId)) {
                partnerId = str(deal.get("partnerId"));
            }
        }
        if (!blank(partnerId) && !db.exists("Partner", partnerId)) {
            throw ApiException.notFound("Partner not found");
        }
        String assignee = blank(str(in.get("assignedToId"))) ? user() : str(in.get("assignedToId"));
        if (db.count("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ?", List.of(assignee, org()))
                == 0) {
            throw ApiException.notFound("User not found");
        }
        Instant due = CrmDb.instant(str(in.get("dueAt")));
        Instant remind = CrmDb.instant(str(in.get("remindAt")));
        boolean note = "note".equals(type);
        String id = CrmDb.newId();
        db.update("INSERT INTO \"Activity\" (id, \"organizationId\", type, title, body, \"dealId\", \"partnerId\","
                + " \"dueAt\", \"remindAt\", duration, status, completed, \"completedAt\", \"assignedToId\","
                + " \"createdById\", \"occurredAt\", \"updatedAt\") VALUES (?, ?, ?::\"ActivityType\", ?, ?, ?, ?, ?, ?, ?,"
                + " ?::\"TaskStatus\", ?, " + (note ? NOW : "NULL") + ", ?, ?, " + NOW + ", " + NOW + ")",
                id, org(), type, title.trim(), str(in.get("body")), blank(dealId) ? null : dealId,
                blank(partnerId) ? null : partnerId, due, note ? null : remind,
                in.get("duration") instanceof Number n ? n.intValue() : null, note ? "done" : "todo", note, assignee,
                user());
        touchDeal(dealId);
        return one(id);
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> in) {
        Map<String, Object> a = require(id);
        Map<String, Object> d = new LinkedHashMap<>();
        if (in.containsKey("title")) {
            if (blank(str(in.get("title")))) {
                throw ApiException.badRequest("title should not be empty");
            }
            d.put("title", str(in.get("title")).trim());
        }
        if (in.containsKey("body")) {
            d.put("body", str(in.get("body")));
        }
        if (in.containsKey("dueAt")) {
            d.put("dueAt", CrmDb.instant(str(in.get("dueAt"))));
            d.put("reminderSentAt", null);
        }
        if (in.containsKey("remindAt")) {
            d.put("remindAt", CrmDb.instant(str(in.get("remindAt"))));
            d.put("reminderSentAt", null);
        }
        if (in.containsKey("assignedToId")) {
            String u = str(in.get("assignedToId"));
            if (blank(u) || db.count("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ?",
                    List.of(u, org())) == 0) {
                throw ApiException.notFound("User not found");
            }
            d.put("assignedToId", u);
        }
        if (in.containsKey("duration")) {
            d.put("duration", in.get("duration") instanceof Number n ? n.intValue() : null);
        }
        db.patch("Activity", id, d, true);
        if (in.containsKey("type")) {
            String type = str(in.get("type"));
            if (type == null || !TYPES.contains(type) || "note".equals(type)) {
                throw ApiException.badRequest("type must be one of: call, email, meeting, task");
            }
            db.update("UPDATE \"Activity\" SET type = ?::\"ActivityType\" WHERE id = ? AND \"organizationId\" = ?", type,
                    id, org());
        }
        touchDeal(str(a.get("dealId")));
        return one(id);
    }

    /** Mark done with an outcome; {@code followUp} schedules the next activity in the same call. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> done(String id, Map<String, Object> in) {
        Map<String, Object> a = require(id);
        db.update("UPDATE \"Activity\" SET status = 'done', completed = true, \"completedAt\" = " + NOW + ", outcome = ?,"
                + " \"occurredAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?",
                str(in.get("outcome")), id, org());
        audit.record(AuditInput.of("Activity", id, "update", null, Obj.of("status", "done", "outcome",
                in.get("outcome"))));
        Map<String, Object> out = new LinkedHashMap<>(one(id));
        if (in.get("followUp") instanceof Map<?, ?> f) {
            Map<String, Object> next = new LinkedHashMap<>((Map<String, Object>) f);
            next.putIfAbsent("dealId", a.get("dealId"));
            next.putIfAbsent("partnerId", a.get("partnerId"));
            out.put("followUp", schedule(next));
        }
        touchDeal(str(a.get("dealId")));
        return out;
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> a = require(id);
        db.update("UPDATE \"Activity\" SET status = 'cancelled', \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                + " \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of("Activity", id, "cancel"));
        touchDeal(str(a.get("dealId")));
        return one(id);
    }

    /** Send due reminders of the current organization (called per organization by the job). */
    @Transactional
    public int sendDueReminders() {
        List<Map<String, Object>> due = db.rows("SELECT a.id, a.title, a.type::text AS type, a.\"dueAt\","
                + " a.\"dealId\", COALESCE(a.\"assignedToId\", a.\"createdById\") AS \"userId\", d.name AS \"dealName\""
                + " FROM \"Activity\" a LEFT JOIN \"Deal\" d ON d.id = a.\"dealId\" WHERE a.\"organizationId\" = ? AND"
                + " a.\"remindAt\" <= ? AND a.\"reminderSentAt\" IS NULL AND a.\"deletedAt\" IS NULL AND"
                + " a.status::text IN ('todo', 'in_progress') ORDER BY a.\"remindAt\" LIMIT 200 FOR UPDATE OF a SKIP"
                + " LOCKED", org(), SqlRows.ts(Instant.now()));
        int sent = 0;
        for (Map<String, Object> r : due) {
            db.update("UPDATE \"Activity\" SET \"reminderSentAt\" = " + NOW + " WHERE id = ?", r.get("id"));
            if (r.get("userId") == null) {
                continue;
            }
            try {
                notifications.send(new SendInput(org(), str(r.get("userId")), "in_app", "crm", "Reminder: "
                        + r.get("title"), r.get("dealName") == null ? "Activity due " + r.get("dueAt")
                        : r.get("dealName") + " — due " + r.get("dueAt"), Obj.of("kind", "crm.activity.reminder",
                        "activityId", r.get("id"), "dealId", r.get("dealId"), "link", r.get("dealId") == null
                                ? "/crm/activities" : "/crm/opportunities/" + r.get("dealId"))));
                sent++;
            } catch (RuntimeException e) {
                log.warn("CRM reminder {} not delivered: {}", r.get("id"), e.toString());
            }
        }
        return sent;
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> require(String id) {
        Map<String, Object> a = db.row("SELECT * FROM \"Activity\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL", id, org());
        if (a == null) {
            throw ApiException.notFound("Activity not found");
        }
        boolean mine = user() != null && (user().equals(a.get("assignedToId")) || user().equals(a.get("createdById")));
        if (!mine && !TenantContext.has(Permissions.CRM__DEAL_VIEW_ALL)) {
            throw ApiException.forbidden("You can only update your own activities");
        }
        return a;
    }

    private Map<String, Object> one(String id) {
        return db.row(SELECT + " WHERE a.id = ? AND a.\"organizationId\" = ?", id, org());
    }

    private void touchDeal(String dealId) {
        if (!blank(dealId)) {
            db.update("UPDATE \"Deal\" SET \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", dealId,
                    org());
        }
    }
}
