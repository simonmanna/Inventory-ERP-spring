package com.inventoryerp.costing.web;

import com.inventoryerp.costing.application.SimulationService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /costing/simulation} (Cost Management CM-11, ADR-S042; extension): versioned cost scenarios and what-if
 * results against a profitability build. Never posts.
 */
@RestController
@RequestMapping("/costing/simulation")
public class CostSimulationController {

    public record ScenarioRequest(
            @NotBlank(message = "name should not be empty")
            @Size(max = 120, message = "name must be at most 120 characters") String name,
            Map<String, Object> parameters,
            @Size(max = 2000, message = "notes must be at most 2000 characters") String notes) {}

    public record SimulateRequest(
            @NotBlank(message = "profitabilityRunId should not be empty") String profitabilityRunId,
            String groupBy,
            Boolean includeUnallocated) {}

    private final SimulationService simulation;

    public CostSimulationController(SimulationService simulation) {
        this.simulation = simulation;
    }

    @GetMapping("/scenarios")
    @RequirePermissions(Permissions.COST_SIMULATION__READ)
    public List<Map<String, Object>> scenarios() {
        return simulation.scenarios();
    }

    @PostMapping("/scenarios")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COST_SIMULATION__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody ScenarioRequest r) {
        return simulation.createScenario(r.name(), r.parameters(), r.notes());
    }

    @GetMapping("/scenarios/{id}")
    @RequirePermissions(Permissions.COST_SIMULATION__READ)
    public Map<String, Object> scenario(@PathVariable String id) {
        return simulation.scenario(id);
    }

    @PostMapping("/scenarios/{id}/simulate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COST_SIMULATION__MANAGE)
    public Map<String, Object> simulate(@PathVariable String id, @Valid @RequestBody SimulateRequest r) {
        return simulation.simulate(id, r.profitabilityRunId(), r.groupBy(), r.includeUnallocated());
    }

    @GetMapping("/scenarios/{id}/results")
    @RequirePermissions(Permissions.COST_SIMULATION__READ)
    public List<Map<String, Object>> results(@PathVariable String id) {
        return simulation.results(id);
    }

    @GetMapping("/results/{resultId}")
    @RequirePermissions(Permissions.COST_SIMULATION__READ)
    public Map<String, Object> result(@PathVariable String resultId) {
        return simulation.result(resultId);
    }
}
