package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.ARR;
import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosKdsService;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** {@code /pos/kds} (port of {@code pos-kds.controller.ts}). */
@RestController
@RequestMapping("/pos/kds")
public class PosKdsController {

    static final String ACTION_MSG = "action must be one of the following values: start, ready, serve, cancel, recall";

    /** {@code KdsModifierDto}. */
    public record KdsModifierRequest(
            @NotNull(message = STR) String name,
            String kitchenPrintName,
            Double priceDelta) {}

    /** {@code SendToKitchenItemDto}. */
    public record SendToKitchenItemRequest(
            @NotNull(message = STR) String productId,
            @NotNull(message = STR) String productName,
            @DecimalMin(value = "1", message = "must not be less than 1") Double quantity,
            String notes,
            String variantName,
            List<String> accompanimentNames,
            @Valid List<KdsModifierRequest> modifiers) {}

    /** {@code SendToKitchenDto}. */
    public record SendToKitchenRequest(
            @NotNull(message = STR) String label,
            String tableId,
            @NotNull(message = ARR) @Valid List<SendToKitchenItemRequest> items) {}

    /** {@code KdsTransitionDto}. */
    public record KdsTransitionRequest(
            @NotNull(message = ACTION_MSG) @OneOf({"start", "ready", "serve", "cancel", "recall"}) String action,
            String reason) {}

    /** {@code KdsBulkTransitionDto}. */
    public record KdsBulkTransitionRequest(
            @NotNull(message = ARR) List<String> ids,
            @NotNull(message = ACTION_MSG) @OneOf({"start", "ready", "serve", "cancel", "recall"}) String action,
            String reason) {}

    /** {@code KdsPriorityDto}. */
    public record KdsPriorityRequest(
            @NotNull(message = "priority must be one of the following values: normal, rush, vip")
            @OneOf({"normal", "rush", "vip"}) String priority) {}

    /** {@code KdsAssignDto}. */
    public record KdsAssignRequest(String chefUserId) {}

    private final PosKdsService svc;

    public PosKdsController(PosKdsService svc) {
        this.svc = svc;
    }

    @GetMapping("/tickets")
    @RequirePermissions(Permissions.POS__KDS)
    public List<Map<String, Object>> list(@RequestParam(required = false) String station,
            @RequestParam(required = false) String status) {
        return svc.listTickets(station, status);
    }

    @GetMapping("/stream")
    @RequirePermissions(Permissions.POS__KDS)
    public SseEmitter stream(@RequestParam(required = false) String station) {
        return svc.streamTickets(station);
    }

    @GetMapping("/tickets/{id}")
    @RequirePermissions(Permissions.POS__KDS)
    public Map<String, Object> get(@PathVariable String id) {
        return svc.getTicket(id);
    }

    @PostMapping("/tickets/bulk-transition")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__KDS)
    public Map<String, Object> bulkTransition(@Valid @RequestBody KdsBulkTransitionRequest body) {
        return svc.bulkTransition(body.ids(), body.action(), body.reason());
    }

    @PostMapping("/tickets/{id}/transition")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__KDS)
    public Map<String, Object> transition(@PathVariable String id, @Valid @RequestBody KdsTransitionRequest body) {
        return svc.transition(id, body.action(), body.reason());
    }

    @PostMapping("/tickets/{id}/priority")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__KDS)
    public Map<String, Object> priority(@PathVariable String id, @Valid @RequestBody KdsPriorityRequest body) {
        return svc.setPriority(id, body.priority());
    }

    @PostMapping("/tickets/{id}/assign")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__KDS)
    public Map<String, Object> assign(@PathVariable String id, @Valid @RequestBody KdsAssignRequest body) {
        return svc.assignChef(id, body.chefUserId());
    }

    @PostMapping("/send-to-kitchen")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    @SuppressWarnings("unchecked")
    public List<String> sendToKitchen(@Valid @RequestBody SendToKitchenRequest dto) {
        return svc.createTicketsFromCart(dto.label(), dto.tableId(),
                (List<Map<String, Object>>) PosDtos.js(dto.items()));
    }
}
