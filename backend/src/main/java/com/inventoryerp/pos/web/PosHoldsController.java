package com.inventoryerp.pos.web;

import com.inventoryerp.shared.validation.ArrayMinSize;
import static com.inventoryerp.pos.web.PosDtos.ARR;
import static com.inventoryerp.pos.web.PosDtos.MIN1;
import static com.inventoryerp.pos.web.PosDtos.NUM;
import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosHoldsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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

/** {@code /pos/holds} (port of {@code pos-holds.controller.ts}). */
@RestController
@RequestMapping("/pos/holds")
public class PosHoldsController {

    /** {@code PosHoldLineDto}. */
    public record PosHoldLineRequest(
            String productId,
            @NotNull(message = STR) String description,
            @NotNull(message = NUM) Double quantity,
            @NotNull(message = NUM) Double unitPrice,
            Double discountPercent,
            String taxId,
            String note) {}

    /** {@code CreatePosHoldDto}. */
    public record CreatePosHoldRequest(
            @NotNull(message = STR) String name,
            String partnerId,
            String branchId,
            String cashSessionId,
            String notes,
            @NotNull(message = ARR) @ArrayMinSize(1) @Valid List<PosHoldLineRequest> lines) {}

    private final PosHoldsService svc;

    public PosHoldsController(PosHoldsService svc) {
        this.svc = svc;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__HOLD)
    public Map<String, Object> create(@Valid @RequestBody CreatePosHoldRequest dto) {
        return svc.create(PosDtos.data(dto));
    }

    @GetMapping
    @RequirePermissions(Permissions.POS__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String branchId, @RequestParam(required = false) String cashSessionId) {
        return svc.list(status, branchId, cashSessionId);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return svc.get(id);
    }

    @PostMapping("/{id}/recall")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__HOLD)
    public Map<String, Object> recall(@PathVariable String id) {
        return svc.recall(id);
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.POS__HOLD)
    public Map<String, Object> cancel(@PathVariable String id) {
        return svc.cancel(id);
    }

    /** Untyped Nest body {@code { notes: string }}. */
    @PatchMapping("/{id}/notes")
    @RequirePermissions(Permissions.POS__HOLD)
    public Map<String, Object> updateNotes(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return svc.updateNotes(id, body.get("notes"));
    }
}
