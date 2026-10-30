package com.inventoryerp.quarry.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.quarry.application.QuarryCommands.PlanInput;
import com.inventoryerp.quarry.application.QuarryPlanService;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
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

/** Extraction plans and production targets with actual vs target (ADR-S012). */
@RestController
@RequestMapping("/quarry")
@RequiresModule("quarry")
@ConditionalOnProperty(name = "app.modules.quarry", havingValue = "true")
@Validated
public class QuarryPlanController {

    private final QuarryPlanService plans;

    public QuarryPlanController(QuarryPlanService plans) {
        this.plans = plans;
    }

    /** {@code activeOn}: plans whose period contains that day. */
    @GetMapping("/extraction-plans")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String siteId,
            @RequestParam(required = false) @OneOf({"draft", "active", "closed"}) String status,
            @RequestParam(required = false) LocalDate activeOn,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return plans.list(siteId, status, activeOn, search, page, pageSize);
    }

    @GetMapping("/extraction-plans/{id}")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return plans.get(id);
    }

    @PostMapping("/extraction-plans")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__PLAN)
    public Map<String, Object> create(@Valid @RequestBody PlanInput dto) {
        return plans.create(dto);
    }

    @PatchMapping("/extraction-plans/{id}")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__PLAN)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody PlanInput dto) {
        return plans.update(id, dto);
    }

    @DeleteMapping("/extraction-plans/{id}")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__PLAN)
    public Map<String, Object> delete(@PathVariable String id) {
        plans.delete(id);
        return Map.of("id", id, "deleted", true);
    }
}
