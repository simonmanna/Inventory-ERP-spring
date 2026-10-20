package com.inventoryerp.inventory.web;

import static com.inventoryerp.inventory.web.InventoryDtos.COUNT_TYPES;

import com.inventoryerp.inventory.application.InventoryCountService;
import com.inventoryerp.inventory.application.InventoryCountService.DraftLine;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
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

/** {@code /inventory/counts} guided physical counts (port of {@code inventory-count.controller.ts}). */
@RestController
@RequestMapping("/inventory/counts")
public class InventoryCountController {

    /** {@code StartCountDto}. */
    public record StartCountRequest(
            @NotEmpty(message = "should not be empty") String locationId,
            @OneOf({"opening", "closing", "cycle", "spot"}) String countType,
            String notes,
            Boolean blind,
            List<String> scopeCategoryIds,
            List<String> scopeProductIds,
            Boolean restart) {}

    /** {@code SaveCountLineDto}. */
    public record SaveCountLine(
            @NotEmpty(message = "should not be empty") String lineId,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal countedQty,
            String reason) {}

    /** {@code SaveCountDraftDto}. */
    public record SaveCountDraftRequest(
            @NotNull(message = "must be an array") List<@Valid SaveCountLine> lines,
            String name,
            String notes) {}

    /** {@code SubmitCountDto}. */
    public record SubmitCountRequest(Boolean force, String forceReason) {}

    private final InventoryCountService counts;

    public InventoryCountController(InventoryCountService counts) {
        this.counts = counts;
    }

    @GetMapping
    @RequirePermissions(Permissions.INVENTORY_COUNT__READ)
    public List<Map<String, Object>> list() {
        return counts.list();
    }

    @GetMapping("/preview")
    @RequirePermissions(Permissions.INVENTORY_COUNT__READ)
    public Map<String, Object> preview(@RequestParam Map<String, String> query) {
        new InventoryDtos.Check(query, "locationId", "countType", "scopeCategoryIds", "scopeProductIds")
                .required("locationId").oneOf("countType", COUNT_TYPES).done();
        return counts.preview(query.get("locationId"), query.get("countType"),
                InventoryDtos.idList(query.get("scopeCategoryIds")), InventoryDtos.idList(query.get("scopeProductIds")));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.INVENTORY_COUNT__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return counts.get(id, false);
    }

    @GetMapping("/{id}/review")
    @RequirePermissions(Permissions.INVENTORY_COUNT__SUBMIT)
    public Map<String, Object> review(@PathVariable String id) {
        return counts.get(id, true);
    }

    @PostMapping("/start")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INVENTORY_COUNT__COUNT)
    public Map<String, Object> start(@Valid @RequestBody StartCountRequest dto) {
        return counts.start(dto.locationId(), dto.countType(), dto.notes(), dto.blind(), dto.scopeCategoryIds(),
                dto.scopeProductIds(), dto.restart());
    }

    @PatchMapping("/{id}/draft")
    @RequirePermissions(Permissions.INVENTORY_COUNT__COUNT)
    public Map<String, Object> saveDraft(@PathVariable String id, @Valid @RequestBody SaveCountDraftRequest dto) {
        return counts.saveDraft(id, dto.lines().stream().map(l -> new DraftLine(l.lineId(), l.countedQty(), l.reason()))
                .toList(), dto.name(), dto.notes());
    }

    @PostMapping("/{id}/submit")
    @Idempotent
    @RequirePermissions(Permissions.INVENTORY_COUNT__SUBMIT)
    public Map<String, Object> submit(@PathVariable String id,
            @Valid @RequestBody(required = false) SubmitCountRequest dto) {
        return counts.submit(id, dto == null ? null : dto.force(), dto == null ? null : dto.forceReason());
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INVENTORY_COUNT__COUNT)
    public Map<String, Object> cancel(@PathVariable String id) {
        return counts.cancel(id);
    }
}
