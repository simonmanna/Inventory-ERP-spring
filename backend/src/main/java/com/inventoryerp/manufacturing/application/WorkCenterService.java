package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateWorkCenter;
import com.inventoryerp.manufacturing.domain.Resource;
import com.inventoryerp.manufacturing.domain.WorkCenter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Work centres (port of {@code WorkCenterService} in {@code masters.service.ts}). */
@Service
public class WorkCenterService {

    private final MfgDb db;

    public WorkCenterService(MfgDb db) {
        this.db = db;
    }

    @Transactional
    public Map<String, Object> create(CreateWorkCenter dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", dto.code());
        data.put("name", dto.name());
        data.put("description", dto.description());
        data.put("isActive", dto.isActive() != null ? dto.isActive() : true);
        data.put("createdBy", TenantContext.userId());
        return db.insert(WorkCenter.class, data);
    }

    /** {@code update({where: {id}, data: {...dto, updatedBy}})} with the unvalidated {@code Partial<>} body. */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        Map<String, Object> data = MfgDb.spread(WorkCenter.class, body);
        data.put("updatedBy", TenantContext.userId());
        return db.update(WorkCenter.class, id, data);
    }

    /** Name order, each with all its resources. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : db.list(WorkCenter.class, " ORDER BY name ASC")) {
            c.put("resources", db.children(Resource.class, "workCenterId", c.get("id"), null));
            out.add(c);
        }
        return out;
    }

    /** Soft delete. */
    @Transactional
    public void remove(String id) {
        db.update(WorkCenter.class, id, Obj.of("deletedAt", Instant.now(), "isActive", false));
    }
}
