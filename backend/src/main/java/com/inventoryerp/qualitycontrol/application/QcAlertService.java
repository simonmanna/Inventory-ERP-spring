package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quality.application.QualityCommands.NcrInput;
import com.inventoryerp.quality.application.QualityNcrService;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.AlertInput;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.AlertPatch;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.AlertQuery;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.EscalateInput;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.MoveInput;
import com.inventoryerp.qualitycontrol.domain.QcAlert;
import com.inventoryerp.qualitycontrol.domain.QcAlertAssignmentHistory;
import com.inventoryerp.qualitycontrol.domain.QcAlertStage;
import com.inventoryerp.qualitycontrol.domain.QcAlertStageHistory;
import com.inventoryerp.qualitycontrol.domain.QcAlertTag;
import com.inventoryerp.qualitycontrol.domain.QcCheck;
import com.inventoryerp.qualitycontrol.domain.QcControlPoint;
import com.inventoryerp.qualitycontrol.domain.QcFailureClass;
import com.inventoryerp.qualitycontrol.domain.QcRootCause;
import com.inventoryerp.qualitycontrol.domain.QcTag;
import com.inventoryerp.qualitycontrol.domain.QcTeam;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quality alerts (ADR-S087 §8): the operational investigation of a quality problem, on a kanban of stages with stage
 * and assignment history. A failed generated check raises at most one automatic alert; an alert escalates to at most
 * one NCR of the ADR-S013 quality module. Resolving an alert never changes its check, an NCR or a CAPA.
 */
@Service
public class QcAlertService {

    static final String AUDIT_ENTITY = "QcAlert";

    private static final String SELECT = """
            SELECT a.*, s.name AS "stageName", s."isClosed" AS "stageClosed", t.name AS "teamName",
                   TRIM(CONCAT(u."firstName", ' ', u."lastName")) AS "responsibleName",
                   p.name AS "productName", p.code AS "productCode", c."checkNumber", cc."checkNumber" AS "correctionCheckNumber",
                   cp.reference AS "controlPointReference", fc.name AS "failureClassName", rc.name AS "rootCauseName",
                   n."ncrNumber", n.status AS "ncrStatus", sp.name AS "supplierName"
            FROM "QcAlert" a
            JOIN "QcAlertStage" s ON s.id = a."stageId"
            LEFT JOIN "QcTeam" t ON t.id = a."teamId"
            LEFT JOIN "User" u ON u.id = a."responsibleUserId"
            LEFT JOIN "Product" p ON p.id = a."productId" AND p."organizationId" = a."organizationId"
            LEFT JOIN "QcCheck" c ON c.id = a."checkId"
            LEFT JOIN "QcCheck" cc ON cc.id = a."correctionCheckId"
            LEFT JOIN "QcControlPoint" cp ON cp.id = a."controlPointId"
            LEFT JOIN "QcFailureClass" fc ON fc.id = a."failureClassId"
            LEFT JOIN "QcRootCause" rc ON rc.id = a."rootCauseId"
            LEFT JOIN "QualityNcr" n ON n.id = a."ncrId"
            LEFT JOIN "Partner" sp ON sp.id = a."supplierPartnerId" AND sp."organizationId" = a."organizationId"
            """;

    private final QcDb db;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;
    private final QcAlertConfigService config;
    private final QualityNcrService ncrs;

    public QcAlertService(QcDb db, SequenceService sequence, AuditService audit, EventBus events,
            QcAlertConfigService config, QualityNcrService ncrs) {
        this.db = db;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
        this.config = config;
        this.ncrs = ncrs;
    }

    // ================================================================== queries

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(AlertQuery q) {
        int page = QcUtil.page(q.page());
        int size = QcUtil.size(q.pageSize());
        List<Object> params = new ArrayList<>();
        String where = where(q, params);
        long total = db.jdbc().sql("SELECT count(*) FROM \"QcAlert\" a JOIN \"QcAlertStage\" s ON s.id = a.\"stageId\""
                + " LEFT JOIN \"Product\" p ON p.id = a.\"productId\" AND p.\"organizationId\" = a.\"organizationId\""
                + where).params(params).query(Long.class).single();
        params.add(size);
        params.add((page - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(SELECT + where
                + " ORDER BY a.\"createdAt\" DESC, a.\"alertNumber\" DESC LIMIT ? OFFSET ?").params(params));
        withTags(rows);
        return PageResult.of(rows, page, size, total);
    }

    /** The kanban: every active stage (in order) with its alerts, highest priority first. */
    @Transactional
    public Map<String, Object> kanban(AlertQuery q) {
        List<Map<String, Object>> stages = config.stages(true);
        List<Object> params = new ArrayList<>();
        String where = where(q, params);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(SELECT + where
                + " ORDER BY a.priority DESC, a.\"createdAt\" DESC LIMIT 1000").params(params));
        withTags(rows);
        Map<String, List<Map<String, Object>>> byStage = new HashMap<>();
        for (Map<String, Object> r : rows) {
            byStage.computeIfAbsent(QcUtil.str(r, "stageId"), k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        for (Map<String, Object> s : stages) {
            List<Map<String, Object>> alerts = byStage.getOrDefault(QcUtil.str(s, "id"), List.of());
            columns.add(Obj.of("stage", s, "count", alerts.size(), "alerts", alerts));
        }
        return Obj.of("columns", columns);
    }

    private static String where(AlertQuery q, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE a.\"organizationId\" = ?");
        params.add(QcDb.org());
        if (QcUtil.has(q.search())) {
            where.append(" AND (a.\"alertNumber\" ILIKE ? OR a.title ILIKE ? OR a.\"batchNumber\" ILIKE ? OR"
                    + " a.\"sourceRef\" ILIKE ? OR p.name ILIKE ?)");
            for (int i = 0; i < 5; i++) {
                params.add(QcUtil.like(q.search()));
            }
        }
        QcUtil.eq(where, params, "a.\"stageId\"", q.stageId());
        QcUtil.eq(where, params, "a.\"teamId\"", q.teamId());
        QcUtil.eq(where, params, "a.\"responsibleUserId\"", q.responsibleUserId());
        QcUtil.eq(where, params, "a.priority", q.priority());
        QcUtil.eq(where, params, "a.\"productId\"", q.productId());
        QcUtil.eq(where, params, "a.\"checkId\"", q.checkId());
        QcUtil.eq(where, params, "a.\"ncrId\"", q.ncrId());
        QcUtil.eq(where, params, "a.origin", q.origin());
        if (q.open() != null) {
            where.append(q.open() ? " AND NOT s.\"isClosed\"" : " AND s.\"isClosed\"");
        }
        if (QcUtil.has(q.tagId())) {
            where.append(" AND EXISTS (SELECT 1 FROM \"QcAlertTag\" x WHERE x.\"alertId\" = a.id AND x.\"tagId\" = ?)");
            params.add(q.tagId());
        }
        return where.toString();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> a = SqlRows.one(db.jdbc().sql(SELECT + " WHERE a.id = ? AND a.\"organizationId\" = ?")
                .params(id, QcDb.org()));
        if (a == null) {
            throw ApiException.notFound("Quality alert not found");
        }
        withTags(List.of(a));
        a.put("stageHistory", SqlRows.list(db.jdbc().sql("SELECT h.*, fs.name AS \"fromStageName\", ts.name AS"
                + " \"toStageName\", TRIM(CONCAT(u.\"firstName\", ' ', u.\"lastName\")) AS \"userName\" FROM"
                + " \"QcAlertStageHistory\" h LEFT JOIN \"QcAlertStage\" fs ON fs.id = h.\"fromStageId\" LEFT JOIN"
                + " \"QcAlertStage\" ts ON ts.id = h.\"toStageId\" LEFT JOIN \"User\" u ON u.id = h.\"userId\" WHERE"
                + " h.\"alertId\" = ? AND h.\"organizationId\" = ? ORDER BY h.\"createdAt\", h.id").params(id, QcDb.org())));
        a.put("assignmentHistory", SqlRows.list(db.jdbc().sql("SELECT h.*, ft.name AS \"fromTeamName\", tt.name AS"
                + " \"toTeamName\", TRIM(CONCAT(fu.\"firstName\", ' ', fu.\"lastName\")) AS \"fromResponsibleName\","
                + " TRIM(CONCAT(tu.\"firstName\", ' ', tu.\"lastName\")) AS \"toResponsibleName\","
                + " TRIM(CONCAT(u.\"firstName\", ' ', u.\"lastName\")) AS \"userName\" FROM \"QcAlertAssignmentHistory\" h"
                + " LEFT JOIN \"QcTeam\" ft ON ft.id = h.\"fromTeamId\" LEFT JOIN \"QcTeam\" tt ON tt.id = h.\"toTeamId\""
                + " LEFT JOIN \"User\" fu ON fu.id = h.\"fromResponsibleUserId\" LEFT JOIN \"User\" tu ON tu.id ="
                + " h.\"toResponsibleUserId\" LEFT JOIN \"User\" u ON u.id = h.\"userId\" WHERE h.\"alertId\" = ? AND"
                + " h.\"organizationId\" = ? ORDER BY h.\"createdAt\", h.id").params(id, QcDb.org())));
        return a;
    }

    // ================================================================== create

    /** A manual alert: free, from a check, or from a source document. */
    @Transactional
    public Map<String, Object> create(AlertInput in) {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> check = null;
        if (QcUtil.has(in.checkId())) {
            check = db.require(QcCheck.class, in.checkId(), false, "Quality check not found");
            fromCheck(data, check);
        }
        if (QcUtil.has(in.sourceType()) != QcUtil.has(in.sourceId())) {
            throw ApiException.badRequest("sourceType and sourceId go together.");
        }
        if (QcUtil.has(in.sourceType())) {
            String table = QcCheckService.SOURCE_TABLES.get(in.sourceType());
            if (!db.exists(table, in.sourceId())) {
                throw ApiException.badRequest("Source document not found.");
            }
            data.put("sourceType", in.sourceType());
            data.put("sourceId", in.sourceId());
        }
        put(data, "productId", ref("Product", in.productId(), "Product not found"));
        put(data, "batchNumber", QcUtil.trim(in.batchNumber()));
        put(data, "workCenterId", ref("WorkCenter", in.workCenterId(), "Work centre not found"));
        put(data, "supplierPartnerId", ref("Partner", in.supplierPartnerId(), "Supplier not found"));
        put(data, "failureClassId", requireRow(QcFailureClass.class, in.failureClassId(), "Failure class not found"));
        put(data, "rootCauseId", requireRow(QcRootCause.class, in.rootCauseId(), "Root cause not found"));
        put(data, "teamId", requireRow(QcTeam.class, in.teamId(), "Quality team not found"));
        put(data, "responsibleUserId", ref("User", in.responsibleUserId(), "Responsible user not found"));
        data.put("title", in.title().trim());
        data.put("description", QcUtil.trim(in.description()));
        data.put("priority", in.priority() == null ? 0 : in.priority());
        data.put("correctiveAction", QcUtil.trim(in.correctiveAction()));
        data.put("preventiveAction", QcUtil.trim(in.preventiveAction()));
        data.put("origin", "manual");
        Map<String, Object> a = insert(data, "Raised by hand");
        String id = QcUtil.str(a, "id");
        if (in.tagIds() != null) {
            replaceTags(id, in.tagIds());
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, Obj.of("alertNumber", a.get("alertNumber"),
                "origin", "manual", "checkId", data.get("checkId"))));
        return get(id);
    }

    /**
     * The automatic alert of a failed generated check whose control point has {@code autoAlert} (ADR-S087 §8): at most
     * one per check, even when the check fails again after a reset (unique index, {@code ON CONFLICT DO NOTHING}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void autoForFailedCheck(Map<String, Object> check) {
        if (!"generated".equals(QcUtil.str(check, "origin")) || check.get("controlPointId") == null) {
            return;
        }
        Map<String, Object> point = db.byId(QcControlPoint.class, QcUtil.str(check, "controlPointId"), false);
        if (point == null || !QcUtil.bool(point, "autoAlert")) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        fromCheck(data, check);
        data.put("title", "Failed: " + check.get("title"));
        String message = QcUtil.str(point, "failureMessage");
        data.put("description", message);
        data.put("priority", QcUtil.bool(check, "blocking") || QcUtil.bool(check, "blockOnFailure") ? 2 : 1);
        data.put("origin", "auto");
        String stageId = QcUtil.str(config.firstStage(), "id");
        String id = UUID.randomUUID().toString();
        int inserted = db.jdbc().sql("INSERT INTO \"QcAlert\" (id, \"organizationId\", \"alertNumber\", title, description,"
                + " origin, \"stageId\", priority, \"teamId\", \"responsibleUserId\", \"checkId\", \"controlPointId\","
                + " \"productId\", \"variantId\", \"batchNumber\", \"workCenterId\", \"sourceType\", \"sourceId\","
                + " \"sourceRef\", \"supplierPartnerId\", \"failureClassId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, 'auto',"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (\"organizationId\", \"checkId\") WHERE"
                + " origin = 'auto' DO NOTHING")
                .params(id, QcDb.org(), "~" + id, data.get("title"), data.get("description"), stageId,
                        data.get("priority"), data.get("teamId"), data.get("responsibleUserId"), data.get("checkId"),
                        data.get("controlPointId"), data.get("productId"), data.get("variantId"), data.get("batchNumber"),
                        data.get("workCenterId"), data.get("sourceType"), data.get("sourceId"), data.get("sourceRef"),
                        data.get("supplierPartnerId"), data.get("failureClassId"), TenantContext.userId())
                .update();
        if (inserted == 0) {
            return;
        }
        // The number is taken only for an alert that was created, so a retry never burns a number.
        String number = sequence.next("quality_control_alert", "QCA-", 5);
        db.update(QcAlert.class, id, Obj.of("alertNumber", number));
        stageHistory(id, null, stageId, "Raised automatically: check " + check.get("checkNumber") + " failed");
        Map<String, Object> a = db.require(QcAlert.class, id, false, "Quality alert not found");
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, Obj.of("alertNumber", number, "origin", "auto",
                "checkId", check.get("id"))));
        publish(DomainEvents.QUALITY_CONTROL_ALERT_CREATED, a, null);
    }

    private void fromCheck(Map<String, Object> data, Map<String, Object> c) {
        data.put("checkId", c.get("id"));
        for (String f : List.of("controlPointId", "productId", "variantId", "batchNumber", "workCenterId", "sourceRef",
                "supplierPartnerId", "failureClassId", "teamId", "responsibleUserId")) {
            data.put(f, c.get(f));
        }
        if (!"manual".equals(QcUtil.str(c, "sourceType"))) {
            data.put("sourceType", c.get("sourceType"));
            data.put("sourceId", c.get("sourceId"));
        }
    }

    private Map<String, Object> insert(Map<String, Object> data, String comment) {
        String stageId = QcUtil.str(config.firstStage(), "id");
        data.put("alertNumber", sequence.next("quality_control_alert", "QCA-", 5));
        data.put("stageId", stageId);
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> a = db.insert(QcAlert.class, data);
        stageHistory(QcUtil.str(a, "id"), null, stageId, comment);
        publish(DomainEvents.QUALITY_CONTROL_ALERT_CREATED, a, null);
        return a;
    }

    // ================================================================== edit, move, escalate

    @Transactional
    public Map<String, Object> update(String id, AlertPatch in) {
        Map<String, Object> before = db.require(QcAlert.class, id, true, "Quality alert not found");
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.title() != null) {
            data.put("title", in.title().trim());
        }
        if (in.description() != null) {
            data.put("description", QcUtil.trim(in.description()));
        }
        if (in.priority() != null) {
            data.put("priority", in.priority());
        }
        if (in.correctiveAction() != null) {
            data.put("correctiveAction", QcUtil.trim(in.correctiveAction()));
        }
        if (in.preventiveAction() != null) {
            data.put("preventiveAction", QcUtil.trim(in.preventiveAction()));
        }
        if (in.failureClassId() != null) {
            data.put("failureClassId", requireRow(QcFailureClass.class, blankToNull(in.failureClassId()),
                    "Failure class not found"));
        }
        if (in.rootCauseId() != null) {
            data.put("rootCauseId", requireRow(QcRootCause.class, blankToNull(in.rootCauseId()), "Root cause not found"));
        }
        if (in.correctionCheckId() != null) {
            String cc = blankToNull(in.correctionCheckId());
            if (cc != null) {
                Map<String, Object> corr = db.require(QcCheck.class, cc, false, "Quality check not found");
                if (!"correction".equals(QcUtil.str(corr, "origin"))
                        || !Objects.equals(corr.get("correctsCheckId"), before.get("checkId"))) {
                    throw ApiException.badRequest("Link a correction check of this alert's check.");
                }
            }
            data.put("correctionCheckId", cc);
        }
        boolean assigned = false;
        if (in.teamId() != null) {
            String team = requireRow(QcTeam.class, blankToNull(in.teamId()), "Quality team not found");
            assigned |= !Objects.equals(team, before.get("teamId"));
            data.put("teamId", team);
        }
        if (in.responsibleUserId() != null) {
            String user = ref("User", blankToNull(in.responsibleUserId()), "Responsible user not found");
            assigned |= !Objects.equals(user, before.get("responsibleUserId"));
            data.put("responsibleUserId", user);
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(QcAlert.class, id, data);
        if (assigned) {
            db.insert(QcAlertAssignmentHistory.class, Obj.of("alertId", id, "fromTeamId", before.get("teamId"),
                    "toTeamId", after.get("teamId"), "fromResponsibleUserId", before.get("responsibleUserId"),
                    "toResponsibleUserId", after.get("responsibleUserId"), "userId", TenantContext.userId()));
            publish(DomainEvents.QUALITY_CONTROL_ALERT_ASSIGNED, after, null);
        }
        if (in.tagIds() != null) {
            replaceTags(id, in.tagIds());
        }
        Map<String, Object> diff = new LinkedHashMap<>(data);
        diff.remove("updatedBy");
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, diff));
        return get(id);
    }

    /** Moves an alert to a stage (a closed stage resolves it); every move is kept in the stage history. */
    @Transactional
    public Map<String, Object> move(String id, MoveInput in) {
        Map<String, Object> before = db.require(QcAlert.class, id, true, "Quality alert not found");
        Map<String, Object> stage = db.require(QcAlertStage.class, in.stageId(), false, "Alert stage not found");
        if (!QcUtil.bool(stage, "isActive")) {
            throw ApiException.badRequest("Alert stage " + stage.get("name") + " is inactive.");
        }
        String from = QcUtil.str(before, "stageId");
        if (in.stageId().equals(from)) {
            return get(id);
        }
        boolean closed = QcUtil.bool(stage, "isClosed");
        Map<String, Object> after = db.update(QcAlert.class, id, Obj.of("stageId", in.stageId(), "closedAt",
                closed ? (before.get("closedAt") != null ? before.get("closedAt") : Instant.now()) : null, "updatedBy",
                TenantContext.userId()));
        stageHistory(id, from, in.stageId(), QcUtil.trim(in.comment()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", Obj.of("stageId", from), Obj.of("op", "move",
                "stageId", in.stageId(), "closed", closed)));
        publish(DomainEvents.QUALITY_CONTROL_ALERT_STAGE_CHANGED, after, from);
        return get(id);
    }

    /**
     * Escalates an alert to a nonconformity (ADR-S013) through {@link QualityNcrService#create}; idempotent: the alert
     * row is locked and an alert that already has an NCR returns it ({@code created = false}).
     */
    @Transactional
    public Map<String, Object> escalate(String id, EscalateInput in) {
        Map<String, Object> a = db.require(QcAlert.class, id, true, "Quality alert not found");
        if (a.get("ncrId") != null) {
            return Obj.of("created", false, "ncrId", a.get("ncrId"), "alert", get(id));
        }
        Map<String, Object> check = a.get("checkId") == null ? null
                : db.byId(QcCheck.class, QcUtil.str(a, "checkId"), false);
        Map<String, Object> root = a.get("rootCauseId") == null ? null
                : db.byId(QcRootCause.class, QcUtil.str(a, "rootCauseId"), false);
        List<String> description = new ArrayList<>();
        if (a.get("description") != null) {
            description.add(QcUtil.str(a, "description"));
        }
        if (a.get("correctiveAction") != null) {
            description.add("Proposed corrective action: " + a.get("correctiveAction"));
        }
        if (a.get("preventiveAction") != null) {
            description.add("Proposed preventive action: " + a.get("preventiveAction"));
        }
        String rootCause = root == null ? null : root.get("description") == null ? QcUtil.str(root, "name")
                : root.get("name") + ": " + root.get("description");
        BigDecimal affected = check == null || check.get("failedQty") == null ? null : QcUtil.dec(check, "failedQty");
        String title = a.get("alertNumber") + ": " + a.get("title");
        Map<String, Object> ncr = ncrs.create(new NcrInput(title.length() > 200 ? title.substring(0, 200) : title,
                description.isEmpty() ? null : String.join("\n\n", description), ncrSource(check),
                in == null || in.severity() == null ? "minor" : in.severity(), null, QcUtil.str(a, "productId"),
                QcUtil.str(a, "batchNumber"), QcUtil.str(a, "supplierPartnerId"), null, workCenter(a), affected, null,
                rootCause, in == null || !QcUtil.has(in.ownerUserId()) ? QcUtil.str(a, "responsibleUserId")
                        : in.ownerUserId()));
        db.update(QcAlert.class, id, Obj.of("ncrId", ncr.get("id"), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", null, Obj.of("op", "escalate", "ncrId",
                ncr.get("id"), "ncrNumber", ncr.get("ncrNumber"))));
        return Obj.of("created", true, "ncrId", ncr.get("id"), "ncrNumber", ncr.get("ncrNumber"), "alert", get(id));
    }

    /** The NCR source of the ADR-S013 module from the check's operation. */
    private static String ncrSource(Map<String, Object> check) {
        if (check == null || check.get("operation") == null) {
            return "internal";
        }
        return switch (QcUtil.str(check, "operation")) {
            case "receipt" -> "incoming";
            case "manufacturing", "work_order" -> "in_process";
            case "delivery" -> "final";
            default -> "internal";
        };
    }

    /** A work centre reference the NCR accepts (it validates against WorkCenter). */
    private String workCenter(Map<String, Object> a) {
        String wc = QcUtil.str(a, "workCenterId");
        return wc != null && db.exists("WorkCenter", wc) ? wc : null;
    }

    // ================================================================== helpers

    private void stageHistory(String alertId, String from, String to, String comment) {
        db.insert(QcAlertStageHistory.class, Obj.of("alertId", alertId, "fromStageId", from, "toStageId", to, "comment",
                comment, "userId", TenantContext.userId()));
    }

    private void replaceTags(String alertId, List<String> tagIds) {
        db.jdbc().sql("DELETE FROM \"QcAlertTag\" WHERE \"alertId\" = ? AND \"organizationId\" = ?")
                .params(alertId, QcDb.org()).update();
        for (String tag : new LinkedHashSet<>(tagIds)) {
            db.require(QcTag.class, tag, false, "Tag not found");
            db.insert(QcAlertTag.class, Obj.of("alertId", alertId, "tagId", tag));
        }
    }

    private void withTags(List<Map<String, Object>> alerts) {
        if (alerts.isEmpty()) {
            return;
        }
        String[] ids = alerts.stream().map(a -> QcUtil.str(a, "id")).toArray(String[]::new);
        Map<String, List<Map<String, Object>>> byAlert = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT x.\"alertId\", t.id, t.name, t.color FROM"
                + " \"QcAlertTag\" x JOIN \"QcTag\" t ON t.id = x.\"tagId\" WHERE x.\"organizationId\" = ? AND"
                + " x.\"alertId\" = ANY(?) ORDER BY lower(t.name)").params(QcDb.org(), ids))) {
            byAlert.computeIfAbsent(QcUtil.str(r, "alertId"), k -> new ArrayList<>())
                    .add(Obj.of("id", r.get("id"), "name", r.get("name"), "color", r.get("color")));
        }
        alerts.forEach(a -> a.put("tags", byAlert.getOrDefault(QcUtil.str(a, "id"), List.of())));
    }

    private void publish(String event, Map<String, Object> a, String fromStage) {
        Map<String, Object> stage = db.byId(QcAlertStage.class, QcUtil.str(a, "stageId"), false);
        Map<String, Object> payload = Obj.of("organizationId", QcDb.org(), "alertId", a.get("id"), "alertNumber",
                a.get("alertNumber"), "origin", a.get("origin"), "checkId", a.get("checkId"), "stageId",
                a.get("stageId"), "teamId", a.get("teamId"), "responsibleUserId", a.get("responsibleUserId"));
        if (fromStage != null) {
            payload.put("fromStageId", fromStage);
            payload.put("closed", stage != null && QcUtil.bool(stage, "isClosed"));
            payload.put("stageName", stage == null ? null : stage.get("name"));
        }
        events.publishOnCommit(event, payload);
    }

    private String ref(String table, String id, String message) {
        if (!QcUtil.has(id)) {
            return null;
        }
        if (!db.exists(table, id)) {
            throw ApiException.badRequest(message);
        }
        return id;
    }

    private String requireRow(Class<?> entity, String id, String message) {
        if (!QcUtil.has(id)) {
            return null;
        }
        db.require(entity, id, false, message);
        return id;
    }

    private static void put(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
