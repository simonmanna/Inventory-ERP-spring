package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.inventory.application.PostingRuleAdminService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.seed.ConstructionDemoPart;
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
 *
 * <p>The same build, longer and in real units, is the manufacturing part of the {@code SEED_CONSTRUCTION_DEMO}
 * organization ({@link #CONSTRUCTION}): three months, tonnes and kilograms instead of pieces, double the block lots, the
 * quarry on branch {@code QRY}, and the account mappings the plant posts to confirmed first.
 */
@Component
public class ManufacturingDemoSeedContributor implements SeedContributor, ConstructionDemoPart {

    private static final Logger log = LoggerFactory.getLogger(ManufacturingDemoSeedContributor.class);
    private static final int DAYS = 30;
    private static final int CURING_DAYS = 7;

    /**
     * How much the demo builds: days of work, block lots per order (one lot = 1000 blocks), the stock units of bulk and
     * of batched materials, and the quarry's branch (null = none).
     */
    public record Scale(int days, int blockLots, String bulkUom, String batchUom, String branchCode) {}

    private static final Scale MONTH = new Scale(DAYS, 1, "UNIT", "UNIT", null);
    public static final Scale CONSTRUCTION = new Scale(90, 2, "TON", "KG", "QRY");

    /** Mapping key, then the template's account code, name and category should the mapping be missing. */
    private static final String[][] PLANT_MAPPINGS = {
        {"stock_valuation", "1400", "Inventory / Stock Valuation", "inventory"},
        {"wip", "1420", "Work In Progress", "work_in_progress"},
        {"overhead_absorbed", "5350", "Manufacturing Overhead Absorbed", "operating_expense"},
        {"cogs", "5100", "Cost of Goods Sold", "cost_of_goods_sold"},
        {"grni_accrued", "2150", "Goods Received Not Invoiced (GRNI)", "current_liability"},
        {"stock_adjustment_expense", "5300", "Stock Adjustment Expense", "operating_expense"},
        {"stock_adjustment_income", "4200", "Stock Adjustment Income", "other_income"},
    };

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
    private final AccountResolverService accounts;

    public ManufacturingDemoSeedContributor(MfgDb db, JsonMapper mapper, PlatformTransactionManager txm,
            @Value("${SEED_MANUFACTURING_DEMO:false}") boolean enabled, QuarrySiteService quarry,
            QuarryExtractionService extraction, CrushingPlantService plants, CrushingProfileService profiles,
            CrushingRunService runs, WorkCenterService centres, PrecastSetupService precastSetup,
            PrecastProductionService precast, CostAbsorptionService absorption, PlanningShiftService targets,
            PostingRuleAdminService postingRules, StockService stock, AccountResolverService accounts) {
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
        this.accounts = accounts;
    }

    @Override
    public int order() {
        return 950;
    }

    @Override
    public int constructionOrder() {
        return 300;
    }

    @Override
    public void seedConstructionDemo(String organizationId) {
        Map<String, Object> out = seedDemo(organizationId, CONSTRUCTION);
        log.info("construction demo, manufacturing part in org {}: {}", organizationId, out);
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

    /** Seed a month into {@code organizationId} (needs its accounts and MAIN warehouse); {@code skipped} when there. */
    public Map<String, Object> seedDemo(String organizationId) {
        return seedDemo(organizationId, MONTH);
    }

    /** Seed {@code scale} into {@code organizationId}; {@code skipped} when the demo plant exists. */
    public Map<String, Object> seedDemo(String organizationId, Scale scale) {
        String admin = db.jdbc().sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\""
                + " LIMIT 1").param(organizationId).query(String.class).optional().orElse(null);
        TenantStore store = TenantStore.user(organizationId, admin);
        return TenantContext.run(store, () -> {
            TenantContext.setPermissions(List.copyOf(Permissions.ALL));
            return tx.execute(s -> build(organizationId, scale));
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

    /** The accounts the plant posts to; the chart template maps them all, this holds for a chart edited by hand too. */
    private void ensurePlantMappings() {
        for (String[] m : PLANT_MAPPINGS) {
            accounts.ensureByCode(m[1], m[2], m[3], m[0]);
        }
    }

    private Map<String, Object> build(String org, Scale scale) {
        if (id("CrushingPlant", "code", "DM-CR1", org) != null) {
            return Obj.of("skipped", true);
        }
        String yard = id("InventoryLocation", "code", "MAIN", org);
        if (yard == null) {
            log.warn("manufacturing demo skipped: the organization has no MAIN warehouse");
            return Obj.of("skipped", true);
        }
        ensurePlantMappings();
        postingRules.restoreDefaults();
        String uom = id("UnitOfMeasure", "code", "UNIT", org);
        String bulk = id("UnitOfMeasure", "code", scale.bulkUom(), org);
        String batch = id("UnitOfMeasure", "code", scale.batchUom(), org);
        int lots = scale.blockLots();
        BigDecimal rawScale = BigDecimal.valueOf((long) scale.days() / DAYS * lots);
        String rom = product(org, bulk, "DM-ROM", "Run-of-mine granite (t)", 0, 0);
        String a20 = product(org, bulk, "DM-AGG-20", "Aggregate 20mm (t)", 45000, 0);
        String a10 = product(org, bulk, "DM-AGG-10", "Aggregate 10mm (t)", 42000, 0);
        String dust = product(org, bulk, "DM-DUST", "Quarry dust (t)", 20000, 0);
        String cement = product(org, batch, "DM-CEMENT", "Cement (kg)", 0, 700);
        String sand = product(org, batch, "DM-SAND", "Sharp sand (kg)", 0, 25);
        String stone = product(org, batch, "DM-STONE", "Block aggregate (kg)", 0, 30);
        String block = product(org, uom, "DM-BLOCK-6", "Concrete block 6 inch", 2500, 0);
        String second = product(org, uom, "DM-BLOCK-6-2", "Concrete block 6 inch, second grade", 1200, 0);

        // Quarry.
        String siteId = str(quarry.createSite(in(SiteInput.class, "code", "DM-Q1", "name", "Demo quarry",
                "licenceNumber", "ML-DEMO-1", "branchId", scale.branchCode() == null ? null
                        : id("Branch", "code", scale.branchCode(), org))), "id");
        String materialId = str(quarry.createMaterial(in(MaterialInput.class, "code", "DM-GR", "name", "Granite",
                "rockType", "granite", "densityTPerM3", "2.65", "productId", rom)), "id");
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
        stock.receive(StockMove.of(cement, yard, new BigDecimal("6000").multiply(rawScale))
                .unitCost(new BigDecimal("700")));
        stock.receive(StockMove.of(sand, yard, new BigDecimal("24000").multiply(rawScale))
                .unitCost(new BigDecimal("25")));
        stock.receive(StockMove.of(stone, yard, new BigDecimal("36000").multiply(rawScale))
                .unitCost(new BigDecimal("30")));
        String mixDraft = str(precastSetup.createMixDesign(in(CreateMixDesign.class, "name", "6 inch hollow block",
                "productId", block, "unitsPerBatch", 100, "lines", List.of(Obj.of("productId", cement, "qtyPerBatch", 50),
                        Obj.of("productId", sand, "qtyPerBatch", 200), Obj.of("productId", stone, "qtyPerBatch", 300)),
                "cementProductId", cement, "cementPerBag", 50, "minCuringDays", CURING_DAYS, "curingCheckpointCode",
                "DM-CURE", "defaultCuringAreaId", area, "pressWorkCenterId", press, "defaultLocationId", yard,
                "defaultOutputLocationId", yard)), "id");
        precastSetup.activateMixDesign(mixDraft);

        // The work, day by day.
        LocalDate today = LocalDate.now(BusinessZone.get());
        int extractionLogs = 0;
        int crushingRuns = 0;
        int blockOrders = 0;
        int released = 0;
        for (int back = scale.days(); back >= 1; back--) {
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
                    "value", 1000 * lots)), "id");
            blockOrders++;
            Map<String, Object> pressed = precast.press(orderId, in(PressingRun.class, "qtyGood", 980 * lots,
                    "qtyBroken", 20 * lots, "cycles", 100 * lots, "shift", "day", "lastRun", true, "date",
                    day.toString()));
            if (back > CURING_DAYS + 1) {
                @SuppressWarnings("unchecked")
                String batchId = str((Map<String, Object>) pressed.get("batch"), "id");
                precast.cube(batchId, in(CubeBatch.class, "qtyGood", 960 * lots, "qtySecondGrade", 15 * lots, "qtyBroken",
                        5 * lots, "date", day.plusDays(CURING_DAYS).toString()));
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
