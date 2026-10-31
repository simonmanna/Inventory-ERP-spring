package com.inventoryerp.quarry.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.quarry.application.QuarryCommands.ExtractionLogInput;
import com.inventoryerp.quarry.application.QuarryCommands.ExtractionLogQuery;
import com.inventoryerp.quarry.application.QuarryCommands.LossInput;
import com.inventoryerp.quarry.application.QuarryCommands.ReverseInput;
import com.inventoryerp.quarry.application.QuarryExtractionService;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Daily extraction logs (ADR-S012): list with filters, detail, entry (draft or posted at once), edit / delete of
 * drafts, cost preview, post and reverse (Idempotency-Key required — they move stock and post to the GL), and
 * standalone losses (overburden stripping, waste).
 */
@RestController
@RequestMapping("/quarry")
@RequiresModule("quarry")
@ConditionalOnProperty(name = "app.modules.quarry", havingValue = "true")
@Validated
public class QuarryExtractionController {

    private final QuarryExtractionService logs;

    public QuarryExtractionController(QuarryExtractionService logs) {
        this.logs = logs;
    }

    /** {@code {data, meta, summary}}. */
    @GetMapping("/extraction-logs")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public Map<String, Object> list(@RequestParam(required = false) String siteId,
            @RequestParam(required = false) String zoneId,
            @RequestParam(required = false) String materialId,
            @RequestParam(required = false) @OneOf({"draft", "posted", "reversed"}) String status,
            @RequestParam(required = false) @OneOf({"day", "night", "morning", "afternoon"}) String shift,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize,
            @RequestParam(required = false) @OneOf({"extractedOn", "code", "netTonnes", "totalCost", "site"}) String sortBy,
            @RequestParam(required = false) @OneOf({"asc", "desc"}) String sortOrder) {
        return logs.list(new ExtractionLogQuery(siteId, zoneId, materialId, status, shift, dateFrom, dateTo, search,
                page, pageSize, sortBy, sortOrder));
    }

    @GetMapping("/extraction-logs/{id}")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return logs.get(id);
    }

    /** What posting would book now: rates, blast share, royalties, allocation, cost per tonne. */
    @GetMapping("/extraction-logs/{id}/cost-preview")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public Map<String, Object> preview(@PathVariable String id) {
        return logs.preview(id);
    }

    /** Entry; {@code post: true} also posts (stock / GL), hence the required Idempotency-Key. */
    @PostMapping("/extraction-logs")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__CREATE)
    public Map<String, Object> create(@Valid @RequestBody ExtractionLogInput dto) {
        return logs.create(dto);
    }

    @PatchMapping("/extraction-logs/{id}")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody ExtractionLogInput dto) {
        return logs.update(id, dto);
    }

    @DeleteMapping("/extraction-logs/{id}")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__UPDATE)
    public Map<String, Object> delete(@PathVariable String id) {
        logs.delete(id);
        return Map.of("id", id, "deleted", true);
    }

    @PostMapping("/extraction-logs/{id}/post")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__POST)
    public Map<String, Object> post(@PathVariable String id) {
        return logs.post(id);
    }

    @PostMapping("/extraction-logs/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__POST)
    public Map<String, Object> reverse(@PathVariable String id, @Valid @RequestBody ReverseInput dto) {
        return logs.reverse(id, dto.reason());
    }

    /** Losses of posted logs and standalone losses. */
    @GetMapping("/losses")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public PageResult<Map<String, Object>> losses(@RequestParam(required = false) String siteId,
            @RequestParam(required = false) @OneOf({"overburden", "unusable_rock", "waste", "spillage", "other"})
            String kind,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return logs.losses(siteId, kind, dateFrom, dateTo, page, pageSize);
    }

    @PostMapping("/losses")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__CREATE)
    public Map<String, Object> createLoss(@Valid @RequestBody LossInput dto) {
        return logs.createLoss(dto);
    }

    @DeleteMapping("/losses/{id}")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__UPDATE)
    public Map<String, Object> deleteLoss(@PathVariable String id) {
        logs.deleteLoss(id);
        return Map.of("id", id, "deleted", true);
    }
}
