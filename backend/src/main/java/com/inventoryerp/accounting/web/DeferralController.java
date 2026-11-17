package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.DeferralService;
import com.inventoryerp.accounting.application.GlAutomationCommands.CancelInput;
import com.inventoryerp.accounting.application.GlAutomationCommands.DeferralFromLineInput;
import com.inventoryerp.accounting.application.GlAutomationCommands.DeferralInput;
import com.inventoryerp.accounting.application.GlAutomationCommands.ReleaseInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import java.time.LocalDate;
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

/** {@code /deferrals}: deferred revenue / prepaid expense schedules (ADR-S073 extension; Nest has no counterpart). */
@RestController
@RequestMapping("/deferrals")
public class DeferralController {

    private final DeferralService service;

    public DeferralController(DeferralService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermissions(Permissions.DEFERRAL__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return service.list(status);
    }

    /** Remaining balance per schedule and per deferral account, reconciled to the GL. */
    @GetMapping("/report")
    @RequirePermissions(Permissions.DEFERRAL__READ)
    public Map<String, Object> report(@RequestParam(required = false) String asOf) {
        return service.report(asOf);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.DEFERRAL__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.DEFERRAL__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody DeferralInput dto) {
        return service.create(dto);
    }

    /** Defer a posted invoice / bill line (the reclassification of a P&amp;L line posts on approval). */
    @PostMapping("/from-journal-line")
    @Idempotent
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.DEFERRAL__MANAGE)
    public Map<String, Object> fromLine(@Valid @RequestBody DeferralFromLineInput dto) {
        return service.createFromLine(dto);
    }

    /**
     * Maker-checker approval (R3): a schedule posts nothing (reclassification, releases) until a user with the
     * journal-entry post permission other than its author approves it.
     */
    @PostMapping("/{id}/approve")
    @Idempotent
    @RequirePermissions(Permissions.JOURNAL_ENTRY__POST)
    public Map<String, Object> approve(@PathVariable String id) {
        return service.approve(id);
    }

    @PostMapping("/{id}/release")
    @Idempotent
    @RequirePermissions(Permissions.DEFERRAL__MANAGE)
    public Map<String, Object> release(@PathVariable String id, @Valid @RequestBody(required = false) ReleaseInput dto) {
        return service.release(id, dto == null ? null : dto.asOf());
    }

    /** Release every schedule's due slices now (what the daily job does). */
    @PostMapping("/run")
    @Idempotent
    @RequirePermissions(Permissions.DEFERRAL__MANAGE)
    public Map<String, Object> run(@Valid @RequestBody(required = false) ReleaseInput dto) {
        return service.releaseDue(dto == null || dto.asOf() == null ? LocalDate.now(BusinessZone.get())
                : LocalDate.parse(dto.asOf().substring(0, 10)));
    }

    @PostMapping("/{id}/cancel")
    @RequirePermissions(Permissions.DEFERRAL__MANAGE)
    public Map<String, Object> cancel(@PathVariable String id, @Valid @RequestBody(required = false) CancelInput dto) {
        return service.cancel(id, dto == null ? null : dto.reason());
    }
}
