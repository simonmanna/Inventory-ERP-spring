package com.inventoryerp.fleet.web.dispatch;

import com.inventoryerp.fleet.application.DispatchCommands.AssignInput;
import com.inventoryerp.fleet.application.DispatchCommands.CloseTripInput;
import com.inventoryerp.fleet.application.DispatchCommands.LoadedQuantityInput;
import com.inventoryerp.fleet.application.DispatchCommands.MeterInput;
import com.inventoryerp.fleet.application.DispatchCommands.PodInput;
import com.inventoryerp.fleet.application.DispatchCommands.ReasonInput;
import com.inventoryerp.fleet.application.DispatchCommands.ReturnInput;
import com.inventoryerp.fleet.application.DispatchCommands.TripPatch;
import com.inventoryerp.fleet.application.DispatchCommands.TripQuery;
import com.inventoryerp.fleet.application.FleetDeliveryNoteService;
import com.inventoryerp.fleet.application.FleetDispatchService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Trips / loads (fleet 1.3): list (dispatcher, or {@code mine=true} for the driver's phone), detail, assignment (own
 * truck + driver + trailer or transporter) with an eligibility check, the lifecycle (loading → weighed → dispatched →
 * in transit → delivered → closed; cancelled / returned), proof of delivery with signature and photo, and the
 * delivery note PDF. Dispatch, POD and return move stock: Idempotency-Key required.
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetTripController {

    private final FleetDispatchService trips;
    private final FleetDeliveryNoteService notes;

    public FleetTripController(FleetDispatchService trips, FleetDeliveryNoteService notes) {
        this.trips = trips;
        this.notes = notes;
    }

    /** {@code status}: a status, {@code open}, {@code on_road} or {@code active}. */
    @GetMapping("/trips")
    @RequirePermissions(Permissions.FLEET_TRIP__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"open", "on_road", "active", "planned", "loading", "weighed",
                "dispatched", "in_transit", "delivered", "closed", "cancelled", "returned"}) String status,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String driverId,
            @RequestParam(required = false) String deliveryOrderId,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) Boolean mine,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return trips.list(new TripQuery(search, status, dateFrom, dateTo, vehicleId, driverId, deliveryOrderId,
                partnerId, mine, page, pageSize));
    }

    @GetMapping("/trips/{id}")
    @RequirePermissions(Permissions.FLEET_TRIP__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return trips.get(id);
    }

    @PatchMapping("/trips/{id}")
    @RequirePermissions(Permissions.FLEET_TRIP__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody TripPatch dto) {
        return trips.update(id, dto);
    }

    @PostMapping("/trips/{id}/assign")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DISPATCH__PLAN)
    public Map<String, Object> assign(@PathVariable String id, @Valid @RequestBody AssignInput dto) {
        return trips.assign(id, dto);
    }

    /** Would this truck / trailer / driver be allowed? {@code {eligible, reasons, warnings, capacityT, …}}. */
    @GetMapping("/trips/{id}/assignment-check")
    @RequirePermissions(Permissions.FLEET_TRIP__READ)
    public Map<String, Object> assignmentCheck(@PathVariable String id,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String trailerId,
            @RequestParam(required = false) String driverId) {
        return trips.assignmentCheck(id, vehicleId, trailerId, driverId);
    }

    @PostMapping("/trips/{id}/start-loading")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_TRIP__DISPATCH)
    public Map<String, Object> startLoading(@PathVariable String id, @Valid @RequestBody(required = false) MeterInput dto) {
        return trips.startLoading(id, dto == null ? null : dto.odometer());
    }

    /** Manual loaded quantity (no weighbridge ticket): the trip becomes weighed. */
    @PostMapping("/trips/{id}/loaded-quantity")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_TRIP__DISPATCH)
    public Map<String, Object> loadedQuantity(@PathVariable String id, @Valid @RequestBody LoadedQuantityInput dto) {
        return trips.recordLoadedQuantity(id, dto.lines());
    }

    /** Issue the goods from the yard (exactly once) and number the delivery note. */
    @PostMapping("/trips/{id}/dispatch")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_TRIP__DISPATCH)
    public Map<String, Object> dispatch(@PathVariable String id, @Valid @RequestBody(required = false) MeterInput dto) {
        return trips.dispatch(id, dto == null ? null : dto.odometer());
    }

    @PostMapping("/trips/{id}/depart")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_TRIP__DISPATCH)
    public Map<String, Object> depart(@PathVariable String id) {
        return trips.depart(id);
    }

    @PostMapping("/trips/{id}/proof-of-delivery")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_TRIP__DELIVER)
    public Map<String, Object> proofOfDelivery(@PathVariable String id, @Valid @RequestBody PodInput dto) {
        return trips.proofOfDelivery(id, dto);
    }

    /** Multipart {@code file}: photo of the delivered goods or the signed paper note. */
    @PostMapping("/trips/{id}/pod-photo")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_TRIP__DELIVER)
    public Map<String, Object> podPhoto(@PathVariable String id,
            @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        if (file == null) {
            throw ApiException.badRequest("Missing file");
        }
        return trips.podPhoto(id, file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    @PostMapping("/trips/{id}/close")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_TRIP__CLOSE)
    public Map<String, Object> close(@PathVariable String id, @Valid @RequestBody(required = false) CloseTripInput dto) {
        return trips.close(id, dto);
    }

    @PostMapping("/trips/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_TRIP__CANCEL)
    public Map<String, Object> cancel(@PathVariable String id, @Valid @RequestBody ReasonInput dto) {
        return trips.cancel(id, dto.reason());
    }

    /** The whole load came back: stock back into the yard, a returned discrepancy per line. */
    @PostMapping("/trips/{id}/return")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_TRIP__CLOSE)
    public Map<String, Object> returnLoad(@PathVariable String id, @Valid @RequestBody ReturnInput dto) {
        return trips.returnLoad(id, dto);
    }

    @GetMapping("/trips/{id}/delivery-note")
    @RequirePermissions(Permissions.FLEET_TRIP__READ)
    public ResponseEntity<byte[]> deliveryNote(@PathVariable String id) {
        FleetDeliveryNoteService.Pdf pdf = notes.render(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pdf.filename().replace("\"", "")
                        + "\"")
                .body(pdf.content());
    }
}
