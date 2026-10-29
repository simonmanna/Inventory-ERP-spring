package com.inventoryerp.repair.application;

import static com.inventoryerp.repair.application.RepairDb.dateOrNull;
import static com.inventoryerp.repair.application.RepairDb.nz;

import com.inventoryerp.core.domain.Product;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.workflow.LifecycleRegistry;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.repair.domain.RepairDiagnosis;
import com.inventoryerp.repair.domain.RepairLabourType;
import com.inventoryerp.repair.domain.RepairOrder;
import com.inventoryerp.repair.domain.RepairQuotation;
import com.inventoryerp.repair.domain.RepairQuotationLine;
import com.inventoryerp.repair.domain.RepairStatusHistory;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Technician findings and the quotation document (port of {@code repair-diagnosis.service.ts}). Approving a
 * quotation moves a {@code waiting_approval} order to {@code approved}; rejecting asks the lifecycle for
 * {@code waiting_approval --revision--> diagnosis}.
 */
@Service
@ConditionalOnProperty(name = "app.modules.repair", havingValue = "true")
public class RepairDiagnosisService {

    private final RepairDb db;
    private final SequenceService seq;
    private final LifecycleRegistry lifecycles;

    public RepairDiagnosisService(RepairDb db, SequenceService seq, LifecycleRegistry lifecycles) {
        this.db = db;
        this.seq = seq;
        this.lifecycles = lifecycles;
    }

    // ─── diagnosis ───

    public Map<String, Object> createDiagnosis(String orderId, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        String orgId = RepairDb.org();
        Map<String, Object> order = db.byId(RepairOrder.class, orderId);
        if (order == null) {
            throw ApiException.notFound("Repair order not found");
        }
        if ("cancelled".equals(order.get("status")) || "closed".equals(order.get("status"))) {
            throw ApiException.badRequest("Order is closed/cancelled — no new diagnosis");
        }
        return db.inTx(() -> {
            Map<String, Object> created = db.insert(RepairDiagnosis.class, Obj.of("organizationId", orgId,
                    "repairOrderId", orderId, "symptoms", dto.get("symptoms"), "rootCause", dto.get("rootCause"),
                    "faults", nz(dto.get("faults"), List.of()), "estimatedCost", dto.get("estimatedCost"),
                    "estimatedHours", dto.get("estimatedHours"),
                    "recommendedParts", nz(dto.get("recommendedParts"), List.of()), "riskNotes", dto.get("riskNotes"),
                    "diagnosedBy", RepairDb.user()));
            db.update(RepairOrder.class, orderId, Obj.of("diagnosisId", created.get("id"), "updatedBy",
                    RepairDb.user()));
            return created;
        });
    }

    /** {@code update({ data: dto })} — every key must be a diagnosis column. */
    public Map<String, Object> updateDiagnosis(String id, Map<String, Object> dto) {
        ensureExists(RepairDiagnosis.class, id);
        return db.update(RepairDiagnosis.class, id, db.spread(RepairDiagnosis.class, dto));
    }

    // ─── quotations ───

    public List<Map<String, Object>> listQuotations(Map<String, String> query) {
        List<Map<String, Object>> rows = db.where(RepairQuotation.class)
                .eqIf("status", query.get("status"))
                .eqIf("repairOrderId", query.get("repairOrderId"))
                .list("\"createdAt\" DESC", RepairDb.take(query.get("take"), 50, 200),
                        RepairDb.skip(query.get("skip")));
        rows.forEach(this::withLines);
        return rows;
    }

    public Map<String, Object> getQuotation(String id) {
        Map<String, Object> q = db.byId(RepairQuotation.class, id);
        if (q == null) {
            throw ApiException.notFound("Quotation not found");
        }
        return withLines(q);
    }

    private Map<String, Object> withLines(Map<String, Object> q) {
        q.put("lines", db.many(RepairQuotationLine.class, "quotationId", q.get("id"), "\"lineNumber\" ASC"));
        return q;
    }

    /** A draft quotation priced from labour types / product sale prices; the order points at it. */
    public Map<String, Object> createQuotation(String orderId, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        String orgId = RepairDb.org();
        if (db.byId(RepairOrder.class, orderId) == null) {
            throw ApiException.notFound("Repair order not found");
        }
        List<Line> lines = buildLines(PosSql.list(nz(dto.get("lines"), List.of())));
        BigDecimal labourTotal = BigDecimal.ZERO;
        BigDecimal partsTotal = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Line l : lines) {
            if ("labour".equals(l.kind)) {
                labourTotal = labourTotal.add(l.amount);
            }
            if ("part".equals(l.kind)) {
                partsTotal = partsTotal.add(l.amount);
            }
            totalAmount = totalAmount.add(l.amount);
        }
        String quotationNumber = seq.next("repair_quotation", "QT-", 6);
        BigDecimal lt = labourTotal;
        BigDecimal pt = partsTotal;
        BigDecimal tt = totalAmount;
        return db.inTx(() -> {
            Map<String, Object> created = db.insert(RepairQuotation.class, Obj.of("organizationId", orgId,
                    "repairOrderId", orderId, "quotationNumber", quotationNumber, "status", "draft",
                    "revision", nz(dto.get("revision"), 1), "labourTotal", lt,
                    "partsTotal", pt, "taxTotal", BigDecimal.ZERO, "totalAmount", tt,
                    "validUntil", dateOrNull(dto.get("validUntil")), "notes", dto.get("notes"),
                    "createdBy", RepairDb.user()));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int idx = 0; idx < lines.size(); idx++) {
                Line l = lines.get(idx);
                rows.add(db.insert(RepairQuotationLine.class, Obj.of("organizationId", orgId,
                        "quotationId", created.get("id"), "kind", l.kind, "description", l.description,
                        "labourTypeId", l.labourTypeId, "productId", l.productId, "quantity", l.quantity,
                        "unitPrice", l.unitPrice, "taxId", l.taxId, "amount", l.amount,
                        "lineNumber", idx)));
            }
            created.put("lines", rows);
            db.update(RepairOrder.class, orderId, Obj.of("quotationId", created.get("id"), "quotedAt", Instant.now(),
                    "updatedBy", RepairDb.user()));
            return created;
        });
    }

    /** Draft → pending (customer review). */
    public Map<String, Object> submitQuotation(String id) {
        ensureExists(RepairQuotation.class, id);
        return db.update(RepairQuotation.class, id, Obj.of("status", "pending"));
    }

    /**
     * Approve; a {@code waiting_approval} order moves to {@code approved} with a history row. The quotation is
     * re-read under a row lock so two concurrent approvals cannot both pass the status check.
     */
    public Map<String, Object> approveQuotation(String id, Map<String, Object> dto) {
        String orgId = RepairDb.org();
        return db.inTx(() -> {
            Map<String, Object> q = db.lockById(RepairQuotation.class, id);
            if (q == null) {
                throw ApiException.notFound("Quotation not found");
            }
            Object status = q.get("status");
            if (!"pending".equals(status) && !"draft".equals(status)) {
                throw ApiException.badRequest("Quotation is " + status + " — only pending/draft can be approved");
            }
            Map<String, Object> updated = db.update(RepairQuotation.class, id, Obj.of("status", "approved",
                    "approvedAt", Instant.now(), "approvedBy", RepairDb.user()));
            Map<String, Object> order = db.lockById(RepairOrder.class, q.get("repairOrderId"));
            if (order != null && "waiting_approval".equals(order.get("status"))) {
                lifecycles.assertTransition(RepairLifecycles.KEY, "waiting_approval", "approved", "approve");
                db.update(RepairOrder.class, (String) order.get("id"), Obj.of("status", "approved",
                        "approvedAt", Instant.now(), "updatedBy", RepairDb.user()));
                db.insert(RepairStatusHistory.class, Obj.of("organizationId", orgId, "repairOrderId", order.get("id"),
                        "fromStatus", "waiting_approval", "toStatus", "approved", "action", "approve",
                        "note", "Quotation " + q.get("quotationNumber") + " approved", "changedBy", RepairDb.user()));
            }
            return updated;
        });
    }

    /**
     * Reject; a {@code waiting_approval} order is sent back to {@code diagnosis}. The lifecycle has no
     * {@code waiting_approval --revision--> diagnosis} edge, so for such an order the assertion fails (500) and the
     * whole transaction — the rejection included — rolls back, exactly as in Nest.
     */
    public Map<String, Object> rejectQuotation(String id, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        String orgId = RepairDb.org();
        Map<String, Object> q = db.byId(RepairQuotation.class, id);
        if (q == null) {
            throw ApiException.notFound("Quotation not found");
        }
        return db.inTx(() -> {
            Map<String, Object> updated = db.update(RepairQuotation.class, id, Obj.of("status", "rejected",
                    "rejectedAt", Instant.now(), "rejectedReason", dto.get("reason")));
            Map<String, Object> order = db.lockById(RepairOrder.class, q.get("repairOrderId"));
            if (order != null && "waiting_approval".equals(order.get("status"))) {
                lifecycles.assertTransition(RepairLifecycles.KEY, "waiting_approval", "diagnosis", "revision");
                db.update(RepairOrder.class, (String) order.get("id"), Obj.of("status", "diagnosis",
                        "updatedBy", RepairDb.user()));
                db.insert(RepairStatusHistory.class, Obj.of("organizationId", orgId, "repairOrderId", order.get("id"),
                        "fromStatus", "waiting_approval", "toStatus", "diagnosis", "action", "revision",
                        "note", "Quotation " + q.get("quotationNumber") + " rejected — "
                                + RepairDb.s(nz(dto.get("reason"), "revise")),
                        "changedBy", RepairDb.user()));
            }
            return updated;
        });
    }

    /** A new draft quoting the same order with {@code revision + 1}; lines default to the previous quotation's. */
    public Map<String, Object> reviseQuotation(String id, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        Map<String, Object> prev = db.byId(RepairQuotation.class, id);
        if (prev == null) {
            throw ApiException.notFound("Quotation not found");
        }
        Map<String, Object> next = new LinkedHashMap<>(dto);
        next.put("revision", (prev.get("revision") == null ? 1 : ((Number) prev.get("revision")).intValue()) + 1);
        if (dto.get("lines") == null) {
            List<Object> lines = new ArrayList<>();
            for (Map<String, Object> l : db.many(RepairQuotationLine.class, "quotationId", id, null)) {
                lines.add(Obj.of("kind", l.get("kind"), "description", l.get("description"),
                        "labourTypeId", l.get("labourTypeId"), "productId", l.get("productId"),
                        "quantity", l.get("quantity"), "unitPrice", l.get("unitPrice"), "taxId", l.get("taxId")));
            }
            next.put("lines", lines);
        }
        return createQuotation((String) prev.get("repairOrderId"), next);
    }

    public Map<String, Object> deleteQuotation(String id) {
        Map<String, Object> q = db.byId(RepairQuotation.class, id);
        if (q == null) {
            throw ApiException.notFound("Quotation not found");
        }
        if ("approved".equals(q.get("status"))) {
            throw ApiException.badRequest("Approved quotation cannot be deleted");
        }
        return db.update(RepairQuotation.class, id, Obj.of("status", "cancelled"));
    }

    // ─── line building ───

    /** A priced quotation line (exact decimals). */
    record Line(String kind, Object description, Object labourTypeId, Object productId, Object taxId,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {}

    /**
     * Resolve raw lines into priced lines with snapshot descriptions: labour from the labour type (name, price),
     * parts from the product (name, sale price); an explicit {@code unitPrice} always wins. JS number semantics
     * ({@code Number(x)}, {@code Math.round(q * p * 100) / 100}) on exact decimals.
     */
    List<Line> buildLines(List<Object> raw) {
        List<Line> out = new ArrayList<>();
        for (int idx = 0; idx < raw.size(); idx++) {
            Map<String, Object> r = raw.get(idx) instanceof Map<?, ?> ? PosSql.map(raw.get(idx)) : Map.of();
            String kind = "part".equals(r.get("kind")) ? "part" : "labour";
            Object description = r.get("description");
            BigDecimal unitPrice = RepairDb.jsNum(nz(r.get("unitPrice"), 0));
            if ("labour".equals(kind) && PosSql.truthy(r.get("labourTypeId"))) {
                Map<String, Object> lt = db.byId(RepairLabourType.class, r.get("labourTypeId"));
                if (lt != null) {
                    description = nz(description, lt.get("name"));
                    if (r.get("unitPrice") == null) {
                        unitPrice = RepairDb.jsNumOrZero(lt.get("price"));
                    }
                }
            }
            if ("part".equals(kind) && PosSql.truthy(r.get("productId"))) {
                Map<String, Object> p = db.byId(Product.class, r.get("productId"));
                if (p != null) {
                    description = nz(description, p.get("name"));
                    if (r.get("unitPrice") == null) {
                        unitPrice = RepairDb.jsNumOrZero(nz(p.get("salesPrice"), 0));
                    }
                }
            }
            BigDecimal quantity = RepairDb.jsNum(nz(r.get("quantity"), 1));
            if (!RepairDb.positive(quantity)) {
                throw ApiException.badRequest("Line " + (idx + 1) + ": quantity must be positive");
            }
            RepairDb.finite(unitPrice, "unitPrice");
            out.add(new Line(kind, nz(description, "Line " + (idx + 1)), r.get("labourTypeId"), r.get("productId"),
                    r.get("taxId"), quantity, unitPrice, RepairDb.round2(quantity.multiply(unitPrice))));
        }
        return out;
    }

    private void ensureExists(Class<?> entity, String id) {
        if (db.byId(entity, id) == null) {
            throw ApiException.notFound("Record not found");
        }
    }
}
