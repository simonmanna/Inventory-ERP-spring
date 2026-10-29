package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FuelCommands.FuelLogInput;
import com.inventoryerp.fleet.application.FuelCommands.FuelLogPatch;
import com.inventoryerp.fleet.application.FuelCommands.FuelLogQuery;
import com.inventoryerp.fleet.application.FuelEfficiencyPolicy.Band;
import com.inventoryerp.fleet.application.FuelEfficiencyPolicy.Efficiency;
import com.inventoryerp.fleet.application.FuelEfficiencyPolicy.Fill;
import com.inventoryerp.fleet.application.FuelEfficiencyPolicy.Flag;
import com.inventoryerp.fleet.domain.FleetFuelAnomaly;
import com.inventoryerp.fleet.domain.FleetFuelLog;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fuel logs of fleet 1.5 (ADR-S009 §3): record (draft), edit, delete, <b>post</b> and <b>reverse</b>, receipts, the
 * efficiency chain and the anomaly review queue.
 *
 * <p>Posting a log (row and vehicle locked {@code FOR UPDATE}, so it happens exactly once): records its odometer /
 * hour-meter readings through {@link FleetMeterService#recordAutomated} (source {@code fuel}, idempotent) — a reading
 * the monotonic meter rule refuses is not recorded and flags {@code meter_not_increasing} instead; runs the accounting
 * and stock effects ({@link FleetFuelPostingService}); then recomputes the vehicle's full-to-full efficiency chain
 * ({@link FuelEfficiencyPolicy}) and syncs the anomalies. Reversal undoes the effects and recomputes the chain.
 */
@Service
public class FleetFuelLogService {

    static final String AUDIT_ENTITY = "FleetFuelLog";
    private static final Set<String> FUEL_TYPES = Set.of("diesel", "petrol", "lpg", "cng", "electric");
    private static final Map<String, String> SORTS = Map.of(
            "fuelledAt", "f.\"fuelledAt\"",
            "code", "f.code",
            "litres", "f.litres",
            "amount", "f.amount",
            "vehicle", "v.code");

    private final FleetDb db;
    private final FleetVehicleService vehicles;
    private final FleetMeterService meters;
    private final FleetFuelCardService cards;
    private final FleetFuelTankService tanks;
    private final FleetFuelSettingsService settings;
    private final FleetFuelPostingService postings;
    private final FleetAttachmentService attachments;
    private final FilesService files;
    private final SequenceService sequence;
    private final AuditService audit;

    public FleetFuelLogService(FleetDb db, FleetVehicleService vehicles, FleetMeterService meters,
            FleetFuelCardService cards, FleetFuelTankService tanks, FleetFuelSettingsService settings,
            FleetFuelPostingService postings, FleetAttachmentService attachments, FilesService files,
            SequenceService sequence, AuditService audit) {
        this.db = db;
        this.vehicles = vehicles;
        this.meters = meters;
        this.cards = cards;
        this.tanks = tanks;
        this.settings = settings;
        this.postings = postings;
        this.attachments = attachments;
        this.files = files;
        this.sequence = sequence;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    private static final String LIST_SELECT = """
            SELECT f.*, v.code AS "v_code", v.name AS "v_name", v."registrationPlate" AS "v_plate",
                   t.code AS "t_code", t.name AS "t_name", c."maskedNumber" AS "c_masked",
                   (SELECT string_agg(a.type, ',' ORDER BY a.type) FROM "FleetFuelAnomaly" a
                     WHERE a."fuelLogId" = f.id AND a.status <> 'resolved') AS "_anomalies"
            """;
    private static final String LIST_FROM = """
             FROM "FleetFuelLog" f
             JOIN "FleetVehicle" v ON v.id = f."vehicleId" AND v."organizationId" = f."organizationId"
             LEFT JOIN "FleetFuelTank" t ON t.id = f."tankId" AND t."organizationId" = f."organizationId"
             LEFT JOIN "FleetFuelCard" c ON c.id = f."fuelCardId" AND c."organizationId" = f."organizationId"
            """;

    /** Paged fuel logs with filters, plus litres / cost totals of the filtered set ({@code summary}). */
    @Transactional(readOnly = true)
    public Map<String, Object> list(FuelLogQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE f.\"organizationId\" = ? AND f.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        filter(where, params, "f.\"vehicleId\"", q.vehicleId());
        filter(where, params, "f.source", q.source());
        filter(where, params, "f.status", q.status());
        filter(where, params, "f.\"tankId\"", q.tankId());
        filter(where, params, "f.\"fuelCardId\"", q.fuelCardId());
        if (FleetVehicleService.notBlank(q.anomaly())) {
            if ("any".equals(q.anomaly())) {
                where.append(" AND f.\"anomalyCount\" > 0");
            } else if ("open".equals(q.anomaly())) {
                where.append(" AND EXISTS (SELECT 1 FROM \"FleetFuelAnomaly\" a WHERE a.\"fuelLogId\" = f.id AND"
                        + " a.status = 'open')");
            } else if ("none".equals(q.anomaly())) {
                where.append(" AND f.\"anomalyCount\" = 0");
            } else {
                where.append(" AND EXISTS (SELECT 1 FROM \"FleetFuelAnomaly\" a WHERE a.\"fuelLogId\" = f.id AND"
                        + " a.type = ? AND a.status <> 'resolved')");
                params.add(q.anomaly());
            }
        }
        ZoneId zone = BusinessZone.get();
        if (q.dateFrom() != null) {
            where.append(" AND f.\"fuelledAt\" >= ?");
            params.add(SqlRows.ts(q.dateFrom().atStartOfDay(zone).toInstant()));
        }
        if (q.dateTo() != null) {
            where.append(" AND f.\"fuelledAt\" < ?");
            params.add(SqlRows.ts(q.dateTo().plusDays(1).atStartOfDay(zone).toInstant()));
        }
        if (FleetVehicleService.notBlank(q.search())) {
            where.append(" AND (f.code ILIKE ? OR f.\"receiptNumber\" ILIKE ? OR f.\"stationName\" ILIKE ? OR v.code"
                    + " ILIKE ? OR v.\"registrationPlate\" ILIKE ?)");
            String like = "%" + q.search().trim().replace("%", "\\%").replace("_", "\\_") + "%";
            for (int i = 0; i < 5; i++) {
                params.add(like);
            }
        }
        Map<String, Object> totals = SqlRows.one(db.jdbc().sql("SELECT count(*) AS total, COALESCE(SUM(f.litres)"
                + " FILTER (WHERE f.status = 'posted'), 0) AS litres, COALESCE(SUM(f.amount) FILTER (WHERE f.status"
                + " = 'posted'), 0) AS amount" + LIST_FROM + where).params(params));
        long total = ((Number) totals.get("total")).longValue();
        String sort = SORTS.getOrDefault(q.sortBy() == null ? "fuelledAt" : q.sortBy(), "f.\"fuelledAt\"");
        String dir = "asc".equalsIgnoreCase(q.sortOrder()) ? "ASC" : "DESC";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(LIST_SELECT + LIST_FROM + where + " ORDER BY " + sort
                + " " + dir + " NULLS LAST, f.id LIMIT ? OFFSET ?").params(pageParams))) {
            rows.add(listRow(r));
        }
        PageResult<Map<String, Object>> pr = PageResult.of(rows, page, size, total);
        return Obj.of("data", pr.data(), "meta", pr.meta(), "summary", Obj.of("litres", totals.get("litres"),
                "amount", totals.get("amount")));
    }

    private static Map<String, Object> listRow(Map<String, Object> r) {
        Map<String, Object> out = SqlRows.as(FleetFuelLog.class, r);
        out.put("vehicle", Obj.of("id", r.get("vehicleId"), "code", r.get("v_code"), "name", r.get("v_name"),
                "registrationPlate", r.get("v_plate")));
        out.put("tank", r.get("tankId") == null ? null
                : Obj.of("id", r.get("tankId"), "code", r.get("t_code"), "name", r.get("t_name")));
        out.put("fuelCard", r.get("fuelCardId") == null ? null
                : Obj.of("id", r.get("fuelCardId"), "maskedNumber", r.get("c_masked")));
        String a = (String) r.get("_anomalies");
        out.put("anomalies", a == null ? List.of() : List.of(a.split(",")));
        return out;
    }

    /** Detail: references, anomalies, GL entries (and reversals), expense, receipt with a signed URL. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> f = require(id, false);
        String org = FleetDb.org();
        Map<String, Object> out = new LinkedHashMap<>(f);
        out.put("vehicle", SqlRows.one(db.jdbc().sql("SELECT id, code, name, \"registrationPlate\", \"fuelType\","
                + " \"tankCapacityL\", \"meterType\", \"categoryId\", status FROM \"FleetVehicle\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(f.get("vehicleId"), org)));
        out.put("tank", ref("SELECT id, code, name, kind FROM \"FleetFuelTank\" WHERE id = ? AND \"organizationId\" = ?",
                f.get("tankId")));
        out.put("fuelCard", ref("SELECT id, \"maskedNumber\", label, status FROM \"FleetFuelCard\" WHERE id = ? AND"
                + " \"organizationId\" = ?", f.get("fuelCardId")));
        out.put("supplier", ref("SELECT id, code, name FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?",
                f.get("supplierId")));
        Map<String, Object> e = ref("SELECT id, \"employeeCode\", \"firstName\", \"lastName\" FROM \"HrEmployee\" WHERE"
                + " id = ? AND \"organizationId\" = ?", f.get("employeeId"));
        out.put("employee", e == null ? null : Obj.of("id", e.get("id"), "employeeCode", e.get("employeeCode"), "name",
                personName(e.get("firstName"), e.get("lastName"))));
        out.put("costCenter", ref("SELECT id, code, name FROM \"CostCenter\" WHERE id = ? AND \"organizationId\" = ?",
                f.get("costCenterId")));
        out.put("paymentAccount", ref("SELECT id, code, name FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?",
                f.get("paymentAccountId")));
        out.put("previousFullLog", ref("SELECT id, code, \"fuelledAt\", odometer, hours FROM \"FleetFuelLog\" WHERE"
                + " id = ? AND \"organizationId\" = ?", f.get("previousFullLogId")));
        out.put("expense", ref("SELECT id, \"expenseCode\", status::text AS status, \"paymentStatus\"::text AS"
                + " \"paymentStatus\", amount, \"amountPaid\" FROM \"Expense\" WHERE id = ? AND \"organizationId\" = ?",
                f.get("expenseId")));
        out.put("anomalies", SqlRows.list(FleetFuelAnomaly.class, db.jdbc().sql("SELECT * FROM \"FleetFuelAnomaly\""
                + " WHERE \"organizationId\" = ? AND \"fuelLogId\" = ? ORDER BY \"createdAt\", type").params(org, id)));
        out.put("journal", journal(f));
        out.put("receipt", receipt((String) f.get("receiptAttachmentId")));
        return out;
    }

    /** The log row (404 when unknown or deleted). */
    Map<String, Object> require(String id, boolean lock) {
        return db.require(FleetFuelLog.class, id, lock, "Fuel log not found");
    }

    /** The GL entries of a log: its own entry, the expense payments, and every reversal of them. */
    private List<Map<String, Object>> journal(Map<String, Object> f) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (f.get("journalEntryId") != null) {
            ids.add((String) f.get("journalEntryId"));
        }
        if (f.get("expenseId") != null) {
            ids.addAll(db.jdbc().sql("SELECT \"journalEntryId\" FROM \"ExpensePayment\" WHERE \"organizationId\" = ? AND"
                    + " \"expenseId\" = ? AND \"journalEntryId\" IS NOT NULL").params(FleetDb.org(), f.get("expenseId"))
                    .query(String.class).list());
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> je : SqlRows.list(db.jdbc().sql("SELECT id, \"entryNumber\", status::text AS status,"
                + " \"postingDate\", description, \"postingType\", \"reversalOfId\" FROM \"JournalEntry\" WHERE"
                + " \"organizationId\" = :org AND (id IN (:ids) OR \"reversalOfId\" IN (:ids)) ORDER BY \"postedAt\","
                + " \"entryNumber\"").param("org", FleetDb.org()).param("ids", List.copyOf(ids)))) {
            Map<String, Object> entry = new LinkedHashMap<>(je);
            entry.put("lines", SqlRows.list(db.jdbc().sql("""
                    SELECT l.id, l."lineNumber", l.debit, l.credit, l.description, l."costCenterId", a.code AS
                           "accountCode", a.name AS "accountName", cc.code AS "costCenterCode"
                    FROM "JournalLine" l JOIN "Account" a ON a.id = l."accountId"
                    LEFT JOIN "CostCenter" cc ON cc.id = l."costCenterId"
                    WHERE l."journalEntryId" = ? ORDER BY l."lineNumber\"""").param(je.get("id"))));
            entries.add(entry);
        }
        return entries;
    }

    private Map<String, Object> receipt(String attachmentId) {
        if (attachmentId == null) {
            return null;
        }
        Map<String, Object> a = SqlRows.one(db.jdbc().sql("SELECT a.id, a.\"fileId\", a.kind, a.title, f.filename,"
                + " f.\"contentType\" FROM \"FleetVehicleAttachment\" a JOIN \"File\" f ON f.id = a.\"fileId\" AND"
                + " f.\"deletedAt\" IS NULL WHERE a.id = ? AND a.\"organizationId\" = ?")
                .params(attachmentId, FleetDb.org()));
        if (a == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(a);
        out.put("url", files.signDownload((String) a.get("fileId")).get("url"));
        return out;
    }

    // ------------------------------------------------------------------ writes

    /** Record a fuel log (draft); {@code post: true} posts it in the same transaction (needs fleet_fuel:post). */
    @Transactional
    public Map<String, Object> create(FuelLogInput in) {
        boolean postNow = Boolean.TRUE.equals(in.post());
        if (postNow && !TenantContext.has(Permissions.FLEET_FUEL__POST)) {
            throw ApiException.forbidden("Missing required permission(s): " + Permissions.FLEET_FUEL__POST);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("vehicleId", in.vehicleId());
        d.put("fuelledAt", in.fuelledAt());
        d.put("source", in.source());
        d.put("litres", in.litres());
        d.put("unitPrice", in.unitPrice());
        d.put("amount", in.amount());
        d.put("fullTank", in.fullTank() == null || in.fullTank());
        d.put("odometer", in.odometer());
        d.put("hours", in.hours());
        d.put("fuelType", in.fuelType());
        d.put("supplierId", blank(in.supplierId()));
        d.put("stationName", FleetVehicleService.trim(in.stationName()));
        d.put("paymentMethod", in.paymentMethod());
        d.put("paymentAccountId", blank(in.paymentAccountId()));
        d.put("fuelCardId", blank(in.fuelCardId()));
        d.put("tankId", blank(in.tankId()));
        d.put("receiptNumber", FleetVehicleService.trim(in.receiptNumber()));
        d.put("employeeId", blank(in.employeeId()));
        d.put("costCenterId", blank(in.costCenterId()));
        d.put("notes", FleetVehicleService.trim(in.notes()));
        normalize(d);
        d.put("code", sequence.next("fleet_fuel_log", "FL-", 6));
        d.put("status", "draft");
        d.put("anomalyCount", 0);
        d.put("createdBy", TenantContext.userId());
        d.put("updatedBy", TenantContext.userId());
        Map<String, Object> row = db.insert(FleetFuelLog.class, d);
        String id = (String) row.get("id");
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, row));
        if (postNow) {
            return doPost(require(id, true));
        }
        return get(id);
    }

    /** Edit a draft. */
    @Transactional
    public Map<String, Object> update(String id, FuelLogPatch in) {
        Map<String, Object> before = require(id, true);
        assertDraft(before, "edited");
        List<String> clear = in.clear() == null ? List.of() : in.clear();
        for (String f : clear) {
            if (!FuelCommands.LOG_CLEARABLE.contains(f)) {
                throw ApiException.badRequest("Field " + f + " cannot be cleared");
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        for (String col : List.of("vehicleId", "fuelledAt", "source", "litres", "unitPrice", "amount", "fullTank",
                "odometer", "hours", "fuelType", "supplierId", "stationName", "paymentMethod", "paymentAccountId",
                "fuelCardId", "tankId", "receiptNumber", "employeeId", "costCenterId", "notes")) {
            d.put(col, before.get(col));
        }
        put(d, "vehicleId", blank(in.vehicleId()));
        put(d, "fuelledAt", in.fuelledAt());
        put(d, "source", in.source());
        put(d, "litres", in.litres());
        put(d, "unitPrice", in.unitPrice());
        put(d, "amount", in.amount());
        put(d, "fullTank", in.fullTank());
        put(d, "odometer", in.odometer());
        put(d, "hours", in.hours());
        put(d, "fuelType", in.fuelType());
        put(d, "supplierId", blank(in.supplierId()));
        put(d, "stationName", FleetVehicleService.trim(in.stationName()));
        put(d, "paymentMethod", in.paymentMethod());
        put(d, "paymentAccountId", blank(in.paymentAccountId()));
        put(d, "fuelCardId", blank(in.fuelCardId()));
        put(d, "tankId", blank(in.tankId()));
        put(d, "receiptNumber", FleetVehicleService.trim(in.receiptNumber()));
        put(d, "employeeId", blank(in.employeeId()));
        put(d, "costCenterId", blank(in.costCenterId()));
        put(d, "notes", FleetVehicleService.trim(in.notes()));
        for (String f : clear) {
            d.put(f, null);
        }
        // A changed amount or price recomputes the other one unless both were sent.
        if (in.amount() != null && in.unitPrice() == null && !clear.contains("unitPrice")) {
            d.put("unitPrice", null);
        } else if (in.unitPrice() != null && in.amount() == null && !clear.contains("amount")) {
            d.put("amount", null);
        } else if (in.litres() != null && in.amount() == null && in.unitPrice() == null && d.get("unitPrice") != null) {
            d.put("amount", null);
        }
        if (!Objects.equals(d.get("vehicleId"), before.get("vehicleId")) && in.costCenterId() == null) {
            d.put("costCenterId", null);
        }
        normalize(d);
        d.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetFuelLog.class, id, d);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, after));
        return get(id);
    }

    /** Delete a draft (soft delete). */
    @Transactional
    public void delete(String id) {
        Map<String, Object> f = require(id, true);
        assertDraft(f, "deleted");
        db.update(FleetFuelLog.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "delete", f, null));
    }

    /** Post a draft: meters, GL / stock / expense effects, efficiency and anomalies — exactly once. */
    @Transactional
    public Map<String, Object> post(String id) {
        Map<String, Object> f = require(id, true);
        assertDraft(f, "posted");
        return doPost(f);
    }

    private Map<String, Object> doPost(Map<String, Object> f) {
        String id = (String) f.get("id");
        String vehicleId = (String) f.get("vehicleId");
        Map<String, Object> vehicle = vehicles.require(vehicleId, true);
        if (VehicleStatusPolicy.DISPOSED.equals(vehicle.get("status"))) {
            throw ApiException.badRequest("Vehicle " + vehicle.get("code") + " is disposed; no fuel can be logged");
        }
        Instant at = (Instant) f.get("fuelledAt");
        LocalDate day = LocalDate.ofInstant(at, BusinessZone.get());
        Map<String, Object> tank = null;
        if ("tank".equals(f.get("source"))) {
            tank = tanks.require((String) f.get("tankId"), false);
            if (!Boolean.TRUE.equals(tank.get("isActive"))) {
                throw ApiException.badRequest("Fuel tank " + tank.get("code") + " is inactive");
            }
        }
        Map<String, Object> card = null;
        if (f.get("fuelCardId") != null) {
            card = cards.require((String) f.get("fuelCardId"));
            if ("fuel_card".equals(f.get("paymentMethod"))) {
                cards.assertUsable(card, day);
            }
        }
        Map<String, Object> log = new LinkedHashMap<>(f);
        if (log.get("costCenterId") == null) {
            log.put("costCenterId", vehicle.get("costCenterId"));
        }
        // Meter readings (source fuel, idempotent per log); a refused reading becomes an anomaly, not an error.
        String code = (String) f.get("code");
        List<String> notes = new ArrayList<>();
        String odoReading = meterReading(vehicleId, "odometer", (BigDecimal) f.get("odometer"), at, id, code, notes);
        String hoursReading = meterReading(vehicleId, "hours", (BigDecimal) f.get("hours"), at, id, code, notes);
        FleetFuelPostingService.Effect e = postings.post(log, vehicle, tank, card);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "posted");
        data.put("postedAt", Instant.now());
        data.put("postedBy", TenantContext.userId());
        data.put("costCenterId", log.get("costCenterId"));
        data.put("odometerReadingId", odoReading);
        data.put("hoursReadingId", hoursReading);
        data.put("meterNote", notes.isEmpty() ? null : String.join("; ", notes));
        data.put("expenseId", e.expenseId());
        data.put("journalEntryId", e.journalEntryId());
        data.put("stockLedgerCode", e.stockLedgerCode());
        data.put("stockUnitCost", e.stockUnitCost());
        data.put("stockValue", e.stockValue());
        data.put("unitPrice", e.unitPrice());
        data.put("amount", e.amount());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> posted = db.update(FleetFuelLog.class, id, data);
        syncAnomalies(posted, cardFlags(posted, card, day), List.of(FuelEfficiencyPolicy.CARD_VEHICLE_MISMATCH,
                FuelEfficiencyPolicy.CARD_LIMIT_EXCEEDED), existingAnomalies(id));
        recompute(vehicleId);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "post", f, Obj.of("status", "posted", "journalEntryId",
                e.journalEntryId(), "expenseId", e.expenseId(), "stockLedgerCode", e.stockLedgerCode(), "amount",
                e.amount())));
        return get(id);
    }

    private String meterReading(String vehicleId, String meterType, BigDecimal value, Instant at, String logId,
            String code, List<String> notes) {
        if (value == null) {
            return null;
        }
        String refusal = meters.readingRejection(vehicleId, meterType, value, at);
        if (refusal != null) {
            notes.add(refusal);
            return null;
        }
        return (String) meters.recordAutomated(vehicleId, meterType, value, at, "fuel", logId, "Fuel log " + code)
                .get("id");
    }

    /** Reverse a posted log: postings mirrored, tank stock returned / expense voided, chain recomputed. */
    @Transactional
    public Map<String, Object> reverse(String id, String reason) {
        Map<String, Object> f = require(id, true);
        if (!"posted".equals(f.get("status"))) {
            throw ApiException.badRequest("Only a posted fuel log can be reversed (fuel log " + f.get("code") + " is "
                    + f.get("status") + ")");
        }
        vehicles.require((String) f.get("vehicleId"), true);
        String reversalEntry = postings.reverse(f, reason.trim());
        Instant now = Instant.now();
        db.update(FleetFuelLog.class, id, Obj.of("status", "reversed", "reversedAt", now, "reversedBy",
                TenantContext.userId(), "reversalReason", reason.trim(), "reversalJournalEntryId", reversalEntry,
                "anomalyCount", 0, "updatedBy", TenantContext.userId()));
        db.jdbc().sql("UPDATE \"FleetFuelAnomaly\" SET status = 'resolved', \"resolvedAt\" = " + FleetDb.NOW
                + ", \"updatedAt\" = " + FleetDb.NOW + " WHERE \"organizationId\" = ? AND \"fuelLogId\" = ? AND status <>"
                + " 'resolved'").params(FleetDb.org(), id).update();
        recompute((String) f.get("vehicleId"));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "cancel", Obj.of("status", "posted"), Obj.of("status",
                "reversed", "reason", reason.trim(), "reversalJournalEntryId", reversalEntry)));
        return get(id);
    }

    /** Attach a receipt photo / scan (stored as a vehicle attachment) and link it to the log. */
    @Transactional
    public Map<String, Object> attachReceipt(String id, String filename, String contentType, byte[] bytes) {
        Map<String, Object> f = require(id, true);
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("The receipt file is empty");
        }
        String kind = contentType != null && contentType.startsWith("image/") ? "photo" : "document";
        Map<String, Object> a = attachments.upload((String) f.get("vehicleId"), kind, "Fuel receipt " + f.get("code"),
                null, filename, contentType, bytes);
        db.update(FleetFuelLog.class, id, Obj.of("receiptAttachmentId", a.get("id"), "updatedBy",
                TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", Obj.of("receiptAttachmentId",
                f.get("receiptAttachmentId")), Obj.of("receiptAttachmentId", a.get("id"))));
        return get(id);
    }

    // ------------------------------------------------------------------ anomalies

    /** Anomaly review queue: {@code status} open (default) / acknowledged / resolved / all. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> anomalies(String status, String type, String vehicleId, Integer page,
            Integer pageSize) {
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null ? 20 : Math.max(1, Math.min(200, pageSize));
        StringBuilder where = new StringBuilder(" WHERE a.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        String st = status == null || status.isBlank() ? "open" : status;
        if (!"all".equals(st)) {
            where.append(" AND a.status = ?");
            params.add(st);
        }
        filter(where, params, "a.type", type);
        filter(where, params, "a.\"vehicleId\"", vehicleId);
        String from = " FROM \"FleetFuelAnomaly\" a JOIN \"FleetFuelLog\" f ON f.id = a.\"fuelLogId\" JOIN"
                + " \"FleetVehicle\" v ON v.id = a.\"vehicleId\" LEFT JOIN \"User\" u ON u.id = a.\"acknowledgedBy\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((p - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT a.*, f.code AS \"f_code\", f.\"fuelledAt\" AS"
                + " \"f_at\", f.litres AS \"f_litres\", f.source AS \"f_source\", f.status AS \"f_status\", v.code AS"
                + " \"v_code\", v.\"registrationPlate\" AS \"v_plate\", u.\"firstName\" AS \"u_first\", u.\"lastName\""
                + " AS \"u_last\"" + from + where + " ORDER BY a.\"createdAt\" DESC, a.id LIMIT ? OFFSET ?")
                .params(pageParams))) {
            Map<String, Object> out = SqlRows.as(FleetFuelAnomaly.class, r);
            out.put("fuelLog", Obj.of("id", r.get("fuelLogId"), "code", r.get("f_code"), "fuelledAt", r.get("f_at"),
                    "litres", r.get("f_litres"), "source", r.get("f_source"), "status", r.get("f_status")));
            out.put("vehicle", Obj.of("id", r.get("vehicleId"), "code", r.get("v_code"), "registrationPlate",
                    r.get("v_plate")));
            out.put("acknowledgedByName", personName(r.get("u_first"), r.get("u_last")));
            rows.add(out);
        }
        return PageResult.of(rows, p, size, total);
    }

    /** Acknowledge an open anomaly with a review note (audited). */
    @Transactional
    public Map<String, Object> acknowledge(String anomalyId, String note) {
        Map<String, Object> a = db.require(FleetFuelAnomaly.class, anomalyId, true, "Fuel anomaly not found");
        if (!"open".equals(a.get("status"))) {
            throw ApiException.badRequest("Only an open anomaly can be acknowledged (this one is " + a.get("status")
                    + ")");
        }
        Map<String, Object> out = db.update(FleetFuelAnomaly.class, anomalyId, Obj.of("status", "acknowledged", "note",
                note.trim(), "acknowledgedBy", TenantContext.userId(), "acknowledgedAt", Instant.now()));
        audit.recordInTx(AuditInput.of("FleetFuelAnomaly", anomalyId, "update", a, out));
        return out;
    }

    // ------------------------------------------------------------------ vehicle fuel tab

    /** Fuel summary and efficiency trend of a vehicle (posted logs; trend = last 60 measured full-tank logs). */
    @Transactional(readOnly = true)
    public Map<String, Object> vehicleFuel(String vehicleId) {
        Map<String, Object> v = vehicles.require(vehicleId, false);
        String org = FleetDb.org();
        Map<String, Object> summary = SqlRows.one(db.jdbc().sql("""
                SELECT count(*) AS logs, COALESCE(SUM(litres), 0) AS litres, COALESCE(SUM(amount), 0) AS amount,
                       max("fuelledAt") AS "lastFuelledAt",
                       COALESCE(SUM("segmentLitres") FILTER (WHERE "lPer100Km" IS NOT NULL), 0) AS "kmLitres",
                       COALESCE(SUM("distanceKm") FILTER (WHERE "lPer100Km" IS NOT NULL), 0) AS km,
                       COALESCE(SUM("segmentLitres") FILTER (WHERE "lPerHour" IS NOT NULL), 0) AS "hourLitres",
                       COALESCE(SUM("hoursRun") FILTER (WHERE "lPerHour" IS NOT NULL), 0) AS "hoursRun",
                       COALESCE(SUM("anomalyCount"), 0) AS anomalies
                FROM "FleetFuelLog" WHERE "organizationId" = ? AND "vehicleId" = ? AND status = 'posted'
                  AND "deletedAt" IS NULL""").params(org, vehicleId));
        BigDecimal km = SqlRows.dec(summary, "km");
        BigDecimal hours = SqlRows.dec(summary, "hoursRun");
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("logs", summary.get("logs"));
        s.put("litres", summary.get("litres"));
        s.put("amount", summary.get("amount"));
        s.put("lastFuelledAt", summary.get("lastFuelledAt"));
        s.put("averageLPer100Km", km.signum() > 0 ? SqlRows.dec(summary, "kmLitres").multiply(BigDecimal.valueOf(100))
                .divide(km, 3, RoundingMode.HALF_UP) : null);
        s.put("averageLPerHour", hours.signum() > 0 ? SqlRows.dec(summary, "hourLitres").divide(hours, 3,
                RoundingMode.HALF_UP) : null);
        s.put("anomalies", summary.get("anomalies"));
        List<Map<String, Object>> trend = SqlRows.list(db.jdbc().sql("""
                SELECT * FROM (SELECT id, code, "fuelledAt", litres, "segmentLitres", "distanceKm", "hoursRun",
                                      "lPer100Km", "lPerHour", "anomalyCount"
                               FROM "FleetFuelLog" WHERE "organizationId" = ? AND "vehicleId" = ? AND status = 'posted'
                                 AND "deletedAt" IS NULL AND ("lPer100Km" IS NOT NULL OR "lPerHour" IS NOT NULL)
                               ORDER BY "fuelledAt" DESC LIMIT 60) t ORDER BY "fuelledAt\"""").params(org, vehicleId));
        Band band = settings.band((String) v.get("categoryId"));
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(LIST_SELECT + LIST_FROM + " WHERE f.\"organizationId\""
                + " = ? AND f.\"vehicleId\" = ? AND f.\"deletedAt\" IS NULL ORDER BY f.\"fuelledAt\" DESC LIMIT 10")
                .params(org, vehicleId))) {
            recent.add(listRow(r));
        }
        return Obj.of("summary", s, "band", Obj.of("minLPer100Km", band.minLPer100Km(), "maxLPer100Km",
                band.maxLPer100Km(), "minLPerHour", band.minLPerHour(), "maxLPerHour", band.maxLPerHour()),
                "trend", trend, "recent", recent);
    }

    // ------------------------------------------------------------------ efficiency chain

    /** Recompute efficiency and chain anomalies of every posted log of a vehicle (vehicle row locked by caller). */
    void recompute(String vehicleId) {
        Map<String, Object> vehicle = vehicles.require(vehicleId, false);
        Band band = settings.band((String) vehicle.get("categoryId"));
        BigDecimal tolerance = SqlRows.dec(settings.current(), "overfillTolerancePct");
        BigDecimal tankL = vehicle.get("tankCapacityL") == null ? null : SqlRows.dec(vehicle, "tankCapacityL");
        List<Map<String, Object>> logs = SqlRows.list(FleetFuelLog.class, db.jdbc().sql("SELECT * FROM \"FleetFuelLog\""
                + " WHERE \"organizationId\" = ? AND \"vehicleId\" = ? AND status = 'posted' AND \"deletedAt\" IS NULL"
                + " ORDER BY \"fuelledAt\", \"createdAt\", id").params(FleetDb.org(), vehicleId));
        List<Fill> chain = new ArrayList<>();
        for (Map<String, Object> l : logs) {
            chain.add(new Fill((String) l.get("id"), SqlRows.dec(l, "litres"), Boolean.TRUE.equals(l.get("fullTank")),
                    (BigDecimal) l.get("odometer"), (BigDecimal) l.get("hours")));
        }
        Map<String, Map<String, Map<String, Object>>> anomalies = new HashMap<>();
        for (Map<String, Object> a : SqlRows.list(FleetFuelAnomaly.class, db.jdbc().sql("SELECT * FROM"
                + " \"FleetFuelAnomaly\" WHERE \"organizationId\" = ? AND \"vehicleId\" = ?")
                .params(FleetDb.org(), vehicleId))) {
            anomalies.computeIfAbsent((String) a.get("fuelLogId"), k -> new HashMap<>()).put((String) a.get("type"), a);
        }
        for (int i = 0; i < chain.size(); i++) {
            Map<String, Object> l = logs.get(i);
            Efficiency e = FuelEfficiencyPolicy.efficiency(chain, i);
            List<Flag> flags = FuelEfficiencyPolicy.flags(chain, i, e, band, tankL, tolerance,
                    (String) l.get("meterNote"));
            Map<String, Object> eff = new LinkedHashMap<>();
            eff.put("previousFullLogId", e.previousFullId());
            eff.put("segmentLitres", e.segmentLitres());
            eff.put("distanceKm", e.distanceKm());
            eff.put("hoursRun", e.hoursRun());
            eff.put("lPer100Km", e.lPer100Km());
            eff.put("lPerHour", e.lPerHour());
            Map<String, Object> changed = new LinkedHashMap<>();
            eff.forEach((k, val) -> {
                if (!same(val, l.get(k))) {
                    changed.put(k, val);
                }
            });
            if (!changed.isEmpty()) {
                db.update(FleetFuelLog.class, (String) l.get("id"), changed);
            }
            syncAnomalies(l, flags, FuelEfficiencyPolicy.CHAIN_TYPES,
                    anomalies.getOrDefault((String) l.get("id"), new HashMap<>()));
        }
    }

    /**
     * Make the anomalies of {@code log} within {@code scope} match {@code flags}: new → open, re-raised resolved →
     * open again, gone → resolved; then refresh the log's {@code anomalyCount}.
     */
    private void syncAnomalies(Map<String, Object> log, List<Flag> flags, List<String> scope,
            Map<String, Map<String, Object>> existing) {
        String logId = (String) log.get("id");
        boolean touched = false;
        Set<String> raised = new LinkedHashSet<>();
        for (Flag fl : flags) {
            raised.add(fl.type());
            Map<String, Object> row = existing.get(fl.type());
            if (row == null) {
                db.insert(FleetFuelAnomaly.class, Obj.of("fuelLogId", logId, "vehicleId", log.get("vehicleId"),
                        "type", fl.type(), "detail", fl.detail(), "observed", fl.observed(), "expectedMin",
                        fl.expectedMin(), "expectedMax", fl.expectedMax(), "status", "open"));
                touched = true;
            } else if ("resolved".equals(row.get("status")) || !Objects.equals(row.get("detail"), fl.detail())) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("detail", fl.detail());
                data.put("observed", fl.observed());
                data.put("expectedMin", fl.expectedMin());
                data.put("expectedMax", fl.expectedMax());
                if ("resolved".equals(row.get("status"))) {
                    data.put("status", "open");
                    data.put("resolvedAt", null);
                }
                db.update(FleetFuelAnomaly.class, (String) row.get("id"), data);
                touched = true;
            }
        }
        for (Map<String, Object> row : existing.values()) {
            if (scope.contains(row.get("type")) && !raised.contains(row.get("type"))
                    && !"resolved".equals(row.get("status"))) {
                db.update(FleetFuelAnomaly.class, (String) row.get("id"), Obj.of("status", "resolved", "resolvedAt",
                        Instant.now()));
                touched = true;
            }
        }
        if (touched) {
            long count = db.jdbc().sql("SELECT count(*) FROM \"FleetFuelAnomaly\" WHERE \"organizationId\" = ? AND"
                    + " \"fuelLogId\" = ? AND status <> 'resolved'").params(FleetDb.org(), logId).query(Long.class)
                    .single();
            db.update(FleetFuelLog.class, logId, Obj.of("anomalyCount", (int) count));
        }
    }

    private Map<String, Map<String, Object>> existingAnomalies(String logId) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> a : SqlRows.list(FleetFuelAnomaly.class, db.jdbc().sql("SELECT * FROM"
                + " \"FleetFuelAnomaly\" WHERE \"organizationId\" = ? AND \"fuelLogId\" = ?")
                .params(FleetDb.org(), logId))) {
            out.put((String) a.get("type"), a);
        }
        return out;
    }

    /** Card anomalies decided at posting: card assigned to another vehicle, spend above the period limit. */
    private List<Flag> cardFlags(Map<String, Object> log, Map<String, Object> card, LocalDate day) {
        List<Flag> out = new ArrayList<>();
        if (card == null) {
            return out;
        }
        String masked = (String) card.get("maskedNumber");
        if (card.get("vehicleId") != null && !card.get("vehicleId").equals(log.get("vehicleId"))) {
            String other = db.jdbc().sql("SELECT code FROM \"FleetVehicle\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(card.get("vehicleId"), FleetDb.org()).query(String.class).optional().orElse("?");
            out.add(new Flag(FuelEfficiencyPolicy.CARD_VEHICLE_MISMATCH, "Fuel card " + masked + " is assigned to"
                    + " vehicle " + other, null, null, null));
        }
        if (card.get("limitAmount") != null && "fuel_card".equals(log.get("paymentMethod"))) {
            String period = (String) card.get("limitPeriod");
            LocalDate start = FleetFuelCardService.periodStart(period, day);
            BigDecimal spent = cards.spend((String) card.get("id"), start, FleetFuelCardService.periodEnd(period, start));
            BigDecimal limit = SqlRows.dec(card, "limitAmount");
            if (spent.compareTo(limit) > 0) {
                out.add(new Flag(FuelEfficiencyPolicy.CARD_LIMIT_EXCEEDED, "Fuel card " + masked + " spend "
                        + FuelEfficiencyPolicy.plain(spent) + " is above its " + period + " limit of "
                        + FuelEfficiencyPolicy.plain(limit), spent, null, limit));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ validation

    /** Validates and completes a draft's fields in place (defaults, derived price / amount, references). */
    private void normalize(Map<String, Object> d) {
        Map<String, Object> v = vehicles.require((String) d.get("vehicleId"), false);
        String vcode = (String) v.get("code");
        if (VehicleStatusPolicy.DISPOSED.equals(v.get("status"))) {
            throw ApiException.badRequest("Vehicle " + vcode + " is disposed; no fuel can be logged");
        }
        Instant at = d.get("fuelledAt") == null ? Instant.now() : (Instant) d.get("fuelledAt");
        if (at.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("Fuelling time cannot be in the future");
        }
        d.put("fuelledAt", at);
        String meterType = (String) v.get("meterType");
        if (d.get("odometer") != null && !MeterReadingPolicy.tracks(meterType, "odometer")) {
            throw ApiException.badRequest("Vehicle " + vcode + " does not track an odometer");
        }
        if (d.get("hours") != null && !MeterReadingPolicy.tracks(meterType, "hours")) {
            throw ApiException.badRequest("Vehicle " + vcode + " does not track engine hours");
        }
        if (!"none".equals(meterType) && d.get("odometer") == null && d.get("hours") == null) {
            throw ApiException.badRequest("Enter the " + ("hours".equals(meterType) ? "engine hours"
                    : "both".equals(meterType) ? "odometer or engine hours" : "odometer") + " of vehicle " + vcode);
        }
        if (d.get("fuelType") == null && FUEL_TYPES.contains(v.get("fuelType"))) {
            d.put("fuelType", v.get("fuelType"));
        }
        BigDecimal litres = (BigDecimal) d.get("litres");
        if ("tank".equals(d.get("source"))) {
            if (d.get("tankId") == null) {
                throw ApiException.badRequest("Choose the site tank the fuel was issued from");
            }
            Map<String, Object> tank = tanks.require((String) d.get("tankId"), false);
            if (!Boolean.TRUE.equals(tank.get("isActive"))) {
                throw ApiException.badRequest("Fuel tank " + tank.get("code") + " is inactive");
            }
            // Tank issues are costed from stock when posted; no payment.
            for (String k : List.of("paymentMethod", "paymentAccountId", "fuelCardId", "supplierId", "stationName",
                    "unitPrice", "amount")) {
                d.put(k, null);
            }
        } else {
            if (d.get("tankId") != null) {
                throw ApiException.badRequest("A station purchase is not issued from a site tank");
            }
            String method = (String) d.get("paymentMethod");
            if (method == null) {
                throw ApiException.badRequest("Choose how the fuel was paid: cash, fuel card or supplier credit");
            }
            priceAndAmount(d, litres);
            if ("cash".equals(method)) {
                String account = (String) d.get("paymentAccountId");
                if (account == null) {
                    throw ApiException.badRequest("Choose the cash or bank account the fuel was paid from");
                }
                long ok = db.jdbc().sql("SELECT count(*) FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id ="
                        + " a.\"categoryId\" WHERE a.id = ? AND a.\"organizationId\" = ? AND c.\"isCashEquivalent\" ="
                        + " true AND a.\"isPostable\" = true AND a.\"isActive\" = true AND a.\"deletedAt\" IS NULL")
                        .params(account, FleetDb.org()).query(Long.class).single();
                if (ok == 0) {
                    throw ApiException.badRequest("Pay fuel from an active cash, bank or mobile-money account");
                }
                d.put("fuelCardId", null);
            } else {
                d.put("paymentAccountId", null);
            }
            if ("fuel_card".equals(method)) {
                if (d.get("fuelCardId") == null) {
                    throw ApiException.badRequest("Choose the fuel card used");
                }
                cards.assertUsable(cards.require((String) d.get("fuelCardId")), LocalDate.ofInstant(at,
                        BusinessZone.get()));
            } else if (d.get("fuelCardId") != null) {
                d.put("fuelCardId", null);
            }
            if ("credit".equals(method) && d.get("supplierId") == null) {
                throw ApiException.badRequest("Choose the supplier the fuel was bought from on credit");
            }
            if (d.get("supplierId") != null && !db.exists("Partner", (String) d.get("supplierId"), true)) {
                throw ApiException.badRequest("Supplier not found");
            }
        }
        if (d.get("employeeId") != null && !db.exists("HrEmployee", (String) d.get("employeeId"), true)) {
            throw ApiException.badRequest("Employee not found");
        }
        if (d.get("costCenterId") == null) {
            d.put("costCenterId", v.get("costCenterId"));
        } else if (!db.exists("CostCenter", (String) d.get("costCenterId"), true)) {
            throw ApiException.badRequest("Cost centre not found");
        }
    }

    /** Completes unit price / amount from each other; both given must agree (pump rounding tolerated). */
    private static void priceAndAmount(Map<String, Object> d, BigDecimal litres) {
        BigDecimal price = (BigDecimal) d.get("unitPrice");
        BigDecimal amount = (BigDecimal) d.get("amount");
        if (price == null && amount == null) {
            throw ApiException.badRequest("Enter the amount paid or the price per litre");
        }
        if (amount == null) {
            d.put("amount", litres.multiply(price).setScale(2, RoundingMode.HALF_UP));
        } else if (price == null) {
            d.put("unitPrice", amount.divide(litres, 4, RoundingMode.HALF_UP));
        } else {
            BigDecimal expected = litres.multiply(price).setScale(2, RoundingMode.HALF_UP);
            BigDecimal tolerance = BigDecimal.ONE.max(expected.multiply(new BigDecimal("0.005")));
            if (expected.subtract(amount).abs().compareTo(tolerance) > 0) {
                throw ApiException.badRequest("The amount " + FuelEfficiencyPolicy.plain(amount) + " does not match "
                        + FuelEfficiencyPolicy.plain(litres) + " L × " + FuelEfficiencyPolicy.plain(price) + " = "
                        + FuelEfficiencyPolicy.plain(expected));
            }
        }
    }

    private static void assertDraft(Map<String, Object> f, String verb) {
        if (!"draft".equals(f.get("status"))) {
            throw ApiException.badRequest("Fuel log " + f.get("code") + " is " + f.get("status") + " and cannot be "
                    + verb);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> ref(String sql, Object id) {
        return id == null ? null : SqlRows.one(db.jdbc().sql(sql).params(id, FleetDb.org()));
    }

    static String personName(Object first, Object last) {
        String s = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
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
