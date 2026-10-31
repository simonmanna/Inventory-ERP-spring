package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.PlanningCommands.ShiftPlanInput;
import com.inventoryerp.manufacturing.application.PlanningCommands.TargetInput;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.ProductionShiftPlan;
import com.inventoryerp.manufacturing.domain.ProductionTarget;
import com.inventoryerp.manufacturing.domain.ShopFloorShift;
import com.inventoryerp.manufacturing.domain.WorkCenter;
import com.inventoryerp.manufacturing.domain.WorkOrder;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shift plans and production targets (Track C 2.5, ADR-S019). A shift plan says what a work centre should make in one
 * shift of one day; a draft can be edited or deleted, a published one is what the crew works to and is shown against
 * the
 * good output posted in that shift. A target is the period level: per work centre, crushing plant or product.
 */
@Service
public class PlanningShiftService {

    private static final String PLAN_SELECT = "SELECT sp.*, s.code AS \"shiftCode\", s.name AS \"shiftName\","
            + " w.code AS \"workCenterCode\", w.name AS \"workCenterName\", o.\"orderCode\", wo.name AS"
            + " \"workOrderName\","
            + " p.name AS \"productName\" FROM \"ProductionShiftPlan\" sp JOIN \"ShopFloorShift\" s ON s.id ="
            + " sp.\"shiftId\""
            + " JOIN \"WorkCenter\" w ON w.id = sp.\"workCenterId\" LEFT JOIN \"ProductionOrder\" o ON o.id ="
            + " sp.\"productionOrderId\" LEFT JOIN \"WorkOrder\" wo ON wo.id = sp.\"workOrderId\""
            + " LEFT JOIN \"Product\" p ON p.id = sp.\"productId\"";

    private final MfgDb db;

    public PlanningShiftService(MfgDb db) {
        this.db = db;
    }

    // ================================================================== shift plans

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> listShiftPlans(String from, String to, String workCenterId, String status,
            Integer page, Integer pageSize) {
        int p = CrushingSupport.page(page);
        int size = CrushingSupport.size(pageSize);
        StringBuilder where = new StringBuilder(" WHERE sp.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        if (CrushingSupport.date(from) != null) {
            where.append(" AND sp.\"shiftDate\" >= ?");
            params.add(java.sql.Date.valueOf(CrushingSupport.date(from)));
        }
        if (CrushingSupport.date(to) != null) {
            where.append(" AND sp.\"shiftDate\" <= ?");
            params.add(java.sql.Date.valueOf(CrushingSupport.date(to)));
        }
        if (CrushingSupport.text(workCenterId) != null) {
            where.append(" AND sp.\"workCenterId\" = ?");
            params.add(workCenterId);
        }
        if (CrushingSupport.text(status) != null) {
            where.append(" AND sp.status = ?");
            params.add(status);
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"ProductionShiftPlan\" sp" + where).params(params)
                .query(Long.class).single();
        params.add(size);
        params.add((p - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(PLAN_SELECT + where
                + " ORDER BY sp.\"shiftDate\", s.\"sortOrder\", w.code LIMIT ? OFFSET ?").params(params));
        for (Map<String, Object> r : rows) {
            withActual(r);
        }
        return PageResult.of(rows, p, size, total);
    }

    /** Adds the good output posted for the plan's shift, and the attainment against its target. */
    void withActual(Map<String, Object> plan) {
        BigDecimal actual = actualQty(plan);
        BigDecimal target = dec(plan, "targetQty");
        plan.put("actualQty", actual);
        plan.put("varianceQty", actual.subtract(target));
        plan.put("attainmentPct", target.signum() <= 0 ? null : actual.multiply(BigDecimal.valueOf(100))
                .divide(target, 2, java.math.RoundingMode.HALF_UP));
    }

    /** Posted good output at the centre in that shift, for the planned work order when the plan names one. */
    BigDecimal actualQty(Map<String, Object> plan) {
        List<Object> params = new ArrayList<>(List.of(MfgDb.org(), plan.get("workCenterId"), plan.get("shiftId"),
                MfgDb.param(plan.get("shiftDate"))));
        String wo = "";
        if (plan.get("workOrderId") != null) {
            wo = " AND pr.\"workOrderId\" = ?";
            params.add(plan.get("workOrderId"));
        }
        return db.jdbc().sql("SELECT coalesce(sum(pr.\"qtyGood\"), 0) FROM \"ShopFloorProductionReport\" pr JOIN"
                + " \"ShopFloorShiftReport\" r ON r.id = pr.\"shiftReportId\" WHERE pr.\"organizationId\" = ?"
                + " AND pr.\"workCenterId\" = ? AND r.\"shiftId\" = ? AND r.\"shiftDate\" = ?"
                + " AND pr.status = 'posted'" + wo).params(params).query(BigDecimal.class).single();
    }

    @Transactional
    public Map<String, Object> createShiftPlan(ShiftPlanInput in) {
        Map<String, Object> data = shiftPlanData(in);
        data.put("status", "draft");
        data.put("createdBy", TenantContext.userId());
        return view(str(db.insert(ProductionShiftPlan.class, data), "id"));
    }

    @Transactional
    public Map<String, Object> updateShiftPlan(String id, ShiftPlanInput in) {
        requireDraft(id);
        db.update(ProductionShiftPlan.class, id, shiftPlanData(in));
        return view(id);
    }

    @Transactional
    public void deleteShiftPlan(String id) {
        requireDraft(id);
        db.jdbc().sql("DELETE FROM \"ProductionShiftPlan\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, MfgDb.org()).update();
    }

    @Transactional
    public Map<String, Object> publishShiftPlan(String id) {
        requireDraft(id);
        db.update(ProductionShiftPlan.class, id, Obj.of("status", "published", "publishedAt", Instant.now(),
                "publishedBy", TenantContext.userId()));
        return view(id);
    }

    private Map<String, Object> shiftPlanData(ShiftPlanInput in) {
        require(ShopFloorShift.class, in.shiftId(), "Shift not found");
        require(WorkCenter.class, in.workCenterId(), "Work centre not found");
        String orderId = CrushingSupport.text(in.productionOrderId());
        String workOrderId = CrushingSupport.text(in.workOrderId());
        String productId = CrushingSupport.text(in.productId());
        if (orderId != null) {
            Map<String, Object> order = require(ProductionOrder.class, orderId, "Production order not found");
            productId = productId != null ? productId : str(order, "outputProductId");
        }
        if (workOrderId != null) {
            Map<String, Object> wo = require(WorkOrder.class, workOrderId, "Work order not found");
            if (orderId != null && !orderId.equals(wo.get("productionOrderId"))) {
                throw ApiException.badRequest("The work order belongs to another production order.");
            }
            orderId = str(wo, "productionOrderId");
        }
        requireProduct(productId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shiftDate", LocalDate.parse(in.shiftDate()));
        data.put("shiftId", in.shiftId());
        data.put("workCenterId", in.workCenterId());
        data.put("productionOrderId", orderId);
        data.put("workOrderId", workOrderId);
        data.put("productId", productId);
        data.put("targetQty", in.targetQty());
        data.put("crewSize", in.crewSize());
        data.put("notes", CrushingSupport.text(in.notes()));
        return data;
    }

    private Map<String, Object> requireDraft(String id) {
        Map<String, Object> plan = require(ProductionShiftPlan.class, id, "Shift plan not found");
        if (!"draft".equals(plan.get("status"))) {
            throw ApiException.badRequest("Only a draft shift plan can be changed.");
        }
        return plan;
    }

    private Map<String, Object> view(String id) {
        Map<String, Object> row = SqlRows.one(db.jdbc().sql(PLAN_SELECT + " WHERE sp.id = ? AND sp.\"organizationId\" ="
                + " ?")
                .params(id, MfgDb.org()));
        if (row == null) {
            throw ApiException.notFound("Shift plan not found");
        }
        withActual(row);
        return row;
    }

    // ================================================================== targets

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTargets(String from, String to, String scope) {
        return targets(CrushingSupport.date(from), CrushingSupport.date(to), scope);
    }

    /** Targets overlapping the period, with what each is set on. */
    List<Map<String, Object>> targets(LocalDate from, LocalDate to, String scope) {
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        if (to != null) {
            where.append(" AND t.\"periodFrom\" <= ?");
            params.add(java.sql.Date.valueOf(to));
        }
        if (from != null) {
            where.append(" AND t.\"periodTo\" >= ?");
            params.add(java.sql.Date.valueOf(from));
        }
        if (CrushingSupport.text(scope) != null) {
            where.append(" AND t.scope = ?");
            params.add(scope);
        }
        return SqlRows.list(db.jdbc().sql("SELECT t.*, coalesce(w.name, c.name, p.name) AS \"subjectName\","
                + " coalesce(w.code, c.code, p.code) AS \"subjectCode\" FROM \"ProductionTarget\" t"
                + " LEFT JOIN \"WorkCenter\" w ON w.id = t.\"workCenterId\" LEFT JOIN \"CrushingPlant\" c ON"
                + " c.id = t.\"plantId\" LEFT JOIN \"Product\" p ON p.id = t.\"productId\"" + where
                + " ORDER BY t.\"periodFrom\" DESC, t.scope LIMIT 500").params(params));
    }

    @Transactional
    public Map<String, Object> createTarget(TargetInput in) {
        Map<String, Object> data = targetData(in);
        data.put("createdBy", TenantContext.userId());
        return db.insert(ProductionTarget.class, data);
    }

    @Transactional
    public Map<String, Object> updateTarget(String id, TargetInput in) {
        require(ProductionTarget.class, id, "Production target not found");
        return db.update(ProductionTarget.class, id, targetData(in));
    }

    @Transactional
    public void deleteTarget(String id) {
        require(ProductionTarget.class, id, "Production target not found");
        db.jdbc().sql("DELETE FROM \"ProductionTarget\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, MfgDb.org()).update();
    }

    private Map<String, Object> targetData(TargetInput in) {
        LocalDate from = LocalDate.parse(in.periodFrom());
        LocalDate to = LocalDate.parse(in.periodTo());
        if (to.isBefore(from)) {
            throw ApiException.badRequest("periodTo must not be before periodFrom");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", in.scope());
        data.put("workCenterId", null);
        data.put("plantId", null);
        data.put("productId", null);
        switch (in.scope()) {
            case "work_center" -> {
                require(WorkCenter.class, in.workCenterId(), "Work centre not found");
                data.put("workCenterId", in.workCenterId());
            }
            case "crushing_plant" -> {
                if (in.plantId() == null || db.jdbc().sql("SELECT count(*) FROM \"CrushingPlant\" WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(in.plantId(), MfgDb.org())
                        .query(Long.class).single() == 0) {
                    throw ApiException.notFound("Crushing plant not found");
                }
                data.put("plantId", in.plantId());
            }
            default -> {
                if (in.productId() == null) {
                    throw ApiException.notFound("Product not found");
                }
                requireProduct(in.productId());
                data.put("productId", in.productId());
            }
        }
        data.put("periodFrom", from);
        data.put("periodTo", to);
        data.put("targetQty", in.targetQty());
        data.put("notes", CrushingSupport.text(in.notes()));
        return data;
    }

    private Map<String, Object> require(Class<?> entity, String id, String message) {
        Map<String, Object> row = id == null ? null : db.byId(entity, id);
        if (row == null) {
            throw ApiException.notFound(message);
        }
        return row;
    }

    private void requireProduct(String productId) {
        if (productId != null && db.jdbc().sql("SELECT count(*) FROM \"Product\" WHERE id = ? AND \"organizationId\" ="
                + " ?"
                + " AND \"deletedAt\" IS NULL").params(productId, MfgDb.org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Product not found");
        }
    }
}
