package com.inventoryerp.hr.web;

import com.inventoryerp.hr.application.HrAccessService;
import com.inventoryerp.hr.application.HrAccessService.Provision;
import com.inventoryerp.hr.application.HrAnalyticsService;
import com.inventoryerp.hr.application.HrAttendanceService;
import com.inventoryerp.hr.application.HrDb;
import com.inventoryerp.hr.application.HrDocumentsService;
import com.inventoryerp.hr.application.HrEssService;
import com.inventoryerp.hr.application.HrLeaveService;
import com.inventoryerp.hr.application.HrLifecycleService;
import com.inventoryerp.hr.application.HrLifecycleService.Change;
import com.inventoryerp.hr.application.HrLifecycleService.Transfer;
import com.inventoryerp.hr.application.HrOrgService;
import com.inventoryerp.hr.application.HrPayrollService;
import com.inventoryerp.hr.application.HrReportsService;
import com.inventoryerp.hr.application.HrSelfService;
import com.inventoryerp.hr.application.HrTalentService;
import com.inventoryerp.hr.application.HrTimesheetService;
import com.inventoryerp.hr.web.HrDtos.ConfirmEmployeeRequest;
import com.inventoryerp.hr.web.HrDtos.LinkUserRequest;
import com.inventoryerp.hr.web.HrDtos.ProvisionUserRequest;
import com.inventoryerp.hr.web.HrDtos.ReactivateEmployeeRequest;
import com.inventoryerp.hr.web.HrDtos.SuspendEmployeeRequest;
import com.inventoryerp.hr.web.HrDtos.TerminateEmployeeRequest;
import com.inventoryerp.hr.web.HrDtos.TransferEmployeeRequest;
import com.inventoryerp.hr.web.HrDtos.UpdateAccessRequest;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.staff.UsersController.SetPinRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /hr} (port of {@code hr.controller.ts}): employees, departments, positions, shifts, attendance, timesheets,
 * leave, holidays, payroll, payslips, advances, loans, bank payments, reviews and reports. Registered only with
 * {@code ENABLE_HR=true} and gated per tenant by {@code @RequiresModule('hr')}. Most bodies are untyped in Nest
 * ({@code dto: any}, never validated) and arrive here as maps; the identity / lifecycle routes are typed.
 * Self-service ({@code /hr/me*}, {@code /hr/team*}) carries no permission: the caller's own employee is resolved from
 * the session.
 */
@RestController
@RequestMapping("/hr")
@RequiresModule("hr")
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrController {

    private final HrOrgService org;
    private final HrAttendanceService attendance;
    private final HrTimesheetService timesheets;
    private final HrLeaveService leave;
    private final HrPayrollService payroll;
    private final HrReportsService reports;
    private final HrAccessService access;
    private final HrLifecycleService lifecycle;
    private final HrSelfService self;
    private final HrDocumentsService documents;
    private final HrAnalyticsService analytics;

    private final HrEssService ess;
    private final HrTalentService talent;

    public HrController(HrOrgService org, HrAttendanceService attendance, HrTimesheetService timesheets,
            HrLeaveService leave, HrPayrollService payroll, HrReportsService reports, HrAccessService access,
            HrLifecycleService lifecycle, HrSelfService self, HrDocumentsService documents,
            HrAnalyticsService analytics, HrEssService ess, HrTalentService talent) {
        this.org = org;
        this.attendance = attendance;
        this.timesheets = timesheets;
        this.leave = leave;
        this.payroll = payroll;
        this.reports = reports;
        this.access = access;
        this.lifecycle = lifecycle;
        this.self = self;
        this.documents = documents;
        this.analytics = analytics;
        this.ess = ess;
        this.talent = talent;
    }

    // ─── self-service ───

    @GetMapping("/me")
    public Map<String, Object> me() {
        return self.me();
    }

    @GetMapping("/me/attendance")
    public Map<String, Object> myAttendance(@RequestParam Map<String, String> query) {
        return self.myAttendance(query);
    }

    @PostMapping("/me/clock")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> clockSelf(@RequestBody Map<String, Object> dto) {
        return self.clock(dto);
    }

    @GetMapping("/me/leave")
    public Map<String, Object> myLeave(@RequestParam Map<String, String> query) {
        return self.myLeave(query);
    }

    @GetMapping("/me/leave/balances")
    public Map<String, Object> myLeaveBalances(@RequestParam Map<String, String> query) {
        return self.myLeaveBalances(query);
    }

    @PostMapping("/me/leave")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> requestOwnLeave(@RequestBody Map<String, Object> dto) {
        return self.requestLeave(dto);
    }

    @PostMapping("/me/leave/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> cancelOwnLeave(@PathVariable String id) {
        return self.cancelMyLeave(id);
    }

    @GetMapping("/me/payslips")
    public Map<String, Object> myPayslips(@RequestParam Map<String, String> query) {
        return self.myPayslips(query);
    }

    @GetMapping("/me/trainings")
    public Map<String, Object> myTrainings() {
        return self.myTrainings();
    }

    @GetMapping("/team")
    public Map<String, Object> team() {
        return self.team();
    }

    @GetMapping("/team/today")
    public Map<String, Object> teamToday() {
        return self.teamToday();
    }

    @GetMapping("/team/leave")
    public Map<String, Object> teamLeave(@RequestParam Map<String, String> query) {
        return self.teamLeave(query);
    }

    @PostMapping("/team/leave/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> approveTeamLeave(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return self.approveTeamLeave(id, dto);
    }

    @PostMapping("/team/leave/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> rejectTeamLeave(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return self.rejectTeamLeave(id, dto);
    }

    // ─── dashboard / reports ───

    @GetMapping("/dashboard")
    @RequirePermissions(Permissions.HR__READ)
    public Map<String, Object> dashboard() {
        return reports.dashboard();
    }

    @GetMapping("/reports/workforce-sales")
    @RequirePermissions(Permissions.HR__REPORT)
    public Map<String, Object> workforceSales(@RequestParam Map<String, String> query) {
        return analytics.workforceSales(query);
    }

    @GetMapping("/reports/workforce-access")
    @RequirePermissions(Permissions.HR__READ)
    public Map<String, Object> workforceAccess() {
        return analytics.workforceAccess();
    }

    @GetMapping("/reports/headcount-trend")
    @RequirePermissions(Permissions.HR__REPORT)
    public List<Map<String, Object>> headcountTrend(@RequestParam(required = false) String months) {
        return reports.headcountTrend(months != null && !months.isEmpty() ? HrDb.jsNumber(months) : 6);
    }

    @GetMapping("/reports/payroll-register/{runId}")
    @RequirePermissions(Permissions.HR__REPORT)
    public Map<String, Object> payrollRegister(@PathVariable String runId) {
        return reports.payrollRegister(runId);
    }

    @GetMapping("/reports/attendance-register")
    @RequirePermissions(Permissions.HR__REPORT)
    public Map<String, Object> attendanceRegister(@RequestParam Map<String, String> query) {
        return reports.attendanceRegister(query);
    }

    @GetMapping("/reports/leave-overview")
    @RequirePermissions(Permissions.HR__REPORT)
    public Map<String, Object> leaveOverview(@RequestParam Map<String, String> query) {
        return reports.leaveOverview(query);
    }

    // ─── departments ───

    @GetMapping("/departments")
    @RequirePermissions(Permissions.HR__READ)
    public Map<String, Object> listDepartments(@RequestParam Map<String, String> query) {
        return org.listDepartments(query);
    }

    @GetMapping("/departments/{id}")
    @RequirePermissions(Permissions.HR__READ)
    public Map<String, Object> getDepartment(@PathVariable String id) {
        return org.getDepartment(id);
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__EMPLOYEE)
    public Map<String, Object> createDepartment(@RequestBody Map<String, Object> dto) {
        return org.createDepartment(dto);
    }

    @PatchMapping("/departments/{id}")
    @RequirePermissions(Permissions.HR__EMPLOYEE)
    public Map<String, Object> updateDepartment(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return org.updateDepartment(id, dto);
    }

    @DeleteMapping("/departments/{id}")
    @RequirePermissions(Permissions.HR__EMPLOYEE)
    public Map<String, Object> deleteDepartment(@PathVariable String id) {
        return org.deleteDepartment(id);
    }

    // ─── positions ───

    @GetMapping("/positions")
    @RequirePermissions(Permissions.HR__READ)
    public Map<String, Object> listPositions(@RequestParam Map<String, String> query) {
        return org.listPositions(query);
    }

    @PostMapping("/positions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__EMPLOYEE)
    public Map<String, Object> createPosition(@RequestBody Map<String, Object> dto) {
        return org.createPosition(dto);
    }

    @PatchMapping("/positions/{id}")
    @RequirePermissions(Permissions.HR__EMPLOYEE)
    public Map<String, Object> updatePosition(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return org.updatePosition(id, dto);
    }

    @DeleteMapping("/positions/{id}")
    @RequirePermissions(Permissions.HR__EMPLOYEE)
    public Map<String, Object> deletePosition(@PathVariable String id) {
        return org.deletePosition(id);
    }

    // ─── employees ───

    @GetMapping("/employees")
    @RequirePermissions(Permissions.HR__READ)
    public Map<String, Object> listEmployees(@RequestParam Map<String, String> query) {
        return org.listEmployees(query);
    }

    @GetMapping("/employees/{id}")
    @RequirePermissions(Permissions.HR__READ)
    public Map<String, Object> getEmployee(@PathVariable String id) {
        return org.getEmployee(id);
    }

    @PostMapping("/employees")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__EMPLOYEE)
    public Map<String, Object> createEmployee(@RequestBody Map<String, Object> dto) {
        return org.createEmployee(dto);
    }

    @PatchMapping("/employees/{id}")
    @RequirePermissions(Permissions.HR__EMPLOYEE)
    public Map<String, Object> updateEmployee(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return org.updateEmployee(id, dto);
    }

    @DeleteMapping("/employees/{id}")
    @RequirePermissions(Permissions.HR__EMPLOYEE)
    public Map<String, Object> deleteEmployee(@PathVariable String id) {
        return org.deleteEmployee(id);
    }

    // ─── identity spine ───

    @GetMapping("/access/linkable-users")
    @RequirePermissions(Permissions.HR__ACCESS)
    public Map<String, Object> linkableUsers(@RequestParam Map<String, String> query) {
        return access.listLinkableUsers(query);
    }

    @GetMapping("/employees/{id}/access")
    @RequirePermissions(Permissions.HR__ACCESS)
    public Map<String, Object> getAccess(@PathVariable String id) {
        return access.getAccess(id);
    }

    @PostMapping("/employees/{id}/link-user")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__ACCESS)
    public Map<String, Object> linkUser(@PathVariable String id, @Valid @RequestBody LinkUserRequest dto) {
        return access.linkUser(id, dto.userId());
    }

    @DeleteMapping("/employees/{id}/link-user")
    @RequirePermissions(Permissions.HR__ACCESS)
    public Map<String, Object> unlinkUser(@PathVariable String id) {
        return access.unlinkUser(id);
    }

    @PostMapping("/employees/{id}/provision-user")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions({Permissions.HR__ACCESS, Permissions.USER__CREATE})
    public Map<String, Object> provisionUser(@PathVariable String id, @Valid @RequestBody ProvisionUserRequest dto) {
        return access.provisionUser(id, new Provision(dto.email(), dto.password(), dto.firstName(), dto.lastName(),
                dto.roleIds(), dto.isActive()));
    }

    @PatchMapping("/employees/{id}/access")
    @RequirePermissions({Permissions.HR__ACCESS, Permissions.ROLE__UPDATE})
    public Map<String, Object> updateAccess(@PathVariable String id, @Valid @RequestBody UpdateAccessRequest dto) {
        return access.updateAccess(id, dto.roleIds(), dto.isActive());
    }

    @PostMapping("/employees/{id}/access/pin")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions({Permissions.HR__ACCESS, Permissions.USER__UPDATE})
    public Map<String, Object> setPin(@PathVariable String id, @Valid @RequestBody SetPinRequest dto) {
        return access.setPin(id, dto.pin());
    }

    @DeleteMapping("/employees/{id}/access/pin")
    @RequirePermissions({Permissions.HR__ACCESS, Permissions.USER__UPDATE})
    public Map<String, Object> clearPin(@PathVariable String id) {
        return access.clearPin(id);
    }

    // ─── employment lifecycle ───

    @PostMapping("/employees/{id}/terminate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__ACCESS)
    public Map<String, Object> terminate(@PathVariable String id, @Valid @RequestBody TerminateEmployeeRequest dto) {
        return lifecycle.terminate(id, new Change(dto.reason(), dto.disableAccount(), null, dto.resigned(), null,
                dto.terminationDate()));
    }

    @PostMapping("/employees/{id}/suspend")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__ACCESS)
    public Map<String, Object> suspend(@PathVariable String id, @Valid @RequestBody SuspendEmployeeRequest dto) {
        return lifecycle.suspend(id, new Change(dto.reason(), dto.disableAccount(), null, null, null, null));
    }

    @PostMapping("/employees/{id}/reactivate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__ACCESS)
    public Map<String, Object> reactivate(@PathVariable String id, @Valid @RequestBody ReactivateEmployeeRequest dto) {
        return lifecycle.reactivate(id, new Change(dto.reason(), null, dto.enableAccount(), null, dto.toProbation(),
                null));
    }

    @PostMapping("/employees/{id}/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__ACCESS)
    public Map<String, Object> confirmEmployee(@PathVariable String id, @Valid @RequestBody ConfirmEmployeeRequest dto) {
        return lifecycle.confirm(id, dto.reason());
    }

    @PostMapping("/employees/{id}/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__ACCESS)
    public Map<String, Object> transfer(@PathVariable String id, @Valid @RequestBody TransferEmployeeRequest dto) {
        return lifecycle.transfer(id, new Transfer(dto.toBranchId(), dto.toDepartmentId(), dto.toPositionId(),
                dto.effectiveDate(), dto.reason()));
    }

    @GetMapping("/employees/{id}/status-history")
    @RequirePermissions(Permissions.HR__READ)
    public Map<String, Object> statusHistory(@PathVariable String id) {
        return lifecycle.statusHistory(id);
    }

    @GetMapping("/employees/{id}/transfers")
    @RequirePermissions(Permissions.HR__READ)
    public Map<String, Object> transferHistory(@PathVariable String id) {
        return lifecycle.transferHistory(id);
    }

    @GetMapping("/employees/{id}/pos-activity")
    @RequirePermissions(Permissions.HR__REPORT)
    public Map<String, Object> posActivity(@PathVariable String id, @RequestParam Map<String, String> query) {
        return analytics.posActivity(id, query);
    }

    // ─── employee documents ───

    @GetMapping("/documents/expiring")
    @RequirePermissions(Permissions.HR__DOCUMENT)
    public Map<String, Object> expiringDocuments(@RequestParam(required = false) String days) {
        return documents.expiring(days != null && !days.isEmpty() ? HrDb.jsNumber(days) : 60);
    }

    @GetMapping("/documents")
    @RequirePermissions(Permissions.HR__DOCUMENT)
    public Map<String, Object> listDocuments(@RequestParam Map<String, String> query) {
        return documents.list(query);
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__DOCUMENT)
    public Map<String, Object> createDocument(@RequestBody Map<String, Object> dto) {
        return documents.create(dto);
    }

    @PatchMapping("/documents/{id}")
    @RequirePermissions(Permissions.HR__DOCUMENT)
    public Map<String, Object> updateDocument(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return documents.update(id, dto);
    }

    @DeleteMapping("/documents/{id}")
    @RequirePermissions(Permissions.HR__DOCUMENT)
    public Map<String, Object> deleteDocument(@PathVariable String id) {
        return documents.remove(id);
    }

    // ─── training ───

    @GetMapping("/training/programs")
    @RequirePermissions(Permissions.HR__TRAINING)
    public Map<String, Object> listPrograms(@RequestParam Map<String, String> query) {
        return documents.listPrograms(query);
    }

    @PostMapping("/training/programs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TRAINING)
    public Map<String, Object> createProgram(@RequestBody Map<String, Object> dto) {
        return documents.createProgram(dto);
    }

    @PatchMapping("/training/programs/{id}")
    @RequirePermissions(Permissions.HR__TRAINING)
    public Map<String, Object> updateProgram(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return documents.updateProgram(id, dto);
    }

    @DeleteMapping("/training/programs/{id}")
    @RequirePermissions(Permissions.HR__TRAINING)
    public Map<String, Object> deleteProgram(@PathVariable String id) {
        return documents.removeProgram(id);
    }

    @GetMapping("/training/enrolments")
    @RequirePermissions(Permissions.HR__TRAINING)
    public Map<String, Object> listEnrolments(@RequestParam Map<String, String> query) {
        return documents.listEnrolments(query);
    }

    @PostMapping("/training/enrolments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TRAINING)
    public Map<String, Object> enrol(@RequestBody Map<String, Object> dto) {
        return documents.enrol(dto);
    }

    @PatchMapping("/training/enrolments/{id}")
    @RequirePermissions(Permissions.HR__TRAINING)
    public Map<String, Object> updateEnrolment(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return documents.updateEnrolment(id, dto);
    }

    @DeleteMapping("/training/enrolments/{id}")
    @RequirePermissions(Permissions.HR__TRAINING)
    public Map<String, Object> deleteEnrolment(@PathVariable String id) {
        return documents.removeEnrolment(id);
    }

    // ─── shifts + assignments ───

    @GetMapping("/shifts")
    @RequirePermissions(Permissions.HR__READ)
    public Map<String, Object> listShifts(@RequestParam Map<String, String> query) {
        return org.listShifts(query);
    }

    @PostMapping("/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__SHIFT)
    public Map<String, Object> createShift(@RequestBody Map<String, Object> dto) {
        return org.createShift(dto);
    }

    @PatchMapping("/shifts/{id}")
    @RequirePermissions(Permissions.HR__SHIFT)
    public Map<String, Object> updateShift(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return org.updateShift(id, dto);
    }

    @DeleteMapping("/shifts/{id}")
    @RequirePermissions(Permissions.HR__SHIFT)
    public Map<String, Object> deleteShift(@PathVariable String id) {
        return org.deleteShift(id);
    }

    @GetMapping("/assignments")
    @RequirePermissions(Permissions.HR__READ)
    public Map<String, Object> listAssignments(@RequestParam Map<String, String> query) {
        return org.listAssignments(query);
    }

    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__SHIFT)
    public Map<String, Object> assignShift(@RequestBody Map<String, Object> dto) {
        return org.assignShift(dto);
    }

    @DeleteMapping("/assignments/{id}")
    @RequirePermissions(Permissions.HR__SHIFT)
    public Map<String, Object> revokeAssignment(@PathVariable String id) {
        return org.revokeAssignment(id);
    }

    // ─── attendance ───

    @PostMapping("/attendance/clock")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__ATTENDANCE)
    public Map<String, Object> clock(@RequestBody Map<String, Object> dto) {
        return attendance.clock(dto);
    }

    @PostMapping("/attendance/manual")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__ATTENDANCE)
    public Map<String, Object> upsertManual(@RequestBody Map<String, Object> dto) {
        return attendance.upsertManual(dto);
    }

    @GetMapping("/attendance")
    @RequirePermissions(Permissions.HR__ATTENDANCE)
    public Map<String, Object> listAttendance(@RequestParam Map<String, String> query) {
        return attendance.listAttendance(query);
    }

    @GetMapping("/attendance/summary")
    @RequirePermissions(Permissions.HR__ATTENDANCE)
    public Map<String, Object> attendanceSummary(@RequestParam Map<String, String> query) {
        return attendance.summary(query);
    }

    @GetMapping("/attendance/{id}")
    @RequirePermissions(Permissions.HR__ATTENDANCE)
    public Map<String, Object> getAttendance(@PathVariable String id) {
        return attendance.getAttendance(id);
    }

    @GetMapping("/attendance-logs")
    @RequirePermissions(Permissions.HR__ATTENDANCE)
    public Map<String, Object> listLogs(@RequestParam Map<String, String> query) {
        return attendance.listLogs(query);
    }

    // ─── timesheets ───

    @GetMapping("/timesheets")
    @RequirePermissions(Permissions.HR__TIMESHEET)
    public Map<String, Object> listTimesheets(@RequestParam Map<String, String> query) {
        return timesheets.list(query);
    }

    @GetMapping("/timesheets/{id}")
    @RequirePermissions(Permissions.HR__TIMESHEET)
    public Map<String, Object> getTimesheet(@PathVariable String id) {
        return timesheets.get(id);
    }

    @PostMapping("/timesheets")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TIMESHEET)
    public Map<String, Object> createTimesheet(@RequestBody Map<String, Object> dto) {
        return timesheets.create(dto);
    }

    @PatchMapping("/timesheets/{id}")
    @RequirePermissions(Permissions.HR__TIMESHEET)
    public Map<String, Object> updateTimesheet(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return timesheets.update(id, dto);
    }

    @PostMapping("/timesheets/{id}/submit")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TIMESHEET)
    public Map<String, Object> submitTimesheet(@PathVariable String id) {
        return timesheets.submit(id);
    }

    @PostMapping("/timesheets/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TIMESHEET)
    public Map<String, Object> approveTimesheet(@PathVariable String id) {
        return timesheets.approve(id);
    }

    @PostMapping("/timesheets/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TIMESHEET)
    public Map<String, Object> rejectTimesheet(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return timesheets.reject(id, dto);
    }

    @DeleteMapping("/timesheets/{id}")
    @RequirePermissions(Permissions.HR__TIMESHEET)
    public Map<String, Object> deleteTimesheet(@PathVariable String id) {
        return timesheets.delete(id);
    }

    @PostMapping("/timesheets/{id}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TIMESHEET)
    public Map<String, Object> addEntry(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return timesheets.addEntry(id, dto);
    }

    @PatchMapping("/timesheets/entries/{entryId}")
    @RequirePermissions(Permissions.HR__TIMESHEET)
    public Map<String, Object> updateEntry(@PathVariable String entryId, @RequestBody Map<String, Object> dto) {
        return timesheets.updateEntry(entryId, dto);
    }

    @DeleteMapping("/timesheets/entries/{entryId}")
    @RequirePermissions(Permissions.HR__TIMESHEET)
    public void deleteEntry(@PathVariable String entryId) {
        timesheets.deleteEntry(entryId);
    }

    // ─── leave ───

    @GetMapping("/leave/types")
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> listLeaveTypes(@RequestParam Map<String, String> query) {
        return leave.listTypes(query);
    }

    @PostMapping("/leave/types")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> createLeaveType(@RequestBody Map<String, Object> dto) {
        return leave.createType(dto);
    }

    @PatchMapping("/leave/types/{id}")
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> updateLeaveType(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return leave.updateType(id, dto);
    }

    @DeleteMapping("/leave/types/{id}")
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> deleteLeaveType(@PathVariable String id) {
        return leave.deleteType(id);
    }

    @GetMapping("/leave/balances")
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> listLeaveBalances(@RequestParam Map<String, String> query) {
        return leave.listBalances(query);
    }

    @PostMapping("/leave/balances/adjust")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> adjustBalance(@RequestBody Map<String, Object> dto) {
        return leave.adjustBalance(dto);
    }

    @GetMapping("/leave/requests")
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> listLeaveRequests(@RequestParam Map<String, String> query) {
        return leave.listRequests(query);
    }

    @PostMapping("/leave/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> createLeaveRequest(@RequestBody Map<String, Object> dto) {
        return leave.createRequest(dto);
    }

    @PostMapping("/leave/requests/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> approveLeaveRequest(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return leave.approveRequest(id, dto);
    }

    @PostMapping("/leave/requests/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> rejectLeaveRequest(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return leave.rejectRequest(id, dto);
    }

    @PostMapping("/leave/requests/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> cancelLeaveRequest(@PathVariable String id) {
        return leave.cancelRequest(id);
    }

    @GetMapping("/holidays")
    @RequirePermissions(Permissions.HR__HOLIDAY)
    public Map<String, Object> listHolidays(@RequestParam Map<String, String> query) {
        return leave.listHolidays(query);
    }

    @PostMapping("/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__HOLIDAY)
    public Map<String, Object> createHoliday(@RequestBody Map<String, Object> dto) {
        return leave.createHoliday(dto);
    }

    @PatchMapping("/holidays/{id}")
    @RequirePermissions(Permissions.HR__HOLIDAY)
    public Map<String, Object> updateHoliday(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return leave.updateHoliday(id, dto);
    }

    @DeleteMapping("/holidays/{id}")
    @RequirePermissions(Permissions.HR__HOLIDAY)
    public Map<String, Object> deleteHoliday(@PathVariable String id) {
        return leave.deleteHoliday(id);
    }

    // ─── payroll components + tax tables ───

    @GetMapping("/payroll/components")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> listComponents(@RequestParam Map<String, String> query) {
        return payroll.listComponents(query);
    }

    @PostMapping("/payroll/components")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> createComponent(@RequestBody Map<String, Object> dto) {
        return payroll.createComponent(dto);
    }

    @PatchMapping("/payroll/components/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> updateComponent(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return payroll.updateComponent(id, dto);
    }

    @DeleteMapping("/payroll/components/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> deleteComponent(@PathVariable String id) {
        return payroll.deleteComponent(id);
    }

    @GetMapping("/payroll/tax-tables")
    @RequirePermissions(Permissions.HR__TAX_TABLE)
    public Map<String, Object> listTaxTables(@RequestParam Map<String, String> query) {
        return payroll.listTaxTables(query);
    }

    @PostMapping("/payroll/tax-tables")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TAX_TABLE)
    public Map<String, Object> createTaxTable(@RequestBody Map<String, Object> dto) {
        return payroll.createTaxTable(dto);
    }

    @PatchMapping("/payroll/tax-tables/{id}")
    @RequirePermissions(Permissions.HR__TAX_TABLE)
    public Map<String, Object> updateTaxTable(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return payroll.updateTaxTable(id, dto);
    }

    @DeleteMapping("/payroll/tax-tables/{id}")
    @RequirePermissions(Permissions.HR__TAX_TABLE)
    public Map<String, Object> deleteTaxTable(@PathVariable String id) {
        return payroll.deleteTaxTable(id);
    }

    // ─── payroll periods + runs ───

    @GetMapping("/payroll/periods")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> listPeriods(@RequestParam Map<String, String> query) {
        return payroll.listPeriods(query);
    }

    @PostMapping("/payroll/periods")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> createPeriod(@RequestBody Map<String, Object> dto) {
        return payroll.createPeriod(dto);
    }

    @PatchMapping("/payroll/periods/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> updatePeriod(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return payroll.updatePeriod(id, dto);
    }

    @GetMapping("/payroll/runs")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> listRuns(@RequestParam Map<String, String> query) {
        return payroll.listRuns(query);
    }

    @GetMapping("/payroll/runs/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> getRun(@PathVariable String id) {
        return payroll.getRun(id);
    }

    @PostMapping("/payroll/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> createRun(@RequestBody Map<String, Object> dto) {
        return payroll.createRun(dto);
    }

    @PostMapping("/payroll/runs/{id}/calculate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> calculateRun(@PathVariable String id) {
        return payroll.calculateRun(id);
    }

    @PostMapping("/payroll/runs/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> approveRun(@PathVariable String id) {
        return payroll.approveRun(id);
    }

    @PostMapping("/payroll/runs/{id}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> reverseRun(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return payroll.reverseRun(id, dto);
    }

    @DeleteMapping("/payroll/runs/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> deleteRun(@PathVariable String id) {
        return payroll.deleteRun(id);
    }

    // ─── payslips ───

    @GetMapping("/payslips")
    @RequirePermissions(Permissions.HR__PAYSLIP)
    public Map<String, Object> listPayslips(@RequestParam Map<String, String> query) {
        return payroll.listPayslips(query);
    }

    @GetMapping("/payslips/{id}")
    @RequirePermissions(Permissions.HR__PAYSLIP)
    public Map<String, Object> getPayslip(@PathVariable String id) {
        return payroll.getPayslip(id);
    }

    @PostMapping("/payslips/{id}/paid")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYSLIP)
    public Map<String, Object> markPayslipPaid(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return payroll.markPaid(id, dto);
    }

    // ─── bank payments ───

    @GetMapping("/bank-payments")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> listBankPayments(@RequestParam Map<String, String> query) {
        return payroll.listBankPayments(query);
    }

    @PostMapping("/bank-payments/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> generateBankPayment(@RequestBody Map<String, Object> dto) {
        return payroll.generateBankPayment(dto);
    }

    @PatchMapping("/bank-payments/{id}/status")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> updateBankPaymentStatus(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return payroll.updateBankPaymentStatus(id, dto);
    }

    // ─── advances + loans ───

    @GetMapping("/advances")
    @RequirePermissions(Permissions.HR__ADVANCE)
    public Map<String, Object> listAdvances(@RequestParam Map<String, String> query) {
        return payroll.listAdvances(query);
    }

    @PostMapping("/advances")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__ADVANCE)
    public Map<String, Object> createAdvance(@RequestBody Map<String, Object> dto) {
        return payroll.createAdvance(dto);
    }

    @PostMapping("/advances/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__ADVANCE)
    public Map<String, Object> approveAdvance(@PathVariable String id) {
        return payroll.approveAdvance(id);
    }

    @PostMapping("/advances/{id}/paid")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__ADVANCE)
    public Map<String, Object> markAdvancePaid(@PathVariable String id) {
        return payroll.markAdvancePaid(id);
    }

    @PostMapping("/advances/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__ADVANCE)
    public Map<String, Object> rejectAdvance(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return payroll.rejectAdvance(id, dto);
    }

    @GetMapping("/loans")
    @RequirePermissions(Permissions.HR__LOAN)
    public Map<String, Object> listLoans(@RequestParam Map<String, String> query) {
        return payroll.listLoans(query);
    }

    @PostMapping("/loans")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__LOAN)
    public Map<String, Object> createLoan(@RequestBody Map<String, Object> dto) {
        return payroll.createLoan(dto);
    }

    @PatchMapping("/loans/{id}")
    @RequirePermissions(Permissions.HR__LOAN)
    public Map<String, Object> updateLoan(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return payroll.updateLoan(id, dto);
    }

    @DeleteMapping("/loans/{id}")
    @RequirePermissions(Permissions.HR__LOAN)
    public Map<String, Object> deleteLoan(@PathVariable String id) {
        return payroll.deleteLoan(id);
    }

    // ─── performance reviews ───

    @GetMapping("/reviews")
    @RequirePermissions(Permissions.HR__PERFORMANCE)
    public Map<String, Object> listReviews(@RequestParam Map<String, String> query) {
        return reports.listReviews(query);
    }

    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PERFORMANCE)
    public Map<String, Object> createReview(@RequestBody Map<String, Object> dto) {
        return reports.createReview(dto);
    }

    @PatchMapping("/reviews/{id}")
    @RequirePermissions(Permissions.HR__PERFORMANCE)
    public Map<String, Object> updateReview(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return reports.updateReview(id, dto);
    }

    @DeleteMapping("/reviews/{id}")
    @RequirePermissions(Permissions.HR__PERFORMANCE)
    public Map<String, Object> deleteReview(@PathVariable String id) {
        return reports.deleteReview(id);
    }

    // ─── self-service additions (HP-8): payslip / earnings PDFs, requests, claims, inbox, appraisals ───

    @GetMapping("/me/payslips/{id}/pdf")
    public ResponseEntity<byte[]> myPayslipPdf(@PathVariable String id) {
        return HrPayrollOpsController.file(ess.myPayslipPdf(id));
    }

    @GetMapping("/me/earnings/{year}")
    public Map<String, Object> myEarnings(@PathVariable int year) {
        return ess.myAnnualSummary(year);
    }

    @GetMapping("/me/earnings/{year}/pdf")
    public ResponseEntity<byte[]> myEarningsPdf(@PathVariable int year) {
        return HrPayrollOpsController.file(ess.myAnnualCertificate(year));
    }

    @GetMapping("/me/profile-requests")
    public Map<String, Object> myProfileRequests() {
        return ess.myProfileRequests();
    }

    @PostMapping("/me/profile-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> requestProfileChange(@RequestBody Map<String, Object> dto) {
        return ess.requestProfileChange(dto);
    }

    @PostMapping("/me/profile-requests/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> cancelProfileRequest(@PathVariable String id) {
        return ess.cancelProfileRequest(id);
    }

    @GetMapping("/me/claims")
    public Map<String, Object> myClaims() {
        return ess.myClaims();
    }

    @PostMapping("/me/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submitClaim(@RequestBody Map<String, Object> dto) {
        return ess.submitClaim(dto);
    }

    @PostMapping("/me/claims/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> cancelClaim(@PathVariable String id) {
        return ess.cancelClaim(id);
    }

    @GetMapping("/me/approvals")
    public Map<String, Object> approvals() {
        return ess.approvals();
    }

    @PostMapping("/me/approvals/claims/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> managerApproveClaim(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return ess.reviewClaim(id, true, dto, true);
    }

    @PostMapping("/me/approvals/claims/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> managerRejectClaim(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return ess.reviewClaim(id, false, dto, true);
    }

    @GetMapping("/me/appraisals")
    public Map<String, Object> myAppraisals() {
        return talent.myAppraisals((String) ess.selfId());
    }

    @GetMapping("/me/appraisals/{id}")
    public Map<String, Object> myAppraisal(@PathVariable String id) {
        return ess.ownAppraisal(talent, id);
    }

    @PostMapping("/me/appraisals/{id}/submit")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submitMyAppraisal(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return talent.submit(id, dto, (String) ess.selfId());
    }
}
