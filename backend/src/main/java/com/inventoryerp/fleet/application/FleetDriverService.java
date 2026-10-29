package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.DriverCommands.DriverInput;
import com.inventoryerp.fleet.application.DriverCommands.DriverPatch;
import com.inventoryerp.fleet.application.DriverCommands.DriverQuery;
import com.inventoryerp.fleet.application.DriverEligibilityPolicy.DriverFacts;
import com.inventoryerp.fleet.application.DriverEligibilityPolicy.VehicleFacts;
import com.inventoryerp.fleet.domain.FleetDriver;
import com.inventoryerp.fleet.domain.FleetVehicleCategory;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Driver &amp; operator profiles (fleet 1.2, ADR-S009): one profile per hr employee with the current driving licence
 * (number, legal classes, issuer, issue / expiry), medical certificate and operator permit expiries, the vehicle
 * classes the person is qualified for, and a status ({@code active / suspended / inactive}). Public API for the
 * later sub-modules: {@link #require}, {@link #facts} + {@link DriverEligibilityPolicy} (dispatch 1.3 assigns drivers
 * to loads the same way) and {@link #documentsDueForOrganization} (1.9 reminder job). The hr employee is read-only.
 */
@Service
public class FleetDriverService {

    static final String AUDIT_ENTITY = "FleetDriver";

    private static final Map<String, String> SORTS = Map.of(
            "code", "d.code",
            "name", "e.\"lastName\"",
            "status", "d.status",
            "licenceExpiresOn", "d.\"licenceExpiresOn\"",
            "createdAt", "d.\"createdAt\"");

    /** The dated documents of a profile: document key → expiry column. */
    static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        DOCUMENTS.put("licence", "licenceExpiresOn");
        DOCUMENTS.put("medical", "medicalExpiresOn");
        DOCUMENTS.put("permit", "permitExpiresOn");
    }

    private final FleetDb db;
    private final SequenceService sequence;
    private final SettingResolverService settings;
    private final AuditService audit;

    public FleetDriverService(FleetDb db, SequenceService sequence, SettingResolverService settings,
            AuditService audit) {
        this.db = db;
        this.sequence = sequence;
        this.settings = settings;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    /** Paged profiles with the employee, qualified classes and the state of each dated document. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(DriverQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        LocalDate today = today();
        StringBuilder where = new StringBuilder(" WHERE d.\"organizationId\" = ? AND d.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (FleetVehicleService.notBlank(q.search())) {
            where.append(" AND (d.code ILIKE ? OR d.\"licenceNumber\" ILIKE ? OR e.\"firstName\" ILIKE ?"
                    + " OR e.\"lastName\" ILIKE ? OR e.\"employeeCode\" ILIKE ?)");
            String like = "%" + q.search().trim().replace("%", "\\%").replace("_", "\\_") + "%";
            for (int i = 0; i < 5; i++) {
                params.add(like);
            }
        }
        if (FleetVehicleService.notBlank(q.status())) {
            where.append(" AND d.status = ?");
            params.add(q.status());
        }
        if (FleetVehicleService.notBlank(q.driverType())) {
            where.append(" AND d.\"driverType\" = ?");
            params.add(q.driverType());
        }
        if (FleetVehicleService.notBlank(q.categoryId())) {
            where.append(" AND EXISTS (SELECT 1 FROM \"FleetDriverQualification\" x WHERE x.\"driverId\" = d.id"
                    + " AND x.\"categoryId\" = ?)");
            params.add(q.categoryId());
        }
        if (FleetVehicleService.notBlank(q.documents())) {
            where.append(" AND (").append(documentsClause(q.documents(), "d", params, today)).append(")");
        }
        String from = " FROM \"FleetDriver\" d LEFT JOIN \"HrEmployee\" e ON e.id = d.\"employeeId\""
                + " AND e.\"organizationId\" = d.\"organizationId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        String sort = SORTS.getOrDefault(q.sortBy() == null ? "code" : q.sortBy(), "d.code");
        String dir = "desc".equalsIgnoreCase(q.sortOrder()) ? "DESC" : "ASC";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT d.*, " + EMPLOYEE_COLUMNS + from + where
                + " ORDER BY " + sort + " " + dir + " NULLS LAST, d.id LIMIT ? OFFSET ?").params(pageParams))) {
            rows.add(decorate(r, today));
        }
        attachQualifications(rows);
        attachOpenCheckouts(rows);
        return PageResult.of(rows, page, size, total);
    }

    private static final String EMPLOYEE_COLUMNS = "e.\"employeeCode\" AS \"e_code\", e.\"firstName\" AS \"e_first\","
            + " e.\"lastName\" AS \"e_last\", e.phone AS \"e_phone\", e.email AS \"e_email\", e.\"isActive\" AS"
            + " \"e_active\", e.\"userId\" AS \"e_user\"";

    /** Detail: profile, employee, qualifications, document states, current assignments and the open check-out. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> d = load(id);
        String org = FleetDb.org();
        d.put("assignments", SqlRows.list(db.jdbc().sql("""
                SELECT a.*, v.code AS "vehicleCode", v."registrationPlate" AS "vehicleRegistrationPlate",
                       v.name AS "vehicleName"
                FROM "FleetAssignment" a JOIN "FleetVehicle" v ON v.id = a."vehicleId"
                WHERE a."organizationId" = ? AND a."driverId" = ? AND a.status = 'active'
                ORDER BY a."startsAt" DESC""").params(org, id)));
        return d;
    }

    /** The driver row (404 when unknown or deleted) — for the other 1.2 services and later sub-modules. */
    public Map<String, Object> require(String id, boolean lock) {
        return db.require(FleetDriver.class, id, lock, "Driver not found");
    }

    /** Eligibility of the driver for the vehicle on {@code on} (today by default): {@code {eligible, reasons}}. */
    @Transactional(readOnly = true)
    public Map<String, Object> eligibility(String driverId, String vehicleId, LocalDate on, String action) {
        Map<String, Object> d = require(driverId, false);
        VehicleFacts v = vehicleFacts(vehicleId, false);
        List<String> reasons = DriverEligibilityPolicy.reasons(facts(d), v, on == null ? today() : on,
                action == null ? "assigned" : action);
        return Obj.of("eligible", reasons.isEmpty(), "reasons", reasons, "driverId", driverId, "vehicleId", vehicleId);
    }

    /** Policy facts of a driver row (employee activity and qualified classes read now). */
    public DriverFacts facts(Map<String, Object> d) {
        String org = FleetDb.org();
        Boolean active = db.jdbc().sql("SELECT \"isActive\" FROM \"HrEmployee\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(d.get("employeeId"), org).query(Boolean.class).optional()
                .orElse(false);
        Set<String> classes = new LinkedHashSet<>(db.jdbc().sql("SELECT \"categoryId\" FROM \"FleetDriverQualification\""
                + " WHERE \"organizationId\" = ? AND \"driverId\" = ?").params(org, d.get("id")).query(String.class)
                .list());
        return new DriverFacts((String) d.get("code"), (String) d.get("status"), Boolean.TRUE.equals(active),
                (String) d.get("licenceNumber"), (LocalDate) d.get("licenceExpiresOn"), (String) d.get("permitNumber"),
                (LocalDate) d.get("permitExpiresOn"), (LocalDate) d.get("medicalExpiresOn"), classes);
    }

    /** Policy facts of a vehicle and its class ({@code lock} = the vehicle row {@code FOR UPDATE}). */
    public VehicleFacts vehicleFacts(String vehicleId, boolean lock) {
        Map<String, Object> v = db.require(com.inventoryerp.fleet.domain.FleetVehicle.class, vehicleId, lock,
                "Vehicle not found");
        Map<String, Object> c = db.byId(FleetVehicleCategory.class, (String) v.get("categoryId"), false);
        return new VehicleFacts((String) v.get("code"), (String) v.get("status"), (String) v.get("categoryId"),
                c == null ? null : (String) c.get("code"), c == null ? null : (String) c.get("name"),
                c == null ? null : (String) c.get("kind"));
    }

    /**
     * Drivers with dated documents (licence, medical, permit) that are expired or expiring on {@code asOf}: one row
     * per document, soonest first. {@code due} = attention (default), expired, expiring or all.
     */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> documentsDue(String due, LocalDate asOf, Integer page, Integer pageSize) {
        List<Map<String, Object>> all = documentRows(FleetDb.org(), due == null ? "attention" : due,
                asOf == null ? today() : asOf);
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null ? 20 : Math.max(1, Math.min(200, pageSize));
        int fromIdx = Math.min(all.size(), (p - 1) * size);
        return PageResult.of(all.subList(fromIdx, Math.min(all.size(), fromIdx + size)), p, size, all.size());
    }

    /** Expired and expiring driver documents of {@code organizationId} — for the scheduled reminder job (1.9). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> documentsDueForOrganization(String organizationId, LocalDate asOf) {
        return documentRows(organizationId, "attention", asOf);
    }

    private List<Map<String, Object>> documentRows(String org, String due, LocalDate asOf) {
        List<Object> params = new ArrayList<>(List.of(org));
        String clause = "all".equals(due) ? "true" : documentsClause(due, "d", params, asOf);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT d.*, " + EMPLOYEE_COLUMNS
                + " FROM \"FleetDriver\" d LEFT JOIN \"HrEmployee\" e ON e.id = d.\"employeeId\" AND"
                + " e.\"organizationId\" = d.\"organizationId\" WHERE d.\"organizationId\" = ? AND d.\"deletedAt\" IS NULL"
                + " AND d.status <> 'inactive' AND (" + clause + ")").params(params))) {
            int lead = ((Number) r.get("reminderLeadDays")).intValue();
            for (Map.Entry<String, String> doc : DOCUMENTS.entrySet()) {
                LocalDate expires = (LocalDate) SqlRows.value(r.get(doc.getValue()));
                if (expires == null) {
                    continue;
                }
                String state = ComplianceState.of(expires, lead, asOf);
                boolean keep = switch (due) {
                    case "expired" -> ComplianceState.EXPIRED.equals(state);
                    case "expiring" -> ComplianceState.EXPIRING.equals(state);
                    case "all" -> true;
                    default -> ComplianceState.EXPIRED.equals(state) || ComplianceState.EXPIRING.equals(state);
                };
                if (keep) {
                    out.add(Obj.of("driverId", r.get("id"), "driverCode", r.get("code"), "driverName",
                            name(r.get("e_first"), r.get("e_last")), "employeeCode", r.get("e_code"),
                            "document", doc.getKey(), "number", "licence".equals(doc.getKey()) ? r.get("licenceNumber")
                                    : "permit".equals(doc.getKey()) ? r.get("permitNumber") : null,
                            "expiresOn", expires, "state", state,
                            "daysToExpiry", ComplianceState.daysToExpiry(expires, asOf)));
                }
            }
        }
        out.sort((a, b) -> ((LocalDate) a.get("expiresOn")).compareTo((LocalDate) b.get("expiresOn")));
        return out;
    }

    /** hr employees for the profile form ({@code search} on code / name), each with the linked driver code. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> employees(String search) {
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        String filter = "";
        if (FleetVehicleService.notBlank(search)) {
            filter = " AND (e.\"employeeCode\" ILIKE ? OR e.\"firstName\" ILIKE ? OR e.\"lastName\" ILIKE ?)";
            String like = "%" + search.trim().replace("%", "\\%").replace("_", "\\_") + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        return SqlRows.list(db.jdbc().sql("SELECT e.id, e.\"employeeCode\", e.\"firstName\", e.\"lastName\", e.phone,"
                + " e.\"isActive\", d.id AS \"driverId\", d.code AS \"driverCode\" FROM \"HrEmployee\" e"
                + " LEFT JOIN \"FleetDriver\" d ON d.\"employeeId\" = e.id AND d.\"organizationId\" = e.\"organizationId\""
                + " AND d.\"deletedAt\" IS NULL WHERE e.\"organizationId\" = ? AND e.\"deletedAt\" IS NULL" + filter
                + " ORDER BY e.\"isActive\" DESC, e.\"firstName\", e.\"lastName\" LIMIT 50").params(params));
    }

    // ------------------------------------------------------------------ writes

    /** Register a profile for an hr employee (one live profile per employee). */
    @Transactional
    public Map<String, Object> create(DriverInput in) {
        Map<String, Object> emp = employee(in.employeeId());
        String holder = db.jdbc().sql("SELECT code FROM \"FleetDriver\" WHERE \"organizationId\" = ? AND \"employeeId\" = ?"
                + " AND \"deletedAt\" IS NULL LIMIT 1").params(FleetDb.org(), in.employeeId()).query(String.class)
                .optional().orElse(null);
        if (holder != null) {
            throw ApiException.conflict("Employee " + emp.get("employeeCode") + " already has driver profile " + holder);
        }
        assertDates(in.licenceIssuedOn(), in.licenceExpiresOn());
        String licence = normalizeLicence(in.licenceNumber());
        String code = FleetVehicleService.notBlank(in.code()) ? FleetCategoryService.normalizeCode(in.code()) : nextCode();
        assertUnique(null, code, licence);
        List<String> classes = categories(in.qualifiedCategoryIds());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("employeeId", in.employeeId());
        data.put("driverType", in.driverType() == null ? "driver" : in.driverType());
        data.put("licenceNumber", licence);
        data.put("licenceClasses", licenceClasses(in.licenceClasses()));
        data.put("licenceIssuer", FleetVehicleService.trim(in.licenceIssuer()));
        data.put("licenceIssuedOn", in.licenceIssuedOn());
        data.put("licenceExpiresOn", in.licenceExpiresOn());
        data.put("medicalExpiresOn", in.medicalExpiresOn());
        data.put("permitNumber", FleetVehicleService.trim(in.permitNumber()));
        data.put("permitExpiresOn", in.permitExpiresOn());
        data.put("reminderLeadDays", in.reminderLeadDays() == null ? defaultLeadDays() : in.reminderLeadDays());
        data.put("status", "active");
        data.put("notes", FleetVehicleService.trim(in.notes()));
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> row = db.insert(FleetDriver.class, data);
        String id = (String) row.get("id");
        replaceQualifications(id, classes);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, auditView(row, classes)));
        return get(id);
    }

    /** Edit a profile; the status moves freely between active / suspended / inactive (suspension needs a reason). */
    @Transactional
    public Map<String, Object> update(String id, DriverPatch in) {
        Map<String, Object> before = require(id, true);
        List<String> clear = in.clear() == null ? List.of() : in.clear();
        for (String f : clear) {
            if (!DriverCommands.DRIVER_CLEARABLE.contains(f)) {
                throw ApiException.badRequest("Field " + f + " cannot be cleared");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, "code", in.code() == null ? null : FleetCategoryService.normalizeCode(in.code()));
        put(data, "driverType", in.driverType());
        put(data, "licenceNumber", normalizeLicence(in.licenceNumber()));
        if (in.licenceClasses() != null) {
            data.put("licenceClasses", licenceClasses(in.licenceClasses()));
        }
        put(data, "licenceIssuer", FleetVehicleService.trim(in.licenceIssuer()));
        put(data, "licenceIssuedOn", in.licenceIssuedOn());
        put(data, "licenceExpiresOn", in.licenceExpiresOn());
        put(data, "medicalExpiresOn", in.medicalExpiresOn());
        put(data, "permitNumber", FleetVehicleService.trim(in.permitNumber()));
        put(data, "permitExpiresOn", in.permitExpiresOn());
        put(data, "reminderLeadDays", in.reminderLeadDays());
        put(data, "status", in.status());
        put(data, "statusReason", FleetVehicleService.trim(in.statusReason()));
        put(data, "notes", FleetVehicleService.trim(in.notes()));
        for (String f : clear) {
            data.put(f, null);
        }
        Map<String, Object> merged = new LinkedHashMap<>(before);
        merged.putAll(data);
        assertDates((LocalDate) merged.get("licenceIssuedOn"), (LocalDate) merged.get("licenceExpiresOn"));
        if ("suspended".equals(in.status()) && !"suspended".equals(before.get("status"))
                && !FleetVehicleService.notBlank((String) merged.get("statusReason"))) {
            throw ApiException.badRequest("A reason is required to suspend a driver");
        }
        if ("active".equals(in.status()) && !"active".equals(before.get("status")) && in.statusReason() == null) {
            data.put("statusReason", null);
        }
        assertUnique(id, (String) data.get("code"), (String) data.get("licenceNumber"));
        List<String> classes = in.qualifiedCategoryIds() == null ? null : categories(in.qualifiedCategoryIds());
        if (data.isEmpty() && classes == null) {
            return get(id);
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetDriver.class, id, data);
        if (classes != null) {
            replaceQualifications(id, classes);
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, auditView(after, classes)));
        return get(id);
    }

    /** Soft delete of a profile without assignments, check-outs or claims (otherwise mark it inactive). */
    @Transactional
    public void delete(String id) {
        Map<String, Object> d = require(id, true);
        String org = FleetDb.org();
        long used = db.jdbc().sql("SELECT (SELECT count(*) FROM \"FleetAssignment\" WHERE \"organizationId\" = ? AND"
                + " \"driverId\" = ?) + (SELECT count(*) FROM \"FleetCheckout\" WHERE \"organizationId\" = ? AND"
                + " \"driverId\" = ?) + (SELECT count(*) FROM \"FleetDriverClaim\" WHERE \"organizationId\" = ? AND"
                + " \"driverId\" = ?)").params(org, id, org, id, org, id).query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("Driver " + d.get("code") + " has assignments, check-outs or claims; mark the"
                    + " profile inactive instead");
        }
        db.update(FleetDriver.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "delete", d, null));
    }

    // ------------------------------------------------------------------ helpers

    /** Profile + employee + qualifications + document states + open check-out. */
    Map<String, Object> load(String id) {
        Map<String, Object> r = SqlRows.one(db.jdbc().sql("SELECT d.*, " + EMPLOYEE_COLUMNS + " FROM \"FleetDriver\" d"
                + " LEFT JOIN \"HrEmployee\" e ON e.id = d.\"employeeId\" AND e.\"organizationId\" = d.\"organizationId\""
                + " WHERE d.id = ? AND d.\"organizationId\" = ? AND d.\"deletedAt\" IS NULL").params(id, FleetDb.org()));
        if (r == null) {
            throw ApiException.notFound("Driver not found");
        }
        List<Map<String, Object>> one = new ArrayList<>(List.of(decorate(r, today())));
        attachQualifications(one);
        attachOpenCheckouts(one);
        return one.getFirst();
    }

    private Map<String, Object> decorate(Map<String, Object> r, LocalDate today) {
        Map<String, Object> d = SqlRows.as(FleetDriver.class, r);
        d.put("name", name(r.get("e_first"), r.get("e_last")));
        d.put("employee", r.get("e_code") == null && r.get("e_first") == null ? null : Obj.of("id", r.get("employeeId"),
                "employeeCode", r.get("e_code"), "firstName", r.get("e_first"), "lastName", r.get("e_last"),
                "phone", r.get("e_phone"), "email", r.get("e_email"), "isActive", r.get("e_active"),
                "userId", r.get("e_user")));
        int lead = ((Number) d.get("reminderLeadDays")).intValue();
        Map<String, Object> docs = new LinkedHashMap<>();
        String worst = null;
        for (Map.Entry<String, String> doc : DOCUMENTS.entrySet()) {
            LocalDate expires = (LocalDate) d.get(doc.getValue());
            String state = ComplianceState.of(expires, lead, today);
            boolean onFile = expires != null || ("licence".equals(doc.getKey()) && d.get("licenceNumber") != null)
                    || ("permit".equals(doc.getKey()) && d.get("permitNumber") != null);
            docs.put(doc.getKey(), onFile ? Obj.of("expiresOn", expires, "state", state,
                    "daysToExpiry", ComplianceState.daysToExpiry(expires, today)) : null);
            if (ComplianceState.EXPIRED.equals(state)) {
                worst = ComplianceState.EXPIRED;
            } else if (ComplianceState.EXPIRING.equals(state) && worst == null) {
                worst = ComplianceState.EXPIRING;
            }
        }
        d.put("documents", docs);
        d.put("documentState", worst == null ? ComplianceState.VALID : worst);
        return d;
    }

    /** Adds {@code qualifications: [{id, code, name, kind}]} to each driver row (one query). */
    private void attachQualifications(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, List<Map<String, Object>>> byDriver = new HashMap<>();
        for (Map<String, Object> q : SqlRows.list(db.jdbc().sql("SELECT q.\"driverId\", c.id, c.code, c.name, c.kind"
                + " FROM \"FleetDriverQualification\" q JOIN \"FleetVehicleCategory\" c ON c.id = q.\"categoryId\""
                + " WHERE q.\"organizationId\" = ? AND q.\"driverId\" = ANY(?::text[]) ORDER BY c.\"sortOrder\", c.code")
                .params(FleetDb.org(), ids(rows)))) {
            byDriver.computeIfAbsent((String) q.get("driverId"), k -> new ArrayList<>()).add(Obj.of("id", q.get("id"),
                    "code", q.get("code"), "name", q.get("name"), "kind", q.get("kind")));
        }
        for (Map<String, Object> r : rows) {
            r.put("qualifications", byDriver.getOrDefault((String) r.get("id"), List.of()));
        }
    }

    /** Adds {@code openCheckout: {id, code, vehicleId, vehicleCode, checkedOutAt} | null} to each driver row. */
    private void attachOpenCheckouts(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> open = new HashMap<>();
        for (Map<String, Object> c : SqlRows.list(db.jdbc().sql("SELECT c.id, c.code, c.\"driverId\", c.\"vehicleId\","
                + " c.\"checkedOutAt\", v.code AS \"vehicleCode\", v.\"registrationPlate\" AS \"vehicleRegistrationPlate\""
                + " FROM \"FleetCheckout\" c JOIN \"FleetVehicle\" v ON v.id = c.\"vehicleId\" WHERE c.\"organizationId\" = ?"
                + " AND c.status = 'open' AND c.\"driverId\" = ANY(?::text[])").params(FleetDb.org(), ids(rows)))) {
            open.put((String) c.get("driverId"), c);
        }
        for (Map<String, Object> r : rows) {
            r.put("openCheckout", open.get((String) r.get("id")));
        }
    }

    private static String[] ids(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> (String) r.get("id")).toArray(String[]::new);
    }

    /** SQL predicate over the dated documents of alias {@code d} (binds the as-of date per document). */
    private static String documentsClause(String due, String a, List<Object> params, LocalDate asOf) {
        List<String> parts = new ArrayList<>();
        for (String col : DOCUMENTS.values()) {
            String c = a + ".\"" + col + "\"";
            switch (due) {
                case "expired" -> {
                    parts.add(c + " < ?");
                    params.add(Date.valueOf(asOf));
                }
                case "expiring" -> {
                    parts.add("(" + c + " >= ? AND " + c + " <= ?::date + " + a + ".\"reminderLeadDays\")");
                    params.add(Date.valueOf(asOf));
                    params.add(Date.valueOf(asOf));
                }
                default -> {
                    parts.add(c + " <= ?::date + " + a + ".\"reminderLeadDays\"");
                    params.add(Date.valueOf(asOf));
                }
            }
        }
        return String.join(" OR ", parts);
    }

    private Map<String, Object> employee(String employeeId) {
        Map<String, Object> e = SqlRows.one(db.jdbc().sql("SELECT id, \"employeeCode\", \"isActive\" FROM \"HrEmployee\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(employeeId, FleetDb.org()));
        if (e == null) {
            throw ApiException.badRequest("Employee not found");
        }
        return e;
    }

    /** Validated, de-duplicated class ids (each must be a class of the organization). */
    private List<String> categories(List<String> ids) {
        List<String> out = new ArrayList<>(new LinkedHashSet<>(ids == null ? List.of() : ids));
        for (String c : out) {
            if (db.byId(FleetVehicleCategory.class, c, false) == null) {
                throw ApiException.badRequest("Vehicle class not found");
            }
        }
        return out;
    }

    private void replaceQualifications(String driverId, List<String> categoryIds) {
        String org = FleetDb.org();
        db.jdbc().sql("DELETE FROM \"FleetDriverQualification\" WHERE \"organizationId\" = ? AND \"driverId\" = ?")
                .params(org, driverId).update();
        for (String c : categoryIds) {
            db.jdbc().sql("INSERT INTO \"FleetDriverQualification\" (id, \"organizationId\", \"driverId\", \"categoryId\","
                    + " \"createdAt\") VALUES (?, ?, ?, ?, " + FleetDb.NOW + ")")
                    .params(UUID.randomUUID().toString(), org, driverId, c).update();
        }
    }

    private void assertUnique(String exceptId, String code, String licence) {
        if (code != null) {
            String other = holder("code", code, exceptId);
            if (other != null) {
                throw ApiException.conflict("A driver with code " + code + " already exists");
            }
        }
        if (licence != null) {
            String other = holder("licenceNumber", licence, exceptId);
            if (other != null) {
                throw ApiException.conflict("Licence number " + licence + " is already registered to driver " + other);
            }
        }
    }

    private String holder(String column, String value, String exceptId) {
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), value));
        if (exceptId != null) {
            params.add(exceptId);
        }
        return db.jdbc().sql("SELECT code FROM \"FleetDriver\" WHERE \"organizationId\" = ? AND \"" + column + "\" = ?"
                        + " AND \"deletedAt\" IS NULL" + (exceptId == null ? "" : " AND id <> ?") + " LIMIT 1")
                .params(params).query(String.class).optional().orElse(null);
    }

    private String nextCode() {
        for (int i = 0; i < 20; i++) {
            String code = sequence.next("fleet_driver", "DRV-", 4);
            if (holder("code", code, null) == null) {
                return code;
            }
        }
        throw ApiException.conflict("Could not allocate a driver code; enter one explicitly");
    }

    private int defaultLeadDays() {
        Number n = settings.resolveNumber(FleetComplianceService.SETTING_LEAD_DAYS, SettingContext.EMPTY);
        return n == null ? 30 : Math.max(0, Math.min(365, n.intValue()));
    }

    private static void assertDates(LocalDate issuedOn, LocalDate expiresOn) {
        if (issuedOn != null && expiresOn != null && expiresOn.isBefore(issuedOn)) {
            throw ApiException.badRequest("The licence expiry date must be on or after the issue date");
        }
    }

    private static Map<String, Object> auditView(Map<String, Object> row, List<String> classes) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        if (classes != null) {
            out.put("qualifiedCategoryIds", classes);
        }
        return out;
    }

    /** Upper case without spaces; blank → null. */
    static String normalizeLicence(String s) {
        String t = FleetVehicleService.trim(s);
        return t == null ? null : t.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String[] licenceClasses(List<String> classes) {
        if (classes == null) {
            return new String[0];
        }
        return classes.stream().map(c -> c.trim().toUpperCase(Locale.ROOT)).filter(c -> !c.isEmpty()).distinct()
                .toArray(String[]::new);
    }

    static String name(Object first, Object last) {
        String n = ((first == null ? "" : first.toString()) + " " + (last == null ? "" : last.toString())).trim();
        return n.isEmpty() ? null : n;
    }

    private static void put(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    static LocalDate today() {
        return LocalDate.now(BusinessZone.get());
    }
}
