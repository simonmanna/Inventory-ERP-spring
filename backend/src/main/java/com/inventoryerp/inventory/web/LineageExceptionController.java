package com.inventoryerp.inventory.web;

import com.inventoryerp.inventory.application.LineageExceptionService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Landed cost LC-1 lineage exceptions (ADR-S053; extension, no NestJS counterpart): shares parked in 1396 because their
 * goods could not be followed, and their resolution.
 */
@RestController
@RequestMapping("/inventory/lineage-exceptions")
public class LineageExceptionController {

    public record AssignRequest(
            @NotBlank(message = "destinationLedgerId should not be empty") String destinationLedgerId,
            @NotBlank(message = "reason should not be empty") @Size(max = 500) String reason) {}

    public record WriteOffRequest(
            @NotBlank(message = "accountId should not be empty") String accountId,
            @NotBlank(message = "reason should not be empty") @Size(max = 500) String reason) {}

    private final LineageExceptionService exceptions;

    public LineageExceptionController(LineageExceptionService exceptions) {
        this.exceptions = exceptions;
    }

    @GetMapping
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return exceptions.list(status);
    }

    @PostMapping("/{id}/assign")
    @Idempotent
    @RequirePermissions(Permissions.LANDED_COST__RESOLVE_LINEAGE)
    public Map<String, Object> assign(@PathVariable String id, @Valid @RequestBody AssignRequest dto) {
        return exceptions.assign(id, dto.destinationLedgerId(), dto.reason());
    }

    @PostMapping("/{id}/write-off")
    @Idempotent
    @RequirePermissions(Permissions.LANDED_COST__RESOLVE_LINEAGE)
    public Map<String, Object> writeOff(@PathVariable String id, @Valid @RequestBody WriteOffRequest dto) {
        return exceptions.writeOff(id, dto.accountId(), dto.reason());
    }
}
