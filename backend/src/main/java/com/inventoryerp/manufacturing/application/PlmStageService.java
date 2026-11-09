package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.manufacturing.application.PlmCommands.SaveStage;
import com.inventoryerp.manufacturing.application.PlmCommands.StageApprover;
import com.inventoryerp.manufacturing.domain.PlmChangeStage;
import com.inventoryerp.manufacturing.domain.PlmChangeStageApprover;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ECO stages (ADR-S063). Ordered by {@code sequence}; the last stage means "approved — ready to apply". A stage
 * with {@code requiresApproval} cannot be left forwards until its approvals are complete. An organization gets the
 * default pipeline New → Engineering review → Approved the first time it touches ECOs.
 */
@Service
public class PlmStageService {

    private final MfgDb db;
    private final PlmSupport plm;

    public PlmStageService(MfgDb db, PlmSupport plm) {
        this.db = db;
        this.plm = plm;
    }

    /** The organization's stages (created with the defaults when there are none), each with its approvers. */
    @Transactional
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> stages = ordered();
        List<Map<String, Object>> approvers = db.list(PlmChangeStageApprover.class, " ORDER BY \"createdAt\"");
        Set<String> users = new HashSet<>();
        approvers.forEach(a -> users.add(str(a, "userId")));
        Map<String, String> names = plm.userNames(users);
        Map<String, Long> inUse = new java.util.HashMap<>();
        db.jdbc().sql("SELECT \"stageId\", count(*) AS n FROM \"EngineeringChange\" WHERE \"organizationId\" = ?"
                + " AND status IN ('draft', 'approved') AND \"stageId\" IS NOT NULL GROUP BY \"stageId\"")
                .param(MfgDb.org()).query((rs, i) -> inUse.put(rs.getString(1), rs.getLong(2))).list();
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
        }
        return stages;
    }

    @Transactional
    public Map<String, Object> create(SaveStage dto) {
        String name = PlmSupport.blank(dto.name());
        if (name == null) {
            throw ApiException.badRequest("name should not be empty");
        }
        List<Map<String, Object>> stages = ordered();
        uniqueName(name, null);
        int seq = dto.sequence() != null ? dto.sequence()
                : stages.isEmpty() ? 10 : ((Integer) stages.getLast().get("sequence")) + 10;
        return db.insert(PlmChangeStage.class, Obj.of("name", name, "sequence", seq, "requiresApproval",
                Boolean.TRUE.equals(dto.requiresApproval()), "description", PlmSupport.blank(dto.description())));
    }

    @Transactional
    public Map<String, Object> update(String id, SaveStage dto) {
        require(id);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        if (dto.name() != null) {
            String name = PlmSupport.blank(dto.name());
            if (name == null) {
                throw ApiException.badRequest("name should not be empty");
            }
            uniqueName(name, id);
            data.put("name", name);
        }
        if (dto.sequence() != null) {
            data.put("sequence", dto.sequence());
        }
        if (dto.requiresApproval() != null) {
            data.put("requiresApproval", dto.requiresApproval());
        }
        if (dto.description() != null) {
            data.put("description", PlmSupport.blank(dto.description()));
        }
        return data.isEmpty() ? require(id) : db.update(PlmChangeStage.class, id, data);
    }

    /** Soft delete; refused while an ECO sits in the stage or when fewer than two stages would remain. */
    @Transactional
    public void remove(String id) {
        Map<String, Object> stage = require(id);
        Long used = db.jdbc().sql("SELECT count(*) FROM \"EngineeringChange\" WHERE \"organizationId\" = ?"
                + " AND \"stageId\" = ? AND status IN ('draft', 'approved')").params(MfgDb.org(), id)
                .query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("Stage " + stage.get("name") + " has " + used
                    + " open change(s); move them first.");
        }
        if (ordered().size() <= 2) {
            throw ApiException.badRequest("A change pipeline needs at least two stages.");
        }
        db.update(PlmChangeStage.class, id, Obj.of("deletedAt", Instant.now()));
    }

    /** Replace the approvers of a stage. */
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
                .params(MfgDb.org(), id).update();
        List<Map<String, Object>> out = new ArrayList<>();
        for (StageApprover a : approvers) {
            out.add(db.insert(PlmChangeStageApprover.class, Obj.of("stageId", id, "userId", a.userId(), "kind",
                    a.kind() == null ? "required" : a.kind())));
        }
        return out;
    }

    // ---- used by the change services -------------------------------------------------------------------------------

    /** Active stages by sequence; seeds the default pipeline once per organization. */
    List<Map<String, Object>> ordered() {
        List<Map<String, Object>> stages = db.list(PlmChangeStage.class, " ORDER BY sequence, \"createdAt\"");
        if (!stages.isEmpty()) {
            return stages;
        }
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)::text").param("plm-stages:" + MfgDb.org())
                .query().listOfRows();
        Long any = db.jdbc().sql("SELECT count(*) FROM \"PlmChangeStage\" WHERE \"organizationId\" = ?")
                .param(MfgDb.org()).query(Long.class).single();
        if (any == 0) {
            db.insert(PlmChangeStage.class, Obj.of("name", "New", "sequence", 10, "requiresApproval", false,
                    "description", "Draft: prepare the change and its new BOM / routing / documents."));
            db.insert(PlmChangeStage.class, Obj.of("name", "Engineering review", "sequence", 20, "requiresApproval",
                    true, "description", "Approvers sign the change off before it can be applied."));
            db.insert(PlmChangeStage.class, Obj.of("name", "Approved", "sequence", 30, "requiresApproval", false,
                    "description", "Ready to apply (on its effective date)."));
        }
        return db.list(PlmChangeStage.class, " ORDER BY sequence, \"createdAt\"");
    }

    Map<String, Object> first() {
        return ordered().getFirst();
    }

    Map<String, Object> last() {
        return ordered().getLast();
    }

    /** The stage of an ECO (stages of legacy ECOs are resolved as the first / last stage by status). */
    Map<String, Object> stageOf(Map<String, Object> eco) {
        List<Map<String, Object>> stages = ordered();
        Object id = eco.get("stageId");
        for (Map<String, Object> s : stages) {
            if (s.get("id").equals(id)) {
                return s;
            }
        }
        return "draft".equals(eco.get("status")) ? stages.getFirst() : stages.getLast();
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

    private void uniqueName(String name, String exceptId) {
        for (Map<String, Object> s : db.list(PlmChangeStage.class, " AND lower(name) = lower(?)", name)) {
            if (!s.get("id").equals(exceptId)) {
                throw ApiException.conflict("A stage named " + name + " already exists.");
            }
        }
    }
}
