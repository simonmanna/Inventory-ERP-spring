package com.inventoryerp.hr.web;

import com.inventoryerp.hr.application.HrPayrollSetupService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.List;
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

/** {@code /hr/payroll} foundations (HP-0): settings, statutory packs, pay groups, pay calendars, period locks. */
@RestController
@RequestMapping("/hr/payroll")
@RequiresModule("hr")
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrPayrollSetupController {

    private final HrPayrollSetupService setup;

    public HrPayrollSetupController(HrPayrollSetupService setup) {
        this.setup = setup;
    }

    @GetMapping("/settings")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> getSettings() {
        return setup.getSettings();
    }

    @PutMapping("/settings")
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> updateSettings(@RequestBody Map<String, Object> dto) {
        return setup.updateSettings(dto);
    }

    @GetMapping("/statutory-packs")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public List<Map<String, Object>> statutoryPacks() {
        return setup.statutoryPacks();
    }

    @GetMapping("/pay-groups")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> listPayGroups(@RequestParam Map<String, String> query) {
        return setup.listPayGroups(query);
    }

    @PostMapping("/pay-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> createPayGroup(@RequestBody Map<String, Object> dto) {
        return setup.createPayGroup(dto);
    }

    @GetMapping("/pay-groups/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> getPayGroup(@PathVariable String id) {
        return setup.getPayGroup(id);
    }

    @PatchMapping("/pay-groups/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> updatePayGroup(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return setup.updatePayGroup(id, dto);
    }

    @DeleteMapping("/pay-groups/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> deletePayGroup(@PathVariable String id) {
        return setup.deletePayGroup(id);
    }

    @GetMapping("/pay-groups/{id}/members")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> listMembers(@PathVariable String id, @RequestParam Map<String, String> query) {
        return setup.listMembers(id, query);
    }

    @PutMapping("/pay-groups/{id}/members")
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> assignMembers(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return setup.assignMembers(id, dto);
    }

    @PostMapping("/pay-groups/{id}/periods")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> generatePeriods(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return setup.generatePeriods(id, dto);
    }

    @GetMapping("/calendar")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> calendar(@RequestParam Map<String, String> query) {
        return setup.calendar(query);
    }

    @PostMapping("/periods/{id}/lock")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> lockPeriod(@PathVariable String id) {
        return setup.lockPeriod(id);
    }

    @PostMapping("/periods/{id}/unlock")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> unlockPeriod(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return setup.unlockPeriod(id, dto);
    }
}
