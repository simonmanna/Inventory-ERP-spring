package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.AddPlanLines;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateOrder;
import com.inventoryerp.manufacturing.application.MfgCommands.CreatePlan;
import com.inventoryerp.manufacturing.application.MfgCommands.PlanLineInput;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.ProductionPlan;
import com.inventoryerp.manufacturing.domain.ProductionPlanLine;
import com.inventoryerp.manufacturing.domain.ProductionRequestLine;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Production plans (port of {@code production-plan.service.ts}): consolidate open request lines into fewer, bigger
 * lines and, on confirm, spin each line into a DRAFT production order. Never auto-starts.
 */
@Service
public class ProductionPlanService {

    private static final java.time.format.DateTimeFormatter ISO_MS = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);

    private record Group(String productId, String variantId, String bomId, String uomId, BigDecimal targetQty,
            List<String> requestLineIds) {}

    private final MfgDb db;
    private final SequenceService seq;
    private final ProductionService production;
    private final TransactionTemplate tx;

    public ProductionPlanService(MfgDb db, SequenceService seq, ProductionService production,
            PlatformTransactionManager txm) {
        this.db = db;
        this.seq = seq;
        this.production = production;
        this.tx = new TransactionTemplate(txm);
    }

    /** A DRAFT plan, optionally pre-filled from all open (submitted/approved) request lines. */
    @Transactional
    public Map<String, Object> create(CreatePlan dto) {
        String planNumber = seq.next("production_plan", "PLN-", 5);
        List<Group> consolidated = truthy(dto.fromOpenRequests()) ? consolidateOpenRequests() : List.of();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planNumber", planNumber);
        data.put("name", dto.name());
        data.put("dateFrom", date(dto.dateFrom()));
        data.put("dateTo", date(dto.dateTo()));
        data.put("locationId", dto.locationId());
        data.put("status", "draft");
        data.put("notes", dto.notes());
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> plan = db.insert(ProductionPlan.class, data);
        for (int i = 0; i < consolidated.size(); i++) {
            Group c = consolidated.get(i);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("planId", plan.get("id"));
            line.put("productId", c.productId());
            line.put("variantId", c.variantId());
            line.put("bomId", c.bomId());
            line.put("targetQty", c.targetQty());
            line.put("uomId", c.uomId());
            line.put("lineNumber", i);
            db.insert(ProductionPlanLine.class, line);
        }
        plan.put("lines", db.children(ProductionPlanLine.class, "planId", plan.get("id"), null));

        // Mark the source request lines as consolidated into this plan (Nest stores the plan id).
        for (Group c : consolidated) {
            if (!c.requestLineIds().isEmpty()) {
                db.jdbc().sql("UPDATE \"ProductionRequestLine\" SET \"plannedLineId\" = :plan WHERE id IN (:ids)"
                                + " AND \"organizationId\" = :org")
                        .param("plan", plan.get("id")).param("ids", c.requestLineIds()).param("org", MfgDb.org())
                        .update();
            }
        }
        return plan;
    }

    /** Append lines to a DRAFT plan. */
    @Transactional
    public Map<String, Object> addLines(String id, AddPlanLines dto) {
        Map<String, Object> plan = getOrThrow(id);
        if (!"draft".equals(plan.get("status"))) {
            throw ApiException.badRequest("Only a draft plan can be edited.");
        }
        int existing = lines(plan).size();
        for (int i = 0; i < dto.lines().size(); i++) {
            PlanLineInput l = dto.lines().get(i);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("planId", id);
            line.put("productId", l.productId());
            line.put("variantId", l.variantId());
            line.put("bomId", l.bomId());
            line.put("targetQty", l.targetQty());
            line.put("scheduledDate", date(l.scheduledDate()));
            line.put("notes", l.notes());
            line.put("lineNumber", existing + i);
            db.insert(ProductionPlanLine.class, line);
        }
        return getOrThrow(id);
    }

    /**
     * Confirm: each line becomes a DRAFT production order. Idempotent per line via {@code generatedOrderId}; like
     * Nest (no surrounding transaction) the lines already generated stay generated when a later line fails, so the
     * confirm can be retried.
     */
    public Map<String, Object> confirm(String id) {
        Map<String, Object> plan = tx.execute(s -> getOrThrow(id));
        if ("confirmed".equals(plan.get("status"))) {
            return plan;
        }
        if (!"draft".equals(plan.get("status"))) {
            throw ApiException.badRequest("Cannot confirm a " + plan.get("status") + " plan.");
        }
        for (Map<String, Object> line : lines(plan)) {
            if (line.get("generatedOrderId") != null) {
                continue;
            }
            tx.executeWithoutResult(s -> {
                Object scheduled = line.get("scheduledDate");
                Map<String, Object> order = production.createOrder(CreateOrder.fromPlan(str(line, "bomId"),
                        str(line, "productId"), str(line, "variantId"), dec(line, "targetQty"),
                        str(plan, "locationId"), scheduled instanceof Instant i ? ISO_MS.format(i) : null,
                        "Plan " + plan.get("planNumber")));
                db.update(ProductionPlanLine.class, str(line, "id"), Obj.of("generatedOrderId", order.get("id")));
                db.update(ProductionOrder.class, str(order, "id"), Obj.of("planId", id));
            });
        }
        return tx.execute(s -> {
            // Mark the consolidated requests as planned.
            db.jdbc().sql("UPDATE \"ProductionRequest\" r SET status = 'planned', \"updatedAt\" = " + MfgDb.NOW
                            + " WHERE r.\"organizationId\" = ? AND r.\"deletedAt\" IS NULL"
                            + " AND r.status IN ('submitted', 'approved') AND EXISTS (SELECT 1 FROM"
                            + " \"ProductionRequestLine\" l WHERE l.\"requestId\" = r.id AND l.\"plannedLineId\" = ?)")
                    .params(MfgDb.org(), id).update();
            Map<String, Object> updated = db.update(ProductionPlan.class, id, Obj.of("status", "confirmed",
                    "updatedBy", TenantContext.userId()));
            updated.put("lines", db.children(ProductionPlanLine.class, "planId", id, null));
            return updated;
        });
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> plan = getOrThrow(id);
        if ("cancelled".equals(plan.get("status"))) {
            return plan;
        }
        Map<String, Object> updated = db.update(ProductionPlan.class, id, Obj.of("status", "cancelled", "updatedBy",
                TenantContext.userId()));
        updated.put("lines", db.children(ProductionPlanLine.class, "planId", id, null));
        return updated;
    }

    /** Newest first, each with its lines. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        String extra = status != null && !status.isEmpty() ? " AND status = ?::\"ProductionPlanStatus\"" : "";
        Object[] params = extra.isEmpty() ? new Object[0] : new Object[] {status};
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : db.list(ProductionPlan.class, extra + " ORDER BY \"createdAt\" DESC", params)) {
            p.put("lines", db.children(ProductionPlanLine.class, "planId", p.get("id"), null));
            out.add(p);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return getOrThrow(id);
    }

    // ---- internals

    private Map<String, Object> getOrThrow(String id) {
        Map<String, Object> plan = db.byId(ProductionPlan.class, id);
        if (plan == null) {
            throw ApiException.notFound("Plan not found");
        }
        plan.put("lines", db.children(ProductionPlanLine.class, "planId", id, "\"lineNumber\" ASC"));
        return plan;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> plan) {
        return (List<Map<String, Object>>) plan.get("lines");
    }

    /** Open (submitted/approved), not-yet-consolidated request lines grouped by product+variant, summed. */
    private List<Group> consolidateOpenRequests() {
        List<Map<String, Object>> rows = SqlRows.list(ProductionRequestLine.class, db.jdbc().sql("SELECT l.* FROM"
                        + " \"ProductionRequestLine\" l JOIN \"ProductionRequest\" r ON r.id = l.\"requestId\""
                        + " WHERE l.\"organizationId\" = ? AND l.\"plannedLineId\" IS NULL"
                        + " AND r.status IN ('submitted', 'approved')")
                .param(MfgDb.org()));
        Map<String, Group> groups = new LinkedHashMap<>();
        for (Map<String, Object> l : rows) {
            String key = str(l, "productId") + "::" + (l.get("variantId") != null ? str(l, "variantId") : "");
            Group g = groups.get(key);
            if (g != null) {
                List<String> ids = new ArrayList<>(g.requestLineIds());
                ids.add(str(l, "id"));
                groups.put(key, new Group(g.productId(), g.variantId(), g.bomId() != null ? g.bomId() : str(l, "bomId"),
                        g.uomId(), g.targetQty().add(dec(l, "quantity")), ids));
            } else {
                groups.put(key, new Group(str(l, "productId"), str(l, "variantId"), str(l, "bomId"), str(l, "uomId"),
                        dec(l, "quantity"), List.of(str(l, "id"))));
            }
        }
        return new ArrayList<>(groups.values());
    }

    /** JS truthiness of an unvalidated body value. */
    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        return true;
    }

    private static Instant date(String iso) {
        return iso == null || iso.isEmpty() ? null : EntityPatch.toInstant(iso);
    }
}
