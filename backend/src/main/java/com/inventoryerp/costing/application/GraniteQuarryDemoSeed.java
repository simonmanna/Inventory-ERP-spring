package com.inventoryerp.costing.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.CurrencyService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.fleet.application.FleetDispatchService;
import com.inventoryerp.inventory.application.PostingRuleAdminService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.InvoiceService;
import com.inventoryerp.invoicing.application.PaymentService;
import com.inventoryerp.invoicing.application.PaymentService.AllocationInput;
import com.inventoryerp.invoicing.application.PaymentService.PaymentInput;
import com.inventoryerp.invoicing.application.VendorBillService;
import com.inventoryerp.invoicing.application.VendorBillService.BillInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.seed.DemoOrganization;
import com.inventoryerp.manufacturing.application.BomService;
import com.inventoryerp.manufacturing.application.CrushingCommands.PlantInput;
import com.inventoryerp.manufacturing.application.CrushingCommands.ProfileInput;
import com.inventoryerp.manufacturing.application.CrushingCommands.RunInput;
import com.inventoryerp.manufacturing.application.CrushingPlantService;
import com.inventoryerp.manufacturing.application.CrushingProfileService;
import com.inventoryerp.manufacturing.application.CrushingRunService;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateBom;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateOrder;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateRouting;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateWorkCenter;
import com.inventoryerp.manufacturing.application.ProductionService;
import com.inventoryerp.manufacturing.application.RoutingService;
import com.inventoryerp.manufacturing.application.StageCommands.InitializeStages;
import com.inventoryerp.manufacturing.application.StageCommands.StageConversion;
import com.inventoryerp.manufacturing.application.StageCommands.StageOutput;
import com.inventoryerp.manufacturing.application.StageExecutionService;
import com.inventoryerp.manufacturing.application.WorkCenterService;
import com.inventoryerp.procurement.application.LandedCostVoucherService;
import com.inventoryerp.procurement.application.LandedCostVoucherService.ChargeInput;
import com.inventoryerp.procurement.application.LandedCostVoucherService.VoucherInput;
import com.inventoryerp.procurement.application.PurchaseOrdersService;
import com.inventoryerp.procurement.application.PurchaseOrdersService.CreateInput;
import com.inventoryerp.procurement.application.PurchaseOrdersService.ReceiveInput;
import com.inventoryerp.procurement.application.PurchaseOrdersService.ReceiveLineInput;
import com.inventoryerp.quarry.application.QuarryCommands.ExtractionLogInput;
import com.inventoryerp.quarry.application.QuarryCommands.MaterialInput;
import com.inventoryerp.quarry.application.QuarryCommands.SiteInput;
import com.inventoryerp.quarry.application.QuarryCommands.StockpileInput;
import com.inventoryerp.quarry.application.QuarryCommands.ZoneInput;
import com.inventoryerp.quarry.application.QuarryExtractionService;
import com.inventoryerp.quarry.application.QuarrySiteService;
import com.inventoryerp.sales.application.SalesBillingService;
import com.inventoryerp.sales.application.SalesDeliveryService;
import com.inventoryerp.sales.application.SalesFulfilmentService;
import com.inventoryerp.sales.application.SalesOrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Granite Quarry – Production &amp; Profitability" demo ({@code SEED_COSTING_DEMO=true} with the {@code seed} profile):
 * organization {@value #CODE}, one month of a quarry and construction-materials business whose every figure can be
 * recomputed by hand. It runs entirely through the module engines, so stock valuation, the GL and the profitability
 * reports agree. All documents fall in the previous calendar month (day 1 to 28); the worksheet with the expected
 * figures is {@code docs/guides/granite-quarry-costing-demo.md}.
 *
 * <ol>
 *   <li>Extraction: 1,000 t granite at UGX 19,000,000 (drilling 12M, loading equipment 4M, internal haulage 3M) =
 *       19,000/t into ROM.</li>
 *   <li>Crushing: 1,000 t feed + 8,000,000 processing (diesel 800 L × 5,000, power 1,000 kWh × 1,000, crew 100 h ×
 *       10,000, maintenance 1M, depreciation 1M) → 20mm 600 t, 10mm 250 t, dust 100 t, 50 t normal loss; 27,000,000 ÷
 *       950 t = 28,421.05/t.</li>
 *   <li>Blocks: 1,000 blocks from 30 bags cement, 5 t sand, 4 t own 20mm, 100 L admixture + 430,000 conversion =
 *       1,818,684.21 (1,818.68 each).</li>
 *   <li>Landed cost: 500 t imported basalt at $16 + $3,000 charges at 3,700 = 81,400/t.</li>
 *   <li>Sales: four orders, delivered and invoiced, one with a 500,000 delivery cost; receipts and supplier
 *       payments.</li>
 * </ol>
 *
 * The actual production costs are expensed from bills and journals; extraction and the plants absorb the same amounts
 * into stock (5361 / 5350), so they net to zero in the P&amp;L and reach it as cost of sales when the goods are sold.
 */
@Component
public class GraniteQuarryDemoSeed implements DemoOrganization {

    private static final Logger log = LoggerFactory.getLogger(GraniteQuarryDemoSeed.class);

    /** Sign-in organization code. */
    public static final String CODE = "GQDEMO";

    static final BigDecimal USD_RATE = new BigDecimal("3700");

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final AccountResolverService accounts;
    private final PostingService posting;
    private final CurrencyService currencies;
    private final PostingRuleAdminService postingRules;
    private final QuarrySiteService quarry;
    private final QuarryExtractionService extraction;
    private final CrushingPlantService plants;
    private final CrushingProfileService profiles;
    private final CrushingRunService runs;
    private final WorkCenterService centres;
    private final RoutingService routings;
    private final BomService boms;
    private final ProductionService production;
    private final StageExecutionService stages;
    private final PurchaseOrdersService purchaseOrders;
    private final VendorBillService bills;
    private final LandedCostVoucherService landedCost;
    private final SalesOrderService salesOrders;
    private final SalesDeliveryService deliveries;
    private final FleetDispatchService dispatch;
    private final SalesFulfilmentService fulfilment;
    private final SalesBillingService billing;
    private final InvoiceService invoices;
    private final PaymentService payments;
    private final ProfitabilityService profitability;

    public GraniteQuarryDemoSeed(JdbcClient jdbc, JsonMapper mapper, PlatformTransactionManager txm,
            @Value("${SEED_COSTING_DEMO:false}") boolean enabled, AccountResolverService accounts,
            PostingService posting, CurrencyService currencies, PostingRuleAdminService postingRules,
            QuarrySiteService quarry, QuarryExtractionService extraction, CrushingPlantService plants,
            CrushingProfileService profiles, CrushingRunService runs, WorkCenterService centres,
            RoutingService routings, BomService boms, ProductionService production, StageExecutionService stages,
            PurchaseOrdersService purchaseOrders, VendorBillService bills, LandedCostVoucherService landedCost,
            SalesOrderService salesOrders, SalesDeliveryService deliveries, FleetDispatchService dispatch,
            SalesFulfilmentService fulfilment, SalesBillingService billing, InvoiceService invoices,
            PaymentService payments, ProfitabilityService profitability) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txm);
        this.enabled = enabled;
        this.accounts = accounts;
        this.posting = posting;
        this.currencies = currencies;
        this.postingRules = postingRules;
        this.quarry = quarry;
        this.extraction = extraction;
        this.plants = plants;
        this.profiles = profiles;
        this.runs = runs;
        this.centres = centres;
        this.routings = routings;
        this.boms = boms;
        this.production = production;
        this.stages = stages;
        this.purchaseOrders = purchaseOrders;
        this.bills = bills;
        this.landedCost = landedCost;
        this.salesOrders = salesOrders;
        this.deliveries = deliveries;
        this.dispatch = dispatch;
        this.fulfilment = fulfilment;
        this.billing = billing;
        this.invoices = invoices;
        this.payments = payments;
        this.profitability = profitability;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String name() {
        return "Granite Quarry Costing Demo";
    }

    @Override
    public String emailDomain() {
        return "granite.test";
    }

    /** First day of the demo month: the previous calendar month, so every document is in the past. */
    static LocalDate monthStart(LocalDate today) {
        return today.withDayOfMonth(1).minusMonths(1);
    }

    @Override
    public void seed(String organizationId) {
        LocalDate start = monthStart(LocalDate.now(BusinessZone.get()));
        Map<String, Object> out = tx.execute(s -> build(organizationId, start));
        log.info("granite quarry costing demo in org {}: {}", organizationId, out);
    }

    // ================================================================== the month

    /** Ids the steps share. */
    private final class Ctx {
        final String org;
        final LocalDate start;
        final Map<String, String> p = new LinkedHashMap<>();
        final Map<String, String> partner = new LinkedHashMap<>();
        final Map<String, String> acc = new LinkedHashMap<>();
        String yard;
        String qry;
        String kla;

        Ctx(String org, LocalDate start) {
            this.org = org;
            this.start = start;
        }

        LocalDate day(int n) {
            return start.plusDays(n - 1L);
        }

        String date(int n) {
            return day(n).toString();
        }

        Instant at(int n, int hour) {
            return day(n).atTime(hour, 0).atZone(BusinessZone.get()).toInstant();
        }
    }

    private Map<String, Object> build(String org, LocalDate start) {
        if (id("Product", "code", "GQ-AGG-20", org) != null) {
            return Obj.of("skipped", true);
        }
        Ctx c = new Ctx(org, start);
        setup(c);
        opening(c);
        purchases(c);
        extract(c);
        String run = crush(c);
        blocks(c);
        importBasalt(c);
        sales(c);
        monthEnd(c);
        settle(c);
        Map<String, Object> runRow = runs.get(run);
        profitability.build(c.date(1), c.day(1).withDayOfMonth(c.day(1).lengthOfMonth()).toString());
        return Obj.of("month", start.toString(), "crushingRun", runRow.get("code"), "costPerTonne",
                runRow.get("costPerTonne"));
    }

    // ------------------------------------------------------------------ master data

    private void setup(Ctx c) {
        postingRules.restoreDefaults();
        c.qry = id("Branch", "code", "QRY", c.org);
        c.kla = id("Branch", "code", "KLA", c.org);
        c.yard = id("InventoryLocation", "code", "MAIN", c.org);
        jdbc.sql("UPDATE \"InventoryLocation\" SET name = 'Quarry stockyard', \"branchId\" = ? WHERE id = ?")
                .params(c.qry, c.yard).update();
        currencies.upsertRate("USD", "UGX", c.at(1, 0).minusSeconds(86400), USD_RATE, "manual");

        String ton = id("UnitOfMeasure", "code", "TON", c.org);
        String bag = id("UnitOfMeasure", "code", "BAG", c.org);
        String litre = id("UnitOfMeasure", "code", "L", c.org);
        String unit = id("UnitOfMeasure", "code", "UNIT", c.org);
        product(c, "GQ-ROM", "Raw granite (run-of-mine)", ton, "0", "19000");
        product(c, "GQ-AGG-20", "Aggregate 20mm", ton, "45000", "28421.05");
        product(c, "GQ-AGG-10", "Aggregate 10mm", ton, "40000", "28421.05");
        product(c, "GQ-DUST", "Stone dust", ton, "20000", "28421.05");
        product(c, "GQ-CEMENT", "Cement 50 kg bag", bag, "0", "35000");
        product(c, "GQ-SAND", "Sharp sand", ton, "0", "35000");
        product(c, "GQ-ADMIX", "Water & block admixture", litre, "0", "500");
        product(c, "GQ-BLOCK-6", "Concrete block 6 inch", unit, "2500", "1818.68");
        product(c, "GQ-BASALT", "Basalt chippings 14mm (imported)", ton, "95000", "81400");

        partner(c, "GQ-C01", "Kampala Builders Ltd", true);
        partner(c, "GQ-C02", "Nile Towers Construction", true);
        partner(c, "GQ-S01", "Lake Cement Ltd", false);
        partner(c, "GQ-S02", "Nile Sand Dealers", false);
        partner(c, "GQ-S03", "Mombasa Basalt Exporters", false);
        partner(c, "GQ-S04", "Swift Clearing & Forwarding", false);
        partner(c, "GQ-S05", "Northern Corridor Freight", false);
        partner(c, "GQ-S06", "Kasese Loading Services", false);
        partner(c, "GQ-S07", "Rocktech Drilling Contractors", false);
        partner(c, "GQ-S08", "Equator Plant Hire", false);
        partner(c, "GQ-S09", "Lakeside Haulage", false);
        partner(c, "GQ-S10", "Equator Fuels Ltd", false);
        partner(c, "GQ-S11", "Kampala Power Distribution", false);
        partner(c, "GQ-S12", "Crusher Spares & Services", false);

        c.acc.put("drilling", accounts.ensureByCode("6310", "Drilling & Extraction Contractors", "operating_expense",
                null));
        c.acc.put("hire", accounts.ensureByCode("6320", "Plant & Equipment Hire", "operating_expense", null));
        c.acc.put("haulage", accounts.ensureByCode("6330", "Internal Haulage", "operating_expense", null));
        c.acc.put("power", accounts.ensureByCode("6340", "Electricity", "operating_expense", null));
        c.acc.put("fuel", accounts.ensureByCode("6410", "Fuel & Diesel", "operating_expense", "fleet_fuel_expense"));
        c.acc.put("maintenance", accounts.ensureByCode("6420", "Repairs & Maintenance", "operating_expense",
                "fleet_maintenance_expense"));
        c.acc.put("freight", accounts.ensureByCode("6460", "Outbound Freight & Transport", "operating_expense",
                "fleet_freight_expense"));
        c.acc.put("capital", accounts.ensureByCode("3010", "Owner's Capital", "equity", null));
        c.acc.put("wages", accounts.byMapping("salary_expense"));
        c.acc.put("wagesPayable", accounts.byMapping("net_pay_payable"));
        c.acc.put("depreciation", accounts.byMapping("depreciation_expense"));
        c.acc.put("accumulated", accounts.byMapping("accumulated_depreciation"));
        c.acc.put("assets", accounts.byMapping("fixed_asset_valuation"));
        c.acc.put("bank", accounts.byMapping("default_bank"));
    }

    private void opening(Ctx c) {
        journal(c, 1, "BANK", "Opening balances: owner's capital", "gqdemo:opening", List.of(
                Line.debit(c.acc.get("bank"), "150000000", "Capital paid into the bank"),
                Line.debit(c.acc.get("assets"), "300000000", "Crusher plant and block machine at cost"),
                Line.credit(c.acc.get("capital"), "450000000", "Owner's capital")));
    }

    // ------------------------------------------------------------------ purchases (materials for the block plant)

    private void purchases(Ctx c) {
        String cementPo = po(c, "GQ-S01", 1, "Cement and admixture for the block plant", null,
                line(c, "GQ-CEMENT", "Cement 50 kg bag", "60", "35000"),
                line(c, "GQ-ADMIX", "Water & block admixture", "200", "500"));
        String sandPo = po(c, "GQ-S02", 1, "Sharp sand for the block plant", null,
                line(c, "GQ-SAND", "Sharp sand", "10", "35000"));
        receive(c, cementPo, 4);
        receive(c, sandPo, 4);
        c.p.put("bill:cement", poBill(c, "GQ-S01", cementPo, 5, null, "LC-INV-2207",
                LineInput.of(c.p.get("GQ-CEMENT"), null, "Cement 50 kg bag", dec("60"), dec("35000"), null, null,
                        "product"),
                LineInput.of(c.p.get("GQ-ADMIX"), null, "Water & block admixture", dec("200"), dec("500"), null, null,
                        "product")));
        poBill(c, "GQ-S02", sandPo, 5, null, "NSD-0981", LineInput.of(c.p.get("GQ-SAND"), null, "Sharp sand",
                dec("10"), dec("35000"), null, null, "product"));
    }

    // ------------------------------------------------------------------ demo one: extraction + crushing

    private void extract(Ctx c) {
        String site = str(quarry.createSite(in(SiteInput.class, "code", "GQ-Q1", "name", "Mukono granite quarry",
                "licenceNumber", "ML-0142", "branchId", c.qry)), "id");
        c.p.put("site", site);
        String material = str(quarry.createMaterial(in(MaterialInput.class, "code", "GQ-GR", "name", "Granite",
                "rockType", "granite", "densityTPerM3", "2.65", "productId", c.p.get("GQ-ROM"))), "id");
        String zone = str(quarry.createZone(in(ZoneInput.class, "siteId", site, "kind", "zone", "code", "Z1", "name",
                "North face")), "id");
        String bench = str(quarry.createZone(in(ZoneInput.class, "siteId", site, "parentId", zone, "kind", "bench",
                "code", "B1", "name", "Bench 1")), "id");
        c.p.put("rom", str(quarry.createStockpile(in(StockpileInput.class, "siteId", site, "code", "GQ-ROM", "name",
                "ROM pad", "kind", "rom")), "id"));
        c.p.put("fg", str(quarry.createStockpile(in(StockpileInput.class, "siteId", site, "code", "GQ-FG", "name",
                "Finished aggregates yard", "kind", "product", "locationId", c.yard)), "id"));

        String logId = str(extraction.create(in(ExtractionLogInput.class, "siteId", site, "zoneId", bench, "materialId",
                material, "stockpileId", c.p.get("rom"), "extractedOn", c.date(2), "shift", "day", "otherCost",
                19000000, "otherCostNote", "Drilling 12.0M + loading equipment 4.0M + internal haulage 3.0M",
                "notes", "Batch GQ-B01: 1,000 t raw granite to the ROM pad", "loads", List.of(Obj.of("method",
                        "survey", "tonnes", 1000)))), "id");
        extraction.post(logId);
        // The actual extraction costs, as the contractors bill them.
        serviceBill(c, "GQ-S07", 2, "RTD-0457", true, svc(c, "drilling", "Drilling & extraction, batch GQ-B01",
                "12000000"));
        serviceBill(c, "GQ-S08", 2, "EPH-1180", true, svc(c, "hire", "Loading equipment hire, batch GQ-B01",
                "4000000"));
        serviceBill(c, "GQ-S09", 2, "LH-3301", true, svc(c, "haulage", "Internal haulage pit to crusher, batch GQ-B01",
                "3000000"));
    }

    private String crush(Ctx c) {
        String plant = str(plants.create(in(PlantInput.class, "code", "GQ-CR1", "name", "Crusher line 1", "siteId",
                c.p.get("site"), "feedStockpileId", c.p.get("rom"), "designCapacityTph", 120, "dieselCostPerLitre",
                5000, "electricityCostPerKwh", 1000, "labourRatePerHour", 10000, "stages", List.of(
                        Obj.of("name", "Primary crushing", "kind", "primary", "units", List.of(Obj.of("code", "GQ-JAW",
                                "name", "Jaw crusher", "kind", "jaw_crusher", "capacityTph", 150, "meterKind",
                                "engine_hours"))),
                        Obj.of("name", "Screening", "kind", "screening", "units", List.of(Obj.of("code", "GQ-SCR",
                                "name", "Triple-deck screen", "kind", "screen", "capacityTph", 120, "meterKind",
                                "kwh")))))), "id");
        String fg = c.p.get("fg");
        String draft = str(profiles.create(in(ProfileInput.class, "plantId", plant, "code", "GQ-SPLIT", "name",
                "Granite split 60/25/10", "feedProductId", c.p.get("GQ-ROM"), "allocationMethod", "quantity",
                "lines", List.of(
                        Obj.of("productId", c.p.get("GQ-AGG-20"), "sizeLabel", "20 mm", "role", "main", "expectedPct",
                                60, "stocked", true, "stockpileId", fg),
                        Obj.of("productId", c.p.get("GQ-AGG-10"), "sizeLabel", "10 mm", "role", "co_product",
                                "expectedPct", 25, "stocked", true, "stockpileId", fg),
                        Obj.of("productId", c.p.get("GQ-DUST"), "sizeLabel", "0-6 mm", "role", "co_product",
                                "expectedPct", 10, "stocked", true, "stockpileId", fg)))), "id");
        String profile = str(profiles.activate(draft), "id");
        Map<String, String> grading = new LinkedHashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT id, \"productId\" FROM \"CrushingGradingLine\""
                + " WHERE \"organizationId\" = ? AND \"profileId\" = ?").params(c.org, profile))) {
            grading.put(str(l, "productId"), str(l, "id"));
        }
        String jaw = id("CrushingPlantUnit", "code", "GQ-JAW", c.org);
        String screen = id("CrushingPlantUnit", "code", "GQ-SCR", c.org);
        String run = str(runs.create(in(RunInput.class, "plantId", plant, "profileId", profile, "runDate", c.date(5),
                "shift", "day", "operatorName", "Patrick Plant", "feedStockpileId", c.p.get("rom"), "feedTonnes", 1000,
                "feedMethod", "belt_scale", "outputMethod", "belt_scale", "operatingHours", 10, "downtimeHours", 1,
                "downtimeReason", "Jaw liner check", "labourHours", 100, "notes",
                "Batch GQ-B01: 1,000 t feed, 50 t fines and spillage lost (normal loss 5%)", "outputs", List.of(
                        Obj.of("gradingLineId", grading.get(c.p.get("GQ-AGG-20")), "actualTonnes", 600),
                        Obj.of("gradingLineId", grading.get(c.p.get("GQ-AGG-10")), "actualTonnes", 250),
                        Obj.of("gradingLineId", grading.get(c.p.get("GQ-DUST")), "actualTonnes", 100)),
                "meters", List.of(Obj.of("unitId", jaw, "runHours", 10, "dieselLitres", 800),
                        Obj.of("unitId", screen, "kwh", 1000)),
                "costs", List.of(Obj.of("kind", "maintenance", "unitId", jaw, "amount", 1000000, "description",
                        "Equipment maintenance (jaw liners, bearings)"),
                        Obj.of("kind", "other", "amount", 1000000, "description",
                                "Depreciation allocation, crusher plant")))), "id");
        runs.post(run);
        return run;
    }

    // ------------------------------------------------------------------ demo three: concrete blocks

    private void blocks(Ctx c) {
        String press = str(centres.create(in(CreateWorkCenter.class, "code", "GQ-PRESS", "name",
                "Block press line")), "id");
        String routing = str(routings.create(in(CreateRouting.class, "name", "Concrete block line", "productId",
                c.p.get("GQ-BLOCK-6"), "operations", List.of(Obj.of("sequence", 10, "name", "Mix, press and cure",
                        "workCenterId", press, "durationMins", 480, "setupMins", 30)))), "id");
        String bom = str(boms.create(in(CreateBom.class, "name", "Concrete block 6 inch, 1,000 pcs", "outputProductId",
                c.p.get("GQ-BLOCK-6"), "outputQuantity", 1000, "overheadCost", 0, "defaultLocationId", c.yard,
                "defaultOutputLocationId", c.yard, "isDefault", true, "routingId", routing, "lines", List.of(
                        Obj.of("componentProductId", c.p.get("GQ-CEMENT"), "quantity", 30, "sequence", 0),
                        Obj.of("componentProductId", c.p.get("GQ-SAND"), "quantity", 5, "sequence", 1),
                        Obj.of("componentProductId", c.p.get("GQ-AGG-20"), "quantity", 4, "sequence", 2),
                        Obj.of("componentProductId", c.p.get("GQ-ADMIX"), "quantity", 100, "sequence", 3)))), "id");
        boms.activate(bom);
        String order = str(production.createOrder(in(CreateOrder.class, "bomId", bom, "locationId", c.yard,
                "outputLocationId", c.yard, "plannedQty", 1000, "scheduledFor", c.date(7), "batchNumber", "BLK-001",
                "mfgDate", c.date(7), "notes", "1,000 concrete blocks, 6 inch")), "id");
        jdbc.sql("UPDATE \"ProductionOrder\" SET status = 'confirmed' WHERE id = ? AND \"organizationId\" = ?")
                .params(order, c.org).update();
        stages.initialize(order, new InitializeStages("Block lot BLK-001"));
        String stage = jdbc.sql("SELECT id FROM \"ProductionOrderStage\" WHERE \"organizationId\" = ? AND"
                + " \"orderId\" = ? ORDER BY sequence LIMIT 1").params(c.org, order).query(String.class).single();
        conversion(c, stage, "200000", "labour", "Direct labour, block press crew");
        conversion(c, stage, "100000", "utility", "Electricity, block press");
        conversion(c, stage, "80000", "overhead", "Machine depreciation, block press");
        conversion(c, stage, "50000", "machine", "Maintenance, block press");
        stages.output(stage, new StageOutput(dec("1000"), BigDecimal.ZERO, null, true, null, "BLK-001", null,
                c.date(7), "1,000 blocks to the stockyard"));
    }

    private void conversion(Ctx c, String stage, String amount, String kind, String note) {
        stages.conversion(stage, new StageConversion(dec(amount), kind, null, c.date(7), note));
    }

    // ------------------------------------------------------------------ demo four: landed cost

    private void importBasalt(Ctx c) {
        String po = po(c, "GQ-S03", 3, "Imported basalt chippings, 500 t", "USD",
                line(c, "GQ-BASALT", "Basalt chippings 14mm", "500", "16"));
        String grn = receive(c, po, 8);
        c.p.put("bill:basalt", poBill(c, "GQ-S03", po, 8, "USD", "MBE-INV-5530", LineInput.of(c.p.get("GQ-BASALT"),
                null, "Basalt chippings 14mm", dec("500"), dec("16"), null, null, "product")));
        String lc = str(landedCost.create(new VoucherInput(null, List.of(grn), c.date(9),
                "Landed cost of 500 t imported basalt", null, List.of(
                        charge(c, "duty", "Customs and clearing", "GQ-S04", "SCF-2291", "1000"),
                        charge(c, "freight", "Transport Mombasa to Kampala", "GQ-S05", "NCF-7781", "1500"),
                        charge(c, "handling", "Loading and unloading", "GQ-S06", "KLS-0412", "300"),
                        charge(c, "other", "Port and documentation charges", "GQ-S04", "SCF-2292", "200")))), "id");
        landedCost.submit(lc);
        landedCost.approve(lc);
        landedCost.post(lc);
    }

    private ChargeInput charge(Ctx c, String kind, String description, String supplier, String ref, String usd) {
        return new ChargeInput(null, kind, description, c.partner.get(supplier), ref, c.date(9), "USD", USD_RATE,
                dec(usd), BigDecimal.ZERO, "capitalize", "quantity", null, null, null, null, null, null, null,
                c.date(30));
    }

    // ------------------------------------------------------------------ demo five: sales

    private void sales(Ctx c) {
        // SO-1, demo five: 100 t 20mm + 50 t 10mm, delivered by Lakeside Haulage for 500,000.
        Sale so1 = sale(c, "GQ-C01", c.kla, 12, "KB-PO-1101", "UBK 214Q", "Lakeside Haulage driver",
                lineOf(c, "GQ-AGG-20", "100", "45000"), lineOf(c, "GQ-AGG-10", "50", "40000"));
        jdbc.sql("UPDATE \"FleetLoad\" SET \"transporterPartnerId\" = ?, \"freightAmount\" = 500000 WHERE id = ? AND"
                + " \"organizationId\" = ?").params(c.partner.get("GQ-S09"), so1.load(), c.org).update();
        String freight = serviceBill(c, "GQ-S09", 13, "LH-3342", false, svc(c, "freight",
                "Delivery of sales order " + so1.number() + " to Kampala Builders", "500000"));
        jdbc.sql("UPDATE \"DocumentLine\" SET dimensions = ?::jsonb WHERE \"documentId\" = ?").params(
                "{\"customerId\":\"" + c.partner.get("GQ-C01") + "\",\"salesOrderId\":\"" + so1.order() + "\"}",
                freight).update();
        postBill(freight);
        c.p.put("inv:so1", so1.invoice());
        // SO-2: the rest of the batch to Nile Towers, collected ex-quarry.
        Sale so2 = sale(c, "GQ-C02", c.qry, 15, "NTC-LPO-77", "Customer trucks", "Nile Towers drivers",
                lineOf(c, "GQ-AGG-20", "496", "45000"), lineOf(c, "GQ-AGG-10", "200", "40000"),
                lineOf(c, "GQ-DUST", "100", "20000"));
        c.p.put("inv:so2", so2.invoice());
        // SO-3: the block lot.
        Sale so3 = sale(c, "GQ-C01", c.kla, 18, "KB-PO-1124", "UBK 214Q", "Lakeside Haulage driver",
                lineOf(c, "GQ-BLOCK-6", "1000", "2500"));
        c.p.put("inv:so3", so3.invoice());
        // SO-4: 300 of the 500 t imported basalt.
        Sale so4 = sale(c, "GQ-C02", c.qry, 20, "NTC-LPO-81", "Customer trucks", "Nile Towers drivers",
                lineOf(c, "GQ-BASALT", "300", "95000"));
        c.p.put("inv:so4", so4.invoice());
    }

    private record Sale(String order, String number, String load, String invoice) {}

    @SafeVarargs
    private Sale sale(Ctx c, String customer, String branch, int day, String ref, String plate, String driver,
            Map<String, Object>... lines) {
        Map<String, Object> so = salesOrders.create(Obj.of("partnerId", c.partner.get(customer), "branchId", branch,
                "locationId", c.yard, "paymentMode", "credit", "deliveryMethod", "delivery", "requestedDate",
                c.date(day), "customerRef", ref, "lines", List.of(lines)));
        String order = str(so, "id");
        salesOrders.confirm(order);
        String load = str(deliveries.create(order, Obj.of("vehiclePlate", plate, "driverName", driver, "plannedDate",
                c.date(day), "locationId", c.yard)), "id");
        dispatch.dispatchAt(load, null, c.at(day, 9));
        fulfilment.rebuild(order);
        List<Map<String, Object>> pod = new ArrayList<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT id, \"dispatchedQty\" FROM \"FleetLoadLine\" WHERE"
                + " \"organizationId\" = ? AND \"loadId\" = ?").params(c.org, load))) {
            pod.add(Obj.of("loadLineId", l.get("id"), "deliveredQty", SqlRows.dec(l, "dispatchedQty")));
        }
        deliveries.confirm(load, Obj.of("receiverName", "Site storekeeper", "receivedAt", c.at(day, 13).toString(),
                "lines", pod));
        String invoice = str(billing.createInvoice(order, Obj.of("mode", "regular", "issueDate", c.date(day))), "id");
        jdbc.sql("UPDATE \"Document\" SET \"branchId\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(branch, invoice, c.org).update();
        invoices.post(invoice);
        return new Sale(order, str(so, "number"), load, invoice);
    }

    private Map<String, Object> lineOf(Ctx c, String product, String qty, String price) {
        return Obj.of("productId", c.p.get(product), "description", name(c, product), "quantity", dec(qty),
                "unitPrice", dec(price));
    }

    // ------------------------------------------------------------------ actual production costs, month end

    private void monthEnd(Ctx c) {
        serviceBill(c, "GQ-S10", 25, "EQF-88213", true, svc(c, "fuel", "Diesel 800 L at 5,000, crusher line",
                "4000000"));
        serviceBill(c, "GQ-S11", 25, "KPD-ACC-4471", true,
                svc(c, "power", "Electricity 1,000 kWh at 1,000, crusher line", "1000000"),
                svc(c, "power", "Electricity, block press", "100000"));
        serviceBill(c, "GQ-S12", 25, "CSS-0719", true,
                svc(c, "maintenance", "Crusher maintenance (jaw liners, bearings)", "1000000"),
                svc(c, "maintenance", "Block press maintenance", "50000"));
        journal(c, 28, "GEN", "Payroll: plant crews", "gqdemo:payroll", List.of(
                Line.debit(c.acc.get("wages"), "1000000", "Crusher crew, 100 h at 10,000"),
                Line.debit(c.acc.get("wages"), "200000", "Block press crew"),
                Line.credit(c.acc.get("wagesPayable"), "1200000", "Net pay payable")));
        journal(c, 28, "GEN", "Depreciation: crusher plant and block machine", "gqdemo:depreciation", List.of(
                Line.debit(c.acc.get("depreciation"), "1000000", "Crusher plant"),
                Line.debit(c.acc.get("depreciation"), "80000", "Block machine"),
                Line.credit(c.acc.get("accumulated"), "1080000", "Accumulated depreciation")));
        journal(c, 28, "BANK", "Payroll paid", "gqdemo:payroll-paid", List.of(
                Line.debit(c.acc.get("wagesPayable"), "1200000", "Net pay"),
                Line.credit(c.acc.get("bank"), "1200000", "Salaries transfer")));
    }

    private void settle(Ctx c) {
        receipt(c, "GQ-C01", 26, "6500000", c.p.get("inv:so1"), "KB transfer 1101");
        receipt(c, "GQ-C01", 26, "2500000", c.p.get("inv:so3"), "KB transfer 1124");
        receipt(c, "GQ-C02", 27, "30000000", c.p.get("inv:so2"), "NTC part payment");
        payBill(c, "GQ-S07", 27, "12000000", null, "EFT drilling");
        payBill(c, "GQ-S08", 27, "4000000", null, "EFT plant hire");
        payBill(c, "GQ-S10", 27, "4000000", null, "EFT diesel");
        payBill(c, "GQ-S01", 27, "2200000", c.p.get("bill:cement"), "EFT cement");
        payBill(c, "GQ-S03", 27, "8000", c.p.get("bill:basalt"), "TT basalt");
    }

    private void receipt(Ctx c, String customer, int day, String amount, String invoice, String ref) {
        payments.createReceipt(new PaymentInput(c.partner.get(customer), c.date(day), dec(amount), "bank", null, ref,
                null, List.of(new AllocationInput(invoice, null, dec(amount))), null, null, null, null), true, false);
    }

    /** Pay a supplier: the given bill, or the supplier's only open bill. */
    private void payBill(Ctx c, String supplier, int day, String amount, String bill, String ref) {
        String doc = bill != null ? bill : jdbc.sql("SELECT id FROM \"Document\" WHERE \"organizationId\" = ? AND"
                + " \"partnerId\" = ? AND \"documentType\" = 'vendor_bill' ORDER BY \"createdAt\" LIMIT 1")
                .params(c.org, c.partner.get(supplier)).query(String.class).single();
        boolean usd = "GQ-S03".equals(supplier);
        payments.createSupplierPayment(new PaymentInput(c.partner.get(supplier), c.date(day), dec(amount), "bank",
                null, ref, null, List.of(new AllocationInput(doc, null, dec(amount))), null, null, null, null,
                usd ? "USD" : null, usd ? USD_RATE : null), true);
    }

    // ------------------------------------------------------------------ helpers

    private void journal(Ctx c, int day, String journal, String description, String key, List<Line> lines) {
        posting.post(new PostingRequest(journal, c.at(day, 17), description, null, null, "demo_journal", null, null,
                key + ":" + c.org, c.qry, null, null, lines));
    }

    private PurchaseOrdersService.LineInput line(Ctx c, String product, String description, String qty, String price) {
        return new PurchaseOrdersService.LineInput(c.p.get(product), description, dec(qty), null, dec(price), null,
                BigDecimal.ZERO, null);
    }

    private String po(Ctx c, String supplier, int day, String description, String currency,
            PurchaseOrdersService.LineInput... lines) {
        Map<String, Object> po = purchaseOrders.create(new CreateInput(c.partner.get(supplier), c.qry, description,
                c.date(day + 3), currency == null ? "UGX" : currency, currency == null ? BigDecimal.ONE : USD_RATE,
                "credit", null, null, c.yard, null, List.of(lines), null));
        jdbc.sql("UPDATE \"PurchaseOrder\" SET \"orderDate\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(SqlRows.ts(c.at(day, 10)), str(po, "id"), c.org).update();
        return str(po, "id");
    }

    /** Receive every line of the order in full; returns the receipt id. */
    @SuppressWarnings("unchecked")
    private String receive(Ctx c, String poId, int day) {
        List<ReceiveLineInput> lines = new ArrayList<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT id, \"productId\", description, quantity FROM"
                + " \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ? ORDER BY \"lineNumber\"").param(poId))) {
            lines.add(new ReceiveLineInput(str(l, "id"), str(l, "productId"), str(l, "description"),
                    SqlRows.dec(l, "quantity"), null, null, null, null));
        }
        Map<String, Object> r = purchaseOrders.receive(poId, new ReceiveInput(c.yard, c.date(day), null, lines));
        return str((Map<String, Object>) r.get("grn"), "id");
    }

    /** The supplier's invoice for a received order (in the order's currency and price): no new GL, opens AP. */
    private String poBill(Ctx c, String supplier, String poId, int day, String currency, String ref,
            LineInput... lines) {
        String id = str(bills.create(new BillInput(c.partner.get(supplier), c.date(day), c.date(day + 30), currency,
                ref, null, c.qry, List.of(poId), List.of(lines))), "id");
        if (currency != null) {
            jdbc.sql("UPDATE \"Document\" SET \"exchangeRate\" = ? WHERE id = ? AND \"organizationId\" = ?")
                    .params(USD_RATE, id, c.org).update();
        }
        postBill(id);
        return id;
    }

    /** A bill for services (no stock): Dr the line accounts / Cr the supplier; left a draft when {@code !post}. */
    private String serviceBill(Ctx c, String supplier, int day, String ref, boolean post, LineInput... lines) {
        String id = str(bills.create(new BillInput(c.partner.get(supplier), c.date(day), c.date(day + 30), null, ref,
                null, c.qry, null, List.of(lines))), "id");
        if (post) {
            postBill(id);
        }
        return id;
    }

    private void postBill(String id) {
        bills.post(id, false, false);
    }

    private LineInput svc(Ctx c, String account, String description, String amount) {
        return LineInput.of(null, c.acc.get(account), description, BigDecimal.ONE, dec(amount), null, null, "product");
    }

    private void product(Ctx c, String code, String name, String uom, String sales, String cost) {
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"salesPrice\", \"costPrice\", \"uomId\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, 'stockable'::\"ProductType\", true, 'AVCO'::\"CostingMethod\", ?, ?, ?, now())"
                + " ON CONFLICT (\"organizationId\", code) DO NOTHING").params(UUID.randomUUID().toString(), c.org,
                        code, name, dec(sales), dec(cost), uom).update();
        c.p.put(code, id("Product", "code", code, c.org));
    }

    private String name(Ctx c, String productCode) {
        return jdbc.sql("SELECT name FROM \"Product\" WHERE id = ?").param(c.p.get(productCode)).query(String.class)
                .single();
    }

    private void partner(Ctx c, String code, String name, boolean customer) {
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isSupplier\","
                + " \"isCompany\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, true, now())").params(
                        UUID.randomUUID().toString(), c.org, code, name, customer, !customer).update();
        c.partner.put(code, id("Partner", "code", code, c.org));
    }

    private String id(String table, String column, String value, String org) {
        return jdbc.sql("SELECT id FROM \"" + table + "\" WHERE \"organizationId\" = ? AND \"" + column + "\" = ?"
                + " LIMIT 1").params(org, value).query(String.class).optional().orElse(null);
    }

    private <T> T in(Class<T> type, Object... kv) {
        return mapper.convertValue(Obj.of(kv), type);
    }

    private static BigDecimal dec(String v) {
        return new BigDecimal(v);
    }
}
