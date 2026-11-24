package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.dms.spi.DmsAttachments;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.qualitycontrol.application.MeasureEvaluator.Outcome;
import com.inventoryerp.qualitycontrol.application.QcCommands.CheckQuery;
import com.inventoryerp.qualitycontrol.application.QcCommands.CorrectionInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.ExecuteInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.ManualCheckInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.ReasonInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.SampleInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.WaiveInput;
import com.inventoryerp.qualitycontrol.domain.QcCheck;
import com.inventoryerp.qualitycontrol.domain.QcCheckEvent;
import com.inventoryerp.qualitycontrol.domain.QcCheckExecution;
import com.inventoryerp.qualitycontrol.domain.QcCheckSample;
import com.inventoryerp.qualitycontrol.domain.QcControlPoint;
import com.inventoryerp.qualitycontrol.domain.QcTeam;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Quality checks (ADR-S087 §2): the requirement ({@code QcCheck}) and its immutable execution revisions
 * ({@code QcCheckExecution} + {@code QcCheckSample}). Every transition appends a {@code QcCheckEvent} and an audit row.
 *
 * <ul>
 *   <li>Execute: {@code pending → passed | failed} (writes revision n). The check row is locked; a second contender
 *       gets 409.</li>
 *   <li>Waive ({@code quality_control:manage}): {@code pending | failed → waived}, reason + disposition.</li>
 *   <li>Reset ({@code manage}): {@code passed | failed | waived → pending}, opening revision n+1; revision n stays.</li>
 *   <li>A pre-completion check whose source is completed ({@code sourceClosed}) can no longer be executed, reset or
 *       waived; a correction check (new, independent) is opened instead.</li>
 *   <li>Manual and correction checks never block (see {@link QcBlockingPolicy}).</li>
 * </ul>
 */
@Service
public class QcCheckService {

    static final String AUDIT_ENTITY = "QcCheck";
    static final String RES_TYPE = "qc_check";
    private static final Map<String, String> SOURCE_TABLES = Map.of("production_order", "ProductionOrder", "work_order",
            "WorkOrder", "goods_receipt", "GoodsReceiptNote", "sales_delivery", "FleetLoad");

    private final QcDb db;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;
    private final QcWorksheetService worksheets;
    private final QcFailureClassService failureClasses;
    private final DmsAttachments attachments;

    public QcCheckService(QcDb db, SequenceService sequence, AuditService audit, EventBus events,
            QcWorksheetService worksheets, QcFailureClassService failureClasses, DmsAttachments attachments) {
        this.db = db;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
        this.worksheets = worksheets;
        this.failureClasses = failureClasses;
        this.attachments = attachments;
    }

    private static final String SELECT = """
            SELECT c.*, p.name AS "productName", p.code AS "productCode", cp.reference AS "controlPointReference",
                   cp.title AS "controlPointTitle", t.name AS "teamName",
                   TRIM(CONCAT(u."firstName", ' ', u."lastName")) AS "responsibleName",
                   fc.code AS "failureClassCode", fc.name AS "failureClassName",
                   orig."checkNumber" AS "correctsCheckNumber"
            FROM "QcCheck" c
            LEFT JOIN "Product" p ON p.id = c."productId" AND p."organizationId" = c."organizationId"
            LEFT JOIN "QcControlPoint" cp ON cp.id = c."controlPointId"
            LEFT JOIN "QcTeam" t ON t.id = c."teamId"
            LEFT JOIN "User" u ON u.id = c."responsibleUserId"
            LEFT JOIN "QcFailureClass" fc ON fc.id = c."failureClassId"
            LEFT JOIN "QcCheck" orig ON orig.id = c."correctsCheckId"
            """;

    // ================================================================== queries

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(CheckQuery q) {
        int page = QcUtil.page(q.page());
        int size = QcUtil.size(q.pageSize());
        StringBuilder where = new StringBuilder(" WHERE c.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(QcDb.org()));
        if (QcUtil.has(q.search())) {
            where.append(" AND (c.\"checkNumber\" ILIKE ? OR c.title ILIKE ? OR c.\"sourceRef\" ILIKE ?"
                    + " OR c.\"batchNumber\" ILIKE ? OR p.name ILIKE ?)");
            for (int i = 0; i < 5; i++) {
                params.add(QcUtil.like(q.search()));
            }
        }
        QcUtil.eq(where, params, "c.status", q.status());
        QcUtil.eq(where, params, "c.origin", q.origin());
        QcUtil.eq(where, params, "c.\"checkType\"", q.checkType());
        QcUtil.eq(where, params, "c.\"sourceType\"", q.sourceType());
        QcUtil.eq(where, params, "c.\"sourceId\"", q.sourceId());
        QcUtil.eq(where, params, "c.\"controlPointId\"", q.controlPointId());
        QcUtil.eq(where, params, "c.\"productId\"", q.productId());
        QcUtil.eq(where, params, "c.\"teamId\"", q.teamId());
        long total = db.jdbc().sql("SELECT count(*) FROM \"QcCheck\" c LEFT JOIN \"Product\" p ON p.id = c.\"productId\""
                + " AND p.\"organizationId\" = c.\"organizationId\"" + where).params(params).query(Long.class).single();
        params.add(size);
        params.add((page - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(SELECT + where
                + " ORDER BY c.\"createdAt\" DESC, c.\"checkNumber\" DESC LIMIT ? OFFSET ?").params(params));
        rows.forEach(QcCheckService::decorate);
        return PageResult.of(rows, page, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> c = SqlRows.one(db.jdbc().sql(SELECT + " WHERE c.id = ? AND c.\"organizationId\" = ?")
                .params(id, QcDb.org()));
        if (c == null) {
            throw ApiException.notFound("Quality check not found");
        }
        decorate(c);
        List<Map<String, Object>> executions = SqlRows.list(db.jdbc().sql("SELECT e.*, fc.name AS \"failureClassName\","
                + " TRIM(CONCAT(u.\"firstName\", ' ', u.\"lastName\")) AS \"executedByName\" FROM \"QcCheckExecution\" e"
                + " LEFT JOIN \"QcFailureClass\" fc ON fc.id = e.\"failureClassId\" LEFT JOIN \"User\" u ON u.id ="
                + " e.\"executedBy\" WHERE e.\"checkId\" = ? AND e.\"organizationId\" = ? ORDER BY e.revision DESC")
                .params(id, QcDb.org()));
        List<Map<String, Object>> samples = db.list(QcCheckSample.class, " AND \"checkId\" = ? ORDER BY revision,"
                + " \"sampleNo\"", id);
        int current = QcUtil.integer(c, "currentRevision");
        for (Map<String, Object> e : executions) {
            String eid = QcUtil.str(e, "id");
            e.put("samples", samples.stream().filter(s -> eid.equals(QcUtil.str(s, "executionId"))).toList());
            e.put("superseded", QcUtil.integer(e, "revision") < current);
        }
        c.put("executions", executions);
        c.put("events", SqlRows.list(db.jdbc().sql("SELECT ev.*, TRIM(CONCAT(u.\"firstName\", ' ', u.\"lastName\"))"
                + " AS \"userName\" FROM \"QcCheckEvent\" ev LEFT JOIN \"User\" u ON u.id = ev.\"userId\""
                + " WHERE ev.\"checkId\" = ? AND ev.\"organizationId\" = ? ORDER BY ev.\"createdAt\", ev.id")
                .params(id, QcDb.org())));
        c.put("corrections", SqlRows.list(db.jdbc().sql("SELECT id, \"checkNumber\", status, \"createdAt\" FROM"
                + " \"QcCheck\" WHERE \"correctsCheckId\" = ? AND \"organizationId\" = ? ORDER BY \"createdAt\"")
                .params(id, QcDb.org())));
        return c;
    }

    /** Counters for the overview page. */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        String org = QcDb.org();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> byStatus = new LinkedHashMap<>();
        for (String s : List.of("pending", "passed", "failed", "waived", "cancelled")) {
            byStatus.put(s, 0L);
        }
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT status, count(*) AS n FROM \"QcCheck\" WHERE"
                + " \"organizationId\" = ? GROUP BY status").params(org))) {
            byStatus.put(QcUtil.str(r, "status"), r.get("n"));
        }
        out.put("byStatus", byStatus);
        out.put("blocking", db.jdbc().sql("SELECT count(*) FROM \"QcCheck\" WHERE \"organizationId\" = ? AND origin ="
                + " 'generated' AND ((status = 'pending' AND blocking) OR (status = 'failed' AND \"blockOnFailure\"))")
                .param(org).query(Long.class).single());
        out.put("failedLast30Days", db.jdbc().sql("SELECT count(*) FROM \"QcCheck\" WHERE \"organizationId\" = ? AND"
                + " status = 'failed' AND \"doneAt\" >= now() - interval '30 days'").param(org).query(Long.class).single());
        out.put("activeControlPoints", db.jdbc().sql("SELECT count(*) FROM \"QcControlPoint\" WHERE \"organizationId\" = ?"
                + " AND \"isActive\"").param(org).query(Long.class).single());
        out.put("quarantineExceptions", db.jdbc().sql("SELECT count(*) FROM \"QcCheck\" WHERE \"organizationId\" = ? AND"
                + " \"quarantineStatus\" = 'failed'").param(org).query(Long.class).single());
        List<Map<String, Object>> recent = SqlRows.list(db.jdbc().sql(SELECT + " WHERE c.\"organizationId\" = ? AND"
                + " c.status = 'failed' ORDER BY c.\"doneAt\" DESC NULLS LAST LIMIT 5").params(org));
        recent.forEach(QcCheckService::decorate);
        out.put("recentFailures", recent);
        List<Map<String, Object>> pending = SqlRows.list(db.jdbc().sql(SELECT + " WHERE c.\"organizationId\" = ? AND"
                + " c.status = 'pending' ORDER BY c.\"createdAt\" LIMIT 10").params(org));
        pending.forEach(QcCheckService::decorate);
        out.put("pending", pending);
        return out;
    }

    // ================================================================== creation

    /** A manual check (ADR-S087 §2): traceable to a source document, never blocking. */
    @Transactional
    public Map<String, Object> createManual(ManualCheckInput in) {
        Map<String, Object> point = null;
        if (in.controlPointId() != null) {
            point = db.byId(QcControlPoint.class, in.controlPointId(), false);
            if (point == null) {
                throw ApiException.badRequest("Control point " + in.controlPointId() + " not found");
            }
            if (!QcUtil.bool(point, "isActive")) {
                throw ApiException.badRequest("Control point " + point.get("reference") + " is not active.");
            }
        }
        String type = point != null ? QcUtil.str(point, "checkType") : in.checkType();
        if (type == null) {
            throw ApiException.badRequest("checkType is required for a check without a control point.");
        }
        String sourceType = QcUtil.or(in.sourceType(), "manual");
        if (!"manual".equals(sourceType)) {
            if (in.sourceId() == null || !db.exists(SOURCE_TABLES.get(sourceType), in.sourceId())) {
                throw ApiException.badRequest("Source document " + in.sourceId() + " not found");
            }
        }
        ref("Product", in.productId(), "Product");
        ref("ProductVariant", in.variantId(), "Variant");
        ref("Partner", in.supplierPartnerId(), "Supplier");
        ref("WorkCenter", in.workCenterId(), "Work center");
        ref("User", in.responsibleUserId(), "User");
        if (in.teamId() != null && db.byId(QcTeam.class, in.teamId(), false) == null) {
            throw ApiException.badRequest("Quality team " + in.teamId() + " not found");
        }
        Map<String, Object> snapshot;
        String templateId;
        boolean failureClassRequired;
        if (point != null) {
            snapshot = snapshotOf(point);
            templateId = point.get("worksheetTemplateKey") != null
                    ? QcUtil.str(worksheets.latestActive(QcUtil.str(point, "worksheetTemplateKey")), "id") : null;
            failureClassRequired = QcUtil.bool(point, "failureClassRequired");
        } else {
            snapshot = manualSnapshot(type, in);
            templateId = null;
            if ("worksheet".equals(type)) {
                if (in.worksheetTemplateId() == null) {
                    throw ApiException.badRequest("A worksheet check needs a worksheet template.");
                }
                Map<String, Object> v = worksheets.requireVersion(in.worksheetTemplateId());
                templateId = QcUtil.str(worksheets.latestActive(QcUtil.str(v, "templateKey")), "id");
            }
            failureClassRequired = Boolean.TRUE.equals(in.failureClassRequired());
        }
        if (templateId != null) {
            Map<String, Object> t = worksheets.requireVersion(templateId);
            snapshot.put("worksheet", Obj.of("templateId", templateId, "templateKey", t.get("templateKey"), "version",
                    t.get("version"), "name", t.get("name"), "fields", t.get("fields")));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkNumber", nextNumber());
        data.put("title", QcUtil.trim(in.title()) != null ? in.title().trim()
                : point != null ? QcUtil.str(point, "title") : "Manual " + type.replace('_', ' ') + " check");
        data.put("controlPointId", point != null ? point.get("id") : null);
        data.put("controlPointVersionId", point != null ? currentVersionId(point) : null);
        data.put("origin", "manual");
        data.put("phase", "pre_completion");
        data.put("operation", point != null ? point.get("operation") : null);
        data.put("checkType", type);
        data.put("sourceType", sourceType);
        data.put("sourceId", "manual".equals(sourceType) ? null : in.sourceId());
        data.put("sourceRef", QcUtil.trim(in.sourceRef()));
        data.put("productId", in.productId());
        data.put("variantId", in.variantId());
        data.put("batchNumber", QcUtil.trim(in.batchNumber()));
        data.put("supplierPartnerId", in.supplierPartnerId());
        data.put("workCenterId", in.workCenterId());
        data.put("teamId", in.teamId() != null ? in.teamId() : point != null ? point.get("teamId") : null);
        data.put("responsibleUserId", in.responsibleUserId() != null ? in.responsibleUserId()
                : point != null ? point.get("responsibleUserId") : null);
        data.put("coveredQty", in.coveredQty());
        data.put("testQty", in.testQty());
        data.put("snapshot", EntityJson.toJson(snapshot));
        data.put("worksheetTemplateId", templateId);
        data.put("blocking", false);
        data.put("blockOnFailure", false);
        data.put("failureClassRequired", failureClassRequired);
        data.put("status", "pending");
        data.put("currentRevision", 1);
        data.put("includeInSpc", Boolean.TRUE.equals(in.includeInSpc()));
        data.put("notes", QcUtil.trim(in.notes()));
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(QcCheck.class, data);
        String id = QcUtil.str(row, "id");
        event(id, "created", null, "pending", 1, null, Obj.of("origin", "manual", "controlPointId",
                data.get("controlPointId")));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, Obj.of("checkNumber", data.get("checkNumber"),
                "origin", "manual", "checkType", type)));
        return get(id);
    }

    /** Requirement snapshot of a control point at check creation (ADR-S087 §1). */
    static Map<String, Object> snapshotOf(Map<String, Object> point) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("controlPointReference", point.get("reference"));
        s.put("controlPointVersion", point.get("version"));
        s.put("instructions", point.get("instructions"));
        s.put("failureMessage", point.get("failureMessage"));
        s.put("norm", point.get("norm"));
        s.put("toleranceMin", point.get("toleranceMin"));
        s.put("toleranceMax", point.get("toleranceMax"));
        s.put("unit", point.get("unit"));
        s.put("decimals", point.get("decimals"));
        return s;
    }

    private static Map<String, Object> manualSnapshot(String type, ManualCheckInput in) {
        if ("measure".equals(type)) {
            if (in.toleranceMin() == null && in.toleranceMax() == null) {
                throw ApiException.badRequest("A measure check needs a minimum or a maximum.");
            }
            if (in.toleranceMin() != null && in.toleranceMax() != null
                    && in.toleranceMin().compareTo(in.toleranceMax()) > 0) {
                throw ApiException.badRequest("The minimum is greater than the maximum.");
            }
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("instructions", QcUtil.trim(in.instructions()));
        if ("measure".equals(type)) {
            s.put("norm", in.norm());
            s.put("toleranceMin", in.toleranceMin());
            s.put("toleranceMax", in.toleranceMax());
            s.put("unit", QcUtil.trim(in.unit()));
            s.put("decimals", in.decimals());
        }
        return s;
    }

    private String currentVersionId(Map<String, Object> point) {
        List<String> ids = db.jdbc().sql("SELECT id FROM \"QcControlPointVersion\" WHERE \"controlPointId\" = ? AND"
                + " \"organizationId\" = ? AND version = ?").params(point.get("id"), QcDb.org(), point.get("version"))
                .query(String.class).list();
        return ids.isEmpty() ? null : ids.getFirst();
    }

    String nextNumber() {
        return sequence.next("quality_control_check", "QCC-", 6);
    }

    // ================================================================== execution

    /** Evaluates a submission without saving it (the "Correct measure / Confirm" step of the UI). */
    @Transactional(readOnly = true)
    public Map<String, Object> evaluate(String id, ExecuteInput in) {
        Map<String, Object> c = db.require(QcCheck.class, id, false, "Quality check not found");
        Computed r = compute(c, in, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", r.result());
        out.put("evaluation", r.evaluation());
        out.put("failureMessage", snapshot(c).path("failureMessage").isNull() ? null
                : snapshot(c).path("failureMessage").asString(null));
        return out;
    }

    @Transactional
    public Map<String, Object> execute(String id, ExecuteInput in) {
        Map<String, Object> c = db.require(QcCheck.class, id, true, "Quality check not found");
        String status = QcUtil.str(c, "status");
        if (!"pending".equals(status)) {
            throw ApiException.conflict("Quality check " + c.get("checkNumber") + " is already " + status + ".");
        }
        requireOpen(c);
        Computed r = compute(c, in, true);
        String failureClassId = null;
        if ("failed".equals(r.result())) {
            if (in.failureClassId() != null) {
                failureClassId = QcUtil.str(failureClasses.requireActive(in.failureClassId()), "id");
            } else if (QcUtil.bool(c, "failureClassRequired")) {
                throw ApiException.badRequest("A failure class is required when this check fails.");
            }
            BigDecimal covered = c.get("coveredQty") != null ? QcUtil.dec(c, "coveredQty") : null;
            if (in.failedQty() != null && covered != null && in.failedQty().compareTo(covered) > 0) {
                throw ApiException.badRequest("The failed quantity exceeds the quantity the check covers.");
            }
        } else if (in.failureClassId() != null || in.failedQty() != null) {
            throw ApiException.badRequest("A failure class and a failed quantity apply to a failed check only.");
        }
        int revision = QcUtil.integer(c, "currentRevision");
        Instant now = Instant.now();
        Map<String, Object> exec = db.insert(QcCheckExecution.class, Obj.of("checkId", id, "revision", revision,
                "result", r.result(), "measureValue", r.measureValue(), "worksheetAnswers",
                r.answers() != null ? EntityJson.toJson(r.answers()) : null, "evaluation", EntityJson.toJson(r.evaluation()),
                "failureClassId", failureClassId, "failedQty", "failed".equals(r.result()) ? in.failedQty() : null,
                "pictureDocumentId", in.pictureDocumentId(), "notes", QcUtil.trim(in.notes()), "executedBy",
                TenantContext.userId(), "executedAt", now));
        int no = 0;
        for (ComputedSample s : r.samples()) {
            db.insert(QcCheckSample.class, Obj.of("checkId", id, "executionId", exec.get("id"), "revision", revision,
                    "sampleNo", ++no, "sampleRef", QcUtil.trim(s.in().sampleRef()), "batchNumber",
                    QcUtil.trim(s.in().batchNumber()), "sampleQty", s.in().sampleQty(), "measureValue",
                    s.in().measureValue(), "result", s.result(), "notes", QcUtil.trim(s.in().notes()), "sampledBy",
                    TenantContext.userId(), "sampledAt", now));
        }
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("status", r.result());
        upd.put("failureClassId", failureClassId);
        upd.put("failedQty", "failed".equals(r.result()) ? in.failedQty() : null);
        upd.put("doneBy", TenantContext.userId());
        upd.put("doneAt", now);
        db.update(QcCheck.class, id, upd);
        event(id, "executed", "pending", r.result(), revision, null, Obj.of("executionId", exec.get("id"), "result",
                r.result(), "measureValue", r.measureValue(), "samples", r.samples().size()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", Obj.of("status", "pending"), Obj.of("op", "execute",
                "status", r.result(), "revision", revision)));
        publish(c, r.result(), revision);
        return get(id);
    }

    private record ComputedSample(SampleInput in, String result) {}

    private record Computed(String result, BigDecimal measureValue, Map<String, Object> evaluation,
            Map<String, Object> answers, List<ComputedSample> samples) {}

    private Computed compute(Map<String, Object> c, ExecuteInput in, boolean persisting) {
        String type = QcUtil.str(c, "checkType");
        JsonNode snap = snapshot(c);
        List<SampleInput> samples = in.samples() != null ? in.samples() : List.of();
        Map<String, Object> evaluation = new LinkedHashMap<>();
        evaluation.put("checkType", type);
        switch (type) {
            case "instructions" -> {
                if ("failed".equals(in.result())) {
                    throw ApiException.badRequest("An instructions check can only be acknowledged.");
                }
                return new Computed("passed", null, Obj.of("checkType", type, "acknowledged", true), null,
                        samples.stream().map(s -> new ComputedSample(s, QcUtil.or(s.result(), "passed"))).toList());
            }
            case "pass_fail", "picture" -> {
                if ("picture".equals(type)) {
                    if (!QcUtil.has(in.pictureDocumentId())) {
                        throw ApiException.badRequest("A picture check needs a photo.");
                    }
                    if (persisting && attachments.list(RES_TYPE, QcUtil.str(c, "id")).stream()
                            .noneMatch(d -> in.pictureDocumentId().equals(String.valueOf(d.get("id"))))) {
                        throw ApiException.badRequest("The photo must be attached to the check first.");
                    }
                }
                boolean anySampleFailed = samples.stream().anyMatch(s -> "failed".equals(s.result()));
                String result = in.result();
                if (result == null) {
                    if ("picture".equals(type)) {
                        result = anySampleFailed ? "failed" : "passed";
                    } else if (!samples.isEmpty() && samples.stream().allMatch(s -> s.result() != null)) {
                        result = anySampleFailed ? "failed" : "passed";
                    } else {
                        throw ApiException.badRequest("result is required.");
                    }
                }
                if ("passed".equals(result) && anySampleFailed) {
                    throw ApiException.badRequest("A failed sample cannot give a passed check.");
                }
                String overall = result;
                evaluation.put("result", overall);
                return new Computed(overall, null, evaluation, null,
                        samples.stream().map(s -> new ComputedSample(s, QcUtil.or(s.result(), overall))).toList());
            }
            case "measure" -> {
                BigDecimal min = decimal(snap, "toleranceMin");
                BigDecimal max = decimal(snap, "toleranceMax");
                Integer decimals = snap.path("decimals").isNumber() ? snap.path("decimals").asInt() : null;
                List<ComputedSample> out = new ArrayList<>();
                List<Map<String, Object>> values = new ArrayList<>();
                boolean passed = true;
                if (samples.isEmpty()) {
                    Outcome o = MeasureEvaluator.evaluate(in.measureValue(), min, max, decimals, "measureValue");
                    passed = o.passed();
                    values.add(Obj.of("value", in.measureValue(), "passed", o.passed(), "reason", o.reason()));
                } else {
                    if (in.measureValue() != null) {
                        throw ApiException.badRequest("Give either measureValue or samples with values, not both.");
                    }
                    int i = 0;
                    for (SampleInput s : samples) {
                        i++;
                        Outcome o = MeasureEvaluator.evaluate(s.measureValue(), min, max, decimals,
                                "Sample " + i + " measureValue");
                        passed &= o.passed();
                        out.add(new ComputedSample(s, o.passed() ? "passed" : "failed"));
                        values.add(Obj.of("sampleNo", i, "value", s.measureValue(), "passed", o.passed(), "reason",
                                o.reason()));
                    }
                }
                evaluation.put("values", values);
                evaluation.put("toleranceMin", min);
                evaluation.put("toleranceMax", max);
                evaluation.put("norm", decimal(snap, "norm"));
                evaluation.put("unit", snap.path("unit").isNull() ? null : snap.path("unit").asString(null));
                return new Computed(passed ? "passed" : "failed", samples.isEmpty() ? in.measureValue() : null,
                        evaluation, null, out);
            }
            case "worksheet" -> {
                JsonNode fields = snap.path("worksheet").path("fields");
                if (!fields.isArray()) {
                    throw ApiException.badRequest("The check has no worksheet template.");
                }
                if (in.worksheetAnswers() == null) {
                    throw ApiException.badRequest("worksheetAnswers is required.");
                }
                WorksheetEvaluator.Result wr = WorksheetEvaluator.evaluate(fields, in.worksheetAnswers());
                Map<String, Object> ev = new LinkedHashMap<>(wr.toMap());
                ev.put("checkType", type);
                String result = wr.passed() ? "passed" : "failed";
                return new Computed(result, null, ev, wr.answers(),
                        samples.stream().map(s -> new ComputedSample(s, QcUtil.or(s.result(), result))).toList());
            }
            default -> throw ApiException.badRequest("Unknown check type " + type);
        }
    }

    // ================================================================== waive / reset / correction / cancel

    @Transactional
    public Map<String, Object> waive(String id, WaiveInput in) {
        Map<String, Object> c = db.require(QcCheck.class, id, true, "Quality check not found");
        String status = QcUtil.str(c, "status");
        if ("passed".equals(status)) {
            throw ApiException.badRequest("A passed check cannot be waived.");
        }
        if (!"pending".equals(status) && !"failed".equals(status)) {
            throw ApiException.conflict("Quality check " + c.get("checkNumber") + " is already " + status + ".");
        }
        requireOpen(c);
        if ("quantity_excluded".equals(in.disposition())) {
            if (in.excludedQty() == null) {
                throw ApiException.badRequest("excludedQty is required for disposition quantity_excluded.");
            }
            BigDecimal covered = c.get("coveredQty") != null ? QcUtil.dec(c, "coveredQty") : null;
            if (covered != null && in.excludedQty().compareTo(covered) > 0) {
                throw ApiException.badRequest("The excluded quantity exceeds the quantity the check covers.");
            }
        } else if (in.excludedQty() != null) {
            throw ApiException.badRequest("excludedQty applies to disposition quantity_excluded only.");
        }
        db.update(QcCheck.class, id, Obj.of("status", "waived", "waivedBy", TenantContext.userId(), "waivedAt",
                Instant.now(), "waiveReason", in.reason().trim(), "waiveDisposition", in.disposition(), "excludedQty",
                in.excludedQty()));
        int revision = QcUtil.integer(c, "currentRevision");
        event(id, "waived", status, "waived", revision, in.reason().trim(), Obj.of("disposition", in.disposition(),
                "excludedQty", in.excludedQty()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "approve", Obj.of("status", status), Obj.of("op", "waive", "status", "waived",
                "reason", in.reason().trim(), "disposition", in.disposition(), "excludedQty", in.excludedQty())));
        publish(c, "waived", revision);
        return get(id);
    }

    @Transactional
    public Map<String, Object> reset(String id, ReasonInput in) {
        Map<String, Object> c = db.require(QcCheck.class, id, true, "Quality check not found");
        String status = QcUtil.str(c, "status");
        if ("pending".equals(status)) {
            throw ApiException.conflict("Quality check " + c.get("checkNumber") + " is already pending.");
        }
        if ("cancelled".equals(status)) {
            throw ApiException.badRequest("A cancelled check cannot be reset.");
        }
        requireOpen(c);
        int revision = QcUtil.integer(c, "currentRevision") + 1;
        Map<String, Object> prior = Obj.of("status", status, "revision", revision - 1, "failureClassId",
                c.get("failureClassId"), "failedQty", c.get("failedQty"), "waiveReason", c.get("waiveReason"),
                "waiveDisposition", c.get("waiveDisposition"), "excludedQty", c.get("excludedQty"), "doneBy",
                c.get("doneBy"), "doneAt", c.get("doneAt"));
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("status", "pending");
        upd.put("currentRevision", revision);
        for (String k : List.of("failureClassId", "failedQty", "waivedBy", "waivedAt", "waiveReason", "waiveDisposition",
                "excludedQty", "doneBy", "doneAt")) {
            upd.put(k, null);
        }
        db.update(QcCheck.class, id, upd);
        event(id, "reset", status, "pending", revision, in.reason().trim(), Obj.of("prior", prior));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "reopen", prior, Obj.of("op", "reset", "status", "pending", "revision", revision,
                "reason", in.reason().trim())));
        return get(id);
    }

    /** Opens an independent correction check for a completed check; the original is never modified. */
    @Transactional
    public Map<String, Object> openCorrection(String id, CorrectionInput in) {
        Map<String, Object> c = db.require(QcCheck.class, id, true, "Quality check not found");
        String status = QcUtil.str(c, "status");
        if (!List.of("passed", "failed", "waived").contains(status)) {
            throw ApiException.badRequest("Only a completed check can be corrected.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkNumber", nextNumber());
        data.put("title", "Correction of " + c.get("checkNumber") + ": " + c.get("title"));
        for (String k : List.of("controlPointId", "controlPointVersionId", "operation", "checkType", "sourceType",
                "sourceId", "sourceLineId", "sourceRef", "workOrderId", "productId", "variantId", "batchNumber",
                "locationId", "supplierPartnerId", "workCenterId", "teamId", "responsibleUserId", "coveredQty", "testQty",
                "worksheetTemplateId", "failureClassRequired", "includeInSpc")) {
            data.put(k, c.get(k));
        }
        data.put("snapshot", EntityJson.toJson(c.get("snapshot")));
        data.put("origin", "correction");
        data.put("phase", "pre_completion");
        data.put("correctsCheckId", id);
        data.put("blocking", false);
        data.put("blockOnFailure", false);
        data.put("status", "pending");
        data.put("currentRevision", 1);
        data.put("notes", QcUtil.trim(in.notes()));
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(QcCheck.class, data);
        String newId = QcUtil.str(row, "id");
        String reason = in.reason().trim();
        event(newId, "created", null, "pending", 1, reason, Obj.of("origin", "correction", "correctsCheckId", id));
        event(id, "correction_opened", status, status, QcUtil.integer(c, "currentRevision"), reason,
                Obj.of("correctionCheckId", newId, "correctionCheckNumber", data.get("checkNumber")));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, newId, "create", Obj.of("correctsCheckId", id),
                Obj.of("op", "correction", "checkNumber", data.get("checkNumber"), "reason", reason)));
        return get(newId);
    }

    /** Cancels a pending manual or correction check; generated checks are cancelled by their source only. */
    @Transactional
    public Map<String, Object> cancel(String id, ReasonInput in) {
        Map<String, Object> c = db.require(QcCheck.class, id, true, "Quality check not found");
        if ("generated".equals(QcUtil.str(c, "origin"))) {
            throw ApiException.badRequest("A generated check is cancelled by its source document only.");
        }
        String status = QcUtil.str(c, "status");
        if (!"pending".equals(status)) {
            throw ApiException.conflict("Quality check " + c.get("checkNumber") + " is already " + status + ".");
        }
        db.update(QcCheck.class, id, Obj.of("status", "cancelled", "cancelReason", in.reason().trim()));
        event(id, "cancelled", "pending", "cancelled", QcUtil.integer(c, "currentRevision"), in.reason().trim(), Map.of());
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "cancel", Obj.of("status", "pending"), Obj.of("status",
                "cancelled", "reason", in.reason().trim())));
        return get(id);
    }

    // ================================================================== helpers

    private static void requireOpen(Map<String, Object> c) {
        if (QcUtil.bool(c, "sourceClosed") && "pre_completion".equals(QcUtil.str(c, "phase"))) {
            throw ApiException.badRequest("Quality check " + c.get("checkNumber") + " is closed: its source document is"
                    + " completed. Open a correction check instead.");
        }
    }

    void event(String checkId, String action, String from, String to, Integer revision, String reason,
            Map<String, Object> payload) {
        db.insert(QcCheckEvent.class, Obj.of("checkId", checkId, "action", action, "fromStatus", from, "toStatus", to,
                "revision", revision, "reason", reason, "payload", EntityJson.toJson(payload), "userId",
                TenantContext.userId()));
    }

    private void publish(Map<String, Object> c, String status, int revision) {
        events.publishOnCommit("failed".equals(status) ? DomainEvents.QUALITY_CONTROL_CHECK_FAILED
                : DomainEvents.QUALITY_CONTROL_CHECK_DONE, Obj.of("organizationId", QcDb.org(), "checkId", c.get("id"),
                "checkNumber", c.get("checkNumber"), "status", status, "revision", revision, "controlPointId",
                c.get("controlPointId"), "sourceType", c.get("sourceType"), "sourceId", c.get("sourceId"), "teamId",
                c.get("teamId"), "responsibleUserId", c.get("responsibleUserId")));
    }

    private static void decorate(Map<String, Object> c) {
        boolean blocks = QcBlockingPolicy.blocks(QcUtil.str(c, "origin"), QcUtil.str(c, "status"),
                QcUtil.bool(c, "blocking"), QcUtil.bool(c, "blockOnFailure"));
        c.put("blocksSource", blocks);
        c.put("canBlock", "generated".equals(QcUtil.str(c, "origin"))
                && QcBlockingPolicy.canBlock(QcUtil.bool(c, "blocking"), QcUtil.bool(c, "blockOnFailure")));
    }

    private static JsonNode snapshot(Map<String, Object> c) {
        Object s = c.get("snapshot");
        return s instanceof JsonNode n ? n : EntityJson.tree(s == null ? "{}" : EntityJson.toJson(s));
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.isNumber() ? v.decimalValue() : new BigDecimal(v.asString());
    }

    private void ref(String table, String id, String label) {
        if (id != null && !db.exists(table, id)) {
            throw ApiException.badRequest(label + " " + id + " not found");
        }
    }
}
