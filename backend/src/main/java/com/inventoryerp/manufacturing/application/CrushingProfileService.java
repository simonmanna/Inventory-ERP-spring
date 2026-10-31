package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.CrushingCommands.GradingLineInput;
import com.inventoryerp.manufacturing.application.CrushingCommands.ProfileInput;
import com.inventoryerp.manufacturing.application.MfgCommands.BomLineInput;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateBom;
import com.inventoryerp.manufacturing.application.StageCommands.AllocationRuleInput;
import com.inventoryerp.manufacturing.application.StageCommands.BomStageSetup;
import com.inventoryerp.manufacturing.application.StageCommands.CoProductInput;
import com.inventoryerp.manufacturing.domain.Bom;
import com.inventoryerp.manufacturing.domain.CrushingGradingLine;
import com.inventoryerp.manufacturing.domain.CrushingGradingProfile;
import com.inventoryerp.manufacturing.domain.CrushingPlant;
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
 * Grading profiles (ADR-S016): the expected yield / grading split of a plant × feed material — one line per size
 * fraction (main product, co-products, by-products such as dust, waste / fines), the circuit stage that yields it,
 * its stockpile and its valuation. Versioned: drafts are edited; activating builds a <b>system-managed BOM</b> (feed
 * 100 t → main fraction %, co-products / by-products / waste per stage with their stockpile locations, the profile's
 * joint-cost allocation rule) on the plant's current routing and archives the previous active version.
 */
@Service
public class CrushingProfileService {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CrushingSupport rows;
    private final MfgDb db;
    private final SequenceService seq;
    private final AuditService audit;
    private final BomService boms;
    private final StageSetupService stageSetup;
    private final CrushingPlantService plants;

    public CrushingProfileService(CrushingSupport rows, SequenceService seq, AuditService audit, BomService boms,
            StageSetupService stageSetup, CrushingPlantService plants) {
        this.rows = rows;
        this.db = rows.db();
        this.seq = seq;
        this.audit = audit;
        this.boms = boms;
        this.stageSetup = stageSetup;
        this.plants = plants;
    }

    private static final String SELECT = """
            SELECT g.*, p.code AS "plantCode", p.name AS "plantName", f.code AS "feedProductCode",
                   f.name AS "feedProductName", b.code AS "bomCode",
                   (SELECT count(*) FROM "CrushingGradingLine" l WHERE l."profileId" = g.id) AS "lineCount"
            FROM "CrushingGradingProfile" g
            JOIN "CrushingPlant" p ON p.id = g."plantId"
            JOIN "Product" f ON f.id = g."feedProductId"
            LEFT JOIN "Bom" b ON b.id = g."bomId"
            WHERE g."organizationId" = ? AND g."deletedAt" IS NULL""";

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String plantId, String status) {
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        StringBuilder extra = new StringBuilder();
        if (CrushingSupport.text(plantId) != null) {
            extra.append(" AND g.\"plantId\" = ?");
            params.add(plantId);
        }
        if (CrushingSupport.text(status) != null) {
            extra.append(" AND g.status = ?");
            params.add(status);
        }
        return SqlRows.list(db.jdbc().sql(SELECT + extra + " ORDER BY p.code, g.code, g.version DESC").params(params));
    }

    /** The profile with its lines (product, stockpile) and the plant's circuit stages. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> g = SqlRows.one(db.jdbc().sql(SELECT + " AND g.id = ?").params(MfgDb.org(), id));
        if (g == null) {
            throw ApiException.notFound("Grading profile not found");
        }
        Map<String, Object> out = new LinkedHashMap<>(g);
        out.put("lines", lines(id));
        out.put("stages", plants.stages(str(g, "plantId")));
        return out;
    }

    /** Lines in order with product and stockpile labels. */
    List<Map<String, Object>> lines(String profileId) {
        return SqlRows.list(db.jdbc().sql("""
                SELECT l.*, p.code AS "productCode", p.name AS "productName", k.code AS "stockpileCode",
                       k.name AS "stockpileName", k."locationId" AS "locationId"
                FROM "CrushingGradingLine" l JOIN "Product" p ON p.id = l."productId"
                LEFT JOIN "QuarryStockpile" k ON k.id = l."stockpileId"
                WHERE l."organizationId" = ? AND l."profileId" = ? ORDER BY l."sortOrder\"""").params(MfgDb.org(),
                profileId));
    }

    /** The active profile of a plant (when it has exactly one), else null. */
    Map<String, Object> soleActive(String plantId) {
        List<Map<String, Object>> active = db.list(CrushingGradingProfile.class, " AND \"plantId\" = ? AND status ="
                + " 'active' AND \"deletedAt\" IS NULL", plantId);
        return active.size() == 1 ? active.getFirst() : null;
    }

    @Transactional
    public Map<String, Object> create(ProfileInput in) {
        Map<String, Object> plant = rows.require(CrushingPlant.class, in.plantId(), false, "Crushing plant not found");
        rows.product(in.feedProductId());
        String code = CrushingSupport.text(in.code());
        code = code == null ? seq.next("crushing_profile", "GP-", 4) : code.toUpperCase();
        long taken = db.jdbc().sql("SELECT count(*) FROM \"CrushingGradingProfile\" WHERE \"organizationId\" = ? AND"
                + " code = ?").params(MfgDb.org(), code).query(Long.class).single();
        if (taken > 0) {
            throw ApiException.conflict("A grading profile with code " + code + " already exists; create a new"
                    + " version of it instead");
        }
        validateLines(in.lines(), in.feedProductId(), plants.stages(str(plant, "id")).size(), false);
        Map<String, Object> d = header(in);
        d.put("code", code);
        d.put("version", 1);
        d.put("status", "draft");
        d.put("createdBy", TenantContext.userId());
        Map<String, Object> g = db.insert(CrushingGradingProfile.class, d);
        writeLines(str(g, "id"), in.lines());
        audit.recordInTx(AuditInput.of("CrushingGradingProfile", str(g, "id"), "create", null, Obj.of("code", code,
                "lines", in.lines().size())));
        return get(str(g, "id"));
    }

    @Transactional
    public Map<String, Object> update(String id, ProfileInput in) {
        Map<String, Object> g = rows.require(CrushingGradingProfile.class, id, true, "Grading profile not found");
        draftOnly(g);
        Map<String, Object> plant = rows.require(CrushingPlant.class, in.plantId(), false, "Crushing plant not found");
        rows.product(in.feedProductId());
        validateLines(in.lines(), in.feedProductId(), plants.stages(str(plant, "id")).size(), false);
        db.update(CrushingGradingProfile.class, id, header(in));
        db.jdbc().sql("DELETE FROM \"CrushingGradingLine\" WHERE \"organizationId\" = ? AND \"profileId\" = ?")
                .params(MfgDb.org(), id).update();
        writeLines(id, in.lines());
        audit.recordInTx(AuditInput.of("CrushingGradingProfile", id, "update", null, Obj.of("lines",
                in.lines().size())));
        return get(id);
    }

    @Transactional
    public void remove(String id) {
        Map<String, Object> g = rows.require(CrushingGradingProfile.class, id, true, "Grading profile not found");
        draftOnly(g);
        db.update(CrushingGradingProfile.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy",
                TenantContext.userId()));
        audit.recordInTx(AuditInput.of("CrushingGradingProfile", id, "delete", Obj.of("code", g.get("code")), null));
    }

    /** Copy a profile (any status) into a new draft version of the same code. */
    @Transactional
    public Map<String, Object> newVersion(String id) {
        Map<String, Object> g = rows.require(CrushingGradingProfile.class, id, false, "Grading profile not found");
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("crushing-profile:" + MfgDb.org()
                + ":" + g.get("code")).query().listOfRows();
        Integer max = db.jdbc().sql("SELECT max(version) FROM \"CrushingGradingProfile\" WHERE \"organizationId\" = ?"
                + " AND code = ?").params(MfgDb.org(), g.get("code")).query(Integer.class).single();
        Map<String, Object> d = new LinkedHashMap<>();
        for (String k : List.of("plantId", "code", "name", "feedProductId", "allocationMethod", "notes")) {
            d.put(k, g.get(k));
        }
        d.put("version", max + 1);
        d.put("status", "draft");
        d.put("createdBy", TenantContext.userId());
        d.put("updatedBy", TenantContext.userId());
        Map<String, Object> copy = db.insert(CrushingGradingProfile.class, d);
        for (Map<String, Object> l : db.list(CrushingGradingLine.class, " AND \"profileId\" = ? ORDER BY \"sortOrder\"",
                id)) {
            Map<String, Object> row = new LinkedHashMap<>(l);
            row.remove("id");
            row.remove("createdAt");
            row.remove("updatedAt");
            row.put("profileId", copy.get("id"));
            db.insert(CrushingGradingLine.class, row);
        }
        audit.recordInTx(AuditInput.of("CrushingGradingProfile", str(copy, "id"), "create", null, Obj.of("code",
                g.get("code"), "version", max + 1, "copiedFrom", id)));
        return get(str(copy, "id"));
    }

    /**
     * Activate a draft: strict validation, then the system-managed BOM (+ co-products and allocation rule) on the
     * plant's current routing; the previous active version of the same plant × feed (and its BOM) is archived.
     */
    @Transactional
    public Map<String, Object> activate(String id) {
        Map<String, Object> g = rows.require(CrushingGradingProfile.class, id, true, "Grading profile not found");
        draftOnly(g);
        Map<String, Object> plant = rows.require(CrushingPlant.class, str(g, "plantId"), true,
                "Crushing plant not found");
        if (!Boolean.TRUE.equals(plant.get("isActive"))) {
            throw ApiException.badRequest("Plant " + plant.get("code") + " is inactive");
        }
        String routingId = str(plant, "routingId");
        List<Map<String, Object>> stages = plants.stages(str(plant, "id"));
        if (routingId == null || stages.isEmpty()) {
            throw ApiException.badRequest("Plant " + plant.get("code") + " has no circuit; set up its stages first");
        }
        List<Map<String, Object>> lines = lines(id);
        List<GradingLineInput> inputs = lines.stream().map(CrushingProfileService::input).toList();
        validateLines(inputs, str(g, "feedProductId"), stages.size(), true);
        Map<String, Object> main = lines.stream().filter(l -> "main".equals(l.get("role"))).findFirst().orElseThrow();
        BigDecimal recovery = lines.stream().map(l -> dec(l, "expectedPct")).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Previous active version (same plant × feed) and its BOM.
        for (Map<String, Object> old : db.list(CrushingGradingProfile.class, " AND \"plantId\" = ? AND \"feedProductId\""
                + " = ? AND status = 'active' AND \"deletedAt\" IS NULL AND id <> ?", g.get("plantId"),
                g.get("feedProductId"), id)) {
            db.update(CrushingGradingProfile.class, str(old, "id"), Obj.of("status", "archived", "updatedBy",
                    TenantContext.userId()));
            if (old.get("bomId") != null) {
                db.update(Bom.class, str(old, "bomId"), Obj.of("status", "archived"));
            }
        }

        BigDecimal mainPct = dec(main, "expectedPct");
        Map<String, Object> bom = boms.create(new CreateBom("Crushing " + g.get("code") + " v" + g.get("version")
                + " · " + plant.get("code") + " (system)", str(main, "productId"), null, mainPct, null, HUNDRED,
                BigDecimal.ZERO, null, str(main, "locationId"), null, null, false, false, null, null,
                "System-managed BOM of crushing grading profile " + g.get("code") + " v" + g.get("version")
                        + ". Edit the profile, not this BOM.",
                List.of(new BomLineInput(str(g, "feedProductId"), null, HUNDRED, null, BigDecimal.ZERO, 0, false,
                        "Feed (100 t)")), routingId));
        String bomId = str(bom, "id");
        Map<Integer, String> opBySeq = new HashMap<>();
        stages.forEach(s -> opBySeq.put((Integer) s.get("sequence"), str(s, "routingOperationId")));
        boolean weight = "weight".equals(g.get("allocationMethod"));
        List<CoProductInput> co = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            if ("main".equals(l.get("role"))) {
                continue;
            }
            Integer stage = (Integer) l.get("stageSequence");
            String op = stage == null || stage >= stages.size() ? null : opBySeq.get(stage);
            boolean stocked = Boolean.TRUE.equals(l.get("stocked"));
            co.add(new CoProductInput(str(l, "productId"), str(l, "role"), op, dec(l, "expectedPct"), null,
                    str(l, "valuation"), decOrNull(l, "nrvUnitValue"), decOrNull(l, "marketValue"),
                    weight ? BigDecimal.ONE : null, decOrNull(l, "fixedPct"), stocked,
                    stocked ? str(l, "locationId") : null, l.get("sizeLabel") == null ? null : str(l, "sizeLabel")));
        }
        stageSetup.saveBomSetup(bomId, new BomStageSetup(co, new AllocationRuleInput(str(g, "allocationMethod"),
                weight ? BigDecimal.ONE : null, decOrNull(main, "marketValue"), "Crushing profile " + g.get("code"))));
        // System-managed: active without the approval gate and without archiving other BOMs of the same output.
        db.update(Bom.class, bomId, Obj.of("status", "active", "isDefault", false, "updatedBy", TenantContext.userId()));
        db.update(CrushingGradingProfile.class, id, Obj.of("status", "active", "bomId", bomId, "routingId", routingId,
                "expectedRecoveryPct", recovery, "activatedAt", Instant.now(), "activatedBy", TenantContext.userId(),
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("CrushingGradingProfile", id, "approve", null, Obj.of("code", g.get("code"),
                "version", g.get("version"), "bomId", bomId, "expectedRecoveryPct", recovery.toPlainString())));
        return get(id);
    }

    /** Archive an active profile (no new runs; posted runs keep it). */
    @Transactional
    public Map<String, Object> archive(String id) {
        Map<String, Object> g = rows.require(CrushingGradingProfile.class, id, true, "Grading profile not found");
        if (!"active".equals(g.get("status"))) {
            throw ApiException.badRequest("Only an active grading profile can be archived");
        }
        db.update(CrushingGradingProfile.class, id, Obj.of("status", "archived", "updatedBy", TenantContext.userId()));
        if (g.get("bomId") != null) {
            db.update(Bom.class, str(g, "bomId"), Obj.of("status", "archived"));
        }
        audit.recordInTx(AuditInput.of("CrushingGradingProfile", id, "cancel", null, Obj.of("code", g.get("code"))));
        return get(id);
    }

    // ------------------------------------------------------------------ internals

    private static void draftOnly(Map<String, Object> g) {
        if (!"draft".equals(g.get("status"))) {
            throw ApiException.badRequest("Grading profile " + g.get("code") + " v" + g.get("version") + " is "
                    + g.get("status") + "; create a new version to change it");
        }
    }

    private Map<String, Object> header(ProfileInput in) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("plantId", in.plantId());
        d.put("name", in.name().trim());
        d.put("feedProductId", in.feedProductId());
        d.put("allocationMethod", in.allocationMethod() == null ? "quantity" : in.allocationMethod());
        d.put("expectedRecoveryPct", in.lines().stream().map(GradingLineInput::expectedPct)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        d.put("notes", CrushingSupport.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }

    private void writeLines(String profileId, List<GradingLineInput> lines) {
        int i = 0;
        for (GradingLineInput l : lines) {
            boolean stocked = l.stocked() == null || l.stocked();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("profileId", profileId);
            d.put("productId", l.productId());
            d.put("sizeLabel", CrushingSupport.text(l.sizeLabel()));
            d.put("role", l.role());
            d.put("stageSequence", "main".equals(l.role()) ? null : l.stageSequence());
            d.put("expectedPct", l.expectedPct());
            d.put("stocked", stocked);
            d.put("stockpileId", stocked ? CrushingSupport.text(l.stockpileId()) : null);
            d.put("valuation", valuation(l, stocked));
            d.put("nrvUnitValue", l.nrvUnitValue());
            d.put("marketValue", l.marketValue());
            d.put("fixedPct", l.fixedPct());
            d.put("sortOrder", i++);
            db.insert(CrushingGradingLine.class, d);
        }
    }

    static String valuation(GradingLineInput l, boolean stocked) {
        if (!stocked) {
            return "zero";
        }
        if (l.valuation() != null) {
            return l.valuation();
        }
        return switch (l.role()) {
            case "by_product" -> "nrv";
            case "waste" -> "zero";
            default -> "allocated";
        };
    }

    /**
     * Line rules (strict = at activation): one main fraction, stocked; distinct products, none the feed; Σ % ≤ 100;
     * stage within the circuit; a stocked line needs a stockpile; co-products share the joint cost.
     */
    void validateLines(List<GradingLineInput> lines, String feedProductId, int stageCount, boolean strict) {
        Set<String> products = new HashSet<>();
        int mains = 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (GradingLineInput l : lines) {
            rows.product(l.productId());
            if (l.productId().equals(feedProductId)) {
                throw ApiException.badRequest("The feed material cannot also be an output fraction");
            }
            if (!products.add(l.productId())) {
                throw ApiException.badRequest("Product " + rows.product(l.productId()).get("name")
                        + " is listed twice");
            }
            boolean stocked = l.stocked() == null || l.stocked();
            if ("main".equals(l.role())) {
                mains++;
                if (!stocked) {
                    throw ApiException.badRequest("The main fraction must be stocked");
                }
            }
            if (l.stageSequence() != null && stageCount > 0 && l.stageSequence() > stageCount) {
                throw ApiException.badRequest("Stage " + l.stageSequence() + " is not part of the plant's circuit ("
                        + stageCount + " stages)");
            }
            if (stocked && CrushingSupport.text(l.stockpileId()) != null) {
                rows.stockpile(l.stockpileId());
            } else if (stocked && strict) {
                throw ApiException.badRequest("Choose the stockpile of " + rows.product(l.productId()).get("name"));
            }
            String valuation = valuation(l, stocked);
            if ("co_product".equals(l.role()) && !"allocated".equals(valuation)) {
                throw ApiException.badRequest("Co-products share the joint cost; use valuation 'allocated'");
            }
            if ("main".equals(l.role()) && !"allocated".equals(valuation)) {
                throw ApiException.badRequest("The main fraction carries the joint cost; use valuation 'allocated'");
            }
            sum = sum.add(l.expectedPct());
        }
        if (mains != 1) {
            throw ApiException.badRequest("A grading profile needs exactly one main fraction");
        }
        if (sum.compareTo(HUNDRED) > 0) {
            throw ApiException.badRequest("The expected split adds up to " + sum.stripTrailingZeros().toPlainString()
                    + " %; it cannot exceed 100 %");
        }
    }

    private static GradingLineInput input(Map<String, Object> l) {
        return new GradingLineInput(str(l, "productId"), str(l, "sizeLabel"), str(l, "role"),
                (Integer) l.get("stageSequence"), dec(l, "expectedPct"), (Boolean) l.get("stocked"),
                str(l, "stockpileId"), str(l, "valuation"), decOrNull(l, "nrvUnitValue"), decOrNull(l, "marketValue"),
                decOrNull(l, "fixedPct"));
    }

    static BigDecimal decOrNull(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : dec(row, key);
    }
}
