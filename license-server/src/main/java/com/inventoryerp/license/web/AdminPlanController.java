package com.inventoryerp.license.web;

import com.inventoryerp.license.application.AdminAudits;
import com.inventoryerp.license.application.AdminTokens;
import com.inventoryerp.license.application.Entitlements;
import com.inventoryerp.license.application.PlanService;
import com.inventoryerp.license.domain.AdminRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The price list. Only an {@code owner} changes what is sold. */
@RestController
@RequestMapping("/admin/plans")
@RequireAdmin
public class AdminPlanController {

    private final PlanService plans;
    private final AdminAudits audits;

    public AdminPlanController(PlanService plans, AdminAudits audits) {
        this.plans = plans;
        this.audits = audits;
    }

    @GetMapping
    public List<AdminDtos.PlanResponse> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return plans.list(activeOnly).stream().map(AdminDtos.PlanResponse::of).toList();
    }

    /** The feature keys an ERP release understands, so the UI can offer them rather than free text. */
    @GetMapping("/features")
    public Set<String> knownFeatures() {
        return new TreeSet<>(Entitlements.KNOWN_FEATURES);
    }

    @GetMapping("/{id}")
    public AdminDtos.PlanResponse get(@PathVariable String id) {
        return AdminDtos.PlanResponse.of(plans.get(id));
    }

    @PostMapping
    @RequireAdmin(AdminRole.OWNER)
    @ResponseStatus(HttpStatus.CREATED)
    public AdminDtos.PlanResponse create(@Valid @RequestBody AdminDtos.PlanRequest body,
            AdminTokens.Principal actor, HttpServletRequest request) {
        var created = plans.create(body.code(), body.name(), body.productCode(), body.period(),
                body.periodCount(), body.priceMinor(), body.currency(), body.maxDevices(), body.maxUsers(),
                body.maxOrganizations(), body.instanceLimit(), body.features(), body.graceDays(), body.isTrial(),
                body.sortOrder());
        audits.record(actor, "plan.create", created.getId(), ClientRateLimitInterceptor.clientIp(request),
                Map.of("code", created.getCode()));
        return AdminDtos.PlanResponse.of(created);
    }

    @PutMapping("/{id}")
    @RequireAdmin(AdminRole.OWNER)
    public AdminDtos.PlanResponse update(@PathVariable String id,
            @Valid @RequestBody AdminDtos.PlanPatchRequest body, AdminTokens.Principal actor,
            HttpServletRequest request) {
        var updated = plans.update(id, body.name(), body.priceMinor(), body.currency(), body.maxDevices(),
                body.maxUsers(), body.maxOrganizations(), body.instanceLimit(), body.features(),
                body.features() != null, body.graceDays(), body.isActive(), body.sortOrder(), body.periodCount());
        audits.record(actor, "plan.update", id, ClientRateLimitInterceptor.clientIp(request), Map.of());
        return AdminDtos.PlanResponse.of(updated);
    }
}
