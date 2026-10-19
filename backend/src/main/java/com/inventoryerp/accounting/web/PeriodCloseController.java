package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.PeriodCloseService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /fiscal-periods/:id/close|lock|reopen} (port of {@code period-close.controller.ts}). */
@RestController
@RequestMapping("/fiscal-periods")
public class PeriodCloseController {

    private final PeriodCloseService periodClose;

    public PeriodCloseController(PeriodCloseService periodClose) {
        this.periodClose = periodClose;
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
