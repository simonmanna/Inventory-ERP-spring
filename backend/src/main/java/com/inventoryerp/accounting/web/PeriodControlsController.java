package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.PeriodControlsService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Period controls (finance readiness audit B1, P2-2; no Nest counterpart): the books lock date, whether every posting
 * needs an open fiscal period, automatic creation of next year's periods and the furthest posting date ahead.
 */
@RestController
@RequestMapping("/fiscal-periods/controls")
public class PeriodControlsController {

    /** The full set of controls (PUT replaces it); {@code booksLockDate} null or absent = the books are not locked. */
    public record UpdateRequest(
            @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be a date (YYYY-MM-DD)") String booksLockDate,
            @NotNull(message = "must be a boolean value") Boolean requireFiscalPeriod,
            @NotNull(message = "must be a boolean value") Boolean autoCreatePeriods,
            @NotNull(message = "must be an integer number")
            @Min(value = 0, message = "must not be less than 0")
            @Max(value = 36500, message = "must not be greater than 36500") Integer futurePostingLimitDays) {}

    private final PeriodControlsService service;

    public PeriodControlsController(PeriodControlsService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermissions(Permissions.FISCAL_PERIOD__READ)
    public Map<String, Object> get() {
        return service.get();
    }

    /** Moving the lock date earlier or removing it also needs {@code fiscal_period:reopen} (checked by the service). */
    @PutMapping
    @RequirePermissions(Permissions.FISCAL_PERIOD__UPDATE)
    public Map<String, Object> update(@Valid @RequestBody UpdateRequest dto) {
        return service.update(new PeriodControlsService.Controls(dto.booksLockDate(), dto.requireFiscalPeriod(),
                dto.autoCreatePeriods(), dto.futurePostingLimitDays()));
    }
}
