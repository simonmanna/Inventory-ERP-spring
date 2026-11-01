package com.inventoryerp.costing.web;

import com.inventoryerp.costing.application.CostAdjustmentService;
import com.inventoryerp.costing.application.CostCloseService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
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
 * {@code /costing/close} and {@code /costing/cost-adjustment-runs} (Cost Management CM-10, ADR-S041; extension): the
 * ordered close gates, reconciliation dashboard, costing period lock / reopen and the AVCO / FIFO cost adjustment run.
 */
@RestController
@RequestMapping("/costing")
public class CostCloseController {

    public record PeriodRequest(
            @NotBlank(message = "from should not be empty") String from,
            @NotBlank(message = "to should not be empty") String to) {}

    public record ReopenRequest(String reason) {}

    private final CostCloseService close;
    private final CostAdjustmentService adjustments;

    public CostCloseController(CostCloseService close, CostAdjustmentService adjustments) {
        this.close = close;
        this.adjustments = adjustments;
    }

    @GetMapping("/close/gates")
    @RequirePermissions(Permissions.COSTING_CLOSE__READ)
    public Map<String, Object> gates(@RequestParam String from, @RequestParam String to) {
        return close.gates(from, to);
    }

    @GetMapping("/close/dashboard")
    @RequirePermissions(Permissions.COSTING_CLOSE__READ)
    public Map<String, Object> dashboard(@RequestParam(required = false) String asOf) {
        return close.dashboard(asOf);
    }

    @GetMapping("/close/locks")
    @RequirePermissions(Permissions.COSTING_CLOSE__READ)
    public List<Map<String, Object>> locks() {
        return close.locks();
    }

    @PostMapping("/close/locks")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COSTING_CLOSE__MANAGE)
    public Map<String, Object> lock(@Valid @RequestBody PeriodRequest r) {
        return close.lock(r.from(), r.to());
    }

    @PostMapping("/close/locks/{id}/reopen")
    @RequirePermissions(Permissions.COSTING_CLOSE__REOPEN)
    public Map<String, Object> reopen(@PathVariable String id, @RequestBody ReopenRequest r) {
        return close.reopen(id, r.reason());
    }

    @GetMapping("/cost-adjustment-runs/preview")
    @RequirePermissions(Permissions.COSTING_CLOSE__READ)
    public Map<String, Object> preview() {
        return adjustments.preview();
    }

    @GetMapping("/cost-adjustment-runs")
    @RequirePermissions(Permissions.COSTING_CLOSE__READ)
    public List<Map<String, Object>> runs() {
        return adjustments.runs();
    }

    @GetMapping("/cost-adjustment-runs/{id}")
    @RequirePermissions(Permissions.COSTING_CLOSE__READ)
    public Map<String, Object> run(@PathVariable String id) {
        return adjustments.get(id);
    }

    @PostMapping("/cost-adjustment-runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.COSTING_CLOSE__MANAGE)
    public Map<String, Object> runAdjustment(@Valid @RequestBody PeriodRequest r) {
        return adjustments.run(r.from(), r.to());
    }

    @PostMapping("/cost-adjustment-runs/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.COSTING_CLOSE__MANAGE)
    public Map<String, Object> reverse(@PathVariable String id) {
        return adjustments.reverse(id);
    }
}
