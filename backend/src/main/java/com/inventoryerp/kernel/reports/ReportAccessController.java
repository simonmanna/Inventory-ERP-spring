package com.inventoryerp.kernel.reports;

import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.kernel.licensing.LicenseFeatures;
import com.inventoryerp.kernel.licensing.RequiresFeature;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /reports/access/*} (port of {@code report-access.controller.ts}). */
@RestController
// Report access control belongs to the advanced reporting add-on.
@RequiresFeature(LicenseFeatures.ADVANCED_REPORTS)
@RequestMapping("/reports/access")
public class ReportAccessController {

    /** {@code SetRoleReportsDto}: {@code null} clears the override. */
    public record SetRoleReportsRequest(List<String> reports) {}

    private final ReportAccessService access;

    public ReportAccessController(ReportAccessService access) {
        this.access = access;
    }

    @GetMapping("/catalog")
    @RequirePermissions(Permissions.ROLE__READ)
    public Map<String, Object> catalog() {
        return access.catalog();
    }

    @GetMapping("/roles")
    @RequirePermissions(Permissions.ROLE__READ)
    public Map<String, Object> roles() {
        return access.listRoleVisibility();
    }

    @PutMapping("/roles/{roleId}")
    @RequirePermissions(Permissions.ROLE__UPDATE)
    public Map<String, Object> setRole(@PathVariable String roleId, @RequestBody SetRoleReportsRequest dto) {
        return access.setRoleVisibility(roleId, dto.reports());
    }

    /** Reports visible to the caller (only ever narrows what their roles allow). */
    @GetMapping("/me")
    public Map<String, Object> me(AuthUser user) {
        return Map.of("reports", access.reportsForUser(user == null ? null : user.sub()));
    }
}
