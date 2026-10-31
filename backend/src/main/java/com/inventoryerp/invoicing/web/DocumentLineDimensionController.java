package com.inventoryerp.invoicing.web;

import com.inventoryerp.invoicing.application.DocumentLineDimensionService;
import com.inventoryerp.invoicing.application.DocumentLineDimensionService.LineDimensions;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /analytic-dimensions/documents/:id/lines}: analytic dimensions on document lines (ADR-S025, extension). */
@RestController
@RequestMapping("/analytic-dimensions/documents")
public class DocumentLineDimensionController {

    public record LineRequest(
            @NotBlank(message = "lineId should not be empty") String lineId,
            Map<String, Object> dimensions) {}

    public record SetLinesRequest(@NotNull(message = "lines must be an array") List<@Valid LineRequest> lines) {}

    private final DocumentLineDimensionService service;

    public DocumentLineDimensionController(DocumentLineDimensionService service) {
        this.service = service;
    }

    @GetMapping("/{documentId}/lines")
    @RequirePermissions(Permissions.ANALYTIC_DIMENSION__READ)
    public Map<String, Object> get(@PathVariable String documentId) {
        return service.get(documentId);
    }

    @PutMapping("/{documentId}/lines")
    @RequirePermissions(Permissions.ANALYTIC_DIMENSION__MANAGE)
    public Map<String, Object> set(@PathVariable String documentId, @Valid @RequestBody SetLinesRequest dto) {
        return service.set(documentId, dto.lines().stream()
                .map(l -> new LineDimensions(l.lineId(), l.dimensions())).toList());
    }
}
