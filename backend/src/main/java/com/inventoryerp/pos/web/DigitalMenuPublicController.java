package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.ARR;
import static com.inventoryerp.pos.web.PosDtos.NUM;
import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.pos.application.DigitalMenuService;
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

/**
 * {@code /menu/public} (port of {@code digital-menu-public.controller.ts}). Despite the name, Nest does not mark it
 * {@code @Public()}: the global JWT guard applies, so a bearer token is required (no permission). Kept on purpose.
 */
@RestController
@RequestMapping("/menu/public")
public class DigitalMenuPublicController {

    /** {@code PublicOrderLineDto}. */
    public record PublicOrderLineRequest(
            @NotNull(message = STR) String productId,
            @NotNull(message = NUM) @DecimalMin(value = "1", message = "must not be less than 1") Double quantity,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = "must not be less than 0") Double unitPrice,
            @NotNull(message = STR) String description,
            List<Object> modifiers,
            String comboId) {}

    /** {@code PublicTenderDto}. */
    public record PublicTenderRequest(
            @NotNull(message = "method must be one of the following values: mobile_money, card, cash_on_pickup, wallet, qr")
            @OneOf({"mobile_money", "card", "cash_on_pickup", "wallet", "qr"}) String method,
            @NotNull(message = NUM) @DecimalMin(value = "0.01", message = "must not be less than 0.01") Double amount,
            String reference) {}

    /** {@code PlaceOrderDto}. */
    public record PlaceOrderRequest(
            @NotNull(message = STR) String token,
            @NotNull(message = STR) String customerName,
            String customerPhone,
            String customerEmail,
            @OneOf({"dine_in", "takeaway", "pickup"}) String orderType,
            String notes,
            @NotNull(message = ARR) @Valid List<PublicOrderLineRequest> lines,
            @NotNull(message = ARR) @Valid List<PublicTenderRequest> tenders) {}

    private final DigitalMenuService svc;

    public DigitalMenuPublicController(DigitalMenuService svc) {
        this.svc = svc;
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog(@RequestParam(required = false) String token) {
        return svc.getPublicCatalog(token);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> placeOrder(@Valid @RequestBody PlaceOrderRequest dto) {
        return svc.placeOrder(PosDtos.data(dto));
    }

    @GetMapping("/orders/{id}/track")
    public Map<String, Object> track(@PathVariable String id, @RequestParam(required = false) String token) {
        return svc.trackOrder(id, token);
    }
}
