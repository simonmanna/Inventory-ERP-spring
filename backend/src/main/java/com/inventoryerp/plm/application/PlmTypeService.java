package com.inventoryerp.plm.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.plm.application.PlmCommands.SaveType;
import com.inventoryerp.plm.domain.PlmChangeType;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ECO types (ADR-S086, Odoo "ECO types"): each type has its own stage pipeline, so e.g. "New product introduction"
 * can run through different stages and approvers than "Engineering changes". One type is the default (ECOs opened
 * from a BOM revision and change requests land there unless a type is chosen). A new type starts with the standard
 * pipeline New → Engineering review → Approved, ready to be reshaped.
 */
@Service
public class PlmTypeService {

    private final PlmDb db;
    private final PlmStageService stages;

    public PlmTypeService(PlmDb db, PlmStageService stages) {
        this.db = db;
        this.stages = stages;
    }

    /** Types by sequence with {@code stageCount} and {@code openChanges}. */
    @Transactional
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> types = stages.types();
        Map<String, Long> open = new HashMap<>();
        db.jdbc().sql("SELECT \"changeTypeId\", count(*) FROM \"EngineeringChange\" WHERE \"organizationId\" = ? AND"
                + " status IN ('draft', 'approved') AND \"changeTypeId\" IS NOT NULL GROUP BY \"changeTypeId\"")
                .param(PlmDb.org()).query((rs, i) -> open.put(rs.getString(1), rs.getLong(2))).list();
        Map<String, Long> stageCount = new HashMap<>();
        db.jdbc().sql("SELECT \"typeId\", count(*) FROM \"PlmChangeStage\" WHERE \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL AND \"typeId\" IS NOT NULL GROUP BY \"typeId\"")
                .param(PlmDb.org()).query((rs, i) -> stageCount.put(rs.getString(1), rs.getLong(2))).list();
        types.forEach(t -> {
            t.put("stageCount", stageCount.getOrDefault(str(t, "id"), 0L));
            t.put("openChanges", open.getOrDefault(str(t, "id"), 0L));
        });
        return types;
    }

    @Transactional
    public Map<String, Object> create(SaveType dto) {
        String name = PlmSupport.blank(dto.name());
        if (name == null) {
            throw ApiException.badRequest("name should not be empty");
        }
        List<Map<String, Object>> types = stages.types();
        uniqueName(name, null);
        boolean makeDefault = Boolean.TRUE.equals(dto.isDefault());
        if (makeDefault) {
            clearDefault();
        }
        int seq = dto.sequence() != null ? dto.sequence()
                : types.isEmpty() ? 10 : ((Integer) types.getLast().get("sequence")) + 10;
        Map<String, Object> type = db.insert(PlmChangeType.class, Obj.of("name", name, "description",
                PlmSupport.blank(dto.description()), "color", PlmSupport.blank(dto.color()), "sequence", seq,
                "isDefault", makeDefault));
        stages.ensureStages(str(type, "id"));
        return type;
    }

    @Transactional
    public Map<String, Object> update(String id, SaveType dto) {
        Map<String, Object> type = stages.requireType(id);
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.name() != null) {
            String name = PlmSupport.blank(dto.name());
            if (name == null) {
                throw ApiException.badRequest("name should not be empty");
            }
            uniqueName(name, id);
            data.put("name", name);
        }
        if (dto.description() != null) {
            data.put("description", PlmSupport.blank(dto.description()));
        }
        if (dto.color() != null) {
            data.put("color", PlmSupport.blank(dto.color()));
        }
        if (dto.sequence() != null) {
            data.put("sequence", dto.sequence());
        }
        if (dto.isDefault() != null && dto.isDefault() != Boolean.TRUE.equals(type.get("isDefault"))) {
            if (!dto.isDefault()) {
                throw ApiException.badRequest("Make another change type the default instead.");
            }
            clearDefault();
            data.put("isDefault", true);
        }
        return data.isEmpty() ? type : db.update(PlmChangeType.class, id, data);
    }

    /** Soft delete with its stages; refused for the default type and while ECOs of the type are open. */
    @Transactional
    public void remove(String id) {
        Map<String, Object> type = stages.requireType(id);
        if (Boolean.TRUE.equals(type.get("isDefault"))) {
            throw ApiException.badRequest("The default change type cannot be deleted.");
        }
        Long open = db.jdbc().sql("SELECT count(*) FROM \"EngineeringChange\" WHERE \"organizationId\" = ? AND"
                + " \"changeTypeId\" = ? AND status IN ('draft', 'approved')").params(PlmDb.org(), id)
                .query(Long.class).single();
        if (open > 0) {
            throw ApiException.badRequest("Change type " + type.get("name") + " has " + open
                    + " open change(s); move them first.");
        }
        db.jdbc().sql("UPDATE \"PlmChangeStage\" SET \"deletedAt\" = now(), \"updatedAt\" = now() WHERE"
                + " \"organizationId\" = ? AND \"typeId\" = ? AND \"deletedAt\" IS NULL").params(PlmDb.org(), id)
                .update();
        db.update(PlmChangeType.class, id, Obj.of("deletedAt", Instant.now()));
    }

    private void clearDefault() {
        db.jdbc().sql("UPDATE \"PlmChangeType\" SET \"isDefault\" = false, \"updatedAt\" = now() WHERE"
                + " \"organizationId\" = ? AND \"isDefault\" AND \"deletedAt\" IS NULL").param(PlmDb.org()).update();
    }

    private void uniqueName(String name, String exceptId) {
        for (Map<String, Object> t : db.list(PlmChangeType.class, " AND lower(name) = lower(?)", name)) {
            if (!t.get("id").equals(exceptId)) {
                throw ApiException.conflict("A change type named " + name + " already exists.");
            }
        }
    }
}
