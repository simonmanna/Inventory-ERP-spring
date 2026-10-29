package com.inventoryerp.fleet.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.fleet.application.FleetTyreCommands.FitInput;
import com.inventoryerp.fleet.application.FleetTyreCommands.RemoveInput;
import com.inventoryerp.fleet.application.FleetTyreCommands.RetreadInput;
import com.inventoryerp.fleet.application.FleetTyreCommands.RotateInput;
import com.inventoryerp.fleet.application.FleetTyreCommands.ScrapInput;
import com.inventoryerp.fleet.application.FleetTyreCommands.TreadReadingInput;
import com.inventoryerp.fleet.application.FleetTyreCommands.TyreInput;
import com.inventoryerp.fleet.application.FleetTyreCommands.TyrePatch;
import com.inventoryerp.fleet.application.FleetTyreCommands.TyreQuery;
import com.inventoryerp.fleet.application.TyrePositionLayout.Layout;
import com.inventoryerp.fleet.domain.FleetTyre;
import com.inventoryerp.fleet.domain.FleetTyreEvent;
import com.inventoryerp.fleet.domain.FleetTyreReading;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tyre register and lifecycle (fleet 1.7, ADR-S009 §3 / §6): tyres by serial number, wheel positions from the
 * vehicle's axle configuration ({@link TyrePositionLayout}), fitment / rotation / removal with the meter reading of
 * the event (recorded on the vehicle's meters, source {@code tyre}), retreads, scrapping, tread-depth readings and the
 * cost per km ({@link TyrePolicy#stats}).
 *
 * <p>A tyre linked to a stocked inventory product is issued from stock exactly once, on its first fitment: one unit
 * through {@link StockService#issue} (quantity only) and the fleet posting Dr fleet tyres {@code fleet_tyre_expense}
 * (6430, the vehicle's cost centre, dimensions vehicleId / tyreId) / Cr the product's stock account, posting key
 * {@code fleet:tyre:issue:<tyreId>}. The tyre row is locked and remembers the issue, so a retry or a later re-fitment
 * never issues again.
 */
@Service
public class FleetTyreService {

    static final String AUDIT_ENTITY = "FleetTyre";
    private static final Duration FUTURE_SKEW = Duration.ofMinutes(5);

    private static final Map<String, String> SORTS = Map.of(
            "serialNumber", "t.\"serialNumber\"",
            "brand", "t.brand",
            "status", "t.status",
            "createdAt", "t.\"createdAt\"",
            "treadDepthMm", "t.\"treadDepthMm\"");

    private final FleetDb db;
    private final FleetVehicleService vehicles;
    private final FleetMeterService meters;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final AuditService audit;

    public FleetTyreService(FleetDb db, FleetVehicleService vehicles, FleetMeterService meters, StockService stock,
            StockPostingService stockPosting, PostingService posting, AccountResolverService accounts,
            AuditService audit) {
        this.db = db;
        this.vehicles = vehicles;
        this.meters = meters;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.posting = posting;
        this.accounts = accounts;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    private static final String SELECT = """
            SELECT t.*, v.code AS "v_code", v."registrationPlate" AS "v_plate",
                   v."currentOdometer" + v."odometerOffset" AS "v_odometer", v."currentHours" + v."hoursOffset" AS "v_hours"
            FROM "FleetTyre" t LEFT JOIN "FleetVehicle" v ON v.id = t."vehicleId" AND v."organizationId" = t."organizationId"
            """;

    /** Paged tyre register with the vehicle it is on and the live wear / cost figures. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(TyreQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ? AND t.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (FleetVehicleService.notBlank(q.search())) {
            where.append(" AND (t.\"serialNumber\" ILIKE ? OR t.brand ILIKE ? OR t.model ILIKE ? OR t.size ILIKE ?)");
            String like = "%" + q.search().trim().replace("%", "\\%").replace("_", "\\_") + "%";
            for (int i = 0; i < 4; i++) {
                params.add(like);
            }
        }
        filter(where, params, "t.status", q.status());
        filter(where, params, "t.\"vehicleId\"", q.vehicleId());
        filter(where, params, "t.\"tyreType\"", q.tyreType());
        long total = db.jdbc().sql("SELECT count(*) FROM \"FleetTyre\" t" + where).params(params).query(Long.class)
                .single();
        String sort = SORTS.getOrDefault(q.sortBy() == null ? "serialNumber" : q.sortBy(), "t.\"serialNumber\"");
        String dir = "desc".equalsIgnoreCase(q.sortOrder()) ? "DESC" : "ASC";
        params.add(size);
        params.add((page - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(SELECT + where + " ORDER BY " + sort + " " + dir
                + " NULLS LAST, t.id LIMIT ? OFFSET ?").params(params))) {
            rows.add(shape(r));
        }
        return PageResult.of(rows, page, size, total);
    }

    /** Detail: the tyre, its vehicle / supplier / product, events (newest first), tread readings and stats. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> out = shape(row(id));
        String org = FleetDb.org();
        out.put("supplier", ref("SELECT id, code, name FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?",
                out.get("supplierPartnerId")));
        out.put("product", ref("SELECT id, sku, name FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?",
                out.get("productId")));
        out.put("stockLocation", ref("SELECT id, code, name FROM \"InventoryLocation\" WHERE id = ? AND"
                + " \"organizationId\" = ?", out.get("stockLocationId")));
        out.put("events", SqlRows.list(db.jdbc().sql("""
                SELECT e.*, v.code AS "vehicleCode", u."firstName" AS "createdByFirstName",
                       u."lastName" AS "createdByLastName"
                FROM "FleetTyreEvent" e LEFT JOIN "FleetVehicle" v ON v.id = e."vehicleId"
                LEFT JOIN "User" u ON u.id = e."createdBy"
                WHERE e."organizationId" = ? AND e."tyreId" = ? ORDER BY e."eventAt" DESC, e."createdAt" DESC""")
                .params(org, id)));
        out.put("readings", SqlRows.list(FleetTyreReading.class, db.jdbc().sql("SELECT * FROM \"FleetTyreReading\""
                + " WHERE \"organizationId\" = ? AND \"tyreId\" = ? ORDER BY \"readingAt\" DESC, \"createdAt\" DESC")
                .params(org, id)));
        return out;
    }

    /**
     * Wheel positions of a vehicle (from its axle configuration) with the tyre fitted at each, plus fitted tyres on a
     * position the current layout no longer has (axle configuration changed).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> vehicleTyres(String vehicleId) {
        Map<String, Object> v = vehicles.require(vehicleId, false);
        Layout layout = layout(v);
        Map<String, Map<String, Object>> byPosition = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(SELECT + " WHERE t.\"organizationId\" = ? AND"
                + " t.\"vehicleId\" = ? AND t.status = 'fitted' AND t.\"deletedAt\" IS NULL ORDER BY t.position")
                .params(FleetDb.org(), vehicleId))) {
            byPosition.put((String) r.get("position"), shape(r));
        }
        List<Map<String, Object>> axles = new ArrayList<>();
        for (TyrePositionLayout.Axle a : layout.axles()) {
            List<Map<String, Object>> positions = new ArrayList<>();
            for (TyrePositionLayout.Position p : a.positions()) {
                positions.add(position(p, byPosition.remove(p.code())));
            }
            axles.add(Obj.of("number", a.number(), "steer", a.steer(), "dual", a.dual(), "positions", positions));
        }
        List<Map<String, Object>> spares = new ArrayList<>();
        for (TyrePositionLayout.Position p : layout.spares()) {
            spares.add(position(p, byPosition.remove(p.code())));
        }
        long fitted = axles.stream().mapToLong(a -> ((List<?>) a.get("positions")).stream()
                .filter(p -> ((Map<?, ?>) p).get("tyre") != null).count()).sum();
        return Obj.of("vehicle", Obj.of("id", v.get("id"), "code", v.get("code"), "registrationPlate",
                        v.get("registrationPlate"), "axleConfig", v.get("axleConfig"), "status", v.get("status")),
                "axleConfig", layout.axleConfig(), "axles", axles, "spares", spares,
                "unplaced", new ArrayList<>(byPosition.values()), "fittedCount", fitted,
                "positionCount", layout.codes().size() - layout.spares().size());
    }

    /** Stock products for the tyre form's "issue from stock" picker (name / SKU search, at most 20). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> products(String search) {
        String like = "%" + (search == null ? "" : search.trim().replace("%", "\\%").replace("_", "\\_")) + "%";
        return SqlRows.list(db.jdbc().sql("SELECT id, sku, name, \"serialTracking\" FROM \"Product\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isActive\" AND (name ILIKE ? OR sku ILIKE ?)"
                + " ORDER BY name LIMIT 20").params(FleetDb.org(), like, like));
    }

    private static Map<String, Object> position(TyrePositionLayout.Position p, Map<String, Object> tyre) {
        return Obj.of("code", p.code(), "axle", p.axle(), "side", p.side(), "slot", p.slot(), "spare", p.spare(),
                "tyre", tyre);
    }

    // ------------------------------------------------------------------ master data

    /** Register a tyre (status {@code in_stock}). */
    @Transactional
    public Map<String, Object> create(TyreInput in) {
        String serial = normalizeSerial(in.serialNumber());
        assertUniqueSerial(serial, null);
        assertRefs(in.supplierPartnerId(), in.productId(), in.stockLocationId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serialNumber", serial);
        data.put("brand", FleetVehicleService.trim(in.brand()));
        data.put("model", FleetVehicleService.trim(in.model()));
        data.put("size", FleetVehicleService.trim(in.size()));
        data.put("tyreType", in.tyreType() == null ? "all_position" : in.tyreType());
        data.put("purchaseCost", in.purchaseCost());
        data.put("supplierPartnerId", blank(in.supplierPartnerId()));
        data.put("purchasedOn", in.purchasedOn());
        data.put("invoiceReference", FleetVehicleService.trim(in.invoiceReference()));
        data.put("productId", blank(in.productId()));
        data.put("stockLocationId", blank(in.stockLocationId()));
        data.put("newTreadDepthMm", in.newTreadDepthMm());
        data.put("treadDepthMm", in.newTreadDepthMm());
        data.put("minTreadDepthMm", in.minTreadDepthMm());
        data.put("status", TyrePolicy.IN_STOCK);
        data.put("notes", FleetVehicleService.trim(in.notes()));
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> row = db.insert(FleetTyre.class, data);
        String id = (String) row.get("id");
        event(id, "registered", null, null, null, Instant.now(), null, null, null, null, in.purchaseCost(),
                blank(in.supplierPartnerId()), null, null, null);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, row));
        return get(id);
    }

    /** Edit master data; the stock product cannot change once the tyre was issued from stock. */
    @Transactional
    public Map<String, Object> update(String id, TyrePatch in) {
        Map<String, Object> before = tyre(id, true);
        String serial0 = (String) before.get("serialNumber");
        if (TyrePolicy.SCRAPPED.equals(before.get("status"))) {
            throw ApiException.badRequest("Tyre " + serial0 + " is scrapped and can no longer be edited");
        }
        List<String> clear = in.clear() == null ? List.of() : in.clear();
        for (String f : clear) {
            if (!FleetTyreCommands.TYRE_CLEARABLE.contains(f)) {
                throw ApiException.badRequest("Field " + f + " cannot be cleared");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, "serialNumber", in.serialNumber() == null ? null : normalizeSerial(in.serialNumber()));
        put(data, "brand", FleetVehicleService.trim(in.brand()));
        put(data, "model", FleetVehicleService.trim(in.model()));
        put(data, "size", FleetVehicleService.trim(in.size()));
        put(data, "tyreType", in.tyreType());
        put(data, "purchaseCost", in.purchaseCost());
        put(data, "supplierPartnerId", blank(in.supplierPartnerId()));
        put(data, "purchasedOn", in.purchasedOn());
        put(data, "invoiceReference", FleetVehicleService.trim(in.invoiceReference()));
        put(data, "productId", blank(in.productId()));
        put(data, "stockLocationId", blank(in.stockLocationId()));
        put(data, "newTreadDepthMm", in.newTreadDepthMm());
        put(data, "minTreadDepthMm", in.minTreadDepthMm());
        put(data, "notes", FleetVehicleService.trim(in.notes()));
        for (String f : clear) {
            data.put(f, null);
        }
        if (data.isEmpty()) {
            return get(id);
        }
        if (before.get("stockIssuedAt") != null && (data.containsKey("productId")
                && !java.util.Objects.equals(data.get("productId"), before.get("productId")))) {
            throw ApiException.badRequest("Tyre " + serial0 + " was already issued from stock; its product cannot change");
        }
        if (data.get("serialNumber") != null) {
            assertUniqueSerial((String) data.get("serialNumber"), id);
        }
        assertRefs((String) data.get("supplierPartnerId"), (String) data.get("productId"),
                (String) data.get("stockLocationId"));
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetTyre.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, after));
        return get(id);
    }

    /** Soft delete of a tyre that was never fitted (otherwise scrap it). */
    @Transactional
    public void delete(String id) {
        Map<String, Object> t = tyre(id, true);
        long history = db.jdbc().sql("SELECT count(*) FROM \"FleetTyreEvent\" WHERE \"organizationId\" = ? AND"
                + " \"tyreId\" = ? AND \"eventType\" <> 'registered'").params(FleetDb.org(), id).query(Long.class)
                .single();
        if (history > 0 || !TyrePolicy.IN_STOCK.equals(t.get("status"))) {
            throw ApiException.badRequest("Tyre " + t.get("serialNumber") + " has a fitment history; scrap it instead");
        }
        db.update(FleetTyre.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "delete", t, null));
    }

    // ------------------------------------------------------------------ lifecycle

    /** Fit a tyre to a free wheel position; the first fitment of a stocked tyre issues it from stock (once). */
    @Transactional
    public Map<String, Object> fit(String id, FitInput in) {
        Map<String, Object> t = tyre(id, true);
        String serial = (String) t.get("serialNumber");
        String status = (String) t.get("status");
        if (TyrePolicy.FITTED.equals(status)) {
            Map<String, Object> on = vehicles.require((String) t.get("vehicleId"), false);
            throw ApiException.badRequest("Tyre " + serial + " is already fitted to vehicle " + on.get("code") + " at "
                    + t.get("position") + "; rotate or remove it first");
        }
        TyrePolicy.assertTransition(serial, status, TyrePolicy.FITTED);
        Map<String, Object> v = vehicles.require(in.vehicleId(), true);
        String code = (String) v.get("code");
        if (VehicleStatusPolicy.DISPOSED.equals(v.get("status"))) {
            throw ApiException.badRequest("Vehicle " + code + " is disposed; tyres can no longer be fitted to it");
        }
        String position = in.position().trim().toUpperCase(java.util.Locale.ROOT);
        assertPosition(v, position);
        String occupant = occupant(in.vehicleId(), position, null);
        if (occupant != null) {
            throw ApiException.conflict("Position " + position + " of vehicle " + code + " already has tyre " + occupant
                    + "; remove or rotate it first");
        }
        Instant at = at(in.at());
        Meters m = meters(v, in.odometer(), in.hours(), at);
        Map<String, Object> data = Obj.of("status", TyrePolicy.FITTED, "vehicleId", in.vehicleId(), "position",
                position, "fittedAt", at, "fitOdometer", m.odometerLifetime(), "fitHours", m.hoursLifetime(),
                "updatedBy", TenantContext.userId());
        if (in.treadDepthMm() != null) {
            data.put("treadDepthMm", in.treadDepthMm());
            data.put("treadMeasuredAt", at);
        }
        db.update(FleetTyre.class, id, data);
        event(id, "fitted", in.vehicleId(), null, position, at, m, null, null, in.treadDepthMm(), null, null,
                null, m.readingId(), FleetVehicleService.trim(in.notes()));
        if (in.treadDepthMm() != null) {
            reading(id, in.vehicleId(), position, at, in.treadDepthMm(), null, m.odometerLifetime(), "At fitment");
        }
        if (t.get("productId") != null && t.get("stockIssuedAt") == null) {
            issueFromStock(t, v, at);
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "assign", Obj.of("status", status),
                Obj.of("status", TyrePolicy.FITTED, "vehicleId", in.vehicleId(), "position", position)));
        return get(id);
    }

    /** Move a fitted tyre to another position of its vehicle; a tyre already there swaps to the vacated position. */
    @Transactional
    public Map<String, Object> rotate(String id, RotateInput in) {
        Map<String, Object> t = tyre(id, true);
        String serial = (String) t.get("serialNumber");
        if (!TyrePolicy.FITTED.equals(t.get("status"))) {
            throw ApiException.badRequest("Tyre " + serial + " is not fitted; fit it to a vehicle first");
        }
        String vehicleId = (String) t.get("vehicleId");
        Map<String, Object> v = vehicles.require(vehicleId, true);
        String from = (String) t.get("position");
        String to = in.position().trim().toUpperCase(java.util.Locale.ROOT);
        if (to.equals(from)) {
            throw ApiException.badRequest("Tyre " + serial + " is already at position " + to);
        }
        assertPosition(v, to);
        Instant at = at(in.at());
        assertAfterFitment(t, at);
        Meters m = meters(v, in.odometer(), in.hours(), at);
        String otherId = db.jdbc().sql("SELECT id FROM \"FleetTyre\" WHERE \"organizationId\" = ? AND \"vehicleId\" = ?"
                        + " AND position = ? AND status = 'fitted' AND \"deletedAt\" IS NULL FOR UPDATE")
                .params(FleetDb.org(), vehicleId, to).query(String.class).optional().orElse(null);
        String notes = FleetVehicleService.trim(in.notes());
        if (otherId != null) {
            Map<String, Object> other = tyre(otherId, true);
            // The partial unique index (vehicle, position) is checked per row: park the moving tyre first.
            db.update(FleetTyre.class, id, Obj.of("position", "~" + from));
            moveStint(other, from, at, m, notes);
        }
        moveStint(t, to, at, m, notes);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", Obj.of("position", from),
                Obj.of("position", to, "swappedWith", otherId)));
        return get(id);
    }

    /** Close the running stint of {@code t} at {@code m} and continue it at {@code to} on the same vehicle. */
    private void moveStint(Map<String, Object> t, String to, Instant at, Meters m, String notes) {
        String id = (String) t.get("id");
        BigDecimal km = TyrePolicy.stint(dec(t, "fitOdometer"), m.odometerLifetime());
        BigDecimal hours = TyrePolicy.stint(dec(t, "fitHours"), m.hoursLifetime());
        db.update(FleetTyre.class, id, Obj.of("position", to, "kmRun", SqlRows.dec(t, "kmRun").add(km),
                "hoursRun", SqlRows.dec(t, "hoursRun").add(hours), "fitOdometer", m.odometerLifetime(),
                "fitHours", m.hoursLifetime(), "updatedBy", TenantContext.userId()));
        event(id, "rotated", (String) t.get("vehicleId"), (String) t.get("position"), to, at, m, km, hours, null,
                null, null, null, m.readingId(), notes);
    }

    /** Take a tyre off its vehicle (status {@code removed}, or {@code scrapped} when {@code scrap}). */
    @Transactional
    public Map<String, Object> remove(String id, RemoveInput in) {
        Map<String, Object> t = tyre(id, true);
        String serial = (String) t.get("serialNumber");
        if (!TyrePolicy.FITTED.equals(t.get("status"))) {
            throw ApiException.badRequest("Tyre " + serial + " is not fitted to a vehicle");
        }
        String vehicleId = (String) t.get("vehicleId");
        Map<String, Object> v = vehicles.require(vehicleId, true);
        Instant at = at(in.at());
        assertAfterFitment(t, at);
        Meters m = meters(v, in.odometer(), in.hours(), at);
        BigDecimal km = TyrePolicy.stint(dec(t, "fitOdometer"), m.odometerLifetime());
        BigDecimal hours = TyrePolicy.stint(dec(t, "fitHours"), m.hoursLifetime());
        boolean scrap = Boolean.TRUE.equals(in.scrap());
        String reason = in.reason().trim();
        Map<String, Object> data = Obj.of("status", scrap ? TyrePolicy.SCRAPPED : TyrePolicy.REMOVED,
                "vehicleId", null, "position", null, "fittedAt", null, "fitOdometer", null, "fitHours", null,
                "kmRun", SqlRows.dec(t, "kmRun").add(km), "hoursRun", SqlRows.dec(t, "hoursRun").add(hours),
                "updatedBy", TenantContext.userId());
        if (in.treadDepthMm() != null) {
            data.put("treadDepthMm", in.treadDepthMm());
            data.put("treadMeasuredAt", at);
        }
        if (scrap) {
            data.put("scrappedAt", at);
            data.put("scrapReason", reason);
        }
        db.update(FleetTyre.class, id, data);
        event(id, "removed", vehicleId, (String) t.get("position"), null, at, m, km, hours, in.treadDepthMm(), null,
                null, reason, m.readingId(), FleetVehicleService.trim(in.notes()));
        if (in.treadDepthMm() != null) {
            reading(id, vehicleId, (String) t.get("position"), at, in.treadDepthMm(), null, m.odometerLifetime(),
                    "At removal");
        }
        if (scrap) {
            event(id, "scrapped", vehicleId, null, null, at, null, null, null, null, null, null, reason, null, null);
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "unassign", Obj.of("vehicleId", vehicleId, "position",
                t.get("position")), Obj.of("status", data.get("status"), "reason", reason)));
        return get(id);
    }

    /** Record a retread of a removed tyre: the cost adds to the tyre, its tread (and wear base) starts again. */
    @Transactional
    public Map<String, Object> retread(String id, RetreadInput in) {
        Map<String, Object> t = tyre(id, true);
        String serial = (String) t.get("serialNumber");
        TyrePolicy.assertTransition(serial, (String) t.get("status"), TyrePolicy.RETREADED);
        if (FleetVehicleService.notBlank(in.partnerId()) && !db.exists("Partner", in.partnerId(), true)) {
            throw ApiException.badRequest("Retreader / supplier not found");
        }
        Instant at = at(in.at());
        db.update(FleetTyre.class, id, Obj.of("status", TyrePolicy.RETREADED,
                "extraCost", SqlRows.dec(t, "extraCost").add(in.cost()),
                "retreadCount", ((Number) t.get("retreadCount")).intValue() + 1,
                "newTreadDepthMm", in.newTreadDepthMm(), "treadDepthMm", in.newTreadDepthMm(), "treadMeasuredAt", at,
                "treadKmBase", SqlRows.dec(t, "kmRun"), "updatedBy", TenantContext.userId()));
        event(id, "retreaded", null, null, null, at, null, null, null, in.newTreadDepthMm(), in.cost(),
                blank(in.partnerId()), null, null, FleetVehicleService.trim(in.notes()));
        reading(id, null, null, at, in.newTreadDepthMm(), null, null, "After retread");
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", Obj.of("status", t.get("status")),
                Obj.of("status", TyrePolicy.RETREADED, "cost", in.cost())));
        return get(id);
    }

    /** Scrap a tyre that is in stock, removed or retreaded (a fitted tyre is removed with {@code scrap} instead). */
    @Transactional
    public Map<String, Object> scrap(String id, ScrapInput in) {
        Map<String, Object> t = tyre(id, true);
        String serial = (String) t.get("serialNumber");
        if (TyrePolicy.FITTED.equals(t.get("status"))) {
            throw ApiException.badRequest("Tyre " + serial + " is fitted; remove it from the vehicle to scrap it");
        }
        TyrePolicy.assertTransition(serial, (String) t.get("status"), TyrePolicy.SCRAPPED);
        Instant at = at(in.at());
        String reason = in.reason().trim();
        db.update(FleetTyre.class, id, Obj.of("status", TyrePolicy.SCRAPPED, "scrappedAt", at, "scrapReason", reason,
                "updatedBy", TenantContext.userId()));
        event(id, "scrapped", null, null, null, at, null, null, null, null, null, null, reason, null, null);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", Obj.of("status", t.get("status")),
                Obj.of("status", TyrePolicy.SCRAPPED, "reason", reason)));
        return get(id);
    }

    /** Tread-depth reading (the latest reading becomes the tyre's current tread). */
    @Transactional
    public Map<String, Object> addReading(String id, TreadReadingInput in) {
        Map<String, Object> t = tyre(id, true);
        if (TyrePolicy.SCRAPPED.equals(t.get("status"))) {
            throw ApiException.badRequest("Tyre " + t.get("serialNumber") + " is scrapped; no further readings");
        }
        Instant at = at(in.readingAt());
        BigDecimal odometer = null;
        String vehicleId = (String) t.get("vehicleId");
        if (vehicleId != null) {
            odometer = lifetime(vehicles.require(vehicleId, false), "odometer");
        }
        Map<String, Object> reading = reading(id, vehicleId, (String) t.get("position"), at, in.treadDepthMm(),
                in.pressureKpa(), odometer, FleetVehicleService.trim(in.notes()));
        Instant measured = (Instant) t.get("treadMeasuredAt");
        if (measured == null || !at.isBefore(measured)) {
            db.update(FleetTyre.class, id, Obj.of("treadDepthMm", in.treadDepthMm(), "treadMeasuredAt", at));
        }
        Map<String, Object> out = new LinkedHashMap<>(reading);
        out.put("belowMinimum", in.treadDepthMm().compareTo(SqlRows.dec(t, "minTreadDepthMm")) <= 0);
        out.put("tyre", get(id));
        return out;
    }

    // ------------------------------------------------------------------ stock issue + GL

    private void issueFromStock(Map<String, Object> t, Map<String, Object> v, Instant at) {
        String id = (String) t.get("id");
        String serial = (String) t.get("serialNumber");
        String productId = (String) t.get("productId");
        String location = t.get("stockLocationId") != null ? (String) t.get("stockLocationId")
                : (String) v.get("homeLocationId");
        if (location == null) {
            throw ApiException.badRequest("Choose the stock location to issue tyre " + serial
                    + " from (on the tyre, or the vehicle's home site)");
        }
        Map<String, Object> product = SqlRows.one(db.jdbc().sql("SELECT id, name, \"serialTracking\" FROM \"Product\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(productId, FleetDb.org()));
        if (product == null) {
            throw ApiException.badRequest("The stock product of tyre " + serial + " no longer exists");
        }
        String code = (String) v.get("code");
        StockMove move = StockMove.of(productId, location, BigDecimal.ONE).source("fleet_tyre", id)
                .reference("Tyre " + serial + " fitted to " + code).moveType("internal_use").skipGl().date(at)
                .notes("Fleet tyre fitment");
        if (Boolean.TRUE.equals(product.get("serialTracking"))) {
            move.serials(List.of(serial));
        }
        StockMove.Issued issued = stock.issue(move);
        BigDecimal value = issued.totalValue() == null ? BigDecimal.ZERO
                : issued.totalValue().setScale(2, RoundingMode.HALF_UP);
        String journalId = null;
        if (value.signum() > 0) {
            String expense = accounts.ensureByCode("6430", "Fleet tyres", "operating_expense", "fleet_tyre_expense");
            String stockAccount = stockAccount(productId, value);
            String desc = "Tyre " + serial + " fitted to " + code;
            Map<String, Object> dims = Obj.of("vehicleId", v.get("id"), "tyreId", id);
            Map<String, Object> entry = posting.post(new PostingRequest("INV", at, desc, null, null, "fleet_tyre", id,
                    null, "fleet:tyre:issue:" + id, null, null, null, List.of(
                            new Line(expense, value, null, null, desc, null, (String) v.get("costCenterId"), dims),
                            new Line(stockAccount, null, value, null, desc, null, null, null))));
            journalId = (String) entry.get("id");
        }
        Map<String, Object> data = Obj.of("stockIssuedAt", Instant.now(), "stockLedgerCode", issued.ledgerCode(),
                "stockIssueValue", value, "issueJournalEntryId", journalId);
        if (t.get("purchaseCost") == null) {
            data.put("purchaseCost", value);
        }
        db.update(FleetTyre.class, id, data);
    }

    /** The product's stock account: the debit side of its stock-in posting rule (falls back to stock valuation). */
    private String stockAccount(String productId, BigDecimal value) {
        for (Line l : stockPosting.receiptLines(productId, value)) {
            if (l.debit() != null) {
                return l.accountId();
            }
        }
        return accounts.byMapping("stock_valuation");
    }

    // ------------------------------------------------------------------ helpers

    /** Meter values of a tyre event: lifetime km / hours (recorded on the vehicle when given, else its current). */
    record Meters(BigDecimal odometer, BigDecimal odometerLifetime, BigDecimal hours, BigDecimal hoursLifetime,
            String readingId) {}

    private Meters meters(Map<String, Object> v, BigDecimal odometer, BigDecimal hours, Instant at) {
        String vehicleId = (String) v.get("id");
        String readingId = null;
        BigDecimal odoLife = lifetime(v, "odometer");
        BigDecimal hoursLife = lifetime(v, "hours");
        String minute = at.truncatedTo(ChronoUnit.MINUTES).toString();
        if (odometer != null) {
            Map<String, Object> r = meters.recordAutomated(vehicleId, "odometer", odometer, at, "tyre",
                    "odo:" + odometer.stripTrailingZeros().toPlainString() + "@" + minute, "Tyre change");
            odoLife = SqlRows.dec(r, "lifetimeValue");
            readingId = (String) r.get("id");
        }
        if (hours != null) {
            Map<String, Object> r = meters.recordAutomated(vehicleId, "hours", hours, at, "tyre",
                    "hrs:" + hours.stripTrailingZeros().toPlainString() + "@" + minute, "Tyre change");
            hoursLife = SqlRows.dec(r, "lifetimeValue");
            readingId = readingId == null ? (String) r.get("id") : readingId;
        }
        return new Meters(odometer, odoLife, hours, hoursLife, readingId);
    }

    static BigDecimal lifetime(Map<String, Object> v, String meter) {
        String current = FleetMeterService.currentColumn(meter);
        if (v.get(current) == null) {
            return null;
        }
        return SqlRows.dec(v, current).add(SqlRows.dec(v, FleetMeterService.offsetColumn(meter)));
    }

    private Layout layout(Map<String, Object> v) {
        Map<String, Object> c = SqlRows.one(db.jdbc().sql("SELECT code, kind FROM \"FleetVehicleCategory\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(v.get("categoryId"), FleetDb.org()));
        return TyrePositionLayout.forVehicle((String) v.get("axleConfig"), c == null ? null : (String) c.get("kind"),
                c == null ? null : (String) c.get("code"));
    }

    private void assertPosition(Map<String, Object> v, String position) {
        Layout layout = layout(v);
        if (!layout.contains(position)) {
            throw ApiException.badRequest("Position " + position + " is not on vehicle " + v.get("code")
                    + " (axle configuration " + (layout.axleConfig() == null ? "default" : layout.axleConfig())
                    + "); use one of " + String.join(", ", layout.codes()));
        }
    }

    private String occupant(String vehicleId, String position, String exceptId) {
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), vehicleId, position));
        if (exceptId != null) {
            params.add(exceptId);
        }
        return db.jdbc().sql("SELECT \"serialNumber\" FROM \"FleetTyre\" WHERE \"organizationId\" = ? AND"
                        + " \"vehicleId\" = ? AND position = ? AND status = 'fitted' AND \"deletedAt\" IS NULL"
                        + (exceptId == null ? "" : " AND id <> ?") + " LIMIT 1")
                .params(params).query(String.class).optional().orElse(null);
    }

    private static void assertAfterFitment(Map<String, Object> t, Instant at) {
        Instant fitted = (Instant) t.get("fittedAt");
        if (fitted != null && at.isBefore(fitted)) {
            throw ApiException.badRequest("The change cannot be dated before the tyre was fitted ("
                    + MeterReadingPolicy.day(fitted) + ")");
        }
    }

    private static Instant at(Instant requested) {
        Instant now = Instant.now();
        if (requested == null) {
            return now;
        }
        if (requested.isAfter(now.plus(FUTURE_SKEW))) {
            throw ApiException.badRequest("The date cannot be in the future");
        }
        return requested;
    }

    private void event(String tyreId, String type, String vehicleId, String from, String to, Instant at, Meters m,
            BigDecimal km, BigDecimal hours, BigDecimal tread, BigDecimal cost, String partnerId, String reason,
            String readingId, String notes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tyreId", tyreId);
        data.put("eventType", type);
        data.put("vehicleId", vehicleId);
        data.put("fromPosition", from);
        data.put("toPosition", to);
        data.put("eventAt", at);
        if (m != null) {
            data.put("odometer", m.odometer());
            data.put("odometerLifetime", m.odometerLifetime());
            data.put("hours", m.hours());
            data.put("hoursLifetime", m.hoursLifetime());
        }
        data.put("kmRun", km);
        data.put("hoursRun", hours);
        data.put("treadDepthMm", tread);
        data.put("cost", cost);
        data.put("partnerId", partnerId);
        data.put("reason", reason);
        data.put("meterReadingId", readingId);
        data.put("notes", notes);
        data.put("createdBy", TenantContext.userId());
        db.insert(FleetTyreEvent.class, data);
    }

    private Map<String, Object> reading(String tyreId, String vehicleId, String position, Instant at, BigDecimal tread,
            BigDecimal pressure, BigDecimal odometer, String notes) {
        return db.insert(FleetTyreReading.class, Obj.of("tyreId", tyreId, "vehicleId", vehicleId, "position",
                position, "readingAt", at, "treadDepthMm", tread, "pressureKpa", pressure, "odometerLifetime",
                odometer, "notes", notes, "createdBy", TenantContext.userId()));
    }

    /** List / detail shape: the row, its vehicle, and live km (closed stints + running stint) with the stats. */
    private Map<String, Object> shape(Map<String, Object> r) {
        Map<String, Object> t = SqlRows.as(FleetTyre.class, r);
        BigDecimal km = SqlRows.dec(t, "kmRun");
        BigDecimal hours = SqlRows.dec(t, "hoursRun");
        BigDecimal stintKm = BigDecimal.ZERO;
        BigDecimal stintHours = BigDecimal.ZERO;
        if (TyrePolicy.FITTED.equals(t.get("status"))) {
            stintKm = TyrePolicy.stint(dec(t, "fitOdometer"), r.get("v_odometer") == null ? null
                    : SqlRows.dec(r, "v_odometer"));
            stintHours = TyrePolicy.stint(dec(t, "fitHours"), r.get("v_hours") == null ? null
                    : SqlRows.dec(r, "v_hours"));
            t.put("vehicle", Obj.of("id", t.get("vehicleId"), "code", r.get("v_code"), "registrationPlate",
                    r.get("v_plate")));
        } else {
            t.put("vehicle", null);
        }
        km = km.add(stintKm);
        hours = hours.add(stintHours);
        TyrePolicy.TyreStats s = TyrePolicy.stats(dec(t, "purchaseCost"), dec(t, "extraCost"), km, hours,
                dec(t, "newTreadDepthMm"), dec(t, "treadDepthMm"), dec(t, "minTreadDepthMm"));
        TyrePolicy.TyreStats wear = TyrePolicy.stats(null, null, km.subtract(SqlRows.dec(t, "treadKmBase")), null,
                dec(t, "newTreadDepthMm"), dec(t, "treadDepthMm"), dec(t, "minTreadDepthMm"));
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCost", s.totalCost());
        stats.put("km", km);
        stats.put("hours", hours);
        stats.put("currentStintKm", stintKm);
        stats.put("costPerKm", s.costPerKm());
        stats.put("costPerHour", s.costPerHour());
        stats.put("treadUsedMm", wear.treadUsedMm());
        stats.put("wearPer1000Km", wear.wearPer1000Km());
        stats.put("projectedLifeKm", wear.projectedLifeKm() == null ? null
                : wear.projectedLifeKm().add(SqlRows.dec(t, "treadKmBase")));
        stats.put("remainingTreadPct", wear.remainingTreadPct());
        stats.put("belowMinimum", t.get("treadDepthMm") != null
                && SqlRows.dec(t, "treadDepthMm").compareTo(SqlRows.dec(t, "minTreadDepthMm")) <= 0);
        t.put("stats", stats);
        return t;
    }

    private Map<String, Object> row(String id) {
        Map<String, Object> r = SqlRows.one(db.jdbc().sql(SELECT + " WHERE t.id = ? AND t.\"organizationId\" = ? AND"
                + " t.\"deletedAt\" IS NULL").params(id, FleetDb.org()));
        if (r == null) {
            throw ApiException.notFound("Tyre not found");
        }
        return r;
    }

    private Map<String, Object> tyre(String id, boolean lock) {
        return db.require(FleetTyre.class, id, lock, "Tyre not found");
    }

    private Map<String, Object> ref(String sql, Object id) {
        return id == null ? null : SqlRows.one(db.jdbc().sql(sql).params(id, FleetDb.org()));
    }

    private void assertUniqueSerial(String serial, String exceptId) {
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), serial));
        if (exceptId != null) {
            params.add(exceptId);
        }
        long n = db.jdbc().sql("SELECT count(*) FROM \"FleetTyre\" WHERE \"organizationId\" = ? AND \"serialNumber\" = ?"
                + " AND \"deletedAt\" IS NULL" + (exceptId == null ? "" : " AND id <> ?")).params(params)
                .query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict("A tyre with serial number " + serial + " already exists");
        }
    }

    private void assertRefs(String partnerId, String productId, String locationId) {
        if (FleetVehicleService.notBlank(partnerId) && !db.exists("Partner", partnerId, true)) {
            throw ApiException.badRequest("Supplier not found");
        }
        if (FleetVehicleService.notBlank(productId) && !db.exists("Product", productId, true)) {
            throw ApiException.badRequest("Stock product not found");
        }
        if (FleetVehicleService.notBlank(locationId) && !db.exists("InventoryLocation", locationId, true)) {
            throw ApiException.badRequest("Stock location not found");
        }
    }

    /** Upper case without surrounding / repeated spaces. */
    static String normalizeSerial(String serial) {
        return serial.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static BigDecimal dec(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : SqlRows.dec(row, key);
    }

    private static void filter(StringBuilder where, List<Object> params, String column, String value) {
        if (FleetVehicleService.notBlank(value)) {
            where.append(" AND ").append(column).append(" = ?");
            params.add(value);
        }
    }

    private static void put(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
