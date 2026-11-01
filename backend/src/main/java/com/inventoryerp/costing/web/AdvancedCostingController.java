package com.inventoryerp.costing.web;

import com.inventoryerp.costing.application.ActivityBasedCostingService;
import com.inventoryerp.costing.application.ActivityBasedCostingService.ActivityInput;
import com.inventoryerp.costing.application.MaterialLedgerService;
import com.inventoryerp.costing.application.VarianceAnalysisService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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

/**
 * {@code /costing}: material-ledger actual costing, variance analysis and activity-based costing (roadmap 3.1, ADR-S030,
 * extension).
 */
@RestController
@RequestMapping("/costing")
public class AdvancedCostingController {

    public record PeriodRequest(
            @NotBlank(message = "from should not be empty") String from,
            @NotBlank(message = "to should not be empty") String to) {}

    public record AbcRunRequest(
            @NotBlank(message = "costObject should not be empty") String costObject,
            @NotBlank(message = "from should not be empty") String from,
            @NotBlank(message = "to should not be empty") String to) {}

    public record ActivityRequest(
            @NotBlank(message = "code should not be empty") String code,
            @NotBlank(message = "name should not be empty") @Size(max = 120, message = "name must be at most 120"
                    + " characters") String name,
            @Size(max = 500, message = "description must be at most 500 characters") String description,
            @NotBlank(message = "costObject should not be empty") String costObject,
            @NotBlank(message = "driver should not be empty") String driver,
            List<String> accountIds,
            String costCenterId,
            BigDecimal poolPercent,
            Boolean isActive) {}

    public record ActivityUpdateRequest(
            @Size(min = 1, max = 120, message = "name must be 1 to 120 characters") String name,
            @Size(max = 500, message = "description must be at most 500 characters") String description,
            String driver,
            List<String> accountIds,
            String costCenterId,
            BigDecimal poolPercent,
            Boolean isActive) {}

    private final MaterialLedgerService ledger;
    private final VarianceAnalysisService variances;
    private final ActivityBasedCostingService abc;

    public AdvancedCostingController(MaterialLedgerService ledger, VarianceAnalysisService variances,
            ActivityBasedCostingService abc) {
        this.ledger = ledger;
        this.variances = variances;
        this.abc = abc;
    }

    // ---- material ledger ------------------------------------------------------------------------------------------

    @GetMapping("/material-ledger/runs")
    @RequirePermissions(Permissions.ADVANCED_COSTING__READ)
    public List<Map<String, Object>> runs() {
        return ledger.list();
    }

    @PostMapping("/material-ledger/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ADVANCED_COSTING__MANAGE)
    public Map<String, Object> createRun(@Valid @RequestBody PeriodRequest dto) {
        return ledger.create(dto.from(), dto.to());
    }

    @GetMapping("/material-ledger/runs/{id}")
    @RequirePermissions(Permissions.ADVANCED_COSTING__READ)
    public Map<String, Object> run(@PathVariable String id) {
        return ledger.get(id);
    }

    @DeleteMapping("/material-ledger/runs/{id}")
    @RequirePermissions(Permissions.ADVANCED_COSTING__MANAGE)
    public Map<String, Object> deleteRun(@PathVariable String id) {
        return ledger.delete(id);
    }

    @PostMapping("/material-ledger/runs/{id}/recompute")
    @RequirePermissions(Permissions.ADVANCED_COSTING__MANAGE)
    public Map<String, Object> recompute(@PathVariable String id) {
        return ledger.recompute(id);
    }

    @PostMapping("/material-ledger/runs/{id}/post")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.ADVANCED_COSTING__POST)
    public Map<String, Object> post(@PathVariable String id) {
        return ledger.post(id);
    }

    @PostMapping("/material-ledger/runs/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.ADVANCED_COSTING__POST)
    public Map<String, Object> reverse(@PathVariable String id) {
        return ledger.reverse(id);
    }

    // ---- variance analysis ----------------------------------------------------------------------------------------

    @GetMapping("/variance-analysis")
    @RequirePermissions(Permissions.ADVANCED_COSTING__READ)
    public Map<String, Object> varianceAnalysis(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return variances.analysis(from, to);
    }

    // ---- activity-based costing -----------------------------------------------------------------------------------

    @GetMapping("/abc/drivers")
    @RequirePermissions(Permissions.ADVANCED_COSTING__READ)
    public Map<String, Object> drivers() {
        return abc.drivers();
    }

    @GetMapping("/abc/activities")
    @RequirePermissions(Permissions.ADVANCED_COSTING__READ)
    public List<Map<String, Object>> activities() {
        return abc.activities();
    }

    @PostMapping("/abc/activities")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ADVANCED_COSTING__MANAGE)
    public Map<String, Object> createActivity(@Valid @RequestBody ActivityRequest dto) {
        return abc.create(new ActivityInput(dto.code(), dto.name(), dto.description(), dto.costObject(), dto.driver(),
                dto.accountIds(), dto.costCenterId(), dto.poolPercent(), dto.isActive()));
    }

    @PatchMapping("/abc/activities/{id}")
    @RequirePermissions(Permissions.ADVANCED_COSTING__MANAGE)
    public Map<String, Object> updateActivity(@PathVariable String id, @Valid @RequestBody ActivityUpdateRequest dto) {
        return abc.update(id, new ActivityInput(null, dto.name(), dto.description(), null, dto.driver(),
                dto.accountIds(), dto.costCenterId(), dto.poolPercent(), dto.isActive()));
    }

    @DeleteMapping("/abc/activities/{id}")
    @RequirePermissions(Permissions.ADVANCED_COSTING__MANAGE)
    public Map<String, Object> deleteActivity(@PathVariable String id) {
        return abc.delete(id);
    }

    @GetMapping("/abc/runs")
    @RequirePermissions(Permissions.ADVANCED_COSTING__READ)
    public List<Map<String, Object>> abcRuns() {
        return abc.runs();
    }

    @PostMapping("/abc/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ADVANCED_COSTING__MANAGE)
    public Map<String, Object> computeAbc(@Valid @RequestBody AbcRunRequest dto) {
        return abc.compute(dto.costObject(), dto.from(), dto.to());
    }

    @GetMapping("/abc/runs/{id}")
    @RequirePermissions(Permissions.ADVANCED_COSTING__READ)
    public Map<String, Object> abcRun(@PathVariable String id) {
        return abc.run(id);
    }
}
