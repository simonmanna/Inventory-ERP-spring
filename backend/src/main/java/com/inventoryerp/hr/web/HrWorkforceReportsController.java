package com.inventoryerp.hr.web;

import com.inventoryerp.hr.application.HrWorkforceReportsService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /hr/analytics} (HP-10): payroll register, summaries, cost, headcount, salary changes, statutory, leave. */
@RestController
@RequestMapping("/hr/analytics")
@RequiresModule("hr")
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrWorkforceReportsController {

    private final HrWorkforceReportsService reports;

    public HrWorkforceReportsController(HrWorkforceReportsService reports) {
        this.reports = reports;
    }

    @GetMapping("/payroll-register/{runId}")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> register(@PathVariable String runId) {
        return reports.register(runId);
    }

    @GetMapping("/payroll-register/{runId}/csv")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public ResponseEntity<byte[]> registerCsv(@PathVariable String runId) {
        return HrPayrollOpsController.file(reports.registerCsv(runId));
    }

    @GetMapping("/payroll-summary")
    @RequirePermissions(Permissions.HR__REPORT)
    public Map<String, Object> payrollSummary(@RequestParam Map<String, String> query) {
        return reports.payrollSummary(query);
    }

    @GetMapping("/cost-by-department")
    @RequirePermissions(Permissions.HR__REPORT)
    public Map<String, Object> costByDepartment(@RequestParam Map<String, String> query) {
        return reports.costByDepartment(query);
    }

    @GetMapping("/headcount")
    @RequirePermissions(Permissions.HR__REPORT)
    public Map<String, Object> headcount(@RequestParam Map<String, String> query) {
        return reports.headcount(query);
    }

    @GetMapping("/salary-changes")
    @RequirePermissions(Permissions.HR__COMPENSATION)
    public Map<String, Object> salaryChanges(@RequestParam Map<String, String> query) {
        return reports.salaryChanges(query);
    }

    @GetMapping("/statutory")
    @RequirePermissions(Permissions.HR__REPORT)
    public Map<String, Object> statutory(@RequestParam Map<String, String> query) {
        return reports.statutory(query);
    }

    @GetMapping("/leave-liability")
    @RequirePermissions(Permissions.HR__REPORT)
    public Map<String, Object> leaveLiability(@RequestParam Map<String, String> query) {
        return reports.leaveLiability(query);
    }
}
