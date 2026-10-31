package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.manufacturing.application.MfgCommands.BomLineInput;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateBom;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateRouting;
import com.inventoryerp.manufacturing.application.MfgCommands.ReviseBom;
import com.inventoryerp.manufacturing.application.MfgCommands.RoutingOperationInput;
import com.inventoryerp.manufacturing.application.MfgCommands.UpdateBom;
import com.inventoryerp.manufacturing.application.PrecastCommands.CreateMixDesign;
import com.inventoryerp.manufacturing.application.PrecastCommands.CuringAreaInput;
import com.inventoryerp.manufacturing.application.PrecastCommands.MixLine;
import com.inventoryerp.manufacturing.application.PrecastCommands.ProductSpecInput;
import com.inventoryerp.manufacturing.application.PrecastCommands.ReviseMixDesign;
import com.inventoryerp.manufacturing.application.PrecastCommands.UpdateCuringArea;
import com.inventoryerp.manufacturing.application.PrecastCommands.UpdateMixDesign;
import com.inventoryerp.manufacturing.application.StageCommands.RoutingStageSetup;
import com.inventoryerp.manufacturing.application.StageCommands.StageSetupInput;
import com.inventoryerp.manufacturing.domain.BomCoProduct;
import com.inventoryerp.manufacturing.domain.PrecastCuringArea;
import com.inventoryerp.manufacturing.domain.PrecastMixDesign;
import com.inventoryerp.manufacturing.domain.PrecastProductSpec;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Block / precast set-up (ADR-S014, Phase 2.4): product specifications (block type, dimensions, strength class,
 * weight, pallet and cycle quantities, second-grade product), curing areas (chambers / yards / sheds) and mix designs.
 * A mix design is a versioned BOM (output = units per mixer batch, lines = materials per batch) plus its batch
 * parameters (cement bag size, batch volume, w/c ratio, minimum curing days, strength checkpoint); versions follow the
 * BOM's draft → active (approval) → archived lifecycle and the ECO revise flow.
 */
@Service
public class PrecastSetupService {

    static final String DEFAULT_CHECKPOINT = "PRECAST-CURE";

    private final MfgDb db;
    private final BomService boms;
    private final EngineeringService engineering;
    private final RoutingService routings;
    private final StageSetupService stageSetup;
    private final ProductionService production;
    private final AuditService audit;

    public PrecastSetupService(MfgDb db, BomService boms, EngineeringService engineering, RoutingService routings,
            StageSetupService stageSetup, ProductionService production, AuditService audit) {
        this.db = db;
        this.boms = boms;
        this.engineering = engineering;
        this.routings = routings;
        this.stageSetup = stageSetup;
        this.production = production;
        this.audit = audit;
    }

    // ================================================================== product specs

    private static final String SPEC_SELECT = "SELECT s.*, p.name AS \"productName\", p.sku AS \"productSku\","
            + " g.name AS \"secondGradeProductName\" FROM \"PrecastProductSpec\" s"
            + " JOIN \"Product\" p ON p.id = s.\"productId\" AND p.\"organizationId\" = s.\"organizationId\""
            + " LEFT JOIN \"Product\" g ON g.id = s.\"secondGradeProductId\" AND g.\"organizationId\" = s.\"organizationId\"";

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> specs(String search, Integer page, Integer pageSize) {
        int pg = page(page);
        int size = size(pageSize);
        StringBuilder where = new StringBuilder(" WHERE s.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        if (search != null && !search.isBlank()) {
            where.append(" AND (p.name ILIKE ? OR p.sku ILIKE ? OR s.\"strengthClass\" ILIKE ?)");
            String like = "%" + search.trim() + "%";
            params.addAll(List.of(like, like, like));
        }
        long total = db.jdbc().sql("SELECT count(*) FROM (" + SPEC_SELECT + where + ") x").params(params)
                .query(Long.class).single();
        params.add(size);
        params.add((pg - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(SPEC_SELECT + where + " ORDER BY p.name, s.id LIMIT ? OFFSET ?")
                .params(params)), pg, size, total);
    }

    /** The spec of a product, or null. */
    Map<String, Object> specFor(String productId) {
        return SqlRows.one(db.jdbc().sql(SPEC_SELECT + " WHERE s.\"organizationId\" = ? AND s.\"productId\" = ?")
                .params(MfgDb.org(), productId));
    }

    @Transactional
    public Map<String, Object> saveSpec(String productId, ProductSpecInput in) {
        production.getProduct(productId);
        if (in.secondGradeProductId() != null) {
            if (in.secondGradeProductId().equals(productId)) {
                throw ApiException.badRequest("The second-grade product must differ from the block product.");
            }
            production.getProduct(in.secondGradeProductId());
        }
        Map<String, Object> existing = specFor(productId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("blockType", in.blockType() != null ? in.blockType() : existing != null ? existing.get("blockType")
                : "hollow");
        data.put("lengthMm", in.lengthMm());
        data.put("widthMm", in.widthMm());
        data.put("heightMm", in.heightMm());
        data.put("strengthClass", trim(in.strengthClass()));
        data.put("strengthMpa", in.strengthMpa());
        data.put("weightKg", in.weightKg());
        data.put("unitsPerPallet", in.unitsPerPallet() != null ? in.unitsPerPallet() : 1);
        data.put("unitsPerCycle", in.unitsPerCycle() != null ? in.unitsPerCycle() : 1);
        data.put("secondGradeProductId", in.secondGradeProductId());
        data.put("notes", trim(in.notes()));
        data.put("isActive", in.isActive() == null || in.isActive());
        if (existing == null) {
            data.put("productId", productId);
            db.insert(PrecastProductSpec.class, data);
        } else {
            db.update(PrecastProductSpec.class, str(existing, "id"), data);
        }
        audit.recordInTx(AuditInput.of("PrecastProductSpec", productId, existing == null ? "create" : "update", null,
                Obj.of("strengthClass", in.strengthClass(), "unitsPerPallet", data.get("unitsPerPallet"))));
        return specFor(productId);
    }

    // ================================================================== curing areas

    /** Curing areas with their current occupancy (pallets of batches still curing or at the gate). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> areas(Boolean activeOnly) {
        return SqlRows.list(db.jdbc().sql("SELECT a.*, l.name AS \"locationName\", coalesce((SELECT sum(b.pallets)"
                + " FROM \"PrecastCuringBatch\" b WHERE b.\"organizationId\" = a.\"organizationId\""
                + " AND b.\"curingAreaId\" = a.id AND b.status IN ('curing', 'cubed')), 0) AS \"palletsInUse\","
                + " (SELECT count(*) FROM \"PrecastCuringBatch\" b WHERE b.\"organizationId\" = a.\"organizationId\""
                + " AND b.\"curingAreaId\" = a.id AND b.status = 'curing') AS \"batchesCuring\""
                + " FROM \"PrecastCuringArea\" a LEFT JOIN \"InventoryLocation\" l ON l.id = a.\"locationId\""
                + " WHERE a.\"organizationId\" = ?" + (Boolean.TRUE.equals(activeOnly) ? " AND a.\"isActive\"" : "")
                + " ORDER BY a.code").params(MfgDb.org()));
    }

    @Transactional
    public Map<String, Object> createArea(CuringAreaInput in) {
        String code = in.code().trim().toUpperCase();
        long dup = db.jdbc().sql("SELECT count(*) FROM \"PrecastCuringArea\" WHERE \"organizationId\" = ? AND code = ?")
                .params(MfgDb.org(), code).query(Long.class).single();
        if (dup > 0) {
            throw ApiException.conflict("Curing area " + code + " already exists.");
        }
        if (in.locationId() != null) {
            production.requireLocation(in.locationId());
        }
        Map<String, Object> row = db.insert(PrecastCuringArea.class, Obj.of("code", code, "name", in.name().trim(),
                "kind", in.kind() != null ? in.kind() : "yard", "capacityPallets", in.capacityPallets(), "locationId",
                in.locationId(), "targetTemperatureC", in.targetTemperatureC(), "notes", trim(in.notes()), "isActive",
                in.isActive() == null || in.isActive()));
        audit.recordInTx(AuditInput.of("PrecastCuringArea", str(row, "id"), "create", null, Obj.of("code", code)));
        return row;
    }

    @Transactional
    public Map<String, Object> updateArea(String id, UpdateCuringArea in) {
        requireArea(id);
        if (in.locationId() != null) {
            production.requireLocation(in.locationId());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        putIf(data, "name", in.name() == null ? null : in.name().trim());
        putIf(data, "kind", in.kind());
        putIf(data, "capacityPallets", in.capacityPallets());
        putIf(data, "locationId", in.locationId());
        putIf(data, "targetTemperatureC", in.targetTemperatureC());
        putIf(data, "notes", in.notes());
        putIf(data, "isActive", in.isActive());
        if (data.isEmpty()) {
            return requireArea(id);
        }
        Map<String, Object> row = db.update(PrecastCuringArea.class, id, data);
        audit.recordInTx(AuditInput.of("PrecastCuringArea", id, "update", null, data));
        return row;
    }

    Map<String, Object> requireArea(String id) {
        Map<String, Object> a = db.byId(PrecastCuringArea.class, id);
        if (a == null) {
            throw ApiException.notFound("Curing area not found");
        }
        return a;
    }

    // ================================================================== mix designs

    private static final String MIX_SELECT = "SELECT m.*, b.code AS \"bomCode\", b.name, b.version, b.status,"
            + " b.\"outputQuantity\" AS \"unitsPerBatch\", b.\"expectedYieldPct\", b.\"routingId\", b.\"isDefault\","
            + " b.\"defaultLocationId\", b.\"defaultOutputLocationId\", p.name AS \"productName\", p.sku AS \"productSku\","
            + " c.name AS \"cementProductName\", a.name AS \"defaultCuringAreaName\""
            + " FROM \"PrecastMixDesign\" m JOIN \"Bom\" b ON b.id = m.\"bomId\" AND b.\"deletedAt\" IS NULL"
            + " JOIN \"Product\" p ON p.id = m.\"productId\" AND p.\"organizationId\" = m.\"organizationId\""
            + " LEFT JOIN \"Product\" c ON c.id = m.\"cementProductId\" AND c.\"organizationId\" = m.\"organizationId\""
            + " LEFT JOIN \"PrecastCuringArea\" a ON a.id = m.\"defaultCuringAreaId\"";

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> mixDesigns(String search, String productId, String status, Integer page,
            Integer pageSize) {
        int pg = page(page);
        int size = size(pageSize);
        StringBuilder where = new StringBuilder(" WHERE m.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        if (search != null && !search.isBlank()) {
            where.append(" AND (b.name ILIKE ? OR b.code ILIKE ? OR p.name ILIKE ? OR p.sku ILIKE ?)");
            String like = "%" + search.trim() + "%";
            params.addAll(List.of(like, like, like, like));
        }
        if (productId != null && !productId.isBlank()) {
            where.append(" AND m.\"productId\" = ?");
            params.add(productId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND b.status = ?");
            params.add(status);
        }
        long total = db.jdbc().sql("SELECT count(*) FROM (" + MIX_SELECT + where + ") x").params(params)
                .query(Long.class).single();
        params.add(size);
        params.add((pg - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(MIX_SELECT + where
                + " ORDER BY p.name, b.version DESC, m.id LIMIT ? OFFSET ?").params(params)), pg, size, total);
    }

    /** Mix design header (joined with its BOM version) or 404. */
    Map<String, Object> mixRow(String id) {
        Map<String, Object> m = SqlRows.one(db.jdbc().sql(MIX_SELECT + " WHERE m.\"organizationId\" = ? AND m.id = ?")
                .params(MfgDb.org(), id));
        if (m == null) {
            throw ApiException.notFound("Mix design not found");
        }
        return m;
    }

    /** Mix design by BOM id, or null. */
    Map<String, Object> mixForBom(String bomId) {
        return bomId == null ? null : SqlRows.one(db.jdbc().sql(MIX_SELECT
                + " WHERE m.\"organizationId\" = ? AND m.\"bomId\" = ?").params(MfgDb.org(), bomId));
    }

    /** Detail: header, materials per batch (with names), block spec, other versions, one-batch scaling. */
    @Transactional(readOnly = true)
    public Map<String, Object> mixDesign(String id) {
        Map<String, Object> m = mixRow(id);
        Map<String, Object> bom = boms.get(str(m, "bomId"));
        List<Map<String, Object>> lines = SqlRows.list(db.jdbc().sql("SELECT l.*, p.name AS \"productName\","
                + " p.sku AS \"productSku\" FROM \"BomLine\" l JOIN \"Product\" p ON p.id = l.\"componentProductId\""
                + " WHERE l.\"bomId\" = ? AND l.\"organizationId\" = ? ORDER BY l.sequence").params(m.get("bomId"),
                MfgDb.org()));
        m.put("lines", lines);
        m.put("spec", specFor(str(m, "productId")));
        m.put("versions", SqlRows.list(db.jdbc().sql("SELECT m.id, b.code AS \"bomCode\", b.version, b.status,"
                + " b.\"createdAt\" FROM \"PrecastMixDesign\" m JOIN \"Bom\" b ON b.id = m.\"bomId\""
                + " AND b.\"deletedAt\" IS NULL WHERE m.\"organizationId\" = ? AND m.\"productId\" = ?"
                + " ORDER BY b.version DESC").params(MfgDb.org(), m.get("productId"))));
        m.put("coProducts", stageSetup.coProducts(str(bom, "id")));
        m.put("perBatch", scaleView(m, "batches", BigDecimal.ONE, true));
        return m;
    }

    /** {@code GET /mix-designs/{id}/scale}. */
    @Transactional(readOnly = true)
    public Map<String, Object> scale(String id, String basis, BigDecimal value, Boolean wholeBatches) {
        return scaleView(mixRow(id), basis == null ? "units" : basis, value, wholeBatches == null || wholeBatches);
    }

    Map<String, Object> scaleView(Map<String, Object> mix, String basis, BigDecimal value, boolean whole) {
        BatchScaler.Result r = BatchScaler.scale(recipe(mix), basis, value, whole);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mixDesignId", mix.get("id"));
        out.put("basis", r.basis());
        out.put("requested", r.requested());
        out.put("wholeBatches", whole);
        out.put("batches", r.batches());
        out.put("units", r.units());
        out.put("expectedGood", r.expectedGood());
        out.put("pallets", r.pallets());
        out.put("cycles", r.cycles());
        out.put("volumeM3", r.volumeM3());
        out.put("cementQty", r.cementQty());
        out.put("cementBags", r.cementBags());
        out.put("cementBagsToDraw", r.cementBagsToDraw());
        List<Map<String, Object>> mats = new ArrayList<>();
        for (BatchScaler.MaterialQty q : r.materials()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", q.productId());
            row.put("productName", q.productName());
            row.put("uomId", q.uomId());
            row.put("qtyPerBatch", q.qtyPerBatch());
            row.put("quantity", q.quantity());
            row.put("cement", q.cement());
            row.put("bags", q.bags());
            row.put("onHand", onHand(q.productId(), str(mix, "defaultLocationId")));
            mats.add(row);
        }
        out.put("materials", mats);
        return out;
    }

    /** The scaler input of a mix design row. */
    BatchScaler.Recipe recipe(Map<String, Object> mix) {
        List<BatchScaler.Material> materials = new ArrayList<>();
        for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("SELECT l.*, p.name AS \"productName\" FROM"
                + " \"BomLine\" l JOIN \"Product\" p ON p.id = l.\"componentProductId\" WHERE l.\"bomId\" = ?"
                + " AND l.\"organizationId\" = ? ORDER BY l.sequence").params(mix.get("bomId"), MfgDb.org()))) {
            materials.add(new BatchScaler.Material(str(l, "componentProductId"), str(l, "productName"),
                    str(l, "uomId"), dec(l, "quantity"), l.get("scrapPct") == null ? null : dec(l, "scrapPct")));
        }
        Map<String, Object> spec = specFor(str(mix, "productId"));
        return new BatchScaler.Recipe(dec(mix, "unitsPerBatch"), mix.get("expectedYieldPct") == null ? null
                : dec(mix, "expectedYieldPct"), materials, str(mix, "cementProductId"), dec(mix, "cementPerBag"),
                mix.get("batchVolumeM3") == null ? null : dec(mix, "batchVolumeM3"),
                spec == null ? 1 : ((Number) spec.get("unitsPerPallet")).intValue(),
                spec == null ? 1 : ((Number) spec.get("unitsPerCycle")).intValue());
    }

    private BigDecimal onHand(String productId, String locationId) {
        return db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ?" + (locationId == null ? "" : " AND \"locationId\" = ?"))
                .params(locationId == null ? List.of(MfgDb.org(), productId) : List.of(MfgDb.org(), productId,
                        locationId)).query(BigDecimal.class).single();
    }

    @Transactional
    public Map<String, Object> createMixDesign(CreateMixDesign in) {
        production.getProduct(in.productId());
        validateMix(in.cementProductId(), in.lines(), in.defaultCuringAreaId());
        int minDays = in.minCuringDays() != null ? in.minCuringDays() : 7;
        String checkpoint = in.curingCheckpointCode() != null && !in.curingCheckpointCode().isBlank()
                ? in.curingCheckpointCode().trim() : DEFAULT_CHECKPOINT;
        String routingId = in.routingId();
        if (routingId == null) {
            routingId = str(routings.create(new CreateRouting("Precast · " + in.name(), in.productId(), null, List.of(
                    new RoutingOperationInput(10, "Batching & pressing", in.pressWorkCenterId(), null, null,
                            "Mix the batch, press the blocks onto pallets.", null, null, null),
                    new RoutingOperationInput(20, "Curing", null, null, null,
                            "Cure for at least " + minDays + " day(s); cube and count at release.", null, null,
                            null)))), "id");
        }
        List<Map<String, Object>> ops = stageSetup.operations(routingId);
        if (ops.size() < 2) {
            throw ApiException.badRequest("A precast routing needs a pressing stage and a curing stage.");
        }
        Map<String, Object> pressOp = ops.get(ops.size() - 2);
        Map<String, Object> cureOp = ops.getLast();
        stageSetup.saveRoutingSetup(routingId, new RoutingStageSetup(List.of(
                new StageSetupInput(str(pressOp, "id"), "backflush", null, null, null, in.expectedYieldPct(), false,
                        null, in.lines().stream().map(MixLine::productId).distinct().toList(), 0),
                new StageSetupInput(str(cureOp, "id"), "backflush", null, null, null, null, true, checkpoint,
                        List.of(), minDays))));
        Map<String, Object> bom = boms.create(new CreateBom(in.name(), in.productId(), null, in.unitsPerBatch(), null,
                in.expectedYieldPct(), null, in.defaultLocationId(), in.defaultOutputLocationId(), null, null, null,
                in.isDefault(), null, null, in.notes(), bomLines(in.lines()), routingId));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bomId", bom.get("id"));
        data.put("productId", in.productId());
        data.put("cementProductId", in.cementProductId());
        data.put("cementPerBag", in.cementPerBag() != null ? in.cementPerBag() : BigDecimal.valueOf(50));
        data.put("batchVolumeM3", in.batchVolumeM3());
        data.put("waterCementRatio", in.waterCementRatio());
        data.put("minCuringDays", minDays);
        data.put("curingCheckpointCode", checkpoint);
        data.put("defaultCuringAreaId", in.defaultCuringAreaId());
        data.put("targetMaturity", in.targetMaturity());
        data.put("datumTemperatureC", in.datumTemperatureC() != null ? in.datumTemperatureC() : BigDecimal.valueOf(-10));
        data.put("notes", trim(in.notes()));
        Map<String, Object> mix = db.insert(PrecastMixDesign.class, data);
        ensureSecondGrade(str(bom, "id"));
        audit.recordInTx(AuditInput.of("PrecastMixDesign", str(mix, "id"), "create", null, Obj.of("bomCode",
                bom.get("code"), "productId", in.productId(), "unitsPerBatch", in.unitsPerBatch())));
        return mixDesign(str(mix, "id"));
    }

    @Transactional
    public Map<String, Object> updateMixDesign(String id, UpdateMixDesign in) {
        Map<String, Object> m = mixRow(id);
        if (!"draft".equals(m.get("status"))) {
            throw ApiException.badRequest("Mix design " + m.get("bomCode") + " v" + m.get("version") + " is "
                    + m.get("status") + "; revise it to change the recipe.");
        }
        validateMix(in.cementProductId(), in.lines(), in.defaultCuringAreaId());
        if (in.name() != null || in.unitsPerBatch() != null || in.lines() != null || in.expectedYieldPct() != null
                || in.notes() != null) {
            boms.update(str(m, "bomId"), new UpdateBom(in.name(), in.unitsPerBatch(), null, in.expectedYieldPct(),
                    null, null, null, null, null, null, null, null, in.notes(),
                    in.lines() == null ? null : bomLines(in.lines()), null, null));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        putIf(data, "cementProductId", in.cementProductId());
        putIf(data, "cementPerBag", in.cementPerBag());
        putIf(data, "batchVolumeM3", in.batchVolumeM3());
        putIf(data, "waterCementRatio", in.waterCementRatio());
        putIf(data, "minCuringDays", in.minCuringDays());
        putIf(data, "curingCheckpointCode", in.curingCheckpointCode());
        putIf(data, "defaultCuringAreaId", in.defaultCuringAreaId());
        putIf(data, "targetMaturity", in.targetMaturity());
        putIf(data, "datumTemperatureC", in.datumTemperatureC());
        putIf(data, "notes", in.notes());
        if (!data.isEmpty()) {
            db.update(PrecastMixDesign.class, id, data);
        }
        if (in.expectedYieldPct() != null && m.get("routingId") != null) {
            List<Map<String, Object>> ops = stageSetup.operations(str(m, "routingId"));
            if (ops.size() >= 2) {
                db.jdbc().sql("UPDATE \"RoutingStageSetup\" SET \"expectedYieldPct\" = ?, \"updatedAt\" = " + MfgDb.NOW
                        + " WHERE \"organizationId\" = ? AND \"routingOperationId\" = ?").params(in.expectedYieldPct(),
                        MfgDb.org(), ops.get(ops.size() - 2).get("id")).update();
            }
        }
        audit.recordInTx(AuditInput.of("PrecastMixDesign", id, "update", null, data));
        return mixDesign(id);
    }

    @Transactional
    public Map<String, Object> activateMixDesign(String id) {
        Map<String, Object> m = mixRow(id);
        boms.activate(str(m, "bomId"));
        audit.recordInTx(AuditInput.of("PrecastMixDesign", id, "approve", null, Obj.of("bomCode", m.get("bomCode"))));
        return mixDesign(id);
    }

    /** New draft version: the BOM is revised through the ECO flow and the batch parameters copied. */
    @Transactional
    public Map<String, Object> reviseMixDesign(String id, ReviseMixDesign in) {
        Map<String, Object> m = mixRow(id);
        Map<String, Object> res = engineering.revise(str(m, "bomId"), new ReviseBom(in == null ? null : in.title(),
                in == null ? null : in.reason()));
        @SuppressWarnings("unchecked")
        Map<String, Object> newBom = (Map<String, Object>) res.get("bom");
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : List.of("productId", "cementProductId", "cementPerBag", "batchVolumeM3", "waterCementRatio",
                "minCuringDays", "curingCheckpointCode", "defaultCuringAreaId", "targetMaturity", "datumTemperatureC",
                "notes")) {
            data.put(f, m.get(f));
        }
        data.put("bomId", newBom.get("id"));
        Map<String, Object> copy = db.insert(PrecastMixDesign.class, data);
        audit.recordInTx(AuditInput.of("PrecastMixDesign", str(copy, "id"), "create", null, Obj.of("revisedFrom", id,
                "bomCode", newBom.get("code"))));
        Map<String, Object> out = mixDesign(str(copy, "id"));
        out.put("change", res.get("change"));
        return out;
    }

    /** Adds the block spec's second-grade product as an NRV by-product of the curing stage when the BOM lacks it. */
    void ensureSecondGrade(String bomId) {
        Map<String, Object> mix = mixForBom(bomId);
        if (mix == null) {
            return;
        }
        Map<String, Object> spec = specFor(str(mix, "productId"));
        String second = spec == null ? null : str(spec, "secondGradeProductId");
        if (second == null) {
            return;
        }
        long has = db.jdbc().sql("SELECT count(*) FROM \"BomCoProduct\" WHERE \"organizationId\" = ? AND \"bomId\" = ?"
                + " AND \"productId\" = ?").params(MfgDb.org(), bomId, second).query(Long.class).single();
        if (has > 0) {
            return;
        }
        String cureOp = null;
        if (mix.get("routingId") != null) {
            List<Map<String, Object>> ops = stageSetup.operations(str(mix, "routingId"));
            cureOp = ops.isEmpty() ? null : str(ops.getLast(), "id");
        }
        db.insert(BomCoProduct.class, Obj.of("bomId", bomId, "routingOperationId", cureOp, "productId", second, "role",
                "by_product", "quantity", BigDecimal.ZERO, "valuation", "nrv", "stocked", true, "sequence", 900,
                "notes", "Second grade (precast)"));
    }

    private void validateMix(String cementProductId, List<MixLine> lines, String areaId) {
        if (lines != null) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (MixLine l : lines) {
                production.getProduct(l.productId());
                if (!seen.add(l.productId())) {
                    throw ApiException.badRequest("Product " + l.productId() + " is listed twice.");
                }
            }
            if (cementProductId != null && !seen.contains(cementProductId)) {
                throw ApiException.badRequest("The cement product must be one of the mix lines.");
            }
        }
        if (areaId != null) {
            requireArea(areaId);
        }
    }

    private static List<BomLineInput> bomLines(List<MixLine> lines) {
        List<BomLineInput> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            MixLine l = lines.get(i);
            out.add(new BomLineInput(l.productId(), null, l.qtyPerBatch(), l.uomId(), l.scrapPct(), i, false,
                    l.notes()));
        }
        return out;
    }

    // ================================================================== helpers

    static int page(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    static int size(Integer size) {
        return size == null || size < 1 ? 20 : Math.min(size, 200);
    }

    static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void putIf(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
