package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.DigitalMenuService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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

/** {@code /pos/menu/sessions|orders} (port of {@code digital-menu.controller.ts}). */
@RestController
@RequestMapping("/pos/menu")
public class DigitalMenuController {

    /** {@code CreateSessionDto}. */
    public record CreateSessionRequest(
            @NotNull(message = STR) String branchId,
            String tableNumber,
            @DecimalMin(value = "0.5", message = "must not be less than 0.5") Double expiresInHours) {}

    /**
     * {@code UpdateOrderStatusDto}: its {@code status} has no class-validator decorator, so the global whitelist
     * rejects it ("property status should not exist") — kept as in Nest.
     */
    public record UpdateOrderStatusRequest() {}

    private final DigitalMenuService svc;

    public DigitalMenuController(DigitalMenuService svc) {
        this.svc = svc;
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> createSession(@Valid @RequestBody CreateSessionRequest dto) {
        return svc.createSession(dto.branchId(), dto.tableNumber(), dto.expiresInHours());
    }

    @GetMapping("/sessions")
    @RequirePermissions(Permissions.POS__READ)
    public List<Map<String, Object>> listSessions(@RequestParam(required = false) String branchId) {
        return svc.listSessions(branchId);
    }

    @DeleteMapping("/sessions/{id}")
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> revokeSession(@PathVariable String id) {
        svc.revokeSession(id);
        return Obj.of("ok", true);
    }

    @GetMapping("/orders")
    @RequirePermissions(Permissions.POS__READ)
    public List<Map<String, Object>> listOrders(@RequestParam(required = false) String status) {
        return svc.listOnlineOrders(status);
    }

    @PatchMapping("/orders/{id}/status")
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> updateStatus(@PathVariable String id, @Valid @RequestBody UpdateOrderStatusRequest dto) {
        return svc.updateOrderStatus(id, null);
    }
}
