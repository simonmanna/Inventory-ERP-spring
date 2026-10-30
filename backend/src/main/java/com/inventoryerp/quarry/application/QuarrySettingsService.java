package com.inventoryerp.quarry.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quarry.application.QuarryCommands.EquipmentRateInput;
import com.inventoryerp.quarry.application.QuarryCommands.RoyaltyRateInput;
import com.inventoryerp.quarry.application.QuarryCommands.SettingsInput;
import com.inventoryerp.quarry.domain.QuarryEquipmentRate;
import com.inventoryerp.quarry.domain.QuarryRoyaltyRate;
import com.inventoryerp.quarry.domain.QuarrySettings;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quarry org settings (allocation basis, fallback rates, explosive segregation of duties), mobile-plant hourly rates
 * (per vehicle or fleet vehicle class) and royalty / levy rates per tonne, plus the rate resolution the extraction
 * posting uses.
 */
@Service
public class QuarrySettingsService {

    private final QuarryDb db;
    private final AuditService audit;

    public QuarrySettingsService(QuarryDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ settings

    /** The org's settings row (created with the defaults on first use). */
    @Transactional
    public Map<String, Object> current() {
        Map<String, Object> row = SqlRows.one(QuarrySettings.class, db.jdbc().sql("SELECT * FROM \"QuarrySettings\""
                + " WHERE \"organizationId\" = ?").params(QuarryDb.org()));
        if (row != null) {
            return row;
        }
        db.jdbc().sql("INSERT INTO \"QuarrySettings\" (id, \"organizationId\") VALUES (gen_random_uuid()::text, ?)"
                + " ON CONFLICT (\"organizationId\") DO NOTHING").params(QuarryDb.org()).update();
        return SqlRows.one(QuarrySettings.class, db.jdbc().sql("SELECT * FROM \"QuarrySettings\" WHERE"
                + " \"organizationId\" = ?").params(QuarryDb.org()));
    }

    @Transactional
    public Map<String, Object> update(SettingsInput in) {
        Map<String, Object> before = current();
        Map<String, Object> d = new LinkedHashMap<>();
        if (in.allocationBasis() != null) {
            d.put("allocationBasis", in.allocationBasis());
        }
        if (in.fuelCostPerLitre() != null) {
            d.put("fuelCostPerLitre", in.fuelCostPerLitre());
        }
        if (in.labourRatePerHour() != null) {
            d.put("labourRatePerHour", in.labourRatePerHour());
        }
        if (in.equipmentRatePerHour() != null) {
            d.put("equipmentRatePerHour", in.equipmentRatePerHour());
        }
        if (in.distinctExplosiveIssuer() != null) {
            d.put("distinctExplosiveIssuer", in.distinctExplosiveIssuer());
        }
        d.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(QuarrySettings.class, (String) before.get("id"), d);
        audit.recordInTx(AuditInput.of("QuarrySettings", (String) before.get("id"), "update", before, after));
        return after;
    }

    // ------------------------------------------------------------------ equipment rates

    @Transactional(readOnly = true)
    public List<Map<String, Object>> equipmentRates() {
        return SqlRows.list(db.jdbc().sql("""
                SELECT r.*, c.code AS "categoryCode", c.name AS "categoryName", v.code AS "vehicleCode",
                       v."registrationPlate" AS "vehiclePlate"
                FROM "QuarryEquipmentRate" r
                LEFT JOIN "FleetVehicleCategory" c ON c.id = r."categoryId" AND c."organizationId" = r."organizationId"
                LEFT JOIN "FleetVehicle" v ON v.id = r."vehicleId" AND v."organizationId" = r."organizationId"
                WHERE r."organizationId" = ? ORDER BY c.code NULLS LAST, v.code""").params(QuarryDb.org()));
    }

    @Transactional
    public Map<String, Object> createEquipmentRate(EquipmentRateInput in) {
        String category = QuarryDb.text(in.categoryId());
        String vehicle = QuarryDb.text(in.vehicleId());
        if ((category == null) == (vehicle == null)) {
            throw ApiException.badRequest("Give either a vehicle class or a vehicle");
        }
        db.requireRef("FleetVehicleCategory", category, false, "Vehicle class not found");
        db.requireRef("FleetVehicle", vehicle, true, "Vehicle not found");
        long dup = db.jdbc().sql("SELECT count(*) FROM \"QuarryEquipmentRate\" WHERE \"organizationId\" = ? AND"
                + " (\"categoryId\" = ? OR \"vehicleId\" = ?)").params(QuarryDb.org(), category, vehicle)
                .query(Long.class).single();
        if (dup > 0) {
            throw ApiException.conflict("A rate for this " + (category != null ? "vehicle class" : "vehicle")
                    + " already exists; edit it instead");
        }
        Map<String, Object> row = db.insert(QuarryEquipmentRate.class, Obj.of("categoryId", category, "vehicleId",
                vehicle, "ratePerHour", in.ratePerHour(), "notes", QuarryDb.text(in.notes()), "updatedBy",
                TenantContext.userId()));
        audit.recordInTx(AuditInput.of("QuarryEquipmentRate", (String) row.get("id"), "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> updateEquipmentRate(String id, EquipmentRateInput in) {
        Map<String, Object> before = db.require(QuarryEquipmentRate.class, id, true, "Equipment rate not found");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ratePerHour", in.ratePerHour());
        d.put("notes", QuarryDb.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(QuarryEquipmentRate.class, id, d);
        audit.recordInTx(AuditInput.of("QuarryEquipmentRate", id, "update", before, after));
        return after;
    }

    @Transactional
    public void deleteEquipmentRate(String id) {
        Map<String, Object> before = db.require(QuarryEquipmentRate.class, id, true, "Equipment rate not found");
        db.jdbc().sql("DELETE FROM \"QuarryEquipmentRate\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, QuarryDb.org()).update();
        audit.recordInTx(AuditInput.of("QuarryEquipmentRate", id, "delete", before, null));
    }

    /** Hourly rate of a vehicle: its own rate, else its class rate, else the settings fallback. */
    BigDecimal equipmentRate(String vehicleId, String categoryId, Map<String, Object> settings) {
        List<BigDecimal> rates = db.jdbc().sql("SELECT \"ratePerHour\" FROM \"QuarryEquipmentRate\" WHERE"
                + " \"organizationId\" = ? AND (\"vehicleId\" = ? OR \"categoryId\" = ?) ORDER BY (\"vehicleId\" IS"
                + " NULL)").params(QuarryDb.org(), vehicleId, categoryId).query(BigDecimal.class).list();
        return rates.isEmpty() ? SqlRows.dec(settings, "equipmentRatePerHour") : rates.getFirst();
    }

    // ------------------------------------------------------------------ royalty / levy rates

    @Transactional(readOnly = true)
    public List<Map<String, Object>> royaltyRates() {
        return SqlRows.list(db.jdbc().sql("""
                SELECT r.*, s.code AS "siteCode", s.name AS "siteName", m.code AS "materialCode",
                       m.name AS "materialName", p.name AS "authorityName"
                FROM "QuarryRoyaltyRate" r
                LEFT JOIN "QuarrySite" s ON s.id = r."siteId"
                LEFT JOIN "QuarryMaterial" m ON m.id = r."materialId"
                LEFT JOIN "Partner" p ON p.id = r."authorityPartnerId" AND p."organizationId" = r."organizationId"
                WHERE r."organizationId" = ? AND r."deletedAt" IS NULL
                ORDER BY r."isActive" DESC, r.kind, r.name, r."effectiveFrom" DESC""").params(QuarryDb.org()));
    }

    @Transactional
    public Map<String, Object> createRoyaltyRate(RoyaltyRateInput in) {
        Map<String, Object> d = royaltyData(in);
        d.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(QuarryRoyaltyRate.class, d);
        audit.recordInTx(AuditInput.of("QuarryRoyaltyRate", (String) row.get("id"), "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> updateRoyaltyRate(String id, RoyaltyRateInput in) {
        Map<String, Object> before = db.require(QuarryRoyaltyRate.class, id, true, "Royalty rate not found");
        Map<String, Object> after = db.update(QuarryRoyaltyRate.class, id, royaltyData(in));
        audit.recordInTx(AuditInput.of("QuarryRoyaltyRate", id, "update", before, after));
        return after;
    }

    @Transactional
    public void deleteRoyaltyRate(String id) {
        Map<String, Object> before = db.require(QuarryRoyaltyRate.class, id, true, "Royalty rate not found");
        db.update(QuarryRoyaltyRate.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("QuarryRoyaltyRate", id, "delete", before, null));
    }

    private Map<String, Object> royaltyData(RoyaltyRateInput in) {
        if (in.effectiveTo() != null && in.effectiveTo().isBefore(in.effectiveFrom())) {
            throw ApiException.badRequest("The end date must be on or after the start date");
        }
        String site = QuarryDb.text(in.siteId());
        String material = QuarryDb.text(in.materialId());
        String partner = QuarryDb.text(in.authorityPartnerId());
        db.requireRef("QuarrySite", site, true, "Quarry site not found");
        db.requireRef("QuarryMaterial", material, true, "Material not found");
        db.requireRef("Partner", partner, true, "Authority (partner) not found");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", in.name().trim());
        d.put("kind", in.kind() == null ? "royalty" : in.kind());
        d.put("siteId", site);
        d.put("materialId", material);
        d.put("basis", in.basis() == null ? QuarryCostCalculator.NET : in.basis());
        d.put("ratePerTonne", in.ratePerTonne());
        d.put("authorityPartnerId", partner);
        d.put("effectiveFrom", in.effectiveFrom());
        d.put("effectiveTo", in.effectiveTo());
        d.put("isActive", in.isActive() == null || in.isActive());
        d.put("notes", QuarryDb.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }

    /** Active royalty / levy rates of a site + material on a day (blank site / material = all). */
    List<QuarryCostCalculator.Royalty> royaltiesFor(String siteId, String materialId, LocalDate day) {
        return SqlRows.list(db.jdbc().sql("""
                SELECT id, name, basis, "ratePerTonne", "authorityPartnerId" FROM "QuarryRoyaltyRate"
                WHERE "organizationId" = ? AND "deletedAt" IS NULL AND "isActive"
                  AND ("siteId" IS NULL OR "siteId" = ?) AND ("materialId" IS NULL OR "materialId" = ?)
                  AND "effectiveFrom" <= ? AND ("effectiveTo" IS NULL OR "effectiveTo" >= ?)
                ORDER BY kind, name""").params(QuarryDb.org(), siteId, materialId, java.sql.Date.valueOf(day),
                java.sql.Date.valueOf(day))).stream()
                .map(r -> new QuarryCostCalculator.Royalty((String) r.get("id"), (String) r.get("name"),
                        (String) r.get("basis"), SqlRows.dec(r, "ratePerTonne"), (String) r.get("authorityPartnerId")))
                .toList();
    }
}
