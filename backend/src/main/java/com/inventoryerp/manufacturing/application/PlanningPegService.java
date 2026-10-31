package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.domain.ProductionDemandPeg;
import com.inventoryerp.manufacturing.domain.ProductionOrderPlanning;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demand pegs and the order planning record (ADR-S017): which customer delivery, sales order, forecast or stock policy
 * a plan line and its production order serve. Confirming a plan carries the pegs to the generated order, whose due date
 * becomes the earliest pegged due date and whose priority the highest pegged priority.
 */
@Service
public class PlanningPegService {

    private static final String PEG_SELECT = "SELECT g.*, p.code AS \"productCode\", p.name AS \"productName\","
            + " pa.name AS \"partnerName\", s.name AS \"siteName\", o.\"orderCode\", o.status AS \"orderStatus\","
            + " pl.\"planNumber\" FROM \"ProductionDemandPeg\" g JOIN \"Product\" p ON p.id = g.\"productId\""
            + " LEFT JOIN \"Partner\" pa ON pa.id = g.\"partnerId\" LEFT JOIN \"FleetCustomerSite\" s ON s.id ="
            + " g.\"siteId\""
            + " LEFT JOIN \"ProductionOrder\" o ON o.id = g.\"productionOrderId\""
            + " JOIN \"ProductionPlan\" pl ON pl.id = g.\"planId\"";

    private final MfgDb db;

    public PlanningPegService(MfgDb db) {
        this.db = db;
    }

    /** The planning row of an order, or null. */
    Map<String, Object> planning(String orderId) {
        List<Map<String, Object>> rows = db.list(ProductionOrderPlanning.class, " AND \"productionOrderId\" = ?",
                orderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Create or update the planning row of an order (the caller holds the order row or owns the order). */
    Map<String, Object> upsert(String orderId, Map<String, Object> data) {
        Map<String, Object> existing = planning(orderId);
        Map<String, Object> row = new LinkedHashMap<>(data);
        row.put("updatedBy", TenantContext.userId());
        if (existing != null) {
            return db.update(ProductionOrderPlanning.class, str(existing, "id"), row);
        }
        row.put("productionOrderId", orderId);
        return db.insert(ProductionOrderPlanning.class, row);
    }

    /** A plan line generated its order: the pegs follow, and the order takes their due date and priority. */
    void attachOrder(String planLineId, String orderId) {
        List<Map<String, Object>> pegs = db.list(ProductionDemandPeg.class, " AND \"planLineId\" = ?", planLineId);
        if (pegs.isEmpty()) {
            return;
        }
        Instant due = null;
        int priority = DemandNetting.LOW;
        String partnerId = null;
        String reason = null;
        for (Map<String, Object> g : pegs) {
            Instant at = (Instant) g.get("dueAt");
            boolean firm = DemandNetting.typeClass(str(g, "demandType")) == 0;
            if (due == null || at.isBefore(due)) {
                due = at;
            }
            int rank = ((Number) g.get("priority")).intValue();
            if (rank >= priority && firm) {
                priority = rank;
                partnerId = str(g, "partnerId");
                reason = g.get("demandRef") == null ? null : str(g, "demandRef");
            }
        }
        db.jdbc().sql("UPDATE \"ProductionDemandPeg\" SET \"productionOrderId\" = ? WHERE \"organizationId\" = ?"
                + " AND \"planLineId\" = ?").params(orderId, MfgDb.org(), planLineId).update();
        upsert(orderId, Obj.of("dueAt", due, "priority", priority, "partnerId", partnerId, "priorityReason", reason));
    }

    /** Pegs filtered by order, plan, customer or product, due date first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pegs(String productionOrderId, String planId, String partnerId, String productId) {
        StringBuilder where = new StringBuilder(" WHERE g.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        addFilter(where, params, "productionOrderId", productionOrderId);
        addFilter(where, params, "planId", planId);
        addFilter(where, params, "partnerId", partnerId);
        addFilter(where, params, "productId", productId);
        return SqlRows.list(db.jdbc().sql(PEG_SELECT + where + " ORDER BY g.\"dueAt\", g.priority DESC LIMIT 500")
                .params(params));
    }

    private static void addFilter(StringBuilder where, List<Object> params, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND g.\"").append(column).append("\" = ?");
            params.add(value);
        }
    }
}
