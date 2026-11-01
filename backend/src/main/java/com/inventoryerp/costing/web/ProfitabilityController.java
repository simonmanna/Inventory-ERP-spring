package com.inventoryerp.costing.web;

import com.inventoryerp.costing.application.ProfitabilityService;
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
 * {@code /costing/profitability} (Cost Management CM-9, ADR-S040; extension): profitability facts by layer (actual /
 * standard / estimate — one layer per response, never mixed) and the ACTUAL reconciliation to the GL P&amp;L.
 */
@RestController
@RequestMapping("/costing/profitability")
public class ProfitabilityController {

    public record BuildRequest(
            @NotBlank(message = "from should not be empty") String from,
            @NotBlank(message = "to should not be empty") String to) {}

    private final ProfitabilityService profitability;

    public ProfitabilityController(ProfitabilityService profitability) {
        this.profitability = profitability;
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROFITABILITY__MANAGE)
    public Map<String, Object> build(@Valid @RequestBody BuildRequest r) {
        return profitability.build(r.from(), r.to());
    }

    @GetMapping("/runs")
    @RequirePermissions(Permissions.PROFITABILITY__READ)
    public List<Map<String, Object>> runs() {
        return profitability.runs();
    }

    @GetMapping("/runs/{id}")
    @RequirePermissions(Permissions.PROFITABILITY__READ)
    public Map<String, Object> run(@PathVariable String id) {
        return profitability.run(id);
    }

    @GetMapping("/runs/{id}/facts")
    @RequirePermissions(Permissions.PROFITABILITY__READ)
    public Map<String, Object> facts(@PathVariable String id, @RequestParam String layer,
            @RequestParam(required = false) String groupBy) {
        return profitability.facts(id, layer, groupBy);
    }

    @GetMapping("/runs/{id}/reconciliation")
    @RequirePermissions(Permissions.PROFITABILITY__READ)
    public Map<String, Object> reconciliation(@PathVariable String id) {
        return profitability.reconciliation(id);
    }
}
