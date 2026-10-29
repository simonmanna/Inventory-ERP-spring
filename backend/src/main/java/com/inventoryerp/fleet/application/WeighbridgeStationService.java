package com.inventoryerp.fleet.application;

import static com.inventoryerp.fleet.application.MaintainableUnits.jsonb;

import com.inventoryerp.fleet.application.WeighbridgeAdapter.DeviceHealth;
import com.inventoryerp.fleet.application.WeighbridgeAdapter.DeviceSpec;
import com.inventoryerp.fleet.application.WeighbridgeAdapter.StableWeight;
import com.inventoryerp.fleet.application.WeighbridgeCommands.DeviceInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.DevicePatch;
import com.inventoryerp.fleet.application.WeighbridgeCommands.ProductSettingInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.SimulatorWeight;
import com.inventoryerp.fleet.application.WeighbridgeCommands.StationInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.StationPatch;
import com.inventoryerp.fleet.domain.WeighbridgeDevice;
import com.inventoryerp.fleet.domain.WeighbridgeProductSetting;
import com.inventoryerp.fleet.domain.WeighbridgeStation;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Weighbridge configuration (fleet 1.4): stations per site / branch (tolerances, tare validity, plausible range),
 * indicator devices with their adapter type and connection / frame configuration, per-product weighing settings,
 * lookups, and device reads through the {@link WeighbridgeAdapter} SPI.
 *
 * <p>A device read ({@link #readDevice}) runs <b>outside</b> any transaction — it can wait up to the device timeout on
 * the indicator — and records the outcome on the device in its own short transaction.
 */
@Service
public class WeighbridgeStationService {

    static final List<String> SUBJECT_TYPES = List.of("sale_delivery", "purchase_receipt", "internal_haulage", "other");

    /** A stable device reading and the device row it came from. */
    public record DeviceReading(Map<String, Object> device, StableWeight weight) {}

    private final FleetDb db;
    private final WeighbridgeAdapters adapters;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public WeighbridgeStationService(FleetDb db, WeighbridgeAdapters adapters, AuditService audit,
            PlatformTransactionManager txm) {
        this.db = db;
        this.adapters = adapters;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    // ------------------------------------------------------------------ stations

    @Transactional(readOnly = true)
    public List<Map<String, Object>> stations(boolean includeInactive) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT s.*, b.name AS \"b_name\", l.code AS"
                + " \"l_code\", l.name AS \"l_name\", (SELECT count(*) FROM \"WeighbridgeDevice\" d WHERE"
                + " d.\"stationId\" = s.id AND d.\"isActive\") AS \"deviceCount\" FROM \"WeighbridgeStation\" s"
                + " LEFT JOIN \"Branch\" b ON b.id = s.\"branchId\" AND b.\"organizationId\" = s.\"organizationId\""
                + " LEFT JOIN \"InventoryLocation\" l ON l.id = s.\"locationId\" AND l.\"organizationId\" ="
                + " s.\"organizationId\" WHERE s.\"organizationId\" = ?" + (includeInactive ? "" : " AND s.\"isActive\"")
                + " ORDER BY s.\"isActive\" DESC, s.code").params(FleetDb.org()))) {
            out.add(stationShape(r));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> station(String id) {
        Map<String, Object> r = SqlRows.one(db.jdbc().sql("SELECT s.*, b.name AS \"b_name\", l.code AS \"l_code\","
                + " l.name AS \"l_name\", 0 AS \"deviceCount\" FROM \"WeighbridgeStation\" s LEFT JOIN \"Branch\" b ON"
                + " b.id = s.\"branchId\" AND b.\"organizationId\" = s.\"organizationId\" LEFT JOIN"
                + " \"InventoryLocation\" l ON l.id = s.\"locationId\" AND l.\"organizationId\" = s.\"organizationId\""
                + " WHERE s.id = ? AND s.\"organizationId\" = ?").params(id, FleetDb.org()));
        if (r == null) {
            throw ApiException.notFound("Weighbridge station not found");
        }
        Map<String, Object> s = stationShape(r);
        s.put("devices", devices(id, true));
        return s;
    }

    private static Map<String, Object> stationShape(Map<String, Object> r) {
        Map<String, Object> s = SqlRows.as(WeighbridgeStation.class, r);
        s.put("branch", r.get("branchId") == null ? null : Obj.of("id", r.get("branchId"), "name", r.get("b_name")));
        s.put("location", r.get("locationId") == null ? null : Obj.of("id", r.get("locationId"), "code",
                r.get("l_code"), "name", r.get("l_name")));
        s.put("deviceCount", ((Number) r.get("deviceCount")).intValue());
        return s;
    }

    /** The station row (tenant-scoped), 404 when missing; {@code active} requires it to be active. */
    Map<String, Object> requireStation(String id, boolean active) {
        Map<String, Object> s = db.require(WeighbridgeStation.class, id, false, "Weighbridge station not found");
        if (active && !Boolean.TRUE.equals(s.get("isActive"))) {
            throw ApiException.badRequest("Weighbridge station " + s.get("code") + " is inactive");
        }
        return s;
    }

    @Transactional
    public Map<String, Object> createStation(StationInput in) {
        String code = in.code().trim().toUpperCase(Locale.ROOT);
        assertStationCodeFree(null, code);
        assertRefs(in.branchId(), in.locationId());
        BigDecimal min = in.minWeightKg() == null ? new BigDecimal("200") : in.minWeightKg();
        BigDecimal max = in.maxWeightKg() == null ? new BigDecimal("100000") : in.maxWeightKg();
        assertRange(min, max);
        Map<String, Object> row = db.insert(WeighbridgeStation.class, Obj.of(
                "code", code,
                "name", in.name().trim(),
                "branchId", FleetIncidentService.blank(in.branchId()),
                "locationId", FleetIncidentService.blank(in.locationId()),
                "tolerancePct", in.tolerancePct() == null ? new BigDecimal("0.5") : in.tolerancePct(),
                "toleranceKg", in.toleranceKg() == null ? new BigDecimal("20") : in.toleranceKg(),
                "tareValidityDays", in.tareValidityDays() == null ? 30 : in.tareValidityDays(),
                "minWeightKg", min,
                "maxWeightKg", max,
                "isActive", true,
                "notes", FleetVehicleService.trim(in.notes()),
                "createdBy", TenantContext.userId(),
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("WeighbridgeStation", (String) row.get("id"), "create", null, row));
        return station((String) row.get("id"));
    }

    @Transactional
    public Map<String, Object> updateStation(String id, StationPatch in) {
        Map<String, Object> before = db.require(WeighbridgeStation.class, id, true, "Weighbridge station not found");
        List<String> clear = clearList(in.clear(), WeighbridgeCommands.STATION_CLEARABLE);
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.code() != null) {
            String code = in.code().trim().toUpperCase(Locale.ROOT);
            assertStationCodeFree(id, code);
            data.put("code", code);
        }
        FleetIncidentService.put(data, "name", FleetVehicleService.trim(in.name()));
        assertRefs(in.branchId(), in.locationId());
        FleetIncidentService.put(data, "branchId", FleetIncidentService.blank(in.branchId()));
        FleetIncidentService.put(data, "locationId", FleetIncidentService.blank(in.locationId()));
        FleetIncidentService.put(data, "tolerancePct", in.tolerancePct());
        FleetIncidentService.put(data, "toleranceKg", in.toleranceKg());
        FleetIncidentService.put(data, "tareValidityDays", in.tareValidityDays());
        FleetIncidentService.put(data, "minWeightKg", in.minWeightKg());
        FleetIncidentService.put(data, "maxWeightKg", in.maxWeightKg());
        FleetIncidentService.put(data, "isActive", in.isActive());
        FleetIncidentService.put(data, "notes", FleetVehicleService.trim(in.notes()));
        for (String f : clear) {
            data.put(f, null);
        }
        if (data.isEmpty()) {
            return station(id);
        }
        assertRange(data.containsKey("minWeightKg") ? (BigDecimal) data.get("minWeightKg")
                : SqlRows.dec(before, "minWeightKg"), data.containsKey("maxWeightKg") ? (BigDecimal) data.get(
                        "maxWeightKg") : SqlRows.dec(before, "maxWeightKg"));
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(WeighbridgeStation.class, id, data);
        audit.recordInTx(AuditInput.of("WeighbridgeStation", id, "update", before, after));
        return station(id);
    }

    private void assertStationCodeFree(String id, String code) {
        long n = db.jdbc().sql("SELECT count(*) FROM \"WeighbridgeStation\" WHERE \"organizationId\" = ? AND code = ?"
                + (id == null ? "" : " AND id <> ?")).params(id == null ? List.of(FleetDb.org(), code)
                        : List.of(FleetDb.org(), code, id)).query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict("A weighbridge station with code " + code + " already exists");
        }
    }

    private void assertRefs(String branchId, String locationId) {
        if (FleetVehicleService.notBlank(branchId) && !db.exists("Branch", branchId, true)) {
            throw ApiException.badRequest("Branch not found");
        }
        if (FleetVehicleService.notBlank(locationId) && !db.exists("InventoryLocation", locationId, true)) {
            throw ApiException.badRequest("Inventory location not found");
        }
    }

    private static void assertRange(BigDecimal min, BigDecimal max) {
        if (max.compareTo(min) <= 0) {
            throw ApiException.badRequest("The maximum weight must be greater than the minimum weight");
        }
    }

    // ------------------------------------------------------------------ devices

    @Transactional(readOnly = true)
    public List<Map<String, Object>> devices(String stationId, boolean includeInactive) {
        StringBuilder where = new StringBuilder(" WHERE d.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (!includeInactive) {
            where.append(" AND d.\"isActive\"");
        }
        FleetIncidentService.eq(where, params, "d.\"stationId\"", stationId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT d.*, s.code AS \"s_code\", s.name AS"
                + " \"s_name\" FROM \"WeighbridgeDevice\" d JOIN \"WeighbridgeStation\" s ON s.id = d.\"stationId\""
                + where + " ORDER BY d.\"isActive\" DESC, s.code, d.code").params(params))) {
            out.add(deviceShape(r));
        }
        return out;
    }

    private static Map<String, Object> deviceShape(Map<String, Object> r) {
        Map<String, Object> d = SqlRows.as(WeighbridgeDevice.class, r);
        d.put("station", Obj.of("id", r.get("stationId"), "code", r.get("s_code"), "name", r.get("s_name")));
        return d;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> device(String id) {
        Map<String, Object> r = SqlRows.one(db.jdbc().sql("SELECT d.*, s.code AS \"s_code\", s.name AS \"s_name\""
                + " FROM \"WeighbridgeDevice\" d JOIN \"WeighbridgeStation\" s ON s.id = d.\"stationId\" WHERE d.id = ?"
                + " AND d.\"organizationId\" = ?").params(id, FleetDb.org()));
        if (r == null) {
            throw ApiException.notFound("Weighbridge device not found");
        }
        return deviceShape(r);
    }

    @Transactional
    public Map<String, Object> createDevice(DeviceInput in) {
        requireStation(in.stationId(), false);
        String type = in.adapterType().trim();
        WeighbridgeAdapter adapter = adapter(type);
        Map<String, Object> config = in.config() == null ? Map.of() : in.config();
        validateConfig(adapter, config);
        String code = in.code().trim().toUpperCase(Locale.ROOT);
        assertDeviceCodeFree(null, code);
        Map<String, Object> row = db.insert(WeighbridgeDevice.class, Obj.of(
                "stationId", in.stationId(),
                "code", code,
                "name", FleetVehicleService.trim(in.name()),
                "adapterType", type,
                "config", jsonb(config),
                "readTimeoutMs", in.readTimeoutMs() == null ? 5000 : in.readTimeoutMs(),
                "isActive", true,
                "notes", FleetVehicleService.trim(in.notes()),
                "createdBy", TenantContext.userId(),
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("WeighbridgeDevice", (String) row.get("id"), "create", null, row));
        return device((String) row.get("id"));
    }

    @Transactional
    public Map<String, Object> updateDevice(String id, DevicePatch in) {
        Map<String, Object> before = db.require(WeighbridgeDevice.class, id, true, "Weighbridge device not found");
        List<String> clear = clearList(in.clear(), WeighbridgeCommands.DEVICE_CLEARABLE);
        Map<String, Object> data = new LinkedHashMap<>();
        if (FleetVehicleService.notBlank(in.stationId())) {
            requireStation(in.stationId(), false);
            data.put("stationId", in.stationId());
        }
        if (in.code() != null) {
            String code = in.code().trim().toUpperCase(Locale.ROOT);
            assertDeviceCodeFree(id, code);
            data.put("code", code);
        }
        FleetIncidentService.put(data, "name", FleetVehicleService.trim(in.name()));
        if (in.config() != null) {
            validateConfig(adapter((String) before.get("adapterType")), in.config());
            data.put("config", jsonb(in.config()));
        }
        FleetIncidentService.put(data, "readTimeoutMs", in.readTimeoutMs());
        FleetIncidentService.put(data, "isActive", in.isActive());
        FleetIncidentService.put(data, "notes", FleetVehicleService.trim(in.notes()));
        for (String f : clear) {
            data.put(f, null);
        }
        if (data.isEmpty()) {
            return device(id);
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(WeighbridgeDevice.class, id, data);
        audit.recordInTx(AuditInput.of("WeighbridgeDevice", id, "update", before, after));
        return device(id);
    }

    /** Demo / tests: put a weight on a simulator's platform. 400 for other adapters. */
    @Transactional
    public Map<String, Object> setSimulatorWeight(String id, SimulatorWeight in) {
        Map<String, Object> before = db.require(WeighbridgeDevice.class, id, true, "Weighbridge device not found");
        if (!SimulatorWeighbridgeAdapter.TYPE.equals(before.get("adapterType"))) {
            throw ApiException.badRequest("Device " + before.get("code") + " is not a simulator");
        }
        Map<String, Object> config = new LinkedHashMap<>(config(before.get("config")));
        config.put("weightKg", in.weightKg().toPlainString());
        if (in.mode() != null) {
            config.put("mode", in.mode());
        }
        if (in.motionFrames() != null) {
            config.put("motionFrames", in.motionFrames());
        }
        validateConfig(adapter(SimulatorWeighbridgeAdapter.TYPE), config);
        db.update(WeighbridgeDevice.class, id, Obj.of("config", jsonb(config), "updatedBy", TenantContext.userId()));
        return device(id);
    }

    /** Reachability probe plus a stable read (settings screen "test read"). Never throws on a device failure. */
    public Map<String, Object> testRead(String id) {
        Map<String, Object> d = loadDevice(id);
        DeviceSpec spec = spec(d);
        WeighbridgeAdapter adapter = adapter(spec.adapterType());
        DeviceHealth health = adapter.health(spec, adapters.timeout((Integer) d.get("readTimeoutMs")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deviceId", id);
        out.put("reachable", health.reachable());
        out.put("detail", health.detail());
        try {
            DeviceReading r = readDevice(id, null);
            out.put("ok", true);
            out.put("weightKg", r.weight().kilograms());
            out.put("rawFrame", r.weight().rawFrame());
            out.put("at", r.weight().at());
        } catch (ApiException e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** Operator screen: the current stable weight on the platform (nothing is stored on a ticket). */
    public Map<String, Object> liveRead(String id) {
        DeviceReading r = readDevice(id, null);
        return Obj.of("deviceId", id, "weightKg", r.weight().kilograms(), "rawFrame", r.weight().rawFrame(),
                "stable", r.weight().stable(), "at", r.weight().at());
    }

    /**
     * Reads a stable weight from an active device (of {@code stationId} when given). Runs outside any transaction;
     * the outcome is recorded on the device. Device failures become 503 (unreachable, timeout, format) or 409
     * (motion, overload) with the adapter's message and {@code reason}.
     */
    public DeviceReading readDevice(String id, String stationId) {
        Map<String, Object> d = loadDevice(id);
        if (!Boolean.TRUE.equals(d.get("isActive"))) {
            throw ApiException.badRequest("Weighbridge device " + d.get("code") + " is inactive");
        }
        if (stationId != null && !stationId.equals(d.get("stationId"))) {
            throw ApiException.badRequest("Weighbridge device " + d.get("code") + " does not belong to this ticket's"
                    + " station");
        }
        DeviceSpec spec = spec(d);
        WeighbridgeAdapter adapter = adapter(spec.adapterType());
        try {
            StableWeight w = adapter.readStable(spec, adapters.timeout((Integer) d.get("readTimeoutMs")));
            record(id, "ok", w.kilograms(), null);
            return new DeviceReading(d, w);
        } catch (WeighbridgeReadException e) {
            record(id, "error", null, e.getMessage());
            HttpStatus status = switch (e.reason()) {
                case "unstable", "overload" -> HttpStatus.CONFLICT;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            throw new ApiException(status, e.getMessage(), Map.of("reason", e.reason()));
        }
    }

    private Map<String, Object> loadDevice(String id) {
        Map<String, Object> d = tx.execute(st -> db.byId(WeighbridgeDevice.class, id, false));
        if (d == null) {
            throw ApiException.notFound("Weighbridge device not found");
        }
        return d;
    }

    private void record(String id, String status, BigDecimal kg, String error) {
        tx.executeWithoutResult(st -> db.update(WeighbridgeDevice.class, id, Obj.of("lastReadAt", Instant.now(),
                "lastReadKg", kg, "lastStatus", status, "lastError", error == null ? null : abbreviate(error))));
    }

    static DeviceSpec spec(Map<String, Object> d) {
        return new DeviceSpec((String) d.get("id"), (String) d.get("code"), (String) d.get("adapterType"),
                config(d.get("config")));
    }

    /** A JSONB configuration column as a plain map (numbers as BigDecimal, arrays as lists of strings). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> config(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof tools.jackson.databind.JsonNode node && node.isObject()) {
            for (Map.Entry<String, tools.jackson.databind.JsonNode> e : node.properties()) {
                tools.jackson.databind.JsonNode v = e.getValue();
                Object plain;
                if (v == null || v.isNull()) {
                    plain = null;
                } else if (v.isNumber()) {
                    plain = v.decimalValue();
                } else if (v.isBoolean()) {
                    plain = v.booleanValue();
                } else if (v.isArray()) {
                    List<String> items = new ArrayList<>();
                    v.forEach(x -> items.add(x.asString()));
                    plain = items;
                } else {
                    plain = v.asString();
                }
                out.put(e.getKey(), plain);
            }
        }
        return out;
    }

    private WeighbridgeAdapter adapter(String type) {
        return adapters.get(type).orElseThrow(() -> ApiException.badRequest("Weighbridge adapter " + type
                + " is not available (available: " + String.join(", ", adapters.available()) + ")"));
    }

    private static void validateConfig(WeighbridgeAdapter adapter, Map<String, Object> config) {
        try {
            adapter.validate(config);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private void assertDeviceCodeFree(String id, String code) {
        long n = db.jdbc().sql("SELECT count(*) FROM \"WeighbridgeDevice\" WHERE \"organizationId\" = ? AND code = ?"
                + (id == null ? "" : " AND id <> ?")).params(id == null ? List.of(FleetDb.org(), code)
                        : List.of(FleetDb.org(), code, id)).query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict("A weighbridge device with code " + code + " already exists");
        }
    }

    // ------------------------------------------------------------------ product settings

    @Transactional(readOnly = true)
    public List<Map<String, Object>> productSettings() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT ps.*, p.code AS \"p_code\", p.name AS"
                + " \"p_name\", u.code AS \"u_code\" FROM \"WeighbridgeProductSetting\" ps JOIN \"Product\" p ON p.id ="
                + " ps.\"productId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\" WHERE ps.\"organizationId\" ="
                + " ? ORDER BY p.name").params(FleetDb.org()))) {
            Map<String, Object> s = SqlRows.as(WeighbridgeProductSetting.class, r);
            s.put("product", Obj.of("id", r.get("productId"), "code", r.get("p_code"), "name", r.get("p_name"),
                    "baseUnit", r.get("u_code")));
            out.add(s);
        }
        return out;
    }

    /** Replaces a product's settings; all fields null removes the row. */
    @Transactional
    public Map<String, Object> putProductSetting(String productId, ProductSettingInput in) {
        if (!db.exists("Product", productId, true)) {
            throw ApiException.notFound("Product not found");
        }
        Map<String, Object> before = SqlRows.one(WeighbridgeProductSetting.class, db.jdbc().sql("SELECT * FROM"
                + " \"WeighbridgeProductSetting\" WHERE \"organizationId\" = ? AND \"productId\" = ? FOR UPDATE")
                .params(FleetDb.org(), productId));
        String notes = FleetVehicleService.trim(in.notes());
        if (in.kgPerUnit() == null && in.tolerancePct() == null && in.toleranceKg() == null && notes == null) {
            if (before != null) {
                db.jdbc().sql("DELETE FROM \"WeighbridgeProductSetting\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(before.get("id"), FleetDb.org()).update();
                audit.recordInTx(AuditInput.of("WeighbridgeProductSetting", (String) before.get("id"), "delete",
                        before, null));
            }
            return Obj.of("productId", productId, "deleted", before != null);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kgPerUnit", in.kgPerUnit());
        data.put("tolerancePct", in.tolerancePct());
        data.put("toleranceKg", in.toleranceKg());
        data.put("notes", notes);
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after;
        if (before == null) {
            data.put("productId", productId);
            after = db.insert(WeighbridgeProductSetting.class, data);
        } else {
            after = db.update(WeighbridgeProductSetting.class, (String) before.get("id"), data);
        }
        audit.recordInTx(AuditInput.of("WeighbridgeProductSetting", (String) after.get("id"), before == null
                ? "create" : "update", before, after));
        return after;
    }

    /** The product's settings row, or null. */
    Map<String, Object> productSetting(String productId) {
        if (productId == null) {
            return null;
        }
        return SqlRows.one(WeighbridgeProductSetting.class, db.jdbc().sql("SELECT * FROM \"WeighbridgeProductSetting\""
                + " WHERE \"organizationId\" = ? AND \"productId\" = ?").params(FleetDb.org(), productId));
    }

    // ------------------------------------------------------------------ lookups

    /** Everything the ticket screens pick from: stations, devices, adapters, units, subject types. */
    @Transactional(readOnly = true)
    public Map<String, Object> lookups() {
        List<Map<String, Object>> units = SqlRows.list(db.jdbc().sql("SELECT id, code, name, symbol, category FROM"
                + " \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND \"isActive\" AND \"deletedAt\" IS NULL ORDER BY"
                + " category, code").params(FleetDb.org()));
        List<Map<String, Object>> locations = SqlRows.list(db.jdbc().sql("SELECT id, code, name FROM"
                + " \"InventoryLocation\" WHERE \"organizationId\" = ? AND \"isActive\" AND \"deletedAt\" IS NULL ORDER"
                + " BY code").params(FleetDb.org()));
        return Obj.of("stations", stations(false), "devices", devices(null, false), "adapters", adapters.available(),
                "subjectTypes", SUBJECT_TYPES, "units", units, "locations", locations);
    }

    /** Products for the ticket form ({@code search} on code / name), with the base and sales / purchase units. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> lookupProducts(String search) {
        StringBuilder where = new StringBuilder(" WHERE p.\"organizationId\" = ? AND p.\"deletedAt\" IS NULL AND"
                + " p.\"isActive\"");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (FleetVehicleService.notBlank(search)) {
            where.append(" AND (p.name ILIKE ? OR p.code ILIKE ?)");
            params.add(FleetIncidentService.like(search));
            params.add(FleetIncidentService.like(search));
        }
        return SqlRows.list(db.jdbc().sql("SELECT p.id, p.code, p.name, p.\"uomId\", p.\"salesUomId\","
                + " p.\"purchaseUomId\", u.code AS \"uomCode\", ps.\"kgPerUnit\" FROM \"Product\" p LEFT JOIN"
                + " \"UnitOfMeasure\" u ON u.id = p.\"uomId\" LEFT JOIN \"WeighbridgeProductSetting\" ps ON"
                + " ps.\"productId\" = p.id AND ps.\"organizationId\" = p.\"organizationId\"" + where
                + " ORDER BY p.name LIMIT 30").params(params));
    }

    /** Customers / suppliers / transporters for the ticket form ({@code role} customer or supplier narrows it). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> lookupPartners(String search, String role) {
        StringBuilder where = new StringBuilder(" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if ("customer".equals(role)) {
            where.append(" AND \"isCustomer\"");
        } else if ("supplier".equals(role)) {
            where.append(" AND \"isSupplier\"");
        }
        if (FleetVehicleService.notBlank(search)) {
            where.append(" AND (name ILIKE ? OR code ILIKE ?)");
            params.add(FleetIncidentService.like(search));
            params.add(FleetIncidentService.like(search));
        }
        return SqlRows.list(db.jdbc().sql("SELECT id, code, name, \"isCustomer\", \"isSupplier\" FROM \"Partner\""
                + where + " ORDER BY name LIMIT 30").params(params));
    }

    /** Purchase orders that can still receive (active / partially received) with their open lines. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> lookupPurchaseOrders(String search, String partnerId) {
        StringBuilder where = new StringBuilder(" WHERE po.\"organizationId\" = ? AND po.\"deletedAt\" IS NULL AND"
                + " po.status::text IN ('active', 'partially_received')");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        FleetIncidentService.eq(where, params, "po.\"partnerId\"", partnerId);
        if (FleetVehicleService.notBlank(search)) {
            where.append(" AND (po.\"orderNumber\" ILIKE ? OR pa.name ILIKE ?)");
            params.add(FleetIncidentService.like(search));
            params.add(FleetIncidentService.like(search));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> po : SqlRows.list(db.jdbc().sql("SELECT po.id, po.\"orderNumber\", po.\"partnerId\","
                + " pa.name AS \"partnerName\", po.status::text AS status FROM \"PurchaseOrder\" po LEFT JOIN"
                + " \"Partner\" pa ON pa.id = po.\"partnerId\"" + where + " ORDER BY po.\"createdAt\" DESC LIMIT 30")
                .params(params))) {
            po.put("lines", SqlRows.list(db.jdbc().sql("SELECT l.id, l.\"productId\", p.name AS \"productName\","
                    + " l.description, l.quantity, l.\"receivedQuantity\", l.\"unitOfMeasureId\", u.code AS \"uomCode\""
                    + " FROM \"PurchaseOrderLine\" l LEFT JOIN \"Product\" p ON p.id = l.\"productId\" LEFT JOIN"
                    + " \"UnitOfMeasure\" u ON u.id = l.\"unitOfMeasureId\" WHERE l.\"purchaseOrderId\" = ? AND"
                    + " l.\"organizationId\" = ? ORDER BY l.\"lineNumber\"").params(po.get("id"), FleetDb.org())));
            out.add(po);
        }
        return out;
    }

    static List<String> clearList(List<String> clear, List<String> allowed) {
        List<String> c = clear == null ? List.of() : clear;
        for (String f : c) {
            if (!allowed.contains(f)) {
                throw ApiException.badRequest("Field " + f + " cannot be cleared");
            }
        }
        return c;
    }

    private static String abbreviate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 499) + "…";
    }
}
