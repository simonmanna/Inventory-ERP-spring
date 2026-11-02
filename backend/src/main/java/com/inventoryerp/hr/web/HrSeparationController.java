package com.inventoryerp.hr.web;

import com.inventoryerp.hr.application.HrSeparationService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /hr/separations} (HP-6): offboarding, clearance, final settlement, gratuity provision. */
@RestController
@RequestMapping("/hr/separations")
@RequiresModule("hr")
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrSeparationController {

    private final HrSeparationService separations;

    public HrSeparationController(HrSeparationService separations) {
        this.separations = separations;
    }

    @GetMapping
    @RequirePermissions(Permissions.HR__SEPARATION)
    public Map<String, Object> list(@RequestParam Map<String, String> query) {
        return separations.list(query);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__SEPARATION)
    public Map<String, Object> create(@RequestBody Map<String, Object> dto) {
        return separations.create(dto);
    }

    @GetMapping("/gratuity")
    @RequirePermissions(Permissions.HR__SEPARATION)
    public Map<String, Object> provisions(@RequestParam Map<String, String> query) {
        return separations.provisions(query);
    }

    @PostMapping("/gratuity/accrue")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> accrue(@RequestBody Map<String, Object> dto) {
        Object asOf = dto.get("asOf");
        return separations.accrueProvision(asOf == null ? LocalDate.now(ZoneOffset.UTC)
                : LocalDate.parse(String.valueOf(asOf)));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.HR__SEPARATION)
    public Map<String, Object> get(@PathVariable String id) {
        return separations.get(id);
    }

    @PostMapping("/{id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__SEPARATION)
    public Map<String, Object> addTask(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return separations.addTask(id, dto);
    }

    @PatchMapping("/{id}/tasks/{taskId}")
    @RequirePermissions(Permissions.HR__SEPARATION)
    public Map<String, Object> updateTask(@PathVariable String id, @PathVariable String taskId,
            @RequestBody Map<String, Object> dto) {
        return separations.updateTask(id, taskId, dto);
    }

    @PostMapping("/{id}/calculate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__SEPARATION)
    public Map<String, Object> calculate(@PathVariable String id) {
        return separations.calculate(id);
    }

    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> approve(@PathVariable String id) {
        return separations.approve(id);
    }

    @PostMapping("/{id}/pay")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> pay(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return separations.pay(id, dto);
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__SEPARATION)
    public Map<String, Object> cancel(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return separations.cancel(id, dto);
    }
}
