package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.user;

import com.inventoryerp.core.domain.Branch;
import com.inventoryerp.hr.domain.HrAttendance;
import com.inventoryerp.hr.domain.HrDepartment;
import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.hr.domain.HrEmployeeTraining;
import com.inventoryerp.hr.domain.HrLeaveBalance;
import com.inventoryerp.hr.domain.HrLeaveRequest;
import com.inventoryerp.hr.domain.HrLeaveType;
import com.inventoryerp.hr.domain.HrPayrollAllowance;
import com.inventoryerp.hr.domain.HrPayrollDeduction;
import com.inventoryerp.hr.domain.HrPayrollItem;
import com.inventoryerp.hr.domain.HrPayrollRun;
import com.inventoryerp.hr.domain.HrPayslip;
import com.inventoryerp.hr.domain.HrPosition;
import com.inventoryerp.hr.domain.HrShift;
import com.inventoryerp.hr.domain.HrShiftAssignment;
import com.inventoryerp.hr.domain.HrTrainingProgram;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Employee self-service and the manager's team view (port of {@code hr-self.service.ts}). No {@code hr:*}
 * permission: every route resolves the caller's own employee from the session, so the only reachable records are
 * their own or their direct reports'.
 */
@Service
public class HrSelfService {

    private final HrDb hr;
    private final HrLeaveService leave;
    private final HrAttendanceService attendance;

    public HrSelfService(HrDb hr, HrLeaveService leave, HrAttendanceService attendance) {
        this.hr = hr;
        this.leave = leave;
        this.attendance = attendance;
    }

    public Map<String, Object> me() {
        Map<String, Object> employee = resolveSelf(false);
        if (employee == null) {
            return Obj.of("linked", false, "employee", null, "message",
                    "Your login is not linked to an employee record. Ask HR to link it if you need self-service.");
        }
        Map<String, Object> full = hr.byId(HrEmployee.class, employee.get("id"));
        hr.one(full, "department", HrDepartment.class, full.get("departmentId"));
        hr.one(full, "position", HrPosition.class, full.get("positionId"));
        hr.onePick(full, "branch", Branch.class, full.get("branchId"), "id", "code", "name");
        hr.onePick(full, "supervisor", HrEmployee.class, full.get("supervisorId"), HrOrgService.SUPERVISOR);
        List<Map<String, Object>> balances = hr.many(HrLeaveBalance.class, "employeeId", full.get("id"), null, null);
        hr.oneAll(balances, "leaveType", HrLeaveType.class, "leaveTypeId");
        full.put("leaveBalances", balances);
        List<Map<String, Object>> assignments = hr.many(HrShiftAssignment.class, "employeeId", full.get("id"),
                "\"isActive\" = true", null);
        hr.oneAll(assignments, "shift", HrShift.class, "shiftId");
        full.put("shiftAssignments", assignments);
        return Obj.of("linked", true, "employee", full);
    }

    private static Map<String, String> with(Map<String, String> query, String employeeId) {
        Map<String, String> q = new LinkedHashMap<>(query);
        q.put("employeeId", employeeId);
        return q;
    }

    public Map<String, Object> myAttendance(Map<String, String> query) {
        return attendance.listAttendance(with(query, (String) resolveSelf(true).get("id")));
    }

    public Map<String, Object> myLeave(Map<String, String> query) {
        return leave.listRequests(with(query, (String) resolveSelf(true).get("id")));
    }

    public Map<String, Object> myLeaveBalances(Map<String, String> query) {
        return leave.listBalances(with(query, (String) resolveSelf(true).get("id")));
    }

    /** Leave for yourself: the employee comes from the session and the balance cannot be overridden. */
    public Map<String, Object> requestLeave(Map<String, Object> dto) {
        Map<String, Object> employee = resolveSelf(true);
        Map<String, Object> body = new LinkedHashMap<>(dto);
        body.put("employeeId", employee.get("id"));
        body.put("overrideBalance", false);
        return leave.createRequest(body);
    }

    public Map<String, Object> cancelMyLeave(String id) {
        Map<String, Object> employee = resolveSelf(true);
        Map<String, Object> row = hr.byId(HrLeaveRequest.class, id);
        if (row == null || !employee.get("id").equals(row.get("employeeId"))) {
            throw ApiException.forbidden("That leave request is not yours");
        }
        return leave.cancelRequest(id);
    }

    public Map<String, Object> myPayslips(Map<String, String> query) {
        Map<String, Object> employee = resolveSelf(true);
        List<Map<String, Object>> rows = hr.where(HrPayslip.class).and("EXISTS (SELECT 1 FROM \"HrPayrollItem\" i"
                + " WHERE i.id = \"HrPayslip\".\"itemId\" AND i.\"employeeId\" = ?)", employee.get("id"))
                .list("\"createdAt\" DESC", HrDb.take(query.get("take"), 24, 60), 0);
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = hr.db().first(HrPayrollItem.class, "id = ?", r.get("itemId"));
            if (item != null) {
                hr.onePick(item, "run", HrPayrollRun.class, item.get("runId"), "id", "runNumber", "status", "periodId",
                        "paymentDate");
                item.put("allowances", hr.many(HrPayrollAllowance.class, "itemId", item.get("id"), null, null));
                item.put("deductions", hr.many(HrPayrollDeduction.class, "itemId", item.get("id"), null, null));
            }
            r.put("item", item);
        }
        return HrDb.rows(rows, rows.size());
    }

    public Map<String, Object> myTrainings() {
        Map<String, Object> employee = resolveSelf(true);
        List<Map<String, Object>> rows = hr.where(HrEmployeeTraining.class).eq("employeeId", employee.get("id"))
                .list("\"enrolledAt\" DESC");
        hr.oneAll(rows, "program", HrTrainingProgram.class, "programId");
        return HrDb.rows(rows, rows.size());
    }

    public Map<String, Object> clock(Map<String, Object> dto) {
        Map<String, Object> employee = resolveSelf(true);
        Map<String, Object> body = new LinkedHashMap<>(dto);
        body.put("employeeId", employee.get("id"));
        body.put("method", dto.get("method") != null ? dto.get("method") : "APP");
        return attendance.clock(body);
    }

    // ─── my team ───

    public Map<String, Object> team() {
        Map<String, Object> employee = resolveSelf(true);
        List<Map<String, Object>> reports = findReports((String) employee.get("id"));
        reports.forEach(r -> HrOrgService.redactEmployee(r, false));
        return Obj.of("manager", Obj.of("id", employee.get("id"), "firstName", employee.get("firstName"),
                "lastName", employee.get("lastName")), "rows", reports, "total", reports.size());
    }

    /** "Who is in today" — server-local (UTC) day start, as in Nest. */
    public Map<String, Object> teamToday() {
        Map<String, Object> employee = resolveSelf(true);
        List<Map<String, Object>> reports = findReports((String) employee.get("id"));
        if (reports.isEmpty()) {
            return Obj.of("total", 0, "present", 0, "late", 0, "onLeave", 0, "absent", 0, "notClockedIn", 0,
                    "rows", List.of());
        }
        List<Object> ids = new ArrayList<>();
        reports.forEach(r -> ids.add(r.get("id")));
        Object start = SqlRows.ts(LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant());
        Map<Object, Map<String, Object>> byEmployee = new HashMap<>();
        hr.where(HrAttendance.class).in("employeeId", ids).and("date = ?", start).list(null)
                .forEach(a -> byEmployee.put(a.get("employeeId"), a));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : reports) {
            Map<String, Object> a = byEmployee.get(r.get("id"));
            Object status = a != null && a.get("status") != null ? a.get("status")
                    : "ON_LEAVE".equals(r.get("employmentStatus")) ? "ON_LEAVE" : "NOT_CLOCKED_IN";
            rows.add(Obj.of("employeeId", r.get("id"), "employeeCode", r.get("employeeCode"),
                    "firstName", r.get("firstName"), "lastName", r.get("lastName"),
                    "employmentStatus", r.get("employmentStatus"), "status", status,
                    "checkInAt", a == null ? null : a.get("checkInAt"), "checkOutAt", a == null ? null : a.get("checkOutAt"),
                    "lateMinutes", a == null || a.get("lateMinutes") == null ? 0 : a.get("lateMinutes")));
        }
        return Obj.of("total", rows.size(), "present", count(rows, "PRESENT"), "late", count(rows, "LATE"),
                "onLeave", count(rows, "ON_LEAVE"), "absent", count(rows, "ABSENT"),
                "notClockedIn", count(rows, "NOT_CLOCKED_IN"), "rows", rows);
    }

    private static long count(List<Map<String, Object>> rows, String status) {
        return rows.stream().filter(r -> status.equals(r.get("status"))).count();
    }

    public Map<String, Object> teamLeave(Map<String, String> query) {
        Map<String, Object> employee = resolveSelf(true);
        List<Map<String, Object>> reports = findReports((String) employee.get("id"));
        if (reports.isEmpty()) {
            return HrDb.rows(List.of(), 0);
        }
        List<Object> ids = new ArrayList<>();
        reports.forEach(r -> ids.add(r.get("id")));
        List<Map<String, Object>> rows = hr.where(HrLeaveRequest.class).in("employeeId", ids)
                .eqIf("status", query.get("status")).list("\"createdAt\" DESC", HrDb.take(query.get("take"), 50, 200), 0);
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", HrAttendanceService.EMPLOYEE_REF);
        hr.oneAll(rows, "leaveType", HrLeaveType.class, "leaveTypeId");
        return HrDb.rows(rows, rows.size());
    }

    public Map<String, Object> approveTeamLeave(String id, Map<String, Object> dto) {
        assertManagesRequest(id);
        return leave.approveRequest(id, dto);
    }

    public Map<String, Object> rejectTeamLeave(String id, Map<String, Object> dto) {
        assertManagesRequest(id);
        return leave.rejectRequest(id, dto);
    }

    // ─── internals ───

    private Map<String, Object> resolveSelf(boolean required) {
        String userId = user();
        if (userId == null) {
            throw ApiException.forbidden("Authentication required");
        }
        Map<String, Object> employee = hr.where(HrEmployee.class).eq("userId", userId).first();
        if (employee == null) {
            if (!required) {
                return null;
            }
            throw ApiException.badRequest("Your login is not linked to an employee record. Ask HR to link it.");
        }
        return employee;
    }

    /** Direct reports plus members of departments this employee manages (active statuses only). */
    private List<Map<String, Object>> findReports(String employeeId) {
        List<Object> departmentIds = new ArrayList<>();
        hr.where(HrDepartment.class).eq("managerId", employeeId).list(null).forEach(d -> departmentIds.add(d.get("id")));
        HrDb.Where w = hr.where(HrEmployee.class).and("id <> ?", employeeId)
                .and("\"employmentStatus\"::text IN ('ACTIVE', 'PROBATION', 'ON_LEAVE')");
        if (departmentIds.isEmpty()) {
            w.and("\"supervisorId\" = ?", employeeId);
        } else {
            List<Object> p = new ArrayList<>(List.of(employeeId));
            p.addAll(departmentIds);
            w.and("\"supervisorId\" = ? OR \"departmentId\" IN (" + com.inventoryerp.pos.application.PosSql.marks(
                    departmentIds.size()) + ")", p.toArray());
        }
        List<Map<String, Object>> rows = w.list("\"firstName\" ASC");
        hr.oneAll(rows, "department", HrDepartment.class, "departmentId", "id", "name");
        hr.oneAll(rows, "position", HrPosition.class, "positionId", "id", "name");
        return rows;
    }

    private void assertManagesRequest(String requestId) {
        Map<String, Object> employee = resolveSelf(true);
        Map<String, Object> request = hr.byId(HrLeaveRequest.class, requestId);
        if (request == null) {
            throw ApiException.forbidden("Leave request not found");
        }
        boolean manages = findReports((String) employee.get("id")).stream()
                .anyMatch(r -> r.get("id").equals(request.get("employeeId")));
        if (!manages) {
            throw ApiException.forbidden("That leave request is not from someone you manage");
        }
    }
}
