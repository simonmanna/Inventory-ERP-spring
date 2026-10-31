package com.inventoryerp.fleet.web.driver;

import com.inventoryerp.fleet.application.DriverAppCommands.DriverFuelInput;
import com.inventoryerp.fleet.application.DriverAppCommands.DriverInspectionInput;
import com.inventoryerp.fleet.application.FleetDriverAppService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.ApiException;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
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
 * Driver web app (DRIVER-1): home, fuel log and pre-trip check for the signed-in driver, on the driver's own units.
 * Trips and proof of delivery are the fleet 1.3 routes ({@code mine=true}). {@code fleet_trip:deliver} is the driver's
 * permission; the service scopes every call to the driver.
 */
@RestController
@RequestMapping("/fleet/driver-app")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetDriverAppController {

    private final FleetDriverAppService app;

    public FleetDriverAppController(FleetDriverAppService app) {
        this.app = app;
    }

    @GetMapping("/home")
    @RequirePermissions(Permissions.FLEET_TRIP__DELIVER)
    public Map<String, Object> home() {
        return app.home();
    }

    @GetMapping("/fuel-options")
    @RequirePermissions(Permissions.FLEET_TRIP__DELIVER)
    public Map<String, Object> fuelOptions(@RequestParam String vehicleId) {
        return app.fuelOptions(vehicleId);
    }

    @GetMapping("/fuel-logs")
    @RequirePermissions(Permissions.FLEET_TRIP__DELIVER)
    public List<Map<String, Object>> fuelLogs() {
        return app.myFuelLogs();
    }

    /** Idempotent: a phone on a bad network retries with the same key. */
    @PostMapping("/fuel-logs")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_TRIP__DELIVER)
    public Map<String, Object> createFuelLog(@Valid @RequestBody DriverFuelInput body) {
        return app.createFuelLog(body);
    }

    /** Multipart {@code file}: receipt photo. */
    @PostMapping("/fuel-logs/{id}/receipt")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_TRIP__DELIVER)
    public Map<String, Object> receipt(@PathVariable String id,
            @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        if (file == null) {
            throw ApiException.badRequest("Missing file");
        }
        return app.addFuelReceipt(id, file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    @GetMapping("/pre-trip")
    @RequirePermissions(Permissions.FLEET_TRIP__DELIVER)
    public Map<String, Object> preTrip(@RequestParam String vehicleId) {
        return app.preTrip(vehicleId);
    }

    /** Idempotent for the same reason as fuel logs. */
    @PostMapping("/inspections")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_TRIP__DELIVER)
    public Map<String, Object> inspection(@Valid @RequestBody DriverInspectionInput body) {
        return app.recordInspection(body);
    }

    /** Multipart {@code file}, optional {@code itemCode}. */
    @PostMapping("/inspections/{id}/photos")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_TRIP__DELIVER)
    public Map<String, Object> photo(@PathVariable String id,
            @RequestParam(required = false) String itemCode,
            @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        if (file == null) {
            throw ApiException.badRequest("Missing file");
        }
        return app.addInspectionPhoto(id, itemCode, file.getOriginalFilename(), file.getContentType(),
                file.getBytes());
    }
}
