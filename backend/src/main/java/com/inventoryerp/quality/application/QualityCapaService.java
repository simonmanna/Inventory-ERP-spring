package com.inventoryerp.quality.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quality.application.QualityCommands.CapaActionComplete;
import com.inventoryerp.quality.application.QualityCommands.CapaActionInput;
import com.inventoryerp.quality.application.QualityCommands.CapaActionPatch;
import com.inventoryerp.quality.application.QualityCommands.CapaInput;
import com.inventoryerp.quality.application.QualityCommands.CapaPatch;
import com.inventoryerp.quality.application.QualityCommands.CapaQuery;
import com.inventoryerp.quality.application.QualityCommands.CapaStatusChange;
import com.inventoryerp.quality.domain.QualityCapa;
import com.inventoryerp.quality.domain.QualityCapaAction;
import com.inventoryerp.quality.domain.QualityNcr;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrective / preventive actions (ADR-S013 §3): {@code open → in_progress → verification → closed}, cancel from any
 * open state. Verification needs every action done / cancelled; closing (effectiveness verified) is done by a user
 * other than the CAPA owner. {@link #overdue} feeds the Phase 5 alerts.
 */
@Service
public class QualityCapaService {

    static final String AUDIT_ENTITY = "QualityCapa";

    private static final Map<String, Set<String>> MOVES = Map.of(
            "open", Set.of("in_progress", "cancelled"),
            "in_progress", Set.of("verification", "cancelled"),
            "verification", Set.of("closed", "in_progress", "cancelled"),
            "closed", Set.of(),
            "cancelled", Set.of());

    private final QualityDb db;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;

    public QualityCapaService(QualityDb db, SequenceService sequence, AuditService audit, EventBus events) {
        this.db = db;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    private static LocalDate today() {
        return LocalDate.now(BusinessZone.get());
    }

    private static final String SELECT = """
            SELECT c.*, n."ncrNumber", TRIM(CONCAT(o."firstName", ' ', o."lastName")) AS "ownerName",
                   (SELECT count(*) FROM "QualityCapaAction" a WHERE a."capaId" = c.id) AS "actionCount",
                   (SELECT count(*) FROM "QualityCapaAction" a WHERE a."capaId" = c.id AND a.status = 'open')
                       AS "openActionCount",
                   (c.status NOT IN ('closed', 'cancelled') AND c."dueDate" < ?) AS overdue
            FROM "QualityCapa" c
            LEFT JOIN "QualityNcr" n ON n.id = c."ncrId"
            LEFT JOIN "User" o ON o.id = c."ownerUserId"
            """;

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(CapaQuery q) {
        int page = Q.page(q.page());
        int size = Q.size(q.pageSize());
        StringBuilder where = new StringBuilder(" WHERE c.\"organizationId\" = ? AND c.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(QualityDb.org()));
        if (Q.has(q.search())) {
            where.append(" AND (c.\"capaNumber\" ILIKE ? OR c.title ILIKE ?)");
            String like = Q.like(q.search());
            params.add(like);
            params.add(like);
        }
        Q.eq(where, params, "c.status", q.status());
        Q.eq(where, params, "c.\"ownerUserId\"", q.ownerUserId());
        Q.eq(where, params, "c.\"ncrId\"", q.ncrId());
        if (Boolean.TRUE.equals(q.overdue())) {
            where.append(" AND c.status NOT IN ('closed', 'cancelled') AND (c.\"dueDate\" < ? OR EXISTS (SELECT 1 FROM"
                    + " \"QualityCapaAction\" a WHERE a.\"capaId\" = c.id AND a.status = 'open' AND a.\"dueDate\" < ?))");
            params.add(java.sql.Date.valueOf(today()));
            params.add(java.sql.Date.valueOf(today()));
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"QualityCapa\" c" + where).params(params).query(Long.class)
                .single();
        List<Object> all = new ArrayList<>();
        all.add(java.sql.Date.valueOf(today()));
        all.addAll(params);
        all.add(size);
        all.add((page - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(SELECT + where + " ORDER BY c.\"createdAt\" DESC, c.id"
                + " LIMIT ? OFFSET ?").params(all)), page, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> c = SqlRows.one(db.jdbc().sql(SELECT + " WHERE c.id = ? AND c.\"organizationId\" = ? AND"
                + " c.\"deletedAt\" IS NULL").params(java.sql.Date.valueOf(today()), id, QualityDb.org()));
        if (c == null) {
            throw ApiException.notFound("CAPA not found");
        }
        c.put("actions", actions(id));
        return c;
    }

    private List<Map<String, Object>> actions(String capaId) {
        return SqlRows.list(db.jdbc().sql("SELECT a.*, TRIM(CONCAT(o.\"firstName\", ' ', o.\"lastName\")) AS \"ownerName\","
                + " (a.status = 'open' AND a.\"dueDate\" < ?) AS overdue FROM \"QualityCapaAction\" a LEFT JOIN \"User\" o"
                + " ON o.id = a.\"ownerUserId\" WHERE a.\"capaId\" = ? AND a.\"organizationId\" = ?"
                + " ORDER BY a.\"dueDate\" NULLS LAST, a.\"createdAt\"").params(java.sql.Date.valueOf(today()), capaId,
                QualityDb.org()));
    }

    @Transactional
    public Map<String, Object> create(CapaInput in) {
        if (Q.has(in.ncrId())) {
            Map<String, Object> ncr = db.require(QualityNcr.class, in.ncrId(), false, "NCR not found");
            if (List.of("closed", "cancelled").contains(Q.str(ncr, "status"))) {
                throw ApiException.badRequest("NCR " + ncr.get("ncrNumber") + " is " + ncr.get("status") + ".");
            }
        }
        requireUser(in.ownerUserId());
        String number = sequence.next("quality_capa", "CAPA-", 5);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("capaNumber", number);
        data.put("ncrId", Q.trim(in.ncrId()));
        data.put("title", in.title().trim());
        data.put("rootCause", Q.trim(in.rootCause()));
        data.put("rootCauseMethod", in.rootCauseMethod());
        data.put("ownerUserId", Q.trim(in.ownerUserId()));
        data.put("dueDate", in.dueDate());
        data.put("status", "open");
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        String id = Q.str(db.insert(QualityCapa.class, data), "id");
        for (CapaActionInput a : in.actions() == null ? List.<CapaActionInput>of() : in.actions()) {
            insertAction(id, a);
        }
        if (Q.has(in.ncrId())) {
            db.jdbc().sql("UPDATE \"QualityNcr\" SET status = 'action', \"updatedAt\" = " + QualityDb.NOW
                    + " WHERE id = ? AND \"organizationId\" = ? AND status IN ('open', 'investigating')")
                    .params(in.ncrId(), QualityDb.org()).update();
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, Obj.of("capaNumber", number, "ncrId",
                in.ncrId())));
        // Notification routing (ADR-S065): the owner has a corrective action to do.
        events.publish("quality.capa.created", Obj.of("organizationId", QualityDb.org(), "capaId", id, "capaNumber",
                number, "ownerUserId", Q.trim(in.ownerUserId())));
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, CapaPatch in) {
        Map<String, Object> c = requireOpen(id);
        requireUser(in.ownerUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.title() != null) {
            data.put("title", in.title().trim());
        }
        if (in.rootCause() != null) {
            data.put("rootCause", Q.trim(in.rootCause()));
        }
        if (in.rootCauseMethod() != null) {
            data.put("rootCauseMethod", in.rootCauseMethod());
        }
        if (in.ownerUserId() != null) {
            data.put("ownerUserId", Q.trim(in.ownerUserId()));
        }
        if (in.dueDate() != null) {
            data.put("dueDate", in.dueDate());
        }
        if (in.effectivenessNotes() != null) {
            data.put("effectivenessNotes", Q.trim(in.effectivenessNotes()));
        }
        data.put("updatedBy", TenantContext.userId());
        db.update(QualityCapa.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", null, Obj.of("capaNumber", c.get("capaNumber"))));
        return get(id);
    }

    @Transactional
    public Map<String, Object> changeStatus(String id, CapaStatusChange in) {
        Map<String, Object> c = db.require(QualityCapa.class, id, true, "CAPA not found");
        String from = Q.str(c, "status");
        String to = in.status();
        if (!MOVES.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.badRequest("CAPA " + c.get("capaNumber") + " cannot move from " + from + " to " + to + ".");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", to);
        if ("verification".equals(to)) {
            List<Map<String, Object>> acts = actions(id);
            if (acts.isEmpty()) {
                throw ApiException.badRequest("CAPA " + c.get("capaNumber") + " has no actions to verify.");
            }
            if (acts.stream().anyMatch(a -> "open".equals(a.get("status")))) {
                throw ApiException.badRequest("All actions must be done or cancelled before verification.");
            }
        }
        if ("closed".equals(to)) {
            String user = TenantContext.userId();
            if (user != null && user.equals(c.get("ownerUserId"))) {
                throw ApiException.forbidden("The CAPA owner cannot verify its effectiveness (segregation of duties).");
            }
            String notes = Q.has(in.notes()) ? in.notes().trim() : Q.str(c, "effectivenessNotes");
            if (!Q.has(notes)) {
                throw ApiException.badRequest("Record the effectiveness check before closing the CAPA.");
            }
            Instant now = Instant.now();
            data.put("effectivenessNotes", notes);
            data.put("verifiedBy", user);
            data.put("verifiedAt", now);
            data.put("closedAt", now);
            data.put("closedBy", user);
        }
        data.put("updatedBy", TenantContext.userId());
        db.update(QualityCapa.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "cancelled".equals(to) ? "cancel" : "closed".equals(to)
                ? "approve" : "update", Obj.of("status", from),
                Obj.of("status", to, "notes", Q.trim(in.notes()))));
        if ("closed".equals(to)) {
            events.publish(DomainEvents.QUALITY_CAPA_CLOSED, Obj.of("organizationId", QualityDb.org(), "capaId", id,
                    "capaNumber", c.get("capaNumber"), "ncrId", c.get("ncrId")));
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> addAction(String capaId, CapaActionInput in) {
        requireOpen(capaId);
        insertAction(capaId, in);
        return get(capaId);
    }

    @Transactional
    public Map<String, Object> updateAction(String actionId, CapaActionPatch in) {
        Map<String, Object> a = db.require(QualityCapaAction.class, actionId, true, "CAPA action not found");
        requireOpen(Q.str(a, "capaId"));
        if (!"open".equals(a.get("status"))) {
            throw ApiException.badRequest("Only an open action can be edited.");
        }
        requireUser(in.ownerUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.kind() != null) {
            data.put("kind", in.kind());
        }
        if (in.description() != null) {
            data.put("description", in.description().trim());
        }
        if (in.ownerUserId() != null) {
            data.put("ownerUserId", Q.trim(in.ownerUserId()));
        }
        if (in.dueDate() != null) {
            data.put("dueDate", in.dueDate());
        }
        if (in.notes() != null) {
            data.put("notes", Q.trim(in.notes()));
        }
        db.update(QualityCapaAction.class, actionId, data);
        return get(Q.str(a, "capaId"));
    }

    @Transactional
    public Map<String, Object> completeAction(String actionId, CapaActionComplete in) {
        Map<String, Object> a = db.require(QualityCapaAction.class, actionId, true, "CAPA action not found");
        Map<String, Object> c = requireOpen(Q.str(a, "capaId"));
        if (!"open".equals(a.get("status"))) {
            throw ApiException.badRequest("The action is already " + a.get("status") + ".");
        }
        String status = Q.or(in.status(), "done");
        db.update(QualityCapaAction.class, actionId, Obj.of("status", status, "completedAt", Instant.now(),
                "completedBy", TenantContext.userId(), "notes", Q.has(in.notes()) ? in.notes().trim() : a.get("notes")));
        if ("open".equals(c.get("status"))) {
            db.update(QualityCapa.class, Q.str(c, "id"), Obj.of("status", "in_progress"));
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, Q.str(c, "id"), "update", null, Obj.of("actionId", actionId,
                "status", status)));
        return get(Q.str(c, "id"));
    }

    /** Open actions past their due date and open CAPAs past theirs (alerts / dashboard). */
    @Transactional(readOnly = true)
    public Map<String, Object> overdue() {
        java.sql.Date d = java.sql.Date.valueOf(today());
        List<Map<String, Object>> actions = SqlRows.list(db.jdbc().sql("SELECT a.id, a.\"capaId\", c.\"capaNumber\","
                + " c.title AS \"capaTitle\", a.kind, a.description, a.\"ownerUserId\", TRIM(CONCAT(o.\"firstName\", ' ',"
                + " o.\"lastName\")) AS \"ownerName\", a.\"dueDate\", (? - a.\"dueDate\") AS \"daysOverdue\""
                + " FROM \"QualityCapaAction\" a JOIN \"QualityCapa\" c ON c.id = a.\"capaId\" AND c.\"deletedAt\" IS NULL"
                + " AND c.status NOT IN ('closed', 'cancelled') LEFT JOIN \"User\" o ON o.id = a.\"ownerUserId\""
                + " WHERE a.\"organizationId\" = ? AND a.status = 'open' AND a.\"dueDate\" < ? ORDER BY a.\"dueDate\"")
                .params(d, QualityDb.org(), d));
        List<Map<String, Object>> capas = SqlRows.list(db.jdbc().sql("SELECT id, \"capaNumber\", title, status,"
                + " \"ownerUserId\", \"dueDate\", (? - \"dueDate\") AS \"daysOverdue\" FROM \"QualityCapa\""
                + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND status NOT IN ('closed', 'cancelled')"
                + " AND \"dueDate\" < ? ORDER BY \"dueDate\"").params(d, QualityDb.org(), d));
        return Obj.of("asOf", today(), "actions", actions, "capas", capas);
    }

    private void insertAction(String capaId, CapaActionInput in) {
        requireUser(in.ownerUserId());
        db.insert(QualityCapaAction.class, Obj.of("capaId", capaId, "kind", Q.or(in.kind(), "corrective"),
                "description", in.description().trim(), "ownerUserId", Q.trim(in.ownerUserId()), "dueDate",
                in.dueDate(), "status", "open"));
    }

    private Map<String, Object> requireOpen(String id) {
        Map<String, Object> c = db.require(QualityCapa.class, id, true, "CAPA not found");
        if (List.of("closed", "cancelled").contains(Q.str(c, "status"))) {
            throw ApiException.badRequest("CAPA " + c.get("capaNumber") + " is " + c.get("status") + ".");
        }
        return c;
    }

    private void requireUser(String userId) {
        if (Q.has(userId) && !db.exists("User", userId, true)) {
            throw ApiException.notFound("Owner not found");
        }
    }
}
