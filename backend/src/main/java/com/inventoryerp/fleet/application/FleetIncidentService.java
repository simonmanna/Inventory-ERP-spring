package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FleetIncidentCommands.ClaimInput;
import com.inventoryerp.fleet.application.FleetIncidentCommands.ClaimPatch;
import com.inventoryerp.fleet.application.FleetIncidentCommands.ClaimQuery;
import com.inventoryerp.fleet.application.FleetIncidentCommands.ClaimStatusChange;
import com.inventoryerp.fleet.application.FleetIncidentCommands.IncidentInput;
import com.inventoryerp.fleet.application.FleetIncidentCommands.IncidentPatch;
import com.inventoryerp.fleet.application.FleetIncidentCommands.IncidentQuery;
import com.inventoryerp.fleet.application.FleetIncidentCommands.IncidentStatusChange;
import com.inventoryerp.fleet.application.FleetIncidentCommands.ThirdParty;
import com.inventoryerp.fleet.domain.FleetIncident;
import com.inventoryerp.fleet.domain.FleetIncidentAttachment;
import com.inventoryerp.fleet.domain.FleetInsuranceClaim;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Incidents / accidents and their insurance claims (fleet 1.7): report (numbered {@code INC-00001}), edit, status
 * workflow ({@link IncidentPolicy}), photos / documents (vehicle attachments in the kernel file store, linked to the
 * incident), claims with claimed / approved / received amounts. The repair link is a free reference plus an optional
 * 1.6 work-order id stored without a foreign key (work orders are built in a parallel sub-module). Claims are a
 * tracking record: insurer receipts are booked by treasury / AR, not here.
 */
@Service
public class FleetIncidentService {

    static final String AUDIT_ENTITY = "FleetIncident";
    private static final Duration FUTURE_SKEW = Duration.ofMinutes(5);

    private final FleetDb db;
    private final FleetVehicleService vehicles;
    private final FleetAttachmentService attachments;
    private final FleetEmployeeLookup employees;
    private final FilesService files;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;

    public FleetIncidentService(FleetDb db, FleetVehicleService vehicles, FleetAttachmentService attachments,
            FleetEmployeeLookup employees, FilesService files, SequenceService sequence, AuditService audit,
            EventBus events) {
        this.db = db;
        this.vehicles = vehicles;
        this.attachments = attachments;
        this.employees = employees;
        this.files = files;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ incidents

    /** Paged incidents with vehicle, driver and claim counts. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(IncidentQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE i.\"organizationId\" = ? AND i.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (FleetVehicleService.notBlank(q.search())) {
            where.append(" AND (i.\"incidentNumber\" ILIKE ? OR i.description ILIKE ? OR i.location ILIKE ?"
                    + " OR v.code ILIKE ? OR v.\"registrationPlate\" ILIKE ? OR i.\"policeReportNumber\" ILIKE ?)");
            String like = like(q.search());
            for (int i = 0; i < 6; i++) {
                params.add(like);
            }
        }
        eq(where, params, "i.status", q.status());
        eq(where, params, "i.\"vehicleId\"", q.vehicleId());
        eq(where, params, "i.\"incidentType\"", q.incidentType());
        range(where, params, "i.\"occurredAt\"", q.from(), q.to());
        String from = " FROM \"FleetIncident\" i JOIN \"FleetVehicle\" v ON v.id = i.\"vehicleId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        params.add(size);
        params.add((page - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT i.*, v.code AS "v_code", v."registrationPlate" AS "v_plate",
                       e."employeeCode" AS "e_code", e."firstName" AS "e_first", e."lastName" AS "e_last",
                       (SELECT count(*) FROM "FleetInsuranceClaim" c WHERE c."incidentId" = i.id
                          AND c."organizationId" = i."organizationId") AS "claimCount"
                """ + from + " LEFT JOIN \"HrEmployee\" e ON e.id = i.\"driverEmployeeId\" AND e.\"organizationId\" ="
                + " i.\"organizationId\"" + where + " ORDER BY i.\"occurredAt\" DESC, i.id LIMIT ? OFFSET ?")
                .params(params))) {
            Map<String, Object> m = SqlRows.as(FleetIncident.class, r);
            m.put("vehicle", Obj.of("id", r.get("vehicleId"), "code", r.get("v_code"), "registrationPlate",
                    r.get("v_plate")));
            m.put("driver", r.get("driverEmployeeId") == null ? null : FleetEmployeeLookup.shape(Obj.of("id",
                    r.get("driverEmployeeId"), "employeeCode", r.get("e_code"), "firstName", r.get("e_first"),
                    "lastName", r.get("e_last"))));
            m.put("claimCount", r.get("claimCount"));
            rows.add(m);
        }
        return PageResult.of(rows, page, size, total);
    }

    /** Detail: incident, vehicle, driver, claims, photos / documents and the fines raised for it. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> i = new LinkedHashMap<>(incident(id, false));
        String org = FleetDb.org();
        Map<String, Object> v = vehicles.require((String) i.get("vehicleId"), false);
        i.put("vehicle", Obj.of("id", v.get("id"), "code", v.get("code"), "registrationPlate",
                v.get("registrationPlate"), "status", v.get("status"), "costCenterId", v.get("costCenterId")));
        i.put("driver", employees.ref((String) i.get("driverEmployeeId")));
        i.put("claims", SqlRows.list(FleetInsuranceClaim.class, db.jdbc().sql("SELECT * FROM \"FleetInsuranceClaim\""
                + " WHERE \"organizationId\" = ? AND \"incidentId\" = ? ORDER BY \"createdAt\"").params(org, id)));
        i.put("attachments", attachmentsOf(id));
        i.put("fines", SqlRows.list(db.jdbc().sql("SELECT id, \"fineNumber\", offence, amount, status, \"chargedTo\""
                + " FROM \"FleetFine\" WHERE \"organizationId\" = ? AND \"incidentId\" = ? AND \"deletedAt\" IS NULL"
                + " ORDER BY \"offenceAt\"").params(org, id)));
        return i;
    }

    /** Report an incident; optionally moves the vehicle to {@code breakdown} (source incident). */
    @Transactional
    public Map<String, Object> create(IncidentInput in) {
        Map<String, Object> v = vehicles.require(in.vehicleId(), true);
        employees.assertEmployee(blank(in.driverEmployeeId()), "Driver (employee) not found");
        notFuture(in.occurredAt());
        String number = nextNumber();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("incidentNumber", number);
        data.put("vehicleId", in.vehicleId());
        data.put("driverEmployeeId", blank(in.driverEmployeeId()));
        data.put("occurredAt", in.occurredAt());
        data.put("location", FleetVehicleService.trim(in.location()));
        data.put("latitude", in.latitude());
        data.put("longitude", in.longitude());
        data.put("incidentType", in.incidentType() == null ? "collision" : in.incidentType());
        data.put("severity", in.severity() == null ? "minor" : in.severity());
        data.put("description", in.description().trim());
        data.put("damageDescription", FleetVehicleService.trim(in.damageDescription()));
        data.put("estimatedCost", in.estimatedCost());
        data.put("atFault", in.atFault() == null ? "unknown" : in.atFault());
        data.put("injuries", FleetVehicleService.trim(in.injuries()));
        data.put("thirdParties", thirdParties(in.thirdParties()));
        data.put("policeReportNumber", FleetVehicleService.trim(in.policeReportNumber()));
        data.put("policeStation", FleetVehicleService.trim(in.policeStation()));
        data.put("workOrderId", blank(in.workOrderId()));
        data.put("repairReference", FleetVehicleService.trim(in.repairReference()));
        data.put("repairCost", in.repairCost());
        data.put("status", "reported");
        data.put("notes", FleetVehicleService.trim(in.notes()));
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> row = db.insert(FleetIncident.class, data);
        String id = (String) row.get("id");
        if (Boolean.TRUE.equals(in.markVehicleBreakdown())
                && !List.of(VehicleStatusPolicy.BREAKDOWN, VehicleStatusPolicy.DISPOSED).contains(v.get("status"))) {
            vehicles.changeStatus(in.vehicleId(), VehicleStatusPolicy.BREAKDOWN, "Incident " + number + ": "
                    + abbreviate(in.description().trim(), 200), "incident", id);
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, row));
        events.publish(DomainEvents.FLEET_INCIDENT_REPORTED, Obj.of("organizationId", FleetDb.org(), "incidentId", id,
                "incidentNumber", number, "vehicleId", in.vehicleId(), "incidentType", row.get("incidentType"),
                "severity", row.get("severity"), "occurredAt", in.occurredAt().toString()));
        return get(id);
    }

    /** Edit an incident that is not cancelled. */
    @Transactional
    public Map<String, Object> update(String id, IncidentPatch in) {
        Map<String, Object> before = incident(id, true);
        assertEditable(before);
        List<String> clear = in.clear() == null ? List.of() : in.clear();
        for (String f : clear) {
            if (!FleetIncidentCommands.INCIDENT_CLEARABLE.contains(f)) {
                throw ApiException.badRequest("Field " + f + " cannot be cleared");
            }
        }
        if (in.occurredAt() != null) {
            notFuture(in.occurredAt());
        }
        employees.assertEmployee(blank(in.driverEmployeeId()), "Driver (employee) not found");
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, "driverEmployeeId", blank(in.driverEmployeeId()));
        put(data, "occurredAt", in.occurredAt());
        put(data, "location", FleetVehicleService.trim(in.location()));
        put(data, "latitude", in.latitude());
        put(data, "longitude", in.longitude());
        put(data, "incidentType", in.incidentType());
        put(data, "severity", in.severity());
        put(data, "description", FleetVehicleService.trim(in.description()));
        put(data, "damageDescription", FleetVehicleService.trim(in.damageDescription()));
        put(data, "estimatedCost", in.estimatedCost());
        put(data, "atFault", in.atFault());
        put(data, "injuries", FleetVehicleService.trim(in.injuries()));
        if (in.thirdParties() != null) {
            data.put("thirdParties", thirdParties(in.thirdParties()));
        }
        put(data, "policeReportNumber", FleetVehicleService.trim(in.policeReportNumber()));
        put(data, "policeStation", FleetVehicleService.trim(in.policeStation()));
        put(data, "workOrderId", blank(in.workOrderId()));
        put(data, "repairReference", FleetVehicleService.trim(in.repairReference()));
        put(data, "repairCost", in.repairCost());
        put(data, "notes", FleetVehicleService.trim(in.notes()));
        for (String f : clear) {
            data.put(f, null);
        }
        if (data.isEmpty()) {
            return get(id);
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetIncident.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, after));
        return get(id);
    }

    /** Status workflow; closing needs every claim decided (settled, rejected or withdrawn). */
    @Transactional
    public Map<String, Object> changeStatus(String id, IncidentStatusChange in) {
        Map<String, Object> i = incident(id, true);
        String number = (String) i.get("incidentNumber");
        String from = (String) i.get("status");
        String to = in.status();
        IncidentPolicy.assertIncident(number, from, to, in.reason());
        if ("closed".equals(to)) {
            long pending = db.jdbc().sql("SELECT count(*) FROM \"FleetInsuranceClaim\" WHERE \"organizationId\" = ? AND"
                    + " \"incidentId\" = ? AND status IN ('draft', 'submitted', 'approved')").params(FleetDb.org(), id)
                    .query(Long.class).single();
            if (pending > 0) {
                throw ApiException.badRequest("Incident " + number + " has " + pending
                        + " open insurance claim(s); settle, reject or withdraw them before closing");
            }
        }
        Map<String, Object> data = Obj.of("status", to, "statusReason", FleetVehicleService.trim(in.reason()),
                "updatedBy", TenantContext.userId());
        if ("closed".equals(to)) {
            data.put("closedAt", Instant.now());
            data.put("closedBy", TenantContext.userId());
        } else if ("closed".equals(from)) {
            data.put("closedAt", null);
            data.put("closedBy", null);
        }
        db.update(FleetIncident.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "closed".equals(from) ? "reopen"
                        : "cancelled".equals(to) ? "cancel" : "update", Obj.of("status", from),
                Obj.of("status", to, "reason", FleetVehicleService.trim(in.reason()))));
        return get(id);
    }

    /** Upload a photo / document of the incident (stored on the vehicle's file store, linked here). */
    @Transactional
    public Map<String, Object> addAttachment(String incidentId, String kind, String title, String filename,
            String contentType, byte[] bytes) {
        Map<String, Object> i = incident(incidentId, false);
        assertEditable(i);
        Map<String, Object> a = attachments.upload((String) i.get("vehicleId"), kind, title == null || title.isBlank()
                ? "Incident " + i.get("incidentNumber") : title, null, filename, contentType, bytes);
        db.insert(FleetIncidentAttachment.class, Obj.of("incidentId", incidentId, "attachmentId", a.get("id")));
        return a;
    }

    /** Remove a photo / document of the incident (and the stored file). */
    @Transactional
    public void removeAttachment(String incidentId, String attachmentId) {
        Map<String, Object> i = incident(incidentId, false);
        long linked = db.jdbc().sql("SELECT count(*) FROM \"FleetIncidentAttachment\" WHERE \"organizationId\" = ? AND"
                + " \"incidentId\" = ? AND \"attachmentId\" = ?").params(FleetDb.org(), incidentId, attachmentId)
                .query(Long.class).single();
        if (linked == 0) {
            throw ApiException.notFound("Attachment not found");
        }
        attachments.delete((String) i.get("vehicleId"), attachmentId);
    }

    private List<Map<String, Object>> attachmentsOf(String incidentId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT a.*, f.filename, f."contentType", f."byteSize" FROM "FleetIncidentAttachment" l
                JOIN "FleetVehicleAttachment" a ON a.id = l."attachmentId"
                JOIN "File" f ON f.id = a."fileId" AND f."deletedAt" IS NULL
                WHERE l."organizationId" = ? AND l."incidentId" = ? ORDER BY a."createdAt" DESC""")
                .params(FleetDb.org(), incidentId))) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            Map<String, Object> signed = files.signDownload((String) r.get("fileId"));
            m.put("url", signed.get("url"));
            m.put("urlExpiresAt", signed.get("expiresAt"));
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ claims

    /** Paged claims with their incident and vehicle. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> claims(ClaimQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE c.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        eq(where, params, "c.status", q.status());
        eq(where, params, "c.\"incidentId\"", q.incidentId());
        if (FleetVehicleService.notBlank(q.search())) {
            where.append(" AND (c.\"claimNumber\" ILIKE ? OR c.\"insurerName\" ILIKE ? OR i.\"incidentNumber\" ILIKE ?"
                    + " OR v.code ILIKE ? OR v.\"registrationPlate\" ILIKE ?)");
            String like = like(q.search());
            for (int i = 0; i < 5; i++) {
                params.add(like);
            }
        }
        String from = " FROM \"FleetInsuranceClaim\" c JOIN \"FleetIncident\" i ON i.id = c.\"incidentId\""
                + " JOIN \"FleetVehicle\" v ON v.id = i.\"vehicleId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        params.add(size);
        params.add((page - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT c.*, i.\"incidentNumber\" AS \"i_number\","
                + " i.\"occurredAt\" AS \"i_at\", v.id AS \"v_id\", v.code AS \"v_code\", v.\"registrationPlate\" AS"
                + " \"v_plate\", p.name AS \"p_name\"" + from + " LEFT JOIN \"Partner\" p ON p.id = c.\"insurerPartnerId\""
                + " AND p.\"organizationId\" = c.\"organizationId\"" + where
                + " ORDER BY c.\"createdAt\" DESC, c.id LIMIT ? OFFSET ?").params(params))) {
            Map<String, Object> m = SqlRows.as(FleetInsuranceClaim.class, r);
            m.put("incident", Obj.of("id", r.get("incidentId"), "incidentNumber", r.get("i_number"), "occurredAt",
                    r.get("i_at")));
            m.put("vehicle", Obj.of("id", r.get("v_id"), "code", r.get("v_code"), "registrationPlate",
                    r.get("v_plate")));
            m.put("insurer", r.get("p_name") != null ? r.get("p_name") : r.get("insurerName"));
            rows.add(m);
        }
        return PageResult.of(rows, page, size, total);
    }

    /** Open a claim (draft) on an incident that is not cancelled. */
    @Transactional
    public Map<String, Object> createClaim(String incidentId, ClaimInput in) {
        Map<String, Object> i = incident(incidentId, true);
        assertEditable(i);
        if (FleetVehicleService.notBlank(in.insurerPartnerId()) && !db.exists("Partner", in.insurerPartnerId(), true)) {
            throw ApiException.badRequest("Insurer not found");
        }
        if (!FleetVehicleService.notBlank(in.insurerPartnerId()) && !FleetVehicleService.notBlank(in.insurerName())) {
            throw ApiException.badRequest("Choose the insurer or type its name");
        }
        Map<String, Object> row = db.insert(FleetInsuranceClaim.class, Obj.of(
                "incidentId", incidentId,
                "claimNumber", FleetVehicleService.trim(in.claimNumber()),
                "insurerPartnerId", blank(in.insurerPartnerId()),
                "insurerName", FleetVehicleService.trim(in.insurerName()),
                "policyNumber", FleetVehicleService.trim(in.policyNumber()),
                "submittedOn", in.submittedOn(),
                "amountClaimed", in.amountClaimed(),
                "excessAmount", in.excessAmount(),
                "status", "draft",
                "notes", FleetVehicleService.trim(in.notes()),
                "createdBy", TenantContext.userId(),
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("FleetInsuranceClaim", (String) row.get("id"), "create", null, row));
        return row;
    }

    /** Edit a claim's reference data; the claimed amount only while draft / submitted. */
    @Transactional
    public Map<String, Object> updateClaim(String id, ClaimPatch in) {
        Map<String, Object> c = claim(id, true);
        String status = (String) c.get("status");
        if (!IncidentPolicy.CLAIM_PENDING.contains(status)) {
            throw ApiException.badRequest("A " + status + " claim can no longer be edited");
        }
        if (in.amountClaimed() != null && "approved".equals(status)) {
            throw ApiException.badRequest("The claimed amount cannot change after approval");
        }
        if (FleetVehicleService.notBlank(in.insurerPartnerId()) && !db.exists("Partner", in.insurerPartnerId(), true)) {
            throw ApiException.badRequest("Insurer not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, "claimNumber", FleetVehicleService.trim(in.claimNumber()));
        put(data, "insurerPartnerId", blank(in.insurerPartnerId()));
        put(data, "insurerName", FleetVehicleService.trim(in.insurerName()));
        put(data, "policyNumber", FleetVehicleService.trim(in.policyNumber()));
        put(data, "submittedOn", in.submittedOn());
        put(data, "amountClaimed", in.amountClaimed());
        put(data, "excessAmount", in.excessAmount());
        put(data, "notes", FleetVehicleService.trim(in.notes()));
        if (data.isEmpty()) {
            return c;
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetInsuranceClaim.class, id, data);
        audit.recordInTx(AuditInput.of("FleetInsuranceClaim", id, "update", c, after));
        return after;
    }

    /** Claim workflow: submit, approve (approved ≤ claimed), reject / withdraw (reason), settle (received ≤ approved). */
    @Transactional
    public Map<String, Object> claimStatus(String id, ClaimStatusChange in) {
        Map<String, Object> c = claim(id, true);
        String from = (String) c.get("status");
        String to = in.status();
        BigDecimal claimed = SqlRows.dec(c, "amountClaimed");
        BigDecimal approved = in.amountApproved() != null ? in.amountApproved()
                : c.get("amountApproved") == null ? null : SqlRows.dec(c, "amountApproved");
        BigDecimal received = in.amountReceived() != null ? in.amountReceived() : SqlRows.dec(c, "amountReceived");
        IncidentPolicy.assertClaim(from, to, claimed, approved, "settled".equals(to) ? received : null,
                in.reason());
        LocalDate day = in.date() != null ? in.date() : LocalDate.now(BusinessZone.get());
        Map<String, Object> data = Obj.of("status", to, "statusReason", FleetVehicleService.trim(in.reason()),
                "updatedBy", TenantContext.userId());
        switch (to) {
            case "submitted" -> {
                if (c.get("submittedOn") == null) {
                    data.put("submittedOn", day);
                }
            }
            case "approved" -> {
                data.put("amountApproved", approved);
                data.put("decidedOn", day);
            }
            case "rejected" -> data.put("decidedOn", day);
            case "settled" -> {
                data.put("amountReceived", received);
                data.put("settledOn", day);
            }
            default -> {
                // withdrawn: reason only
            }
        }
        Map<String, Object> after = db.update(FleetInsuranceClaim.class, id, data);
        audit.recordInTx(AuditInput.of("FleetInsuranceClaim", id, "approved".equals(to) ? "approve"
                : "rejected".equals(to) ? "reject" : "update", Obj.of("status", from), after));
        return after;
    }

    // ------------------------------------------------------------------ helpers

    Map<String, Object> incident(String id, boolean lock) {
        return db.require(FleetIncident.class, id, lock, "Incident not found");
    }

    private Map<String, Object> claim(String id, boolean lock) {
        return db.require(FleetInsuranceClaim.class, id, lock, "Insurance claim not found");
    }

    private static void assertEditable(Map<String, Object> i) {
        if ("cancelled".equals(i.get("status"))) {
            throw ApiException.badRequest("Incident " + i.get("incidentNumber") + " is cancelled");
        }
    }

    private String nextNumber() {
        for (int n = 0; n < 20; n++) {
            String code = sequence.next("fleet_incident", "INC-", 5);
            long used = db.jdbc().sql("SELECT count(*) FROM \"FleetIncident\" WHERE \"organizationId\" = ? AND"
                    + " \"incidentNumber\" = ?").params(FleetDb.org(), code).query(Long.class).single();
            if (used == 0) {
                return code;
            }
        }
        throw ApiException.conflict("Could not allocate an incident number");
    }

    private static String thirdParties(List<ThirdParty> parties) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (parties != null) {
            for (ThirdParty p : parties) {
                out.add(Obj.of("name", p.name().trim(), "phone", FleetVehicleService.trim(p.phone()),
                        "vehiclePlate", FleetVehicleService.normalizePlate(p.vehiclePlate()),
                        "insurer", FleetVehicleService.trim(p.insurer()),
                        "policyNumber", FleetVehicleService.trim(p.policyNumber()),
                        "notes", FleetVehicleService.trim(p.notes())));
            }
        }
        return EntityJson.toJson(out);
    }

    static void notFuture(Instant at) {
        if (at != null && at.isAfter(Instant.now().plus(FUTURE_SKEW))) {
            throw ApiException.badRequest("The date cannot be in the future");
        }
    }

    static String like(String s) {
        return "%" + s.trim().replace("%", "\\%").replace("_", "\\_") + "%";
    }

    static void eq(StringBuilder where, List<Object> params, String column, String value) {
        if (FleetVehicleService.notBlank(value)) {
            where.append(" AND ").append(column).append(" = ?");
            params.add(value);
        }
    }

    /** {@code column} within business days {@code from}..{@code to} (inclusive). */
    static void range(StringBuilder where, List<Object> params, String column, LocalDate from, LocalDate to) {
        if (from != null) {
            where.append(" AND ").append(column).append(" >= ?");
            params.add(SqlRows.ts(from.atStartOfDay(BusinessZone.get()).toInstant()));
        }
        if (to != null) {
            where.append(" AND ").append(column).append(" < ?");
            params.add(SqlRows.ts(to.plusDays(1).atStartOfDay(BusinessZone.get()).toInstant()));
        }
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static void put(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
