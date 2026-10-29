package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FuelCommands.DipInput;
import com.inventoryerp.fleet.application.FuelCommands.ReconcileInput;
import com.inventoryerp.fleet.application.FuelCommands.TankInput;
import com.inventoryerp.fleet.application.FuelCommands.TankPatch;
import com.inventoryerp.fleet.domain.FleetFuelTank;
import com.inventoryerp.fleet.domain.FleetTankDip;
import com.inventoryerp.fleet.domain.FleetVehicle;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Adjusted;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.approvals.ApprovalAdminService;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Site fuel tanks and bowsers (fleet 1.5). A tank is an inventory location holding one fuel product (litres = the
 * product's base unit): its <b>book level</b> is that location's stock. Fuel is bought into it through the normal
 * procurement goods receipt (GRN) into the tank's location — there is no fleet purchase flow — and leaves it through
 * fuel logs with {@code source = tank} (stock issues, {@link FleetFuelPostingService}).
 *
 * <p><b>Dips</b> record the measured litres against the book level at the dip time (inventory ledger as of then).
 * <b>Reconciliation</b> adjusts stock by the variance through {@link StockService#adjust} (inventory's adjustment
 * gain / loss posting); a variance above the org thresholds ({@code FleetFuelSettings}) first needs approval through
 * the kernel approval engine (entity type {@value #APPROVAL_ENTITY}; default workflow created on first use, approver
 * permission {@code fleet_fuel:post}, the requester cannot approve).
 */
@Service
public class FleetFuelTankService {

    public static final String APPROVAL_ENTITY = "fleet_tank_reconciliation";
    static final String AUDIT_ENTITY = "FleetFuelTank";
    static final String DIP_SOURCE = "fleet_tank_dip";

    private final FleetDb db;
    private final StockService stock;
    private final ApprovalsService approvals;
    private final ApprovalAdminService approvalAdmin;
    private final FleetFuelSettingsService settings;
    private final SequenceService sequence;
    private final AuditService audit;

    public FleetFuelTankService(FleetDb db, StockService stock, ApprovalsService approvals,
            ApprovalAdminService approvalAdmin, FleetFuelSettingsService settings, SequenceService sequence,
            AuditService audit) {
        this.db = db;
        this.stock = stock;
        this.approvals = approvals;
        this.approvalAdmin = approvalAdmin;
        this.settings = settings;
        this.sequence = sequence;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT t.*, l.code AS "l_code", l.name AS "l_name", p.code AS "p_code", p.name AS "p_name",
                   b.code AS "b_code", b.name AS "b_name", bv.code AS "bv_code",
                   COALESCE(s.quantity, 0) AS "_level", COALESCE(s."runningAverageCost", 0) AS "_avgCost",
                   (SELECT count(*) FROM "FleetFuelLog" d WHERE d."organizationId" = t."organizationId"
                      AND d."tankId" = t.id AND d.status = 'draft' AND d."deletedAt" IS NULL) AS "_drafts"
            FROM "FleetFuelTank" t
            LEFT JOIN "InventoryLocation" l ON l.id = t."locationId" AND l."organizationId" = t."organizationId"
            LEFT JOIN "Product" p ON p.id = t."productId" AND p."organizationId" = t."organizationId"
            LEFT JOIN "Branch" b ON b.id = t."branchId" AND b."organizationId" = t."organizationId"
            LEFT JOIN "FleetVehicle" bv ON bv.id = t."bowserVehicleId" AND bv."organizationId" = t."organizationId"
            LEFT JOIN "StockItem" s ON s."organizationId" = t."organizationId" AND s."productId" = t."productId"
                 AND s."locationId" = t."locationId" AND s."variantKey" = ''
            """;

    // ------------------------------------------------------------------ tanks

    /** Tanks with book level, fill %, value and last dip ({@code includeInactive} adds switched-off tanks). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(boolean includeInactive) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(SELECT + " WHERE t.\"organizationId\" = ? AND"
                + " t.\"deletedAt\" IS NULL" + (includeInactive ? "" : " AND t.\"isActive\" = true")
                + " ORDER BY t.code").param(FleetDb.org()))) {
            out.add(render(r));
        }
        return out;
    }

    /** One tank with level and its latest dips. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> out = render(row(id));
        out.put("recentDips", dips(id, 5));
        return out;
    }

    private Map<String, Object> row(String id) {
        Map<String, Object> r = SqlRows.one(db.jdbc().sql(SELECT + " WHERE t.id = ? AND t.\"organizationId\" = ? AND"
                + " t.\"deletedAt\" IS NULL").params(id, FleetDb.org()));
        if (r == null) {
            throw ApiException.notFound("Fuel tank not found");
        }
        return r;
    }

    /** The tank row (404 when unknown) — locked when {@code lock}. */
    Map<String, Object> require(String id, boolean lock) {
        return db.require(FleetFuelTank.class, id, lock, "Fuel tank not found");
    }

    /** Register a tank on an existing inventory location (one tank per location) and fuel product. */
    @Transactional
    public Map<String, Object> create(TankInput in) {
        if (!db.exists("InventoryLocation", in.locationId(), true)) {
            throw ApiException.badRequest("Inventory location not found");
        }
        if (!db.exists("Product", in.productId(), true)) {
            throw ApiException.badRequest("Fuel product not found");
        }
        String holder = db.jdbc().sql("SELECT code FROM \"FleetFuelTank\" WHERE \"organizationId\" = ? AND"
                + " \"locationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1").params(FleetDb.org(), in.locationId())
                .query(String.class).optional().orElse(null);
        if (holder != null) {
            throw ApiException.conflict("This location already holds fuel tank " + holder);
        }
        assertRefs(in.branchId(), in.bowserVehicleId());
        assertLevels(in.capacityL(), in.reorderLevelL());
        String code = FleetVehicleService.notBlank(in.code()) ? FleetCategoryService.normalizeCode(in.code())
                : nextCode();
        assertUniqueCode(code, null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", in.name().trim());
        data.put("kind", in.kind() == null ? "tank" : in.kind());
        data.put("locationId", in.locationId());
        data.put("productId", in.productId());
        data.put("capacityL", in.capacityL());
        data.put("reorderLevelL", in.reorderLevelL());
        data.put("branchId", blank(in.branchId()));
        data.put("bowserVehicleId", blank(in.bowserVehicleId()));
        data.put("isActive", in.isActive() == null || in.isActive());
        data.put("notes", FleetVehicleService.trim(in.notes()));
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> created = db.insert(FleetFuelTank.class, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, (String) created.get("id"), "create", null, created));
        return get((String) created.get("id"));
    }

    /** Edit a tank (location and product are fixed). */
    @Transactional
    public Map<String, Object> update(String id, TankPatch in) {
        Map<String, Object> before = require(id, true);
        List<String> clear = in.clear() == null ? List.of() : in.clear();
        for (String f : clear) {
            if (!FuelCommands.TANK_CLEARABLE.contains(f)) {
                throw ApiException.badRequest("Field " + f + " cannot be cleared");
            }
        }
        assertRefs(in.branchId(), in.bowserVehicleId());
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.code() != null) {
            String code = FleetCategoryService.normalizeCode(in.code());
            assertUniqueCode(code, id);
            data.put("code", code);
        }
        put(data, "name", FleetVehicleService.trim(in.name()));
        put(data, "kind", in.kind());
        put(data, "capacityL", in.capacityL());
        put(data, "reorderLevelL", in.reorderLevelL());
        put(data, "branchId", blank(in.branchId()));
        put(data, "bowserVehicleId", blank(in.bowserVehicleId()));
        put(data, "isActive", in.isActive());
        put(data, "notes", FleetVehicleService.trim(in.notes()));
        for (String f : clear) {
            data.put(f, null);
        }
        if (data.isEmpty()) {
            return get(id);
        }
        Map<String, Object> merged = new LinkedHashMap<>(before);
        merged.putAll(data);
        assertLevels((BigDecimal) merged.get("capacityL"), (BigDecimal) merged.get("reorderLevelL"));
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetFuelTank.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, after));
        return get(id);
    }

    /** Soft delete of a tank without fuel logs or dips (otherwise deactivate it). */
    @Transactional
    public void delete(String id) {
        Map<String, Object> t = require(id, true);
        long used = db.jdbc().sql("SELECT (SELECT count(*) FROM \"FleetFuelLog\" WHERE \"organizationId\" = ? AND"
                + " \"tankId\" = ? AND \"deletedAt\" IS NULL) + (SELECT count(*) FROM \"FleetTankDip\" WHERE"
                + " \"organizationId\" = ? AND \"tankId\" = ?)").params(FleetDb.org(), id, FleetDb.org(), id)
                .query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("Fuel tank " + t.get("code") + " has fuel logs or dips; deactivate it"
                    + " instead");
        }
        db.update(FleetFuelTank.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "delete", t, null));
    }

    /** Book litres of the tank location now. */
    BigDecimal level(Map<String, Object> tank) {
        return db.jdbc().sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ?").params(FleetDb.org(), tank.get("productId"),
                        tank.get("locationId")).query(BigDecimal.class).single();
    }

    /** Book litres of the tank location as of {@code at} (inventory ledger up to then). */
    BigDecimal levelAt(Map<String, Object> tank, Instant at) {
        return db.jdbc().sql("SELECT COALESCE(SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ? AND \"createdAt\" <= ?")
                .params(FleetDb.org(), tank.get("productId"), tank.get("locationId"), SqlRows.ts(at))
                .query(BigDecimal.class).single();
    }

    /**
     * Stock movements of the tank (inventory ledger, newest first): GRN receipts, issues to vehicles (with the fuel
     * log), dip adjustments, reversals.
     */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> movements(String id, Integer page, Integer pageSize) {
        Map<String, Object> t = require(id, false);
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null ? 20 : Math.max(1, Math.min(200, pageSize));
        String org = FleetDb.org();
        long total = db.jdbc().sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ?").params(org, t.get("productId"), t.get("locationId"))
                .query(Long.class).single();
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("""
                SELECT l.id, l."ledgerCode", l.type::text AS type, l."quantityChange", l."balanceAfter", l."unitCost",
                       l."totalValue", l."referenceType", l."referenceId", l.notes, l."createdAt",
                       f.code AS "fuelLogCode", v.code AS "vehicleCode"
                FROM "InventoryLedger" l
                LEFT JOIN "FleetFuelLog" f ON f.id = l."referenceId" AND f."organizationId" = l."organizationId"
                     AND l."referenceType" IN ('fleet_fuel_issue', 'fleet_fuel_issue_reversal')
                LEFT JOIN "FleetVehicle" v ON v.id = f."vehicleId"
                WHERE l."organizationId" = ? AND l."productId" = ? AND l."locationId" = ?
                ORDER BY l."createdAt" DESC, l.id DESC LIMIT ? OFFSET ?""")
                .params(org, t.get("productId"), t.get("locationId"), size, (p - 1) * size));
        return PageResult.of(rows, p, size, total);
    }

    // ------------------------------------------------------------------ dips

    /** Dips of a tank, newest first ({@code limit} rows). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> dips(String tankId, int limit) {
        require(tankId, false);
        return SqlRows.list(db.jdbc().sql("""
                SELECT d.*, u."firstName" AS "recordedByFirstName", u."lastName" AS "recordedByLastName"
                FROM "FleetTankDip" d LEFT JOIN "User" u ON u.id = d."recordedBy"
                WHERE d."organizationId" = ? AND d."tankId" = ? ORDER BY d."dippedAt" DESC, d."createdAt" DESC
                LIMIT ?""").params(FleetDb.org(), tankId, Math.max(1, Math.min(500, limit))));
    }

    /** One dip (404 when unknown). */
    @Transactional(readOnly = true)
    public Map<String, Object> dip(String id) {
        return db.require(FleetTankDip.class, id, false, "Tank dip not found");
    }

    /** Record a dip: book level at the dip time and the variance dip − book. */
    @Transactional
    public Map<String, Object> recordDip(String tankId, DipInput in) {
        Map<String, Object> t = require(tankId, true);
        Instant at = in.dippedAt() == null ? Instant.now() : in.dippedAt();
        if (at.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("Dip time cannot be in the future");
        }
        BigDecimal capacity = SqlRows.dec(t, "capacityL");
        if (in.dipLitres().compareTo(capacity) > 0) {
            throw ApiException.badRequest("Dip " + FuelEfficiencyPolicy.plain(in.dipLitres()) + " L is above the tank"
                    + " capacity of " + FuelEfficiencyPolicy.plain(capacity) + " L");
        }
        BigDecimal book = levelAt(t, at).setScale(3, RoundingMode.HALF_UP);
        Map<String, Object> dip = db.insert(FleetTankDip.class, Obj.of(
                "tankId", tankId,
                "dippedAt", at,
                "dipLitres", in.dipLitres(),
                "bookLitres", book,
                "varianceLitres", in.dipLitres().subtract(book),
                "notes", FleetVehicleService.trim(in.notes()),
                "recordedBy", TenantContext.userId(),
                "status", "open"));
        audit.recordInTx(AuditInput.of("FleetTankDip", (String) dip.get("id"), "measure", null, dip));
        return dip;
    }

    /**
     * Reconcile a dip: no variance → {@code accepted}; a variance within the thresholds is adjusted in stock at once;
     * a larger one waits for approval ({@code pending_approval}) and is applied when approved.
     */
    @Transactional
    public Map<String, Object> reconcile(String dipId, ReconcileInput in) {
        Map<String, Object> d = db.require(FleetTankDip.class, dipId, true, "Tank dip not found");
        Map<String, Object> t = require((String) d.get("tankId"), true);
        if (!"open".equals(d.get("status"))) {
            throw ApiException.badRequest("This dip is already " + d.get("status").toString().replace('_', ' '));
        }
        BigDecimal variance = SqlRows.dec(d, "varianceLitres");
        String reason = in == null ? null : FleetVehicleService.trim(in.reason());
        Instant now = Instant.now();
        if (variance.signum() == 0) {
            Map<String, Object> out = db.update(FleetTankDip.class, dipId, Obj.of("status", "accepted", "reason", reason,
                    "reconciledBy", TenantContext.userId(), "reconciledAt", now));
            audit.recordInTx(AuditInput.of("FleetTankDip", dipId, "reconcile", d, out));
            return out;
        }
        if (reason == null) {
            throw ApiException.badRequest("A reason is required to reconcile a dip variance");
        }
        long pending = db.jdbc().sql("SELECT count(*) FROM \"FleetTankDip\" WHERE \"organizationId\" = ? AND"
                + " \"tankId\" = ? AND status = 'pending_approval'").params(FleetDb.org(), t.get("id"))
                .query(Long.class).single();
        if (pending > 0) {
            throw ApiException.conflict("A reconciliation of tank " + t.get("code") + " is already waiting for"
                    + " approval");
        }
        db.update(FleetTankDip.class, dipId, Obj.of("reason", reason, "reconciledBy", TenantContext.userId(),
                "reconciledAt", now));
        audit.recordInTx(AuditInput.of("FleetTankDip", dipId, "reconcile", d, Obj.of("reason", reason,
                "varianceLitres", variance)));
        if (!needsApproval(variance, SqlRows.dec(t, "capacityL"))) {
            assertApplicable(t, variance);
            return apply(dipId, t, variance, null);
        }
        ensureWorkflow();
        BigDecimal unit = unitCost(t);
        Map<String, Object> snapshot = Obj.of("amount", variance.abs().multiply(unit).setScale(2, RoundingMode.HALF_UP),
                "tank", t.get("code"), "varianceLitres", FuelEfficiencyPolicy.plain(variance),
                "dipLitres", FuelEfficiencyPolicy.plain(SqlRows.dec(d, "dipLitres")),
                "bookLitres", FuelEfficiencyPolicy.plain(SqlRows.dec(d, "bookLitres")), "reason", reason);
        Map<String, Object> gate = approvals.checkOrRequestApproval(APPROVAL_ENTITY, dipId, snapshot);
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            return db.update(FleetTankDip.class, dipId, Obj.of("status", "pending_approval", "approvalRequestId",
                    gate.get("requestId")));
        }
        // No active workflow (switched off by an administrator): the engine auto-approves.
        assertApplicable(t, variance);
        return apply(dipId, t, variance, null);
    }

    /** Apply the approval engine's decision on a pending reconciliation (idempotent). */
    @Transactional
    public Map<String, Object> applyDecision(String dipId, boolean approved, String deciderId) {
        Map<String, Object> d = db.require(FleetTankDip.class, dipId, true, "Tank dip not found");
        if (!"pending_approval".equals(d.get("status"))) {
            return d;
        }
        Map<String, Object> t = require((String) d.get("tankId"), true);
        Instant now = Instant.now();
        if (!approved) {
            Map<String, Object> out = db.update(FleetTankDip.class, dipId, Obj.of("status", "rejected", "decidedBy",
                    deciderId, "decidedAt", now));
            audit.recordInTx(AuditInput.of("FleetTankDip", dipId, "reject", d, out));
            return out;
        }
        BigDecimal variance = SqlRows.dec(d, "varianceLitres");
        String problem = applicability(t, variance);
        if (problem != null) {
            Map<String, Object> out = db.update(FleetTankDip.class, dipId, Obj.of("status", "failed", "failureReason",
                    problem, "decidedBy", deciderId, "decidedAt", now));
            audit.recordInTx(AuditInput.of("FleetTankDip", dipId, "cancel", d, out));
            return out;
        }
        return apply(dipId, t, variance, deciderId);
    }

    /** Re-read the linked approval request and apply its outcome now; refused while it is pending. */
    @Transactional
    public Map<String, Object> sync(String dipId) {
        Map<String, Object> d = db.require(FleetTankDip.class, dipId, false, "Tank dip not found");
        if (!"pending_approval".equals(d.get("status"))) {
            return d;
        }
        String requestId = (String) d.get("approvalRequestId");
        String status = db.jdbc().sql("SELECT status::text FROM \"ApprovalRequest\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(requestId, FleetDb.org()).query(String.class).optional()
                .orElse("pending");
        if ("pending".equals(status)) {
            throw ApiException.badRequest("The reconciliation is waiting for approval (request " + requestId + ")");
        }
        String decider = db.jdbc().sql("SELECT \"approverId\" FROM \"ApprovalDecision\" WHERE \"requestId\" = ? ORDER BY"
                + " \"decidedAt\" DESC LIMIT 1").param(requestId).query(String.class).optional().orElse(null);
        return applyDecision(dipId, "approved".equals(status), decider);
    }

    /** Post the variance as a stock adjustment of the tank location (count = current book + variance). */
    private Map<String, Object> apply(String dipId, Map<String, Object> t, BigDecimal variance, String deciderId) {
        BigDecimal current = lockedLevel(t);
        StockMove m = StockMove.of((String) t.get("productId"), (String) t.get("locationId"), null)
                .source(DIP_SOURCE, dipId).notes("Dip reconciliation " + t.get("code") + ": "
                        + FuelEfficiencyPolicy.plain(variance) + " L");
        m.countedQuantity = current.add(variance);
        m.approvedById = deciderId;
        Adjusted adj = stock.adjust(m);
        BigDecimal value = adj.unitCost() == null ? BigDecimal.ZERO : adj.unitCost().multiply(variance.abs());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "adjusted");
        data.put("adjustmentLedgerCode", adj.ledgerCode());
        data.put("adjustedLitres", variance);
        data.put("adjustmentValue", value);
        if (deciderId != null) {
            data.put("decidedBy", deciderId);
            data.put("decidedAt", Instant.now());
        }
        Map<String, Object> out = db.update(FleetTankDip.class, dipId, data);
        audit.recordInTx(AuditInput.of("FleetTankDip", dipId, "adjust", null, Obj.of("ledgerCode", adj.ledgerCode(),
                "varianceLitres", variance, "value", value)));
        return out;
    }

    /** Over the litre threshold or the % of capacity threshold of the org settings. */
    boolean needsApproval(BigDecimal variance, BigDecimal capacity) {
        Map<String, Object> s = settings.current();
        BigDecimal abs = variance.abs();
        if (abs.compareTo(SqlRows.dec(s, "dipApprovalLitres")) > 0) {
            return true;
        }
        return capacity.signum() > 0 && abs.multiply(BigDecimal.valueOf(100)).divide(capacity, 6, RoundingMode.HALF_UP)
                .compareTo(SqlRows.dec(s, "dipApprovalPct")) > 0;
    }

    private void assertApplicable(Map<String, Object> t, BigDecimal variance) {
        String problem = applicability(t, variance);
        if (problem != null) {
            throw ApiException.badRequest(problem);
        }
    }

    /**
     * Why the adjustment cannot be posted now (null = it can): it would take the book below zero, or the books are
     * locked today (same rule as inventory movements).
     */
    private String applicability(Map<String, Object> t, BigDecimal variance) {
        BigDecimal after = level(t).add(variance);
        if (after.signum() < 0) {
            return "The adjustment would take tank " + t.get("code") + " below zero (book "
                    + FuelEfficiencyPolicy.plain(level(t)) + " L, variance " + FuelEfficiencyPolicy.plain(variance)
                    + " L)";
        }
        Instant now = Instant.now();
        Instant lock = db.jdbc().sql("SELECT \"booksLockDate\" FROM \"Organization\" WHERE id = ?").param(FleetDb.org())
                .query(java.sql.Timestamp.class).optional().map(ts -> ts.toLocalDateTime().toInstant(ZoneOffset.UTC))
                .orElse(null);
        if (lock != null && !now.isAfter(lock)) {
            return "Books are locked; stock cannot move today";
        }
        long closed = db.jdbc().sql("SELECT count(*) FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL AND \"startDate\" <= ? AND \"endDate\" >= ? AND status IN ('closed', 'locked')")
                .params(FleetDb.org(), SqlRows.ts(now), SqlRows.ts(now)).query(Long.class).single();
        return closed > 0 ? "The current fiscal period is closed; stock cannot move today" : null;
    }

    /** Current book litres with the tank's stock row locked (serialises adjustments with issues). */
    private BigDecimal lockedLevel(Map<String, Object> t) {
        db.jdbc().sql("SELECT id FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND"
                + " \"locationId\" = ? FOR UPDATE").params(FleetDb.org(), t.get("productId"), t.get("locationId"))
                .query().listOfRows();
        return level(t);
    }

    private BigDecimal unitCost(Map<String, Object> t) {
        return db.jdbc().sql("SELECT COALESCE(max(\"runningAverageCost\"), 0) FROM \"StockItem\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ?")
                .params(FleetDb.org(), t.get("productId"), t.get("locationId")).query(BigDecimal.class).single();
    }

    /** Creates the default reconciliation workflow when the organization has none (active or not). */
    private void ensureWorkflow() {
        long n = db.jdbc().sql("SELECT count(*) FROM \"ApprovalWorkflow\" WHERE \"organizationId\" = ? AND"
                + " \"entityType\" = ?").params(FleetDb.org(), APPROVAL_ENTITY).query(Long.class).single();
        if (n == 0) {
            approvalAdmin.createWorkflow("Fuel tank reconciliation", APPROVAL_ENTITY, null, true, true,
                    List.of(new ApprovalAdminService.Step(1, "Fuel controller",
                            List.of(Permissions.FLEET_FUEL__POST), 1, null, null)));
        }
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> render(Map<String, Object> r) {
        Map<String, Object> out = SqlRows.as(FleetFuelTank.class, r);
        BigDecimal level = SqlRows.dec(r, "_level");
        BigDecimal capacity = SqlRows.dec(r, "capacityL");
        out.put("location", Obj.of("id", r.get("locationId"), "code", r.get("l_code"), "name", r.get("l_name")));
        out.put("product", Obj.of("id", r.get("productId"), "code", r.get("p_code"), "name", r.get("p_name")));
        out.put("branch", r.get("branchId") == null ? null
                : Obj.of("id", r.get("branchId"), "code", r.get("b_code"), "name", r.get("b_name")));
        out.put("bowserVehicle", r.get("bowserVehicleId") == null ? null
                : Obj.of("id", r.get("bowserVehicleId"), "code", r.get("bv_code")));
        out.put("bookLitres", level);
        out.put("fillPct", capacity.signum() > 0
                ? level.multiply(BigDecimal.valueOf(100)).divide(capacity, 1, RoundingMode.HALF_UP) : null);
        out.put("averageCost", SqlRows.dec(r, "_avgCost"));
        out.put("bookValue", level.multiply(SqlRows.dec(r, "_avgCost")).setScale(2, RoundingMode.HALF_UP));
        out.put("belowReorder", r.get("reorderLevelL") != null && level.compareTo(SqlRows.dec(r, "reorderLevelL")) < 0);
        out.put("overCapacity", level.compareTo(capacity) > 0);
        out.put("draftIssues", r.get("_drafts"));
        Map<String, Object> last = SqlRows.one(db.jdbc().sql("SELECT id, \"dippedAt\", \"dipLitres\", \"varianceLitres\","
                + " status FROM \"FleetTankDip\" WHERE \"organizationId\" = ? AND \"tankId\" = ? ORDER BY \"dippedAt\""
                + " DESC LIMIT 1").params(FleetDb.org(), r.get("id")));
        out.put("lastDip", last);
        return out;
    }

    private String nextCode() {
        for (int i = 0; i < 20; i++) {
            String code = sequence.next("fleet_fuel_tank", "TANK-", 3);
            if (db.jdbc().sql("SELECT count(*) FROM \"FleetFuelTank\" WHERE \"organizationId\" = ? AND code = ? AND"
                    + " \"deletedAt\" IS NULL").params(FleetDb.org(), code).query(Long.class).single() == 0) {
                return code;
            }
        }
        throw ApiException.conflict("Could not allocate a tank code; enter one explicitly");
    }

    private void assertUniqueCode(String code, String exceptId) {
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), code));
        if (exceptId != null) {
            params.add(exceptId);
        }
        long n = db.jdbc().sql("SELECT count(*) FROM \"FleetFuelTank\" WHERE \"organizationId\" = ? AND code = ? AND"
                + " \"deletedAt\" IS NULL" + (exceptId == null ? "" : " AND id <> ?")).params(params)
                .query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict("A fuel tank with code " + code + " already exists");
        }
    }

    private void assertRefs(String branchId, String bowserVehicleId) {
        if (FleetVehicleService.notBlank(branchId) && !db.exists("Branch", branchId, true)) {
            throw ApiException.badRequest("Branch not found");
        }
        if (FleetVehicleService.notBlank(bowserVehicleId)
                && db.byId(FleetVehicle.class, bowserVehicleId, false) == null) {
            throw ApiException.badRequest("Bowser vehicle not found");
        }
    }

    private static void assertLevels(BigDecimal capacity, BigDecimal reorder) {
        if (capacity != null && reorder != null && reorder.compareTo(capacity) > 0) {
            throw ApiException.badRequest("The reorder level cannot exceed the tank capacity");
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
