package com.inventoryerp.beverage.web;

import com.inventoryerp.beverage.application.BeverageCountService;
import com.inventoryerp.beverage.application.BeverageCountService.Approval;
import com.inventoryerp.beverage.application.BeverageCountService.DraftLine;
import com.inventoryerp.beverage.application.BeverageCountService.Reading;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /beverage/counts} digital-weight bottle counts (port of {@code beverage-count.controller.ts}). */
@RestController
@RequestMapping("/beverage/counts")
@RequiresModule("beverage")
@ConditionalOnProperty(name = "app.modules.beverage", havingValue = "true")
public class BeverageCountController {

    /** {@code StartBottleCountDto}. */
    public record StartBottleCountRequest(
            String locationId,
            @OneOf({"opening", "closing"}) String countType,
            String notes) {}

    /** {@code BottleReadingDto}. */
    public record BottleReadingRequest(
            @NotNull(message = "must be a number conforming to the specified constraints") Double measuredWeightG,
            String bottleNumber,
            @OneOf({"MANUAL", "SCALE", "BLUETOOTH", "USB", "IMPORT", "API"}) String measurementSource) {}

    /** {@code SaveBottleCountLineDto}. */
    public record SaveBottleCountLineRequest(
            @NotNull(message = "must be a string") String lineId,
            Double sealedFullCount,
            List<@Valid BottleReadingRequest> readings,
            String reason) {}

    /** {@code SaveBottleCountDraftDto}. */
    public record SaveBottleCountDraftRequest(
            String name,
            String notes,
            @NotNull(message = "must be an array") List<@Valid SaveBottleCountLineRequest> lines) {}

    /** {@code SubmitBottleCountDto}. */
    public record SubmitBottleCountRequest(String approverId, String approverEmail, String managerPin) {}

    private final BeverageCountService counts;

    public BeverageCountController(BeverageCountService counts) {
        this.counts = counts;
    }

    @GetMapping
    @RequirePermissions(Permissions.BEVERAGE__READ)
    public List<Map<String, Object>> list() {
        return counts.list();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.BEVERAGE__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return counts.get(id);
    }

    @PostMapping("/start")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BEVERAGE__COUNT)
    public Map<String, Object> start(@Valid @RequestBody(required = false) StartBottleCountRequest dto) {
        StartBottleCountRequest d = dto == null ? new StartBottleCountRequest(null, null, null) : dto;
        return counts.start(d.locationId(), d.countType(), d.notes());
    }

    @PatchMapping("/{id}/draft")
    @RequirePermissions(Permissions.BEVERAGE__COUNT)
    public Map<String, Object> saveDraft(@PathVariable String id, @Valid @RequestBody SaveBottleCountDraftRequest dto) {
        List<DraftLine> lines = dto.lines().stream().map(l -> new DraftLine(l.lineId(), l.sealedFullCount(),
                l.readings() == null ? null : l.readings().stream()
                        .map(r -> new Reading(r.measuredWeightG(), r.bottleNumber(), r.measurementSource())).toList(),
                l.reason())).toList();
        return counts.saveDraft(id, lines, dto.name(), dto.notes());
    }

    @PostMapping("/{id}/submit")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BEVERAGE__COUNT)
    public Map<String, Object> submit(@PathVariable String id,
            @Valid @RequestBody(required = false) SubmitBottleCountRequest dto) {
        return counts.submit(id, dto == null ? null
                : new Approval(dto.approverId(), dto.approverEmail(), dto.managerPin()));
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BEVERAGE__COUNT)
    public Map<String, Object> cancel(@PathVariable String id) {
        return counts.cancel(id);
    }
}
