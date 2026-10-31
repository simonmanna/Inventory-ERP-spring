package com.inventoryerp.fleet.application;

import com.inventoryerp.core.application.ProductUnitProfileService;
import com.inventoryerp.core.application.ProductUnitProfileService.ProfileInput;
import com.inventoryerp.fleet.application.DispatchCommands.AssignInput;
import com.inventoryerp.fleet.application.DispatchCommands.ChargeRuleInput;
import com.inventoryerp.fleet.application.DispatchCommands.CloseOrderInput;
import com.inventoryerp.fleet.application.DispatchCommands.CloseTripInput;
import com.inventoryerp.fleet.application.DispatchCommands.DeliveryOrderInput;
import com.inventoryerp.fleet.application.DispatchCommands.InvoiceRequest;
import com.inventoryerp.fleet.application.DispatchCommands.LoadFactorInput;
import com.inventoryerp.fleet.application.DispatchCommands.PlanInput;
import com.inventoryerp.fleet.application.DispatchCommands.PodInput;
import com.inventoryerp.fleet.application.DispatchCommands.SiteInput;
import com.inventoryerp.fleet.application.DispatchCommands.TransporterRateInput;
import com.inventoryerp.fleet.application.DriverCommands.DriverInput;
import com.inventoryerp.fleet.application.FleetCommands.ComplianceInput;
import com.inventoryerp.fleet.application.FleetCommands.VehicleInput;
import com.inventoryerp.fleet.application.FleetReportCommands.GenerateFreightBills;
import com.inventoryerp.fleet.application.FuelCommands.FuelCardInput;
import com.inventoryerp.fleet.application.FuelCommands.FuelLogInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.CompleteInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.LabourInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.PartIssueInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.WorkOrderInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.DeviceInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.ProductSettingInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.SimulatorWeight;
import com.inventoryerp.fleet.application.WeighbridgeCommands.StationInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.TicketInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.WeighInput;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.invoicing.application.InvoiceService;
import com.inventoryerp.invoicing.application.PaymentService;
import com.inventoryerp.invoicing.application.PaymentService.AllocationInput;
import com.inventoryerp.invoicing.application.PaymentService.PaymentInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.seed.ConstructionDemoPart;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fleet part of the {@code SEED_CONSTRUCTION_DEMO} organization: 10 tippers, 2 wheel loaders and an excavator, 15
 * drivers and operators, sites for the 20 customers, 2 transporters, 2 weighbridges on simulator indicators, then three
 * months of working days: six own deliveries and on alternate days a transporter delivery, each with a weighbridge
 * ticket, dispatch from stock, proof of delivery, closing and a posted invoice; fuel-card fuel and workshop jobs; bank
 * receipts for the older invoices and monthly draft transporter freight bills. Follows the plant data of the
 * manufacturing part ({@code DM-*} products and stockpiles). Everything goes through the services, so stock and GL come
 * from their engines; the past dates are carried by {@code dispatchAt}, {@code issuePartAt} and the document dates, and
 * the lifecycle times of a day's documents are set afterwards on fleet's own rows, as the fleet demo does. No journal
 * row is written here. Skipped when the first truck exists.
 */
@Component
public class FleetConstructionDemoSeed implements ConstructionDemoPart {

    private static final Logger log = LoggerFactory.getLogger(FleetConstructionDemoSeed.class);
    private static final int DAYS = 90;
    private static final int TRUCKS = 10;
    private static final int OWN_TRIPS_PER_DAY = 6;
    private static final int KAMPALA_CUSTOMERS = 12;
    private static final String TRANSPORTER_PRODUCT = "DM-AGG-20";

    /** Site name, city, zone, km from the yard, one per customer {@code CM-CUST-nn}. */
    private static final String[][] SITES = {
        {"Kololo apartments", "Kampala", "Kampala Central", "28"},
        {"Jinja Road towers", "Kampala", "Kampala Central", "26"},
        {"Northern bypass lot 4", "Kampala", "Kampala North", "24"},
        {"Munyonyo estate", "Kampala", "Kampala South", "34"},
        {"Mengo school block", "Kampala", "Kampala Central", "29"},
        {"Kololo mall", "Kampala", "Kampala Central", "28"},
        {"Ntinda villas", "Kampala", "Kampala North", "22"},
        {"Bukoto flats", "Kampala", "Kampala North", "23"},
        {"Makindye chapel", "Kampala", "Kampala South", "31"},
        {"Entebbe apron", "Entebbe", "Entebbe", "55"},
        {"Wakiso phase 2", "Wakiso", "Wakiso", "19"},
        {"Kira drainage", "Kira", "Kampala North", "18"},
        {"Mukono bypass", "Mukono", "Mukono", "46"},
        {"Jinja bridge approach", "Jinja", "Eastern", "88"},
        {"Mbale works yard", "Mbale", "Eastern", "120"},
        {"Masaka town store", "Masaka", "Central", "95"},
        {"Gulu ring road", "Gulu", "Northern", "130"},
        {"Mbarara store", "Mbarara", "Western", "140"},
        {"Fort Portal hotel", "Fort Portal", "Western", "150"},
        {"Lugazi park", "Lugazi", "Mukono", "60"},
    };

    /** Product mix of the own deliveries, repeating: code, quantity per trip, price, kg per stock unit. */
    private static final Object[][] MIX = {
        {"DM-AGG-20", 20, 45000, 1000}, {"DM-AGG-10", 18, 42000, 1000}, {"DM-AGG-20", 20, 45000, 1000},
        {"DM-BLOCK-6", 400, 2500, 18}, {"DM-DUST", 20, 20000, 1000}, {"DM-AGG-10", 18, 42000, 1000},
    };

    /** Stock the depot starts with: product code, quantity. */
    private static final Object[][] DEPOT_STOCK = {
        {"DM-AGG-20", 2500}, {"DM-AGG-10", 2200}, {"DM-DUST", 1200}, {"DM-BLOCK-6", 20000},
    };

    private record Unit(String id, String code, boolean truck) {}

    private record Customer(String id, String siteId, BigDecimal km, boolean depot) {}

    private record Billed(String partnerId, String invoiceId, BigDecimal amount, LocalDate day, String branchId) {}

    /** What the setup created, and the running state of the days. */
    private static final class Demo {
        String org;
        String quarryBranch;
        String depotBranch;
        String yard;
        String depot;
        String quarryStation;
        String depotStation;
        String quarryDevice;
        String depotDevice;
        String oil;
        String filter;
        String card;
        String[] mechanics;
        String[] rates;
        String[] transporters;
        final Map<String, String> products = new HashMap<>();
        final Map<String, String> stockpiles = new HashMap<>();
        final List<Unit> units = new ArrayList<>();
        final List<String> drivers = new ArrayList<>();
        final List<Customer> customers = new ArrayList<>();
        final List<Billed> billed = new ArrayList<>();
        BigDecimal[] meters;
        int trip;
        int transporterTrip;
        int jobs;
    }

    private final FleetDb db;
    private final JsonMapper mapper;
    private final TransactionTemplate tx;
    private final FleetCategoryService categories;
    private final FleetVehicleService vehicles;
    private final FleetDriverService drivers;
    private final FleetComplianceService compliance;
    private final FleetCustomerSiteService sites;
    private final FleetDispatchSettingsService dispatchSettings;
    private final FleetDeliveryOrderService orders;
    private final FleetDispatchService dispatch;
    private final FleetDeliveryInvoiceService deliveryInvoices;
    private final FleetFuelCardService fuelCards;
    private final FleetFuelLogService fuelLogs;
    private final FleetWorkOrderService workOrders;
    private final FleetWorkOrderCostService workOrderCosts;
    private final FleetFreightBillService freightBills;
    private final WeighbridgeStationService stations;
    private final WeighbridgeTicketService tickets;
    private final StockService stock;
    private final InvoiceService invoices;
    private final PaymentService payments;
    private final ProductUnitProfileService unitProfiles;

    public FleetConstructionDemoSeed(FleetDb db, JsonMapper mapper, PlatformTransactionManager txm,
            FleetCategoryService categories, FleetVehicleService vehicles, FleetDriverService drivers,
            FleetComplianceService compliance, FleetCustomerSiteService sites,
            FleetDispatchSettingsService dispatchSettings, FleetDeliveryOrderService orders,
            FleetDispatchService dispatch, FleetDeliveryInvoiceService deliveryInvoices,
            FleetFuelCardService fuelCards, FleetFuelLogService fuelLogs, FleetWorkOrderService workOrders,
            FleetWorkOrderCostService workOrderCosts, FleetFreightBillService freightBills,
            WeighbridgeStationService stations, WeighbridgeTicketService tickets, StockService stock,
            InvoiceService invoices, PaymentService payments, ProductUnitProfileService unitProfiles) {
        this.db = db;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txm);
        this.categories = categories;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.compliance = compliance;
        this.sites = sites;
        this.dispatchSettings = dispatchSettings;
        this.orders = orders;
        this.dispatch = dispatch;
        this.deliveryInvoices = deliveryInvoices;
        this.fuelCards = fuelCards;
        this.fuelLogs = fuelLogs;
        this.workOrders = workOrders;
        this.workOrderCosts = workOrderCosts;
        this.freightBills = freightBills;
        this.stations = stations;
        this.tickets = tickets;
        this.stock = stock;
        this.invoices = invoices;
        this.payments = payments;
        this.unitProfiles = unitProfiles;
    }

    @Override
    public int constructionOrder() {
        return 400;
    }

    @Override
    public void seedConstructionDemo(String organizationId) {
        String admin = db.jdbc().sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\""
                + " LIMIT 1").param(organizationId).query(String.class).optional().orElse(null);
        TenantContext.run(TenantStore.user(organizationId, admin), () -> {
            TenantContext.setPermissions(List.copyOf(Permissions.ALL));
            if (id("FleetVehicle", "code", "CM-T01", organizationId) != null) {
                return;
            }
            Demo d = tx.execute(s -> setup(organizationId));
            LocalDate today = LocalDate.now(BusinessZone.get());
            for (int back = DAYS; back >= 1; back--) {
                LocalDate day = today.minusDays(back);
                if (day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    continue;
                }
                int daysBack = back;
                tx.executeWithoutResult(s -> workDay(d, day, daysBack));
            }
            tx.executeWithoutResult(s -> {
                collect(d, today);
                bills(today);
                liveWork(d, today);
            });
            log.info("construction demo, fleet part in org {}: {} own and {} transporter trips, {} workshop jobs",
                    organizationId, d.trip, d.transporterTrip, d.jobs);
        });
    }

    private <T> T in(Class<T> type, Object... kv) {
        return mapper.convertValue(Obj.of(kv), type);
    }

    private String id(String table, String column, String value, String org) {
        return db.jdbc().sql("SELECT id FROM \"" + table + "\" WHERE \"organizationId\" = ? AND \"" + column + "\" = ?"
                + " LIMIT 1").params(org, value).query(String.class).optional().orElse(null);
    }

    private static Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(BusinessZone.get()).toInstant();
    }

    // ------------------------------------------------------------------ set-up

    private Demo setup(String org) {
        Demo d = new Demo();
        d.org = org;
        LocalDate today = LocalDate.now(BusinessZone.get());
        categories.ensureDefaults();
        d.quarryBranch = id("Branch", "code", "QRY", org);
        d.depotBranch = id("Branch", "code", "KLA", org);
        d.yard = id("InventoryLocation", "code", "MAIN", org);
        d.depot = id("InventoryLocation", "code", "KLA-YARD", org);
        for (String code : List.of("DM-AGG-20", "DM-AGG-10", "DM-DUST", "DM-BLOCK-6")) {
            d.products.put(code, id("Product", "code", code, org));
        }
        d.oil = id("Product", "code", "CM-OIL-15W40", org);
        d.filter = id("Product", "code", "CM-FILTER", org);
        for (Object[] s : new Object[][] {{"DM-AGG-20", "DM-A20"}, {"DM-AGG-10", "DM-A10"}, {"DM-DUST", "DM-DU"}}) {
            d.stockpiles.put((String) s[0], db.jdbc().sql("SELECT \"locationId\" FROM \"QuarryStockpile\" WHERE"
                    + " \"organizationId\" = ? AND code = ?").params(org, s[1]).query(String.class).single());
        }
        d.stockpiles.put("DM-BLOCK-6", d.yard);
        materials(d);
        weighbridges(d);
        d.mechanics = new String[] {id("HrEmployee", "employeeCode", "EMP-CM16", org),
            id("HrEmployee", "employeeCode", "EMP-CM17", org)};
        units(d, today);
        people(d, today);
        customers(d);
        transporters(d, today);
        return d;
    }

    /** Density and piece weight of what is sold, load factors for planning, opening spares and the depot's stock. */
    private void materials(Demo d) {
        unitProfiles.save(d.products.get("DM-AGG-20"), new ProfileInput(new BigDecimal("1.55"), null, null, null));
        unitProfiles.save(d.products.get("DM-AGG-10"), new ProfileInput(new BigDecimal("1.50"), null, null, null));
        unitProfiles.save(d.products.get("DM-DUST"), new ProfileInput(new BigDecimal("1.60"), null, null, null));
        unitProfiles.save(d.products.get("DM-BLOCK-6"), new ProfileInput(null, new BigDecimal("18"), null, null));
        dispatchSettings.putLoadFactor(d.products.get("DM-AGG-20"), in(LoadFactorInput.class, "tonnesPerUnit", 1,
                "cubicMetresPerUnit", "0.645", "notes", "Bulk density 1.55 t/m3"));
        dispatchSettings.putLoadFactor(d.products.get("DM-AGG-10"), in(LoadFactorInput.class, "tonnesPerUnit", 1,
                "cubicMetresPerUnit", "0.667", "notes", "Bulk density 1.50 t/m3"));
        dispatchSettings.putLoadFactor(d.products.get("DM-DUST"), in(LoadFactorInput.class, "tonnesPerUnit", 1,
                "cubicMetresPerUnit", "0.625", "notes", "Bulk density 1.60 t/m3"));
        dispatchSettings.putLoadFactor(d.products.get("DM-BLOCK-6"), in(LoadFactorInput.class, "tonnesPerUnit",
                "0.018", "cubicMetresPerUnit", "0.0144", "notes", "6 inch hollow block"));
        stock.receive(StockMove.of(d.oil, d.yard, new BigDecimal("900")).unitCost(new BigDecimal("9000")));
        stock.receive(StockMove.of(d.filter, d.yard, new BigDecimal("60")).unitCost(new BigDecimal("45000")));
        for (Object[] s : DEPOT_STOCK) {
            String code = (String) s[0];
            stock.transfer(StockMove.transfer(d.products.get(code), d.stockpiles.get(code), d.depot,
                    new BigDecimal((Integer) s[1])).reference("Depot opening stock"));
        }
    }

    /** A station at each gate, each with a simulator indicator: no real weighbridge is connected in the demo. */
    private void weighbridges(Demo d) {
        d.quarryStation = (String) stations.createStation(in(StationInput.class, "code", "CM-WB1", "name",
                "Quarry gate", "branchId", d.quarryBranch, "locationId", d.yard, "tolerancePct", 2, "toleranceKg", 100,
                "tareValidityDays", 365, "notes", "Demo: simulator indicator")).get("id");
        d.depotStation = (String) stations.createStation(in(StationInput.class, "code", "CM-WB2", "name",
                "Depot gate", "branchId", d.depotBranch, "locationId", d.depot, "tolerancePct", 2, "toleranceKg", 100,
                "tareValidityDays", 365, "notes", "Demo: simulator indicator")).get("id");
        d.quarryDevice = device(d.quarryStation, "CM-SIM1");
        d.depotDevice = device(d.depotStation, "CM-SIM2");
        stations.putProductSetting(d.products.get("DM-BLOCK-6"), new ProductSettingInput(new BigDecimal("18"), null,
                null, "6 inch hollow block"));
    }

    private String device(String station, String code) {
        return (String) stations.createDevice(in(DeviceInput.class, "stationId", station, "code", code, "name",
                "Simulator " + code, "adapterType", SimulatorWeighbridgeAdapter.TYPE, "config", Obj.of("weightKg",
                        "0"))).get("id");
    }

    private void units(Demo d, LocalDate today) {
        String tipper = id("FleetVehicleCategory", "code", "TIPPER", d.org);
        String loader = id("FleetVehicleCategory", "code", "WHEEL_LOADER", d.org);
        String excavator = id("FleetVehicleCategory", "code", "EXCAVATOR", d.org);
        Instant opening = at(today.minusDays(DAYS + 1L), 6);
        d.meters = new BigDecimal[TRUCKS + 3];
        for (int i = 0; i < TRUCKS; i++) {
            boolean depot = i >= 6;
            d.meters[i] = new BigDecimal(30000 + 2750L * i);
            String code = "CM-T%02d".formatted(i + 1);
            String vehicle = (String) vehicles.create(in(VehicleInput.class, "code", code, "name", "Tipper " + (i + 1),
                    "registrationPlate", "UBR %03dK".formatted(101 + i), "make", "Sinotruk", "model", "HOWO 371",
                    "year", 2022, "categoryId", tipper, "payloadCapacityT", 20, "bodyVolumeM3", 16, "axleConfig",
                    "6x4", "fuelType", "diesel", "tankCapacityL", 400, "openingOdometer", d.meters[i], "branchId",
                    depot ? d.depotBranch : d.quarryBranch, "homeLocationId", depot ? d.depot : d.yard)).get("id");
            openedBefore(d.org, vehicle, "odometerAt", opening);
            d.units.add(new Unit(vehicle, code, true));
            compliance.create(vehicle, in(ComplianceInput.class, "docType", "insurance", "documentNumber",
                    "CM-POL-2026-%02d".formatted(i + 1), "issuer", "Jubilee Insurance", "issuedOn",
                    today.minusMonths(4).toString(), "expiresOn", today.minusMonths(4).plusYears(1).minusDays(1)
                            .toString(), "costAmount", 4_200_000));
            compliance.create(vehicle, in(ComplianceInput.class, "docType", "road_licence", "documentNumber",
                    "CM-RL-%02d".formatted(i + 1), "issuedOn", today.minusMonths(11).toString(), "expiresOn",
                    today.plusDays(30 + 15L * i).toString(), "costAmount", 350_000));
        }
        Object[][] plant = {
            {"CM-LDR1", "Wheel loader 1", "Caterpillar", "950GC", loader, 3500},
            {"CM-LDR2", "Wheel loader 2", "Caterpillar", "950GC", loader, 4100},
            {"CM-EXC1", "Excavator", "Komatsu", "PC210", excavator, 5200},
        };
        for (int i = 0; i < plant.length; i++) {
            Object[] p = plant[i];
            d.meters[TRUCKS + i] = new BigDecimal((Integer) p[5]);
            String vehicle = (String) vehicles.create(in(VehicleInput.class, "code", p[0], "name", p[1], "make", p[2],
                    "model", p[3], "year", 2021, "categoryId", p[4], "fuelType", "diesel", "tankCapacityL", 300,
                    "openingHours", p[5], "branchId", d.quarryBranch, "homeLocationId", d.yard)).get("id");
            openedBefore(d.org, vehicle, "hoursAt", opening);
            d.units.add(new Unit(vehicle, (String) p[0], false));
        }
    }

    /** The meter rule orders readings by date: the opening reading must predate the first back-dated trip. */
    private void openedBefore(String org, String vehicleId, String atColumn, Instant at) {
        db.jdbc().sql("UPDATE \"FleetMeterReading\" SET \"readingAt\" = ? WHERE \"organizationId\" = ? AND"
                + " \"vehicleId\" = ? AND source = 'opening'").params(SqlRows.ts(at), org, vehicleId).update();
        db.jdbc().sql("UPDATE \"FleetVehicle\" SET \"" + atColumn + "\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(SqlRows.ts(at), vehicleId, org).update();
    }

    private void people(Demo d, LocalDate today) {
        String tipper = id("FleetVehicleCategory", "code", "TIPPER", d.org);
        String loader = id("FleetVehicleCategory", "code", "WHEEL_LOADER", d.org);
        String excavator = id("FleetVehicleCategory", "code", "EXCAVATOR", d.org);
        for (int i = 1; i <= 15; i++) {
            String employee = id("HrEmployee", "employeeCode", "EMP-CM%02d".formatted(i), d.org);
            boolean operator = i > 12;
            String driver = (String) drivers.create(in(DriverInput.class, "employeeId", employee, "driverType",
                    operator ? "operator" : "driver", "licenceNumber", "UG-DL-7%02d".formatted(i), "licenceClasses",
                    operator ? List.of("G") : List.of("CM", "CH"), "licenceIssuer", "Ministry of Works",
                    "licenceExpiresOn", today.plusYears(2).plusDays(25L * i).toString(), "qualifiedCategoryIds",
                    operator ? List.of(loader, excavator) : List.of(tipper))).get("id");
            if (!operator) {
                d.drivers.add(driver);
            }
        }
    }

    private void customers(Demo d) {
        for (int i = 0; i < SITES.length; i++) {
            String partner = id("Partner", "code", "CM-CUST-%02d".formatted(i + 1), d.org);
            String[] s = SITES[i];
            String site = (String) sites.create(in(SiteInput.class, "partnerId", partner, "name", s[0], "address",
                    s[0] + ", " + s[1], "city", s[1], "contactName", "Site engineer", "contactPhone",
                    "+2567001%05d".formatted(i + 1), "zone", s[2], "distanceKm", s[3])).get("id");
            d.customers.add(new Customer(partner, site, new BigDecimal(s[3]), i < KAMPALA_CUSTOMERS));
        }
        dispatchSettings.createChargeRule(in(ChargeRuleInput.class, "name", "Delivery Kampala per tonne", "zone",
                "Kampala Central", "basis", "per_tonne", "rate", 2500, "minAmount", 30000));
        dispatchSettings.createChargeRule(in(ChargeRuleInput.class, "name", "Delivery Mukono per tonne", "zone",
                "Mukono", "basis", "per_tonne", "rate", 4000, "minAmount", 50000));
    }

    private void transporters(Demo d, LocalDate today) {
        d.transporters = new String[] {id("Partner", "code", "CM-TRAN-01", d.org),
            id("Partner", "code", "CM-TRAN-02", d.org)};
        d.rates = new String[] {
            (String) dispatchSettings.createRate(in(TransporterRateInput.class, "partnerId", d.transporters[0], "name",
                    "Haulage per tonne", "basis", "per_tonne", "rate", 3500)).get("id"),
            (String) dispatchSettings.createRate(in(TransporterRateInput.class, "partnerId", d.transporters[1], "name",
                    "Haulage per km", "basis", "per_km", "rate", 5500)).get("id")};
        d.card = (String) fuelCards.create(in(FuelCardInput.class, "cardNumber", "7004 1200 0000 8821", "label",
                "Fleet card", "providerPartnerId", id("Partner", "code", "CM-FUEL-01", d.org), "status", "active",
                "expiresOn", today.plusYears(2).toString())).get("id");
    }

    // ------------------------------------------------------------------ a working day

    private void workDay(Demo d, LocalDate day, int back) {
        for (int n = 0; n < OWN_TRIPS_PER_DAY; n++) {
            ownDelivery(d, day, 7 + n);
        }
        if (back % 2 == 0) {
            transporterDelivery(d, day);
        }
        for (int u = 0; u < d.units.size(); u++) {
            if ((back + u) % 5 == 0) {
                fuel(d, u, day, back);
            }
            if ((back + 7 * u) % 30 == 0) {
                workshop(d, u, day);
            }
        }
    }

    private void ownDelivery(Demo d, LocalDate day, int hour) {
        int n = d.trip++;
        Object[] mix = MIX[n % MIX.length];
        String code = (String) mix[0];
        int customerIndex = n % d.customers.size();
        Customer c = d.customers.get(customerIndex);
        int truck = n % TRUCKS;
        BigDecimal qty = new BigDecimal((Integer) mix[1]);
        String location = c.depot() ? d.depot : d.stockpiles.get(code);
        String branch = c.depot() ? d.depotBranch : d.quarryBranch;
        String order = deliveryOrder(c, code, d, qty, (Integer) mix[2], location, branch, day);
        Unit unit = d.units.get(truck);
        String trip = firstTrip(dispatch.plan(order, in(PlanInput.class, "vehicleId", unit.id(), "driverId",
                d.drivers.get(truck), "plannedDate", day.toString())));
        Instant out = at(day, hour);
        long tare = 13000L + 50L * truck;
        // Bulk loads come in a little under the order; blocks weigh exactly their count.
        long net = qty.longValue() * (Integer) mix[3] - ("DM-BLOCK-6".equals(code) ? 0 : (n % 7) * 20L);
        String device = c.depot() ? d.depotDevice : d.quarryDevice;
        String ticket = weigh(c.depot() ? d.depotStation : d.quarryStation, device, trip, tare, tare + net,
                n % 12 == 11);
        BigDecimal km = c.km().multiply(BigDecimal.TWO);
        BigDecimal start = d.meters[truck];
        BigDecimal end = start.add(km);
        d.meters[truck] = end;
        finishTrip(trip, ticket, start, end, km, out);
        invoice(d, order, c.id(), day, branch);
    }

    private void transporterDelivery(Demo d, LocalDate day) {
        int n = d.transporterTrip++;
        Customer c = d.customers.get(KAMPALA_CUSTOMERS + n % (d.customers.size() - KAMPALA_CUSTOMERS));
        int which = n % 2;
        String order = deliveryOrder(c, TRANSPORTER_PRODUCT, d, new BigDecimal("25"), 45000,
                d.stockpiles.get(TRANSPORTER_PRODUCT), d.quarryBranch, day);
        String trip = firstTrip(dispatch.plan(order, in(PlanInput.class, "payloadT", 30, "plannedDate",
                day.toString())));
        dispatch.assign(trip, in(AssignInput.class, "carrier", "transporter", "transporterPartnerId",
                d.transporters[which], "transporterRateId", d.rates[which], "transporterPlate", which == 0 ? "UAZ 455M"
                        : "UBA 812T", "transporterDriverName", which == 0 ? "Wasswa" : "Etyang"));
        String ticket = weigh(d.quarryStation, d.quarryDevice, trip, 14500, 14500 + 25000 - (n % 5) * 30L, false);
        finishTrip(trip, ticket, null, null, null, at(day, 14));
        invoice(d, order, c.id(), day, d.quarryBranch);
    }

    private String deliveryOrder(Customer c, String productCode, Demo d, BigDecimal qty, int price, String location,
            String branch, LocalDate day) {
        return (String) orders.create(in(DeliveryOrderInput.class, "partnerId", c.id(), "siteId", c.siteId(),
                "locationId", location, "requestedDate", day.toString(), "windowFrom", "07:00", "windowTo", "17:00",
                "branchId", branch, "lines", List.of(Obj.of("productId", d.products.get(productCode), "quantity", qty,
                        "unitPrice", price)))).get("id");
    }

    /** Ticket for the trip, empty then loaded; one in twelve loaded weights is keyed by hand. Feeds the trip. */
    private String weigh(String station, String device, String trip, long tareKg, long grossKg, boolean manual) {
        String ticket = (String) tickets.create(in(TicketInput.class, "stationId", station, "subjectType",
                "sale_delivery", "tripId", trip)).get("id");
        stations.setSimulatorWeight(device, new SimulatorWeight(BigDecimal.valueOf(tareKg), null, null));
        tickets.firstWeigh(ticket, new WeighInput(device, null, null, null, null));
        if (manual) {
            tickets.secondWeigh(ticket, new WeighInput(null, BigDecimal.valueOf(grossKg),
                    "Indicator unstable, weight read from the display", null, null));
        } else {
            stations.setSimulatorWeight(device, new SimulatorWeight(BigDecimal.valueOf(grossKg), null, null));
            tickets.secondWeigh(ticket, new WeighInput(device, null, null, null, null));
        }
        return ticket;
    }

    /** Dispatch at {@code out} → proof of delivery → close, then the times on fleet's own trip and ticket rows. */
    private void finishTrip(String trip, String ticket, BigDecimal startOdo, BigDecimal endOdo, BigDecimal km,
            Instant out) {
        Map<String, Object> d = dispatch.dispatchAt(trip, startOdo, out);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) d.get("lines");
        List<Map<String, Object>> pod = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            pod.add(Obj.of("loadLineId", l.get("id"), "deliveredQty", SqlRows.dec(l, "dispatchedQty")));
        }
        dispatch.proofOfDelivery(trip, in(PodInput.class, "receiverName", "Site storekeeper", "receiverPhone",
                "+256700300400", "receivedAt", out.plus(Duration.ofHours(2)).toString(), "lines", pod, "endOdometer",
                endOdo));
        dispatch.close(trip, in(CloseTripInput.class, "distanceKm", km));
        Instant first = out.minus(Duration.ofMinutes(40));
        Instant second = out.minus(Duration.ofMinutes(20));
        db.jdbc().sql("UPDATE \"FleetLoad\" SET \"weighedAt\" = ?, \"departedAt\" = ?, \"closedAt\" = ? WHERE id = ?"
                + " AND \"organizationId\" = ?").params(SqlRows.ts(second),
                        SqlRows.ts(out.plus(Duration.ofMinutes(10))), SqlRows.ts(out.plus(Duration.ofHours(3))), trip,
                        FleetDb.org()).update();
        db.jdbc().sql("UPDATE \"WeighbridgeTicket\" SET \"firstWeighedAt\" = ?, \"secondWeighedAt\" = ?,"
                + " \"completedAt\" = ?, \"createdAt\" = ? WHERE id = ? AND \"organizationId\" = ?").params(
                        SqlRows.ts(first), SqlRows.ts(second), SqlRows.ts(second), SqlRows.ts(first), ticket,
                        FleetDb.org()).update();
    }

    /** Invoice what was delivered; an order weighed short of its quantity is closed first, as a dispatcher would. */
    private void invoice(Demo d, String order, String partner, LocalDate day, String branch) {
        if (!"delivered".equals(orders.require(order, false).get("status"))) {
            orders.close(order, new CloseOrderInput("Weighed short of the order; the remainder is not delivered"));
        }
        Map<String, Object> out = deliveryInvoices.invoice(order, new InvoiceRequest(day, true));
        @SuppressWarnings("unchecked")
        Map<String, Object> inv = (Map<String, Object>) out.get("invoice");
        String id = (String) inv.get("id");
        invoices.post(id);
        d.billed.add(new Billed(partner, id, Money.dec(inv.get("totalAmount")), day, branch));
    }

    private static String firstTrip(Map<String, Object> plan) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) plan.get("createdTripIds");
        return ids.getFirst();
    }

    private void fuel(Demo d, int u, LocalDate day, int back) {
        Unit unit = d.units.get(u);
        Instant when = at(day, 18);
        if (unit.truck()) {
            d.meters[u] = d.meters[u].add(new BigDecimal("15"));
            fuelLogs.create(in(FuelLogInput.class, "vehicleId", unit.id(), "fuelledAt", when.toString(), "source",
                    "station", "litres", 160 + (back + u) % 40, "unitPrice", 5200, "fullTank", true, "odometer",
                    d.meters[u], "fuelType", "diesel", "stationName", "Total Energies Namanve", "paymentMethod",
                    "fuel_card", "fuelCardId", d.card, "receiptNumber", "TE-" + unit.code() + "-" + day, "post", true));
        } else {
            d.meters[u] = d.meters[u].add(new BigDecimal("40"));
            fuelLogs.create(in(FuelLogInput.class, "vehicleId", unit.id(), "fuelledAt", when.toString(), "source",
                    "station", "litres", 220, "unitPrice", 5200, "fullTank", true, "hours", d.meters[u], "fuelType",
                    "diesel", "stationName", "Quarry bowser delivery", "paymentMethod", "fuel_card", "fuelCardId",
                    d.card, "receiptNumber", "TE-" + unit.code() + "-" + day, "post", true));
        }
    }

    /** A service, or every fourth job a breakdown: oil and a filter from the yard store, three hours of labour. */
    private void workshop(Demo d, int u, LocalDate day) {
        Unit unit = d.units.get(u);
        boolean breakdown = d.jobs++ % 4 == 3;
        String wo = (String) workOrders.create(in(WorkOrderInput.class, "vehicleId", unit.id(), "type",
                breakdown ? "breakdown" : "preventive", "priority", breakdown ? "high" : "normal", "title",
                breakdown ? "Hydraulic hose failure" : "Scheduled service")).get("id");
        if ("draft".equals(workOrders.get(wo).get("status"))) {
            workOrders.open(wo);
        }
        workOrders.start(wo);
        Instant from = at(day, 19);
        workOrderCosts.issuePartAt(wo, in(PartIssueInput.class, "productId", d.oil, "locationId", d.yard, "quantity",
                20, "description", "Engine oil change"), from);
        workOrderCosts.issuePartAt(wo, in(PartIssueInput.class, "productId", d.filter, "locationId", d.yard,
                "quantity", 1, "description", "Oil filter"), from);
        workOrderCosts.addLabour(wo, in(LabourInput.class, "employeeId", d.mechanics[d.jobs % 2], "hours", 3, "rate",
                15000, "workDate", day.toString(), "description", "Service and greasing"));
        workOrders.complete(wo, in(CompleteInput.class, "resolution", breakdown ? "Hose replaced"
                : "Oil and filter changed"));
        Instant to = from.plus(Duration.ofHours(3));
        db.jdbc().sql("UPDATE \"FleetWorkOrder\" SET \"reportedAt\" = ?, \"openedAt\" = ?, \"startedAt\" = ?,"
                + " \"completedAt\" = ? WHERE id = ? AND \"organizationId\" = ?").params(SqlRows.ts(from),
                        SqlRows.ts(from), SqlRows.ts(from), SqlRows.ts(to), wo, FleetDb.org()).update();
        db.jdbc().sql("UPDATE \"FleetDowntime\" SET \"startedAt\" = ?, \"endedAt\" = ? WHERE \"workOrderId\" = ? AND"
                + " \"organizationId\" = ?").params(SqlRows.ts(from), SqlRows.ts(to), wo, FleetDb.org()).update();
    }

    // ------------------------------------------------------------------ after the days

    /** Invoices older than a month: four in five paid by bank three weeks after the invoice, the fifth half paid. */
    private void collect(Demo d, LocalDate today) {
        int n = 0;
        for (Billed b : d.billed) {
            if (b.day().isAfter(today.minusDays(30))) {
                continue;
            }
            BigDecimal amount = n++ % 5 == 4 ? b.amount().divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP) : b.amount();
            payments.createReceipt(new PaymentInput(b.partnerId(), b.day().plusDays(21).toString(), amount, "bank",
                    null, "Bank transfer", b.branchId(), List.of(new AllocationInput(b.invoiceId(), null, amount)),
                    null, null, null, null), true, false);
        }
    }

    /** The transporters' trips of each 30 days on draft freight bills, ready to match their invoices. */
    private void bills(LocalDate today) {
        for (int from = DAYS; from > 0; from -= 30) {
            freightBills.generate(new GenerateFreightBills(null, today.minusDays(from), today.minusDays(from - 29L),
                    "Demo month"));
        }
    }

    /** Today's open delivery on the first truck: work waiting on the dispatch board and in the driver app. */
    private void liveWork(Demo d, LocalDate today) {
        Customer c = d.customers.getFirst();
        String order = deliveryOrder(c, "DM-AGG-20", d, new BigDecimal("20"), 45000, d.depot, d.depotBranch, today);
        dispatch.plan(order, in(PlanInput.class, "vehicleId", d.units.getFirst().id(), "driverId",
                d.drivers.getFirst(), "plannedDate", today.toString()));
    }
}
