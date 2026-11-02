package com.inventoryerp.hr.web;

import com.inventoryerp.hr.application.HrCompensationService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /hr/compensation} (HP-1): grades, pay elements, salary structures, salary revisions, employee elements. */
@RestController
@RequestMapping("/hr/compensation")
@RequiresModule("hr")
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrCompensationController {

    private final HrCompensationService comp;

    public HrCompensationController(HrCompensationService comp) {
        this.comp = comp;
    }

    @GetMapping("/grades")
    @RequirePermissions(Permissions.HR__COMPENSATION)
    public Map<String, Object> listGrades() {
        return comp.listGrades();
    }

    @PostMapping("/grades")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> createGrade(@RequestBody Map<String, Object> dto) {
        return comp.saveGrade(null, dto);
    }

    @PatchMapping("/grades/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> updateGrade(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return comp.saveGrade(id, dto);
    }

    @DeleteMapping("/grades/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> deleteGrade(@PathVariable String id) {
        return comp.deleteGrade(id);
    }

    @GetMapping("/elements")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> listElements(@RequestParam Map<String, String> query) {
        return comp.listElements(query);
    }

    @PostMapping("/elements")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> createElement(@RequestBody Map<String, Object> dto) {
        return comp.saveElement(null, dto);
    }

    @PatchMapping("/elements/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> updateElement(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return comp.saveElement(id, dto);
    }

    @DeleteMapping("/elements/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> deleteElement(@PathVariable String id) {
        return comp.deleteElement(id);
    }

    @GetMapping("/structures")
    @RequirePermissions(Permissions.HR__COMPENSATION)
    public Map<String, Object> listStructures() {
        return comp.listStructures();
    }

    @PostMapping("/structures")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> createStructure(@RequestBody Map<String, Object> dto) {
        return comp.saveStructure(null, dto);
    }

    @PatchMapping("/structures/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> updateStructure(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return comp.saveStructure(id, dto);
    }

    @DeleteMapping("/structures/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> deleteStructure(@PathVariable String id) {
        return comp.deleteStructure(id);
    }

    @GetMapping("/revisions")
    @RequirePermissions(Permissions.HR__COMPENSATION)
    public Map<String, Object> listRevisions(@RequestParam Map<String, String> query) {
        return comp.listRevisions(query);
    }

    @PostMapping("/revisions/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__COMPENSATION_APPROVE)
    public Map<String, Object> approve(@PathVariable String id) {
        return comp.approve(id);
    }

    @PostMapping("/revisions/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__COMPENSATION_APPROVE)
    public Map<String, Object> reject(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return comp.reject(id, dto);
    }

    @GetMapping("/employees/{employeeId}")
    @RequirePermissions(Permissions.HR__COMPENSATION)
    public Map<String, Object> history(@PathVariable String employeeId) {
        return comp.history(employeeId);
    }

    @PostMapping("/employees/{employeeId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__COMPENSATION)
    public Map<String, Object> propose(@PathVariable String employeeId, @RequestBody Map<String, Object> dto) {
        return comp.propose(employeeId, dto);
    }

    @GetMapping("/employees/{employeeId}/elements")
    @RequirePermissions(Permissions.HR__COMPENSATION)
    public Map<String, Object> listEmployeeElements(@PathVariable String employeeId) {
        return comp.listEmployeeElements(employeeId);
    }

    @PostMapping("/employees/{employeeId}/elements")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__COMPENSATION)
    public Map<String, Object> addEmployeeElement(@PathVariable String employeeId,
            @RequestBody Map<String, Object> dto) {
        return comp.saveEmployeeElement(employeeId, null, dto);
    }

    @PatchMapping("/employees/{employeeId}/elements/{id}")
    @RequirePermissions(Permissions.HR__COMPENSATION)
    public Map<String, Object> updateEmployeeElement(@PathVariable String employeeId, @PathVariable String id,
            @RequestBody Map<String, Object> dto) {
        return comp.saveEmployeeElement(employeeId, id, dto);
    }

    @DeleteMapping("/employees/{employeeId}/elements/{id}")
    @RequirePermissions(Permissions.HR__COMPENSATION)
    public Map<String, Object> deleteEmployeeElement(@PathVariable String employeeId, @PathVariable String id) {
        return comp.deleteEmployeeElement(employeeId, id);
    }
}
