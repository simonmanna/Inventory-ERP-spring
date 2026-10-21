package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.def;
import static com.inventoryerp.hr.application.HrDb.nz;
import static com.inventoryerp.hr.application.HrDb.org;
import static com.inventoryerp.hr.application.HrDb.truthy;
import static com.inventoryerp.hr.application.HrDb.user;

import com.inventoryerp.hr.domain.HrDepartment;
import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.hr.domain.HrEmployeeLoan;
import com.inventoryerp.hr.domain.HrLeaveBalance;
import com.inventoryerp.hr.domain.HrLeaveType;
import com.inventoryerp.hr.domain.HrPosition;
import com.inventoryerp.hr.domain.HrSalaryAdvance;
import com.inventoryerp.hr.domain.HrShift;
import com.inventoryerp.hr.domain.HrShiftAssignment;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * HR core masters (port of {@code hr-org.service.ts}): departments, positions, employees, shifts and shift
 * assignments. Compensation-grade employee fields are redacted / write-guarded without {@code hr:compensation}.
 */
@Service
public class HrOrgService {

    static final List<String> EMPLOYMENT_TYPES = List.of("FULL_TIME", "PART_TIME", "CONTRACT", "INTERN", "CASUAL",
            "PROBATION");
    static final List<String> PAY_FREQUENCIES = List.of("MONTHLY", "BIWEEKLY", "WEEKLY", "DAILY", "HOURLY");
    static final List<String> SHIFT_TYPES = List.of("MORNING", "AFTERNOON", "NIGHT", "SPLIT", "ROTATING", "FLEXIBLE");

    /** Fields redacted from an employee unless the caller holds {@code hr:compensation} (hr-employee-projection.ts). */
    public static final List<String> HR_COMPENSATION_FIELDS = List.of("baseSalary", "hourlyRate", "bankName",
            "bankAccountName", "bankAccountNumber", "mobileMoneyProvider", "mobileMoneyNumber", "taxNumber",
            "pensionNumber", "socialSecurityNumber", "dateOfBirth");

    private final HrDb hr;
    private final SequenceService seq;

    public HrOrgService(HrDb hr, SequenceService seq) {
        this.hr = hr;
        this.seq = seq;
    }

    /** Drop compensation keys (absent = "you may not see this", unlike null). */
    public static Map<String, Object> redactEmployee(Map<String, Object> row, boolean canSee) {
        if (row == null || canSee) {
            return row;
        }
        HR_COMPENSATION_FIELDS.forEach(row::remove);
        return row;
    }

    public static List<Map<String, Object>> redactEmployees(List<Map<String, Object>> rows, boolean canSee) {
        if (!canSee) {
            rows.forEach(r -> redactEmployee(r, false));
        }
        return rows;
    }

    static boolean canSeeCompensation() {
        return TenantContext.has(Permissions.HR__COMPENSATION);
    }

    private static void assertMayWriteCompensation(Map<String, Object> dto) {
        if (canSeeCompensation()) {
            return;
        }
        List<String> attempted = new ArrayList<>();
        for (String f : HR_COMPENSATION_FIELDS) {
            if (dto.containsKey(f)) {
                attempted.add(f);
            }
        }
        if (!attempted.isEmpty()) {
            throw ApiException.forbidden("Setting " + String.join(", ", attempted) + " requires the "
                    + Permissions.HR__COMPENSATION + " permission.");
        }
    }

    static String upper(Object v) {
        return PosSql.jsString(v).toUpperCase();
    }

    // ─── departments ───

    public Map<String, Object> listDepartments(Map<String, String> query) {
        HrDb.Where w = hr.where(HrDepartment.class).search(query.get("search"), "code", "name");
        List<Map<String, Object>> rows = w.list("name ASC", HrDb.take(query.get("take"), 100, 200),
                HrDb.skip(query.get("skip")));
        hr.counts(rows, "employees", "HrEmployee", "departmentId", "positions", "HrPosition", "departmentId");
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> getDepartment(String id) {
        Map<String, Object> row = hr.byId(HrDepartment.class, id);
        if (row == null) {
            throw ApiException.notFound("Department not found");
        }
        hr.one(row, "manager", HrEmployee.class, row.get("managerId"));
        row.put("positions", hr.many(HrPosition.class, "departmentId", id, null, null));
        return row;
    }

    public Map<String, Object> createDepartment(Map<String, Object> dto) {
        String orgId = org();
        if (!truthy(dto.get("code")) || !truthy(dto.get("name"))) {
            throw ApiException.badRequest("code and name are required");
        }
        if (hr.where(HrDepartment.class).eq("code", dto.get("code")).first() != null) {
            throw ApiException.badRequest("Department code \"" + PosSql.jsString(dto.get("code")) + "\" already exists");
        }
        return hr.audited(() -> hr.insert(HrDepartment.class, Obj.of("organizationId", orgId,
                "code", upper(dto.get("code")), "name", dto.get("name"), "description", dto.get("description"),
                "managerId", dto.get("managerId"), "isActive", nz(dto.get("isActive"), true), "createdBy", user())),
                row -> AuditInput.of("HrDepartment", (String) row.get("id"), "create", null, Obj.of("code",
                        row.get("code"), "name", row.get("name"), "managerId", row.get("managerId"))));
    }

    public Map<String, Object> updateDepartment(String id, Map<String, Object> dto) {
        Map<String, Object> row = hr.byId(HrDepartment.class, id);
        if (row == null) {
            throw ApiException.notFound("Department not found");
        }
        Map<String, Object> data = Obj.of("name", nz(dto.get("name"), row.get("name")),
                "description", def(dto, "description", row.get("description")),
                "managerId", def(dto, "managerId", row.get("managerId")),
                "isActive", nz(dto.get("isActive"), row.get("isActive")), "updatedBy", user());
        return hr.audited(() -> hr.update(HrDepartment.class, id, data),
                updated -> AuditInput.of("HrDepartment", id, "update",
                        Obj.of("name", row.get("name"), "managerId", row.get("managerId"), "isActive", row.get("isActive")),
                        Obj.of("name", updated.get("name"), "managerId", updated.get("managerId"),
                                "isActive", updated.get("isActive"))));
    }

    public Map<String, Object> deleteDepartment(String id) {
        Map<String, Object> row = hr.byId(HrDepartment.class, id);
        if (row == null) {
            throw ApiException.notFound("Department not found");
        }
        if (hr.where(HrEmployee.class).eq("departmentId", id).count() > 0) {
            throw ApiException.badRequest("Department still has employees — reassign them first");
        }
        return hr.audited(() -> hr.softDelete(HrDepartment.class, id, Obj.of("updatedBy", user())),
                r -> AuditInput.of("HrDepartment", id, "delete", Obj.of("code", row.get("code"), "name", row.get("name")),
                        null));
    }

    // ─── positions ───

    public Map<String, Object> listPositions(Map<String, String> query) {
        HrDb.Where w = hr.where(HrPosition.class).eqIf("departmentId", query.get("departmentId"))
                .search(query.get("search"), "code", "name");
        List<Map<String, Object>> rows = w.list("name ASC", HrDb.take(query.get("take"), 100, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "department", HrDepartment.class, "departmentId");
        hr.counts(rows, "employees", "HrEmployee", "positionId");
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> createPosition(Map<String, Object> dto) {
        String orgId = org();
        if (!truthy(dto.get("code")) || !truthy(dto.get("name"))) {
            throw ApiException.badRequest("code and name are required");
        }
        if (hr.where(HrPosition.class).eq("code", dto.get("code")).first() != null) {
            throw ApiException.badRequest("Position code \"" + PosSql.jsString(dto.get("code")) + "\" already exists");
        }
        return hr.audited(() -> hr.insert(HrPosition.class, Obj.of("organizationId", orgId,
                "code", upper(dto.get("code")), "name", dto.get("name"), "departmentId", dto.get("departmentId"),
                "defaultSalary", dto.get("defaultSalary"), "isActive", nz(dto.get("isActive"), true),
                "createdBy", user())),
                row -> AuditInput.of("HrPosition", (String) row.get("id"), "create", null, Obj.of("code", row.get("code"),
                        "name", row.get("name"), "departmentId", row.get("departmentId"))));
    }

    public Map<String, Object> updatePosition(String id, Map<String, Object> dto) {
        Map<String, Object> row = hr.byId(HrPosition.class, id);
        if (row == null) {
            throw ApiException.notFound("Position not found");
        }
        Map<String, Object> data = Obj.of("name", nz(dto.get("name"), row.get("name")),
                "departmentId", def(dto, "departmentId", row.get("departmentId")),
                "defaultSalary", def(dto, "defaultSalary", row.get("defaultSalary")),
                "isActive", nz(dto.get("isActive"), row.get("isActive")), "updatedBy", user());
        return hr.audited(() -> hr.update(HrPosition.class, id, data),
                updated -> AuditInput.of("HrPosition", id, "update",
                        Obj.of("name", row.get("name"), "defaultSalary", row.get("defaultSalary"),
                                "isActive", row.get("isActive")),
                        Obj.of("name", updated.get("name"), "defaultSalary", updated.get("defaultSalary"),
                                "isActive", updated.get("isActive"))));
    }

    public Map<String, Object> deletePosition(String id) {
        Map<String, Object> row = hr.byId(HrPosition.class, id);
        if (row == null) {
            throw ApiException.notFound("Position not found");
        }
        if (hr.where(HrEmployee.class).eq("positionId", id).count() > 0) {
            throw ApiException.badRequest("Position still has employees — reassign them first");
        }
        return hr.audited(() -> hr.softDelete(HrPosition.class, id, Obj.of("updatedBy", user())),
                r -> AuditInput.of("HrPosition", id, "delete", Obj.of("code", row.get("code"), "name", row.get("name")),
                        null));
    }

    // ─── employees ───

    static final String[] SUPERVISOR = {"id", "firstName", "lastName", "employeeCode"};

    public Map<String, Object> listEmployees(Map<String, String> query) {
        HrDb.Where w = hr.where(HrEmployee.class).eqIf("departmentId", query.get("departmentId"))
                .eqIf("positionId", query.get("positionId")).eqIf("employmentType", query.get("employmentType"));
        if (query.get("isActive") != null) {
            w.eq("isActive", "true".equals(query.get("isActive")));
        }
        if ("true".equals(query.get("linked"))) {
            w.and("\"userId\" IS NOT NULL");
        }
        if ("false".equals(query.get("linked"))) {
            w.and("\"userId\" IS NULL");
        }
        w.search(query.get("search"), "employeeCode", "firstName", "lastName", "email", "phone");
        List<Map<String, Object>> rows = w.list("\"employeeCode\" ASC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "department", HrDepartment.class, "departmentId");
        hr.oneAll(rows, "position", HrPosition.class, "positionId");
        hr.oneAll(rows, "supervisor", HrEmployee.class, "supervisorId", SUPERVISOR);
        return HrDb.rows(redactEmployees(rows, canSeeCompensation()), w.count());
    }

    public Map<String, Object> getEmployee(String id) {
        Map<String, Object> row = hr.byId(HrEmployee.class, id);
        if (row == null) {
            throw ApiException.notFound("Employee not found");
        }
        hr.one(row, "department", HrDepartment.class, row.get("departmentId"));
        hr.one(row, "position", HrPosition.class, row.get("positionId"));
        hr.onePick(row, "supervisor", HrEmployee.class, row.get("supervisorId"), SUPERVISOR);
        List<Map<String, Object>> balances = hr.many(HrLeaveBalance.class, "employeeId", id, null, null);
        hr.oneAll(balances, "leaveType", HrLeaveType.class, "leaveTypeId");
        row.put("leaveBalances", balances);
        row.put("loans", hr.many(HrEmployeeLoan.class, "employeeId", id, "\"deletedAt\" IS NULL", null));
        row.put("salaryAdvances", hr.many(HrSalaryAdvance.class, "employeeId", id, "\"deletedAt\" IS NULL", null));
        List<Map<String, Object>> assignments = hr.many(HrShiftAssignment.class, "employeeId", id, null, null);
        hr.oneAll(assignments, "shift", HrShift.class, "shiftId");
        row.put("shiftAssignments", assignments);
        return redactEmployee(row, canSeeCompensation());
    }

    private static void assertEnums(Map<String, Object> dto) {
        if (truthy(dto.get("employmentType")) && !EMPLOYMENT_TYPES.contains(dto.get("employmentType"))) {
            throw ApiException.badRequest("Invalid employmentType: " + PosSql.jsString(dto.get("employmentType")));
        }
        if (truthy(dto.get("payFrequency")) && !PAY_FREQUENCIES.contains(dto.get("payFrequency"))) {
            throw ApiException.badRequest("Invalid payFrequency: " + PosSql.jsString(dto.get("payFrequency")));
        }
    }

    private static Object date(Map<String, Object> dto, String k) {
        return truthy(dto.get(k)) ? HrDb.jsDate(dto.get(k)) : null;
    }

    public Map<String, Object> createEmployee(Map<String, Object> dto) {
        String orgId = org();
        if (!truthy(dto.get("firstName"))) {
            throw ApiException.badRequest("firstName is required");
        }
        Object employeeCode = dto.get("employeeCode") != null ? dto.get("employeeCode")
                : seq.next("hr_employee", "EMP-", 5);
        if (hr.where(HrEmployee.class).eq("employeeCode", employeeCode).first() != null) {
            throw ApiException.badRequest("Employee code \"" + PosSql.jsString(employeeCode) + "\" already exists");
        }
        assertEnums(dto);
        assertMayWriteCompensation(dto);
        return hr.inTx(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("organizationId", orgId);
            data.put("employeeCode", employeeCode);
            data.put("firstName", dto.get("firstName"));
            for (String k : List.of("lastName", "email", "phone", "photoUrl", "gender")) {
                data.put(k, dto.get(k));
            }
            data.put("dateOfBirth", date(dto, "dateOfBirth"));
            data.put("address", dto.get("address"));
            data.put("employmentType", nz(dto.get("employmentType"), "FULL_TIME"));
            data.put("hireDate", date(dto, "hireDate"));
            data.put("contractEndDate", date(dto, "contractEndDate"));
            data.put("probationEndDate", date(dto, "probationEndDate"));
            for (String k : List.of("departmentId", "positionId", "supervisorId", "baseSalary")) {
                data.put(k, dto.get(k));
            }
            data.put("payFrequency", nz(dto.get("payFrequency"), "MONTHLY"));
            for (String k : List.of("hourlyRate", "bankName", "bankAccountName", "bankAccountNumber",
                    "mobileMoneyProvider", "mobileMoneyNumber", "taxNumber", "pensionNumber", "socialSecurityNumber")) {
                data.put(k, dto.get(k));
            }
            data.put("isActive", nz(dto.get("isActive"), true));
            data.put("notes", dto.get("notes"));
            data.put("createdBy", user());
            Map<String, Object> created = hr.insert(HrEmployee.class, data);
            hr.auditInTx(AuditInput.of("HrEmployee", (String) created.get("id"), "create", null, Obj.of(
                    "employeeCode", created.get("employeeCode"), "firstName", created.get("firstName"),
                    "lastName", created.get("lastName"), "departmentId", created.get("departmentId"),
                    "positionId", created.get("positionId"))));
            return redactEmployee(created, canSeeCompensation());
        });
    }

    static final List<String> EMPLOYEE_FIELDS = List.of("firstName", "lastName", "email", "phone", "photoUrl",
            "gender", "address", "employmentType", "departmentId", "positionId", "supervisorId", "baseSalary",
            "payFrequency", "hourlyRate", "bankName", "bankAccountName", "bankAccountNumber", "mobileMoneyProvider",
            "mobileMoneyNumber", "taxNumber", "pensionNumber", "socialSecurityNumber", "isActive", "notes");

    public Map<String, Object> updateEmployee(String id, Map<String, Object> dto) {
        Map<String, Object> row = hr.byId(HrEmployee.class, id);
        if (row == null) {
            throw ApiException.notFound("Employee not found");
        }
        assertEnums(dto);
        assertMayWriteCompensation(dto);
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : EMPLOYEE_FIELDS) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f));
            }
        }
        for (String f : List.of("dateOfBirth", "hireDate", "contractEndDate", "probationEndDate")) {
            if (dto.containsKey(f)) {
                data.put(f, date(dto, f));
            }
        }
        data.put("updatedBy", user());
        Map<String, Object> oldValues = new LinkedHashMap<>();
        for (String f : data.keySet()) {
            if (!"updatedBy".equals(f)) {
                oldValues.put(f, row.get(f));
            }
        }
        return hr.inTx(() -> {
            Map<String, Object> updated = hr.update(HrEmployee.class, id, data);
            hr.auditInTx(AuditInput.of("HrEmployee", id, "update", oldValues, data));
            Object userId = row.get("userId");
            if (userId != null && (data.containsKey("firstName") || data.containsKey("lastName"))) {
                Map<String, Object> names = new LinkedHashMap<>();
                if (data.containsKey("firstName") && truthy(data.get("firstName"))) {
                    names.put("firstName", data.get("firstName"));
                }
                if (data.containsKey("lastName")) {
                    names.put("lastName", truthy(data.get("lastName")) ? data.get("lastName") : null);
                }
                if (!names.isEmpty()) {
                    Map<String, Object> userData = new LinkedHashMap<>(names);
                    userData.put("updatedBy", user());
                    hr.db().updateMany(com.inventoryerp.kernel.domain.User.class, userData,
                            "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", userId, org());
                    Map<String, Object> audited = new LinkedHashMap<>(names);
                    audited.put("via", "hr-employee");
                    hr.auditInTx(AuditInput.of("User", (String) userId, "update", null, audited));
                }
            }
            return redactEmployee(updated, canSeeCompensation());
        });
    }

    public Map<String, Object> deleteEmployee(String id) {
        Map<String, Object> row = hr.byId(HrEmployee.class, id);
        if (row == null) {
            throw ApiException.notFound("Employee not found");
        }
        return hr.inTx(() -> {
            Map<String, Object> deleted = hr.softDelete(HrEmployee.class, id, Obj.of("isActive", false,
                    "updatedBy", user()));
            hr.auditInTx(AuditInput.of("HrEmployee", id, "delete", Obj.of("employeeCode", row.get("employeeCode"),
                    "isActive", row.get("isActive")), null));
            return deleted;
        });
    }

    // ─── shifts ───

    public Map<String, Object> listShifts(Map<String, String> query) {
        HrDb.Where w = hr.where(HrShift.class).search(query.get("search"), "code", "name");
        List<Map<String, Object>> rows = w.list("name ASC", HrDb.take(query.get("take"), 100, 200),
                HrDb.skip(query.get("skip")));
        hr.counts(rows, "assignments", "HrShiftAssignment", "shiftId");
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> createShift(Map<String, Object> dto) {
        if (!truthy(dto.get("code")) || !truthy(dto.get("name")) || !truthy(dto.get("startTime"))
                || !truthy(dto.get("endTime"))) {
            throw ApiException.badRequest("code, name, startTime and endTime are required");
        }
        if (truthy(dto.get("shiftType")) && !SHIFT_TYPES.contains(dto.get("shiftType"))) {
            throw ApiException.badRequest("Invalid shiftType: " + PosSql.jsString(dto.get("shiftType")));
        }
        if (hr.where(HrShift.class).eq("code", dto.get("code")).first() != null) {
            throw ApiException.badRequest("Shift code \"" + PosSql.jsString(dto.get("code")) + "\" already exists");
        }
        return hr.insert(HrShift.class, Obj.of("organizationId", org(), "code", upper(dto.get("code")),
                "name", dto.get("name"), "shiftType", nz(dto.get("shiftType"), "MORNING"),
                "startTime", dto.get("startTime"), "endTime", dto.get("endTime"), "breakStart", dto.get("breakStart"),
                "breakEnd", dto.get("breakEnd"), "graceMinutes", nz(dto.get("graceMinutes"), 10),
                "maxOvertimeMinutes", nz(dto.get("maxOvertimeMinutes"), 180), "isActive", nz(dto.get("isActive"), true),
                "description", dto.get("description"), "createdBy", user()));
    }

    public Map<String, Object> updateShift(String id, Map<String, Object> dto) {
        if (hr.byId(HrShift.class, id) == null) {
            throw ApiException.notFound("Shift not found");
        }
        if (truthy(dto.get("shiftType")) && !SHIFT_TYPES.contains(dto.get("shiftType"))) {
            throw ApiException.badRequest("Invalid shiftType: " + PosSql.jsString(dto.get("shiftType")));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : List.of("name", "shiftType", "startTime", "endTime", "breakStart", "breakEnd", "graceMinutes",
                "maxOvertimeMinutes", "isActive", "description")) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f));
            }
        }
        data.put("updatedBy", user());
        return hr.update(HrShift.class, id, data);
    }

    public Map<String, Object> deleteShift(String id) {
        if (hr.byId(HrShift.class, id) == null) {
            throw ApiException.notFound("Shift not found");
        }
        return hr.softDelete(HrShift.class, id, Obj.of("isActive", false, "updatedBy", user()));
    }

    // ─── shift assignments ───

    public Map<String, Object> listAssignments(Map<String, String> query) {
        HrDb.Where w = hr.where(HrShiftAssignment.class).eqIf("employeeId", query.get("employeeId"))
                .eqIf("shiftId", query.get("shiftId"));
        List<Map<String, Object>> rows = w.list("\"effectiveFrom\" DESC", HrDb.take(query.get("take"), 100, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", SUPERVISOR);
        hr.oneAll(rows, "shift", HrShift.class, "shiftId");
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> assignShift(Map<String, Object> dto) {
        if (!truthy(dto.get("employeeId")) || !truthy(dto.get("shiftId"))) {
            throw ApiException.badRequest("employeeId and shiftId are required");
        }
        hr.where(HrShiftAssignment.class).eq("employeeId", dto.get("employeeId")).eq("isActive", true)
                .update(Obj.of("isActive", false));
        return hr.insert(HrShiftAssignment.class, Obj.of("organizationId", org(), "employeeId", dto.get("employeeId"),
                "shiftId", dto.get("shiftId"),
                "effectiveFrom", truthy(dto.get("effectiveFrom")) ? HrDb.jsDate(dto.get("effectiveFrom"))
                        : PosDb.raw(PosSql.NOW),
                "effectiveTo", date(dto, "effectiveTo"), "isActive", true, "createdBy", user()));
    }

    public Map<String, Object> revokeAssignment(String id) {
        if (hr.byId(HrShiftAssignment.class, id) == null) {
            throw ApiException.notFound("Assignment not found");
        }
        return hr.update(HrShiftAssignment.class, id, Obj.of("isActive", false, "updatedAt", PosDb.raw(PosSql.NOW),
                "deletedAt", PosDb.raw(PosSql.NOW), "createdBy", user()));
    }
}
