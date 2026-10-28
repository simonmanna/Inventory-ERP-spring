package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.ProcessCostingService;
import com.inventoryerp.manufacturing.application.ProcessCostingService.ProcessInput;
import com.inventoryerp.manufacturing.application.ProcessCostingService.UnitsInput;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /manufacturing/process-costing} — processes, periods, inputs and period close (ADR-S008 §2). */
@RestController
@RequestMapping("/manufacturing/process-costing")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class ProcessCostingController {

    static final String NEG = "must not be less than 0";
    static final String PCT = "must be between 0 and 100";

    /** Process body. */
    public record ProcessRequest(String code, String name, Integer sequence,
            @OneOf({"weighted_average", "fifo"}) String method, String nextProcessId, String outputProductId,
            String outputLocationId, String costCenterId, Boolean isActive, String notes) {}

    /** Open-period body. */
    public record PeriodRequest(
            @NotNull(message = "periodFrom must be a date (YYYY-MM-DD)") String periodFrom,
            @NotNull(message = "periodTo must be a date (YYYY-MM-DD)") String periodTo) {}

    /** Units body. */
    public record UnitsRequest(
            @DecimalMin(value = "0", message = "unitsStarted " + NEG) BigDecimal unitsStarted,
            @DecimalMin(value = "0", message = "unitsCompleted " + NEG) BigDecimal unitsCompleted,
            @DecimalMin(value = "0", message = "closingUnits " + NEG) BigDecimal closingUnits,
            @DecimalMin(value = "0", message = "closingMaterialPct " + PCT)
            @DecimalMax(value = "100", message = "closingMaterialPct " + PCT) BigDecimal closingMaterialPct,
            @DecimalMin(value = "0", message = "closingConversionPct " + PCT)
            @DecimalMax(value = "100", message = "closingConversionPct " + PCT) BigDecimal closingConversionPct,
            @DecimalMin(value = "0", message = "normalLossUnits " + NEG) BigDecimal normalLossUnits,
            @DecimalMin(value = "0", message = "abnormalLossUnits " + NEG) BigDecimal abnormalLossUnits,
            @DecimalMin(value = "0", message = "abnormalMaterialPct " + PCT)
            @DecimalMax(value = "100", message = "abnormalMaterialPct " + PCT) BigDecimal abnormalMaterialPct,
            @DecimalMin(value = "0", message = "abnormalConversionPct " + PCT)
            @DecimalMax(value = "100", message = "abnormalConversionPct " + PCT) BigDecimal abnormalConversionPct,
            @DecimalMin(value = "0", message = "openingUnits " + NEG) BigDecimal openingUnits,
            @DecimalMin(value = "0", message = "openingMaterialPct " + PCT)
            @DecimalMax(value = "100", message = "openingMaterialPct " + PCT) BigDecimal openingMaterialPct,
            @DecimalMin(value = "0", message = "openingConversionPct " + PCT)
            @DecimalMax(value = "100", message = "openingConversionPct " + PCT) BigDecimal openingConversionPct,
            @DecimalMin(value = "0", message = "openingMaterialCost " + NEG) BigDecimal openingMaterialCost,
            @DecimalMin(value = "0", message = "openingConversionCost " + NEG) BigDecimal openingConversionCost,
            @DecimalMin(value = "0", message = "openingTransferredInCost " + NEG) BigDecimal openingTransferredInCost) {}

    /** Material issue body. */
    public record MaterialRequest(
            @NotNull(message = "productId must be a string") @NotEmpty(message = "productId should not be empty")
            String productId,
            @NotNull(message = "locationId must be a string") @NotEmpty(message = "locationId should not be empty")
            String locationId,
            @NotNull(message = "quantity must be a positive number") @Positive(message = "quantity must be a positive number")
            BigDecimal quantity,
            String description) {}

    /** Conversion cost body. */
    public record CostRequest(
            @NotNull(message = "element must be one of the following values: labour, overhead, material")
            @OneOf({"labour", "overhead", "material"}) String element,
            @NotNull(message = "amount must be a positive number") @Positive(message = "amount must be a positive number")
            BigDecimal amount,
            String creditAccountId,
            String description) {}

    private final ProcessCostingService svc;

    public ProcessCostingController(ProcessCostingService svc) {
        this.svc = svc;
    }

    private static ProcessInput input(ProcessRequest d) {
        return new ProcessInput(d.code(), d.name(), d.sequence(), d.method(), d.nextProcessId(), d.outputProductId(),
                d.outputLocationId(), d.costCenterId(), d.isActive(), d.notes());
    }

    @GetMapping("/processes")
    @RequirePermissions(Permissions.PROCESS_COSTING__READ)
    public List<Map<String, Object>> processes() {
        return svc.processes();
    }

    @GetMapping("/processes/{id}")
    @RequirePermissions(Permissions.PROCESS_COSTING__READ)
    public Map<String, Object> process(@PathVariable String id) {
        return svc.process(id);
    }

    @PostMapping("/processes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCESS_COSTING__MANAGE)
    public Map<String, Object> createProcess(@Valid @RequestBody ProcessRequest dto) {
        return svc.createProcess(input(dto));
    }

    @PatchMapping("/processes/{id}")
    @RequirePermissions(Permissions.PROCESS_COSTING__MANAGE)
    public Map<String, Object> updateProcess(@PathVariable String id, @Valid @RequestBody ProcessRequest dto) {
        return svc.updateProcess(id, input(dto));
    }

    @PostMapping("/processes/{id}/periods")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCESS_COSTING__MANAGE)
    public Map<String, Object> openPeriod(@PathVariable String id, @Valid @RequestBody PeriodRequest dto) {
        return svc.openPeriod(id, day(dto.periodFrom(), "periodFrom"), day(dto.periodTo(), "periodTo"));
    }

    @GetMapping("/periods/{id}")
    @RequirePermissions(Permissions.PROCESS_COSTING__READ)
    public Map<String, Object> period(@PathVariable String id) {
        return svc.period(id);
    }

    @GetMapping("/periods/{id}/preview")
    @RequirePermissions(Permissions.PROCESS_COSTING__READ)
    public Map<String, Object> preview(@PathVariable String id) {
        return svc.preview(id);
    }

    @PatchMapping("/periods/{id}/units")
    @RequirePermissions(Permissions.PROCESS_COSTING__MANAGE)
    public Map<String, Object> units(@PathVariable String id, @Valid @RequestBody UnitsRequest d) {
        return svc.updateUnits(id, new UnitsInput(d.unitsStarted(), d.unitsCompleted(), d.closingUnits(),
                d.closingMaterialPct(), d.closingConversionPct(), d.normalLossUnits(), d.abnormalLossUnits(),
                d.abnormalMaterialPct(), d.abnormalConversionPct(), d.openingUnits(), d.openingMaterialPct(),
                d.openingConversionPct(), d.openingMaterialCost(), d.openingConversionCost(),
                d.openingTransferredInCost()));
    }

    @PostMapping("/periods/{id}/materials")
    @Idempotent
    @RequirePermissions(Permissions.PROCESS_COSTING__MANAGE)
    public Map<String, Object> material(@PathVariable String id, @Valid @RequestBody MaterialRequest dto) {
        return svc.issueMaterial(id, dto.productId(), dto.locationId(), dto.quantity(), dto.description());
    }

    @PostMapping("/periods/{id}/costs")
    @Idempotent
    @RequirePermissions(Permissions.PROCESS_COSTING__MANAGE)
    public Map<String, Object> cost(@PathVariable String id, @Valid @RequestBody CostRequest dto) {
        return svc.addCost(id, dto.element(), dto.amount(), dto.creditAccountId(), dto.description());
    }

    @PostMapping("/periods/{id}/close")
    @Idempotent
    @RequirePermissions(Permissions.PROCESS_COSTING__CLOSE)
    public Map<String, Object> close(@PathVariable String id) {
        return svc.close(id);
    }

    @GetMapping("/reports/cost-of-production")
    @RequirePermissions(Permissions.PROCESS_COSTING__READ)
    public List<Map<String, Object>> report(@RequestParam String from, @RequestParam String to) {
        return svc.report(day(from, "from"), day(to, "to"));
    }

    static LocalDate day(String v, String field) {
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest(field + " must be a date (YYYY-MM-DD)");
        }
    }
}
