package com.inventoryerp.hr.web;

import com.inventoryerp.hr.application.HrLeavePolicyService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

/** {@code /hr/leave} additions (HP-4): policies, accrual and year-end runs, encashment, ledger, day count, calendar. */
@RestController
@RequestMapping("/hr/leave")
@RequiresModule("hr")
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrLeavePolicyController {

    private final HrLeavePolicyService leave;

    public HrLeavePolicyController(HrLeavePolicyService leave) {
        this.leave = leave;
    }

    @GetMapping("/policies")
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> listPolicies() {
        return leave.listPolicies();
    }

    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> createPolicy(@RequestBody Map<String, Object> dto) {
        return leave.savePolicy(null, dto);
    }

    @PatchMapping("/policies/{id}")
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> updatePolicy(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return leave.savePolicy(id, dto);
    }

    @DeleteMapping("/policies/{id}")
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> deletePolicy(@PathVariable String id) {
        return leave.deletePolicy(id);
    }

    @PostMapping("/accruals/run")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> accrue(@RequestBody Map<String, Object> dto) {
        Object asOf = dto.get("asOf");
        return leave.accrue(asOf == null ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(String.valueOf(asOf)));
    }

    @PostMapping("/year-end")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> yearEnd(@RequestBody Map<String, Object> dto) {
        if (!(dto.get("year") instanceof Number n)) {
            throw ApiException.badRequest("year is required");
        }
        return leave.yearEnd(n.intValue());
    }

    @PostMapping("/encashments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> encash(@RequestBody Map<String, Object> dto) {
        return leave.encash(dto);
    }

    @GetMapping("/ledger")
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> ledger(@RequestParam Map<String, String> query) {
        return leave.ledger(query);
    }

    @GetMapping("/count-days")
    @RequirePermissions(Permissions.HR__READ)
    public Map<String, Object> countDays(@RequestParam Map<String, String> query) {
        return leave.countDays(query);
    }

    @GetMapping("/calendar")
    @RequirePermissions(Permissions.HR__LEAVE)
    public Map<String, Object> calendar(@RequestParam Map<String, String> query) {
        return leave.calendar(query);
    }
}
