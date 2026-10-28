package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.AssignWorkOrder;
import com.inventoryerp.manufacturing.application.MfgCommands.CompleteWorkOrder;
import com.inventoryerp.manufacturing.domain.Bom;
import com.inventoryerp.manufacturing.domain.ProductionCostComponent;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.Resource;
import com.inventoryerp.manufacturing.domain.Routing;
import com.inventoryerp.manufacturing.domain.RoutingOperation;
import com.inventoryerp.manufacturing.domain.WorkCenter;
import com.inventoryerp.manufacturing.domain.WorkOrder;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Work orders — one routing operation of a production order on the shop floor (port of
 * {@code work-order.service.ts}). Completing one with logged labour rolls a labour/machine cost into the order's
 * overhead, which the production complete leg then absorbs into WIP.
 */
@Service
public class WorkOrderService {

    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    private final MfgDb db;

    public WorkOrderService(MfgDb db) {
        this.db = db;
    }

    /** Materialise the work orders of a production order from its routing (idempotent). */
    @Transactional
    public List<Map<String, Object>> generateForOrder(String productionOrderId) {
        Map<String, Object> order = db.byId(ProductionOrder.class, productionOrderId);
        if (order == null) {
            throw ApiException.notFound("Production order not found");
        }
        long existing = db.jdbc().sql("SELECT count(*) FROM \"WorkOrder\" WHERE \"organizationId\" = ?"
                + " AND \"productionOrderId\" = ?").params(MfgDb.org(), productionOrderId).query(Long.class).single();
        if (existing > 0) {
            return list(productionOrderId);
        }
        // Routing: the BOM's routingId, else one linked by bom/product.
        String routingId = null;
        if (order.get("bomId") != null) {
            Map<String, Object> bom = db.byId(Bom.class, str(order, "bomId"));
            routingId = bom == null ? null : str(bom, "routingId");
        }
        Map<String, Object> routing = routingId != null ? db.byId(Routing.class, routingId) : null;
        if (routing == null) {
            // Prisma drops {bomId: undefined}, so without a BOM the OR matches any active routing.
            List<Map<String, Object>> rows = order.get("bomId") != null
                    ? db.list(Routing.class, " AND (\"bomId\" = ? OR \"productId\" = ?) AND \"isActive\" = true LIMIT 1",
                            order.get("bomId"), order.get("outputProductId"))
                    : db.list(Routing.class, " AND \"isActive\" = true LIMIT 1");
            routing = rows.isEmpty() ? null : rows.getFirst();
        }
        List<Map<String, Object>> ops = routing == null ? List.of()
                : db.children(RoutingOperation.class, "routingId", routing.get("id"), "sequence ASC");
        if (ops.isEmpty()) {
            throw ApiException.badRequest("No routing with operations for this order. Create one first.");
        }
        for (Map<String, Object> op : ops) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("productionOrderId", productionOrderId);
            data.put("routingOperationId", op.get("id"));
            data.put("operationSequence", op.get("sequence"));
            data.put("name", op.get("name"));
            data.put("workCenterId", op.get("workCenterId"));
            data.put("plannedDurationMins", ((Number) op.get("durationMins")).intValue()
                    + ((Number) op.get("setupMins")).intValue());
            data.put("status", "pending");
            data.put("createdBy", TenantContext.userId());
            db.insert(WorkOrder.class, data);
        }
        return list(productionOrderId);
    }

    /** By operation sequence, each with its work centre and resource. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String productionOrderId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> wo : db.list(WorkOrder.class, " AND \"productionOrderId\" = ?"
                + " ORDER BY \"operationSequence\" ASC", productionOrderId)) {
            wo.put("workCenter", one(WorkCenter.class, wo.get("workCenterId")));
            wo.put("resource", one(Resource.class, wo.get("resourceId")));
            out.add(wo);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> assign(String id, AssignWorkOrder dto) {
        getOrThrow(id);
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.resourceId() != null) {
            data.put("resourceId", dto.resourceId());
        }
        if (dto.assignedToId() != null) {
            data.put("assignedToId", dto.assignedToId());
        }
        if (dto.workCenterId() != null) {
            data.put("workCenterId", dto.workCenterId());
        }
        data.put("updatedBy", TenantContext.userId());
        return db.update(WorkOrder.class, id, data);
    }

    @Transactional
    public Map<String, Object> start(String id) {
        Map<String, Object> wo = getOrThrow(id);
        if (!"pending".equals(wo.get("status")) && !"paused".equals(wo.get("status"))) {
            throw ApiException.badRequest("Cannot start a " + wo.get("status") + " work order.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "in_progress");
        data.put("startedAt", wo.get("startedAt") != null ? wo.get("startedAt") : Instant.now());
        data.put("pausedAt", null);
        data.put("updatedBy", TenantContext.userId());
        return db.update(WorkOrder.class, id, data);
    }

    @Transactional
    public Map<String, Object> pause(String id) {
        Map<String, Object> wo = getOrThrow(id);
        if (!"in_progress".equals(wo.get("status"))) {
            throw ApiException.badRequest("Only an in-progress work order can pause.");
        }
        return db.update(WorkOrder.class, id, Obj.of("status", "paused", "pausedAt", Instant.now(), "labourMins",
                labourMins(wo) + elapsedMins(wo.get("startedAt")), "updatedBy", TenantContext.userId()));
    }

    /** Complete; logged labour × the resource's hourly cost rolls into the order's overhead + a cost component. */
    @Transactional
    public Map<String, Object> complete(String id, CompleteWorkOrder dto) {
        Map<String, Object> wo = getOrThrow(id);
        if ("completed".equals(wo.get("status")) || "cancelled".equals(wo.get("status"))) {
            throw ApiException.badRequest("Work order already " + wo.get("status") + ".");
        }
        int labourMins = dto.labourMins() != null ? dto.labourMins()
                : labourMins(wo) + ("in_progress".equals(wo.get("status")) ? elapsedMins(wo.get("startedAt")) : 0);
        Map<String, Object> resource = wo.get("resourceId") == null ? null
                : db.byId(Resource.class, str(wo, "resourceId"));
        BigDecimal labourCost = BomService.mul(Money.div(BigDecimal.valueOf(labourMins), SIXTY),
                resource == null ? BigDecimal.ZERO : dec(resource, "costPerHour"));
        String kind = resource != null && "machine".equals(resource.get("kind")) ? "machine" : "labour";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "completed");
        data.put("completedAt", Instant.now());
        data.put("labourMins", labourMins);
        data.put("qtyGood", dto.qtyGood() != null ? dto.qtyGood() : wo.get("qtyGood"));
        data.put("qtyScrap", dto.qtyScrap() != null ? dto.qtyScrap() : wo.get("qtyScrap"));
        data.put("notes", dto.notes() != null ? dto.notes() : wo.get("notes"));
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> updated = db.update(WorkOrder.class, id, data);

        if (labourCost.signum() > 0) {
            String orderId = str(wo, "productionOrderId");
            db.update(ProductionOrder.class, orderId,
                    Obj.of("overheadCost", new MfgDb.Raw("\"overheadCost\" + ?", labourCost)));
            db.insert(ProductionCostComponent.class, Obj.of("orderId", orderId, "kind", kind, "amount", labourCost,
                    "sourceType", "work_order", "sourceId", id, "notes", labourMins + " min · " + wo.get("name")));
        }
        return updated;
    }

    private static int labourMins(Map<String, Object> wo) {
        return wo.get("labourMins") instanceof Number n ? n.intValue() : 0;
    }

    /** {@code Math.max(0, Math.round((now - from) / 60000))}. */
    private static int elapsedMins(Object from) {
        if (!(from instanceof Instant i)) {
            return 0;
        }
        return (int) Math.max(0, Math.round((System.currentTimeMillis() - i.toEpochMilli()) / 60000.0));
    }

    private Map<String, Object> one(Class<?> entity, Object id) {
        return id == null ? null : db.children(entity, "id", id, null).stream().findFirst().orElse(null);
    }

    private Map<String, Object> getOrThrow(String id) {
        Map<String, Object> wo = db.byId(WorkOrder.class, id);
        if (wo == null) {
            throw ApiException.notFound("Work order not found");
        }
        return wo;
    }
}
