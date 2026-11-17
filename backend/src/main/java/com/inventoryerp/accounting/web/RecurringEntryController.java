package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.GlAutomationCommands.RunInput;
import com.inventoryerp.accounting.application.GlAutomationCommands.TemplateInput;
import com.inventoryerp.accounting.application.RecurringJournalService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
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

/** {@code /recurring-entries}: recurring journal templates (ADR-S073 extension; Nest has no counterpart). */
@RestController
@RequestMapping("/recurring-entries")
public class RecurringEntryController {

    private final RecurringJournalService service;

    public RecurringEntryController(RecurringJournalService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermissions(Permissions.RECURRING_ENTRY__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return service.list(status);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.RECURRING_ENTRY__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/{id}/runs")
    @RequirePermissions(Permissions.RECURRING_ENTRY__READ)
    public List<Map<String, Object>> runs(@PathVariable String id) {
        return service.runs(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RECURRING_ENTRY__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody TemplateInput dto) {
        return service.create(dto);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.RECURRING_ENTRY__MANAGE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody TemplateInput dto) {
        return service.update(id, dto);
    }

    @PostMapping("/{id}/pause")
    @RequirePermissions(Permissions.RECURRING_ENTRY__MANAGE)
    public Map<String, Object> pause(@PathVariable String id) {
        return service.pause(id);
    }

    @PostMapping("/{id}/resume")
    @RequirePermissions(Permissions.RECURRING_ENTRY__MANAGE)
    public Map<String, Object> resume(@PathVariable String id) {
        return service.resume(id);
    }

    /**
     * Maker-checker approval (R3): an approved {@code post}-mode template posts its occurrences; until then they are
     * staged as drafts. The approver needs the journal-entry post permission and must not be the template's author or
     * last editor.
     */
    @PostMapping("/{id}/approve")
    @RequirePermissions(Permissions.JOURNAL_ENTRY__POST)
    public Map<String, Object> approve(@PathVariable String id) {
        return service.approve(id);
    }

    /** Generate one occurrence now (posts, or stages a draft in maker-checker mode or while unapproved). */
    @PostMapping("/{id}/run")
    @Idempotent
    @RequirePermissions(Permissions.RECURRING_ENTRY__MANAGE)
    public Map<String, Object> run(@PathVariable String id, @Valid @RequestBody(required = false) RunInput dto) {
        return service.runNow(id, dto == null ? null : dto.date());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.RECURRING_ENTRY__MANAGE)
    public void remove(@PathVariable String id) {
        service.remove(id);
    }
}
