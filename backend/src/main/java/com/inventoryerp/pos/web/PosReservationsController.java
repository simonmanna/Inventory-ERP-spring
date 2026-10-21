package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.DATE_MSG;
import static com.inventoryerp.pos.web.PosDtos.ISO_DATE;
import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosReservationsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /pos/reservations} (port of {@code pos-reservations.controller.ts}). */
@RestController
@RequestMapping("/pos/reservations")
public class PosReservationsController {

    static final String MIN1 = "must not be less than 1";

    /** {@code CreateReservationBody} (no {@code id}: the whitelist rejects a client-minted id, as in Nest). */
    public record CreateReservationRequest(
            @NotNull(message = STR) String tableId,
            @NotNull(message = STR) String customerName,
            String phone,
            String email,
            @Min(value = 1, message = MIN1) Integer partySize,
            @NotNull(message = DATE_MSG) @Pattern(regexp = ISO_DATE, message = DATE_MSG) String startAt,
            @NotNull(message = DATE_MSG) @Pattern(regexp = ISO_DATE, message = DATE_MSG) String endAt,
            String notes) {}

    /** {@code UpdateReservationBody}. */
    public record UpdateReservationRequest(
            String customerName,
            String phone,
            String email,
            @Min(value = 1, message = MIN1) Integer partySize,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String startAt,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String endAt,
            String notes) {}

    /** {@code SeatBody}. */
    public record SeatRequest(String orderId) {}

    private final PosReservationsService svc;

    public PosReservationsController(PosReservationsService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(Permissions.TABLES__VIEW)
    public List<Map<String, Object>> list(@RequestParam(required = false) String date,
            @RequestParam(required = false) String status, @RequestParam(required = false) String tableId) {
        return svc.list(date, status, tableId);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.TABLES__VIEW)
    public Map<String, Object> get(@PathVariable String id) {
        return svc.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__RESERVE)
    public Map<String, Object> create(@Valid @RequestBody CreateReservationRequest dto) {
        return svc.create(PosDtos.data(dto));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.TABLES__RESERVE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateReservationRequest dto) {
        return svc.update(id, PosDtos.data(dto));
    }

    @PostMapping("/{id}/seat")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__RESERVE)
    public Map<String, Object> seat(@PathVariable String id, @Valid @RequestBody SeatRequest dto) {
        return svc.seat(id, dto.orderId());
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__RESERVE)
    public Map<String, Object> cancel(@PathVariable String id) {
        return svc.cancel(id);
    }

    @PostMapping("/{id}/no-show")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__RESERVE)
    public Map<String, Object> noShow(@PathVariable String id) {
        return svc.markNoShow(id);
    }

    @PostMapping("/{id}/complete")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__RESERVE)
    public Map<String, Object> complete(@PathVariable String id) {
        return svc.complete(id);
    }
}
