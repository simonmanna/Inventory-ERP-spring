package com.inventoryerp.inventory.web;

import com.inventoryerp.inventory.application.StockReservationService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /inventory/reservations} soft reservations / ATP (port of {@code stock-reservation.controller.ts}). */
@RestController
@RequestMapping("/inventory/reservations")
public class StockReservationController {

    /** {@code ReserveStockDto}. */
    public record ReserveRequest(
            @NotNull(message = "must be a string") String productId,
            String variantId,
            @NotNull(message = "must be a string") String locationId,
            @NotNull(message = "must be a positive number") @Positive(message = "must be a positive number")
            BigDecimal quantity,
            @NotNull(message = "must be a string") String sourceType,
            @NotNull(message = "must be a string") String sourceId,
            String reason) {}

    /** {@code ReleaseDto}. */
    public record ReleaseRequest(
            @NotNull(message = "must be a string") String sourceType,
            @NotNull(message = "must be a string") String sourceId) {}

    private final StockReservationService reservations;

    public StockReservationController(StockReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INVENTORY__MOVE)
    public Map<String, Object> reserve(@Valid @RequestBody ReserveRequest dto) {
        return reservations.reserve(new StockReservationService.Reserve(dto.productId(), dto.variantId(),
                dto.locationId(), dto.quantity(), dto.sourceType(), dto.sourceId(), dto.reason()));
    }

    @PostMapping("/release")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INVENTORY__MOVE)
    public Map<String, Object> release(@Valid @RequestBody ReleaseRequest dto) {
        return reservations.release(dto.sourceType(), dto.sourceId());
    }

    @PostMapping("/consume")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INVENTORY__MOVE)
    public Map<String, Object> consume(@Valid @RequestBody ReleaseRequest dto) {
        return reservations.consume(dto.sourceType(), dto.sourceId());
    }

    @GetMapping("/atp")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public Map<String, Object> atp(@RequestParam(required = false) String productId,
            @RequestParam(required = false) String locationId, @RequestParam(required = false) String variantId) {
        return reservations.availableToPromise(productId, locationId, variantId);
    }

    @GetMapping
    @RequirePermissions(Permissions.INVENTORY__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String sourceId) {
        return reservations.listForSource(sourceType, sourceId);
    }
}
