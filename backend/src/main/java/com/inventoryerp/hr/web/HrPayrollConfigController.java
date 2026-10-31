package com.inventoryerp.hr.web;

import com.inventoryerp.hr.application.HrOvertimeService;
import com.inventoryerp.hr.application.HrPayrollCostService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /hr/payroll} additions beyond the Nest port: overtime rules (HR-3) and cost allocation (HR-5). */
@RestController
@RequestMapping("/hr/payroll")
@RequiresModule("hr")
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrPayrollConfigController {

    private final HrOvertimeService overtime;
    private final HrPayrollCostService costs;

    public HrPayrollConfigController(HrOvertimeService overtime, HrPayrollCostService costs) {
        this.overtime = overtime;
        this.costs = costs;
    }

    @GetMapping("/overtime-rules")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> listOvertimeRules(@RequestParam Map<String, String> query) {
        return overtime.list(query);
    }

    @PostMapping("/overtime-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> createOvertimeRule(@RequestBody Map<String, Object> dto) {
        return overtime.create(dto);
    }

    @PatchMapping("/overtime-rules/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> updateOvertimeRule(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return overtime.update(id, dto);
    }

    @DeleteMapping("/overtime-rules/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> deleteOvertimeRule(@PathVariable String id) {
        return overtime.delete(id);
    }

    @GetMapping("/cost-allocation-rules")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> listCostRules(@RequestParam Map<String, String> query) {
        return costs.listRules(query);
    }

    @PutMapping("/cost-allocation-rules")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> setCostRules(@RequestBody Map<String, Object> dto) {
        return costs.setRules(dto);
    }

    @GetMapping("/runs/{id}/cost-allocation")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> runCostReport(@PathVariable String id) {
        return costs.runReport(id);
    }

    @GetMapping("/cost-report")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> costReport(@RequestParam Map<String, String> query) {
        return costs.periodReport(query);
    }
}
