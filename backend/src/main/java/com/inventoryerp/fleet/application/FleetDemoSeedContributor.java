package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.DispatchCommands.AssignInput;
import com.inventoryerp.fleet.application.DispatchCommands.ChargeRuleInput;
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
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.seed.SeedContributor;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opt-in construction-materials fleet demo ({@code SEED_FLEET_DEMO=true} with the {@code seed} profile, fleet 1.9):
 * three tipper trucks and a wheel loader, three drivers, two customer sites, a transporter with a rate card, a week of
 * deliveries (own trucks every day, the transporter every third day) with dispatch from stock, proof of delivery and
 * closing, one invoiced delivery order with its transport charge, fuel-card fuel logs, a completed loader service with
 * an oil change and labour, insurance premiums and the week's draft transporter freight bill. Master data comes from
 * the core / hr demo contributors ({@code FD-*} partners and products, {@code EMP-FD*} employees). Everything runs
 * through the module services, so stock and GL are posted by their engines and every entry is balanced. Off by
 * default, never part of the POS demo; skipped when the demo trucks already exist. Deterministic apart from dates
 * (the week ends today).
 */
@Component
public class FleetDemoSeedContributor implements SeedContributor {

    private static final Logger log = LoggerFactory.getLogger(FleetDemoSeedContributor.class);
    private static final String SIGNATURE = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADU"
            + "lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private final FleetDb db;
    private final JsonMapper mapper;
    private final TransactionTemplate tx;
    private final boolean enabled;
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
    private final StockService stock;

    public FleetDemoSeedContributor(FleetDb db, JsonMapper mapper, PlatformTransactionManager txm,
            @Value("${SEED_FLEET_DEMO:false}") boolean enabled, FleetCategoryService categories,
            FleetVehicleService vehicles, FleetDriverService drivers, FleetComplianceService compliance,
            FleetCustomerSiteService sites, FleetDispatchSettingsService dispatchSettings,
            FleetDeliveryOrderService orders, FleetDispatchService dispatch,
            FleetDeliveryInvoiceService deliveryInvoices, FleetFuelCardService fuelCards, FleetFuelLogService fuelLogs,
            FleetWorkOrderService workOrders, FleetWorkOrderCostService workOrderCosts,
            FleetFreightBillService freightBills, StockService stock) {
        this.db = db;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txm);
        this.enabled = enabled;
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
        this.stock = stock;
    }

    @Override
    public int order() {
        return 900;
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

    /**
     * Seed the fleet demo into {@code organizationId} (needs the core / hr demo master data and the organization's
     * accounts and MAIN warehouse). Returns what was created; {@code skipped} when the demo trucks exist.
     */
    public Map<String, Object> seedDemo(String organizationId) {
        String admin = db.jdbc().sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\""
                + " LIMIT 1").param(organizationId).query(String.class).optional().orElse(null);
        TenantStore store = TenantStore.user(organizationId, admin);
        return TenantContext.run(store, () -> {
            TenantContext.setPermissions(List.copyOf(Permissions.ALL));
            return tx.execute(s -> build(organizationId));
        });
    }

    private Map<String, Object> build(String org) {
        if (id("FleetVehicle", "code", "TRK-01", org) != null) {
            return Obj.of("skipped", true);
        }
        LocalDate today = LocalDate.now(BusinessZone.get());
        categories.ensureDefaults();
        String tipper = id("FleetVehicleCategory", "code", "TIPPER", org);
        String loaderClass = id("FleetVehicleCategory", "code", "WHEEL_LOADER", org);
        String yard = id("InventoryLocation", "code", "MAIN", org);
        String aggregate = id("Product", "code", "FD-AGG-20", org);
        String sand = id("Product", "code", "FD-SAND", org);
        String blocks = id("Product", "code", "FD-BLOCK-6", org);
        String oil = id("Product", "code", "FD-OIL-15W40", org);
        String builder = id("Partner", "code", "FD-CUST-01", org);
        String towers = id("Partner", "code", "FD-CUST-02", org);
        String haulier = id("Partner", "code", "FD-TRAN-01", org);
        if (tipper == null || yard == null || aggregate == null || builder == null || haulier == null) {
            log.warn("fleet demo skipped: run the core / hr / inventory demo contributors first");
            return Obj.of("skipped", true);
        }

        // Opening stock of the quarry yard (inventory's receipt posting) and the planning factors.
        stock.receive(StockMove.of(aggregate, yard, new BigDecimal("2000")).unitCost(new BigDecimal("18000")));
        stock.receive(StockMove.of(sand, yard, new BigDecimal("1000")).unitCost(new BigDecimal("12000")));
        stock.receive(StockMove.of(blocks, yard, new BigDecimal("20000")).unitCost(new BigDecimal("1500")));
        stock.receive(StockMove.of(oil, yard, new BigDecimal("200")).unitCost(new BigDecimal("9000")));
        dispatchSettings.putLoadFactor(aggregate, in(LoadFactorInput.class, "tonnesPerUnit", 1, "notes", "Sold by t"));
        dispatchSettings.putLoadFactor(sand, in(LoadFactorInput.class, "tonnesPerUnit", 1, "notes", "Sold by t"));
        dispatchSettings.putLoadFactor(blocks, in(LoadFactorInput.class, "tonnesPerUnit", "0.018",
                "cubicMetresPerUnit", "0.0144", "notes", "6 inch hollow block"));

        // Units, drivers, documents.
        List<String> trucks = new ArrayList<>();
        BigDecimal[] odometer = {new BigDecimal("42000"), new BigDecimal("58500"), new BigDecimal("31200")};
        for (int i = 0; i < 3; i++) {
            trucks.add((String) vehicles.create(in(VehicleInput.class, "code", "TRK-0" + (i + 1), "name",
                    "Tipper " + (i + 1), "registrationPlate", "UBQ 10" + (i + 1) + "K", "make", "Sinotruk", "model",
                    "HOWO 371", "year", 2022, "categoryId", tipper, "payloadCapacityT", 20, "bodyVolumeM3", 16,
                    "axleConfig", "6x4", "fuelType", "diesel", "tankCapacityL", 400, "openingOdometer",
                    odometer[i], "homeLocationId", yard)).get("id"));
        }
        String loader = (String) vehicles.create(in(VehicleInput.class, "code", "LDR-01", "name", "Wheel loader",
                "make", "Caterpillar", "model", "950GC", "year", 2021, "categoryId", loaderClass, "fuelType", "diesel",
                "tankCapacityL", 300, "openingHours", 3500, "homeLocationId", yard)).get("id");
        for (int i = 0; i < 3; i++) {
            compliance.create(trucks.get(i), in(ComplianceInput.class, "docType", "insurance", "documentNumber",
                    "POL-2026-00" + (i + 1), "issuer", "Jubilee Insurance", "issuedOn", today.minusMonths(3).toString(),
                    "expiresOn", today.minusMonths(3).plusYears(1).minusDays(1).toString(), "costAmount", 4_200_000));
            compliance.create(trucks.get(i), in(ComplianceInput.class, "docType", "road_licence", "documentNumber",
                    "RL-" + (i + 1), "issuedOn", today.minusMonths(11).toString(), "expiresOn",
                    today.plusDays(10 + 20L * i).toString(), "costAmount", 350_000));
        }
        List<String> driverIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String emp = id("HrEmployee", "employeeCode", "EMP-FD0" + (i + 1), org);
            driverIds.add((String) drivers.create(in(DriverInput.class, "employeeId", emp, "driverType", "driver",
                    "licenceNumber", "UG-DL-55" + (i + 1), "licenceClasses", List.of("CM", "CH"), "licenceIssuer",
                    "Ministry of Works", "licenceExpiresOn", today.plusYears(2).plusDays(25L * i).toString(),
                    "qualifiedCategoryIds", List.of(tipper))).get("id"));
        }
        String mechanic = id("HrEmployee", "employeeCode", "EMP-FD05", org);

        // Sites, transporter rate, transport charge.
        String siteA = (String) sites.create(in(SiteInput.class, "partnerId", builder, "name", "Kololo apartments",
                "address", "Plot 12 Kololo Hill Drive", "city", "Kampala", "latitude", "0.3376", "longitude",
                "32.5940", "contactName", "Site engineer", "contactPhone", "+256700100200", "zone", "Kampala Central",
                "distanceKm", 28)).get("id");
        String siteB = (String) sites.create(in(SiteInput.class, "partnerId", towers, "name", "Jinja Road towers",
                "address", "Jinja Road, Mukono", "city", "Mukono", "zone", "Mukono", "distanceKm", 46)).get("id");
        String rate = (String) dispatchSettings.createRate(in(TransporterRateInput.class, "partnerId", haulier,
                "name", "Haulage per tonne", "basis", "per_tonne", "rate", 3500)).get("id");
        dispatchSettings.createChargeRule(in(ChargeRuleInput.class, "name", "Delivery Kampala per tonne", "partnerId",
                builder, "basis", "per_tonne", "rate", 2500, "minAmount", 30000));

        // A week of deliveries: each own truck once a day, the transporter every third day.
        String[] products = {aggregate, sand, blocks};
        Object[] quantities = {20, 18, 1000};
        Object[] prices = {45000, 32000, 2500};
        int trips = 0;
        String firstOrder = null;
        for (int d = 6; d >= 0; d--) {
            LocalDate day = today.minusDays(d);
            for (int t = 0; t < 3; t++) {
                int p = (d + t) % 3;
                boolean toA = (d + t) % 2 == 0;
                String order = (String) orders.create(in(DeliveryOrderInput.class, "partnerId", toA ? builder
                        : towers, "siteId", toA ? siteA : siteB, "locationId", yard, "requestedDate", day.toString(),
                        "windowFrom", "07:00", "windowTo", "17:00", "lines", List.of(Obj.of("productId", products[p],
                                "quantity", quantities[p], "unitPrice", prices[p])))).get("id");
                if (firstOrder == null && toA) {
                    firstOrder = order;
                }
                String trip = firstTrip(dispatch.plan(order, in(PlanInput.class, "vehicleId", trucks.get(t),
                        "driverId", driverIds.get(t), "plannedDate", day.toString())));
                BigDecimal km = new BigDecimal(toA ? 28 : 46).multiply(BigDecimal.valueOf(2));
                BigDecimal start = odometer[t];
                odometer[t] = odometer[t].add(km);
                run(trip, start, odometer[t], km, day, 8 + t);
                trips++;
            }
            if (d % 3 == 0) {
                String order = (String) orders.create(in(DeliveryOrderInput.class, "partnerId", towers, "siteId",
                        siteB, "locationId", yard, "requestedDate", day.toString(), "lines", List.of(Obj.of(
                                "productId", aggregate, "quantity", 25, "unitPrice", 45000)))).get("id");
                String trip = firstTrip(dispatch.plan(order, in(PlanInput.class, "payloadT", 30, "plannedDate",
                        day.toString())));
                dispatch.assign(trip, in(AssignInput.class, "carrier", "transporter", "transporterPartnerId", haulier,
                        "transporterRateId", rate, "transporterPlate", "UAZ 455M", "transporterDriverName",
                        "Wasswa"));
                run(trip, null, null, null, day, 11);
                trips++;
            }
        }
        if (firstOrder != null) {
            deliveryInvoices.invoice(firstOrder, new InvoiceRequest(null, true));
        }

        // Fuel: one fuel card, a full tank per truck on two days of the week (odometer from the trips).
        String card = (String) fuelCards.create(in(FuelCardInput.class, "cardNumber", "7004 1200 0000 4417",
                "label", "Fleet card", "providerPartnerId", haulier, "status", "active", "expiresOn",
                today.plusYears(2).toString())).get("id");
        for (int t = 0; t < 3; t++) {
            for (int k = 0; k < 2; k++) {
                odometer[t] = odometer[t].add(new BigDecimal("15"));
                fuelLogs.create(in(FuelLogInput.class, "vehicleId", trucks.get(t), "source", "station", "litres",
                        k == 0 ? 180 : 165, "unitPrice", 5200, "fullTank", true, "odometer", odometer[t], "fuelType",
                        "diesel", "stationName", "Total Energies Namanve", "paymentMethod", "fuel_card", "fuelCardId",
                        card, "receiptNumber", "TE-" + (t + 1) + "-" + (k + 1), "post", true));
            }
        }

        // Workshop: the loader's 500-hour service — engine oil from the yard store and 3 h of labour.
        String wo = (String) workOrders.create(in(WorkOrderInput.class, "vehicleId", loader, "type", "preventive",
                "priority", "normal", "title", "500-hour service")).get("id");
        Map<String, Object> w = workOrders.get(wo);
        if ("draft".equals(w.get("status"))) {
            workOrders.open(wo);
        }
        workOrders.start(wo);
        workOrderCosts.issuePart(wo, in(PartIssueInput.class, "productId", oil, "locationId", yard, "quantity", 20,
                "description", "Engine oil change"));
        if (mechanic != null) {
            workOrderCosts.addLabour(wo, in(LabourInput.class, "employeeId", mechanic, "hours", 3, "rate", 15000,
                    "workDate", today.toString(), "description", "Service and greasing"));
        }
        workOrders.complete(wo, in(CompleteInput.class, "hours", 3512, "resolution", "Oil and filters changed"));

        // The week's transporter trips on a draft freight bill, ready to match the haulier's invoice.
        Map<String, Object> bills = freightBills.generate(new GenerateFreightBills(haulier, today.minusDays(7), today,
                "Demo week"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vehicles", 4);
        out.put("drivers", 3);
        out.put("trips", trips);
        out.put("workOrderId", wo);
        out.put("freightBills", bills.get("created"));
        log.info("fleet demo seeded in org {}: {} trips", org, trips);
        return out;
    }

    private static String firstTrip(Map<String, Object> plan) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) plan.get("createdTripIds");
        return ids.getFirst();
    }

    /** Dispatch → POD → close one trip, then date its lifecycle on {@code day} (fleet's own trip row). */
    private void run(String trip, BigDecimal startOdo, BigDecimal endOdo, BigDecimal km, LocalDate day, int hour) {
        Map<String, Object> d = dispatch.dispatch(trip, startOdo);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) d.get("lines");
        List<Map<String, Object>> pod = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            pod.add(Obj.of("loadLineId", l.get("id"), "deliveredQty", SqlRows.dec(l, "dispatchedQty")));
        }
        dispatch.proofOfDelivery(trip, in(PodInput.class, "receiverName", "Site storekeeper", "receiverPhone",
                "+256700300400", "signature", SIGNATURE, "lines", pod, "endOdometer", endOdo));
        dispatch.close(trip, in(CloseTripInput.class, "distanceKm", km));
        ZonedDateTime at = day.atTime(hour, 0).atZone(BusinessZone.get());
        db.jdbc().sql("UPDATE \"FleetLoad\" SET \"plannedDate\" = ?, \"dispatchedAt\" = ?, \"departedAt\" = ?,"
                + " \"deliveredAt\" = ?, \"closedAt\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(java.sql.Date.valueOf(day), SqlRows.ts(at.toInstant()), SqlRows.ts(at.plusMinutes(20)
                        .toInstant()), SqlRows.ts(at.plusHours(2).toInstant()), SqlRows.ts(at.plusHours(3)
                        .toInstant()), trip, FleetDb.org()).update();
    }
}
