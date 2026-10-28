package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateRouting;
import com.inventoryerp.manufacturing.application.MfgCommands.RoutingOperationInput;
import com.inventoryerp.manufacturing.domain.Routing;
import com.inventoryerp.manufacturing.domain.RoutingOperation;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Routings: ordered operations per BOM/product (port of {@code RoutingService} in {@code masters.service.ts}). */
@Service
public class RoutingService {

    private final MfgDb db;

    public RoutingService(MfgDb db) {
        this.db = db;
    }

    @Transactional
    public Map<String, Object> create(CreateRouting dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", dto.name());
        data.put("productId", dto.productId());
        data.put("bomId", dto.bomId());
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> routing = db.insert(Routing.class, data);
        for (RoutingOperationInput o : dto.operations()) {
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("routingId", routing.get("id"));
            op.put("sequence", o.sequence());
            op.put("name", o.name());
            op.put("workCenterId", o.workCenterId());
            op.put("durationMins", o.durationMins() != null ? o.durationMins() : 0);
            op.put("setupMins", o.setupMins() != null ? o.setupMins() : 0);
            op.put("instructions", o.instructions());
            op.put("isSubcontracted", o.isSubcontracted() != null ? o.isSubcontracted() : false);
            op.put("subcontractorId", o.subcontractorId());
            op.put("subcontractCostPerUnit", o.subcontractCostPerUnit() != null ? o.subcontractCostPerUnit()
                    : java.math.BigDecimal.ZERO);
            db.insert(RoutingOperation.class, op);
        }
        return withOperations(routing);
    }

    /** Name order, filtered by BOM and/or product. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String bomId, String productId) {
        StringBuilder extra = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (bomId != null) {
            extra.append(" AND \"bomId\" = ?");
            params.add(bomId);
        }
        if (productId != null) {
            extra.append(" AND \"productId\" = ?");
            params.add(productId);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.list(Routing.class, extra + " ORDER BY name ASC", params.toArray())) {
            out.add(withOperations(r));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = db.byId(Routing.class, id);
        if (r == null) {
            throw ApiException.notFound("Routing not found");
        }
        return withOperations(r);
    }

    /** Soft delete. */
    @Transactional
    public void remove(String id) {
        db.update(Routing.class, id, Obj.of("deletedAt", Instant.now(), "isActive", false));
    }

    Map<String, Object> withOperations(Map<String, Object> routing) {
        routing.put("operations", db.children(RoutingOperation.class, "routingId", routing.get("id"), "sequence ASC"));
        return routing;
    }
}
