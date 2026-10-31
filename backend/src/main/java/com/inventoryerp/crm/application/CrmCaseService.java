package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.NOW;
import static com.inventoryerp.crm.application.CrmDb.blank;
import static com.inventoryerp.crm.application.CrmDb.org;
import static com.inventoryerp.crm.application.CrmDb.str;
import static com.inventoryerp.crm.application.CrmDb.user;

import com.inventoryerp.fleet.application.FleetDiscrepancyService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer complaints and service requests (CRM-1). A case has a customer, a priority that sets its due time, an owner
 * and a timeline. It can be opened straight from a fleet delivery discrepancy ({@link FleetDiscrepancyService}, the
 * fleet API): one live case per discrepancy, and the fleet decision is written to the case's timeline by
 * {@link CrmCaseDiscrepancySubscriber}. The decision does not close the case: resolving it stays a person's call, since
 * the customer may still need an answer.
 */
@Service
public class CrmCaseService {

    private static final Logger log = LoggerFactory.getLogger(CrmCaseService.class);
    private static final String SELECT = """
            SELECT c.*, p.name AS "partnerName",
                   NULLIF(trim(concat(u."firstName", ' ', u."lastName")), '') AS "assigneeName",
                   (c."dueAt" < (now() AT TIME ZONE 'UTC') AND c.status IN ('open', 'in_progress')) AS overdue
            FROM "CrmCase" c
            LEFT JOIN "Partner" p ON p.id = c."partnerId"
            LEFT JOIN "User" u ON u.id = c."assigneeId"
            """;

    private final CrmDb db;
    private final AuditService audit;
    private final SequenceService sequence;
    private final FleetDiscrepancyService discrepancies;
    private final NotificationsService notifications;

    public CrmCaseService(CrmDb db, AuditService audit, SequenceService sequence, FleetDiscrepancyService discrepancies,
            NotificationsService notifications) {
        this.db = db;
        this.audit = audit;
        this.sequence = sequence;
        this.discrepancies = discrepancies;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(Map<String, String> p) {
        StringBuilder where = new StringBuilder(" WHERE c.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        for (String[] f : new String[][] {{"status", "status"}, {"caseType", "\"caseType\""}, {"category", "category"},
            {"priority", "priority"}, {"partnerId", "\"partnerId\""}, {"assigneeId", "\"assigneeId\""}}) {
            if (!blank(p.get(f[0]))) {
                where.append(" AND c.").append(f[1]).append(" = ?");
                params.add(p.get(f[0]));
            }
        }
        if ("true".equals(p.get("overdue"))) {
            where.append(" AND c.\"dueAt\" < (now() AT TIME ZONE 'UTC') AND c.status IN ('open', 'in_progress')");
        }
        if (!blank(p.get("q"))) {
            String like = "%" + BaseCrudService.escapeLike(p.get("q").trim()) + "%";
            where.append(" AND (c.code ILIKE ? OR c.subject ILIKE ? OR p.name ILIKE ?)");
            params.addAll(List.of(like, like, like));
        }
        int page = Math.max(1, CrmPipelineService.parseInt(p.get("page"), 1));
        int pageSize = Math.min(200, Math.max(1, CrmPipelineService.parseInt(p.get("pageSize"), 25)));
        long total = db.count("SELECT count(*) FROM \"CrmCase\" c LEFT JOIN \"Partner\" p ON p.id = c.\"partnerId\""
                + where, params);
        List<Map<String, Object>> rows = db.rows(SELECT + where + " ORDER BY overdue DESC, c.\"dueAt\" ASC NULLS LAST,"
                + " c.code DESC OFFSET " + (long) (page - 1) * pageSize + " LIMIT " + pageSize, params);
        return PageResult.of(rows, page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> row = db.row(SELECT + " WHERE c.id = ? AND c.\"organizationId\" = ?", id, org());
        if (row == null) {
            throw ApiException.notFound("Case not found");
        }
        row.put("events", db.rows("SELECT e.*, NULLIF(trim(concat(u.\"firstName\", ' ', u.\"lastName\")), '') AS"
                + " \"actorName\" FROM \"CrmCaseEvent\" e LEFT JOIN \"User\" u ON u.id = e.\"actorId\" WHERE e.\"caseId\""
                + " = ? AND e.\"organizationId\" = ? ORDER BY e.\"createdAt\" DESC, e.id", id, org()));
        return row;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> in) {
        if (blank(str(in.get("subject"))) || blank(str(in.get("partnerId")))) {
            throw ApiException.badRequest("subject and partnerId are required");
        }
        if (!db.exists("Partner", str(in.get("partnerId")))) {
            throw ApiException.notFound("Partner not found");
        }
        if (!blank(str(in.get("assigneeId"))) && !db.exists("User", str(in.get("assigneeId")))) {
            throw ApiException.notFound("User not found");
        }
        Map<String, Object> data = new LinkedHashMap<>(in);
        // Fleet links come only from fromDiscrepancy, where fleet vouches for them.
        data.keySet().removeAll(List.of("deliveryOrderId", "deliveryOrderCode", "discrepancyId", "discrepancyCode"));
        return insert(data);
    }

    private Map<String, Object> insert(Map<String, Object> in) {
        String type = oneOf(in.get("caseType"), "complaint", CrmCaseRules.TYPES, "caseType");
        String category = oneOf(in.get("category"), "other", CrmCaseRules.CATEGORIES, "category");
        String priority = oneOf(in.get("priority"), "normal", CrmCaseRules.PRIORITIES, "priority");
        Instant due = blank(str(in.get("dueAt"))) ? CrmCaseRules.dueAt(Instant.now(), priority)
                : CrmDb.instant(str(in.get("dueAt")));
        Map<String, Object> row = db.insert("CrmCase", Obj.of("code", sequence.next("crm_case", "CASE-", 6),
                "caseType", type, "category", category, "subject", str(in.get("subject")),
                "description", in.get("description"), "partnerId", str(in.get("partnerId")),
                "contactName", in.get("contactName"), "contactPhone", in.get("contactPhone"), "priority", priority,
                "assigneeId", blank(str(in.get("assigneeId"))) ? null : str(in.get("assigneeId")), "dueAt", due,
                "deliveryOrderId", in.get("deliveryOrderId"), "deliveryOrderCode", in.get("deliveryOrderCode"),
                "discrepancyId", in.get("discrepancyId"), "discrepancyCode", in.get("discrepancyCode"),
                "createdBy", user()));
        String id = str(row.get("id"));
        event(id, "status", null, null, "open");
        audit.recordInTx(AuditInput.of("CrmCase", id, "create", null, Obj.of("code", row.get("code"), "partnerId",
                row.get("partnerId"), "caseType", type)));
        if (row.get("assigneeId") != null) {
            notifyAssignee(row);
        }
        return get(id);
    }

    /**
     * Open a complaint from a delivery discrepancy, with its customer, delivery and quantities filled in. Repeating
     * the call returns the live case.
     */
    @Transactional
    public Map<String, Object> fromDiscrepancy(String discrepancyId, Map<String, Object> in) {
        Map<String, Object> x = discrepancies.get(discrepancyId);
        Map<String, Object> existing = db.row("SELECT id FROM \"CrmCase\" WHERE \"organizationId\" = ? AND"
                + " \"discrepancyId\" = ? AND status <> 'cancelled'", org(), discrepancyId);
        if (existing != null) {
            return get(str(existing.get("id")));
        }
        if (x.get("partnerId") == null) {
            throw ApiException.badRequest("This delivery has no customer");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseType", "complaint");
        data.put("category", "delivery");
        data.put("subject", "Delivery " + x.get("kind") + " " + x.get("code") + ": " + x.get("productName"));
        data.put("description", in.get("description") != null ? in.get("description") : describe(x));
        data.put("partnerId", x.get("partnerId"));
        data.put("priority", in.get("priority") != null ? in.get("priority") : "high");
        data.put("assigneeId", in.get("assigneeId"));
        data.put("deliveryOrderId", x.get("deliveryOrderId"));
        data.put("deliveryOrderCode", x.get("deliveryOrderCode"));
        data.put("discrepancyId", discrepancyId);
        data.put("discrepancyCode", x.get("code"));
        return insert(data);
    }

    private static String describe(Map<String, Object> x) {
        String uom = x.get("uomCode") == null ? "" : " " + x.get("uomCode");
        return "Dispatched " + x.get("dispatchedQty") + uom + ", delivered " + x.get("deliveredQty") + uom + ". Reason: "
                + x.get("reason") + (x.get("notes") == null ? "" : ". " + x.get("notes"));
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> in) {
        Map<String, Object> row = db.require("CrmCase", id, "Case not found");
        assertOpen(row);
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : List.of("subject", "description", "contactName", "contactPhone")) {
            if (in.containsKey(f)) {
                data.put(f, in.get(f));
            }
        }
        if (in.containsKey("subject") && blank(str(in.get("subject")))) {
            throw ApiException.badRequest("subject is required");
        }
        if (in.containsKey("category")) {
            data.put("category", oneOf(in.get("category"), "other", CrmCaseRules.CATEGORIES, "category"));
        }
        if (in.containsKey("priority")) {
            data.put("priority", oneOf(in.get("priority"), "normal", CrmCaseRules.PRIORITIES, "priority"));
        }
        if (in.containsKey("dueAt")) {
            data.put("dueAt", CrmDb.instant(str(in.get("dueAt"))));
        }
        data.put("updatedBy", user());
        db.patch("CrmCase", id, data, true);
        audit.recordInTx(AuditInput.of("CrmCase", id, "update", null, data));
        return get(id);
    }

    @Transactional
    public Map<String, Object> assign(String id, Map<String, Object> in) {
        Map<String, Object> row = db.require("CrmCase", id, "Case not found");
        assertOpen(row);
        String assignee = blank(str(in.get("assigneeId"))) ? null : str(in.get("assigneeId"));
        if (assignee != null && !db.exists("User", assignee)) {
            throw ApiException.notFound("User not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assigneeId", assignee);
        data.put("updatedBy", user());
        Map<String, Object> out = db.patch("CrmCase", id, data, true);
        event(id, "assignment", null, null, null);
        audit.recordInTx(AuditInput.of("CrmCase", id, "update", Obj.of("assigneeId", row.get("assigneeId")),
                Obj.of("assigneeId", assignee)));
        if (assignee != null) {
            notifyAssignee(out);
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> addNote(String id, Map<String, Object> in) {
        db.require("CrmCase", id, "Case not found");
        if (blank(str(in.get("body")))) {
            throw ApiException.badRequest("body is required");
        }
        event(id, "note", str(in.get("body")), null, null);
        db.patch("CrmCase", id, Obj.of("updatedBy", user()), true);
        return get(id);
    }

    /** Move a case: resolving needs the resolution, cancelling a reason; a resolved case can be reopened. */
    @Transactional
    public Map<String, Object> setStatus(String id, Map<String, Object> in) {
        db.jdbc().sql("SELECT id FROM \"CrmCase\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, org())
                .query().listOfRows();
        Map<String, Object> row = db.require("CrmCase", id, "Case not found");
        String from = str(row.get("status"));
        String to = str(in.get("status"));
        if (!CrmCaseRules.STATUSES.contains(to)) {
            throw ApiException.badRequest("Invalid status: " + to);
        }
        if (!CrmCaseRules.mayBecome(from, to)) {
            throw ApiException.badRequest("A case cannot go from " + from + " to " + to);
        }
        String note = str(in.get("note"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", to);
        data.put("updatedBy", user());
        if ("resolved".equals(to)) {
            if (blank(note)) {
                throw ApiException.badRequest("A resolution is required");
            }
            data.put("resolution", note);
            data.put("resolvedAt", Instant.now());
        } else if ("cancelled".equals(to) && blank(note)) {
            throw ApiException.badRequest("A reason is required");
        } else if ("closed".equals(to)) {
            data.put("closedAt", Instant.now());
        } else if ("in_progress".equals(to) && "resolved".equals(from)) {
            data.put("resolvedAt", null);
        }
        db.patch("CrmCase", id, data, true);
        event(id, "status", note, from, to);
        audit.recordInTx(AuditInput.of("CrmCase", id, "update", Obj.of("status", from), Obj.of("status", to)));
        return get(id);
    }

    /** Timeline entry for a fleet decision on a linked discrepancy (at-least-once delivery: skips a repeat). */
    @Transactional
    public void recordDiscrepancyDecision(String discrepancyId, String code, String decision, boolean creditNote) {
        Map<String, Object> linked = db.row("SELECT id FROM \"CrmCase\" WHERE \"organizationId\" = ? AND"
                + " \"discrepancyId\" = ? AND status <> 'cancelled'", org(), discrepancyId);
        if (linked == null) {
            return;
        }
        String id = str(linked.get("id"));
        if (db.count("SELECT count(*) FROM \"CrmCaseEvent\" WHERE \"caseId\" = ? AND kind = 'discrepancy' AND"
                + " \"toStatus\" = ?", List.of(id, decision)) > 0) {
            return;
        }
        event(id, "discrepancy", "Discrepancy " + code + " " + decision + (creditNote ? "; credit note drafted" : ""),
                null, decision);
        db.update("UPDATE \"CrmCase\" SET \"updatedAt\" = " + NOW + " WHERE id = ?", id);
    }

    // ------------------------------------------------------------------ helpers

    private void event(String caseId, String kind, String body, String from, String to) {
        db.insert("CrmCaseEvent", Obj.of("caseId", caseId, "kind", kind, "body", body, "fromStatus", from, "toStatus", to,
                "actorId", user()));
    }

    private static void assertOpen(Map<String, Object> row) {
        if (!CrmCaseRules.LIVE.contains(str(row.get("status")))) {
            throw ApiException.badRequest("A " + row.get("status") + " case cannot be changed");
        }
    }

    private static String oneOf(Object value, String fallback, List<String> allowed, String what) {
        String v = blank(str(value)) ? fallback : str(value);
        if (!allowed.contains(v)) {
            throw ApiException.badRequest("Invalid " + what + ": " + v);
        }
        return v;
    }

    private void notifyAssignee(Map<String, Object> row) {
        try {
            notifications.send(new SendInput(org(), str(row.get("assigneeId")), "in_app", "crm", "Case " + row.get("code"),
                    str(row.get("subject")), Obj.of("kind", "crm.case.assigned", "caseId", row.get("id"), "link",
                            "/crm/cases/" + row.get("id"))));
        } catch (RuntimeException e) {
            log.warn("Case notification {} not delivered: {}", row.get("id"), e.toString());
        }
    }
}
