package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.qualitycontrol.application.QcCommands.ControlPointInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.ControlPointQuery;
import com.inventoryerp.qualitycontrol.domain.QcControlPoint;
import com.inventoryerp.qualitycontrol.domain.QcControlPointVersion;
import com.inventoryerp.qualitycontrol.domain.QcTeam;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quality control points (ADR-S087 §1). Every create, update, activation and deactivation appends an immutable
 * {@code QcControlPointVersion} with the full configuration; checks reference the version they were generated under and
 * keep their own snapshot, so a later edit never changes an existing check. A deactivated point generates no new checks;
 * the checks it already generated stay valid (pending ones still block until done or waived).
 */
@Service
public class QcControlPointService {

    static final String DEACTIVATED_NOTE = "Deactivated: no new checks are generated. Checks already generated stay"
            + " valid; pending ones still block until they are done or waived.";
    private static final Set<String> NOT_CONFIG = Set.of("organizationId", "createdAt", "updatedAt", "createdBy");

    private final QcDb db;
    private final SequenceService sequence;
    private final AuditService audit;
    private final QcWorksheetService worksheets;

    public QcControlPointService(QcDb db, SequenceService sequence, AuditService audit, QcWorksheetService worksheets) {
        this.db = db;
        this.sequence = sequence;
        this.audit = audit;
        this.worksheets = worksheets;
    }

    private static final String SELECT = """
            SELECT p.*, t.name AS "teamName", TRIM(CONCAT(u."firstName", ' ', u."lastName")) AS "responsibleName",
                   w.id AS "worksheetTemplateId", w.name AS "worksheetTemplateName", sp.name AS "supplierName",
                   ro.name AS "routingOperationName",
                   loc.name AS "failureLocationName",
                   (SELECT count(*) FROM "QcCheck" c WHERE c."controlPointId" = p.id) AS "checkCount",
                   (SELECT count(*) FROM "QcCheck" c WHERE c."controlPointId" = p.id AND c.status = 'pending')
                       AS "pendingCount"
            FROM "QcControlPoint" p
            LEFT JOIN "QcTeam" t ON t.id = p."teamId"
            LEFT JOIN "User" u ON u.id = p."responsibleUserId"
            LEFT JOIN "QcWorksheetTemplate" w ON w."organizationId" = p."organizationId"
                AND w."templateKey" = p."worksheetTemplateKey" AND w."isLatest"
            LEFT JOIN "Partner" sp ON sp.id = p."supplierPartnerId" AND sp."organizationId" = p."organizationId"
            LEFT JOIN "RoutingOperation" ro ON ro.id = p."routingOperationId" AND ro."organizationId" = p."organizationId"
            LEFT JOIN "InventoryLocation" loc ON loc.id = p."failureLocationId" AND loc."organizationId" = p."organizationId"
            """;

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(ControlPointQuery q) {
        int page = QcUtil.page(q.page());
        int size = QcUtil.size(q.pageSize());
        StringBuilder where = new StringBuilder(" WHERE p.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(QcDb.org()));
        if (QcUtil.has(q.search())) {
            where.append(" AND (p.title ILIKE ? OR p.reference ILIKE ?)");
            params.add(QcUtil.like(q.search()));
            params.add(QcUtil.like(q.search()));
        }
        QcUtil.eq(where, params, "p.operation", q.operation());
        QcUtil.eq(where, params, "p.\"checkType\"", q.checkType());
        QcUtil.eq(where, params, "p.\"isActive\"", q.active());
        long total = db.jdbc().sql("SELECT count(*) FROM \"QcControlPoint\" p" + where).params(params).query(Long.class)
                .single();
        params.add(size);
        params.add((page - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(SELECT + where + " ORDER BY p.\"isActive\" DESC, p.operation,"
                + " p.sequence, p.reference LIMIT ? OFFSET ?").params(params)), page, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> p = SqlRows.one(db.jdbc().sql(SELECT + " WHERE p.id = ? AND p.\"organizationId\" = ?")
                .params(id, QcDb.org()));
        if (p == null) {
            throw ApiException.notFound("Control point not found");
        }
        p.put("versions", SqlRows.list(db.jdbc().sql("SELECT v.id, v.version, v.reason, v.\"changedBy\", v.\"changedAt\","
                + " v.config, TRIM(CONCAT(u.\"firstName\", ' ', u.\"lastName\")) AS \"changedByName\""
                + " FROM \"QcControlPointVersion\" v LEFT JOIN \"User\" u ON u.id = v.\"changedBy\""
                + " WHERE v.\"controlPointId\" = ? AND v.\"organizationId\" = ? ORDER BY v.version DESC")
                .params(id, QcDb.org())));
        if (!QcUtil.bool(p, "isActive")) {
            p.put("note", DEACTIVATED_NOTE);
        }
        return p;
    }

    @Transactional
    public Map<String, Object> create(ControlPointInput in) {
        Map<String, Object> data = normalize(in);
        data.put("reference", sequence.next("quality_control_point", "QCP-", 5));
        data.put("isActive", true);
        data.put("version", 1);
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(QcControlPoint.class, data);
        String id = QcUtil.str(row, "id");
        writeVersion(row, QcUtil.trim(in.reason()) != null ? in.reason().trim() : "created");
        audit.recordInTx(AuditInput.of("QcControlPoint", id, "create", null, config(row)));
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, ControlPointInput in) {
        Map<String, Object> before = db.require(QcControlPoint.class, id, true, "Control point not found");
        Map<String, Object> data = normalize(in);
        data.put("version", QcUtil.integer(before, "version") + 1);
        Map<String, Object> row = db.update(QcControlPoint.class, id, data);
        writeVersion(row, QcUtil.trim(in.reason()));
        audit.recordInTx(AuditInput.of("QcControlPoint", id, "update", config(before), config(row)));
        return get(id);
    }

    @Transactional
    public Map<String, Object> setActive(String id, boolean active, String reason) {
        Map<String, Object> before = db.require(QcControlPoint.class, id, true, "Control point not found");
        if (QcUtil.bool(before, "isActive") == active) {
            return get(id);
        }
        Map<String, Object> row = db.update(QcControlPoint.class, id, Obj.of("isActive", active, "version",
                QcUtil.integer(before, "version") + 1));
        writeVersion(row, QcUtil.trim(reason) != null ? reason.trim() : active ? "activated" : "deactivated");
        audit.recordInTx(AuditInput.of("QcControlPoint", id, "update", Obj.of("isActive", !active),
                Obj.of("op", active ? "activate" : "deactivate", "isActive", active, "reason", reason)));
        return get(id);
    }

    /** The version row a check generated now is pinned to (the point's current version). */
    Map<String, Object> currentVersion(Map<String, Object> point) {
        return SqlRows.one(db.jdbc().sql("SELECT * FROM \"QcControlPointVersion\" WHERE \"controlPointId\" = ? AND"
                + " \"organizationId\" = ? AND version = ?").params(point.get("id"), QcDb.org(), point.get("version")));
    }

    // ------------------------------------------------------------------ validation

    private Map<String, Object> normalize(ControlPointInput in) {
        String op = in.operation();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("title", in.title().trim());
        d.put("operation", op);
        d.put("productIds", EntityJson.toJson(refs("Product", in.productIds(), "Product")));
        d.put("categoryIds", EntityJson.toJson(refs("ProductCategory", in.categoryIds(), "Product category")));
        if (in.supplierPartnerId() != null) {
            if (!"receipt".equals(op)) {
                throw ApiException.badRequest("A supplier applies to receipt control points only.");
            }
            ref("Partner", in.supplierPartnerId(), "Supplier");
        }
        d.put("supplierPartnerId", in.supplierPartnerId());
        if (in.routingOperationId() != null) {
            if (!"work_order".equals(op)) {
                throw ApiException.badRequest("A routing operation applies to work-order control points only.");
            }
            ref("RoutingOperation", in.routingOperationId(), "Routing operation");
        }
        d.put("routingOperationId", in.routingOperationId());
        if (in.teamId() != null && db.byId(QcTeam.class, in.teamId(), false) == null) {
            throw ApiException.badRequest("Quality team " + in.teamId() + " not found");
        }
        d.put("teamId", in.teamId());
        ref("User", in.responsibleUserId(), "User");
        d.put("responsibleUserId", in.responsibleUserId());
        String type = in.checkType();
        d.put("checkType", type);
        d.put("instructions", QcUtil.trim(in.instructions()));
        d.put("failureMessage", QcUtil.trim(in.failureMessage()));
        d.put("failureClassRequired", Boolean.TRUE.equals(in.failureClassRequired()));
        if ("measure".equals(type)) {
            if (in.toleranceMin() == null && in.toleranceMax() == null) {
                throw ApiException.badRequest("A measure control point needs a minimum or a maximum.");
            }
            if (in.toleranceMin() != null && in.toleranceMax() != null && in.toleranceMin().compareTo(in.toleranceMax()) > 0) {
                throw ApiException.badRequest("The minimum is greater than the maximum.");
            }
            if (in.norm() != null && ((in.toleranceMin() != null && in.norm().compareTo(in.toleranceMin()) < 0)
                    || (in.toleranceMax() != null && in.norm().compareTo(in.toleranceMax()) > 0))) {
                throw ApiException.badRequest("The norm must lie between the minimum and the maximum.");
            }
            d.put("norm", in.norm());
            d.put("toleranceMin", in.toleranceMin());
            d.put("toleranceMax", in.toleranceMax());
            d.put("unit", QcUtil.trim(in.unit()));
            d.put("decimals", in.decimals());
        } else {
            for (String k : List.of("norm", "toleranceMin", "toleranceMax", "unit", "decimals")) {
                d.put(k, null);
            }
        }
        if ("worksheet".equals(type)) {
            if (in.worksheetTemplateId() == null) {
                throw ApiException.badRequest("A worksheet control point needs a worksheet template.");
            }
            Map<String, Object> t = worksheets.requireVersion(in.worksheetTemplateId());
            worksheets.latestActive(QcUtil.str(t, "templateKey"));
            d.put("worksheetTemplateKey", t.get("templateKey"));
        } else {
            d.put("worksheetTemplateKey", null);
        }
        String per = QcUtil.or(in.controlPer(), "operation");
        if ("quantity".equals(per) && in.testQtyPct() == null) {
            throw ApiException.badRequest("A quantity control point needs a test percentage.");
        }
        d.put("controlPer", per);
        d.put("testQtyPct", "quantity".equals(per) ? in.testQtyPct() : null);
        d.put("minSampleQty", "quantity".equals(per) ? in.minSampleQty() : null);
        String freq = QcUtil.or(in.frequency(), "all");
        if ("random".equals(freq) && in.randomPct() == null) {
            throw ApiException.badRequest("A random control point needs a percentage of operations.");
        }
        if ("periodic".equals(freq) && (in.periodValue() == null || in.periodUnit() == null)) {
            throw ApiException.badRequest("A periodic control point needs a period value and unit.");
        }
        d.put("frequency", freq);
        d.put("randomPct", "random".equals(freq) ? in.randomPct() : null);
        d.put("periodValue", "periodic".equals(freq) ? in.periodValue() : null);
        d.put("periodUnit", "periodic".equals(freq) ? in.periodUnit() : null);
        d.put("blocking", in.blocking() == null || in.blocking());
        d.put("blockOnFailure", Boolean.TRUE.equals(in.blockOnFailure()));
        d.put("autoAlert", in.autoAlert() == null || in.autoAlert());
        if (in.receiptMode() != null && !"receipt".equals(op)) {
            throw ApiException.badRequest("A receipt mode applies to receipt control points only.");
        }
        d.put("receiptMode", "receipt".equals(op) ? QcUtil.or(in.receiptMode(), "pre_receipt") : null);
        ref("InventoryLocation", in.failureLocationId(), "Failure location");
        d.put("failureLocationId", in.failureLocationId());
        String spc = QcUtil.or(in.spcSubgroup(), "individual");
        if ("consecutive".equals(spc) && in.spcSubgroupSize() == null) {
            throw ApiException.badRequest("Consecutive SPC subgroups need a subgroup size (2 to 10).");
        }
        if ("time_window".equals(spc) && in.spcWindowHours() == null) {
            throw ApiException.badRequest("Time-window SPC subgroups need a window in hours.");
        }
        d.put("spcSubgroup", spc);
        d.put("spcSubgroupSize", "consecutive".equals(spc) ? in.spcSubgroupSize() : null);
        d.put("spcWindowHours", "time_window".equals(spc) ? in.spcWindowHours() : null);
        d.put("spcSplitByWorkCenter", Boolean.TRUE.equals(in.spcSplitByWorkCenter()));
        d.put("sequence", QcUtil.or(in.sequence(), 10));
        return d;
    }

    private List<String> refs(String table, List<String> ids, String label) {
        List<String> out = new ArrayList<>(new LinkedHashSet<>(ids != null ? ids : List.of()));
        for (String id : out) {
            ref(table, id, label);
        }
        return out;
    }

    private void ref(String table, String id, String label) {
        if (id != null && !db.exists(table, id)) {
            throw ApiException.badRequest(label + " " + id + " not found");
        }
    }

    private void writeVersion(Map<String, Object> row, String reason) {
        db.insert(QcControlPointVersion.class, Obj.of("controlPointId", row.get("id"), "version", row.get("version"),
                "config", EntityJson.toJson(config(row)), "reason", reason, "changedBy", TenantContext.userId(),
                "changedAt", java.time.Instant.now()));
    }

    static Map<String, Object> config(Map<String, Object> row) {
        Map<String, Object> c = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (!NOT_CONFIG.contains(e.getKey())) {
                c.put(e.getKey(), e.getValue());
            }
        }
        return c;
    }
}
