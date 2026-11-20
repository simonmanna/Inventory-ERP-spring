package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.RevaluationService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.idempotency.Idempotent;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
            @Pattern(regexp = AccountingDtos.ISO_DATE, message = "must be a valid ISO 8601 date string") String asOf,
            Boolean acknowledgeWarnings) {}

    /** FX-2 C: {@code PUT /fx-revaluations/accounts/{id}} body. */
    public record AccountTreatmentRequest(
            @NotNull(message = "must be a string")
            @com.inventoryerp.shared.validation.OneOf({"inherit", "monetary", "non_monetary"}) String fxRevaluation) {}

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

    /** FX-2 C: conditions the run refuses on unless acknowledged. */
    @PostMapping("/warnings")
    @RequirePermissions(Permissions.CURRENCY__READ)
    public List<RevaluationService.Warning> warnings(@Valid @RequestBody RevaluationRequest dto) {
        return revaluation.warnings(revaluation.endOfDay(dto.asOf()));
    }

    /** FX-2 C: balance-sheet accounts with their monetary setting and effective treatment. */
    @GetMapping("/accounts")
    @RequirePermissions(Permissions.CURRENCY__READ)
    public List<Map<String, Object>> accounts() {
        return revaluation.accountTreatments();
    }

    @PutMapping("/accounts/{id}")
    @RequirePermissions(Permissions.CURRENCY__POLICY)
    public Map<String, Object> setAccount(@PathVariable String id, @Valid @RequestBody AccountTreatmentRequest dto) {
        return revaluation.setAccountTreatment(id, dto.fxRevaluation());
    }

    @PostMapping
    @Idempotent // posts to the ledger: a retried run replays instead of running again (review 2026-11-15); answers 200
    @RequirePermissions(Permissions.CURRENCY__UPDATE)
    public Map<String, Object> run(@Valid @RequestBody RevaluationRequest dto) {
        RevaluationService.Result r = revaluation.run(revaluation.endOfDay(dto.asOf()),
                Boolean.TRUE.equals(dto.acknowledgeWarnings()));
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
