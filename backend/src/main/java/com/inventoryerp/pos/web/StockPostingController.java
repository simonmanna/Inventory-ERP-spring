package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosStockPostingService;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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

/** {@code /pos/posting-monitor} (port of {@code billing/stock-posting.controller.ts}). */
@RestController
@RequestMapping("/pos/posting-monitor")
public class StockPostingController {

    /** {@code ResolveExceptionDto}. */
    public record ResolveExceptionRequest(
            @NotNull(message = "status must be one of the following values: resolved, discarded")
            @OneOf({"resolved", "discarded"}) String status,
            @NotNull(message = STR) String note) {}

    /** {@code AssignExceptionDto}. */
    public record AssignExceptionRequest(@NotNull(message = STR) String assignedToId) {}

    private final PosStockPostingService svc;

    public StockPostingController(PosStockPostingService svc) {
        this.svc = svc;
    }

    @GetMapping("/counts")
    @RequirePermissions(Permissions.POS__REPORTS)
    public Map<String, Object> counts() {
        return svc.counts();
    }

    @GetMapping("/jobs")
    @RequirePermissions(Permissions.POS__REPORTS)
    public List<Map<String, Object>> jobs(@RequestParam(required = false) String status) {
        return svc.listJobs(status == null ? "all" : status);
    }

    @PostMapping("/jobs/{id}/retry")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public Map<String, Object> retryJob(@PathVariable String id) {
        return svc.retryJob(id);
    }

    @GetMapping("/exceptions")
    @RequirePermissions(Permissions.POS__REPORTS)
    public List<Map<String, Object>> exceptions(@RequestParam(required = false) String status) {
        return svc.listExceptions(status == null ? "open" : status);
    }

    @PostMapping("/exceptions/{id}/assign")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public Map<String, Object> assign(@PathVariable String id, @Valid @RequestBody AssignExceptionRequest dto) {
        return svc.assignException(id, dto.assignedToId());
    }

    @PostMapping("/exceptions/{id}/resolve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public Map<String, Object> resolve(@PathVariable String id, @Valid @RequestBody ResolveExceptionRequest dto) {
        return svc.resolveException(id, dto.status(), dto.note());
    }
}
