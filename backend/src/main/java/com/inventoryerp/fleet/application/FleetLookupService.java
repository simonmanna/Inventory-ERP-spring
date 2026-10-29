package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read models for the fleet screens: the reference lists a vehicle form needs (classes, branches, sites, cost
 * centres — so a fleet user does not also need branch / cost-centre / asset permissions), asset and partner pickers,
 * and the fleet overview tiles.
 */
@Service
public class FleetLookupService {

    private final FleetDb db;
    private final FleetCategoryService categories;

    public FleetLookupService(FleetDb db, FleetCategoryService categories) {
        this.db = db;
        this.categories = categories;
    }

    /** Active classes, branches, inventory locations (sites / yards) and cost centres: id, code, name. */
    @Transactional
    public Map<String, Object> lookups() {
        String org = FleetDb.org();
        return Obj.of(
                "categories", categories.list(false),
                "branches", rows("SELECT id, code, name FROM \"Branch\" WHERE \"organizationId\" = ? AND"
                        + " \"deletedAt\" IS NULL AND \"isActive\" = true ORDER BY name", org),
                "locations", rows("SELECT id, code, name, type::text AS type FROM \"InventoryLocation\" WHERE"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isActive\" = true ORDER BY name", org),
                "costCenters", rows("SELECT id, code, name FROM \"CostCenter\" WHERE \"organizationId\" = ? AND"
                        + " \"deletedAt\" IS NULL AND \"isActive\" = true ORDER BY code", org));
    }

    /** Fixed assets matching {@code search} (code / name / serial), with the vehicle already linked to each. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> assets(String search) {
        String like = like(search);
        return rows("""
                SELECT a.id, a."assetCode", a.name, a."serialNumber", a.status::text AS status,
                       v.id AS "linkedVehicleId", v.code AS "linkedVehicleCode"
                FROM "Asset" a LEFT JOIN "FleetVehicle" v ON v."assetId" = a.id AND v."organizationId" = a."organizationId"
                    AND v."deletedAt" IS NULL
                WHERE a."organizationId" = ? AND a."deletedAt" IS NULL
                  AND (a."assetCode" ILIKE ? OR a.name ILIKE ? OR coalesce(a."serialNumber", '') ILIKE ?)
                ORDER BY a."assetCode" LIMIT 20""", FleetDb.org(), like, like, like);
    }

    /** Partners (lessors, hire companies, transporters) matching {@code search}. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> partners(String search) {
        String like = like(search);
        return rows("""
                SELECT id, code, name, "isSupplier", "isCustomer" FROM "Partner"
                WHERE "organizationId" = ? AND "deletedAt" IS NULL AND (name ILIKE ? OR code ILIKE ?)
                ORDER BY "isSupplier" DESC, name LIMIT 20""", FleetDb.org(), like, like);
    }

    /** Fleet landing tiles: vehicles by status and class, compliance attention, pending meter corrections. */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        String org = FleetDb.org();
        java.sql.Date today = java.sql.Date.valueOf(LocalDate.now(BusinessZone.get()));
        List<Map<String, Object>> byStatus = rows("SELECT status, count(*) AS count FROM \"FleetVehicle\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL GROUP BY status ORDER BY status", org);
        List<Map<String, Object>> byCategory = rows("""
                SELECT c.id, c.code, c.name, c.kind, count(v.id) AS count
                FROM "FleetVehicleCategory" c JOIN "FleetVehicle" v ON v."categoryId" = c.id AND v."deletedAt" IS NULL
                    AND v.status <> 'disposed'
                WHERE c."organizationId" = ? GROUP BY c.id, c.code, c.name, c.kind, c."sortOrder"
                ORDER BY c."sortOrder", c.name""", org);
        Map<String, Object> compliance = SqlRows.one(db.jdbc().sql("""
                SELECT count(*) FILTER (WHERE d."expiresOn" < ?) AS expired,
                       count(*) FILTER (WHERE d."expiresOn" >= ? AND d."expiresOn" <= ?::date + d."reminderLeadDays")
                           AS expiring
                FROM "FleetComplianceDocument" d JOIN "FleetVehicle" v ON v.id = d."vehicleId"
                WHERE d."organizationId" = ? AND d.status = 'active' AND d."deletedAt" IS NULL
                  AND v."deletedAt" IS NULL AND v.status <> 'disposed'""").params(today, today, today, org));
        long pending = db.jdbc().sql("SELECT count(*) FROM \"FleetMeterCorrection\" WHERE \"organizationId\" = ? AND"
                + " status = 'pending'").param(org).query(Long.class).single();
        long total = byStatus.stream().filter(r -> !"disposed".equals(r.get("status")))
                .mapToLong(r -> ((Number) r.get("count")).longValue()).sum();
        return Obj.of("total", total, "byStatus", byStatus, "byCategory", byCategory, "compliance", compliance,
                "pendingCorrections", pending);
    }

    private List<Map<String, Object>> rows(String sql, Object... params) {
        return SqlRows.list(db.jdbc().sql(sql).params(params));
    }

    private static String like(String search) {
        String s = search == null ? "" : search.trim().replace("%", "\\%").replace("_", "\\_");
        return "%" + s + "%";
    }
}
