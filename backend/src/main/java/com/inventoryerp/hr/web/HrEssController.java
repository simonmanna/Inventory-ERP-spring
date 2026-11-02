package com.inventoryerp.hr.web;

import com.inventoryerp.hr.application.HrEssService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR review of self-service requests (HP-8): profile changes and pay claims. The employee's own routes
 * ({@code /hr/me/*}, any signed-in employee) live in {@link HrController} next to the Nest self-service routes.
 */
@RestController
@RequestMapping("/hr")
@RequiresModule("hr")
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrEssController {

    private final HrEssService ess;

    public HrEssController(HrEssService ess) {
        this.ess = ess;
    }

    @GetMapping("/profile-requests")
    @RequirePermissions(Permissions.HR__EMPLOYEE)
    public Map<String, Object> listProfileRequests(@RequestParam Map<String, String> query) {
        return ess.listProfileRequests(query);
    }

    @PostMapping("/profile-requests/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__EMPLOYEE)
    public Map<String, Object> approveProfileRequest(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return ess.reviewProfileRequest(id, true, dto == null ? Map.of() : dto);
    }

    @PostMapping("/profile-requests/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__EMPLOYEE)
    public Map<String, Object> rejectProfileRequest(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return ess.reviewProfileRequest(id, false, dto);
    }

    @GetMapping("/claims")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> listClaims(@RequestParam Map<String, String> query) {
        return ess.listClaims(query);
    }

    @PostMapping("/claims/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> approveClaim(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return ess.reviewClaim(id, true, dto == null ? Map.of() : dto, false);
    }

    @PostMapping("/claims/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> rejectClaim(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return ess.reviewClaim(id, false, dto, false);
    }
}
