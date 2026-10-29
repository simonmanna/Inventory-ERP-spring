package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.domain.FleetVehicle;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.postgresql.util.PGobject;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The maintainable-unit seam of fleet maintenance (1.6). Every maintenance row keys on a unit id that today is a
 * {@link FleetVehicle} — trucks, mobile plant and, from Phase 4, fixed plant (crushers, conveyors, screens, block
 * machines, generators) registered with a category of kind {@code fixed_plant}. Schedules, work orders, downtime and
 * inspections only use what this class exposes (identity, class, cost centre, branch, lifetime meters, status hold /
 * release), so a later fixed-plant register only has to provide the same unit view.
 */
@Component
public class MaintainableUnits {

    private final FleetDb db;
    private final FleetVehicleService vehicles;

    public MaintainableUnits(FleetDb db, FleetVehicleService vehicles) {
        this.db = db;
        this.vehicles = vehicles;
    }

    /** The unit row (404 when unknown); refused when disposed if {@code live}. */
    public Map<String, Object> require(String unitId, boolean lock, boolean live) {
        Map<String, Object> v = vehicles.require(unitId, lock);
        if (live && VehicleStatusPolicy.DISPOSED.equals(v.get("status"))) {
            throw ApiException.badRequest("Vehicle " + v.get("code") + " is disposed");
        }
        return v;
    }

    /** Lifetime odometer (raw + offset), or null without readings. */
    public static BigDecimal lifetimeKm(Map<String, Object> v) {
        return v.get("currentOdometer") == null ? null
                : SqlRows.dec(v, "currentOdometer").add(SqlRows.dec(v, "odometerOffset"));
    }

    /** Lifetime engine hours (raw + offset), or null without readings. */
    public static BigDecimal lifetimeHours(Map<String, Object> v) {
        return v.get("currentHours") == null ? null
                : SqlRows.dec(v, "currentHours").add(SqlRows.dec(v, "hoursOffset"));
    }

    /** Moves the unit to {@code to} unless it is already there (source = maintenance). */
    public void hold(String unitId, String to, String reason, String sourceId) {
        Map<String, Object> v = vehicles.require(unitId, true);
        if (!to.equals(v.get("status")) && !VehicleStatusPolicy.DISPOSED.equals(v.get("status"))) {
            vehicles.changeStatus(unitId, to, reason, "maintenance", sourceId);
        }
    }

    /**
     * Back into service after maintenance / downtime: only when no work order of the unit is still in progress and
     * no downtime is still open; then {@code preferred} when it is active / idle, else active.
     */
    public void release(String unitId, String preferred, String reason, String sourceId) {
        Map<String, Object> v = vehicles.require(unitId, true);
        String status = (String) v.get("status");
        if (!VehicleStatusPolicy.IN_WORKSHOP.equals(status) && !VehicleStatusPolicy.BREAKDOWN.equals(status)) {
            return;
        }
        String org = FleetDb.org();
        long busy = db.jdbc().sql("SELECT (SELECT count(*) FROM \"FleetWorkOrder\" WHERE \"organizationId\" = ? AND"
                        + " \"vehicleId\" = ? AND status = 'in_progress') + (SELECT count(*) FROM \"FleetDowntime\""
                        + " WHERE \"organizationId\" = ? AND \"vehicleId\" = ? AND \"endedAt\" IS NULL)")
                .params(org, unitId, org, unitId).query(Long.class).single();
        if (busy > 0) {
            return;
        }
        String to = VehicleStatusPolicy.IDLE.equals(preferred) ? VehicleStatusPolicy.IDLE : VehicleStatusPolicy.ACTIVE;
        vehicles.changeStatus(unitId, to, reason, "maintenance", sourceId);
    }

    // ------------------------------------------------------------------ shared helpers of the maintenance services

    static LocalDate today() {
        return LocalDate.now(BusinessZone.get());
    }

    /** A {@code jsonb} parameter. */
    static PGobject jsonb(Object value) {
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(EntityJson.toJson(value));
            return pg;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A JSON array of strings (as read back from a {@code jsonb} column) → list. */
    static List<String> strings(Object json) {
        List<String> out = new ArrayList<>();
        if (json instanceof JsonNode n && n.isArray()) {
            n.forEach(x -> out.add(x.asString()));
        } else if (json instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }

    /** Field {@code name} of a JSON object node, or null. */
    static String text(JsonNode n, String name) {
        JsonNode v = n.get(name);
        return v == null || v.isNull() ? null : v.asString();
    }

    static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    static String like(String search) {
        return "%" + search.trim().replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
