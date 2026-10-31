package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateOrder;
import com.inventoryerp.manufacturing.application.OrderRollup.Child;
import com.inventoryerp.manufacturing.application.OrderRollup.Rolled;
import com.inventoryerp.manufacturing.application.StageCommands.ChildOrders;
import com.inventoryerp.manufacturing.application.StageCommands.CreateMultistageOrder;
import com.inventoryerp.manufacturing.application.StageCommands.InitializeStages;
import com.inventoryerp.manufacturing.domain.ProductionMaterial;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.ProductionOrderPeg;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-level production (ADR-S011 §2): a parent order creates and pegs child orders for its components that have an
 * active BOM (sub-assemblies, intermediates), recursively; the order tree rolls statuses and progress up
 * ({@link OrderRollup}). Children deliver into the parent's production location, so the parent's stages consume
 * their output like any other material.
 */
@Service
public class MultiLevelProductionService {

    static final int DEFAULT_LEVELS = 5;
    private static final int MAX_LEVELS = 10;

    private final MfgDb db;
    private final ProductionService production;
    private final BomService boms;
    private final StageExecutionService stages;
    private final UomConversionService uom;
    private final AuditService audit;

    public MultiLevelProductionService(MfgDb db, ProductionService production, BomService boms,
            StageExecutionService stages, UomConversionService uom, AuditService audit) {
        this.db = db;
        this.production = production;
        this.boms = boms;
        this.stages = stages;
        this.uom = uom;
        this.audit = audit;
    }

    /** Create a production order, stage it from its routing and (optionally) create its child orders. */
    @Transactional
    public Map<String, Object> createMultistage(CreateMultistageOrder dto) {
        Map<String, Object> order = production.createOrder(dto.order());
        String id = str(order, "id");
        stages.initialize(id, new InitializeStages(null));
        if (Boolean.TRUE.equals(dto.createChildOrders())) {
            childOrders(id, new ChildOrders(dto.netAgainstStock(), true, null));
        }
        Map<String, Object> view = stages.view(id);
        view.put("tree", tree(id));
        return view;
    }

    /**
     * For each material with an active BOM: a child order for the required quantity (net of the stock at the parent's
     * production location when {@code netAgainstStock}), pegged to the material line, staged when its routing has a
     * stage set-up, recursing up to {@code maxLevels}. Materials already pegged are skipped (idempotent).
     */
    @Transactional
    public Map<String, Object> childOrders(String orderId, ChildOrders dto) {
        Map<String, Object> parent = db.byId(ProductionOrder.class, orderId);
        if (parent == null) {
            throw ApiException.notFound("Production order not found");
        }
        if (!"draft".equals(parent.get("status")) && !"confirmed".equals(parent.get("status"))) {
            throw ApiException.badRequest("Child orders can only be created for a draft or confirmed order (order "
                    + parent.get("orderCode") + " is " + parent.get("status") + ").");
        }
        int levels = dto == null || dto.maxLevels() == null ? DEFAULT_LEVELS
                : Math.max(1, Math.min(MAX_LEVELS, dto.maxLevels()));
        boolean net = dto != null && Boolean.TRUE.equals(dto.netAgainstStock());
        boolean stage = dto == null || dto.initializeStages() == null || dto.initializeStages();
        Set<String> path = new HashSet<>();
        path.add(str(parent, "outputProductId"));
        int created = explode(parent, 1, levels, net, stage, path);
        audit.recordInTx(AuditInput.of("ProductionOrder", orderId, "create", null, Obj.of("orderCode",
                parent.get("orderCode"), "childOrders", created)));
        return tree(orderId);
    }

    private int explode(Map<String, Object> parent, int level, int maxLevels, boolean net, boolean stage,
            Set<String> path) {
        int created = 0;
        String parentId = str(parent, "id");
        for (Map<String, Object> m : db.children(ProductionMaterial.class, "orderId", parentId, "sequence ASC")) {
            String productId = str(m, "productId");
            if (path.contains(productId)) {
                continue; // cycle guard
            }
            boolean pegged = db.jdbc().sql("SELECT count(*) FROM \"ProductionOrderPeg\" WHERE \"organizationId\" = ?"
                    + " AND \"parentMaterialId\" = ?").params(MfgDb.org(), m.get("id")).query(Long.class).single() > 0;
            if (pegged) {
                continue;
            }
            Map<String, Object> bom = boms.findActive(productId, str(m, "variantId"));
            if (bom == null) {
                continue;
            }
            Map<String, Object> product = production.getProduct(productId);
            BigDecimal need = uom.toBase(dec(m, "qtyPlanned"), str(m, "uomId"), productId, str(product, "uomId"), true);
            if (net) {
                BigDecimal onHand = db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE"
                        + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ?")
                        .params(MfgDb.org(), productId, parent.get("locationId")).query(BigDecimal.class).single();
                need = need.subtract(onHand.max(BigDecimal.ZERO));
            }
            if (need.signum() <= 0) {
                continue;
            }
            String location = bom.get("defaultLocationId") != null ? str(bom, "defaultLocationId")
                    : str(parent, "locationId");
            Map<String, Object> child = production.createOrder(new CreateOrder(str(bom, "id"), productId,
                    str(m, "variantId"), location, str(parent, "locationId"), null, need, null, null, null, null,
                    "Component of " + parent.get("orderCode"), null, null));
            String childId = str(child, "id");
            db.update(ProductionOrder.class, childId, Obj.of("parentOrderId", parentId));
            db.insert(ProductionOrderPeg.class, Obj.of("parentOrderId", parentId, "childOrderId", childId,
                    "parentMaterialId", m.get("id"), "productId", productId, "quantity", need, "level", level));
            created++;
            if (stage && hasStageSetup(bom)) {
                stages.initialize(childId, new InitializeStages(null));
            }
            if (level < maxLevels) {
                Set<String> next = new HashSet<>(path);
                next.add(productId);
                created += explode(db.byId(ProductionOrder.class, childId), level + 1, maxLevels, net, stage, next);
            }
        }
        return created;
    }

    private boolean hasStageSetup(Map<String, Object> bom) {
        if (bom.get("routingId") == null) {
            return false;
        }
        return db.jdbc().sql("SELECT count(*) FROM \"RoutingStageSetup\" s JOIN \"RoutingOperation\" o"
                + " ON o.id = s.\"routingOperationId\" WHERE s.\"organizationId\" = ? AND o.\"routingId\" = ?")
                .params(MfgDb.org(), bom.get("routingId")).query(Long.class).single() > 0;
    }

    /** The order and its pegged descendants with rolled-up status and progress. */
    @Transactional(readOnly = true)
    public Map<String, Object> tree(String orderId) {
        Map<String, Object> order = db.byId(ProductionOrder.class, orderId);
        if (order == null) {
            throw ApiException.notFound("Production order not found");
        }
        return node(order, null, 0);
    }

    private Map<String, Object> node(Map<String, Object> order, Map<String, Object> peg, int depth) {
        String id = str(order, "id");
        Map<String, Object> counts = SqlRows.one(db.jdbc().sql("SELECT count(*) AS n, count(*) FILTER (WHERE status ="
                + " 'completed') AS done FROM \"ProductionOrderStage\" WHERE \"organizationId\" = ? AND \"orderId\" = ?")
                .params(MfgDb.org(), id));
        int n = ((Number) counts.get("n")).intValue();
        int done = ((Number) counts.get("done")).intValue();
        String status = str(order, "status");
        List<Map<String, Object>> children = new ArrayList<>();
        List<Child> rolledChildren = new ArrayList<>();
        if (depth < MAX_LEVELS) {
            for (Map<String, Object> p : db.list(ProductionOrderPeg.class, " AND \"parentOrderId\" = ?"
                    + " ORDER BY \"createdAt\"", id)) {
                Map<String, Object> child = db.byId(ProductionOrder.class, str(p, "childOrderId"));
                if (child == null) {
                    continue;
                }
                Map<String, Object> c = node(child, p, depth + 1);
                children.add(c);
                rolledChildren.add(new Child(str(c, "rolledStatus"), (BigDecimal) c.get("progressPct")));
            }
        }
        BigDecimal own = OrderRollup.ownProgress(status, n, done);
        Rolled r = OrderRollup.roll(status, own, rolledChildren);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("orderCode", order.get("orderCode"));
        out.put("status", status);
        out.put("outputProductId", order.get("outputProductId"));
        out.put("productName", db.jdbc().sql("SELECT name FROM \"Product\" WHERE id = ?")
                .param(order.get("outputProductId")).query(String.class).optional().orElse(null));
        out.put("plannedQty", order.get("plannedQty"));
        out.put("producedQty", order.get("producedQty"));
        out.put("multiStage", n > 0);
        out.put("stages", n);
        out.put("stagesCompleted", done);
        out.put("ownProgressPct", own);
        out.put("rolledStatus", r.status());
        out.put("progressPct", r.progressPct());
        out.put("childrenDone", r.childrenDone());
        out.put("childrenOpen", r.childrenOpen());
        out.put("peg", peg == null ? null : Obj.of("quantity", peg.get("quantity"), "parentMaterialId",
                peg.get("parentMaterialId"), "level", peg.get("level")));
        out.put("children", children);
        return out;
    }
}
