package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FleetCommands.ComplianceInput;
import com.inventoryerp.fleet.application.FleetCommands.CompliancePatch;
import com.inventoryerp.fleet.application.FleetCommands.ComplianceQuery;
import com.inventoryerp.fleet.application.FleetCommands.ComplianceRenewal;
import com.inventoryerp.fleet.domain.FleetComplianceDocument;
import com.inventoryerp.fleet.domain.FleetVehicle;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vehicle compliance documents (1.1): registration, insurance, road licence / permit, inspection certificate,
 * operator permit — number, issuer, issue / expiry dates, reminder lead time, attachments, renewal. The expiry query
 * ({@link #due}, {@link #dueForOrganization}) is the source for alerts and the 1.9 reminder job.
 */
@Service
public class FleetComplianceService {

    static final String SETTING_LEAD_DAYS = "fleet.complianceReminderDays";

    private final FleetDb db;
    private final SettingResolverService settings;
    private final AuditService audit;

    public FleetComplianceService(FleetDb db, SettingResolverService settings, AuditService audit) {
        this.db = db;
        this.settings = settings;
        this.audit = audit;
    }

    /** Documents of a vehicle (active first, then renewed history), each with its expiry state and attachments. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForVehicle(String vehicleId) {
        db.require(FleetVehicle.class, vehicleId, false, "Vehicle not found");
        LocalDate today = today();
        List<Map<String, Object>> rows = db.list(FleetComplianceDocument.class, " AND \"vehicleId\" = ? ORDER BY"
                + " CASE WHEN status = 'active' THEN 0 ELSE 1 END, \"docType\", \"expiresOn\" DESC NULLS LAST",
                vehicleId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> d = decorate(r, today);
            d.put("attachments", SqlRows.list(db.jdbc().sql("""
                    SELECT a.id, a."fileId", a.title, f.filename, f."contentType", f."byteSize"
                    FROM "FleetVehicleAttachment" a JOIN "File" f ON f.id = a."fileId" AND f."deletedAt" IS NULL
                    WHERE a."organizationId" = ? AND a."complianceDocumentId" = ? ORDER BY a."createdAt\"""")
                    .params(FleetDb.org(), r.get("id"))));
            out.add(d);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> create(String vehicleId, ComplianceInput in) {
        Map<String, Object> v = db.require(FleetVehicle.class, vehicleId, true, "Vehicle not found");
        if (VehicleStatusPolicy.DISPOSED.equals(v.get("status"))) {
            throw ApiException.badRequest("Vehicle " + v.get("code") + " is disposed");
        }
        assertDates(in.issuedOn(), in.expiresOn());
        Map<String, Object> row = db.insert(FleetComplianceDocument.class, Obj.of(
                "vehicleId", vehicleId,
                "docType", in.docType(),
                "documentNumber", FleetVehicleService.trim(in.documentNumber()),
                "issuer", FleetVehicleService.trim(in.issuer()),
                "issuedOn", in.issuedOn(),
                "expiresOn", in.expiresOn(),
                "reminderLeadDays", in.reminderLeadDays() == null ? defaultLeadDays() : in.reminderLeadDays(),
                "status", "active",
                "notes", FleetVehicleService.trim(in.notes()),
                "createdBy", TenantContext.userId(),
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("FleetComplianceDocument", (String) row.get("id"), "create", null, row));
        return decorate(row, today());
    }

    @Transactional
    public Map<String, Object> update(String id, CompliancePatch in) {
        Map<String, Object> before = require(id, true);
        List<String> clear = in.clear() == null ? List.of() : in.clear();
        for (String f : clear) {
            if (!FleetCommands.COMPLIANCE_CLEARABLE.contains(f)) {
                throw ApiException.badRequest("Field " + f + " cannot be cleared");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        putIf(data, "docType", in.docType());
        putIf(data, "documentNumber", FleetVehicleService.trim(in.documentNumber()));
        putIf(data, "issuer", FleetVehicleService.trim(in.issuer()));
        putIf(data, "issuedOn", in.issuedOn());
        putIf(data, "expiresOn", in.expiresOn());
        putIf(data, "reminderLeadDays", in.reminderLeadDays());
        putIf(data, "notes", FleetVehicleService.trim(in.notes()));
        for (String f : clear) {
            data.put(f, null);
        }
        if (data.isEmpty()) {
            return decorate(before, today());
        }
        Map<String, Object> merged = new LinkedHashMap<>(before);
        merged.putAll(data);
        assertDates((LocalDate) merged.get("issuedOn"), (LocalDate) merged.get("expiresOn"));
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetComplianceDocument.class, id, data);
        audit.recordInTx(AuditInput.of("FleetComplianceDocument", id, "update", before, after));
        return decorate(after, today());
    }

    /** Renew: a new active document of the same type replaces this one (which becomes {@code renewed}). */
    @Transactional
    public Map<String, Object> renew(String id, ComplianceRenewal in) {
        Map<String, Object> old = require(id, true);
        if (!"active".equals(old.get("status"))) {
            throw ApiException.badRequest("Only an active document can be renewed");
        }
        LocalDate oldExpiry = (LocalDate) old.get("expiresOn");
        if (oldExpiry != null && !in.expiresOn().isAfter(oldExpiry)) {
            throw ApiException.badRequest("The renewal must expire after the current document (" + oldExpiry + ")");
        }
        assertDates(in.issuedOn(), in.expiresOn());
        Map<String, Object> row = db.insert(FleetComplianceDocument.class, Obj.of(
                "vehicleId", old.get("vehicleId"),
                "docType", old.get("docType"),
                "documentNumber", in.documentNumber() == null ? old.get("documentNumber")
                        : FleetVehicleService.trim(in.documentNumber()),
                "issuer", in.issuer() == null ? old.get("issuer") : FleetVehicleService.trim(in.issuer()),
                "issuedOn", in.issuedOn(),
                "expiresOn", in.expiresOn(),
                "reminderLeadDays", in.reminderLeadDays() == null ? old.get("reminderLeadDays") : in.reminderLeadDays(),
                "status", "active",
                "notes", FleetVehicleService.trim(in.notes()),
                "createdBy", TenantContext.userId(),
                "updatedBy", TenantContext.userId()));
        db.update(FleetComplianceDocument.class, id, Obj.of("status", "renewed", "renewedById", row.get("id"),
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("FleetComplianceDocument", id, "update", old, row));
        return decorate(row, today());
    }

    /** Soft delete. */
    @Transactional
    public void delete(String id) {
        Map<String, Object> row = require(id, true);
        db.update(FleetComplianceDocument.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy",
                TenantContext.userId()));
        audit.recordInTx(AuditInput.of("FleetComplianceDocument", id, "delete", row, null));
    }

    /** One document with its expiry state. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return decorate(require(id, false), today());
    }

    /** One document of the current organization (404 when unknown or deleted). */
    public Map<String, Object> require(String id, boolean lock) {
        return db.require(FleetComplianceDocument.class, id, lock, "Compliance document not found");
    }

    /**
     * Org-wide expiry list of active documents on live vehicles: {@code due} = {@code attention} (expired or
     * expiring, the default), {@code expiring}, {@code expired} or {@code all}; soonest expiry first.
     */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> due(ComplianceQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        LocalDate asOf = q.asOf() == null ? today() : q.asOf();
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        where(where, params, FleetDb.org(), q.due() == null ? "attention" : q.due(), asOf);
        if (FleetVehicleService.notBlank(q.vehicleId())) {
            where.append(" AND d.\"vehicleId\" = ?");
            params.add(q.vehicleId());
        }
        if (FleetVehicleService.notBlank(q.docType())) {
            where.append(" AND d.\"docType\" = ?");
            params.add(q.docType());
        }
        if (FleetVehicleService.notBlank(q.branchId())) {
            where.append(" AND v.\"branchId\" = ?");
            params.add(q.branchId());
        }
        String from = " FROM \"FleetComplianceDocument\" d JOIN \"FleetVehicle\" v ON v.id = d.\"vehicleId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT d.*, v.code AS \"vehicleCode\", v.name AS"
                + " \"vehicleName\", v.\"registrationPlate\" AS \"vehicleRegistrationPlate\", v.\"branchId\" AS"
                + " \"vehicleBranchId\"" + from + where + " ORDER BY d.\"expiresOn\" ASC NULLS LAST, v.code, d.id"
                + " LIMIT ? OFFSET ?").params(pageParams))) {
            Map<String, Object> d = decorate(SqlRows.as(FleetComplianceDocument.class, r), asOf);
            d.put("vehicle", Obj.of("id", r.get("vehicleId"), "code", r.get("vehicleCode"), "name",
                    r.get("vehicleName"), "registrationPlate", r.get("vehicleRegistrationPlate"), "branchId",
                    r.get("vehicleBranchId")));
            rows.add(d);
        }
        return PageResult.of(rows, page, size, total);
    }

    /**
     * Expired and expiring documents of {@code organizationId} on {@code asOf} — for scheduled reminders, which run
     * without a request tenant. Each row carries the vehicle code and plate.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> dueForOrganization(String organizationId, LocalDate asOf) {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        where(where, params, organizationId, "attention", asOf);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT d.*, v.code AS \"vehicleCode\","
                + " v.\"registrationPlate\" AS \"vehicleRegistrationPlate\" FROM \"FleetComplianceDocument\" d"
                + " JOIN \"FleetVehicle\" v ON v.id = d.\"vehicleId\"" + where + " ORDER BY d.\"expiresOn\", v.code")
                .params(params))) {
            Map<String, Object> d = decorate(SqlRows.as(FleetComplianceDocument.class, r), asOf);
            d.put("vehicleCode", r.get("vehicleCode"));
            d.put("vehicleRegistrationPlate", r.get("vehicleRegistrationPlate"));
            out.add(d);
        }
        return out;
    }

    private static void where(StringBuilder where, List<Object> params, String org, String due, LocalDate asOf) {
        where.append(" WHERE d.\"organizationId\" = ? AND d.status = 'active' AND d.\"deletedAt\" IS NULL"
                + " AND v.\"deletedAt\" IS NULL AND v.status <> 'disposed'");
        params.add(org);
        java.sql.Date day = java.sql.Date.valueOf(asOf);
        switch (due) {
            case "all" -> { }
            case "expired" -> {
                where.append(" AND d.\"expiresOn\" < ?");
                params.add(day);
            }
            case "expiring" -> {
                where.append(" AND d.\"expiresOn\" >= ? AND d.\"expiresOn\" <= ?::date + d.\"reminderLeadDays\"");
                params.add(day);
                params.add(day);
            }
            default -> {
                where.append(" AND d.\"expiresOn\" <= ?::date + d.\"reminderLeadDays\"");
                params.add(day);
            }
        }
    }

    /** Adds {@code state} (expired / expiring / valid / no_expiry / renewed) and {@code daysToExpiry}. */
    static Map<String, Object> decorate(Map<String, Object> row, LocalDate today) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        LocalDate expires = (LocalDate) row.get("expiresOn");
        int lead = row.get("reminderLeadDays") == null ? 0 : ((Number) row.get("reminderLeadDays")).intValue();
        out.put("state", "renewed".equals(row.get("status")) ? ComplianceState.RENEWED
                : ComplianceState.of(expires, lead, today));
        out.put("daysToExpiry", ComplianceState.daysToExpiry(expires, today));
        return out;
    }

    private int defaultLeadDays() {
        Number n = settings.resolveNumber(SETTING_LEAD_DAYS, SettingContext.EMPTY);
        return n == null ? 30 : Math.max(0, Math.min(365, n.intValue()));
    }

    private static void assertDates(LocalDate issuedOn, LocalDate expiresOn) {
        if (issuedOn != null && expiresOn != null && expiresOn.isBefore(issuedOn)) {
            throw ApiException.badRequest("The expiry date must be on or after the issue date");
        }
    }

    private static void putIf(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    private static LocalDate today() {
        return LocalDate.now(BusinessZone.get());
    }
}
