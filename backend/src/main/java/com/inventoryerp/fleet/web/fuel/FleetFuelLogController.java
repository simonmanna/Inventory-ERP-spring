package com.inventoryerp.fleet.web.fuel;

import com.inventoryerp.fleet.application.FleetFuelLogService;
import com.inventoryerp.fleet.application.FleetFuelLookupService;
import com.inventoryerp.fleet.application.FuelCommands.AcknowledgeInput;
import com.inventoryerp.fleet.application.FuelCommands.FuelLogInput;
import com.inventoryerp.fleet.application.FuelCommands.FuelLogPatch;
import com.inventoryerp.fleet.application.FuelCommands.FuelLogQuery;
import com.inventoryerp.fleet.application.FuelCommands.ReverseInput;
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
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Fuel logs (fleet 1.5, ADR-S009): list with filters, detail, quick entry (draft or posted at once), edit / delete of
 * drafts, post and reverse (Idempotency-Key required — they move stock and money), receipts, the anomaly review queue,
 * a vehicle's fuel summary and efficiency trend, and the fuel form lookups / overview.
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetFuelLogController {

    private final FleetFuelLogService logs;
    private final FleetFuelLookupService lookups;

    public FleetFuelLogController(FleetFuelLogService logs, FleetFuelLookupService lookups) {
        this.logs = logs;
        this.lookups = lookups;
    }

    @GetMapping("/fuel/overview")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public Map<String, Object> overview() {
        return lookups.overview();
    }

    @GetMapping("/fuel/lookups")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public Map<String, Object> lookups() {
        return lookups.lookups();
    }

    @GetMapping("/fuel/lookups/employees")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public List<Map<String, Object>> employees(@RequestParam(required = false) String search) {
        return lookups.employees(search);
    }

    /** {@code {data, meta, summary}}; {@code anomaly} = any / open / none / an anomaly type. */
    @GetMapping("/fuel-logs")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public Map<String, Object> list(@RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) @OneOf({"station", "tank"}) String source,
            @RequestParam(required = false) @OneOf({"draft", "posted", "reversed"}) String status,
            @RequestParam(required = false) String anomaly,
            @RequestParam(required = false) String tankId,
            @RequestParam(required = false) String fuelCardId,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize,
            @RequestParam(required = false) @OneOf({"fuelledAt", "code", "litres", "amount", "vehicle"}) String sortBy,
            @RequestParam(required = false) @OneOf({"asc", "desc"}) String sortOrder) {
        return logs.list(new FuelLogQuery(vehicleId, source, status, anomaly, tankId, fuelCardId, dateFrom, dateTo,
                search, page, pageSize, sortBy, sortOrder));
    }

    @GetMapping("/fuel-logs/{id}")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return logs.get(id);
    }

    /** Quick entry; {@code post: true} also posts (stock / GL), hence the required Idempotency-Key. */
    @PostMapping("/fuel-logs")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_FUEL__CREATE)
    public Map<String, Object> create(@Valid @RequestBody FuelLogInput dto) {
        return logs.create(dto);
    }

    @PatchMapping("/fuel-logs/{id}")
    @RequirePermissions(Permissions.FLEET_FUEL__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody FuelLogPatch dto) {
        return logs.update(id, dto);
    }

    @DeleteMapping("/fuel-logs/{id}")
    @RequirePermissions(Permissions.FLEET_FUEL__UPDATE)
    public Map<String, Object> delete(@PathVariable String id) {
        logs.delete(id);
        return Map.of("id", id, "deleted", true);
    }

    @PostMapping("/fuel-logs/{id}/post")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_FUEL__POST)
    public Map<String, Object> post(@PathVariable String id) {
        return logs.post(id);
    }

    @PostMapping("/fuel-logs/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_FUEL__POST)
    public Map<String, Object> reverse(@PathVariable String id, @Valid @RequestBody ReverseInput dto) {
        return logs.reverse(id, dto.reason());
    }

    /** Multipart {@code file}: receipt photo (camera) or scan. */
    @PostMapping("/fuel-logs/{id}/receipt")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_FUEL__CREATE)
    public Map<String, Object> receipt(@PathVariable String id,
            @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        if (file == null) {
            throw ApiException.badRequest("Missing file");
        }
        return logs.attachReceipt(id, file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    /** Anomaly review queue: {@code status} open (default) / acknowledged / resolved / all. */
    @GetMapping("/fuel-anomalies")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public PageResult<Map<String, Object>> anomalies(
            @RequestParam(required = false) @OneOf({"open", "acknowledged", "resolved", "all"}) String status,
            @RequestParam(required = false) @OneOf({"overfill", "meter_not_increasing", "high_consumption",
                "low_consumption", "card_vehicle_mismatch", "card_limit_exceeded"}) String type,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return logs.anomalies(status, type, vehicleId, page, pageSize);
    }

    @PostMapping("/fuel-anomalies/{id}/acknowledge")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_FUEL__UPDATE)
    public Map<String, Object> acknowledge(@PathVariable String id, @Valid @RequestBody AcknowledgeInput dto) {
        return logs.acknowledge(id, dto.note());
    }

    /** Fuel tab of a vehicle: totals, average efficiency, class band, efficiency trend, recent logs. */
    @GetMapping("/vehicles/{id}/fuel")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public Map<String, Object> vehicleFuel(@PathVariable String id) {
        return logs.vehicleFuel(id);
    }
}
