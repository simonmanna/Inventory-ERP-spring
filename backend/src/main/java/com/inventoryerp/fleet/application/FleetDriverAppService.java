package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.DriverAppCommands.DriverFuelInput;
import com.inventoryerp.fleet.application.DriverAppCommands.DriverInspectionInput;
import com.inventoryerp.fleet.application.FuelCommands.FuelLogInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.InspectionInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Driver web app (DRIVER-1): the signed-in user's driver profile, the units it may use and the fuel log and pre-trip
 * check on them. A thin, driver-scoped layer over {@link FleetFuelLogService} and {@link FleetInspectionService}:
 * rules stay there; this class resolves the driver from {@code HrEmployee.userId} (as {@code mine=true} does for trips),
 * refuses any unit that is not the driver's, and keeps drivers to their own records. Fuel logs stay drafts here.
 */
@Service
public class FleetDriverAppService {

    /** Trip states in which a truck is on the driver's plate. */
    private static final String LIVE_TRIP = "('planned', 'loading', 'weighed', 'dispatched', 'in_transit')";

    private final FleetDb db;
    private final FleetFuelLogService fuelLogs;
    private final FleetFuelLookupService fuelLookups;
    private final FleetInspectionService inspections;
    private final FleetVehicleService vehicles;

    public FleetDriverAppService(FleetDb db, FleetFuelLogService fuelLogs, FleetFuelLookupService fuelLookups,
            FleetInspectionService inspections, FleetVehicleService vehicles) {
        this.db = db;
        this.fuelLogs = fuelLogs;
        this.fuelLookups = fuelLookups;
        this.inspections = inspections;
        this.vehicles = vehicles;
    }

    /** Home: who I am, my units with today's check and open defects, and how many trips are live. */
    @Transactional(readOnly = true)
    public Map<String, Object> home() {
        Map<String, Object> driver = driver();
        String driverId = (String) driver.get("id");
        String org = FleetDb.org();
        List<Map<String, Object>> units = units(driverId);
        long trips = db.jdbc().sql("SELECT count(*) FROM \"FleetLoad\" WHERE \"organizationId\" = ? AND \"driverId\" = ?"
                + " AND status IN " + LIVE_TRIP).params(org, driverId).query(Long.class).single();
        return Obj.of("driver", driver, "units", units, "liveTrips", trips);
    }

    /** Payment accounts, tanks, the cards of this unit or driver, and suppliers for the fuel form. */
    @Transactional(readOnly = true)
    public Map<String, Object> fuelOptions(String vehicleId) {
        Map<String, Object> driver = driver();
        assertMine((String) driver.get("id"), vehicleId);
        Map<String, Object> all = fuelLookups.lookups();
        List<Map<String, Object>> cards = new ArrayList<>();
        if (all.get("cards") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> c && (vehicleId.equals(c.get("vehicleId"))
                        || (c.get("vehicleId") == null && (c.get("employeeId") == null
                                || driver.get("employeeId").equals(c.get("employeeId")))))) {
                    cards.add(Obj.of("id", c.get("id"), "maskedNumber", c.get("maskedNumber"), "label",
                            c.get("label")));
                }
            }
        }
        // Names only: account balances and tank levels stay office data.
        return Obj.of("tanks", pick(all.get("tanks"), "id", "code", "name"), "cards", cards,
                "paymentAccounts", pick(all.get("paymentAccounts"), "id", "name"),
                "suppliers", pick(all.get("suppliers"), "id", "name"));
    }

    private static List<Map<String, Object>> pick(Object rows, String... keys) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String k : keys) {
                        row.put(k, m.get(k));
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** My last 30 fuel logs. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myFuelLogs() {
        Map<String, Object> driver = driver();
        return SqlRows.list(db.jdbc().sql("""
                SELECT f.id, f.code, f."fuelledAt", f.source, f.litres, f.amount, f.status, f."anomalyCount",
                       v.code AS "vehicleCode", v."registrationPlate" AS "vehiclePlate"
                FROM "FleetFuelLog" f JOIN "FleetVehicle" v ON v.id = f."vehicleId"
                WHERE f."organizationId" = ? AND f."employeeId" = ? AND f."deletedAt" IS NULL
                ORDER BY f."fuelledAt" DESC LIMIT 30""").params(FleetDb.org(), driver.get("employeeId")));
    }

    /** Records a draft fuel log for one of my units; the office posts it. */
    @Transactional
    public Map<String, Object> createFuelLog(DriverFuelInput in) {
        Map<String, Object> driver = driver();
        assertMine((String) driver.get("id"), in.vehicleId());
        Map<String, Object> log = fuelLogs.create(new FuelLogInput(in.vehicleId(), in.fuelledAt(), in.source(),
                in.litres(), in.unitPrice(), in.amount(), in.fullTank(), in.odometer(), in.hours(), null,
                in.supplierId(), in.stationName(), in.paymentMethod(), in.paymentAccountId(), in.fuelCardId(),
                in.tankId(), in.receiptNumber(), (String) driver.get("employeeId"), null, in.notes(), false));
        return Obj.of("id", log.get("id"), "code", log.get("code"), "status", log.get("status"));
    }

    /** Receipt photo for one of my own fuel logs. */
    @Transactional
    public Map<String, Object> addFuelReceipt(String logId, String filename, String contentType, byte[] bytes) {
        Map<String, Object> driver = driver();
        Map<String, Object> log = fuelLogs.require(logId, false);
        if (!driver.get("employeeId").equals(log.get("employeeId"))) {
            throw ApiException.forbidden("This fuel log is not yours");
        }
        Map<String, Object> out = fuelLogs.attachReceipt(logId, filename, contentType, bytes);
        return Obj.of("id", out.get("id"), "code", out.get("code"));
    }

    /** The pre-trip checklist of a unit: the class's own when there is one, else the general one. */
    @Transactional
    public Map<String, Object> preTrip(String vehicleId) {
        Map<String, Object> driver = driver();
        assertMine((String) driver.get("id"), vehicleId);
        Map<String, Object> v = vehicles.require(vehicleId, false);
        List<Map<String, Object>> templates = inspections.templates((String) v.get("categoryId"), "pre_trip", false);
        Map<String, Object> unit = Obj.of("id", v.get("id"), "code", v.get("code"), "name", v.get("name"),
                "registrationPlate", v.get("registrationPlate"), "meterType", v.get("meterType"),
                "currentOdometer", v.get("currentOdometer"), "currentHours", v.get("currentHours"));
        return Obj.of("unit", unit, "template", templates.isEmpty() ? null : templates.get(0));
    }

    /** Records my pre-trip check; failed items raise defects as for any inspection. */
    @Transactional
    public Map<String, Object> recordInspection(DriverInspectionInput in) {
        Map<String, Object> driver = driver();
        assertMine((String) driver.get("id"), in.vehicleId());
        Map<String, Object> out = inspections.record(new InspectionInput(in.vehicleId(), in.templateId(),
                (String) driver.get("employeeId"), in.inspectedAt(), in.odometer(), in.hours(), in.notes(),
                in.items()));
        return Obj.of("id", out.get("id"), "code", out.get("code"), "result", out.get("result"), "defectCount",
                out.get("defectCount"));
    }

    /** Photo for one of my own inspections. */
    @Transactional
    public Map<String, Object> addInspectionPhoto(String inspectionId, String itemCode, String filename,
            String contentType, byte[] bytes) {
        Map<String, Object> driver = driver();
        Map<String, Object> mine = SqlRows.one(db.jdbc().sql("SELECT id, \"inspectorEmployeeId\" FROM"
                + " \"FleetInspection\" WHERE id = ? AND \"organizationId\" = ?").params(inspectionId, FleetDb.org()));
        if (mine == null) {
            throw ApiException.notFound("Inspection not found");
        }
        if (!driver.get("employeeId").equals(mine.get("inspectorEmployeeId"))) {
            throw ApiException.forbidden("This inspection is not yours");
        }
        Map<String, Object> out = inspections.addPhoto(inspectionId, itemCode, filename, contentType, bytes);
        return Obj.of("id", out.get("id"), "code", out.get("code"));
    }

    // ------------------------------------------------------------------ scope

    /** The signed-in user's active driver profile; 403 for anyone else. */
    private Map<String, Object> driver() {
        Map<String, Object> d = SqlRows.one(db.jdbc().sql("""
                SELECT d.id, d.code, d."employeeId", d."driverType", d."licenceExpiresOn", d."medicalExpiresOn",
                       e."firstName", e."lastName"
                FROM "FleetDriver" d JOIN "HrEmployee" e ON e.id = d."employeeId"
                WHERE d."organizationId" = ? AND e."userId" = ? AND d."deletedAt" IS NULL AND d.status = 'active'
                LIMIT 1""").params(FleetDb.org(), TenantContext.userId()));
        if (d == null) {
            throw ApiException.forbidden("No active driver profile is linked to your user");
        }
        return d;
    }

    /** Units on an active assignment, an open check-out or a live trip of the driver, with today's check. */
    private List<Map<String, Object>> units(String driverId) {
        String org = FleetDb.org();
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(BusinessZone.get());
        Object dayStart = SqlRows.ts(today.atStartOfDay(BusinessZone.get()).toInstant());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT v.id, v.code, v.name, v."registrationPlate", v.make, v.model, v.status, v."meterType",
                       v."fuelType", v."currentOdometer", v."currentHours", c.name AS "categoryName",
                       (SELECT count(*) FROM "FleetDefect" x WHERE x."organizationId" = v."organizationId"
                          AND x."vehicleId" = v.id AND x.status IN ('open', 'in_repair')) AS "openDefects",
                       (SELECT i.result FROM "FleetInspection" i WHERE i."organizationId" = v."organizationId"
                          AND i."vehicleId" = v.id AND i.kind = 'pre_trip' AND i."inspectedAt" >= ?::timestamp
                          ORDER BY i."inspectedAt" DESC LIMIT 1) AS "preTripToday"
                FROM "FleetVehicle" v JOIN "FleetVehicleCategory" c ON c.id = v."categoryId"
                WHERE v."organizationId" = ? AND v."deletedAt" IS NULL AND v.status <> 'disposed'
                  AND v.id IN (""" + MINE_SQL + ") ORDER BY v.code").params(dayStart, org, org, driverId,
                SqlRows.ts(now), SqlRows.ts(now), org, driverId, org, driverId))) {
            out.add(new LinkedHashMap<>(r));
        }
        return out;
    }

    /** {@code ?} = org, driver, now, now, org, driver, org, driver. */
    private static final String MINE_SQL = """
            SELECT a."vehicleId" FROM "FleetAssignment" a WHERE a."organizationId" = ? AND a."driverId" = ?
              AND a.status = 'active' AND a."startsAt" <= ?::timestamp
              AND (a."endsAt" IS NULL OR a."endsAt" > ?::timestamp)
            UNION SELECT k."vehicleId" FROM "FleetCheckout" k WHERE k."organizationId" = ? AND k."driverId" = ?
              AND k.status = 'open'
            UNION SELECT t."vehicleId" FROM "FleetLoad" t WHERE t."organizationId" = ? AND t."driverId" = ?
              AND t."vehicleId" IS NOT NULL AND t.status IN """ + " " + LIVE_TRIP;

    private void assertMine(String driverId, String vehicleId) {
        String org = FleetDb.org();
        Instant now = Instant.now();
        long n = db.jdbc().sql("SELECT count(*) FROM (" + MINE_SQL + ") m WHERE m.\"vehicleId\" = ?")
                .params(org, driverId, SqlRows.ts(now), SqlRows.ts(now), org, driverId, org, driverId, vehicleId)
                .query(Long.class).single();
        if (n == 0) {
            throw ApiException.forbidden("This vehicle is not assigned to you");
        }
    }
}
