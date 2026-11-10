package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.RevaluationService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /fx-revaluations}: unrealised FX revaluation (ACC-2, B3). Nest registers no route for the revaluation
 * service; these routes are an owner-approved extension (docs/spec/accounting.md, accounting remediation deviations).
 */
@RestController
@RequestMapping("/fx-revaluations")
public class FxRevaluationController {

    /** {@code asOf}: the revaluation date (end of day); defaults to today. */
    public record RevaluationRequest(
            @NotNull(message = "must be a valid ISO 8601 date string")
            @Pattern(regexp = AccountingDtos.ISO_DATE, message = "must be a valid ISO 8601 date string") String asOf) {}

    private final RevaluationService revaluation;

    public FxRevaluationController(RevaluationService revaluation) {
        this.revaluation = revaluation;
    }

    @GetMapping
    @RequirePermissions(Permissions.CURRENCY__READ)
    public List<Map<String, Object>> list() {
        return revaluation.list();
    }

    @PostMapping("/preview")
    @RequirePermissions(Permissions.CURRENCY__READ)
    public List<RevaluationService.Line> preview(@Valid @RequestBody RevaluationRequest dto) {
        return revaluation.preview(revaluation.endOfDay(dto.asOf()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CURRENCY__UPDATE)
    public Map<String, Object> run(@Valid @RequestBody RevaluationRequest dto) {
        RevaluationService.Result r = revaluation.run(revaluation.endOfDay(dto.asOf()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revalued", r.revalued());
        out.put("totalGain", r.totalGain());
        return out;
    }

    /**
     * A bare date means the end of that day, as an org-local wall-clock value (compared with date-like columns such as
     * bank statement lines; ledger comparisons convert it, ADR-S070).
     */
    static Instant endOfDay(String asOf) {
        if (asOf.length() == 10) {
            return LocalDate.parse(asOf).atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        }
        return EntityPatch.toInstant(asOf);
    }
}
