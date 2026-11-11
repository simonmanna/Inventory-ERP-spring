package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.NOW;
import static com.inventoryerp.crm.application.CrmDb.blank;
import static com.inventoryerp.crm.application.CrmDb.dec;
import static com.inventoryerp.crm.application.CrmDb.org;
import static com.inventoryerp.crm.application.CrmDb.str;
import static com.inventoryerp.crm.application.CrmDb.strings;
import static com.inventoryerp.crm.application.CrmDb.user;

import com.inventoryerp.core.application.PartnerService;
import com.inventoryerp.crm.application.CrmDealQuery.Filter;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leads and opportunities on the configurable pipeline (ADR-S010): list / kanban / grouped views over shared filters,
 * create (with automatic assignment), edit, drag between stages (stage history + legacy stage sync), won / lost /
 * reopen, lead → opportunity conversion (optionally creating the customer), and the merged timeline.
 */
@Service
public class CrmPipelineService {

    static final Set<String> SORTS = Set.of("createdAt", "updatedAt", "amount", "expectedClose", "name", "priority",
            "stageChangedAt");
    static final Map<String, String[]> GROUPS = new LinkedHashMap<>();

    static {
        // groupBy key → {key expression, label expression, extra join, order expression}
        GROUPS.put("stage", new String[] {"d.\"stageId\"", "s.name", "", "min(s.sequence)"});
        GROUPS.put("owner", new String[] {"d.\"ownerId\"", "COALESCE(NULLIF(trim(concat_ws(' ', u.\"firstName\","
                + " u.\"lastName\")), ''), u.email)", "", "2"});
        GROUPS.put("team", new String[] {"d.\"teamId\"", "gt.name", " LEFT JOIN \"CrmTeam\" gt ON gt.id = d.\"teamId\"",
                "2"});
        GROUPS.put("source", new String[] {"d.\"sourceId\"", "gs.name",
                " LEFT JOIN \"CrmSource\" gs ON gs.id = d.\"sourceId\"", "2"});
        GROUPS.put("lostReason", new String[] {"d.\"lostReasonId\"", "gl.name",
                " LEFT JOIN \"CrmLostReason\" gl ON gl.id = d.\"lostReasonId\"", "2"});
        GROUPS.put("partner", new String[] {"d.\"partnerId\"", "COALESCE(p.name, d.\"companyName\")", "", "2"});
        GROUPS.put("tag", new String[] {"tg.id", "gtag.name", " LEFT JOIN LATERAL unnest(d.\"tagIds\") tg(id) ON true"
                + " LEFT JOIN \"CrmTag\" gtag ON gtag.id = tg.id", "2"});
        GROUPS.put("city", new String[] {"d.city", "d.city", "", "2"});
        GROUPS.put("country", new String[] {"d.country", "d.country", "", "2"});
        GROUPS.put("kind", new String[] {"d.kind", "d.kind", "", "2"});
        GROUPS.put("status", new String[] {"d.status", "d.status", "", "2"});
        GROUPS.put("priority", new String[] {"d.priority::text", "d.priority::text", "", "1 DESC"});
        GROUPS.put("createdMonth", new String[] {"to_char(d.\"createdAt\", 'YYYY-MM')",
                "to_char(d.\"createdAt\", 'YYYY-MM')", "", "1"});
        GROUPS.put("closeMonth", new String[] {"to_char(d.\"expectedClose\", 'YYYY-MM')",
                "to_char(d.\"expectedClose\", 'YYYY-MM')", "", "1"});
        GROUPS.put("wonMonth", new String[] {"to_char(d.\"wonAt\", 'YYYY-MM')", "to_char(d.\"wonAt\", 'YYYY-MM')", "",
                "1"});
    }

    private final CrmDb db;
    private final CrmConfigService config;
    private final AuditService audit;
    private final EventBus events;
    private final PartnerService partners;

    public CrmPipelineService(CrmDb db, CrmConfigService config, AuditService audit, EventBus events,
            PartnerService partners) {
        this.db = db;
        this.config = config;
        this.audit = audit;
        this.events = events;
        this.partners = partners;
    }

    // ------------------------------------------------------------------ queries

    /** Paginated list {@code {data, meta, totals}}. */
    @Transactional
    public Map<String, Object> list(Map<String, String> params) {
        config.ensureDefaults();
        CrmDealQuery q = new CrmDealQuery(Filter.of(params));
        int page = Math.max(1, parseInt(params.get("page"), 1));
        int pageSize = Math.min(200, Math.max(1, parseInt(params.get("pageSize"), 50)));
        String sort = params.get("sort") != null && SORTS.contains(params.get("sort")) ? params.get("sort") : "createdAt";
        String dir = "asc".equalsIgnoreCase(params.get("dir")) ? "ASC" : "DESC";
        List<Map<String, Object>> rows = db.rows("SELECT " + CrmDealQuery.COLUMNS + CrmDealQuery.FROM + q.where
                + " ORDER BY d.\"" + sort + "\" " + dir + " NULLS LAST, d.id OFFSET " + (long) (page - 1) * pageSize
                + " LIMIT " + pageSize, q.params);
        rows.forEach(q::decorate);
        Map<String, Object> totals = db.row("SELECT count(*) AS count, COALESCE(sum(d.amount), 0) AS amount,"
                + " COALESCE(sum(d.amount * COALESCE(d.probability, CASE d.status WHEN 'won' THEN 100 WHEN 'lost' THEN 0"
                + " ELSE s.probability END, 0) / 100), 0) AS weighted" + CrmDealQuery.FROM + q.where,
                q.params.toArray());
        long total = ((Number) totals.get("count")).longValue();
        return Obj.of("data", rows, "meta", Obj.of("page", page, "pageSize", pageSize, "total", total, "totalPages",
                Math.max(1, (long) Math.ceil((double) total / pageSize))), "totals", totals);
    }

    /** Kanban: one column per active stage with count, totals, activity-state bar and the first cards. */
    @Transactional
    public Map<String, Object> kanban(Map<String, String> params) {
        config.ensureDefaults();
        Map<String, String> p = new HashMap<>(params);
        p.putIfAbsent("kind", "opportunity");
        if (blank(p.get("status"))) {
            p.put("status", "open,won");
        }
        CrmDealQuery q = new CrmDealQuery(Filter.of(p));
        int limit = Math.min(200, Math.max(5, parseInt(params.get("limit"), 60)));
        List<Map<String, Object>> stages = db.rows("SELECT * FROM \"CrmStage\" WHERE \"organizationId\" = ? AND"
                + " \"isActive\" AND (?::text IS NULL OR \"teamId\" IS NULL OR \"teamId\" = ?) ORDER BY sequence", org(),
                p.get("teamId"), p.get("teamId"));
        List<Object> aggParams = new ArrayList<>(q.params);
        aggParams.add(SqlRows.ts(q.todayStart));
        aggParams.add(SqlRows.ts(q.todayStart));
        aggParams.add(SqlRows.ts(q.tomorrowStart));
        aggParams.add(SqlRows.ts(q.tomorrowStart));
        Map<Object, Map<String, Object>> agg = new HashMap<>();
        for (Map<String, Object> a : db.rows("SELECT d.\"stageId\", count(*) AS count, COALESCE(sum(d.amount), 0) AS"
                + " amount, count(*) FILTER (WHERE na.\"dueAt\" < ?) AS overdue, count(*) FILTER (WHERE na.\"dueAt\""
                + " >= ? AND na.\"dueAt\" < ?) AS today, count(*) FILTER (WHERE na.\"dueAt\" >= ?) AS planned,"
                + " count(*) FILTER (WHERE na.id IS NULL) AS none" + CrmDealQuery.FROM + q.where.toString()
                .replace(" WHERE d.", " WHERE d.") + " GROUP BY d.\"stageId\"", reorder(aggParams, q.params.size()))) {
            agg.put(a.get("stageId"), a);
        }
        List<Map<String, Object>> cards = db.rows("SELECT * FROM (SELECT " + CrmDealQuery.COLUMNS + ", row_number()"
                + " OVER (PARTITION BY d.\"stageId\" ORDER BY d.\"sequenceInStage\", d.priority DESC, d.\"updatedAt\""
                + " DESC) AS rn" + CrmDealQuery.FROM + q.where + ") x WHERE rn <= " + limit, q.params);
        Map<Object, List<Map<String, Object>>> byStage = new HashMap<>();
        for (Map<String, Object> c : cards) {
            c.remove("rn");
            byStage.computeIfAbsent(c.get("stageId"), k -> new ArrayList<>()).add(q.decorate(c));
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        for (Map<String, Object> s : stages) {
            Map<String, Object> a = agg.getOrDefault(s.get("id"), Map.of());
            columns.add(Obj.of("stage", s, "count", a.getOrDefault("count", 0), "amount", a.getOrDefault("amount", 0),
                    "activity", Obj.of("overdue", a.getOrDefault("overdue", 0), "today", a.getOrDefault("today", 0),
                            "planned", a.getOrDefault("planned", 0), "none", a.getOrDefault("none", 0)),
                    "items", byStage.getOrDefault(s.get("id"), List.of())));
        }
        List<Map<String, Object>> orphans = byStage.getOrDefault(null, List.of());
        return Obj.of("columns", columns, "unstaged", orphans);
    }

    /** The FILTER params go before the WHERE params in the aggregate statement. */
    private static List<Object> reorder(List<Object> all, int whereCount) {
        List<Object> out = new ArrayList<>(all.subList(whereCount, all.size()));
        out.addAll(all.subList(0, whereCount));
        return out;
    }

    /** Grouped table (Odoo "group by"): {@code [{key, label, count, amount, weighted}]}. */
    @Transactional
    public List<Map<String, Object>> grouped(String groupBy, Map<String, String> params) {
        config.ensureDefaults();
        String[] g = GROUPS.get(groupBy == null ? "stage" : groupBy);
        if (g == null) {
            throw ApiException.badRequest("groupBy must be one of: " + String.join(", ", GROUPS.keySet()));
        }
        CrmDealQuery q = new CrmDealQuery(Filter.of(params));
        String from = CrmDealQuery.FROM + g[2];
        return db.rows("SELECT " + g[0] + " AS key, " + g[1] + " AS label, count(DISTINCT d.id) AS count,"
                + " COALESCE(sum(d.amount), 0) AS amount, COALESCE(sum(d.amount * COALESCE(d.probability, CASE d.status"
                + " WHEN 'won' THEN 100 WHEN 'lost' THEN 0 ELSE s.probability END, 0) / 100), 0) AS weighted" + from
                + q.where + " GROUP BY 1, 2 ORDER BY " + g[3] + " NULLS LAST", q.params);
    }

    /** Full record: card fields, partner + contacts, stage, quotations, planned activities. */
    @Transactional
    public Map<String, Object> get(String id) {
        Map<String, Object> r = visible(id);
        Map<String, Object> partner = r.get("partnerId") == null ? null : db.row("SELECT id, code, name, email, phone,"
                + " \"creditLimit\", \"creditHold\", \"isCustomer\" FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ?", r.get("partnerId"), org());
        if (partner != null) {
            partner.put("contacts", db.rows("SELECT id, \"firstName\", \"lastName\", position, email, phone,"
                    + " \"isPrimary\" FROM \"Contact\" WHERE \"partnerId\" = ? AND \"deletedAt\" IS NULL ORDER BY"
                    + " \"isPrimary\" DESC, \"firstName\"", partner.get("id")));
        }
        r.put("partner", partner);
        r.put("quotations", db.rows("SELECT id, number, status, total, \"currencyCode\", \"issueDate\", \"validUntil\""
                + " FROM \"SalesQuotation\" WHERE \"dealId\" = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " ORDER BY \"createdAt\" DESC", id, org()));
        r.put("plannedActivities", db.rows("SELECT a.*, COALESCE(NULLIF(trim(concat_ws(' ', u.\"firstName\","
                + " u.\"lastName\")), ''), u.email) AS \"assignedToName\" FROM \"Activity\" a LEFT JOIN \"User\" u ON"
                + " u.id = a.\"assignedToId\" WHERE a.\"dealId\" = ? AND a.\"organizationId\" = ? AND a.\"deletedAt\" IS"
                + " NULL AND a.status::text IN ('todo', 'in_progress') AND a.type::text NOT IN ('deal_stage_change',"
                + " 'note') ORDER BY a.\"dueAt\" NULLS LAST", id, org()));
        r.put("stageDurations", db.rows("SELECT \"fromStageId\" AS \"stageId\", sum(\"secondsInStage\") AS seconds FROM"
                + " \"CrmStageHistory\" WHERE \"dealId\" = ? GROUP BY 1", id));
        return r;
    }

    /** Merged chatter: logged activities, stage changes and audited field changes, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> timeline(String id) {
        visible(id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : db.rows("SELECT a.id, a.type::text AS type, a.title, a.body, a.status::text AS"
                + " status, a.outcome, a.\"dueAt\", a.\"completedAt\", a.\"occurredAt\" AS at, a.\"createdById\","
                + " COALESCE(NULLIF(trim(concat_ws(' ', u.\"firstName\", u.\"lastName\")), ''), u.email) AS \"userName\""
                + " FROM \"Activity\" a LEFT JOIN \"User\" u ON u.id = a.\"createdById\" WHERE a.\"dealId\" = ? AND"
                + " a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL AND (a.status::text IN ('done', 'cancelled')"
                + " OR a.type::text IN ('note', 'deal_stage_change') OR a.\"dueAt\" IS NULL)", id, org())) {
            a.put("source", "activity");
            out.add(a);
        }
        for (Map<String, Object> h : db.rows("SELECT h.id, h.\"changedAt\" AS at, h.\"fromStatus\", h.\"toStatus\","
                + " fs.name AS \"fromStage\", ts.name AS \"toStage\", h.\"secondsInStage\","
                + " COALESCE(NULLIF(trim(concat_ws(' ', u.\"firstName\", u.\"lastName\")), ''), u.email) AS \"userName\""
                + " FROM \"CrmStageHistory\" h LEFT JOIN \"CrmStage\" fs ON fs.id = h.\"fromStageId\" LEFT JOIN"
                + " \"CrmStage\" ts ON ts.id = h.\"toStageId\" LEFT JOIN \"User\" u ON u.id = h.\"changedById\" WHERE"
                + " h.\"dealId\" = ? AND h.\"organizationId\" = ?", id, org())) {
            h.put("source", "stage");
            out.add(h);
        }
        for (Map<String, Object> l : db.rows("SELECT l.id, l.action::text AS action, l.\"newValues\", l.\"createdAt\" AS"
                + " at, COALESCE(NULLIF(trim(concat_ws(' ', u.\"firstName\", u.\"lastName\")), ''), u.email) AS"
                + " \"userName\" FROM \"AuditLog\" l LEFT JOIN \"User\" u ON u.id = l.\"actorId\" WHERE l.\"organizationId\""
                + " = ? AND l.entity = 'Deal' AND l.\"entityId\" = ? ORDER BY l.\"createdAt\" DESC LIMIT 100", org(), id)) {
            l.put("source", "audit");
            out.add(l);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> m.get("at") instanceof Instant i ? i : Instant.EPOCH)
                .reversed());
        return out;
    }

    // ------------------------------------------------------------------ create / update

    /** Create a lead or an opportunity. */
    @Transactional
    public Map<String, Object> create(Map<String, Object> in) {
        config.ensureDefaults();
        String kind = blank(str(in.get("kind"))) ? "opportunity" : str(in.get("kind"));
        if (!Set.of("lead", "opportunity").contains(kind)) {
            throw ApiException.badRequest("kind must be one of: lead, opportunity");
        }
        String name = str(in.get("name"));
        if (blank(name)) {
            throw ApiException.badRequest("name should not be empty");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name.trim());
        d.put("kind", kind);
        d.putAll(fields(in));
        String partnerId = str(d.get("partnerId"));
        if (partnerId != null && d.get("contactName") == null) {
            Map<String, Object> contact = primaryContact(partnerId);
            if (contact != null) {
                d.putIfAbsent("contactId", contact.get("id"));
                d.put("contactName", contact.get("name"));
                d.putIfAbsent("email", contact.get("email"));
                d.putIfAbsent("phone", contact.get("phone"));
            }
        }
        String owner = in.containsKey("ownerId") ? str(in.get("ownerId")) : null;
        if (!in.containsKey("ownerId")) {
            owner = autoAssign();
            if (owner == null) {
                owner = user();
            }
        }
        d.put("ownerId", blank(owner) ? null : owner);
        if (d.get("teamId") == null && owner != null) {
            d.put("teamId", teamOf(owner));
        }
        Map<String, Object> stage;
        if (d.get("stageId") != null) {
            stage = config.stage(str(d.get("stageId")));
        } else {
            stage = config.firstStage(str(d.get("teamId")));
            d.put("stageId", stage.get("id"));
        }
        if (d.get("currencyCode") == null) {
            d.put("currencyCode", orgCurrency());
        }
        d.putIfAbsent("amount", BigDecimal.ZERO);
        d.put("createdById", user());
        d.put("updatedAt", Instant.now());
        d.put("stageChangedAt", Instant.now());
        Map<String, Object> row = db.insert("Deal", d);
        String id = str(row.get("id"));
        syncLegacyStage(id, "lead".equals(kind) ? "lead" : str(stage.get("legacyStage")));
        history(id, null, str(stage.get("id")), null, "open", null);
        audit.record(AuditInput.of("Deal", id, "create", null, Obj.of("kind", kind, "name", name, "stageId",
                stage.get("id"), "amount", d.get("amount"))));
        events.publish("crm.deal.created", Obj.of("organizationId", org(), "dealId", id, "name", name, "stage",
                "lead".equals(kind) ? "lead" : stage.get("legacyStage")));
        return get(id);
    }

    /** Partial update of the editable fields. */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> in) {
        Map<String, Object> before = visible(id);
        Map<String, Object> d = fields(in);
        if (in.containsKey("name")) {
            if (blank(str(in.get("name")))) {
                throw ApiException.badRequest("name should not be empty");
            }
            d.put("name", str(in.get("name")).trim());
        }
        if (in.containsKey("ownerId")) {
            String owner = str(in.get("ownerId"));
            d.put("ownerId", blank(owner) ? null : config.requireUser(owner));
        }
        Object newStage = d.remove("stageId");
        if (!d.isEmpty()) {
            db.patch("Deal", id, d, true);
            Map<String, Object> changed = new LinkedHashMap<>();
            d.forEach((k, v) -> {
                if (!String.valueOf(v).equals(String.valueOf(before.get(k)))) {
                    changed.put(k, v instanceof Instant || v instanceof java.time.LocalDate ? v.toString() : v);
                }
            });
            if (!changed.isEmpty()) {
                audit.record(AuditInput.of("Deal", id, "update", null, changed));
            }
        }
        if (newStage != null && !newStage.equals(before.get("stageId"))) {
            return move(id, Obj.of("stageId", newStage));
        }
        return get(id);
    }

    /** Drag & drop: move to {@code stageId} (optionally at {@code index} among the stage's cards). */
    @Transactional
    public Map<String, Object> move(String id, Map<String, Object> in) {
        Map<String, Object> deal = lock(id);
        String stageId = str(in.get("stageId"));
        Map<String, Object> stage = db.require("CrmStage", stageId, "Stage not found");
        if ("lead".equals(deal.get("kind"))) {
            throw ApiException.badRequest("Convert the lead to an opportunity before moving it on the pipeline");
        }
        if (in.get("index") instanceof Number idx) {
            reorderInStage(id, stageId, idx.intValue());
        }
        if (stageId.equals(deal.get("stageId"))) {
            return get(id);
        }
        if ("lost".equals(deal.get("status"))) {
            throw ApiException.badRequest("Restore the lost opportunity before moving it");
        }
        boolean toWon = Boolean.TRUE.equals(stage.get("isWon"));
        if (toWon) {
            return markWon(id, stageId);
        }
        String fromStatus = str(deal.get("status"));
        db.jdbc().sql("UPDATE \"Deal\" SET \"stageId\" = ?, status = 'open', \"wonAt\" = NULL, probability = NULL,"
                + " \"stageChangedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" ="
                + " ?").params(stageId, id, org()).update();
        syncLegacyStage(id, str(stage.get("legacyStage")));
        history(id, str(deal.get("stageId")), stageId, fromStatus, "open", deal.get("stageChangedAt"));
        stageActivity(deal, stageName(str(deal.get("stageId"))), str(stage.get("name")));
        events.publish("crm.deal.stage_changed", Obj.of("organizationId", org(), "dealId", id, "fromStage",
                deal.get("stage"), "toStage", stage.get("legacyStage"), "toStageId", stageId));
        return get(id);
    }

    /** Mark won: moves to the won stage (if configured), the partner becomes a customer. */
    @Transactional
    public Map<String, Object> won(String id) {
        Map<String, Object> deal = lock(id);
        if ("won".equals(deal.get("status"))) {
            return get(id);
        }
        Map<String, Object> wonStage = config.wonStage();
        return markWon(id, wonStage == null ? str(deal.get("stageId")) : str(wonStage.get("id")));
    }

    private Map<String, Object> markWon(String id, String stageId) {
        Map<String, Object> deal = lock(id);
        if ("lead".equals(deal.get("kind"))) {
            throw ApiException.badRequest("Convert the lead to an opportunity before marking it won");
        }
        db.jdbc().sql("UPDATE \"Deal\" SET \"stageId\" = ?, status = 'won', \"wonAt\" = " + NOW + ", \"lostAt\" = NULL,"
                + " \"lostReasonId\" = NULL, probability = NULL, \"stageChangedAt\" = " + NOW + ", \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ?").params(stageId, id, org()).update();
        syncLegacyStage(id, "won");
        if (deal.get("partnerId") != null) {
            db.update("UPDATE \"Partner\" SET \"isCustomer\" = true, \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                    + " \"organizationId\" = ? AND \"isCustomer\" = false", deal.get("partnerId"), org());
        }
        history(id, str(deal.get("stageId")), stageId, str(deal.get("status")), "won", deal.get("stageChangedAt"));
        stageActivity(deal, stageName(str(deal.get("stageId"))), "Won");
        audit.record(AuditInput.of("Deal", id, "update", Obj.of("status", deal.get("status")), Obj.of("status", "won")));
        events.publish(DomainEvents.CRM_DEAL_WON, Obj.of("organizationId", org(), "dealId", id, "amount",
                deal.get("amount")));
        events.publish("crm.deal.stage_changed", Obj.of("organizationId", org(), "dealId", id, "fromStage",
                deal.get("stage"), "toStage", "won", "toStageId", stageId));
        return get(id);
    }

    /** Mark lost with an optional reason and note. */
    @Transactional
    public Map<String, Object> lost(String id, Map<String, Object> in) {
        Map<String, Object> deal = lock(id);
        String reason = str(in.get("lostReasonId"));
        if (!blank(reason) && !db.exists("CrmLostReason", reason)) {
            throw ApiException.notFound("Lost reason not found");
        }
        if ("won".equals(deal.get("status"))) {
            throw ApiException.badRequest("A won opportunity cannot be marked lost; reopen it first");
        }
        db.jdbc().sql("UPDATE \"Deal\" SET status = 'lost', \"lostAt\" = " + NOW + ", \"lostReasonId\" = ?,"
                + " \"lostNote\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(blank(reason) ? null : reason, str(in.get("lostNote")), id, org()).update();
        syncLegacyStage(id, "lost");
        history(id, str(deal.get("stageId")), str(deal.get("stageId")), str(deal.get("status")), "lost", null);
        stageActivity(deal, stageName(str(deal.get("stageId"))), "Lost");
        audit.record(AuditInput.of("Deal", id, "update", Obj.of("status", deal.get("status")), Obj.of("status", "lost",
                "lostReasonId", reason, "lostNote", in.get("lostNote"))));
        events.publish(DomainEvents.CRM_DEAL_LOST, Obj.of("organizationId", org(), "dealId", id, "lostReasonId",
                reason));
        return get(id);
    }

    /** Reopen a won / lost record back to an open stage. */
    @Transactional
    public Map<String, Object> reopen(String id) {
        Map<String, Object> deal = lock(id);
        if ("open".equals(deal.get("status"))) {
            return get(id);
        }
        String stageId = str(deal.get("stageId"));
        Map<String, Object> stage = stageId == null ? null : config.stage(stageId);
        if (stage == null || Boolean.TRUE.equals(stage.get("isWon"))) {
            Map<String, Object> prev = db.row("SELECT h.\"fromStageId\" FROM \"CrmStageHistory\" h JOIN \"CrmStage\" s"
                    + " ON s.id = h.\"fromStageId\" WHERE h.\"dealId\" = ? AND NOT s.\"isWon\" AND s.\"isActive\" ORDER"
                    + " BY h.\"changedAt\" DESC LIMIT 1", id);
            stage = prev != null ? config.stage(str(prev.get("fromStageId"))) : config.firstStage(str(deal.get("teamId")));
        }
        db.jdbc().sql("UPDATE \"Deal\" SET status = 'open', \"stageId\" = ?, \"wonAt\" = NULL, \"lostAt\" = NULL,"
                + " \"lostReasonId\" = NULL, \"lostNote\" = NULL, \"stageChangedAt\" = " + NOW + ", \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ?").params(stage.get("id"), id, org()).update();
        syncLegacyStage(id, "lead".equals(deal.get("kind")) ? "lead" : str(stage.get("legacyStage")));
        history(id, stageId, str(stage.get("id")), str(deal.get("status")), "open", null);
        audit.record(AuditInput.of("Deal", id, "restore", Obj.of("status", deal.get("status")), Obj.of("status",
                "open")));
        return get(id);
    }

    /** Lead → opportunity: link an existing customer or create one, then drop it on the pipeline. */
    @Transactional
    public Map<String, Object> convert(String id, Map<String, Object> in) {
        Map<String, Object> deal = lock(id);
        if (!"lead".equals(deal.get("kind"))) {
            throw ApiException.badRequest("Only leads can be converted");
        }
        String partnerId = str(in.get("partnerId"));
        if (blank(partnerId)) {
            partnerId = str(deal.get("partnerId"));
        }
        if (blank(partnerId) && Boolean.TRUE.equals(in.get("createCustomer"))) {
            String customerName = !blank(str(in.get("customerName"))) ? str(in.get("customerName"))
                    : !blank(str(deal.get("companyName"))) ? str(deal.get("companyName"))
                    : !blank(str(deal.get("contactName"))) ? str(deal.get("contactName")) : str(deal.get("name"));
            boolean company = !blank(str(deal.get("companyName")));
            List<Map<String, Object>> contacts = new ArrayList<>();
            if (company && !blank(str(deal.get("contactName")))) {
                String[] parts = str(deal.get("contactName")).trim().split("\\s+", 2);
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("firstName", parts[0]);
                c.put("lastName", parts.length > 1 ? parts[1] : null);
                c.put("email", deal.get("email"));
                c.put("phone", deal.get("phone"));
                c.put("isPrimary", true);
                contacts.add(c);
            }
            Map<String, Object> created = partners.create(new NewCustomer(customerName, company, true,
                    str(deal.get("email")), str(deal.get("phone"))), contacts, List.of());
            partnerId = str(created.get("id"));
        } else if (!blank(partnerId) && !db.exists("Partner", partnerId)) {
            throw ApiException.notFound("Partner not found");
        }
        Map<String, Object> stage = !blank(str(in.get("stageId"))) ? db.require("CrmStage", str(in.get("stageId")),
                "Stage not found") : config.stage(str(deal.get("stageId")));
        if (stage == null) {
            stage = config.firstStage(str(deal.get("teamId")));
        }
        String owner = in.containsKey("ownerId") ? str(in.get("ownerId")) : str(deal.get("ownerId"));
        if (!blank(owner)) {
            config.requireUser(owner);
        }
        db.jdbc().sql("UPDATE \"Deal\" SET kind = 'opportunity', \"partnerId\" = ?, \"ownerId\" = ?, \"stageId\" = ?,"
                + " \"convertedAt\" = " + NOW + ", \"stageChangedAt\" = " + NOW + ", \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ?")
                .params(blank(partnerId) ? null : partnerId, blank(owner) ? null : owner, stage.get("id"), id, org())
                .update();
        syncLegacyStage(id, str(stage.get("legacyStage")));
        history(id, str(deal.get("stageId")), str(stage.get("id")), "open", "open", deal.get("stageChangedAt"));
        audit.record(AuditInput.of("Deal", id, "update", Obj.of("kind", "lead"), Obj.of("kind", "opportunity",
                "partnerId", partnerId)));
        events.publish(DomainEvents.CRM_LEAD_CONVERTED, Obj.of("organizationId", org(), "dealId", id, "partnerId",
                partnerId));
        return get(id);
    }

    /** Partner record created on conversion (fields of {@code Partner}). */
    public record NewCustomer(String name, Boolean isCompany, Boolean isCustomer, String email, String phone) {}

    /** Re-assign; {@code ownerId} null = automatic assignment by the configured rule. */
    @Transactional
    public Map<String, Object> assign(String id, Map<String, Object> in) {
        visible(id);
        String owner = str(in.get("ownerId"));
        if (blank(owner)) {
            owner = autoAssign();
            if (owner == null) {
                throw ApiException.badRequest("Automatic assignment is off or the assignment pool is empty");
            }
        } else {
            config.requireUser(owner);
        }
        db.patch("Deal", id, Obj.of("ownerId", owner, "teamId", teamOf(owner)), true);
        audit.record(AuditInput.of("Deal", id, "update", null, Obj.of("ownerId", owner)));
        // Notification routing (ADR-S065): the new owner learns about it.
        events.publish("crm.deal.assigned", Obj.of("organizationId", org(), "dealId", id, "ownerId", owner));
        return get(id);
    }

    /** Archive (soft delete). Won opportunities are kept. */
    @Transactional
    public Map<String, Object> archive(String id) {
        Map<String, Object> deal = lock(id);
        if ("won".equals(deal.get("status"))) {
            throw ApiException.badRequest("Cannot delete a won deal; archive it instead");
        }
        db.update("UPDATE \"Deal\" SET \"deletedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                + " \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of("Deal", id, "delete"));
        events.publish("crm.deal.deleted", Obj.of("organizationId", org(), "dealId", id));
        return Obj.of("ok", true);
    }

    // ------------------------------------------------------------------ helpers

    /** Card row visible to the caller, or 404. */
    Map<String, Object> visible(String id) {
        CrmDealQuery q = new CrmDealQuery(null);
        List<Object> params = new ArrayList<>(q.params);
        params.add(id);
        Map<String, Object> r = db.row("SELECT " + CrmDealQuery.COLUMNS + CrmDealQuery.FROM + q.where + " AND d.id = ?",
                params.toArray());
        if (r == null) {
            throw ApiException.notFound("Opportunity not found");
        }
        return q.decorate(r);
    }

    private Map<String, Object> lock(String id) {
        visible(id);
        return db.row("SELECT *, stage::text AS stage FROM \"Deal\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL FOR UPDATE", id, org());
    }

    private Map<String, Object> fields(Map<String, Object> in) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (String k : List.of("contactName", "companyName", "email", "phone", "city", "country", "notes",
                "lostNote")) {
            if (in.containsKey(k)) {
                String v = str(in.get(k));
                d.put(k, blank(v) ? null : v.trim());
            }
        }
        if (in.containsKey("email") && d.get("email") != null && !str(d.get("email")).matches("^[^@\\s]+@[^@\\s]+$")) {
            throw ApiException.badRequest("email must be an email");
        }
        if (in.containsKey("partnerId")) {
            String p = str(in.get("partnerId"));
            if (!blank(p) && !db.exists("Partner", p)) {
                throw ApiException.notFound("Partner not found");
            }
            d.put("partnerId", blank(p) ? null : p);
        }
        if (in.containsKey("contactId")) {
            String c = str(in.get("contactId"));
            d.put("contactId", blank(c) ? null : c);
        }
        if (in.containsKey("teamId")) {
            String t = str(in.get("teamId"));
            if (!blank(t) && !db.exists("CrmTeam", t)) {
                throw ApiException.notFound("Team not found");
            }
            d.put("teamId", blank(t) ? null : t);
        }
        if (in.containsKey("sourceId")) {
            String s = str(in.get("sourceId"));
            if (!blank(s) && !db.exists("CrmSource", s)) {
                throw ApiException.notFound("Source not found");
            }
            d.put("sourceId", blank(s) ? null : s);
        }
        if (in.containsKey("stageId") && !blank(str(in.get("stageId")))) {
            db.require("CrmStage", str(in.get("stageId")), "Stage not found");
            d.put("stageId", str(in.get("stageId")));
        }
        if (in.containsKey("tagIds")) {
            List<String> tags = strings(in.get("tagIds"));
            for (String t : tags) {
                if (!db.exists("CrmTag", t)) {
                    throw ApiException.notFound("Tag not found");
                }
            }
            d.put("tagIds", tags);
        }
        if (in.containsKey("amount")) {
            BigDecimal a = dec(in.get("amount"));
            if (a.signum() < 0) {
                throw ApiException.badRequest("amount must not be less than 0");
            }
            d.put("amount", a);
        }
        if (in.containsKey("currencyCode") && !blank(str(in.get("currencyCode")))) {
            d.put("currencyCode", str(in.get("currencyCode")).toUpperCase());
        }
        if (in.containsKey("probability")) {
            Object p = in.get("probability");
            BigDecimal v = p == null ? null : dec(p);
            if (v != null && (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0)) {
                throw ApiException.badRequest("probability must be between 0 and 100");
            }
            d.put("probability", v);
        }
        if (in.containsKey("priority")) {
            int pr = in.get("priority") == null ? 0 : ((Number) in.get("priority")).intValue();
            if (pr < 0 || pr > 3) {
                throw ApiException.badRequest("priority must be between 0 and 3");
            }
            d.put("priority", pr);
        }
        if (in.containsKey("expectedClose")) {
            d.put("expectedClose", CrmDb.instant(str(in.get("expectedClose"))));
        }
        return d;
    }

    private Map<String, Object> primaryContact(String partnerId) {
        Map<String, Object> c = db.row("SELECT id, trim(concat_ws(' ', \"firstName\", \"lastName\")) AS name, email,"
                + " phone FROM \"Contact\" WHERE \"partnerId\" = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " ORDER BY \"isPrimary\" DESC, \"createdAt\" LIMIT 1", partnerId, org());
        if (c != null) {
            return c;
        }
        Map<String, Object> p = db.row("SELECT email, phone FROM \"Partner\" WHERE id = ?", partnerId);
        return p == null || (p.get("email") == null && p.get("phone") == null) ? null
                : Obj.of("id", null, "name", null, "email", p.get("email"), "phone", p.get("phone"));
    }

    /** Owner from the settings' assignment rule, or null for manual assignment. */
    String autoAssign() {
        Map<String, Object> st = config.settings();
        List<String> pool = strings(st.get("assignmentPool"));
        String mode = str(st.get("assignmentMode"));
        if ("manual".equals(mode) || pool.isEmpty()) {
            return null;
        }
        if ("least_loaded".equals(mode)) {
            Map<String, Object> best = db.row("SELECT u.id FROM unnest(?::text[]) u(id) LEFT JOIN \"Deal\" d ON"
                    + " d.\"ownerId\" = u.id AND d.\"organizationId\" = ? AND d.\"deletedAt\" IS NULL AND d.status ="
                    + " 'open' GROUP BY u.id ORDER BY count(d.id), u.id LIMIT 1", pool.toArray(String[]::new), org());
            return best == null ? null : str(best.get("id"));
        }
        Map<String, Object> cur = db.row("UPDATE \"CrmSettings\" SET \"roundRobinCursor\" = \"roundRobinCursor\" + 1"
                + " WHERE \"organizationId\" = ? RETURNING \"roundRobinCursor\"", org());
        int n = ((Number) cur.get("roundRobinCursor")).intValue();
        return pool.get(Math.floorMod(n - 1, pool.size()));
    }

    private String teamOf(String userId) {
        Map<String, Object> t = db.row("SELECT id FROM \"CrmTeam\" WHERE \"organizationId\" = ? AND \"isActive\" AND"
                + " (\"leaderId\" = ? OR ? = ANY(\"memberIds\")) ORDER BY name LIMIT 1", org(), userId, userId);
        return t == null ? null : str(t.get("id"));
    }

    String orgCurrency() {
        Map<String, Object> o = db.row("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?", org());
        return o == null || o.get("currencyCode") == null ? "USD" : str(o.get("currencyCode"));
    }

    private void syncLegacyStage(String id, String legacy) {
        db.update("UPDATE \"Deal\" SET stage = ?::\"DealStage\" WHERE id = ? AND \"organizationId\" = ?",
                legacy == null ? "qualified" : legacy, id, org());
    }

    private void history(String dealId, String from, String to, String fromStatus, String toStatus, Object since) {
        Long seconds = since instanceof Instant i ? java.time.Duration.between(i, Instant.now()).getSeconds() : null;
        db.insert("CrmStageHistory", Obj.of("dealId", dealId, "fromStageId", from, "toStageId", to, "fromStatus",
                fromStatus, "toStatus", toStatus, "secondsInStage", seconds, "changedById", user()));
    }

    private String stageName(String id) {
        Map<String, Object> s = id == null ? null : config.stage(id);
        return s == null ? "—" : str(s.get("name"));
    }

    private void stageActivity(Map<String, Object> deal, String from, String to) {
        db.update("INSERT INTO \"Activity\" (id, \"organizationId\", type, title, \"dealId\", \"partnerId\","
                + " \"occurredAt\", status, completed, \"createdById\", \"updatedAt\") VALUES (?, ?, 'deal_stage_change',"
                + " ?, ?, ?, " + NOW + ", 'done', true, ?, " + NOW + ")", CrmDb.newId(), org(), "Stage: " + from + " → "
                + to, deal.get("id"), deal.get("partnerId"), user());
    }

    private void reorderInStage(String id, String stageId, int index) {
        List<Map<String, Object>> ids = db.rows("SELECT id FROM \"Deal\" WHERE \"organizationId\" = ? AND \"stageId\" = ?"
                + " AND \"deletedAt\" IS NULL AND id <> ? ORDER BY \"sequenceInStage\", priority DESC, \"updatedAt\""
                + " DESC", org(), stageId, id);
        List<Object> order = new ArrayList<>(ids.stream().map(m -> m.get("id")).toList());
        order.add(Math.max(0, Math.min(index, order.size())), id);
        for (int i = 0; i < order.size(); i++) {
            db.update("UPDATE \"Deal\" SET \"sequenceInStage\" = ? WHERE id = ? AND \"organizationId\" = ?", i,
                    order.get(i), org());
        }
    }

    static int parseInt(String v, int fallback) {
        try {
            return blank(v) ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Does the caller see every salesperson's records? */
    static boolean viewAll() {
        return TenantContext.has(Permissions.CRM__DEAL_VIEW_ALL);
    }
}
