package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FleetCommands.CategoryInput;
import com.inventoryerp.fleet.application.FleetCommands.CategoryPatch;
import com.inventoryerp.fleet.domain.FleetVehicleCategory;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configurable vehicle / equipment classes (1.1). Every organization starts with the construction-materials defaults
 * (tipper, trailer, prime mover, water bowser, excavator, wheel loader, dozer, forklift, generator, light vehicle,
 * other), seeded on first use; administrators add, rename, deactivate or delete classes afterwards.
 */
@Service
public class FleetCategoryService {

    /** code, name, kind, default meter type. */
    static final List<String[]> DEFAULTS = List.of(
            new String[] {"TIPPER", "Tipper truck", "road_vehicle", "odometer"},
            new String[] {"TRAILER", "Trailer", "trailer", "none"},
            new String[] {"PRIME_MOVER", "Prime mover", "road_vehicle", "odometer"},
            new String[] {"WATER_BOWSER", "Water bowser", "road_vehicle", "odometer"},
            new String[] {"EXCAVATOR", "Excavator", "mobile_plant", "hours"},
            new String[] {"WHEEL_LOADER", "Wheel loader", "mobile_plant", "hours"},
            new String[] {"DOZER", "Dozer", "mobile_plant", "hours"},
            new String[] {"FORKLIFT", "Forklift", "mobile_plant", "hours"},
            new String[] {"GENERATOR", "Generator", "mobile_plant", "hours"},
            new String[] {"LIGHT_VEHICLE", "Light vehicle", "road_vehicle", "odometer"},
            new String[] {"OTHER", "Other", "other", "odometer"});

    /** CMMS-1: code, name, kind, default meter type of the fixed-plant classes. */
    static final List<String[]> FIXED_PLANT_DEFAULTS = List.of(
            new String[] {"CRUSHER", "Crusher", "fixed_plant", "hours"},
            new String[] {"CONVEYOR", "Conveyor", "fixed_plant", "hours"},
            new String[] {"SCREEN", "Vibrating screen", "fixed_plant", "hours"},
            new String[] {"BLOCK_MACHINE", "Block machine", "fixed_plant", "hours"},
            new String[] {"GENSET", "Fixed generator", "fixed_plant", "hours"});

    private final FleetDb db;
    private final AuditService audit;

    public FleetCategoryService(FleetDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    /** Seeds the default classes for the current organization when it has none (idempotent). */
    @Transactional
    public void ensureDefaults() {
        String org = FleetDb.org();
        long n = db.jdbc().sql("SELECT count(*) FROM \"FleetVehicleCategory\" WHERE \"organizationId\" = ?").param(org)
                .query(Long.class).single();
        if (n > 0) {
            return;
        }
        seed(org, DEFAULTS, 10);
    }

    /**
     * Seeds the fixed-plant classes for the current organization (CMMS-1). Unlike {@link #ensureDefaults} it runs for
     * organizations that already have classes, adds only the missing codes and never touches a renamed class.
     */
    @Transactional
    public void ensureFixedPlantClasses() {
        ensureDefaults();
        seed(FleetDb.org(), FIXED_PLANT_DEFAULTS, 200);
    }

    private void seed(String org, List<String[]> classes, int firstSort) {
        int sort = firstSort;
        for (String[] d : classes) {
            db.jdbc().sql("INSERT INTO \"FleetVehicleCategory\" (id, \"organizationId\", code, name, kind,"
                            + " \"defaultMeterType\", \"sortOrder\", \"isSystem\", \"isActive\", \"createdAt\", \"updatedAt\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, true, true, " + FleetDb.NOW + ", " + FleetDb.NOW + ")"
                            + " ON CONFLICT (\"organizationId\", code) DO NOTHING")
                    .params(UUID.randomUUID().toString(), org, d[0], d[1], d[2], d[3], sort).update();
            sort += 10;
        }
    }

    /** Classes in display order, each with its {@code vehicleCount}. */
    @Transactional
    public List<Map<String, Object>> list(boolean includeInactive) {
        ensureDefaults();
        List<Map<String, Object>> rows = db.list(FleetVehicleCategory.class,
                (includeInactive ? "" : " AND \"isActive\" = true") + " ORDER BY \"sortOrder\" ASC, name ASC");
        Map<String, Long> counts = new LinkedHashMap<>();
        db.jdbc().sql("SELECT \"categoryId\", count(*) AS n FROM \"FleetVehicle\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL GROUP BY \"categoryId\"").param(FleetDb.org())
                .query((rs, i) -> counts.put(rs.getString(1), rs.getLong(2))).list();
        for (Map<String, Object> r : rows) {
            r.put("vehicleCount", counts.getOrDefault((String) r.get("id"), 0L));
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> create(CategoryInput in) {
        ensureDefaults();
        String code = normalizeCode(in.code());
        assertCodeFree(code, null);
        Map<String, Object> row = db.insert(FleetVehicleCategory.class, Obj.of(
                "code", code,
                "name", in.name().trim(),
                "kind", in.kind() == null ? "road_vehicle" : in.kind(),
                "defaultMeterType", in.defaultMeterType() == null ? "odometer" : in.defaultMeterType(),
                "description", blankToNull(in.description()),
                "sortOrder", in.sortOrder() == null ? 0 : in.sortOrder(),
                "isSystem", false,
                "isActive", in.isActive() == null || in.isActive()));
        audit.recordInTx(AuditInput.of("FleetVehicleCategory", (String) row.get("id"), "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> update(String id, CategoryPatch in) {
        Map<String, Object> before = db.require(FleetVehicleCategory.class, id, true, "Vehicle class not found");
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.code() != null) {
            String code = normalizeCode(in.code());
            assertCodeFree(code, id);
            data.put("code", code);
        }
        if (in.name() != null) {
            data.put("name", in.name().trim());
        }
        if (in.kind() != null) {
            data.put("kind", in.kind());
        }
        if (in.defaultMeterType() != null) {
            data.put("defaultMeterType", in.defaultMeterType());
        }
        if (in.description() != null) {
            data.put("description", blankToNull(in.description()));
        }
        if (in.sortOrder() != null) {
            data.put("sortOrder", in.sortOrder());
        }
        if (in.isActive() != null) {
            data.put("isActive", in.isActive());
        }
        if (data.isEmpty()) {
            return before;
        }
        Map<String, Object> after = db.update(FleetVehicleCategory.class, id, data);
        audit.recordInTx(AuditInput.of("FleetVehicleCategory", id, "update", before, after));
        return after;
    }

    /** Hard delete of an unused class; a class in use must be deactivated instead. */
    @Transactional
    public void delete(String id) {
        Map<String, Object> row = db.require(FleetVehicleCategory.class, id, true, "Vehicle class not found");
        long used = db.jdbc().sql("SELECT count(*) FROM \"FleetVehicle\" WHERE \"organizationId\" = ? AND"
                + " \"categoryId\" = ?").params(FleetDb.org(), id).query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("Vehicle class " + row.get("code") + " is used by " + used
                    + " vehicle(s); deactivate it instead");
        }
        db.jdbc().sql("DELETE FROM \"FleetVehicleCategory\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, FleetDb.org()).update();
        audit.recordInTx(AuditInput.of("FleetVehicleCategory", id, "delete", row, null));
    }

    /** An active class of the current organization, or 400. */
    public Map<String, Object> requireActive(String id) {
        Map<String, Object> row = db.byId(FleetVehicleCategory.class, id, false);
        if (row == null) {
            throw ApiException.badRequest("Vehicle class not found");
        }
        if (!Boolean.TRUE.equals(row.get("isActive"))) {
            throw ApiException.badRequest("Vehicle class " + row.get("code") + " is inactive");
        }
        return row;
    }

    private void assertCodeFree(String code, String exceptId) {
        long n = db.jdbc().sql("SELECT count(*) FROM \"FleetVehicleCategory\" WHERE \"organizationId\" = ? AND code = ?"
                + (exceptId == null ? "" : " AND id <> ?"))
                .params(exceptId == null ? List.of(FleetDb.org(), code) : List.of(FleetDb.org(), code, exceptId))
                .query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict("A vehicle class with code " + code + " already exists");
        }
    }

    static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
