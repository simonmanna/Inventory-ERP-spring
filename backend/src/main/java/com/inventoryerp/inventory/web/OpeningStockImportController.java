package com.inventoryerp.inventory.web;

import com.inventoryerp.inventory.application.OpeningStockImportService;
import com.inventoryerp.inventory.application.OpeningStockImportService.Row;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IPR R5 / ADR-S090: opening-stock import (new routes, no Nest counterpart). Preview needs
 * {@code inventory_valuation:manage} (the authority an opening count needs); import and batch retry post journals and
 * need {@code inventory_valuation:post} plus an Idempotency-Key; the reads need {@code inventory_valuation:read}.
 */
@RestController
@RequestMapping("/inventory/opening-stock")
public class OpeningStockImportController {

    /** The rows of a CSV file (parsed by the client), at most 20,000. */
    public record PreviewRequest(@NotNull @Size(min = 1, max = OpeningStockImportService.MAX_ROWS) List<@Valid Row> rows,
            @Size(max = 255) String fileName) {}

    /** The previewed rows again, the preview token and the explicit approval. */
    public record ImportRequest(@NotNull @Size(min = 1, max = OpeningStockImportService.MAX_ROWS) List<@Valid Row> rows,
            @NotNull @Size(min = 1, max = 128) String previewToken, @NotNull Boolean approve,
            @Size(max = 255) String fileName, @Size(max = 2000) String notes) {}

    /** Approve a failed batch again; {@code batchToken} when it was previewed again after a stale refusal. */
    public record RetryRequest(@Size(max = 128) String batchToken, @NotNull Boolean approve) {}

    private final OpeningStockImportService service;

    public OpeningStockImportController(OpeningStockImportService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__MANAGE)
    public Map<String, Object> preview(@Valid @RequestBody PreviewRequest body) {
        return service.preview(body.rows());
    }

    @PostMapping("/import")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INVENTORY_VALUATION__POST)
    public Map<String, Object> importRun(@Valid @RequestBody ImportRequest body) {
        return service.importRun(body.rows(), body.previewToken(), body.approve(), body.fileName(), body.notes());
    }

    @GetMapping("/runs")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__READ)
    public List<Map<String, Object>> runs() {
        return service.runs();
    }

    @GetMapping("/runs/{id}")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__READ)
    public Map<String, Object> run(@PathVariable String id) {
        return service.run(id);
    }

    @GetMapping("/runs/{id}/batches/{batchId}")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__READ)
    public Map<String, Object> batch(@PathVariable String id, @PathVariable String batchId) {
        return service.batch(id, batchId);
    }

    @PostMapping("/runs/{id}/batches/{batchId}/retry")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INVENTORY_VALUATION__POST)
    public Map<String, Object> retry(@PathVariable String id, @PathVariable String batchId,
            @Valid @RequestBody RetryRequest body) {
        return service.retryBatch(id, batchId, body.batchToken(), body.approve());
    }

    @GetMapping("/runs/{id}/reconciliation")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__READ)
    public Map<String, Object> reconciliation(@PathVariable String id) {
        return service.reconciliation(id);
    }
}
