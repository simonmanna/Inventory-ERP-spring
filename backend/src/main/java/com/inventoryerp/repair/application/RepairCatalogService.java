package com.inventoryerp.repair.application;

import static com.inventoryerp.repair.application.RepairDb.nz;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.repair.domain.RepairLabourType;
import com.inventoryerp.repair.domain.RepairTechnician;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * RMMS config masters (port of {@code repair-catalog.service.ts}): labour charge types and technicians. Both are
 * soft-deleted ({@code deletedAt}).
 */
@Service
@ConditionalOnProperty(name = "app.modules.repair", havingValue = "true")
public class RepairCatalogService {

    private final RepairDb db;
    private final SequenceService seq;

    public RepairCatalogService(RepairDb db, SequenceService seq) {
        this.db = db;
        this.seq = seq;
    }

    // ─── labour types ───

    public List<Map<String, Object>> listLabourTypes(Map<String, String> query) {
        RepairDb.Where w = db.where(RepairLabourType.class);
        activeFilter(w, query.get("isActive"));
        return w.list("code ASC");
    }

    public Map<String, Object> createLabourType(Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        return db.insert(RepairLabourType.class, Obj.of("organizationId", RepairDb.org(), "code", dto.get("code"),
                "name", dto.get("name"), "description", dto.get("description"), "productId", dto.get("productId"),
                "price", nz(dto.get("price"), 0), "durationMinutes", nz(dto.get("durationMinutes"), 60),
                "isActive", nz(dto.get("isActive"), true)));
    }

    public Map<String, Object> updateLabourType(String id, Map<String, Object> dto) {
        ensureExists(RepairLabourType.class, id);
        return db.update(RepairLabourType.class, id, RepairDb.body(dto));
    }

    public Map<String, Object> deleteLabourType(String id) {
        ensureExists(RepairLabourType.class, id);
        return db.softDelete(RepairLabourType.class, id);
    }

    // ─── technicians ───

    public List<Map<String, Object>> listTechnicians(Map<String, String> query) {
        RepairDb.Where w = db.where(RepairTechnician.class).eqIf("availability", query.get("availability"));
        activeFilter(w, query.get("isActive"));
        return w.list("name ASC");
    }

    public Map<String, Object> createTechnician(Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        Object code = dto.get("code") != null ? dto.get("code") : seq.next("repair_technician", "TECH-", 4);
        return db.insert(RepairTechnician.class, Obj.of("organizationId", RepairDb.org(), "code", code,
                "name", dto.get("name"), "phone", dto.get("phone"), "email", dto.get("email"),
                "skills", nz(dto.get("skills"), List.of()), "certifications", nz(dto.get("certifications"), List.of()),
                "availability", nz(dto.get("availability"), "available"), "hourlyRate", nz(dto.get("hourlyRate"), 0),
                "isActive", nz(dto.get("isActive"), true), "notes", dto.get("notes")));
    }

    public Map<String, Object> updateTechnician(String id, Map<String, Object> dto) {
        ensureExists(RepairTechnician.class, id);
        return db.update(RepairTechnician.class, id, RepairDb.body(dto));
    }

    public Map<String, Object> deleteTechnician(String id) {
        ensureExists(RepairTechnician.class, id);
        return db.softDelete(RepairTechnician.class, id);
    }

    /** {@code isActive !== undefined && isActive !== 'all' ? { isActive: isActive === 'true' } : {}}. */
    static void activeFilter(RepairDb.Where w, String isActive) {
        if (isActive != null && !"all".equals(isActive)) {
            w.eq("isActive", "true".equals(isActive));
        }
    }

    private void ensureExists(Class<?> entity, String id) {
        if (db.byId(entity, id) == null) {
            throw ApiException.notFound("Record not found");
        }
    }
}
