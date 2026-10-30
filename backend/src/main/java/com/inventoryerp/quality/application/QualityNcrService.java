package com.inventoryerp.quality.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quality.application.QualityCommands.NcrInput;
import com.inventoryerp.quality.application.QualityCommands.NcrPatch;
import com.inventoryerp.quality.application.QualityCommands.NcrQuery;
import com.inventoryerp.quality.application.QualityCommands.NcrStatusChange;
import com.inventoryerp.quality.application.SpecEvaluator.Evaluation;
import com.inventoryerp.quality.application.SpecEvaluator.ParameterOutcome;
import com.inventoryerp.quality.domain.QualityInspectionLot;
import com.inventoryerp.quality.domain.QualityNcr;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nonconformance reports (ADR-S013 §3): {@code open → investigating → action → closed}, cancel from any open state,
 * reopen a closed one. Closing needs every linked CAPA closed / cancelled, and a root cause for major / critical NCRs.
 * A lot that fails with {@code autoNcr} raises one automatically ({@link #raiseForFailedLot}).
 */
@Service
public class QualityNcrService {

    static final String AUDIT_ENTITY = "QualityNcr";

    private static final Map<String, Set<String>> MOVES = Map.of(
            "open", Set.of("investigating", "action", "closed", "cancelled"),
            "investigating", Set.of("action", "closed", "cancelled"),
            "action", Set.of("investigating", "closed", "cancelled"),
            "closed", Set.of("open"),
            "cancelled", Set.of());

    private final QualityDb db;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;

    public QualityNcrService(QualityDb db, SequenceService sequence, AuditService audit, EventBus events) {
        this.db = db;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    private static final String SELECT = """
            SELECT n.*, l."lotNumber", p.name AS "productName", sp.name AS "supplierName", cp.name AS "customerName",
                   wc.name AS "workCenterName", TRIM(CONCAT(o."firstName", ' ', o."lastName")) AS "ownerName",
                   (SELECT count(*) FROM "QualityCapa" c WHERE c."ncrId" = n.id AND c."deletedAt" IS NULL) AS "capaCount"
            FROM "QualityNcr" n
            LEFT JOIN "QualityInspectionLot" l ON l.id = n."lotId"
            LEFT JOIN "Product" p ON p.id = n."productId" AND p."organizationId" = n."organizationId"
            LEFT JOIN "Partner" sp ON sp.id = n."supplierPartnerId" AND sp."organizationId" = n."organizationId"
            LEFT JOIN "Partner" cp ON cp.id = n."customerPartnerId" AND cp."organizationId" = n."organizationId"
            LEFT JOIN "WorkCenter" wc ON wc.id = n."workCenterId" AND wc."organizationId" = n."organizationId"
            LEFT JOIN "User" o ON o.id = n."ownerUserId"
            """;

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(NcrQuery q) {
        int page = Q.page(q.page());
        int size = Q.size(q.pageSize());
        StringBuilder where = new StringBuilder(" WHERE n.\"organizationId\" = ? AND n.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(QualityDb.org()));
        if (Q.has(q.search())) {
            where.append(" AND (n.\"ncrNumber\" ILIKE ? OR n.title ILIKE ? OR n.\"batchNumber\" ILIKE ? OR p.name ILIKE ?)");
            String like = Q.like(q.search());
            for (int i = 0; i < 4; i++) {
                params.add(like);
            }
        }
        Q.eq(where, params, "n.status", q.status());
        Q.eq(where, params, "n.severity", q.severity());
        Q.eq(where, params, "n.source", q.source());
        Q.eq(where, params, "n.\"lotId\"", q.lotId());
        Q.eq(where, params, "n.\"supplierPartnerId\"", q.supplierPartnerId());
        long total = db.jdbc().sql("SELECT count(*) FROM \"QualityNcr\" n LEFT JOIN \"Product\" p ON p.id ="
                + " n.\"productId\" AND p.\"organizationId\" = n.\"organizationId\"" + where).params(params)
                .query(Long.class).single();
        params.add(size);
        params.add((page - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(SELECT + where + " ORDER BY n.\"createdAt\" DESC, n.id"
                + " LIMIT ? OFFSET ?").params(params)), page, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> n = SqlRows.one(db.jdbc().sql(SELECT + " WHERE n.id = ? AND n.\"organizationId\" = ? AND"
                + " n.\"deletedAt\" IS NULL").params(id, QualityDb.org()));
        if (n == null) {
            throw ApiException.notFound("NCR not found");
        }
        n.put("capas", SqlRows.list(db.jdbc().sql("SELECT id, \"capaNumber\", title, status, \"dueDate\", \"ownerUserId\""
                + " FROM \"QualityCapa\" WHERE \"ncrId\" = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " ORDER BY \"createdAt\"").params(id, QualityDb.org())));
        return n;
    }

    @Transactional
    public Map<String, Object> create(NcrInput in) {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> lot = null;
        if (Q.has(in.lotId())) {
            lot = db.require(QualityInspectionLot.class, in.lotId(), false, "Inspection lot not found");
        }
        requireRef("Product", in.productId(), "Product " + in.productId() + " not found");
        requireRef("Partner", in.supplierPartnerId(), "Supplier not found");
        requireRef("Partner", in.customerPartnerId(), "Customer not found");
        requireRef("WorkCenter", in.workCenterId(), "Work centre not found");
        requireRef("User", in.ownerUserId(), "Owner not found");
        data.put("title", in.title().trim());
        data.put("description", Q.trim(in.description()));
        data.put("source", in.source() != null ? in.source() : lot != null ? Q.str(lot, "stage") : "internal");
        data.put("severity", Q.or(in.severity(), "minor"));
        data.put("lotId", Q.trim(in.lotId()));
        data.put("productId", Q.has(in.productId()) ? in.productId() : lot != null ? lot.get("productId") : null);
        data.put("batchNumber", Q.has(in.batchNumber()) ? in.batchNumber().trim()
                : lot != null ? lot.get("batchNumber") : null);
        data.put("supplierPartnerId", Q.has(in.supplierPartnerId()) ? in.supplierPartnerId()
                : lot != null ? lot.get("supplierPartnerId") : null);
        data.put("customerPartnerId", Q.trim(in.customerPartnerId()));
        data.put("workCenterId", Q.has(in.workCenterId()) ? in.workCenterId() : lot != null ? lot.get("workCenterId") : null);
        data.put("quantityAffected", in.quantityAffected());
        data.put("disposition", Q.trim(in.disposition()));
        data.put("rootCause", Q.trim(in.rootCause()));
        data.put("ownerUserId", Q.trim(in.ownerUserId()));
        return insert(data);
    }

    /** NCR for a lot whose evaluation just turned to fail (once per lot while an NCR is open). */
    void raiseForFailedLot(Map<String, Object> lot, Evaluation ev) {
        long open = db.jdbc().sql("SELECT count(*) FROM \"QualityNcr\" WHERE \"organizationId\" = ? AND \"lotId\" = ?"
                + " AND status NOT IN ('closed', 'cancelled') AND \"deletedAt\" IS NULL")
                .params(QualityDb.org(), lot.get("id")).query(Long.class).single();
        if (open > 0) {
            return;
        }
        List<String> failed = new ArrayList<>();
        List<String> detail = new ArrayList<>();
        for (ParameterOutcome p : ev.parameters()) {
            if ("fail".equals(p.status())) {
                failed.add(p.code());
                detail.add(p.code() + " — " + p.name() + ": " + String.join("; ", p.messages()));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", "Lot " + lot.get("lotNumber") + " failed: " + String.join(", ", failed));
        data.put("description", String.join("\n", detail));
        data.put("source", lot.get("stage"));
        data.put("severity", "major");
        data.put("lotId", lot.get("id"));
        data.put("productId", lot.get("productId"));
        data.put("batchNumber", lot.get("batchNumber"));
        data.put("supplierPartnerId", lot.get("supplierPartnerId"));
        data.put("workCenterId", lot.get("workCenterId"));
        data.put("quantityAffected", lot.get("quantity"));
        insert(data);
    }

    private Map<String, Object> insert(Map<String, Object> data) {
        String number = sequence.next("quality_ncr", "NCR-", 5);
        data.put("ncrNumber", number);
        data.put("status", "open");
        data.put("reportedBy", TenantContext.userId());
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        String id = Q.str(db.insert(QualityNcr.class, data), "id");
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, Obj.of("ncrNumber", number, "severity",
                data.get("severity"), "lotId", data.get("lotId"))));
        events.publish(DomainEvents.QUALITY_NCR_RAISED, Obj.of("organizationId", QualityDb.org(), "ncrId", id,
                "ncrNumber", number, "severity", data.get("severity"), "source", data.get("source"), "lotId",
                data.get("lotId"), "productId", data.get("productId"), "supplierPartnerId",
                data.get("supplierPartnerId")));
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, NcrPatch in) {
        Map<String, Object> n = db.require(QualityNcr.class, id, true, "NCR not found");
        if (List.of("closed", "cancelled").contains(Q.str(n, "status"))) {
            throw ApiException.badRequest("NCR " + n.get("ncrNumber") + " is " + n.get("status") + ".");
        }
        requireRef("User", in.ownerUserId(), "Owner not found");
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.title() != null) {
            data.put("title", in.title().trim());
        }
        if (in.description() != null) {
            data.put("description", Q.trim(in.description()));
        }
        if (in.severity() != null) {
            data.put("severity", in.severity());
        }
        if (in.quantityAffected() != null) {
            data.put("quantityAffected", in.quantityAffected());
        }
        if (in.disposition() != null) {
            data.put("disposition", Q.trim(in.disposition()));
        }
        if (in.rootCause() != null) {
            data.put("rootCause", Q.trim(in.rootCause()));
        }
        if (in.ownerUserId() != null) {
            data.put("ownerUserId", Q.trim(in.ownerUserId()));
        }
        data.put("updatedBy", TenantContext.userId());
        db.update(QualityNcr.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", null, Obj.of("ncrNumber", n.get("ncrNumber"))));
        return get(id);
    }

    @Transactional
    public Map<String, Object> changeStatus(String id, NcrStatusChange in) {
        Map<String, Object> n = db.require(QualityNcr.class, id, true, "NCR not found");
        String from = Q.str(n, "status");
        String to = in.status();
        if (from.equals(to)) {
            return get(id);
        }
        if (!MOVES.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.badRequest("NCR " + n.get("ncrNumber") + " cannot move from " + from + " to " + to + ".");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", to);
        if ("closed".equals(to)) {
            List<String> open = db.jdbc().sql("SELECT \"capaNumber\" FROM \"QualityCapa\" WHERE \"ncrId\" = ? AND"
                    + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND status NOT IN ('closed', 'cancelled')"
                    + " ORDER BY \"capaNumber\"").params(id, QualityDb.org()).query(String.class).list();
            if (!open.isEmpty()) {
                throw ApiException.badRequest("NCR " + n.get("ncrNumber") + " has open CAPAs: " + String.join(", ", open)
                        + ".");
            }
            if (!"minor".equals(n.get("severity")) && !Q.has(Q.str(n, "rootCause"))) {
                throw ApiException.badRequest("A major or critical NCR needs a root cause before it can be closed.");
            }
            data.put("closedAt", Instant.now());
            data.put("closedBy", TenantContext.userId());
            data.put("closureNotes", Q.trim(in.notes()));
        } else if ("open".equals(to)) {
            data.put("closedAt", null);
            data.put("closedBy", null);
        }
        data.put("updatedBy", TenantContext.userId());
        db.update(QualityNcr.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "open".equals(to) ? "reopen" : "cancelled".equals(to) ? "cancel"
                : "update", Obj.of("status", from),
                Obj.of("status", to, "notes", Q.trim(in.notes()))));
        return get(id);
    }

    Map<String, Object> require(String id) {
        return db.require(QualityNcr.class, id, false, "NCR not found");
    }

    private void requireRef(String table, String id, String message) {
        if (Q.has(id) && !db.exists(table, id, true)) {
            throw ApiException.notFound(message);
        }
    }
}
