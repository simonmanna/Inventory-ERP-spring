package com.inventoryerp.quarry.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Form lookups of the quarry screens (sites, zones, materials, stockpiles, magazines, explosives, mobile plant,
 * trucks, hr shifts, inventory locations, recent completed haulage tickets), employee / product / partner searches
 * and the dashboard overview. Read-only, organization-scoped.
 */
@Service
public class QuarryLookupService {

    private final QuarryDb db;

    public QuarryLookupService(QuarryDb db) {
        this.db = db;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> lookups() {
        String org = QuarryDb.org();
        return Obj.of(
                "sites", rows("SELECT id, code, name, status FROM \"QuarrySite\" WHERE \"organizationId\" = ? AND"
                        + " \"deletedAt\" IS NULL ORDER BY code", org),
                "zones", rows("SELECT id, \"siteId\", \"parentId\", kind, code, name, status, \"materialId\" FROM"
                        + " \"QuarryZone\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY code", org),
                "materials", rows("SELECT id, code, name, \"rockType\", grade, \"densityTPerM3\", \"productId\","
                        + " \"isActive\" FROM \"QuarryMaterial\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                        + " ORDER BY code", org),
                "stockpiles", rows("SELECT id, \"siteId\", code, name, kind, \"isDefault\", \"isActive\" FROM"
                        + " \"QuarryStockpile\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY code", org),
                "magazines", rows("SELECT id, \"siteId\", code, name, \"isActive\" FROM \"QuarryMagazine\" WHERE"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY code", org),
                "explosives", rows("SELECT x.id, x.kind, x.\"netExplosiveKg\", p.code, p.name FROM \"QuarryExplosive\" x"
                        + " JOIN \"Product\" p ON p.id = x.\"productId\" WHERE x.\"organizationId\" = ? AND x.\"deletedAt\""
                        + " IS NULL AND x.\"isActive\" ORDER BY p.name", org),
                "plant", rows("SELECT v.id, v.code, v.name, v.\"registrationPlate\", c.code AS \"classCode\", c.name AS"
                        + " \"className\", v.\"currentHours\" FROM \"FleetVehicle\" v JOIN \"FleetVehicleCategory\" c ON"
                        + " c.id = v.\"categoryId\" WHERE v.\"organizationId\" = ? AND v.\"deletedAt\" IS NULL AND"
                        + " v.status <> 'disposed' AND c.kind = 'mobile_plant' ORDER BY v.code", org),
                "trucks", rows("SELECT v.id, v.code, v.name, v.\"registrationPlate\", v.\"payloadCapacityT\" FROM"
                        + " \"FleetVehicle\" v JOIN \"FleetVehicleCategory\" c ON c.id = v.\"categoryId\" WHERE"
                        + " v.\"organizationId\" = ? AND v.\"deletedAt\" IS NULL AND v.status <> 'disposed' AND"
                        + " c.kind <> 'trailer' AND v.\"payloadCapacityT\" IS NOT NULL ORDER BY v.code", org),
                "vehicleClasses", rows("SELECT id, code, name, kind FROM \"FleetVehicleCategory\" WHERE"
                        + " \"organizationId\" = ? AND \"isActive\" ORDER BY code", org),
                "hrShifts", rows("SELECT id, code, name, \"startTime\", \"endTime\" FROM \"HrShift\" WHERE"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isActive\" ORDER BY code", org),
                "locations", rows("SELECT id, code, name, type::text AS type FROM \"InventoryLocation\" WHERE"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isActive\" ORDER BY code", org),
                "branches", rows("SELECT id, code, name FROM \"Branch\" WHERE \"organizationId\" = ? AND \"deletedAt\""
                        + " IS NULL ORDER BY code", org),
                "costCenters", rows("SELECT id, code, name FROM \"CostCenter\" WHERE \"organizationId\" = ? AND"
                        + " \"deletedAt\" IS NULL ORDER BY code", org),
                "shifts", List.of(QuarryCommands.SHIFTS));
    }

    /** Completed weighbridge tickets of internal haulage not yet used by quarry (newest first). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> tickets(String search) {
        String like = QuarryDb.blank(search) ? "%" : QuarryDb.like(search);
        return SqlRows.list(db.jdbc().sql("""
                SELECT t.id, t."ticketNumber", t."subjectType", t."netKg", t."completedAt", t."vehicleId",
                       t."registrationPlate", t."tripId"
                FROM "WeighbridgeTicket" t
                WHERE t."organizationId" = ? AND t.status = 'completed' AND t."subjectType" IN ('internal_haulage', 'other')
                  AND (t."ticketNumber" ILIKE ? OR COALESCE(t."registrationPlate", '') ILIKE ?)
                  AND NOT EXISTS (SELECT 1 FROM "QuarryHaulage" h WHERE h."weighbridgeTicketId" = t.id
                      AND h."deletedAt" IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM "QuarryExtractionLoad" x WHERE x."weighbridgeTicketId" = t.id AND x.active)
                ORDER BY t."completedAt" DESC LIMIT 30""").params(QuarryDb.org(), like, like));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> employees(String search) {
        String like = QuarryDb.blank(search) ? "%" : QuarryDb.like(search);
        return SqlRows.list(db.jdbc().sql("SELECT id, \"employeeCode\", trim(concat(\"firstName\", ' ', \"lastName\")) AS"
                + " name FROM \"HrEmployee\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND (\"employeeCode\""
                + " ILIKE ? OR \"firstName\" ILIKE ? OR \"lastName\" ILIKE ?) ORDER BY \"firstName\", \"lastName\" LIMIT 30")
                .params(QuarryDb.org(), like, like, like));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> products(String search) {
        String like = QuarryDb.blank(search) ? "%" : QuarryDb.like(search);
        return SqlRows.list(db.jdbc().sql("SELECT id, code, name, \"costingMethod\"::text AS \"costingMethod\" FROM"
                + " \"Product\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"trackInventory\" AND"
                + " (code ILIKE ? OR name ILIKE ?) ORDER BY name LIMIT 30").params(QuarryDb.org(), like, like));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> partners(String search) {
        String like = QuarryDb.blank(search) ? "%" : QuarryDb.like(search);
        return SqlRows.list(db.jdbc().sql("SELECT id, code, name FROM \"Partner\" WHERE \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL AND (code ILIKE ? OR name ILIKE ?) ORDER BY name LIMIT 30")
                .params(QuarryDb.org(), like, like));
    }

    /** Dashboard tiles: month-to-date tonnes / cost per tonne / losses, today, drafts, approvals, blasts, ROM stock. */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        String org = QuarryDb.org();
        LocalDate today = LocalDate.now(BusinessZone.get());
        Map<String, Object> month = SqlRows.one(db.jdbc().sql("SELECT COALESCE(SUM(\"netTonnes\"), 0) AS \"netTonnes\","
                + " COALESCE(SUM(\"totalCost\"), 0) AS \"totalCost\", COALESCE(SUM(\"lossTonnes\"), 0) AS \"lossTonnes\","
                + " COALESCE(SUM(\"grossTonnes\"), 0) AS \"grossTonnes\", count(*) AS logs FROM \"QuarryExtractionLog\""
                + " WHERE \"organizationId\" = ? AND status = 'posted' AND \"deletedAt\" IS NULL AND \"extractedOn\" >= ?"
                + " AND \"extractedOn\" <= ?").params(org, java.sql.Date.valueOf(today.withDayOfMonth(1)),
                java.sql.Date.valueOf(today)));
        BigDecimal net = SqlRows.dec(month, "netTonnes");
        month.put("costPerTonne", net.signum() > 0 ? SqlRows.dec(month, "totalCost").divide(net, 4, RoundingMode.HALF_UP)
                : null);
        BigDecimal todayTonnes = db.jdbc().sql("SELECT COALESCE(SUM(\"netTonnes\"), 0) FROM \"QuarryExtractionLog\" WHERE"
                + " \"organizationId\" = ? AND status = 'posted' AND \"deletedAt\" IS NULL AND \"extractedOn\" = ?")
                .params(org, java.sql.Date.valueOf(today)).query(BigDecimal.class).single();
        BigDecimal target = db.jdbc().sql("SELECT COALESCE(SUM(\"targetTonnes\"), 0) FROM \"QuarryExtractionPlan\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND status = 'active' AND \"periodStart\" <= ? AND"
                + " \"periodEnd\" >= ?").params(org, java.sql.Date.valueOf(today), java.sql.Date.valueOf(today))
                .query(BigDecimal.class).single();
        Map<String, Object> counts = SqlRows.one(db.jdbc().sql("""
                SELECT (SELECT count(*) FROM "QuarryExtractionLog" WHERE "organizationId" = ? AND status = 'draft'
                          AND "deletedAt" IS NULL) AS drafts,
                       (SELECT count(*) FROM "QuarryExplosiveIssue" WHERE "organizationId" = ?
                          AND status = 'pending_approval') AS "pendingExplosiveApprovals",
                       (SELECT count(*) FROM "QuarryExplosiveIssue" WHERE "organizationId" = ? AND status = 'approved')
                          AS "approvedToIssue",
                       (SELECT count(*) FROM "QuarryBlast" WHERE "organizationId" = ? AND status IN ('planned', 'fired')
                          AND "deletedAt" IS NULL) AS "openBlasts",
                       (SELECT COALESCE(SUM(si.quantity), 0) FROM "StockItem" si JOIN "QuarryStockpile" k
                          ON k."locationId" = si."locationId" AND k."organizationId" = si."organizationId"
                          WHERE si."organizationId" = ? AND k.kind = 'rom' AND k."deletedAt" IS NULL) AS "romTonnes",
                       (SELECT count(*) FROM "QuarryHaulage" h WHERE h."organizationId" = ? AND h."deletedAt" IS NULL
                          AND h."hauledOn" = ?) AS "haulsToday"
                """).params(org, org, org, org, org, org, java.sql.Date.valueOf(today)));
        return Obj.of("today", today, "month", month, "todayTonnes", todayTonnes, "activeTargetTonnes", target,
                "counts", counts);
    }

    private List<Map<String, Object>> rows(String sql, Object... params) {
        return SqlRows.list(db.jdbc().sql(sql).params(params));
    }
}
