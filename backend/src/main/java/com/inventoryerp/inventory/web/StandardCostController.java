package com.inventoryerp.inventory.web;

import com.inventoryerp.inventory.application.StandardCostService;
import com.inventoryerp.inventory.application.StandardCostService.StandardInput;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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

/** {@code /inventory/standard-costs} — versioned product standards and their variances (ADR-S008). */
@RestController
@RequestMapping("/inventory/standard-costs")
public class StandardCostController {

    static final String DAY = "^\\d{4}-\\d{2}-\\d{2}$";
    static final String NEG = "must not be less than 0";

    /** Create body. */
    public record CreateStandardRequest(
            @NotNull(message = "productId must be a string") @NotEmpty(message = "productId should not be empty")
            String productId,
            @NotNull(message = "effectiveFrom must be a date (YYYY-MM-DD)")
            @Pattern(regexp = DAY, message = "effectiveFrom must be a date (YYYY-MM-DD)") String effectiveFrom,
            @DecimalMin(value = "0", message = "materialCost " + NEG) BigDecimal materialCost,
            @DecimalMin(value = "0", message = "labourCost " + NEG) BigDecimal labourCost,
            @DecimalMin(value = "0", message = "overheadCost " + NEG) BigDecimal overheadCost,
            @DecimalMin(value = "0", message = "subcontractCost " + NEG) BigDecimal subcontractCost,
            String notes,
            Boolean convertMethod) {}

    private final StandardCostService standards;

    public StandardCostController(StandardCostService standards) {
        this.standards = standards;
    }

    @GetMapping
    @RequirePermissions(Permissions.STANDARD_COST__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String productId,
            @RequestParam(required = false) String status) {
        return standards.list(productId, status);
    }

    @GetMapping("/variances")
    @RequirePermissions(Permissions.STANDARD_COST__READ)
    public Map<String, Object> variances(@RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String productId) {
        return standards.variances(day(from, "from"), day(to, "to"), kind, productId);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.STANDARD_COST__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return standards.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.STANDARD_COST__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody CreateStandardRequest dto) {
        return standards.create(new StandardInput(dto.productId(), dto.effectiveFrom(), dto.materialCost(),
                dto.labourCost(), dto.overheadCost(), dto.subcontractCost(), dto.notes(), dto.convertMethod(), "manual",
                null));
    }

    @PostMapping("/{id}/approve")
    @RequirePermissions(Permissions.STANDARD_COST__APPROVE)
    public Map<String, Object> approve(@PathVariable String id) {
        return standards.approve(id);
    }

    @PostMapping("/{id}/activate")
    @Idempotent
    @RequirePermissions(Permissions.STANDARD_COST__APPROVE)
    public Map<String, Object> activate(@PathVariable String id) {
        return standards.activate(id);
    }

    @PostMapping("/{id}/cancel")
    @RequirePermissions(Permissions.STANDARD_COST__MANAGE)
    public Map<String, Object> cancel(@PathVariable String id) {
        return standards.cancel(id);
    }

    static LocalDate day(String v, String field) {
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest(field + " must be a date (YYYY-MM-DD)");
        }
    }
}
