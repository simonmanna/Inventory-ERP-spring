package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.manufacturing.application.StageCommands.AllocationRuleInput;
import com.inventoryerp.manufacturing.application.StageCommands.BomStageSetup;
import com.inventoryerp.manufacturing.application.StageCommands.CoProductInput;
import com.inventoryerp.manufacturing.application.StageCommands.RoutingStageSetup;
import com.inventoryerp.manufacturing.application.StageCommands.StageSetupInput;
import com.inventoryerp.manufacturing.domain.Bom;
import com.inventoryerp.manufacturing.domain.BomCoProduct;
import com.inventoryerp.manufacturing.domain.MfgCostAllocationRule;
import com.inventoryerp.manufacturing.domain.Routing;
import com.inventoryerp.manufacturing.domain.RoutingOperation;
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
 * Multi-stage set-up (ADR-S011, Phase 2.1): the stage behaviour of routing operations (issue mode, intermediate
 * product + stage location, expected yield, QC gate, materials consumed), a BOM's co-products / by-products / waste
 * and the joint-cost allocation rules (organization default + BOM overrides).
 */
@Service
public class StageSetupService {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final MfgDb db;
    private final AuditService audit;
    private final ProductionService production;

    public StageSetupService(MfgDb db, AuditService audit, ProductionService production) {
        this.db = db;
        this.audit = audit;
        this.production = production;
    }

    // ================================================================== routing stages

    /** The routing's operations, each with its stage set-up ({@code stage}: null = defaults). */
    @Transactional(readOnly = true)
    public Map<String, Object> routingSetup(String routingId) {
        Map<String, Object> routing = routing(routingId);
        List<Map<String, Object>> ops = new ArrayList<>();
        List<Map<String, Object>> all = operations(routingId);
        for (int i = 0; i < all.size(); i++) {
            Map<String, Object> op = all.get(i);
            Map<String, Object> row = new LinkedHashMap<>(op);
            row.put("isLast", i == all.size() - 1);
            row.put("stage", setupFor(str(op, "id")));
            ops.add(row);
        }
        routing.put("operations", ops);
        return routing;
    }

    /** Upsert the stage set-up of the listed operations (others keep theirs). */
    @Transactional
    public Map<String, Object> saveRoutingSetup(String routingId, RoutingStageSetup dto) {
        routing(routingId);
        Set<String> opIds = new HashSet<>();
        operations(routingId).forEach(o -> opIds.add(str(o, "id")));
        for (StageSetupInput s : dto.stages()) {
            if (!opIds.contains(s.operationId())) {
                throw ApiException.badRequest("Operation " + s.operationId() + " is not part of this routing.");
            }
            if (s.outputProductId() != null) {
                production.getProduct(s.outputProductId());
            }
            if (s.stageLocationId() != null) {
                production.requireLocation(s.stageLocationId());
            }
            List<String> materials = s.materialProductIds() == null ? List.of() : s.materialProductIds();
            materials.forEach(production::getProduct);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("issueMode", s.issueMode() != null ? s.issueMode() : "backflush");
            data.put("outputProductId", s.outputProductId());
            data.put("outputPerUnit", s.outputPerUnit() != null ? s.outputPerUnit() : BigDecimal.ONE);
            data.put("stageLocationId", s.stageLocationId());
            data.put("expectedYieldPct", s.expectedYieldPct() != null ? s.expectedYieldPct() : HUNDRED);
            data.put("qcGate", Boolean.TRUE.equals(s.qcGate()));
            data.put("qcCheckpointCode", s.qcCheckpointCode());
            data.put("materialProductIds", new MfgDb.Json(materials));
            Map<String, Object> existing = setupFor(s.operationId());
            if (existing == null) {
                data.put("routingOperationId", s.operationId());
                db.insert(com.inventoryerp.manufacturing.domain.RoutingStageSetup.class, data);
            } else {
                db.update(com.inventoryerp.manufacturing.domain.RoutingStageSetup.class, str(existing, "id"), data);
            }
        }
        audit.recordInTx(AuditInput.of("Routing", routingId, "update", null, Obj.of("stageSetup",
                dto.stages().size())));
        return routingSetup(routingId);
    }

    /** The stage set-up row of a routing operation, or null. */
    Map<String, Object> setupFor(String operationId) {
        List<Map<String, Object>> rows = db.list(com.inventoryerp.manufacturing.domain.RoutingStageSetup.class,
                " AND \"routingOperationId\" = ? LIMIT 1", operationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    List<Map<String, Object>> operations(String routingId) {
        return db.children(RoutingOperation.class, "routingId", routingId, "sequence ASC");
    }

    private Map<String, Object> routing(String routingId) {
        Map<String, Object> r = db.byId(Routing.class, routingId);
        if (r == null) {
            throw ApiException.notFound("Routing not found");
        }
        return r;
    }

    // ================================================================== BOM co-products + allocation

    /** Co-products of the BOM and its allocation rule (own override, else the organization default). */
    @Transactional(readOnly = true)
    public Map<String, Object> bomSetup(String bomId) {
        Map<String, Object> bom = bom(bomId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bomId", bomId);
        out.put("routingId", bom.get("routingId"));
        out.put("outputProductId", bom.get("outputProductId"));
        out.put("outputQuantity", bom.get("outputQuantity"));
        out.put("coProducts", coProducts(bomId));
        Map<String, Object> own = ruleRow(bomId);
        out.put("allocationRule", own);
        out.put("effectiveRule", effectiveRule(bomId));
        return out;
    }

    /** Replace the BOM's co-products; set / clear its allocation override. */
    @Transactional
    public Map<String, Object> saveBomSetup(String bomId, BomStageSetup dto) {
        Map<String, Object> bom = bom(bomId);
        if ("archived".equals(bom.get("status"))) {
            throw ApiException.badRequest("An archived BOM cannot be changed.");
        }
        Set<String> opIds = new HashSet<>();
        if (bom.get("routingId") != null) {
            operations(str(bom, "routingId")).forEach(o -> opIds.add(str(o, "id")));
        }
        Set<String> seen = new HashSet<>();
        for (CoProductInput c : dto.coProducts()) {
            production.getProduct(c.productId());
            if (c.productId().equals(bom.get("outputProductId"))) {
                throw ApiException.badRequest("The BOM output cannot also be a co-product.");
            }
            if (!seen.add(c.productId())) {
                throw ApiException.badRequest("Product " + c.productId() + " is listed twice.");
            }
            if (c.operationId() != null && !opIds.contains(c.operationId())) {
                throw ApiException.badRequest("Operation " + c.operationId() + " is not part of the BOM's routing.");
            }
            if (c.locationId() != null) {
                production.requireLocation(c.locationId());
            }
            if (Boolean.FALSE.equals(c.stocked()) && !"zero".equals(valuation(c))) {
                throw ApiException.badRequest("An output that is not stocked must be valued at zero.");
            }
            if ("nrv".equals(valuation(c)) && "co_product".equals(c.role())) {
                throw ApiException.badRequest("Co-products share the joint cost; use valuation 'allocated'.");
            }
        }
        db.jdbc().sql("DELETE FROM \"BomCoProduct\" WHERE \"organizationId\" = ? AND \"bomId\" = ?")
                .params(MfgDb.org(), bomId).update();
        int seq = 0;
        for (CoProductInput c : dto.coProducts()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bomId", bomId);
            row.put("routingOperationId", c.operationId());
            row.put("productId", c.productId());
            row.put("role", c.role());
            row.put("quantity", c.quantity());
            row.put("uomId", c.uomId());
            row.put("valuation", valuation(c));
            row.put("nrvUnitValue", c.nrvUnitValue());
            row.put("marketValue", c.marketValue());
            row.put("weightPerUnit", c.weightPerUnit());
            row.put("fixedPct", c.fixedPct());
            row.put("stocked", c.stocked() == null || c.stocked());
            row.put("locationId", c.locationId());
            row.put("sequence", seq++);
            row.put("notes", c.notes());
            db.insert(BomCoProduct.class, row);
        }
        if (dto.allocationRule() == null) {
            db.jdbc().sql("DELETE FROM \"MfgCostAllocationRule\" WHERE \"organizationId\" = ? AND \"bomId\" = ?")
                    .params(MfgDb.org(), bomId).update();
        } else {
            upsertRule(bomId, dto.allocationRule());
        }
        audit.recordInTx(AuditInput.of("Bom", bomId, "update", null, Obj.of("coProducts", dto.coProducts().size(),
                "allocationMethod", dto.allocationRule() == null ? null : dto.allocationRule().method())));
        return bomSetup(bomId);
    }

    /** Organization default (null when unset) and every BOM override. */
    @Transactional(readOnly = true)
    public Map<String, Object> rules() {
        List<Map<String, Object>> overrides = SqlRows.list(db.jdbc().sql("SELECT r.*, b.code AS \"bomCode\","
                + " b.name AS \"bomName\" FROM \"MfgCostAllocationRule\" r JOIN \"Bom\" b ON b.id = r.\"bomId\""
                + " WHERE r.\"organizationId\" = ? ORDER BY b.code").param(MfgDb.org()));
        return Obj.of("organizationDefault", ruleRow(null), "effectiveDefault", effectiveRule(null), "bomOverrides",
                overrides);
    }

    /** Set the organization default allocation rule. */
    @Transactional
    public Map<String, Object> saveDefaultRule(AllocationRuleInput dto) {
        upsertRule(null, dto);
        audit.recordInTx(AuditInput.of("MfgCostAllocationRule", "default", "update", null, Obj.of("method",
                dto.method())));
        return rules();
    }

    /** BOM override → organization default → {@code quantity}; {@code source} tells which. */
    Map<String, Object> effectiveRule(String bomId) {
        Map<String, Object> own = bomId == null ? null : ruleRow(bomId);
        if (own != null) {
            own.put("source", "bom");
            return own;
        }
        Map<String, Object> def = ruleRow(null);
        if (def != null) {
            def.put("source", "organization");
            return def;
        }
        Map<String, Object> builtin = new LinkedHashMap<>();
        builtin.put("method", "quantity");
        builtin.put("mainWeightPerUnit", null);
        builtin.put("mainMarketValue", null);
        builtin.put("source", "default");
        return builtin;
    }

    List<Map<String, Object>> coProducts(String bomId) {
        return SqlRows.list(db.jdbc().sql("SELECT c.*, p.name AS \"productName\", p.\"salesPrice\" FROM"
                + " \"BomCoProduct\" c JOIN \"Product\" p ON p.id = c.\"productId\" WHERE c.\"organizationId\" = ?"
                + " AND c.\"bomId\" = ? ORDER BY c.sequence").params(MfgDb.org(), bomId));
    }

    private Map<String, Object> ruleRow(String bomId) {
        List<Map<String, Object>> rows = bomId == null
                ? db.list(MfgCostAllocationRule.class, " AND \"bomId\" IS NULL LIMIT 1")
                : db.list(MfgCostAllocationRule.class, " AND \"bomId\" = ? LIMIT 1", bomId);
        return rows.isEmpty() ? null : new LinkedHashMap<>(rows.getFirst());
    }

    private void upsertRule(String bomId, AllocationRuleInput dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("method", dto.method());
        data.put("mainWeightPerUnit", dto.mainWeightPerUnit());
        data.put("mainMarketValue", dto.mainMarketValue());
        data.put("notes", dto.notes());
        Map<String, Object> existing = ruleRow(bomId);
        if (existing == null) {
            data.put("bomId", bomId);
            db.insert(MfgCostAllocationRule.class, data);
        } else {
            db.update(MfgCostAllocationRule.class, str(existing, "id"), data);
        }
    }

    private Map<String, Object> bom(String bomId) {
        Map<String, Object> bom = db.byId(Bom.class, bomId);
        if (bom == null) {
            throw ApiException.notFound("BOM not found");
        }
        return bom;
    }

    private static String valuation(CoProductInput c) {
        if (c.valuation() != null) {
            return c.valuation();
        }
        return switch (c.role()) {
            case "by_product" -> "nrv";
            case "waste" -> "zero";
            default -> "allocated";
        };
    }

    static BigDecimal decOrNull(Map<String, Object> row, String key) {
        return row == null || row.get(key) == null ? null : dec(row, key);
    }
}
