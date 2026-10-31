package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.inventory.application.PostingRuleAdminService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.seed.SeedContributor;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.manufacturing.application.CostingCommands.RateInput;
import com.inventoryerp.manufacturing.application.CrushingCommands.PlantInput;
import com.inventoryerp.manufacturing.application.CrushingCommands.ProfileInput;
import com.inventoryerp.manufacturing.application.CrushingCommands.RunInput;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateWorkCenter;
import com.inventoryerp.manufacturing.application.PlanningCommands.TargetInput;
import com.inventoryerp.manufacturing.application.PrecastCommands.CreateMixDesign;
import com.inventoryerp.manufacturing.application.PrecastCommands.CreatePrecastOrder;
import com.inventoryerp.manufacturing.application.PrecastCommands.CubeBatch;
import com.inventoryerp.manufacturing.application.PrecastCommands.CuringAreaInput;
import com.inventoryerp.manufacturing.application.PrecastCommands.GateDecision;
import com.inventoryerp.manufacturing.application.PrecastCommands.PressingRun;
import com.inventoryerp.manufacturing.application.PrecastCommands.ProductSpecInput;
import com.inventoryerp.quarry.application.QuarryCommands.ExtractionLogInput;
import com.inventoryerp.quarry.application.QuarryCommands.MaterialInput;
import com.inventoryerp.quarry.application.QuarryCommands.SiteInput;
import com.inventoryerp.quarry.application.QuarryCommands.StockpileInput;
import com.inventoryerp.quarry.application.QuarryCommands.ZoneInput;
import com.inventoryerp.quarry.application.QuarryExtractionService;
import com.inventoryerp.quarry.application.QuarrySiteService;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opt-in construction manufacturing demo ({@code SEED_MANUFACTURING_DEMO=true} with the {@code seed} profile, MFG-4):
 * one quarry with a bench and stockpiles, one crusher line with a grading profile, one block plant with a mix design and
 * curing yard, and a month of work to Saturday: daily extraction into ROM, a daily crushing run with co-products, a block
 * order every third working day. Blocks older than their curing time are cubed and passed, newer ones are still curing.
 * Absorption rates and the month's targets are set. Everything runs through the module services, so stock and GL are
 * posted by their engines. Off by default, never part of the POS demo; skipped when the demo plant exists. Codes
 * {@code DM-*}.
 */
@Component
public class ManufacturingDemoSeedContributor implements SeedContributor {

    private static final Logger log = LoggerFactory.getLogger(ManufacturingDemoSeedContributor.class);
    private static final int DAYS = 30;
    private static final int CURING_DAYS = 7;

    private final MfgDb db;
    private final JsonMapper mapper;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final QuarrySiteService quarry;
    private final QuarryExtractionService extraction;
    private final CrushingPlantService plants;
    private final CrushingProfileService profiles;
    private final CrushingRunService runs;
    private final WorkCenterService centres;
    private final PrecastSetupService precastSetup;
    private final PrecastProductionService precast;
    private final CostAbsorptionService absorption;
    private final PlanningShiftService targets;
    private final PostingRuleAdminService postingRules;
    private final StockService stock;

    public ManufacturingDemoSeedContributor(MfgDb db, JsonMapper mapper, PlatformTransactionManager txm,
            @Value("${SEED_MANUFACTURING_DEMO:false}") boolean enabled, QuarrySiteService quarry,
            QuarryExtractionService extraction, CrushingPlantService plants, CrushingProfileService profiles,
            CrushingRunService runs, WorkCenterService centres, PrecastSetupService precastSetup,
            PrecastProductionService precast, CostAbsorptionService absorption, PlanningShiftService targets,
            PostingRuleAdminService postingRules, StockService stock) {
        this.db = db;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txm);
        this.enabled = enabled;
        this.quarry = quarry;
        this.extraction = extraction;
        this.plants = plants;
        this.profiles = profiles;
        this.runs = runs;
        this.centres = centres;
        this.precastSetup = precastSetup;
        this.precast = precast;
        this.absorption = absorption;
        this.targets = targets;
        this.postingRules = postingRules;
        this.stock = stock;
    }

    @Override
    public int order() {
        return 950;
    }

    @Override
    public void seed(String organizationId) {
        if (enabled) {
            seedDemo(organizationId);
        }
    }

    private <T> T in(Class<T> type, Object... kv) {
        return mapper.convertValue(Obj.of(kv), type);
    }

    private String id(String table, String column, String value, String org) {
        return db.jdbc().sql("SELECT id FROM \"" + table + "\" WHERE \"organizationId\" = ? AND \"" + column + "\" = ?"
                + " LIMIT 1").params(org, value).query(String.class).optional().orElse(null);
    }

    /** Seed into {@code organizationId} (needs its accounts and MAIN warehouse); {@code skipped} when already there. */
    public Map<String, Object> seedDemo(String organizationId) {
        String admin = db.jdbc().sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\""
                + " LIMIT 1").param(organizationId).query(String.class).optional().orElse(null);
        TenantStore store = TenantStore.user(organizationId, admin);
        return TenantContext.run(store, () -> {
            TenantContext.setPermissions(List.copyOf(Permissions.ALL));
            return tx.execute(s -> build(organizationId));
        });
    }

    private String product(String org, String uom, String code, String name, int sales, int cost) {
        db.jdbc().sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"salesPrice\", \"costPrice\", \"uomId\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, 'stockable'::\"ProductType\", true, 'AVCO'::\"CostingMethod\", ?, ?, ?, now())"
                + " ON CONFLICT (\"organizationId\", code) DO NOTHING").params(UUID.randomUUID().toString(), org, code,
                        name, sales, cost, uom).update();
        return id("Product", "code", code, org);
    }

    private Map<String, Object> build(String org) {
        if (id("CrushingPlant", "code", "DM-CR1", org) != null) {
            return Obj.of("skipped", true);
        }
        String yard = id("InventoryLocation", "code", "MAIN", org);
        if (yard == null) {
            log.warn("manufacturing demo skipped: the organization has no MAIN warehouse");
            return Obj.of("skipped", true);
        }
        postingRules.restoreDefaults();
        String uom = id("UnitOfMeasure", "code", "UNIT", org);
        String rom = product(org, uom, "DM-ROM", "Run-of-mine granite (t)", 0, 0);
        String a20 = product(org, uom, "DM-AGG-20", "Aggregate 20mm (t)", 45000, 0);
        String a10 = product(org, uom, "DM-AGG-10", "Aggregate 10mm (t)", 42000, 0);
        String dust = product(org, uom, "DM-DUST", "Quarry dust (t)", 20000, 0);
        String cement = product(org, uom, "DM-CEMENT", "Cement (kg)", 0, 700);
        String sand = product(org, uom, "DM-SAND", "Sharp sand (kg)", 0, 25);
        String stone = product(org, uom, "DM-STONE", "Block aggregate (kg)", 0, 30);
        String block = product(org, uom, "DM-BLOCK-6", "Concrete block 6 inch", 2500, 0);
        String second = product(org, uom, "DM-BLOCK-6-2", "Concrete block 6 inch, second grade", 1200, 0);

        // Quarry.
        String siteId = str(quarry.createSite(in(SiteInput.class, "code", "DM-Q1", "name", "Demo quarry",
                "licenceNumber", "ML-DEMO-1")), "id");
        String materialId = str(quarry.createMaterial(in(MaterialInput.class, "code", "DM-GR", "name", "Granite",
                "rockType", "granite", "productId", rom)), "id");
        String zone = str(quarry.createZone(in(ZoneInput.class, "siteId", siteId, "kind", "zone", "code", "Z1", "name",
                "North zone")), "id");
        String bench = str(quarry.createZone(in(ZoneInput.class, "siteId", siteId, "parentId", zone, "kind", "bench",
                "code", "B1", "name", "Bench 1")), "id");
        String romPile = stockpile(siteId, "DM-ROM", "ROM pad", "rom");
        String pile20 = stockpile(siteId, "DM-A20", "20mm stockpile", "product");
        String pile10 = stockpile(siteId, "DM-A10", "10mm stockpile", "product");
        String pileDust = stockpile(siteId, "DM-DU", "Dust stockpile", "product");

        // Crusher line.
        String plantId = str(plants.create(in(PlantInput.class, "code", "DM-CR1", "name", "Demo crusher line", "siteId",
                siteId, "feedStockpileId", romPile, "designCapacityTph", 120, "dieselCostPerLitre", 5000,
                "electricityCostPerKwh", 900, "labourRatePerHour", 8000, "stages", List.of(
                        Obj.of("name", "Primary crush", "kind", "primary", "units", List.of(Obj.of("code", "DM-JAW",
                                "name", "Jaw crusher", "kind", "jaw_crusher", "capacityTph", 150, "meterKind", "both"))),
                        Obj.of("name", "Screening", "kind", "screening", "units", List.of(Obj.of("code", "DM-SCR",
                                "name", "Triple-deck screen", "kind", "screen", "capacityTph", 120, "meterKind",
                                "kwh")))))), "id");
        String draft = str(profiles.create(in(ProfileInput.class, "plantId", plantId, "code", "DM-SPLIT", "name",
                "Granite split", "feedProductId", rom, "lines", List.of(
                        Obj.of("productId", a20, "sizeLabel", "20 mm", "role", "main", "expectedPct", 50, "stocked", true,
                                "stockpileId", pile20),
                        Obj.of("productId", a10, "sizeLabel", "10 mm", "role", "co_product", "expectedPct", 30, "stocked",
                                true, "stockpileId", pile10),
                        Obj.of("productId", dust, "sizeLabel", "0-6 mm", "role", "co_product", "expectedPct", 15,
                                "stocked", true, "stockpileId", pileDust)))), "id");
        String profileId = str(profiles.activate(draft), "id");
        Map<String, String> grading = new HashMap<>();
        for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("SELECT id, \"productId\" FROM \"CrushingGradingLine\""
                + " WHERE \"organizationId\" = ? AND \"profileId\" = ?").params(org, profileId))) {
            grading.put(str(l, "productId"), str(l, "id"));
        }

        // Block plant.
        String press = str(centres.create(in(CreateWorkCenter.class, "code", "DM-PRESS", "name", "Block press")), "id");
        absorption.saveRate(press, in(RateInput.class, "labourRatePerHour", 8000, "machineRatePerHour", 15000,
                "overheadRatePerHour", 5000));
        String area = str(precastSetup.createArea(in(CuringAreaInput.class, "code", "DM-YARD", "name", "Curing yard",
                "kind", "yard", "capacityPallets", 500)), "id");
        precastSetup.saveSpec(block, in(ProductSpecInput.class, "blockType", "hollow", "lengthMm", 450, "widthMm", 150,
                "heightMm", 225, "strengthClass", "7 N/mm²", "strengthMpa", 7, "unitsPerPallet", 100, "unitsPerCycle", 10,
                "secondGradeProductId", second));
        stock.receive(StockMove.of(cement, yard, new BigDecimal("6000")).unitCost(new BigDecimal("700")));
        stock.receive(StockMove.of(sand, yard, new BigDecimal("24000")).unitCost(new BigDecimal("25")));
        stock.receive(StockMove.of(stone, yard, new BigDecimal("36000")).unitCost(new BigDecimal("30")));
        String mixDraft = str(precastSetup.createMixDesign(in(CreateMixDesign.class, "name", "6 inch hollow block",
                "productId", block, "unitsPerBatch", 100, "lines", List.of(Obj.of("productId", cement, "qtyPerBatch", 50),
                        Obj.of("productId", sand, "qtyPerBatch", 200), Obj.of("productId", stone, "qtyPerBatch", 300)),
                "cementProductId", cement, "cementPerBag", 50, "minCuringDays", CURING_DAYS, "curingCheckpointCode",
                "DM-CURE", "defaultCuringAreaId", area, "pressWorkCenterId", press, "defaultLocationId", yard,
                "defaultOutputLocationId", yard)), "id");
        precastSetup.activateMixDesign(mixDraft);

        // A month of work.
        LocalDate today = LocalDate.now(BusinessZone.get());
        int extractionLogs = 0;
        int crushingRuns = 0;
        int blockOrders = 0;
        int released = 0;
        for (int back = DAYS; back >= 1; back--) {
            LocalDate day = today.minusDays(back);
            if (day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            String logId = str(extraction.create(in(ExtractionLogInput.class, "siteId", siteId, "zoneId", bench,
                    "materialId", materialId, "stockpileId", romPile, "extractedOn", day.toString(), "shift", "day",
                    "otherCost", 3600000, "otherCostNote", "Drilling and loading contractor", "loads", List.of(Obj.of(
                            "method", "survey", "tonnes", 420)))), "id");
            extraction.post(logId);
            extractionLogs++;
            int wobble = back % 3;
            String runId = str(runs.create(in(RunInput.class, "plantId", plantId, "profileId", profileId, "runDate",
                    day.toString(), "shift", back % 2 == 0 ? "day" : "night", "feedStockpileId", romPile, "feedTonnes", 400,
                    "feedMethod", "belt_scale", "outputMethod", "belt_scale", "operatingHours", 7, "downtimeHours", 1,
                    "labourHours", 16, "outputs", List.of(
                            Obj.of("gradingLineId", grading.get(a20), "actualTonnes", 198 + wobble),
                            Obj.of("gradingLineId", grading.get(a10), "actualTonnes", 120),
                            Obj.of("gradingLineId", grading.get(dust), "actualTonnes", 60 - wobble)))), "id");
            runs.post(runId);
            crushingRuns++;
            if (extractionLogs % 3 != 0) {
                continue;
            }
            String orderId = str(precast.createOrder(in(CreatePrecastOrder.class, "mixDesignId", mixDraft, "basis", "units",
                    "value", 1000)), "id");
            blockOrders++;
            Map<String, Object> pressed = precast.press(orderId, in(PressingRun.class, "qtyGood", 980, "qtyBroken", 20,
                    "cycles", 100, "shift", "day", "lastRun", true, "date", day.toString()));
            if (back > CURING_DAYS + 1) {
                @SuppressWarnings("unchecked")
                String batchId = str((Map<String, Object>) pressed.get("batch"), "id");
                precast.cube(batchId, in(CubeBatch.class, "qtyGood", 960, "qtySecondGrade", 15, "qtyBroken", 5, "date",
                        day.plusDays(CURING_DAYS).toString()));
                precast.gateDecision(batchId, in(GateDecision.class, "result", "passed", "reference", "Cube test 7 day"));
                released++;
            }
        }
        LocalDate first = today.withDayOfMonth(1);
        LocalDate last = today.withDayOfMonth(today.lengthOfMonth());
        targets.createTarget(in(TargetInput.class, "scope", "crushing_plant", "plantId", plantId, "periodFrom",
                first.toString(), "periodTo", last.toString(), "targetQty", 9500));
        targets.createTarget(in(TargetInput.class, "scope", "product", "productId", block, "periodFrom", first.toString(),
                "periodTo", last.toString(), "targetQty", 8000));
        return Obj.of("extractionLogs", extractionLogs, "crushingRuns", crushingRuns, "blockOrders", blockOrders,
                "released", released);
    }

    private String stockpile(String siteId, String code, String name, String kind) {
        return str(quarry.createStockpile(in(StockpileInput.class, "siteId", siteId, "code", code, "name", name, "kind",
                kind)), "id");
    }
}
