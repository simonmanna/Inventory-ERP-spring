package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateResource;
import com.inventoryerp.manufacturing.domain.Resource;
import com.inventoryerp.manufacturing.domain.WorkCenter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Machines, labour and spaces (port of {@code ResourceService} in {@code masters.service.ts}). */
@Service
public class ResourceService {

    private final MfgDb db;

    public ResourceService(MfgDb db) {
        this.db = db;
    }

    @Transactional
    public Map<String, Object> create(CreateResource dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", dto.code());
        data.put("name", dto.name());
        data.put("kind", dto.kind());
        data.put("workCenterId", dto.workCenterId());
        data.put("fixedAssetId", dto.fixedAssetId());
        data.put("userId", dto.userId());
        data.put("costPerHour", dto.costPerHour() != null ? dto.costPerHour() : BigDecimal.ZERO);
        data.put("capacityMinsPerDay", dto.capacityMinsPerDay());
        data.put("isActive", dto.isActive() != null ? dto.isActive() : true);
        data.put("createdBy", TenantContext.userId());
        return db.insert(Resource.class, data);
    }

    /** Unvalidated {@code Partial<>} body; {@code costPerHour} only when non-null. */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        Map<String, Object> rest = new LinkedHashMap<>(body);
        Object costPerHour = rest.remove("costPerHour");
        Map<String, Object> data = MfgDb.spread(Resource.class, rest);
        if (costPerHour != null) {
            data.put("costPerHour", Money.dec(costPerHour));
        }
        data.put("updatedBy", TenantContext.userId());
        return db.update(Resource.class, id, data);
    }

    /** Name order, each with its work centre. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String kind) {
        boolean byKind = kind != null && !kind.isEmpty();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.list(Resource.class, (byKind ? " AND kind = ?::\"ResourceKind\"" : "")
                + " ORDER BY name ASC", byKind ? new Object[] {kind} : new Object[0])) {
            r.put("workCenter", r.get("workCenterId") == null ? null
                    : db.children(WorkCenter.class, "id", r.get("workCenterId"), null).stream().findFirst()
                            .orElse(null));
            out.add(r);
        }
        return out;
    }

    /** Soft delete. */
    @Transactional
    public void remove(String id) {
        db.update(Resource.class, id, Obj.of("deletedAt", Instant.now(), "isActive", false));
    }
}
