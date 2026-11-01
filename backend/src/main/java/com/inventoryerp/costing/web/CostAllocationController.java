package com.inventoryerp.costing.web;

import com.inventoryerp.costing.application.CostCycleService;
import com.inventoryerp.costing.application.CostCycleService.CycleInput;
import com.inventoryerp.costing.application.CostCycleService.KeyValueInput;
import com.inventoryerp.costing.application.CostCycleService.SegmentInput;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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

/**
 * {@code /costing/allocation-*} and {@code /costing/statistical-keys} (Cost Management CM-3, ADR-S033; extension):
 * cost-centre allocation cycles (financial / statistical, sequential / reciprocal), runs and statistical keys.
 */
@RestController
@RequestMapping("/costing")
public class CostAllocationController {

    public record SegmentRequest(
            @Size(max = 120, message = "name must be at most 120 characters") String name,
            @NotBlank(message = "senderCostCenterId should not be empty") String senderCostCenterId,
            List<String> senderAccountIds,
            @NotEmpty(message = "receiverIds should not be empty") List<String> receiverIds,
            @NotBlank(message = "basis should not be empty") String basis,
            String statisticalKeyId,
            Map<String, BigDecimal> manualValues,
            BigDecimal sharePercent,
            String rounding) {}

    public record CycleRequest(
            @NotBlank(message = "code should not be empty") String code,
            @NotBlank(message = "name should not be empty") @Size(max = 120, message = "name must be at most 120"
                    + " characters") String name,
            String postingMode,
            String method,
            Boolean isActive,
            @NotEmpty(message = "segments should not be empty") List<@Valid SegmentRequest> segments) {}

    public record CycleUpdateRequest(
            @Size(min = 1, max = 120, message = "name must be 1 to 120 characters") String name,
            String postingMode,
            String method,
            Boolean isActive,
            List<@Valid SegmentRequest> segments) {}

    public record PeriodRequest(
            @NotBlank(message = "from should not be empty") String from,
            @NotBlank(message = "to should not be empty") String to) {}

    public record KeyRequest(
            @NotBlank(message = "code should not be empty") String code,
            @Size(max = 120, message = "name must be at most 120 characters") String name,
            @Size(max = 30, message = "unit must be at most 30 characters") String unit) {}

    public record KeyValueRequest(
            @NotBlank(message = "receiverId should not be empty") String receiverId,
            @NotBlank(message = "periodFrom should not be empty") String periodFrom,
            @NotBlank(message = "periodTo should not be empty") String periodTo,
            BigDecimal value) {}

    public record KeyValuesRequest(@NotEmpty(message = "values should not be empty")
            List<@Valid KeyValueRequest> values) {}

    private final CostCycleService cycles;

    public CostAllocationController(CostCycleService cycles) {
        this.cycles = cycles;
    }

    private static List<SegmentInput> segments(List<SegmentRequest> in) {
        return in == null ? null : in.stream().map(s -> new SegmentInput(s.name(), s.senderCostCenterId(),
                s.senderAccountIds(), s.receiverIds(), s.basis(), s.statisticalKeyId(), s.manualValues(),
                s.sharePercent(), s.rounding())).toList();
    }

    @GetMapping("/allocation-cycles")
    @RequirePermissions(Permissions.COST_ALLOCATION__READ)
    public List<Map<String, Object>> cycles() {
        return cycles.cycles();
    }

    @GetMapping("/allocation-cycles/{id}")
    @RequirePermissions(Permissions.COST_ALLOCATION__READ)
    public Map<String, Object> cycle(@PathVariable String id) {
        return cycles.cycle(id);
    }

    @PostMapping("/allocation-cycles")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COST_ALLOCATION__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody CycleRequest dto) {
        return cycles.createCycle(new CycleInput(dto.code(), dto.name(), dto.postingMode(), dto.method(),
                dto.isActive(), segments(dto.segments())));
    }

    @PutMapping("/allocation-cycles/{id}")
    @RequirePermissions(Permissions.COST_ALLOCATION__MANAGE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody CycleUpdateRequest dto) {
        return cycles.updateCycle(id, new CycleInput(null, dto.name(), dto.postingMode(), dto.method(), dto.isActive(),
                segments(dto.segments())));
    }

    @PostMapping("/allocation-cycles/{id}/preview")
    @RequirePermissions(Permissions.COST_ALLOCATION__MANAGE)
    public Map<String, Object> preview(@PathVariable String id, @Valid @RequestBody PeriodRequest dto) {
        return cycles.preview(id, dto.from(), dto.to());
    }

    @PostMapping("/allocation-cycles/{id}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.COST_ALLOCATION__POST)
    public Map<String, Object> run(@PathVariable String id, @Valid @RequestBody PeriodRequest dto) {
        return cycles.run(id, dto.from(), dto.to());
    }

    @GetMapping("/allocation-runs")
    @RequirePermissions(Permissions.COST_ALLOCATION__READ)
    public List<Map<String, Object>> runs(@RequestParam(required = false) String cycleId) {
        return cycles.runs(cycleId);
    }

    @GetMapping("/allocation-runs/{id}")
    @RequirePermissions(Permissions.COST_ALLOCATION__READ)
    public Map<String, Object> getRun(@PathVariable String id) {
        return cycles.getRun(id);
    }

    @PostMapping("/allocation-runs/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.COST_ALLOCATION__POST)
    public Map<String, Object> reverse(@PathVariable String id) {
        return cycles.reverse(id);
    }

    @GetMapping("/statistical-keys")
    @RequirePermissions(Permissions.COST_ALLOCATION__READ)
    public List<Map<String, Object>> keys() {
        return cycles.keys();
    }

    @PostMapping("/statistical-keys")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COST_ALLOCATION__MANAGE)
    public Map<String, Object> createKey(@Valid @RequestBody KeyRequest dto) {
        return cycles.createKey(dto.code(), dto.name(), dto.unit());
    }

    @PutMapping("/statistical-keys/{id}/values")
    @RequirePermissions(Permissions.COST_ALLOCATION__MANAGE)
    public Map<String, Object> setValues(@PathVariable String id, @Valid @RequestBody KeyValuesRequest dto) {
        return cycles.setKeyValues(id, dto.values().stream().map(v -> new KeyValueInput(v.receiverId(),
                v.periodFrom(), v.periodTo(), v.value())).toList());
    }
}
