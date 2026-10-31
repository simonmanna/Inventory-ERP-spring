package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.nz;
import static com.inventoryerp.hr.application.HrDb.org;
import static com.inventoryerp.hr.application.HrDb.truthy;
import static com.inventoryerp.hr.application.HrDb.user;
import static com.inventoryerp.hr.application.HrRecruitmentService.day;

import com.inventoryerp.hr.domain.HrDepartment;
import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.hr.domain.HrEmployeeContract;
import com.inventoryerp.hr.domain.HrEmployeeDocument;
import com.inventoryerp.hr.domain.HrPosition;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Employment contract records (HR-2). A contract is drafted, then activated; activating supersedes the employee's
 * current contract under a row lock on the employee, and {@code HrEmployee.contractEndDate} follows the active
 * contract so the existing employee screens and reports stay right. Past its end date a contract expires (daily job);
 * terminating ends it early. Editing is draft-only: the signed record is never rewritten.
 */
@Service
public class HrContractService {

    static final List<String> TYPES = List.of("permanent", "fixed_term", "casual", "seasonal", "consultant",
            "apprenticeship");
    /** Types that must state an end date. */
    static final List<String> DATED_TYPES = List.of("fixed_term", "seasonal", "apprenticeship");
    private static final String[] EMPLOYEE_REF = {"id", "employeeCode", "firstName", "lastName"};
    private static final List<String> TEXT_FIELDS = List.of("positionId", "departmentId", "documentId", "notes");

    private final HrDb hr;
    private final SequenceService seq;

    public HrContractService(HrDb hr, SequenceService seq) {
        this.hr = hr;
        this.seq = seq;
    }

    public Map<String, Object> list(Map<String, String> query) {
        HrDb.Where w = hr.where(HrEmployeeContract.class).eqIf("employeeId", query.get("employeeId"))
                .eqIf("status", query.get("status")).eqIf("contractType", query.get("contractType"));
        List<Map<String, Object>> rows = w.list("\"startDate\" DESC, code DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", EMPLOYEE_REF);
        return HrDb.rows(rows, w.count());
    }

    /** Active contracts ending within {@code days} (default 60), soonest first. */
    public Map<String, Object> expiring(Map<String, String> query) {
        int days = query.get("days") == null ? 60 : HrRecruitmentService.intVal(query.get("days"));
        LocalDate until = LocalDate.now(ZoneOffset.UTC).plusDays(days);
        List<Map<String, Object>> rows = hr.where(HrEmployeeContract.class).eq("status", "active")
                .and("\"endDate\" IS NOT NULL AND \"endDate\" <= ?", java.sql.Date.valueOf(until))
                .list("\"endDate\" ASC", 200, 0);
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", EMPLOYEE_REF);
        return HrDb.rows(rows, rows.size());
    }

    public Map<String, Object> get(String id) {
        Map<String, Object> row = require(id);
        hr.onePick(row, "employee", HrEmployee.class, row.get("employeeId"), EMPLOYEE_REF);
        hr.onePick(row, "position", HrPosition.class, row.get("positionId"), "id", "name");
        hr.onePick(row, "department", HrDepartment.class, row.get("departmentId"), "id", "name");
        return row;
    }

    public Map<String, Object> create(Map<String, Object> dto) {
        if (!truthy(dto.get("employeeId")) || !truthy(dto.get("startDate"))) {
            throw ApiException.badRequest("employeeId and startDate are required");
        }
        Map<String, Object> employee = hr.byId(HrEmployee.class, dto.get("employeeId"));
        if (employee == null) {
            throw ApiException.notFound("Employee not found");
        }
        Object type = nz(dto.get("contractType"), "permanent");
        LocalDate start = day(dto.get("startDate"), "startDate");
        LocalDate end = day(dto.get("endDate"), "endDate");
        assertTerms(type, start, end);
        assertRefs(dto);
        String code = seq.next("hr_contract", "CTR-", 6);
        return hr.inTx(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("organizationId", org());
            data.put("code", code);
            data.put("employeeId", dto.get("employeeId"));
            data.put("contractType", type);
            data.put("status", "draft");
            data.put("startDate", start);
            data.put("endDate", end);
            data.put("positionId", nz(blank(dto.get("positionId")), employee.get("positionId")));
            data.put("departmentId", nz(blank(dto.get("departmentId")), employee.get("departmentId")));
            data.put("hoursPerWeek", dto.get("hoursPerWeek"));
            data.put("probationMonths", dto.get("probationMonths"));
            data.put("signedOn", day(dto.get("signedOn"), "signedOn"));
            data.put("documentId", blank(dto.get("documentId")));
            data.put("renewalOfId", dto.get("renewalOfId"));
            data.put("notes", dto.get("notes"));
            data.put("createdBy", user());
            Map<String, Object> row = hr.insert(HrEmployeeContract.class, data);
            hr.auditInTx(AuditInput.of("HrEmployeeContract", (String) row.get("id"), "create", null,
                    Obj.of("code", code, "employeeId", dto.get("employeeId"), "contractType", type)));
            return row;
        });
    }

    public Map<String, Object> update(String id, Map<String, Object> dto) {
        Map<String, Object> row = require(id);
        if (!"draft".equals(row.get("status"))) {
            throw ApiException.badRequest("Only a draft contract can be edited");
        }
        assertRefs(dto);
        Object type = nz(dto.get("contractType"), row.get("contractType"));
        LocalDate start = dto.containsKey("startDate") ? day(dto.get("startDate"), "startDate")
                : (LocalDate) row.get("startDate");
        LocalDate end = dto.containsKey("endDate") ? day(dto.get("endDate"), "endDate") : (LocalDate) row.get("endDate");
        assertTerms(type, start, end);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contractType", type);
        data.put("startDate", start);
        data.put("endDate", end);
        for (String f : TEXT_FIELDS) {
            if (dto.containsKey(f)) {
                data.put(f, blank(dto.get(f)));
            }
        }
        for (String f : List.of("hoursPerWeek", "probationMonths")) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f));
            }
        }
        if (dto.containsKey("signedOn")) {
            data.put("signedOn", day(dto.get("signedOn"), "signedOn"));
        }
        data.put("updatedBy", user());
        return hr.update(HrEmployeeContract.class, id, data);
    }

    public Map<String, Object> delete(String id) {
        Map<String, Object> row = require(id);
        if (!"draft".equals(row.get("status"))) {
            throw ApiException.badRequest("Only a draft contract can be deleted");
        }
        return hr.softDelete(HrEmployeeContract.class, id, Obj.of("updatedBy", user()));
    }

    /** Make a draft the employee's active contract; the one it replaces becomes {@code superseded}. */
    public Map<String, Object> activate(String id) {
        return hr.inTx(() -> {
            Map<String, Object> row = require(id);
            String employeeId = (String) row.get("employeeId");
            lockEmployee(employeeId);
            row = require(id);
            if (!"draft".equals(row.get("status"))) {
                throw ApiException.badRequest("Only a draft contract can be activated");
            }
            LocalDate end = (LocalDate) row.get("endDate");
            if (end != null && end.isBefore(LocalDate.now(ZoneOffset.UTC))) {
                throw ApiException.badRequest("This contract has already ended");
            }
            for (Map<String, Object> current : hr.where(HrEmployeeContract.class).eq("employeeId", employeeId)
                    .eq("status", "active").list(null)) {
                hr.update(HrEmployeeContract.class, (String) current.get("id"), Obj.of("status", "superseded",
                        "updatedBy", user()));
                hr.auditInTx(AuditInput.of("HrEmployeeContract", (String) current.get("id"), "update",
                        Obj.of("status", "active"), Obj.of("status", "superseded", "supersededBy", id)));
            }
            Map<String, Object> out = hr.update(HrEmployeeContract.class, id, Obj.of("status", "active",
                    "updatedBy", user()));
            syncEmployee(employeeId, end);
            hr.auditInTx(AuditInput.of("HrEmployeeContract", id, "update", Obj.of("status", "draft"),
                    Obj.of("status", "active")));
            return out;
        });
    }

    /** End an active contract early; the employee's status is a separate decision (lifecycle routes). */
    public Map<String, Object> terminate(String id, Map<String, Object> dto) {
        if (!truthy(dto.get("reason"))) {
            throw ApiException.badRequest("A reason is required");
        }
        return hr.inTx(() -> {
            Map<String, Object> row = require(id);
            lockEmployee((String) row.get("employeeId"));
            row = require(id);
            if (!"active".equals(row.get("status"))) {
                throw ApiException.badRequest("Only an active contract can be terminated");
            }
            LocalDate on = day(dto.get("terminatedOn"), "terminatedOn");
            LocalDate effective = on != null ? on : LocalDate.now(ZoneOffset.UTC);
            if (effective.isBefore((LocalDate) row.get("startDate"))) {
                throw ApiException.badRequest("A contract cannot end before it starts");
            }
            Map<String, Object> out = hr.update(HrEmployeeContract.class, id, Obj.of("status", "terminated",
                    "terminatedOn", effective, "terminationReason", dto.get("reason"), "updatedBy", user()));
            syncEmployee((String) row.get("employeeId"), effective);
            hr.auditInTx(AuditInput.of("HrEmployeeContract", id, "update", Obj.of("status", "active"),
                    Obj.of("status", "terminated", "reason", dto.get("reason"))));
            return out;
        });
    }

    /** Draft the next contract from an active or expired one: same terms, starting the day after it ends. */
    public Map<String, Object> renew(String id, Map<String, Object> dto) {
        Map<String, Object> row = require(id);
        if (!"active".equals(row.get("status")) && !"expired".equals(row.get("status"))) {
            throw ApiException.badRequest("Only an active or expired contract can be renewed");
        }
        LocalDate oldEnd = (LocalDate) row.get("endDate");
        LocalDate start = day(dto.get("startDate"), "startDate");
        if (start == null) {
            if (oldEnd == null) {
                throw ApiException.badRequest("startDate is required: this contract has no end date");
            }
            start = oldEnd.plusDays(1);
        }
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("employeeId", row.get("employeeId"));
        next.put("contractType", nz(dto.get("contractType"), row.get("contractType")));
        next.put("startDate", start.toString());
        next.put("endDate", dto.get("endDate"));
        next.put("positionId", row.get("positionId"));
        next.put("departmentId", row.get("departmentId"));
        next.put("hoursPerWeek", row.get("hoursPerWeek"));
        next.put("renewalOfId", id);
        next.put("notes", dto.get("notes"));
        return create(next);
    }

    /** Daily: active contracts past their end date expire. Returns how many. */
    public int expireDue() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return hr.inTx(() -> {
            List<Map<String, Object>> due = hr.where(HrEmployeeContract.class).eq("status", "active")
                    .and("\"endDate\" < ?", java.sql.Date.valueOf(today)).list(null);
            for (Map<String, Object> c : due) {
                hr.update(HrEmployeeContract.class, (String) c.get("id"), Obj.of("status", "expired"));
                hr.auditInTx(AuditInput.of("HrEmployeeContract", (String) c.get("id"), "update",
                        Obj.of("status", "active"), Obj.of("status", "expired")));
            }
            return due.size();
        });
    }

    private void syncEmployee(String employeeId, LocalDate end) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contractEndDate", end == null ? null : end.atStartOfDay().toInstant(ZoneOffset.UTC));
        data.put("updatedBy", user());
        hr.update(HrEmployee.class, employeeId, data);
    }

    private void lockEmployee(String employeeId) {
        hr.jdbc().sql("SELECT id FROM \"HrEmployee\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(employeeId, org()).query().listOfRows();
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> row = hr.byId(HrEmployeeContract.class, id);
        if (row == null) {
            throw ApiException.notFound("Contract not found");
        }
        return row;
    }

    private void assertRefs(Map<String, Object> dto) {
        if (truthy(dto.get("positionId")) && hr.byId(HrPosition.class, dto.get("positionId")) == null) {
            throw ApiException.notFound("Position not found");
        }
        if (truthy(dto.get("departmentId")) && hr.byId(HrDepartment.class, dto.get("departmentId")) == null) {
            throw ApiException.notFound("Department not found");
        }
        if (truthy(dto.get("documentId")) && hr.byId(HrEmployeeDocument.class, dto.get("documentId")) == null) {
            throw ApiException.notFound("Document not found");
        }
    }

    static void assertTerms(Object type, LocalDate start, LocalDate end) {
        if (!TYPES.contains(type)) {
            throw ApiException.badRequest("Invalid contractType: " + type);
        }
        if (start == null) {
            throw ApiException.badRequest("startDate is required");
        }
        if (end != null && end.isBefore(start)) {
            throw ApiException.badRequest("endDate cannot be before startDate");
        }
        if (end == null && DATED_TYPES.contains(type)) {
            throw ApiException.badRequest("A " + type + " contract needs an end date");
        }
    }

    private static Object blank(Object v) {
        return v instanceof String s && s.isBlank() ? null : v;
    }
}
