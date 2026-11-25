package com.inventoryerp.plm.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.plm.application.PlmCommands.SaveStage;
import com.inventoryerp.plm.application.PlmCommands.StageApprover;
import com.inventoryerp.plm.domain.PlmChangeStage;
import com.inventoryerp.plm.domain.PlmChangeStageApprover;
import com.inventoryerp.plm.domain.PlmChangeType;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ECO types and their stage pipelines (ADR-S063; types since ADR-S086). Every ECO belongs to a type and each type has
 * its own stages ordered by {@code sequence}: the last stage of a type means "approved — ready to apply". A stage with
 * {@code requiresApproval} cannot be left forwards until its approvals are complete. An organization gets the default
 * type "Engineering changes" with New → Engineering review → Approved the first time it touches ECOs, and every new
 * type starts with a pipeline of its own.
 */
@Service
public class PlmStageService {

    static final String DEFAULT_TYPE = "Engineering changes";

    private final PlmDb db;
    private final PlmSupport plm;

    public PlmStageService(PlmDb db, PlmSupport plm) {
        this.db = db;
        this.plm = plm;
    }

    /**
     * The organization's stages (of one type when {@code typeId} is given), by type then sequence, each with its
     * approvers, {@code isFinal} (last of its type), {@code openChanges} and {@code typeName}.
     */
    @Transactional
    public List<Map<String, Object>> list(String typeId) {
        List<Map<String, Object>> types = types();
        if (typeId != null && !typeId.isBlank()) {
            requireType(typeId);
        }
        List<Map<String, Object>> approvers = db.list(PlmChangeStageApprover.class, " ORDER BY \"createdAt\"");
        Set<String> users = new HashSet<>();
        approvers.forEach(a -> users.add(str(a, "userId")));
        Map<String, String> names = plm.userNames(users);
        Map<String, Long> inUse = new HashMap<>();
        db.jdbc().sql("SELECT \"stageId\", count(*) AS n FROM \"EngineeringChange\" WHERE \"organizationId\" = ?"
                + " AND status IN ('draft', 'approved') AND \"stageId\" IS NOT NULL GROUP BY \"stageId\"")
                .param(PlmDb.org()).query((rs, i) -> inUse.put(rs.getString(1), rs.getLong(2))).list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> type : types) {
            if (typeId != null && !typeId.isBlank() && !typeId.equals(type.get("id"))) {
                continue;
            }
            List<Map<String, Object>> stages = ordered(str(type, "id"));
            for (int i = 0; i < stages.size(); i++) {
                Map<String, Object> s = stages.get(i);
                List<Map<String, Object>> mine = new ArrayList<>();
                for (Map<String, Object> a : approvers) {
                    if (s.get("id").equals(a.get("stageId"))) {
                        mine.add(Obj.merge(a, Obj.of("userName", names.get(str(a, "userId")))));
                    }
                }
                s.put("approvers", mine);
                s.put("isFinal", i == stages.size() - 1);
                s.put("openChanges", inUse.getOrDefault(str(s, "id"), 0L));
                s.put("typeName", type.get("name"));
                out.add(s);
            }
        }
        return out;
    }

    @Transactional
    public Map<String, Object> create(SaveStage dto) {
        String name = PlmSupport.blank(dto.name());
        if (name == null) {
            throw ApiException.badRequest("name should not be empty");
        }
        String typeId = PlmSupport.blank(dto.typeId()) != null ? str(requireType(dto.typeId()), "id")
                : str(defaultType(), "id");
        List<Map<String, Object>> stages = ordered(typeId);
        uniqueName(typeId, name, null);
        int seq = dto.sequence() != null ? dto.sequence()
                : stages.isEmpty() ? 10 : ((Integer) stages.getLast().get("sequence")) + 10;
        return db.insert(PlmChangeStage.class, Obj.of("name", name, "sequence", seq, "requiresApproval",
                Boolean.TRUE.equals(dto.requiresApproval()), "description", PlmSupport.blank(dto.description()),
                "typeId", typeId, "folded", Boolean.TRUE.equals(dto.folded())));
    }

    @Transactional
    public Map<String, Object> update(String id, SaveStage dto) {
        Map<String, Object> stage = require(id);
        if (PlmSupport.blank(dto.typeId()) != null && !dto.typeId().equals(stage.get("typeId"))) {
            throw ApiException.badRequest("A stage cannot move to another change type.");
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        if (dto.name() != null) {
            String name = PlmSupport.blank(dto.name());
            if (name == null) {
                throw ApiException.badRequest("name should not be empty");
            }
            uniqueName(str(stage, "typeId"), name, id);
            data.put("name", name);
        }
        if (dto.sequence() != null) {
            data.put("sequence", dto.sequence());
        }
        if (dto.requiresApproval() != null) {
            // Integrity fix 2026-11-24 (ML-5): approval cannot be switched off under changes waiting for it.
            if (!dto.requiresApproval() && Boolean.TRUE.equals(stage.get("requiresApproval"))) {
                Long waiting = db.jdbc().sql("SELECT count(*) FROM \"EngineeringChange\" WHERE \"organizationId\" = ?"
                        + " AND \"stageId\" = ? AND status = 'draft'").params(PlmDb.org(), id).query(Long.class).single();
                if (waiting > 0) {
                    throw ApiException.badRequest("Stage " + stage.get("name") + " has " + waiting
                            + " change(s) awaiting its approval; move them first.");
                }
            }
            data.put("requiresApproval", dto.requiresApproval());
        }
        if (dto.description() != null) {
            data.put("description", PlmSupport.blank(dto.description()));
        }
        if (dto.folded() != null) {
            data.put("folded", dto.folded());
        }
        return data.isEmpty() ? stage : db.update(PlmChangeStage.class, id, data);
    }

    /** Soft delete; refused while an ECO sits in the stage or when fewer than two stages would remain in its type. */
    @Transactional
    public void remove(String id) {
        Map<String, Object> stage = require(id);
        Long used = db.jdbc().sql("SELECT count(*) FROM \"EngineeringChange\" WHERE \"organizationId\" = ?"
                + " AND \"stageId\" = ? AND status IN ('draft', 'approved')").params(PlmDb.org(), id)
                .query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("Stage " + stage.get("name") + " has " + used
                    + " open change(s); move them first.");
        }
        if (ordered(str(stage, "typeId")).size() <= 2) {
            throw ApiException.badRequest("A change pipeline needs at least two stages.");
        }
        db.update(PlmChangeStage.class, id, Obj.of("deletedAt", Instant.now()));
    }

    /** Replace the approvers of a stage ({@code required}, {@code optional} or {@code comment}-only reviewers). */
    @Transactional
    public List<Map<String, Object>> setApprovers(String id, List<StageApprover> approvers) {
        require(id);
        Set<String> seen = new HashSet<>();
        for (StageApprover a : approvers) {
            if (!seen.add(a.userId())) {
                throw ApiException.badRequest("A user is listed twice as approver.");
            }
            plm.requireUser(a.userId(), "approvers.userId");
        }
        db.jdbc().sql("DELETE FROM \"PlmChangeStageApprover\" WHERE \"organizationId\" = ? AND \"stageId\" = ?")
                .params(PlmDb.org(), id).update();
        List<Map<String, Object>> out = new ArrayList<>();
        for (StageApprover a : approvers) {
            out.add(db.insert(PlmChangeStageApprover.class, Obj.of("stageId", id, "userId", a.userId(), "kind",
                    a.kind() == null ? "required" : a.kind())));
        }
        return out;
    }

    // ---- types (used by PlmTypeService and the change services) -------------------------------------------------------

    /** Live types by sequence; the default type (with its pipeline) is created when the organization has none. */
    List<Map<String, Object>> types() {
        List<Map<String, Object>> types = db.list(PlmChangeType.class, " ORDER BY sequence, \"createdAt\"");
        if (!types.isEmpty()) {
            return types;
        }
        defaultType();
        return db.list(PlmChangeType.class, " ORDER BY sequence, \"createdAt\"");
    }

    /** The organization's default type; created (with the default pipeline) the first time it is needed. */
    Map<String, Object> defaultType() {
        List<Map<String, Object>> found = db.list(PlmChangeType.class, " AND \"isDefault\" LIMIT 1");
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        lock();
        found = db.list(PlmChangeType.class, " AND \"isDefault\" LIMIT 1");
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        List<Map<String, Object>> live = db.list(PlmChangeType.class, " ORDER BY sequence, \"createdAt\" LIMIT 1");
        Map<String, Object> type = live.isEmpty()
                ? db.insert(PlmChangeType.class, Obj.of("name", DEFAULT_TYPE, "description",
                        "BOM, routing, product and document changes.", "sequence", 10, "isDefault", true))
                : db.update(PlmChangeType.class, str(live.getFirst(), "id"), Obj.of("isDefault", true));
        // Stages that predate types (or were left without one) join the default pipeline.
        db.jdbc().sql("UPDATE \"PlmChangeStage\" SET \"typeId\" = ?, \"updatedAt\" = now() WHERE \"organizationId\" = ?"
                + " AND \"typeId\" IS NULL AND \"deletedAt\" IS NULL").params(type.get("id"), PlmDb.org()).update();
        ensureStages(str(type, "id"));
        return type;
    }

    Map<String, Object> requireType(String id) {
        Map<String, Object> t = db.byId(PlmChangeType.class, id);
        if (t == null) {
            throw ApiException.notFound("Change type not found");
        }
        return t;
    }

    /** The type of an ECO: its own when still live, else its stage's, else the default type. */
    String typeIdOf(Map<String, Object> eco) {
        Object typeId = eco.get("changeTypeId");
        if (typeId != null && db.byId(PlmChangeType.class, typeId.toString()) != null) {
            return typeId.toString();
        }
        Object stageId = eco.get("stageId");
        if (stageId != null) {
            String stageType = db.jdbc().sql("SELECT \"typeId\" FROM \"PlmChangeStage\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(stageId, PlmDb.org()).query(String.class).optional()
                    .orElse(null);
            if (stageType != null && db.byId(PlmChangeType.class, stageType) != null) {
                return stageType;
            }
        }
        return str(defaultType(), "id");
    }

    /** Seed New → Engineering review → Approved for a type that has no live stage. */
    void ensureStages(String typeId) {
        Long any = db.jdbc().sql("SELECT count(*) FROM \"PlmChangeStage\" WHERE \"organizationId\" = ? AND \"typeId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(PlmDb.org(), typeId).query(Long.class).single();
        if (any > 0) {
            return;
        }
        db.insert(PlmChangeStage.class, Obj.of("typeId", typeId, "name", "New", "sequence", 10, "requiresApproval",
                false, "folded", false, "description", "Draft: prepare the change and its new BOM / routing / documents."));
        db.insert(PlmChangeStage.class, Obj.of("typeId", typeId, "name", "Engineering review", "sequence", 20,
                "requiresApproval", true, "folded", false, "description",
                "Approvers sign the change off before it can be applied."));
        db.insert(PlmChangeStage.class, Obj.of("typeId", typeId, "name", "Approved", "sequence", 30,
                "requiresApproval", false, "folded", false, "description", "Ready to apply (on its effective date)."));
    }

    // ---- pipelines (used by the change services) --------------------------------------------------------------------

    /** Active stages of a type by sequence (the type's default pipeline is seeded when it has none). */
    List<Map<String, Object>> ordered(String typeId) {
        List<Map<String, Object>> stages = db.list(PlmChangeStage.class, " AND \"typeId\" = ? ORDER BY sequence,"
                + " \"createdAt\"", typeId);
        if (!stages.isEmpty()) {
            return stages;
        }
        lock();
        ensureStages(typeId);
        return db.list(PlmChangeStage.class, " AND \"typeId\" = ? ORDER BY sequence, \"createdAt\"", typeId);
    }

    Map<String, Object> first(String typeId) {
        return ordered(typeId).getFirst();
    }

    Map<String, Object> last(String typeId) {
        return ordered(typeId).getLast();
    }

    /** The stage of an ECO (stages of legacy ECOs are resolved as the first / last stage of its type by status). */
    Map<String, Object> stageOf(Map<String, Object> eco) {
        List<Map<String, Object>> stages = ordered(typeIdOf(eco));
        Object id = eco.get("stageId");
        for (Map<String, Object> s : stages) {
            if (s.get("id").equals(id)) {
                return s;
            }
        }
        return "draft".equals(eco.get("status")) ? stages.getFirst() : stages.getLast();
    }

    /** The next stage of the same type, or null at the end of the pipeline. */
    Map<String, Object> next(Map<String, Object> stage) {
        int sq = (Integer) stage.get("sequence");
        for (Map<String, Object> s : ordered(str(stage, "typeId"))) {
            if ((Integer) s.get("sequence") > sq) {
                return s;
            }
        }
        return null;
    }

    List<Map<String, Object>> approversOf(String stageId) {
        return db.list(PlmChangeStageApprover.class, " AND \"stageId\" = ? ORDER BY \"createdAt\"", stageId);
    }

    Map<String, Object> require(String id) {
        Map<String, Object> s = db.byId(PlmChangeStage.class, id);
        if (s == null) {
            throw ApiException.notFound("Change stage not found");
        }
        return s;
    }

    private void lock() {
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)::text").param("plm-stages:" + PlmDb.org())
                .query().listOfRows();
    }

    private void uniqueName(String typeId, String name, String exceptId) {
        for (Map<String, Object> s : db.list(PlmChangeStage.class, " AND \"typeId\" = ? AND lower(name) = lower(?)",
                typeId, name)) {
            if (!s.get("id").equals(exceptId)) {
                throw ApiException.conflict("A stage named " + name + " already exists.");
            }
        }
    }
}
