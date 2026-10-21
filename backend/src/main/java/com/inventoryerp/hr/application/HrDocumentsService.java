package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.nz;
import static com.inventoryerp.hr.application.HrDb.org;
import static com.inventoryerp.hr.application.HrDb.truthy;
import static com.inventoryerp.hr.application.HrDb.user;

import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.hr.domain.HrEmployeeDocument;
import com.inventoryerp.hr.domain.HrEmployeeTraining;
import com.inventoryerp.hr.domain.HrTrainingProgram;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Employee paperwork and training (port of {@code hr-documents.service.ts}); the bytes live in {@code File}, this
 * service owns only the HR metadata. Gated by {@code hr:document} / {@code hr:training}.
 */
@Service
public class HrDocumentsService {

    static final List<String> DOCUMENT_TYPES = List.of("CONTRACT", "OFFER_LETTER", "ID_DOCUMENT", "CERTIFICATE",
            "WORK_PERMIT", "TRAINING_CERTIFICATE", "DISCIPLINARY", "OTHER");
    static final List<String> TRAINING_STATUSES = List.of("ENROLLED", "IN_PROGRESS", "COMPLETED", "FAILED",
            "CANCELLED");
    static final String[] EMPLOYEE_REF = {"id", "employeeCode", "firstName", "lastName"};

    private final HrDb hr;

    public HrDocumentsService(HrDb hr) {
        this.hr = hr;
    }

    // ─── employee documents ───

    public Map<String, Object> list(Map<String, String> query) {
        HrDb.Where w = hr.where(HrEmployeeDocument.class).eqIf("employeeId", query.get("employeeId"))
                .eqIf("documentType", query.get("documentType"));
        if (truthy(query.get("expiringBefore"))) {
            w.and("\"expiresAt\" <= ?", SqlRows.ts(HrDb.jsDate(query.get("expiringBefore"))));
        }
        List<Map<String, Object>> rows = w.list("\"createdAt\" DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", EMPLOYEE_REF);
        return HrDb.rows(rows, w.count());
    }

    /** Documents due to expire within {@code days}. */
    public Map<String, Object> expiring(double days) {
        if (Double.isNaN(days) || Double.isInfinite(days)) {
            throw new IllegalStateException("Invalid value for argument `lte`: Provided Date object is invalid.");
        }
        Instant until = Instant.now().plus(Duration.ofDays((long) days));
        List<Map<String, Object>> rows = hr.where(HrEmployeeDocument.class)
                .and("\"expiresAt\" IS NOT NULL AND \"expiresAt\" <= ?", SqlRows.ts(until))
                .list("\"expiresAt\" ASC", 200, 0);
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", EMPLOYEE_REF);
        return HrDb.rows(rows, rows.size());
    }

    private void assertEmployee(Object employeeId) {
        if (hr.byId(HrEmployee.class, employeeId) == null) {
            throw ApiException.notFound("Employee not found");
        }
    }

    private static void assertDateOrder(Object issuedAt, Object expiresAt) {
        if (!truthy(issuedAt) || !truthy(expiresAt)) {
            return;
        }
        Instant i;
        Instant e;
        try {
            i = HrDb.jsDate(issuedAt);
            e = HrDb.jsDate(expiresAt);
        } catch (RuntimeException invalid) {
            return;
        }
        if (e.isBefore(i)) {
            throw ApiException.badRequest("expiresAt cannot be before issuedAt");
        }
    }

    private static void assertDocumentType(Map<String, Object> dto) {
        if (truthy(dto.get("documentType")) && !DOCUMENT_TYPES.contains(dto.get("documentType"))) {
            throw ApiException.badRequest("Invalid documentType: " + PosSql.jsString(dto.get("documentType")));
        }
    }

    public Map<String, Object> create(Map<String, Object> dto) {
        if (!truthy(dto.get("employeeId")) || !truthy(dto.get("title"))) {
            throw ApiException.badRequest("employeeId and title are required");
        }
        assertDocumentType(dto);
        assertEmployee(dto.get("employeeId"));
        assertDateOrder(dto.get("issuedAt"), dto.get("expiresAt"));
        return hr.audited(() -> hr.insert(HrEmployeeDocument.class, Obj.of("organizationId", org(),
                "employeeId", dto.get("employeeId"), "documentType", nz(dto.get("documentType"), "OTHER"),
                "title", dto.get("title"), "fileId", dto.get("fileId"), "documentNumber", dto.get("documentNumber"),
                "issuedAt", truthy(dto.get("issuedAt")) ? HrDb.jsDate(dto.get("issuedAt")) : null,
                "expiresAt", truthy(dto.get("expiresAt")) ? HrDb.jsDate(dto.get("expiresAt")) : null,
                "notes", dto.get("notes"), "createdBy", user())),
                row -> AuditInput.of("HrEmployeeDocument", (String) row.get("id"), "create", null, Obj.of(
                        "employeeId", row.get("employeeId"), "documentType", row.get("documentType"),
                        "title", row.get("title"), "expiresAt", row.get("expiresAt"))));
    }

    public Map<String, Object> update(String id, Map<String, Object> dto) {
        Map<String, Object> row = hr.byId(HrEmployeeDocument.class, id);
        if (row == null) {
            throw ApiException.notFound("Document not found");
        }
        assertDocumentType(dto);
        assertDateOrder(nz(dto.get("issuedAt"), row.get("issuedAt")), nz(dto.get("expiresAt"), row.get("expiresAt")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updatedBy", user());
        for (String f : List.of("documentType", "title", "fileId", "documentNumber", "notes")) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f));
            }
        }
        for (String f : List.of("issuedAt", "expiresAt")) {
            if (dto.containsKey(f)) {
                data.put(f, truthy(dto.get(f)) ? HrDb.jsDate(dto.get(f)) : null);
            }
        }
        return hr.audited(() -> hr.update(HrEmployeeDocument.class, id, data),
                u -> AuditInput.of("HrEmployeeDocument", id, "update",
                        Obj.of("title", row.get("title"), "documentType", row.get("documentType"),
                                "expiresAt", row.get("expiresAt")),
                        Obj.of("title", u.get("title"), "documentType", u.get("documentType"),
                                "expiresAt", u.get("expiresAt"))));
    }

    public Map<String, Object> remove(String id) {
        Map<String, Object> row = hr.byId(HrEmployeeDocument.class, id);
        if (row == null) {
            throw ApiException.notFound("Document not found");
        }
        return hr.audited(() -> hr.softDelete(HrEmployeeDocument.class, id, Obj.of("updatedBy", user())),
                r -> AuditInput.of("HrEmployeeDocument", id, "delete", Obj.of("employeeId", row.get("employeeId"),
                        "title", row.get("title"), "documentType", row.get("documentType")), null));
    }

    // ─── training programs ───

    public Map<String, Object> listPrograms(Map<String, String> query) {
        HrDb.Where w = hr.where(HrTrainingProgram.class).search(query.get("search"), "code", "name");
        List<Map<String, Object>> rows = w.list("name ASC", HrDb.take(query.get("take"), 100, 200), 0);
        hr.counts(rows, "enrolments", "HrEmployeeTraining", "programId");
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> createProgram(Map<String, Object> dto) {
        if (!truthy(dto.get("code")) || !truthy(dto.get("name"))) {
            throw ApiException.badRequest("code and name are required");
        }
        String code = HrOrgService.upper(dto.get("code"));
        if (hr.where(HrTrainingProgram.class).eq("code", code).first() != null) {
            throw ApiException.badRequest("Program code \"" + PosSql.jsString(dto.get("code")) + "\" already exists");
        }
        return hr.audited(() -> hr.insert(HrTrainingProgram.class, Obj.of("organizationId", org(), "code", code,
                "name", dto.get("name"), "description", dto.get("description"), "provider", dto.get("provider"),
                "durationHours", dto.get("durationHours"), "isActive", nz(dto.get("isActive"), true),
                "createdBy", user())),
                row -> AuditInput.of("HrTrainingProgram", (String) row.get("id"), "create", null, Obj.of(
                        "code", row.get("code"), "name", row.get("name"), "provider", row.get("provider"))));
    }

    public Map<String, Object> updateProgram(String id, Map<String, Object> dto) {
        Map<String, Object> row = hr.byId(HrTrainingProgram.class, id);
        if (row == null) {
            throw ApiException.notFound("Training program not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updatedBy", user());
        for (String f : List.of("name", "description", "provider", "durationHours", "isActive")) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f));
            }
        }
        return hr.audited(() -> hr.update(HrTrainingProgram.class, id, data),
                u -> AuditInput.of("HrTrainingProgram", id, "update",
                        Obj.of("name", row.get("name"), "isActive", row.get("isActive")),
                        Obj.of("name", u.get("name"), "isActive", u.get("isActive"))));
    }

    public Map<String, Object> removeProgram(String id) {
        Map<String, Object> row = hr.byId(HrTrainingProgram.class, id);
        if (row == null) {
            throw ApiException.notFound("Training program not found");
        }
        if (hr.where(HrEmployeeTraining.class).eq("programId", id).count() > 0) {
            throw ApiException.badRequest(
                    "Program has enrolments — deactivate it instead of deleting, so the training history survives");
        }
        return hr.audited(() -> hr.softDelete(HrTrainingProgram.class, id, Obj.of("isActive", false,
                "updatedBy", user())),
                r -> AuditInput.of("HrTrainingProgram", id, "delete", Obj.of("code", row.get("code"),
                        "name", row.get("name")), null));
    }

    // ─── enrolments ───

    public Map<String, Object> listEnrolments(Map<String, String> query) {
        HrDb.Where w = hr.where(HrEmployeeTraining.class).eqIf("employeeId", query.get("employeeId"))
                .eqIf("programId", query.get("programId")).eqIf("status", query.get("status"));
        List<Map<String, Object>> rows = w.list("\"enrolledAt\" DESC", HrDb.take(query.get("take"), 50, 200), 0);
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", EMPLOYEE_REF);
        hr.oneAll(rows, "program", HrTrainingProgram.class, "programId", "id", "code", "name", "provider");
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> enrol(Map<String, Object> dto) {
        if (!truthy(dto.get("employeeId")) || !truthy(dto.get("programId"))) {
            throw ApiException.badRequest("employeeId and programId are required");
        }
        assertEmployee(dto.get("employeeId"));
        if (hr.byId(HrTrainingProgram.class, dto.get("programId")) == null) {
            throw ApiException.notFound("Training program not found");
        }
        Map<String, Object> open = hr.where(HrEmployeeTraining.class).eq("employeeId", dto.get("employeeId"))
                .eq("programId", dto.get("programId")).and("status::text IN ('ENROLLED', 'IN_PROGRESS')").first();
        if (open != null) {
            throw ApiException.badRequest("This employee is already enrolled on that program and has not finished it");
        }
        return hr.audited(() -> hr.insert(HrEmployeeTraining.class, Obj.of("organizationId", org(),
                "employeeId", dto.get("employeeId"), "programId", dto.get("programId"), "status", "ENROLLED",
                "trainer", dto.get("trainer"), "notes", dto.get("notes"), "createdBy", user())),
                row -> AuditInput.of("HrEmployeeTraining", (String) row.get("id"), "assign", null, Obj.of(
                        "employeeId", row.get("employeeId"), "programId", row.get("programId"),
                        "status", row.get("status"))));
    }

    public Map<String, Object> updateEnrolment(String id, Map<String, Object> dto) {
        Map<String, Object> row = hr.byId(HrEmployeeTraining.class, id);
        if (row == null) {
            throw ApiException.notFound("Enrolment not found");
        }
        if (truthy(dto.get("status")) && !TRAINING_STATUSES.contains(dto.get("status"))) {
            throw ApiException.badRequest("Invalid status: " + PosSql.jsString(dto.get("status")));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updatedBy", user());
        for (String f : List.of("status", "score", "certificateFileId", "trainer", "notes")) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f));
            }
        }
        if ("IN_PROGRESS".equals(dto.get("status")) && row.get("startedAt") == null) {
            data.put("startedAt", PosDb.raw(PosSql.NOW));
        }
        if ("COMPLETED".equals(dto.get("status")) && row.get("completedAt") == null) {
            data.put("completedAt", PosDb.raw(PosSql.NOW));
        }
        return hr.audited(() -> hr.update(HrEmployeeTraining.class, id, data),
                u -> AuditInput.of("HrEmployeeTraining", id, "update",
                        Obj.of("status", row.get("status"), "score", row.get("score")),
                        Obj.of("status", u.get("status"), "score", u.get("score"), "employeeId", row.get("employeeId"))));
    }

    public Map<String, Object> removeEnrolment(String id) {
        Map<String, Object> row = hr.byId(HrEmployeeTraining.class, id);
        if (row == null) {
            throw ApiException.notFound("Enrolment not found");
        }
        return hr.audited(() -> hr.softDelete(HrEmployeeTraining.class, id, Obj.of("updatedBy", user())),
                r -> AuditInput.of("HrEmployeeTraining", id, "delete", Obj.of("employeeId", row.get("employeeId"),
                        "programId", row.get("programId"), "status", row.get("status")), null));
    }
}
