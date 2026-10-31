package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.PeriodCloseChecklistService;
import com.inventoryerp.accounting.application.PeriodCloseService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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

/** {@code /fiscal-periods/:id/close|lock|reopen} (port of {@code period-close.controller.ts}). */
@RestController
@RequestMapping("/fiscal-periods")
public class PeriodCloseController {

    /** Sign or unsign one manual checklist step. */
    public record SignOffRequest(@NotNull(message = "must be a boolean value") Boolean signed, String note) {}

    private final PeriodCloseService periodClose;
    private final PeriodCloseChecklistService checklist;

    public PeriodCloseController(PeriodCloseService periodClose, PeriodCloseChecklistService checklist) {
        this.periodClose = periodClose;
        this.checklist = checklist;
    }

    @GetMapping("/{id}/close-checklist")
    @RequirePermissions(Permissions.FISCAL_PERIOD__READ)
    public Map<String, Object> closeChecklist(@PathVariable String id) {
        return checklist.checklist(id);
    }

    @PutMapping("/{id}/close-checklist/{key}")
    @RequirePermissions(Permissions.FISCAL_PERIOD__UPDATE)
    public Map<String, Object> signOff(@PathVariable String id, @PathVariable String key,
            @Valid @RequestBody SignOffRequest dto) {
        return checklist.signOff(id, key, dto.signed(), dto.note());
    }

    @PostMapping("/{id}/close")
    @Idempotent
    @RequirePermissions(Permissions.FISCAL_PERIOD__UPDATE)
    public Map<String, Object> close(@PathVariable String id) {
        return periodClose.close(id);
    }

    @PostMapping("/{id}/lock")
    @Idempotent
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.FISCAL_PERIOD__UPDATE)
    public void lock(@PathVariable String id) {
        periodClose.lock(id);
    }

    @PostMapping("/{id}/reopen")
    @Idempotent
    @RequirePermissions(Permissions.FISCAL_PERIOD__REOPEN)
    public Map<String, Object> reopen(@PathVariable String id) {
        return periodClose.reopen(id);
    }
}
