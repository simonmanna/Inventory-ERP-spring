package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.CrushingCommands.PlantInput;
import com.inventoryerp.manufacturing.application.CrushingCommands.StageInput;
import com.inventoryerp.manufacturing.application.CrushingCommands.UnitInput;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateRouting;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateWorkCenter;
import com.inventoryerp.manufacturing.application.MfgCommands.RoutingOperationInput;
import com.inventoryerp.manufacturing.application.StageCommands.RoutingStageSetup;
import com.inventoryerp.manufacturing.application.StageCommands.StageSetupInput;
import com.inventoryerp.manufacturing.domain.CrushingPlant;
import com.inventoryerp.manufacturing.domain.CrushingPlantStage;
import com.inventoryerp.manufacturing.domain.CrushingPlantUnit;
import com.inventoryerp.manufacturing.domain.RoutingOperation;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crushing plants and their circuits (ADR-S016): the plant (site, default ROM feed stockpile, design capacity, the
 * diesel / kWh / labour rates that cost a run), its ordered stages (primary crush → secondary → screening ...) and
 * the equipment of each stage. Every unit is a manufacturing work centre; the stages are the operations of a
 * plant routing with a manual-issue stage set-up (ADR-S011). Changing the stages creates a new routing version, so
 * grading profiles activated earlier keep the circuit they were built on.
 */
@Service
public class CrushingPlantService {

    private final CrushingSupport rows;
    private final MfgDb db;
    private final SequenceService seq;
    private final AuditService audit;
    private final WorkCenterService workCenters;
    private final RoutingService routings;
    private final StageSetupService stageSetup;

    public CrushingPlantService(CrushingSupport rows, SequenceService seq, AuditService audit,
            WorkCenterService workCenters, RoutingService routings, StageSetupService stageSetup) {
        this.rows = rows;
        this.db = rows.db();
        this.seq = seq;
        this.audit = audit;
        this.workCenters = workCenters;
        this.routings = routings;
        this.stageSetup = stageSetup;
    }

    private static final String SELECT = """
            SELECT p.*, s.code AS "siteCode", s.name AS "siteName", k.code AS "feedStockpileCode",
                   k.name AS "feedStockpileName",
                   (SELECT count(*) FROM "CrushingPlantStage" st WHERE st."plantId" = p.id) AS "stageCount",
                   (SELECT count(*) FROM "CrushingPlantUnit" u WHERE u."plantId" = p.id AND u."isActive") AS "unitCount",
                   (SELECT count(*) FROM "CrushingGradingProfile" g WHERE g."plantId" = p.id AND g.status = 'active'
                       AND g."deletedAt" IS NULL) AS "activeProfileCount"
            FROM "CrushingPlant" p
            LEFT JOIN "QuarrySite" s ON s.id = p."siteId"
            LEFT JOIN "QuarryStockpile" k ON k.id = p."feedStockpileId"
            WHERE p."organizationId" = ? AND p."deletedAt" IS NULL""";

    /** Plants in code order (inactive ones too when asked). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Boolean includeInactive) {
        return SqlRows.list(db.jdbc().sql(SELECT + (Boolean.TRUE.equals(includeInactive) ? "" : " AND p.\"isActive\"")
                + " ORDER BY p.code").params(MfgDb.org()));
    }

    /** The plant with its circuit (stages in order, each with its units) and its grading profiles. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> plant = SqlRows.one(db.jdbc().sql(SELECT + " AND p.id = ?").params(MfgDb.org(), id));
        if (plant == null) {
            throw ApiException.notFound("Crushing plant not found");
        }
        Map<String, Object> out = new LinkedHashMap<>(plant);
        List<Map<String, Object>> units = SqlRows.list(db.jdbc().sql("SELECT u.*, w.code AS \"workCenterCode\" FROM"
                + " \"CrushingPlantUnit\" u LEFT JOIN \"WorkCenter\" w ON w.id = u.\"workCenterId\" WHERE"
                + " u.\"organizationId\" = ? AND u.\"plantId\" = ? ORDER BY u.\"sortOrder\", u.code")
                .params(MfgDb.org(), id));
        List<Map<String, Object>> stages = new ArrayList<>();
        for (Map<String, Object> s : stages(id)) {
            Map<String, Object> row = new LinkedHashMap<>(s);
            row.put("units", units.stream().filter(u -> s.get("id").equals(u.get("stageId"))).toList());
            stages.add(row);
        }
        out.put("stages", stages);
        out.put("inactiveUnits", units.stream().filter(u -> u.get("stageId") == null).toList());
        out.put("profiles", SqlRows.list(db.jdbc().sql("SELECT g.id, g.code, g.name, g.version, g.status,"
                + " g.\"expectedRecoveryPct\", g.\"feedProductId\", p.name AS \"feedProductName\" FROM"
                + " \"CrushingGradingProfile\" g JOIN \"Product\" p ON p.id = g.\"feedProductId\" WHERE"
                + " g.\"organizationId\" = ? AND g.\"plantId\" = ? AND g.\"deletedAt\" IS NULL"
                + " ORDER BY g.code, g.version DESC").params(MfgDb.org(), id)));
        return out;
    }

    List<Map<String, Object>> stages(String plantId) {
        return SqlRows.list(CrushingPlantStage.class, db.jdbc().sql("SELECT * FROM \"CrushingPlantStage\" WHERE"
                + " \"organizationId\" = ? AND \"plantId\" = ? ORDER BY sequence").params(MfgDb.org(), plantId));
    }

    List<Map<String, Object>> units(String plantId) {
        return SqlRows.list(CrushingPlantUnit.class, db.jdbc().sql("SELECT * FROM \"CrushingPlantUnit\" WHERE"
                + " \"organizationId\" = ? AND \"plantId\" = ? ORDER BY \"sortOrder\", code").params(MfgDb.org(), plantId));
    }

    @Transactional
    public Map<String, Object> create(PlantInput in) {
        String code = CrushingSupport.text(in.code());
        code = code == null ? seq.next("crushing_plant", "CP-", 3) : code.toUpperCase();
        assertFreeCode(code, null);
        Map<String, Object> data = data(in);
        data.put("code", code);
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> plant = db.insert(CrushingPlant.class, data);
        syncCircuit(plant, in.stages());
        audit.recordInTx(AuditInput.of("CrushingPlant", str(plant, "id"), "create", null, Obj.of("code", code,
                "stages", in.stages().size())));
        return get(str(plant, "id"));
    }

    @Transactional
    public Map<String, Object> update(String id, PlantInput in) {
        Map<String, Object> before = rows.require(CrushingPlant.class, id, true, "Crushing plant not found");
        String code = CrushingSupport.text(in.code());
        code = code == null ? str(before, "code") : code.toUpperCase();
        assertFreeCode(code, id);
        Map<String, Object> data = data(in);
        data.put("code", code);
        Map<String, Object> plant = db.update(CrushingPlant.class, id, data);
        syncCircuit(plant, in.stages());
        audit.recordInTx(AuditInput.of("CrushingPlant", id, "update", Obj.of("code", before.get("code")),
                Obj.of("code", code, "stages", in.stages().size())));
        return get(id);
    }

    /** Soft delete; refused while the plant has draft runs. */
    @Transactional
    public void remove(String id) {
        Map<String, Object> plant = rows.require(CrushingPlant.class, id, true, "Crushing plant not found");
        long drafts = db.jdbc().sql("SELECT count(*) FROM \"CrushingRun\" WHERE \"organizationId\" = ? AND"
                + " \"plantId\" = ? AND status = 'draft' AND \"deletedAt\" IS NULL").params(MfgDb.org(), id)
                .query(Long.class).single();
        if (drafts > 0) {
            throw ApiException.badRequest("Plant " + plant.get("code") + " has " + drafts
                    + " draft run(s); post or delete them first");
        }
        db.update(CrushingPlant.class, id, Obj.of("deletedAt", Instant.now(), "isActive", false, "updatedBy",
                TenantContext.userId()));
        audit.recordInTx(AuditInput.of("CrushingPlant", id, "delete", Obj.of("code", plant.get("code")), null));
    }

    private Map<String, Object> data(PlantInput in) {
        String siteId = CrushingSupport.text(in.siteId());
        if (siteId != null && db.jdbc().sql("SELECT count(*) FROM \"QuarrySite\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(siteId, MfgDb.org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Quarry site not found");
        }
        String feed = CrushingSupport.text(in.feedStockpileId());
        if (feed != null) {
            Map<String, Object> k = rows.stockpile(feed);
            if (siteId != null && !siteId.equals(k.get("siteId"))) {
                throw ApiException.badRequest("The feed stockpile " + k.get("code") + " belongs to another site");
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", in.name().trim());
        d.put("siteId", siteId);
        d.put("feedStockpileId", feed);
        d.put("designCapacityTph", in.designCapacityTph());
        d.put("dieselCostPerLitre", CrushingSupport.nz(in.dieselCostPerLitre()));
        d.put("electricityCostPerKwh", CrushingSupport.nz(in.electricityCostPerKwh()));
        d.put("labourRatePerHour", CrushingSupport.nz(in.labourRatePerHour()));
        d.put("isActive", in.isActive() == null || in.isActive());
        d.put("notes", CrushingSupport.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }

    private void assertFreeCode(String code, String exceptId) {
        long n = db.jdbc().sql("SELECT count(*) FROM \"CrushingPlant\" WHERE \"organizationId\" = ? AND code = ?"
                + " AND \"deletedAt\" IS NULL AND id <> ?").params(MfgDb.org(), code, exceptId == null ? "" : exceptId)
                .query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict("A crushing plant with code " + code + " already exists");
        }
    }

    /**
     * Upsert the stages (by sequence) and units (by code; units left out are deactivated), create the work centre of
     * each new unit, and — when the stage list changed — a new routing version with a manual-issue stage set-up.
     */
    private void syncCircuit(Map<String, Object> plant, List<StageInput> input) {
        String plantId = str(plant, "id");
        String plantCode = str(plant, "code");
        Set<String> codes = new HashSet<>();
        for (StageInput s : input) {
            if (s.units() != null) {
                for (UnitInput u : s.units()) {
                    if (!codes.add(u.code().trim().toUpperCase())) {
                        throw ApiException.badRequest("Unit code " + u.code().trim().toUpperCase()
                                + " is used twice in the circuit");
                    }
                }
            }
        }
        String before = signature(plantId);
        Map<Integer, Map<String, Object>> existing = new HashMap<>();
        stages(plantId).forEach(s -> existing.put((Integer) s.get("sequence"), s));
        Map<String, Map<String, Object>> unitsByCode = new HashMap<>();
        units(plantId).forEach(u -> unitsByCode.put(str(u, "code"), u));
        Set<String> kept = new HashSet<>();
        int sort = 0;
        for (int i = 0; i < input.size(); i++) {
            StageInput s = input.get(i);
            int sequence = i + 1;
            Map<String, Object> sd = new LinkedHashMap<>();
            sd.put("name", s.name().trim());
            sd.put("kind", s.kind() == null ? "other" : s.kind());
            sd.put("capacityTph", s.capacityTph());
            Map<String, Object> stage = existing.get(sequence);
            if (stage == null) {
                sd.put("plantId", plantId);
                sd.put("sequence", sequence);
                stage = db.insert(CrushingPlantStage.class, sd);
            } else {
                stage = db.update(CrushingPlantStage.class, str(stage, "id"), sd);
            }
            for (UnitInput u : s.units() == null ? List.<UnitInput>of() : s.units()) {
                String code = u.code().trim().toUpperCase();
                kept.add(code);
                Map<String, Object> ud = new LinkedHashMap<>();
                ud.put("stageId", stage.get("id"));
                ud.put("name", u.name().trim());
                ud.put("kind", u.kind() == null ? "other" : u.kind());
                ud.put("capacityTph", u.capacityTph());
                ud.put("powerKw", u.powerKw());
                ud.put("meterKind", u.meterKind() == null ? "engine_hours" : u.meterKind());
                ud.put("isActive", u.isActive() == null || u.isActive());
                ud.put("sortOrder", sort++);
                ud.put("notes", CrushingSupport.text(u.notes()));
                Map<String, Object> unit = unitsByCode.get(code);
                if (unit == null) {
                    ud.put("plantId", plantId);
                    ud.put("code", code);
                    ud.put("workCenterId", workCenter(plantCode + "-" + code, u.name().trim() + " (" + plantCode + ")"));
                    db.insert(CrushingPlantUnit.class, ud);
                } else {
                    db.update(CrushingPlantUnit.class, str(unit, "id"), ud);
                }
            }
        }
        for (Map<String, Object> u : unitsByCode.values()) {
            if (!kept.contains(str(u, "code"))) {
                db.update(CrushingPlantUnit.class, str(u, "id"), Obj.of("stageId", null, "isActive", false));
            }
        }
        db.jdbc().sql("DELETE FROM \"CrushingPlantStage\" WHERE \"organizationId\" = ? AND \"plantId\" = ? AND sequence > ?")
                .params(MfgDb.org(), plantId, input.size()).update();
        if (plant.get("routingId") == null || !before.equals(signature(plantId))) {
            newRouting(plant);
        }
    }

    /** Stage names and their lead work centre — a change means a new routing version. */
    private String signature(String plantId) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> s : stages(plantId)) {
            sb.append(s.get("sequence")).append(':').append(s.get("name")).append(':').append(leadWorkCenter(str(s, "id")))
                    .append('|');
        }
        return sb.toString();
    }

    private String leadWorkCenter(String stageId) {
        return db.jdbc().sql("SELECT \"workCenterId\" FROM \"CrushingPlantUnit\" WHERE \"organizationId\" = ? AND"
                + " \"stageId\" = ? AND \"isActive\" ORDER BY \"sortOrder\", code LIMIT 1").params(MfgDb.org(), stageId)
                .query(String.class).optional().orElse(null);
    }

    private String workCenter(String code, String name) {
        String found = db.jdbc().sql("SELECT id FROM \"WorkCenter\" WHERE \"organizationId\" = ? AND code = ?"
                + " AND \"deletedAt\" IS NULL LIMIT 1").params(MfgDb.org(), code).query(String.class).optional()
                .orElse(null);
        if (found != null) {
            return found;
        }
        return str(workCenters.create(new CreateWorkCenter(code, name, "Crushing plant equipment", true)), "id");
    }

    private void newRouting(Map<String, Object> plant) {
        String plantId = str(plant, "id");
        int version = (Integer) db.byId(CrushingPlant.class, plantId).get("circuitVersion") + 1;
        List<Map<String, Object>> stages = stages(plantId);
        List<RoutingOperationInput> ops = new ArrayList<>();
        for (Map<String, Object> s : stages) {
            ops.add(new RoutingOperationInput((Integer) s.get("sequence"), str(s, "name"), leadWorkCenter(str(s, "id")),
                    0, 0, "Crushing circuit stage (" + s.get("kind") + ")", false, null, BigDecimal.ZERO));
        }
        Map<String, Object> routing = routings.create(new CreateRouting("Crushing circuit " + plant.get("code") + " v"
                + version, null, null, ops));
        String routingId = str(routing, "id");
        List<Map<String, Object>> operations = db.children(RoutingOperation.class, "routingId", routingId,
                "sequence ASC");
        List<StageSetupInput> setups = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            String opId = str(operations.get(i), "id");
            setups.add(new StageSetupInput(opId, "manual", null, null, null, null, false, null, List.of(), null));
            db.update(CrushingPlantStage.class, str(stages.get(i), "id"), Obj.of("routingOperationId", opId));
        }
        stageSetup.saveRoutingSetup(routingId, new RoutingStageSetup(setups));
        if (plant.get("routingId") != null) {
            db.jdbc().sql("UPDATE \"Routing\" SET \"isActive\" = false, \"updatedAt\" = " + MfgDb.NOW
                    + " WHERE id = ? AND \"organizationId\" = ?").params(plant.get("routingId"), MfgDb.org()).update();
        }
        db.update(CrushingPlant.class, plantId, Obj.of("routingId", routingId, "circuitVersion", version));
    }
}
