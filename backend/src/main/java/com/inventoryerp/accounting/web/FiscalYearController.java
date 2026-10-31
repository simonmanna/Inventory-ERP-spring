package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.FiscalYearService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /fiscal-years}: fiscal years and the year-end close (roadmap 1.7, ADR-S023, extension). */
@RestController
@RequestMapping("/fiscal-years")
public class FiscalYearController {

    private static final String DATE = "^\\d{4}-\\d{2}-\\d{2}$";

    public record CreateFiscalYearRequest(
            String name,
            @NotNull(message = "must be a valid ISO 8601 date string")
            @Pattern(regexp = DATE, message = "must be a valid ISO 8601 date string") String startDate,
            @Pattern(regexp = DATE, message = "must be a valid ISO 8601 date string") String endDate,
            @OneOf({"monthly", "quarterly", "yearly"}) String periodicity,
            Boolean createPeriods) {}

    public record SignOffRequest(@NotNull(message = "must be a boolean value") Boolean signed, String note) {}

    public record CloseFiscalYearRequest(Boolean lockPeriods) {}

    private final FiscalYearService years;

    public FiscalYearController(FiscalYearService years) {
        this.years = years;
    }

    @GetMapping
    @RequirePermissions(Permissions.FISCAL_PERIOD__READ)
    public List<Map<String, Object>> list() {
        return years.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FISCAL_PERIOD__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateFiscalYearRequest dto) {
        return years.create(dto.name(), LocalDate.parse(dto.startDate()),
                dto.endDate() == null ? null : LocalDate.parse(dto.endDate()), dto.periodicity(),
                dto.createPeriods() == null || dto.createPeriods());
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.FISCAL_PERIOD__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return years.get(id);
    }

    @GetMapping("/{id}/close-checklist")
    @RequirePermissions(Permissions.FISCAL_PERIOD__READ)
    public Map<String, Object> checklist(@PathVariable String id) {
        return years.checklist(id);
    }

    @PutMapping("/{id}/close-checklist/{key}")
    @RequirePermissions(Permissions.FISCAL_PERIOD__UPDATE)
    public Map<String, Object> signOff(@PathVariable String id, @PathVariable String key,
            @Valid @RequestBody SignOffRequest dto) {
        return years.signOff(id, key, dto.signed(), dto.note());
    }

    @GetMapping("/{id}/closing-balances")
    @RequirePermissions(Permissions.FISCAL_PERIOD__READ)
    public Map<String, Object> closingBalances(@PathVariable String id) {
        return years.closingBalances(id);
    }

    @PostMapping("/{id}/close")
    @Idempotent
    @RequirePermissions(Permissions.FISCAL_PERIOD__UPDATE)
    public Map<String, Object> close(@PathVariable String id, @RequestBody(required = false) CloseFiscalYearRequest dto) {
        return years.close(id, dto == null || dto.lockPeriods() == null || dto.lockPeriods());
    }

    @PostMapping("/{id}/reopen")
    @Idempotent
    @RequirePermissions(Permissions.FISCAL_PERIOD__REOPEN)
    public Map<String, Object> reopen(@PathVariable String id) {
        return years.reopen(id);
    }
}
