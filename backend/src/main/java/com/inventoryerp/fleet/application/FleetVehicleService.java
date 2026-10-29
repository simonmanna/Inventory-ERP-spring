package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FleetCommands.VehicleInput;
import com.inventoryerp.fleet.application.FleetCommands.VehiclePatch;
import com.inventoryerp.fleet.application.FleetCommands.VehicleQuery;
import com.inventoryerp.fleet.domain.FleetVehicle;
import com.inventoryerp.fleet.domain.FleetVehicleStatusHistory;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vehicle &amp; equipment master (1.1) and the module's public vehicle API for the later sub-areas: registration,
 * edit, soft delete, the status machine with history ({@link #changeStatus}), and read models (list, detail, linked
 * fixed asset). Branch, site, cost centre, partner and asset references are validated against their modules' tables
 * (read-only, org-scoped); fleet never writes them.
 */
@Service
public class FleetVehicleService {

    static final String AUDIT_ENTITY = "FleetVehicle";

    /** Sort keys the list accepts → column. */
    private static final Map<String, String> SORTS = Map.of(
            "code", "v.code",
            "registrationPlate", "v.\"registrationPlate\"",
            "make", "v.make",
            "status", "v.status",
            "createdAt", "v.\"createdAt\"");

    private static final Set<String> PLATE_REQUIRED_KINDS = Set.of("road_vehicle", "trailer");

    private final FleetDb db;
    private final FleetCategoryService categories;
    private final FleetMeterService meters;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;

    public FleetVehicleService(FleetDb db, FleetCategoryService categories, FleetMeterService meters,
            SequenceService sequence, AuditService audit, EventBus events) {
        this.db = db;
        this.categories = categories;
        this.meters = meters;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ reads

    /** Paged register with class, branch, site and cost-centre names. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(VehicleQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE v.\"organizationId\" = ? AND v.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (notBlank(q.search())) {
            where.append(" AND (v.code ILIKE ? OR v.name ILIKE ? OR v.\"registrationPlate\" ILIKE ? OR v.vin ILIKE ?"
                    + " OR v.make ILIKE ? OR v.model ILIKE ?)");
            String like = "%" + q.search().trim().replace("%", "\\%").replace("_", "\\_") + "%";
            for (int i = 0; i < 6; i++) {
                params.add(like);
            }
        }
        filter(where, params, "v.\"categoryId\"", q.categoryId());
        filter(where, params, "c.kind", q.kind());
        filter(where, params, "v.status", q.status());
        filter(where, params, "v.\"branchId\"", q.branchId());
        filter(where, params, "v.ownership", q.ownership());
        String from = " FROM \"FleetVehicle\" v JOIN \"FleetVehicleCategory\" c ON c.id = v.\"categoryId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        String sort = SORTS.getOrDefault(q.sortBy() == null ? "code" : q.sortBy(), "v.code");
        String dir = "desc".equalsIgnoreCase(q.sortOrder()) ? "DESC" : "ASC";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        // One query per page (no N+1): the referenced names come from left joins.
        String select = """
                SELECT v.*, c.code AS "c_code", c.name AS "c_name", c.kind AS "c_kind",
                       b.code AS "b_code", b.name AS "b_name", l.code AS "l_code", l.name AS "l_name",
                       cc.code AS "cc_code", cc.name AS "cc_name", p.code AS "p_code", p.name AS "p_name"
                """;
        String joins = """
                 LEFT JOIN "Branch" b ON b.id = v."branchId" AND b."organizationId" = v."organizationId"
                 LEFT JOIN "InventoryLocation" l ON l.id = v."homeLocationId" AND l."organizationId" = v."organizationId"
                 LEFT JOIN "CostCenter" cc ON cc.id = v."costCenterId" AND cc."organizationId" = v."organizationId"
                 LEFT JOIN "Partner" p ON p.id = v."ownerPartnerId" AND p."organizationId" = v."organizationId"
                """;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(select + from + joins + where + " ORDER BY " + sort
                + " " + dir + " NULLS LAST, v.id LIMIT ? OFFSET ?").params(pageParams))) {
            Map<String, Object> v = SqlRows.as(FleetVehicle.class, r);
            v.put("category", Obj.of("id", r.get("categoryId"), "code", r.get("c_code"), "name", r.get("c_name"),
                    "kind", r.get("c_kind")));
            v.put("branch", named(r, "branchId", "b_"));
            v.put("homeLocation", named(r, "homeLocationId", "l_"));
            v.put("costCenter", named(r, "costCenterId", "cc_"));
            v.put("ownerPartner", named(r, "ownerPartnerId", "p_"));
            v.put("meters", meters(v));
            rows.add(v);
        }
        return PageResult.of(rows, page, size, total);
    }

    private static Map<String, Object> named(Map<String, Object> r, String idKey, String prefix) {
        return r.get(idKey) == null ? null
                : Obj.of("id", r.get(idKey), "code", r.get(prefix + "code"), "name", r.get(prefix + "name"));
    }

    private static Map<String, Object> meters(Map<String, Object> v) {
        return Obj.of(
                "odometer", meter(v, "currentOdometer", "odometerOffset", "odometerAt"),
                "hours", meter(v, "currentHours", "hoursOffset", "hoursAt"));
    }

    /** Detail: the row plus class, references, linked asset summary, meters and compliance / correction counts. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> v = withRefs(require(id, false));
        String org = FleetDb.org();
        LocalDate today = LocalDate.now(BusinessZone.get());
        Map<String, Object> compliance = db.jdbc().sql("""
                        SELECT count(*) FILTER (WHERE "expiresOn" < ?) AS expired,
                               count(*) FILTER (WHERE "expiresOn" >= ? AND "expiresOn" <= ?::date + "reminderLeadDays")
                                   AS expiring,
                               count(*) AS total
                        FROM "FleetComplianceDocument" WHERE "organizationId" = ? AND "vehicleId" = ?
                          AND status = 'active' AND "deletedAt" IS NULL""")
                .params(java.sql.Date.valueOf(today), java.sql.Date.valueOf(today), java.sql.Date.valueOf(today), org,
                        id)
                .query().singleRow();
        v.put("compliance", Obj.of("expired", compliance.get("expired"), "expiring", compliance.get("expiring"),
                "total", compliance.get("total")));
        v.put("pendingCorrections", db.jdbc().sql("SELECT count(*) FROM \"FleetMeterCorrection\" WHERE"
                + " \"organizationId\" = ? AND \"vehicleId\" = ? AND status = 'pending'").params(org, id)
                .query(Long.class).single());
        return v;
    }

    /** The vehicle row (404 when unknown or deleted) — for later sub-modules. */
    public Map<String, Object> require(String id, boolean lock) {
        return db.require(FleetVehicle.class, id, lock, "Vehicle not found");
    }

    /** Status history, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> statusHistory(String id) {
        require(id, false);
        return SqlRows.list(db.jdbc().sql("""
                SELECT h.*, u."firstName" AS "changedByFirstName", u."lastName" AS "changedByLastName"
                FROM "FleetVehicleStatusHistory" h LEFT JOIN "User" u ON u.id = h."changedBy"
                WHERE h."organizationId" = ? AND h."vehicleId" = ? ORDER BY h."changedAt" DESC, h.id DESC""")
                .params(FleetDb.org(), id));
    }

    /**
     * The linked fixed asset, read-only (depreciation stays in fixedasset): asset fields, accumulated depreciation and
     * book value from the posted depreciation lines, and the last 24 depreciation lines. {@code {asset: null}} when
     * the vehicle has no asset.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> linkedAsset(String id) {
        Map<String, Object> v = require(id, false);
        String assetId = (String) v.get("assetId");
        if (assetId == null) {
            return Obj.of("asset", null, "depreciation", List.of());
        }
        String org = FleetDb.org();
        Map<String, Object> asset = SqlRows.one(db.jdbc().sql("""
                SELECT a.id, a."assetCode", a.name, a.status::text AS status, a."purchaseDate", a."purchaseCost",
                       a."currentValue", a."salvageValue", a."usefulLife", a."usefulLifeUnit", a."capitalizedCost",
                       c.name AS "categoryName"
                FROM "Asset" a LEFT JOIN "AssetCategory" c ON c.id = a."categoryId"
                WHERE a.id = ? AND a."organizationId" = ?""").params(assetId, org));
        List<Map<String, Object>> lines = SqlRows.list(db.jdbc().sql("""
                SELECT id, period, method::text AS method, "depreciationAmount", "accumulatedDepr", "bookValue",
                       "isPosted", "postedAt"
                FROM "AssetDepreciation" WHERE "organizationId" = ? AND "assetId" = ?
                ORDER BY period DESC LIMIT 24""").params(org, assetId));
        BigDecimal accumulated = BigDecimal.ZERO;
        BigDecimal book = null;
        for (Map<String, Object> l : lines) {
            if (Boolean.TRUE.equals(l.get("isPosted"))) {
                accumulated = SqlRows.dec(l, "accumulatedDepr");
                book = SqlRows.dec(l, "bookValue");
                break;
            }
        }
        if (asset != null && book == null) {
            book = asset.get("currentValue") == null ? null : SqlRows.dec(asset, "currentValue");
        }
        return Obj.of("asset", asset, "accumulatedDepreciation", accumulated, "bookValue", book,
                "depreciation", lines);
    }

    // ------------------------------------------------------------------ writes

    /** Register a vehicle; records the registration status row and the opening meter readings. */
    @Transactional
    public Map<String, Object> create(VehicleInput in) {
        Map<String, Object> category = categories.requireActive(in.categoryId());
        String meterType = in.meterType() != null ? in.meterType() : (String) category.get("defaultMeterType");
        String ownership = in.ownership() == null ? "owned" : in.ownership();
        String plate = normalizePlate(in.registrationPlate());
        String vin = normalizeVin(in.vin());
        assertPlate((String) category.get("kind"), plate);
        assertOwner(ownership, in.ownerPartnerId());
        assertYear(in.year());
        assertRefs(in.branchId(), in.homeLocationId(), in.costCenterId(), in.ownerPartnerId(), in.assetId());
        String code = notBlank(in.code()) ? FleetCategoryService.normalizeCode(in.code()) : nextCode();
        assertUnique(null, code, plate, vin, in.assetId());
        if (in.openingOdometer() != null && !MeterReadingPolicy.tracks(meterType, "odometer")) {
            throw ApiException.badRequest("Vehicle " + code + " does not track an odometer");
        }
        if (in.openingHours() != null && !MeterReadingPolicy.tracks(meterType, "hours")) {
            throw ApiException.badRequest("Vehicle " + code + " does not track engine hours");
        }
        Instant now = Instant.now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", trim(in.name()));
        data.put("registrationPlate", plate);
        data.put("vin", vin);
        data.put("engineNumber", trim(in.engineNumber()));
        data.put("make", trim(in.make()));
        data.put("model", trim(in.model()));
        data.put("year", in.year());
        data.put("color", trim(in.color()));
        data.put("categoryId", in.categoryId());
        data.put("ownership", ownership);
        data.put("ownerPartnerId", blank(in.ownerPartnerId()));
        data.put("payloadCapacityT", in.payloadCapacityT());
        data.put("bodyVolumeM3", in.bodyVolumeM3());
        data.put("axleConfig", trim(in.axleConfig()));
        data.put("fuelType", in.fuelType() == null ? "diesel" : in.fuelType());
        data.put("tankCapacityL", in.tankCapacityL());
        data.put("meterType", meterType);
        data.put("branchId", blank(in.branchId()));
        data.put("homeLocationId", blank(in.homeLocationId()));
        data.put("costCenterId", blank(in.costCenterId()));
        data.put("assetId", blank(in.assetId()));
        data.put("acquiredOn", in.acquiredOn());
        data.put("notes", trim(in.notes()));
        data.put("status", VehicleStatusPolicy.ACTIVE);
        data.put("statusChangedAt", now);
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> row = db.insert(FleetVehicle.class, data);
        String id = (String) row.get("id");
        if (in.openingOdometer() != null) {
            meters.recordOpening(id, "odometer", in.openingOdometer(), now);
        }
        if (in.openingHours() != null) {
            meters.recordOpening(id, "hours", in.openingHours(), now);
        }
        history(id, null, VehicleStatusPolicy.ACTIVE, "Registered", "registration", null);
        Map<String, Object> created = require(id, false);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, created));
        events.publish(DomainEvents.FLEET_VEHICLE_CREATED, Obj.of("organizationId", FleetDb.org(), "vehicleId", id,
                "code", code, "categoryId", in.categoryId()));
        return withRefs(created);
    }

    /** Edit the master data of a vehicle that is not disposed. */
    @Transactional
    public Map<String, Object> update(String id, VehiclePatch in) {
        Map<String, Object> before = require(id, true);
        String code0 = (String) before.get("code");
        if (VehicleStatusPolicy.DISPOSED.equals(before.get("status"))) {
            throw ApiException.badRequest("Vehicle " + code0 + " is disposed and can no longer be edited");
        }
        List<String> clear = in.clear() == null ? List.of() : in.clear();
        for (String f : clear) {
            if (!FleetCommands.VEHICLE_CLEARABLE.contains(f)) {
                throw ApiException.badRequest("Field " + f + " cannot be cleared");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, "code", in.code() == null ? null : FleetCategoryService.normalizeCode(in.code()));
        put(data, "name", trim(in.name()));
        put(data, "registrationPlate", normalizePlate(in.registrationPlate()));
        put(data, "vin", normalizeVin(in.vin()));
        put(data, "engineNumber", trim(in.engineNumber()));
        put(data, "make", trim(in.make()));
        put(data, "model", trim(in.model()));
        put(data, "year", in.year());
        put(data, "color", trim(in.color()));
        put(data, "categoryId", blank(in.categoryId()));
        put(data, "ownership", in.ownership());
        put(data, "ownerPartnerId", blank(in.ownerPartnerId()));
        put(data, "payloadCapacityT", in.payloadCapacityT());
        put(data, "bodyVolumeM3", in.bodyVolumeM3());
        put(data, "axleConfig", trim(in.axleConfig()));
        put(data, "fuelType", in.fuelType());
        put(data, "tankCapacityL", in.tankCapacityL());
        put(data, "meterType", in.meterType());
        put(data, "branchId", blank(in.branchId()));
        put(data, "homeLocationId", blank(in.homeLocationId()));
        put(data, "costCenterId", blank(in.costCenterId()));
        put(data, "assetId", blank(in.assetId()));
        put(data, "acquiredOn", in.acquiredOn());
        put(data, "notes", trim(in.notes()));
        for (String f : clear) {
            data.put(f, null);
        }
        if (data.isEmpty()) {
            return withRefs(before);
        }
        Map<String, Object> merged = new LinkedHashMap<>(before);
        merged.putAll(data);
        Map<String, Object> category = data.containsKey("categoryId")
                ? categories.requireActive((String) merged.get("categoryId"))
                : db.byId(com.inventoryerp.fleet.domain.FleetVehicleCategory.class, (String) merged.get("categoryId"),
                        false);
        assertPlate(category == null ? null : (String) category.get("kind"), (String) merged.get("registrationPlate"));
        assertOwner((String) merged.get("ownership"), (String) merged.get("ownerPartnerId"));
        assertYear((Integer) merged.get("year"));
        assertRefs((String) data.get("branchId"), (String) data.get("homeLocationId"),
                (String) data.get("costCenterId"), (String) data.get("ownerPartnerId"), (String) data.get("assetId"));
        assertUnique(id, (String) data.get("code"), (String) data.get("registrationPlate"), (String) data.get("vin"),
                (String) data.get("assetId"));
        if (data.containsKey("meterType")) {
            for (String meter : List.of("odometer", "hours")) {
                if (!MeterReadingPolicy.tracks((String) data.get("meterType"), meter) && meters.hasReadings(id, meter)) {
                    throw ApiException.badRequest("Vehicle " + code0 + " has " + MeterReadingPolicy.meterLabel(meter)
                            .toLowerCase(Locale.ROOT) + " readings; keep a meter type that tracks it");
                }
            }
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetVehicle.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, after));
        return withRefs(after);
    }

    /** Soft delete of a vehicle without operating history (a vehicle with history is disposed instead). */
    @Transactional
    public void delete(String id) {
        Map<String, Object> v = require(id, true);
        if (meters.hasOperatingHistory(id)) {
            throw ApiException.badRequest("Vehicle " + v.get("code") + " has operating history; mark it disposed"
                    + " instead");
        }
        db.update(FleetVehicle.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "delete", v, null));
    }

    /**
     * Move a vehicle to {@code to} (public API: maintenance, inspections and breakdown reports call it with their
     * {@code source} / {@code sourceId}). Writes the history row with the current meters, audits and publishes
     * {@code fleet.vehicle.status_changed}.
     */
    @Transactional
    public Map<String, Object> changeStatus(String id, String to, String reason, String source, String sourceId) {
        Map<String, Object> v = require(id, true);
        String from = (String) v.get("status");
        VehicleStatusPolicy.assertTransition((String) v.get("code"), from, to, reason);
        Instant now = Instant.now();
        Map<String, Object> data = Obj.of("status", to, "statusReason", trim(reason), "statusChangedAt", now,
                "updatedBy", TenantContext.userId());
        if (VehicleStatusPolicy.DISPOSED.equals(to)) {
            data.put("disposedAt", now);
        }
        Map<String, Object> after = db.update(FleetVehicle.class, id, data);
        String src = source == null ? "manual" : source;
        history(id, from, to, trim(reason), src, sourceId);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", Obj.of("status", from),
                Obj.of("status", to, "reason", trim(reason), "source", src)));
        events.publish(DomainEvents.FLEET_VEHICLE_STATUS_CHANGED, Obj.of("organizationId", FleetDb.org(),
                "vehicleId", id, "fromStatus", from, "toStatus", to, "reason", trim(reason), "source", src,
                "sourceId", sourceId));
        return withRefs(after);
    }

    // ------------------------------------------------------------------ helpers

    private void history(String vehicleId, String from, String to, String reason, String source, String sourceId) {
        Map<String, Object> v = db.byId(FleetVehicle.class, vehicleId, false);
        db.insert(FleetVehicleStatusHistory.class, Obj.of(
                "vehicleId", vehicleId,
                "fromStatus", from,
                "toStatus", to,
                "reason", reason,
                "source", source,
                "sourceId", sourceId,
                "odometer", v.get("currentOdometer"),
                "hours", v.get("currentHours"),
                "changedBy", TenantContext.userId(),
                "changedAt", Instant.now()));
    }

    /** Adds {@code category}, {@code branch}, {@code homeLocation}, {@code costCenter}, {@code ownerPartner}, meters. */
    Map<String, Object> withRefs(Map<String, Object> v) {
        if (v == null) {
            return null;
        }
        String org = FleetDb.org();
        Map<String, Object> out = new LinkedHashMap<>(v);
        out.put("category", ref("SELECT id, code, name, kind, \"defaultMeterType\" FROM \"FleetVehicleCategory\""
                + " WHERE id = ? AND \"organizationId\" = ?", v.get("categoryId"), org));
        out.put("branch", ref("SELECT id, code, name FROM \"Branch\" WHERE id = ? AND \"organizationId\" = ?",
                v.get("branchId"), org));
        out.put("homeLocation", ref("SELECT id, code, name, type::text AS type FROM \"InventoryLocation\" WHERE id = ?"
                + " AND \"organizationId\" = ?", v.get("homeLocationId"), org));
        out.put("costCenter", ref("SELECT id, code, name FROM \"CostCenter\" WHERE id = ? AND \"organizationId\" = ?",
                v.get("costCenterId"), org));
        out.put("ownerPartner", ref("SELECT id, code, name FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?",
                v.get("ownerPartnerId"), org));
        out.put("asset", ref("SELECT id, \"assetCode\", name, status::text AS status FROM \"Asset\" WHERE id = ?"
                + " AND \"organizationId\" = ?", v.get("assetId"), org));
        out.put("meters", meters(v));
        return out;
    }

    private static Map<String, Object> meter(Map<String, Object> v, String current, String offset, String at) {
        if (v.get(current) == null) {
            return null;
        }
        BigDecimal value = SqlRows.dec(v, current);
        return Obj.of("value", value, "lifetime", value.add(SqlRows.dec(v, offset)), "at", v.get(at));
    }

    private Map<String, Object> ref(String sql, Object id, String org) {
        return id == null ? null : SqlRows.one(db.jdbc().sql(sql).params(id, org));
    }

    private String nextCode() {
        for (int i = 0; i < 20; i++) {
            String code = sequence.next("fleet_vehicle", "VEH-", 4);
            if (!taken("code", code, null)) {
                return code;
            }
        }
        throw ApiException.conflict("Could not allocate a vehicle code; enter one explicitly");
    }

    private void assertUnique(String exceptId, String code, String plate, String vin, String assetId) {
        if (code != null && taken("code", code, exceptId)) {
            throw ApiException.conflict("A vehicle with code " + code + " already exists");
        }
        if (plate != null) {
            String other = holder("registrationPlate", plate, exceptId);
            if (other != null) {
                throw ApiException.conflict("Registration plate " + plate + " is already registered to vehicle "
                        + other);
            }
        }
        if (vin != null) {
            String other = holder("vin", vin, exceptId);
            if (other != null) {
                throw ApiException.conflict("VIN / chassis number " + vin + " is already registered to vehicle "
                        + other);
            }
        }
        if (assetId != null) {
            String other = holder("assetId", assetId, exceptId);
            if (other != null) {
                throw ApiException.conflict("This fixed asset is already linked to vehicle " + other);
            }
        }
    }

    private boolean taken(String column, String value, String exceptId) {
        return holder(column, value, exceptId) != null;
    }

    /** Code of the live vehicle holding {@code column = value}, or null. */
    private String holder(String column, String value, String exceptId) {
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), value));
        if (exceptId != null) {
            params.add(exceptId);
        }
        return db.jdbc().sql("SELECT code FROM \"FleetVehicle\" WHERE \"organizationId\" = ? AND \"" + column
                        + "\" = ? AND \"deletedAt\" IS NULL" + (exceptId == null ? "" : " AND id <> ?") + " LIMIT 1")
                .params(params).query(String.class).optional().orElse(null);
    }

    private void assertRefs(String branchId, String locationId, String costCenterId, String partnerId,
            String assetId) {
        if (notBlank(branchId) && !db.exists("Branch", branchId, true)) {
            throw ApiException.badRequest("Branch not found");
        }
        if (notBlank(locationId) && !db.exists("InventoryLocation", locationId, true)) {
            throw ApiException.badRequest("Home site / location not found");
        }
        if (notBlank(costCenterId) && !db.exists("CostCenter", costCenterId, true)) {
            throw ApiException.badRequest("Cost centre not found");
        }
        if (notBlank(partnerId) && !db.exists("Partner", partnerId, true)) {
            throw ApiException.badRequest("Owner / transporter partner not found");
        }
        if (notBlank(assetId) && !db.exists("Asset", assetId, true)) {
            throw ApiException.badRequest("Fixed asset not found");
        }
    }

    private static void assertPlate(String kind, String plate) {
        if (kind != null && PLATE_REQUIRED_KINDS.contains(kind) && plate == null) {
            throw ApiException.badRequest("A registration plate is required for road vehicles and trailers");
        }
    }

    private static void assertOwner(String ownership, String ownerPartnerId) {
        if (("hired".equals(ownership) || "third_party".equals(ownership)) && !notBlank(ownerPartnerId)) {
            throw ApiException.badRequest("The owner / transporter is required for hired and third-party vehicles");
        }
    }

    private static void assertYear(Integer year) {
        if (year != null && year > LocalDate.now(BusinessZone.get()).getYear() + 1) {
            throw ApiException.badRequest("Model year cannot be later than next year");
        }
    }

    private static void filter(StringBuilder where, List<Object> params, String column, String value) {
        if (notBlank(value)) {
            where.append(" AND ").append(column).append(" = ?");
            params.add(value);
        }
    }

    private static void put(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    /** Upper case, single spaces ({@code "uaz 123x"} → {@code "UAZ 123X"}); blank → null. */
    static String normalizePlate(String plate) {
        String s = trim(plate);
        return s == null ? null : s.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Upper case without spaces or dashes; blank → null. */
    static String normalizeVin(String vin) {
        String s = trim(vin);
        return s == null ? null : s.toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "");
    }

    static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
