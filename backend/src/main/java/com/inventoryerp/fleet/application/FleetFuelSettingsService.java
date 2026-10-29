package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FuelCommands.BandInput;
import com.inventoryerp.fleet.application.FuelCommands.SettingsInput;
import com.inventoryerp.fleet.application.FuelEfficiencyPolicy.Band;
import com.inventoryerp.fleet.domain.FleetFuelBand;
import com.inventoryerp.fleet.domain.FleetFuelSettings;
import com.inventoryerp.fleet.domain.FleetVehicleCategory;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fuel thresholds of an organization (fleet 1.5): the overfill tolerance, the dip-variance approval thresholds and the
 * expected consumption band per vehicle class. Created with recommended defaults on first use; the construction
 * classes seeded by 1.1 get default bands (owner-reviewable, docs/DECISIONS_FOR_OWNER.md).
 */
@Service
public class FleetFuelSettingsService {

    /** Default bands by system class code: min / max L/100 km, min / max L/h. */
    static final Map<String, BigDecimal[]> DEFAULT_BANDS = Map.of(
            "TIPPER", bounds("25", "60", null, null),
            "PRIME_MOVER", bounds("25", "55", null, null),
            "WATER_BOWSER", bounds("20", "55", null, null),
            "LIGHT_VEHICLE", bounds("5", "18", null, null),
            "EXCAVATOR", bounds(null, null, "8", "35"),
            "WHEEL_LOADER", bounds(null, null, "6", "30"),
            "DOZER", bounds(null, null, "10", "45"),
            "FORKLIFT", bounds(null, null, "1.5", "8"),
            "GENERATOR", bounds(null, null, "1", "60"));

    private final FleetDb db;
    private final AuditService audit;

    public FleetFuelSettingsService(FleetDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    private static BigDecimal[] bounds(String minKm, String maxKm, String minH, String maxH) {
        return new BigDecimal[] {dec(minKm), dec(maxKm), dec(minH), dec(maxH)};
    }

    private static BigDecimal dec(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    /** The organization's settings row (inserted with the defaults on first use). */
    @Transactional
    public Map<String, Object> current() {
        Map<String, Object> row = SqlRows.one(FleetFuelSettings.class, db.jdbc().sql("SELECT * FROM"
                + " \"FleetFuelSettings\" WHERE \"organizationId\" = ?").param(FleetDb.org()));
        if (row != null) {
            return row;
        }
        db.jdbc().sql("INSERT INTO \"FleetFuelSettings\" (id, \"organizationId\") VALUES (?, ?)"
                + " ON CONFLICT (\"organizationId\") DO NOTHING").params(UUID.randomUUID().toString(), FleetDb.org())
                .update();
        return SqlRows.one(FleetFuelSettings.class, db.jdbc().sql("SELECT * FROM \"FleetFuelSettings\" WHERE"
                + " \"organizationId\" = ?").param(FleetDb.org()));
    }

    /** Settings + one band row per active class (classes without a band have null bounds). */
    @Transactional
    public Map<String, Object> view() {
        Map<String, Object> s = current();
        ensureDefaultBands();
        List<Map<String, Object>> bands = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT c.id AS "categoryId", c.code, c.name, c.kind, c."defaultMeterType", b."minLPer100Km",
                       b."maxLPer100Km", b."minLPerHour", b."maxLPerHour", b."updatedAt"
                FROM "FleetVehicleCategory" c
                LEFT JOIN "FleetFuelBand" b ON b."categoryId" = c.id AND b."organizationId" = c."organizationId"
                WHERE c."organizationId" = ? AND c."isActive" = true ORDER BY c."sortOrder", c.code""")
                .param(FleetDb.org()))) {
            bands.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>(s);
        out.put("bands", bands);
        return out;
    }

    /** Update the org thresholds (null = unchanged). */
    @Transactional
    public Map<String, Object> update(SettingsInput in) {
        Map<String, Object> before = current();
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.overfillTolerancePct() != null) {
            data.put("overfillTolerancePct", in.overfillTolerancePct());
        }
        if (in.dipApprovalLitres() != null) {
            data.put("dipApprovalLitres", in.dipApprovalLitres());
        }
        if (in.dipApprovalPct() != null) {
            data.put("dipApprovalPct", in.dipApprovalPct());
        }
        if (!data.isEmpty()) {
            data.put("updatedBy", TenantContext.userId());
            Map<String, Object> after = db.update(FleetFuelSettings.class, (String) before.get("id"), data);
            audit.recordInTx(AuditInput.of("FleetFuelSettings", (String) before.get("id"), "update", before, after));
        }
        return view();
    }

    /** Replace the consumption band of class {@code categoryId}. */
    @Transactional
    public Map<String, Object> putBand(String categoryId, BandInput in) {
        Map<String, Object> category = db.require(FleetVehicleCategory.class, categoryId, false,
                "Vehicle class not found");
        assertOrdered(in.minLPer100Km(), in.maxLPer100Km(), "L/100 km");
        assertOrdered(in.minLPerHour(), in.maxLPerHour(), "L/h");
        Map<String, Object> existing = SqlRows.one(FleetFuelBand.class, db.jdbc().sql("SELECT * FROM \"FleetFuelBand\""
                + " WHERE \"organizationId\" = ? AND \"categoryId\" = ?").params(FleetDb.org(), categoryId));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("minLPer100Km", in.minLPer100Km());
        data.put("maxLPer100Km", in.maxLPer100Km());
        data.put("minLPerHour", in.minLPerHour());
        data.put("maxLPerHour", in.maxLPerHour());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after;
        if (existing == null) {
            Map<String, Object> ins = new LinkedHashMap<>(data);
            ins.put("categoryId", categoryId);
            after = db.insert(FleetFuelBand.class, ins);
        } else {
            after = db.update(FleetFuelBand.class, (String) existing.get("id"), data);
        }
        audit.recordInTx(AuditInput.of("FleetFuelBand", (String) after.get("id"), existing == null ? "create" : "update",
                existing, Obj.merge(after, Obj.of("categoryCode", category.get("code")))));
        return after;
    }

    /** The band of class {@code categoryId} ({@link Band#NONE} when none is configured). */
    public Band band(String categoryId) {
        ensureDefaultBands();
        Map<String, Object> b = SqlRows.one(FleetFuelBand.class, db.jdbc().sql("SELECT * FROM \"FleetFuelBand\" WHERE"
                + " \"organizationId\" = ? AND \"categoryId\" = ?").params(FleetDb.org(), categoryId));
        if (b == null) {
            return Band.NONE;
        }
        return new Band((BigDecimal) b.get("minLPer100Km"), (BigDecimal) b.get("maxLPer100Km"),
                (BigDecimal) b.get("minLPerHour"), (BigDecimal) b.get("maxLPerHour"));
    }

    /** Insert the default band of every system class that has none yet (a band once set is never re-seeded). */
    void ensureDefaultBands() {
        List<String> missing = db.jdbc().sql("SELECT c.code FROM \"FleetVehicleCategory\" c WHERE c.\"organizationId\""
                + " = ? AND c.\"isSystem\" = true AND c.code = ANY(?::text[]) AND NOT EXISTS (SELECT 1 FROM"
                + " \"FleetFuelBand\" b WHERE b.\"categoryId\" = c.id)")
                .params(FleetDb.org(), DEFAULT_BANDS.keySet().toArray(String[]::new)).query(String.class).list();
        for (String code : missing) {
            BigDecimal[] v = DEFAULT_BANDS.get(code);
            db.jdbc().sql("""
                    INSERT INTO "FleetFuelBand" (id, "organizationId", "categoryId", "minLPer100Km", "maxLPer100Km",
                                                 "minLPerHour", "maxLPerHour")
                    SELECT gen_random_uuid()::text, c."organizationId", c.id, ?::numeric, ?::numeric, ?::numeric,
                           ?::numeric
                    FROM "FleetVehicleCategory" c
                    WHERE c."organizationId" = ? AND c.code = ? AND c."isSystem" = true
                      AND NOT EXISTS (SELECT 1 FROM "FleetFuelBand" b WHERE b."categoryId" = c.id)
                    ON CONFLICT ("organizationId", "categoryId") DO NOTHING""")
                    .params(v[0], v[1], v[2], v[3], FleetDb.org(), code).update();
        }
    }

    private static void assertOrdered(BigDecimal min, BigDecimal max, String unit) {
        if (min != null && max != null && max.compareTo(min) < 0) {
            throw ApiException.badRequest("The maximum " + unit + " must not be below the minimum");
        }
    }
}
