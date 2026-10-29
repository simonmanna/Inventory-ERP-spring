package com.inventoryerp.fleet.web;

import com.inventoryerp.fleet.application.DriverCommands.AssignmentEnd;
import com.inventoryerp.fleet.application.DriverCommands.AssignmentInput;
import com.inventoryerp.fleet.application.DriverCommands.CheckInInput;
import com.inventoryerp.fleet.application.DriverCommands.CheckOutInput;
import com.inventoryerp.fleet.application.DriverCommands.HistoryQuery;
import com.inventoryerp.fleet.application.FleetAssignmentService;
import com.inventoryerp.fleet.application.FleetCheckoutService;
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
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
 * Vehicle ↔ driver assignments and vehicle check-out / check-in with photos (fleet 1.2, ADR-S009). Assignment and
 * check-out are refused with the eligibility reason when the licence / class / vehicle status does not allow them.
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetCheckoutController {

    private final FleetAssignmentService assignments;
    private final FleetCheckoutService checkouts;

    public FleetCheckoutController(FleetAssignmentService assignments, FleetCheckoutService checkouts) {
        this.assignments = assignments;
        this.checkouts = checkouts;
    }

    @GetMapping("/assignments")
    @RequirePermissions(Permissions.FLEET_DRIVER__READ)
    public PageResult<Map<String, Object>> assignments(
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String driverId,
            @RequestParam(required = false) @OneOf({"active", "ended"}) String status,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return assignments.list(new HistoryQuery(vehicleId, driverId, status, page, pageSize));
    }

    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DRIVER__ASSIGN)
    public Map<String, Object> assign(@Valid @RequestBody AssignmentInput dto) {
        return assignments.assign(dto);
    }

    @PostMapping("/assignments/{id}/end")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DRIVER__ASSIGN)
    public Map<String, Object> end(@PathVariable String id, @Valid @RequestBody(required = false) AssignmentEnd dto) {
        return assignments.end(id, dto);
    }

    @GetMapping("/checkouts")
    @RequirePermissions(Permissions.FLEET_DRIVER__READ)
    public PageResult<Map<String, Object>> checkouts(
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String driverId,
            @RequestParam(required = false) @OneOf({"open", "closed"}) String status,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return checkouts.list(new HistoryQuery(vehicleId, driverId, status, page, pageSize));
    }

    @GetMapping("/checkouts/{id}")
    @RequirePermissions(Permissions.FLEET_DRIVER__READ)
    public Map<String, Object> checkout(@PathVariable String id) {
        return checkouts.get(id);
    }

    /** The vehicle's open check-out ({@code {checkout: null}} when it is in the yard). */
    @GetMapping("/vehicles/{id}/open-checkout")
    @RequirePermissions(Permissions.FLEET_DRIVER__READ)
    public Map<String, Object> openCheckout(@PathVariable String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkout", checkouts.openForVehicle(id));
        return out;
    }

    @PostMapping("/vehicles/{id}/check-out")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DRIVER__CHECKOUT)
    public Map<String, Object> checkOut(@PathVariable String id, @Valid @RequestBody CheckOutInput dto) {
        return checkouts.checkOut(id, dto);
    }

    @PostMapping("/checkouts/{id}/check-in")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DRIVER__CHECKOUT)
    public Map<String, Object> checkIn(@PathVariable String id, @Valid @RequestBody CheckInInput dto) {
        return checkouts.checkIn(id, dto);
    }

    /** Multipart: {@code file} (image) and optional {@code phase} (out / in; default from the check-out status). */
    @PostMapping("/checkouts/{id}/photos")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DRIVER__CHECKOUT)
    public Map<String, Object> photo(@PathVariable String id,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "phase", required = false) String phase) throws IOException {
        if (file == null) {
            throw ApiException.badRequest("Missing file");
        }
        return checkouts.addPhoto(id, phase, file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }
}
