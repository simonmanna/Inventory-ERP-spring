package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.blank;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL for the CRM opportunity / lead filters shared by the pipeline, list, grouped table and reports. Produces a
 * {@code FROM … WHERE …} fragment over {@code "Deal" d} with the joins the filters and card columns need, plus the
 * bound parameters. Visibility: without {@code crm:deal:view_all} a user sees their own records, unassigned ones and
 * those of teams they lead.
 */
final class CrmDealQuery {

    /** Filter values (all optional; {@code null} / blank = not filtered). */
    record Filter(String kind, String status, String stageId, String ownerId, String teamId, String sourceId,
            String tagId, String partnerId, String lostReasonId, String q, Integer priority, String createdFrom,
            String createdTo, String closeFrom, String closeTo, Boolean mine, Boolean unassigned, String activityState,
            String city, String country) {

        static Filter of(Map<String, String> p) {
            return new Filter(p.get("kind"), p.get("status"), p.get("stageId"), p.get("ownerId"), p.get("teamId"),
                    p.get("sourceId"), p.get("tagId"), p.get("partnerId"), p.get("lostReasonId"), p.get("q"),
                    blank(p.get("priority")) ? null : Integer.valueOf(p.get("priority")), p.get("createdFrom"),
                    p.get("createdTo"), p.get("closeFrom"), p.get("closeTo"), "true".equals(p.get("mine")),
                    "true".equals(p.get("unassigned")), p.get("activityState"), p.get("city"), p.get("country"));
        }

        Filter withKind(String k) {
            return new Filter(k, status, stageId, ownerId, teamId, sourceId, tagId, partnerId, lostReasonId, q,
                    priority, createdFrom, createdTo, closeFrom, closeTo, mine, unassigned, activityState, city,
                    country);
        }
    }

    /** Card / row columns (needs {@link #FROM}). */
    static final String COLUMNS = """
            d.id, d.kind, d.name, d."partnerId", p.name AS "partnerName", d."contactId", d."contactName",
            d."companyName", d.email, d.phone, d.city, d.country, d."ownerId",
            COALESCE(NULLIF(trim(concat_ws(' ', u."firstName", u."lastName")), ''), u.email) AS "ownerName",
            d."teamId", d."stageId", s.name AS "stageName", d.stage::text AS "legacyStage", d.status, d.amount,
            d."currencyCode",
            COALESCE(d.probability, CASE d.status WHEN 'won' THEN 100 WHEN 'lost' THEN 0 ELSE s.probability END, 0)
                AS probability,
            d.probability AS "probabilityOverride", d.priority, d."tagIds", d."sourceId", d."lostReasonId",
            d."lostNote", d."expectedClose", d."createdAt", d."updatedAt", d."stageChangedAt", d."wonAt", d."lostAt",
            d."convertedAt", d."sequenceInStage", d.notes,
            na.id AS "nextActivityId", na.title AS "nextActivityTitle", na.type::text AS "nextActivityType",
            na."dueAt" AS "nextActivityDueAt",
            (SELECT count(*) FROM "SalesQuotation" cq WHERE cq."dealId" = d.id AND cq."deletedAt" IS NULL)
                AS "quotationCount",
            COALESCE(s."rottingDays", cs."rottingDays", 14) AS "rottingDays"
            """;

    static final String FROM = """
             FROM "Deal" d
             LEFT JOIN "Partner" p ON p.id = d."partnerId"
             LEFT JOIN "User" u ON u.id = d."ownerId"
             LEFT JOIN "CrmStage" s ON s.id = d."stageId"
             LEFT JOIN "CrmSettings" cs ON cs."organizationId" = d."organizationId"
             LEFT JOIN LATERAL (SELECT a.id, a.title, a.type, a."dueAt" FROM "Activity" a WHERE a."dealId" = d.id
                 AND a."deletedAt" IS NULL AND a.status::text IN ('todo', 'in_progress') AND a."dueAt" IS NOT NULL
                 AND a.type::text <> 'deal_stage_change' ORDER BY a."dueAt" LIMIT 1) na ON true
            """;

    final StringBuilder where = new StringBuilder();
    final List<Object> params = new ArrayList<>();
    final Instant todayStart;
    final Instant tomorrowStart;

    CrmDealQuery(Filter f) {
        ZoneId zone = BusinessZone.get();
        LocalDate today = LocalDate.now(zone);
        todayStart = today.atStartOfDay(zone).toInstant();
        tomorrowStart = today.plusDays(1).atStartOfDay(zone).toInstant();
        where.append(" WHERE d.\"organizationId\" = ? AND d.\"deletedAt\" IS NULL");
        params.add(TenantContext.organizationId());
        visibility();
        if (f == null) {
            return;
        }
        eq("d.kind", f.kind());
        if (!blank(f.status())) {
            List<String> st = List.of(f.status().split(","));
            for (String s : st) {
                if (!List.of("open", "won", "lost").contains(s)) {
                    throw ApiException.badRequest("status must be one of: open, won, lost");
                }
            }
            where.append(" AND d.status IN (").append(CrmDb.marks(st.size())).append(')');
            params.addAll(st);
        }
        eq("d.\"stageId\"", f.stageId());
        if ("none".equals(f.ownerId()) || Boolean.TRUE.equals(f.unassigned())) {
            where.append(" AND d.\"ownerId\" IS NULL");
        } else {
            eq("d.\"ownerId\"", f.ownerId());
        }
        if (Boolean.TRUE.equals(f.mine())) {
            where.append(" AND d.\"ownerId\" = ?");
            params.add(TenantContext.userId());
        }
        eq("d.\"teamId\"", f.teamId());
        eq("d.\"sourceId\"", f.sourceId());
        eq("d.\"partnerId\"", f.partnerId());
        eq("d.\"lostReasonId\"", f.lostReasonId());
        if (!blank(f.tagId())) {
            where.append(" AND ? = ANY(d.\"tagIds\")");
            params.add(f.tagId());
        }
        if (f.priority() != null) {
            where.append(" AND d.priority >= ?");
            params.add(f.priority());
        }
        if (!blank(f.city())) {
            where.append(" AND d.city ILIKE ?");
            params.add(f.city());
        }
        if (!blank(f.country())) {
            where.append(" AND d.country ILIKE ?");
            params.add(f.country());
        }
        if (!blank(f.q())) {
            String like = "%" + BaseCrudService.escapeLike(f.q().trim()) + "%";
            where.append(" AND (d.name ILIKE ? OR p.name ILIKE ? OR d.\"contactName\" ILIKE ? OR d.\"companyName\""
                    + " ILIKE ? OR d.email ILIKE ? OR d.phone ILIKE ?)");
            for (int i = 0; i < 6; i++) {
                params.add(like);
            }
        }
        range("d.\"createdAt\"", f.createdFrom(), f.createdTo());
        range("d.\"expectedClose\"", f.closeFrom(), f.closeTo());
        if (!blank(f.activityState())) {
            switch (f.activityState()) {
                case "overdue" -> {
                    where.append(" AND na.\"dueAt\" < ?");
                    params.add(SqlRows.ts(todayStart));
                }
                case "today" -> {
                    where.append(" AND na.\"dueAt\" >= ? AND na.\"dueAt\" < ?");
                    params.add(SqlRows.ts(todayStart));
                    params.add(SqlRows.ts(tomorrowStart));
                }
                case "planned" -> {
                    where.append(" AND na.\"dueAt\" >= ?");
                    params.add(SqlRows.ts(tomorrowStart));
                }
                case "none" -> where.append(" AND na.id IS NULL");
                default -> throw ApiException.badRequest("activityState must be one of: overdue, today, planned, none");
            }
        }
    }

    private void visibility() {
        if (TenantContext.has(Permissions.CRM__DEAL_VIEW_ALL)) {
            return;
        }
        String me = TenantContext.userId();
        where.append(" AND (d.\"ownerId\" = ? OR d.\"ownerId\" IS NULL OR d.\"teamId\" IN (SELECT t.id FROM"
                + " \"CrmTeam\" t WHERE t.\"leaderId\" = ?))");
        params.add(me);
        params.add(me);
    }

    private void eq(String col, String v) {
        if (!blank(v)) {
            where.append(" AND ").append(col).append(" = ?");
            params.add(v);
        }
    }

    private void range(String col, String from, String to) {
        if (!blank(from)) {
            where.append(" AND ").append(col).append(" >= ?");
            params.add(SqlRows.ts(CrmDb.instant(from)));
        }
        if (!blank(to)) {
            Instant end = to.length() == 10 ? CrmDb.date(to).plusDays(1).atStartOfDay(BusinessZone.get()).toInstant()
                    : CrmDb.instant(to);
            where.append(" AND ").append(col).append(" < ?");
            params.add(SqlRows.ts(end));
        }
    }

    /** Activity state of a card from its next planned activity due date. */
    String activityState(Object nextDue) {
        if (!(nextDue instanceof Instant due)) {
            return "none";
        }
        if (due.isBefore(todayStart)) {
            return "overdue";
        }
        return due.isBefore(tomorrowStart) ? "today" : "planned";
    }

    /** Add derived card fields: activity state, days in stage, rotting, weighted amount, next activity object. */
    Map<String, Object> decorate(Map<String, Object> r) {
        r.put("activityState", activityState(r.get("nextActivityDueAt")));
        Instant changed = r.get("stageChangedAt") instanceof Instant i ? i : null;
        long days = changed == null ? 0 : java.time.Duration.between(changed, Instant.now()).toDays();
        r.put("daysInStage", days);
        int rot = r.get("rottingDays") instanceof Number n ? n.intValue() : 14;
        r.put("rotting", "open".equals(r.get("status")) && "opportunity".equals(r.get("kind")) && days >= rot);
        double amount = r.get("amount") instanceof Number n ? n.doubleValue() : 0;
        double prob = r.get("probability") instanceof Number n ? n.doubleValue() : 0;
        r.put("weightedAmount", com.inventoryerp.kernel.common.JsNumbers.js(Math.round(amount * prob) / 100.0));
        if (r.get("nextActivityId") != null) {
            r.put("nextActivity", com.inventoryerp.kernel.common.Obj.of("id", r.get("nextActivityId"), "title",
                    r.get("nextActivityTitle"), "type", r.get("nextActivityType"), "dueAt", r.get("nextActivityDueAt")));
        } else {
            r.put("nextActivity", null);
        }
        r.remove("nextActivityId");
        r.remove("nextActivityTitle");
        r.remove("nextActivityType");
        r.remove("nextActivityDueAt");
        return r;
    }
}
