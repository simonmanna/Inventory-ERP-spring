package com.inventoryerp.fleet.application;

import com.inventoryerp.expenses.application.ExpensesService;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pickers and the overview tiles of the fuel screens (fleet 1.5): fuel-able vehicles with their meters and tank size,
 * active tanks with level, active cards (masked), cash / bank accounts and suppliers for station purchases (from the
 * expenses module), inventory locations, cost centres, stocked products and hr employees.
 */
@Service
public class FleetFuelLookupService {

    private final FleetDb db;
    private final ExpensesService expenses;
    private final FleetFuelTankService tanks;

    public FleetFuelLookupService(FleetDb db, ExpensesService expenses, FleetFuelTankService tanks) {
        this.db = db;
        this.expenses = expenses;
        this.tanks = tanks;
    }

    /** Vehicles (not disposed), active tanks, active cards and payment accounts for the quick-entry form. */
    @Transactional(readOnly = true)
    public Map<String, Object> lookups() {
        String org = FleetDb.org();
        List<Map<String, Object>> vehicles = SqlRows.list(db.jdbc().sql("""
                SELECT v.id, v.code, v.name, v."registrationPlate", v."fuelType", v."tankCapacityL", v."meterType",
                       v."currentOdometer", v."currentHours", v."costCenterId", v.status, c.name AS "categoryName"
                FROM "FleetVehicle" v JOIN "FleetVehicleCategory" c ON c.id = v."categoryId"
                WHERE v."organizationId" = ? AND v."deletedAt" IS NULL AND v.status <> 'disposed'
                ORDER BY v.code""").param(org));
        List<Map<String, Object>> tankRows = new ArrayList<>();
        for (Map<String, Object> t : tanks.list(false)) {
            tankRows.add(Obj.of("id", t.get("id"), "code", t.get("code"), "name", t.get("name"), "kind",
                    t.get("kind"), "bookLitres", t.get("bookLitres"), "capacityL", t.get("capacityL"),
                    "product", t.get("product")));
        }
        List<Map<String, Object>> cards = SqlRows.list(db.jdbc().sql("SELECT id, \"maskedNumber\", label,"
                + " \"vehicleId\", \"employeeId\", \"providerPartnerId\" FROM \"FleetFuelCard\" WHERE \"organizationId\""
                + " = ? AND \"deletedAt\" IS NULL AND status = 'active' ORDER BY \"last4\"").param(org));
        List<Map<String, Object>> locations = SqlRows.list(db.jdbc().sql("SELECT id, code, name, type::text AS type"
                + " FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isActive\" ="
                + " true ORDER BY code").param(org));
        List<Map<String, Object>> costCenters = SqlRows.list(db.jdbc().sql("SELECT id, code, name FROM \"CostCenter\""
                + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isActive\" = true ORDER BY code")
                .param(org));
        return Obj.of("vehicles", vehicles, "tanks", tankRows, "cards", cards, "paymentAccounts",
                expenses.paymentAccounts(), "suppliers", expenses.suppliers(), "locations", locations,
                "costCenters", costCenters);
    }

    /** Stocked products matching {@code search} (code / name), at most 30 — the fuel product of a tank. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> products(String search) {
        String like = "%" + (search == null ? "" : search.trim().replace("%", "\\%").replace("_", "\\_")) + "%";
        return SqlRows.list(db.jdbc().sql("SELECT id, code, name, \"costingMethod\"::text AS \"costingMethod\" FROM"
                + " \"Product\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"trackInventory\" = true"
                + " AND (code ILIKE ? OR name ILIKE ?) ORDER BY name LIMIT 30").params(FleetDb.org(), like, like));
    }

    /** Active hr employees matching {@code search} (code / name), at most 30. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> employees(String search) {
        String like = "%" + (search == null ? "" : search.trim().replace("%", "\\%").replace("_", "\\_")) + "%";
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : SqlRows.list(db.jdbc().sql("SELECT id, \"employeeCode\", \"firstName\","
                + " \"lastName\" FROM \"HrEmployee\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND"
                + " \"isActive\" = true AND (\"employeeCode\" ILIKE ? OR \"firstName\" ILIKE ? OR \"lastName\" ILIKE ?"
                + " OR concat_ws(' ', \"firstName\", \"lastName\") ILIKE ?) ORDER BY \"firstName\", \"lastName\" LIMIT 30")
                .params(FleetDb.org(), like, like, like, like))) {
            out.add(Obj.of("id", e.get("id"), "employeeCode", e.get("employeeCode"), "name",
                    FleetFuelLogService.personName(e.get("firstName"), e.get("lastName"))));
        }
        return out;
    }

    /** Month-to-date litres and cost, drafts, open anomalies, tanks below reorder level. */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        String org = FleetDb.org();
        ZoneId zone = BusinessZone.get();
        LocalDate first = LocalDate.now(zone).withDayOfMonth(1);
        Map<String, Object> month = SqlRows.one(db.jdbc().sql("""
                SELECT COALESCE(SUM(litres), 0) AS litres, COALESCE(SUM(amount), 0) AS amount,
                       COALESCE(SUM(litres) FILTER (WHERE source = 'tank'), 0) AS "tankLitres",
                       COALESCE(SUM(litres) FILTER (WHERE source = 'station'), 0) AS "stationLitres", count(*) AS logs
                FROM "FleetFuelLog" WHERE "organizationId" = ? AND status = 'posted' AND "deletedAt" IS NULL
                  AND "fuelledAt" >= ?""").params(org, SqlRows.ts(first.atStartOfDay(zone).toInstant())));
        long drafts = db.jdbc().sql("SELECT count(*) FROM \"FleetFuelLog\" WHERE \"organizationId\" = ? AND status ="
                + " 'draft' AND \"deletedAt\" IS NULL").param(org).query(Long.class).single();
        long open = db.jdbc().sql("SELECT count(*) FROM \"FleetFuelAnomaly\" WHERE \"organizationId\" = ? AND status ="
                + " 'open'").param(org).query(Long.class).single();
        List<Map<String, Object>> all = tanks.list(false);
        long low = all.stream().filter(t -> Boolean.TRUE.equals(t.get("belowReorder"))).count();
        long pendingDips = db.jdbc().sql("SELECT count(*) FROM \"FleetTankDip\" WHERE \"organizationId\" = ? AND status"
                + " = 'pending_approval'").param(org).query(Long.class).single();
        return Obj.of("monthStart", first, "month", month, "drafts", drafts, "openAnomalies", open, "tanks",
                all.size(), "tanksBelowReorder", low, "pendingReconciliations", pendingDips);
    }
}
