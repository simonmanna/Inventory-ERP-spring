package com.inventoryerp.inventory.web;

import com.inventoryerp.inventory.application.WarehouseAbcService;
import com.inventoryerp.inventory.application.WarehouseOutboundService;
import com.inventoryerp.inventory.application.WarehouseOutboundService.LineInput;
import com.inventoryerp.inventory.application.WarehouseOutboundService.PackItem;
import com.inventoryerp.inventory.application.WarehouseOutboundService.PickOrderInput;
import com.inventoryerp.inventory.application.WarehouseSetupService;
import com.inventoryerp.inventory.application.WarehouseSetupService.BinInput;
import com.inventoryerp.inventory.application.WarehouseSetupService.RuleInput;
import com.inventoryerp.inventory.application.WarehouseSetupService.SettingsInput;
import com.inventoryerp.inventory.application.WarehouseTaskService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /warehouse}: warehouse management (roadmap 2.1, ADR-S026, extension) — settings, bins, putaway rules, bin
 * stock, putaway / move / pick tasks, pick orders (1–3 steps), packages, waves, ABC classes and cycle counts. Literal
 * segments are declared before {@code /{id}} and never collide with a UUID.
 */
@RestController
@RequestMapping("/warehouse")
public class WarehouseController {

    private static final String QTY_MSG = "quantity must be a positive number";
    private static final String DATE = "^\\d{4}-\\d{2}-\\d{2}$";

    public record SettingsRequest(
            Boolean enabled,
            @Min(value = 1, message = "outboundSteps must not be less than 1")
            @Max(value = 3, message = "outboundSteps must not be greater than 3") Integer outboundSteps,
            @DecimalMin(value = "1", message = "abcAPercent must not be less than 1")
            @DecimalMax(value = "99", message = "abcAPercent must not be greater than 99") BigDecimal abcAPercent,
            @DecimalMin(value = "1", message = "abcBPercent must not be less than 1")
            @DecimalMax(value = "99.99", message = "abcBPercent must not be greater than 99.99") BigDecimal abcBPercent,
            @Min(value = 1, message = "abcLookbackDays must not be less than 1") Integer abcLookbackDays,
            @Min(value = 1, message = "countDaysA must not be less than 1") Integer countDaysA,
            @Min(value = 1, message = "countDaysB must not be less than 1") Integer countDaysB,
            @Min(value = 1, message = "countDaysC must not be less than 1") Integer countDaysC) {}

    public record CreateBinRequest(
            @NotBlank(message = "locationId should not be empty") String locationId,
            String parentId,
            @NotBlank(message = "code should not be empty") @Size(max = 40, message = "code must be at most 40 characters")
            String code,
            String name,
            @OneOf({"zone", "aisle", "rack", "bin"}) String kind,
            @OneOf({"storage", "receiving", "output", "quarantine"}) String usage,
            Integer pickSequence,
            @DecimalMin(value = "0.000001", message = "maxQty must be a positive number") BigDecimal maxQty,
            Boolean mixedProducts,
            Boolean isActive) {}

    public record UpdateBinRequest(
            String parentId,
            @Size(min = 1, max = 40, message = "code must be between 1 and 40 characters") String code,
            String name,
            @OneOf({"zone", "aisle", "rack", "bin"}) String kind,
            @OneOf({"storage", "receiving", "output", "quarantine"}) String usage,
            Integer pickSequence,
            @DecimalMin(value = "0.000001", message = "maxQty must be a positive number") BigDecimal maxQty,
            Boolean mixedProducts,
            Boolean isActive) {}

    public record CreateRuleRequest(
            @NotBlank(message = "locationId should not be empty") String locationId,
            String productId,
            String categoryId,
            String targetBinId,
            String targetZoneId,
            Integer priority,
            Boolean isActive) {}

    public record UpdateRuleRequest(String productId, String categoryId, String targetBinId, String targetZoneId,
            Integer priority, Boolean isActive) {}

    public record GeneratePutawayRequest(
            @NotBlank(message = "locationId should not be empty") String locationId,
            String productId) {}

    public record CreateTaskRequest(
            @NotBlank(message = "locationId should not be empty") String locationId,
            @NotNull(message = "type must be one of the following values: putaway, move")
            @OneOf({"putaway", "move"}) String type,
            @NotBlank(message = "productId should not be empty") String productId,
            String variantId,
            String batchId,
            @NotNull(message = QTY_MSG) @DecimalMin(value = "0.000001", message = QTY_MSG) BigDecimal quantity,
            String fromBinId,
            String toBinId) {}

    public record CompleteTaskRequest(
            @DecimalMin(value = "0", message = "quantity must not be less than 0") BigDecimal quantity,
            String toBinId) {}

    public record PickLineRequest(
            @NotBlank(message = "productId should not be empty") String productId,
            String variantId,
            @NotNull(message = QTY_MSG) @DecimalMin(value = "0.000001", message = QTY_MSG) BigDecimal quantity) {}

    public record CreatePickOrderRequest(
            @NotBlank(message = "locationId should not be empty") String locationId,
            @OneOf({"manual", "sales_order", "delivery_order", "transfer", "stock_out"}) String sourceType,
            String sourceId,
            String sourceRef,
            Integer priority,
            @Pattern(regexp = DATE, message = "scheduledDate must be a valid ISO 8601 date string") String scheduledDate,
            String notes,
            @Min(value = 1, message = "steps must not be less than 1")
            @Max(value = 3, message = "steps must not be greater than 3") Integer steps,
            @NotEmpty(message = "lines should not be empty") List<@Valid PickLineRequest> lines) {}

    public record PackItemRequest(
            @NotBlank(message = "lineId should not be empty") String lineId,
            @NotNull(message = QTY_MSG) @DecimalMin(value = "0.000001", message = QTY_MSG) BigDecimal quantity) {}

    public record PackRequest(
            String packageType,
            @DecimalMin(value = "0", message = "weightKg must not be less than 0") BigDecimal weightKg,
            List<@Valid PackItemRequest> items) {}

    public record ShipRequest(String carrier, String trackingRef) {}

    public record CancelPickOrderRequest(String returnBinId) {}

    public record CreateWaveRequest(
            @NotBlank(message = "locationId should not be empty") String locationId,
            @NotEmpty(message = "pickOrderIds should not be empty") List<String> pickOrderIds,
            String notes) {}

    public record CycleCountRequest(
            List<@OneOf({"A", "B", "C"}) String> classes,
            @Min(value = 1, message = "limit must not be less than 1") Integer limit) {}

    private final WarehouseSetupService setup;
    private final WarehouseTaskService tasks;
    private final WarehouseOutboundService outbound;
    private final WarehouseAbcService abc;

    public WarehouseController(WarehouseSetupService setup, WarehouseTaskService tasks,
            WarehouseOutboundService outbound, WarehouseAbcService abc) {
        this.setup = setup;
        this.tasks = tasks;
        this.outbound = outbound;
        this.abc = abc;
    }

    // ---------------------------------------------------------------- locations: settings, overview, ABC

    @GetMapping("/locations/{locationId}/settings")
    @RequirePermissions(Permissions.WAREHOUSE__READ)
    public Map<String, Object> settings(@PathVariable String locationId) {
        return setup.settings(locationId);
    }

    @PutMapping("/locations/{locationId}/settings")
    @RequirePermissions(Permissions.WAREHOUSE__MANAGE)
    public Map<String, Object> saveSettings(@PathVariable String locationId, @Valid @RequestBody SettingsRequest dto) {
        return setup.saveSettings(locationId, new SettingsInput(dto.enabled(), dto.outboundSteps(), dto.abcAPercent(),
                dto.abcBPercent(), dto.abcLookbackDays(), dto.countDaysA(), dto.countDaysB(), dto.countDaysC()));
    }

    @GetMapping("/locations/{locationId}/overview")
    @RequirePermissions(Permissions.WAREHOUSE__READ)
    public Map<String, Object> overview(@PathVariable String locationId) {
        return setup.overview(locationId);
    }

    @GetMapping("/locations/{locationId}/abc")
    @RequirePermissions(Permissions.WAREHOUSE__READ)
    public Map<String, Object> abc(@PathVariable String locationId) {
        return abc.list(locationId);
    }

    @PostMapping("/locations/{locationId}/abc/classify")
    @RequirePermissions(Permissions.WAREHOUSE__MANAGE)
    public Map<String, Object> classify(@PathVariable String locationId) {
        return abc.classify(locationId);
    }

    @PostMapping("/locations/{locationId}/cycle-counts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions({Permissions.WAREHOUSE__OPERATE, Permissions.INVENTORY_COUNT__COUNT})
    public Map<String, Object> cycleCount(@PathVariable String locationId,
            @Valid @RequestBody(required = false) CycleCountRequest dto) {
        return abc.generateCycleCount(locationId, dto == null ? null : dto.classes(), dto == null ? null : dto.limit());
    }

    // ---------------------------------------------------------------- bins

    @GetMapping("/bins")
    @RequirePermissions(Permissions.WAREHOUSE__READ)
    public List<Map<String, Object>> bins(@RequestParam String locationId,
            @RequestParam(required = false) Boolean includeInactive) {
        return setup.bins(locationId, Boolean.TRUE.equals(includeInactive));
    }

    @PostMapping("/bins")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WAREHOUSE__MANAGE)
    public Map<String, Object> createBin(@Valid @RequestBody CreateBinRequest dto) {
        return setup.createBin(new BinInput(dto.locationId(), dto.parentId(), dto.code(), dto.name(), dto.kind(),
                dto.usage(), dto.pickSequence(), dto.maxQty(), dto.mixedProducts(), dto.isActive()));
    }

    @GetMapping("/bins/{id}")
    @RequirePermissions(Permissions.WAREHOUSE__READ)
    public Map<String, Object> bin(@PathVariable String id) {
        return setup.bin(id);
    }

    @PatchMapping("/bins/{id}")
    @RequirePermissions(Permissions.WAREHOUSE__MANAGE)
    public Map<String, Object> updateBin(@PathVariable String id, @Valid @RequestBody UpdateBinRequest dto) {
        return setup.updateBin(id, new BinInput(null, dto.parentId(), dto.code(), dto.name(), dto.kind(), dto.usage(),
                dto.pickSequence(), dto.maxQty(), dto.mixedProducts(), dto.isActive()));
    }

    @DeleteMapping("/bins/{id}")
    @RequirePermissions(Permissions.WAREHOUSE__MANAGE)
    public Map<String, Object> deleteBin(@PathVariable String id) {
        return setup.deleteBin(id);
    }

    @GetMapping("/bin-stock")
    @RequirePermissions(Permissions.WAREHOUSE__READ)
    public List<Map<String, Object>> binStock(@RequestParam String locationId,
            @RequestParam(required = false) String binId, @RequestParam(required = false) String productId) {
        return setup.binStock(locationId, binId, productId);
    }

    // ---------------------------------------------------------------- putaway

    @GetMapping("/putaway-rules")
    @RequirePermissions(Permissions.WAREHOUSE__READ)
    public List<Map<String, Object>> rules(@RequestParam String locationId) {
        return setup.rules(locationId);
    }

    @PostMapping("/putaway-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WAREHOUSE__MANAGE)
    public Map<String, Object> createRule(@Valid @RequestBody CreateRuleRequest dto) {
        return setup.createRule(new RuleInput(dto.locationId(), dto.productId(), dto.categoryId(), dto.targetBinId(),
                dto.targetZoneId(), dto.priority(), dto.isActive()));
    }

    @PatchMapping("/putaway-rules/{id}")
    @RequirePermissions(Permissions.WAREHOUSE__MANAGE)
    public Map<String, Object> updateRule(@PathVariable String id, @Valid @RequestBody UpdateRuleRequest dto) {
        return setup.updateRule(id, new RuleInput(null, dto.productId(), dto.categoryId(), dto.targetBinId(),
                dto.targetZoneId(), dto.priority(), dto.isActive()));
    }

    @DeleteMapping("/putaway-rules/{id}")
    @RequirePermissions(Permissions.WAREHOUSE__MANAGE)
    public Map<String, Object> deleteRule(@PathVariable String id) {
        return setup.deleteRule(id);
    }

    @GetMapping("/putaway/suggest")
    @RequirePermissions(Permissions.WAREHOUSE__READ)
    public Map<String, Object> suggest(@RequestParam String locationId, @RequestParam String productId,
            @RequestParam(required = false) String variantId, @RequestParam BigDecimal quantity) {
        return tasks.suggest(locationId, productId, variantId, quantity);
    }

    @PostMapping("/putaway/generate")
    @RequirePermissions(Permissions.WAREHOUSE__OPERATE)
    public Map<String, Object> generatePutaway(@Valid @RequestBody GeneratePutawayRequest dto) {
        return tasks.generatePutaway(dto.locationId(), dto.productId());
    }

    // ---------------------------------------------------------------- tasks

    @GetMapping("/tasks")
    @RequirePermissions(Permissions.WAREHOUSE__READ)
    public List<Map<String, Object>> tasks(@RequestParam String locationId,
            @RequestParam(required = false) String type, @RequestParam(required = false) String status,
            @RequestParam(required = false) String waveId) {
        return tasks.tasks(locationId, type, status, waveId);
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WAREHOUSE__OPERATE)
    public Map<String, Object> createTask(@Valid @RequestBody CreateTaskRequest dto) {
        return tasks.create(dto.locationId(), dto.type(), dto.productId(), dto.variantId(), dto.batchId(),
                dto.quantity(), dto.fromBinId(), dto.toBinId());
    }

    @PostMapping("/tasks/{id}/complete")
    @RequirePermissions(Permissions.WAREHOUSE__OPERATE)
    public Map<String, Object> completeTask(@PathVariable String id,
            @Valid @RequestBody(required = false) CompleteTaskRequest dto) {
        return tasks.complete(id, dto == null ? null : dto.quantity(), dto == null ? null : dto.toBinId());
    }

    @PostMapping("/tasks/{id}/cancel")
    @RequirePermissions(Permissions.WAREHOUSE__OPERATE)
    public Map<String, Object> cancelTask(@PathVariable String id) {
        return tasks.cancel(id);
    }

    // ---------------------------------------------------------------- pick orders

    @GetMapping("/pick-orders")
    @RequirePermissions(Permissions.WAREHOUSE__READ)
    public List<Map<String, Object>> pickOrders(@RequestParam String locationId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String waveId) {
        return outbound.list(locationId, status, waveId);
    }

    @PostMapping("/pick-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WAREHOUSE__OPERATE)
    public Map<String, Object> createPickOrder(@Valid @RequestBody CreatePickOrderRequest dto) {
        return outbound.create(new PickOrderInput(dto.locationId(), dto.sourceType(), dto.sourceId(), dto.sourceRef(),
                dto.priority(), dto.scheduledDate() == null ? null : LocalDate.parse(dto.scheduledDate()), dto.notes(),
                dto.steps(), dto.lines().stream().map(l -> new LineInput(l.productId(), l.variantId(), l.quantity()))
                        .toList()));
    }

    @GetMapping("/pick-orders/{id}")
    @RequirePermissions(Permissions.WAREHOUSE__READ)
    public Map<String, Object> pickOrder(@PathVariable String id) {
        return outbound.get(id);
    }

    @PostMapping("/pick-orders/{id}/release")
    @RequirePermissions(Permissions.WAREHOUSE__OPERATE)
    public Map<String, Object> release(@PathVariable String id) {
        return outbound.release(id);
    }

    @PostMapping("/pick-orders/{id}/pack")
    @RequirePermissions(Permissions.WAREHOUSE__OPERATE)
    public Map<String, Object> pack(@PathVariable String id, @Valid @RequestBody(required = false) PackRequest dto) {
        return outbound.pack(id, dto == null ? null : dto.packageType(), dto == null ? null : dto.weightKg(),
                dto == null || dto.items() == null ? null
                        : dto.items().stream().map(i -> new PackItem(i.lineId(), i.quantity())).toList());
    }

    @PostMapping("/pick-orders/{id}/ship")
    @RequirePermissions(Permissions.WAREHOUSE__OPERATE)
    public Map<String, Object> ship(@PathVariable String id, @Valid @RequestBody(required = false) ShipRequest dto) {
        return outbound.ship(id, dto == null ? null : dto.carrier(), dto == null ? null : dto.trackingRef());
    }

    @PostMapping("/pick-orders/{id}/cancel")
    @RequirePermissions(Permissions.WAREHOUSE__OPERATE)
    public Map<String, Object> cancel(@PathVariable String id,
            @Valid @RequestBody(required = false) CancelPickOrderRequest dto) {
        return outbound.cancel(id, dto == null ? null : dto.returnBinId());
    }

    // ---------------------------------------------------------------- waves

    @GetMapping("/waves")
    @RequirePermissions(Permissions.WAREHOUSE__READ)
    public List<Map<String, Object>> waves(@RequestParam String locationId) {
        return outbound.waves(locationId);
    }

    @PostMapping("/waves")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WAREHOUSE__OPERATE)
    public Map<String, Object> createWave(@Valid @RequestBody CreateWaveRequest dto) {
        return outbound.createWave(dto.locationId(), dto.pickOrderIds(), dto.notes());
    }

    @GetMapping("/waves/{id}")
    @RequirePermissions(Permissions.WAREHOUSE__READ)
    public Map<String, Object> wave(@PathVariable String id) {
        return outbound.getWave(id);
    }

    @PostMapping("/waves/{id}/release")
    @RequirePermissions(Permissions.WAREHOUSE__OPERATE)
    public Map<String, Object> releaseWave(@PathVariable String id) {
        return outbound.releaseWave(id);
    }
}
