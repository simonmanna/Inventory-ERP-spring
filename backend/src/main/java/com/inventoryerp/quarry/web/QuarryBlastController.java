package com.inventoryerp.quarry.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.quarry.application.QuarryBlastService;
import com.inventoryerp.quarry.application.QuarryCommands.BlastInput;
import com.inventoryerp.quarry.application.QuarryCommands.BlastQuery;
import com.inventoryerp.quarry.application.QuarryCommands.CancelInput;
import com.inventoryerp.quarry.application.QuarryCommands.ExplosiveInput;
import com.inventoryerp.quarry.application.QuarryCommands.ExplosiveIssueInput;
import com.inventoryerp.quarry.application.QuarryCommands.FireInput;
import com.inventoryerp.quarry.application.QuarryCommands.IssueQuery;
import com.inventoryerp.quarry.application.QuarryCommands.MagazineInput;
import com.inventoryerp.quarry.application.QuarryCommands.ReconcileInput;
import com.inventoryerp.quarry.application.QuarryExplosiveService;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drilling &amp; blasting (ADR-S012): blasts (plan, fire with the compliance record, close, cancel), magazines, the
 * controlled-explosive registry and explosive issues (request → approval engine → issue from the magazine with
 * Idempotency-Key → reconcile the unused return).
 */
@RestController
@RequestMapping("/quarry")
@RequiresModule("quarry")
@ConditionalOnProperty(name = "app.modules.quarry", havingValue = "true")
@Validated
public class QuarryBlastController {

    private final QuarryBlastService blasts;
    private final QuarryExplosiveService explosives;

    public QuarryBlastController(QuarryBlastService blasts, QuarryExplosiveService explosives) {
        this.blasts = blasts;
        this.explosives = explosives;
    }

    @GetMapping("/blasts")
    @RequirePermissions(Permissions.QUARRY_BLASTING__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String siteId,
            @RequestParam(required = false) @OneOf({"planned", "fired", "closed", "cancelled"}) String status,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return blasts.list(new BlastQuery(siteId, status, dateFrom, dateTo, search, page, pageSize));
    }

    @GetMapping("/blasts/{id}")
    @RequirePermissions(Permissions.QUARRY_BLASTING__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return blasts.get(id);
    }

    @PostMapping("/blasts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY_BLASTING__CREATE)
    public Map<String, Object> create(@Valid @RequestBody BlastInput dto) {
        return blasts.create(dto);
    }

    @PatchMapping("/blasts/{id}")
    @RequirePermissions(Permissions.QUARRY_BLASTING__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody BlastInput dto) {
        return blasts.update(id, dto);
    }

    @PostMapping("/blasts/{id}/fire")
    @RequirePermissions(Permissions.QUARRY_BLASTING__UPDATE)
    public Map<String, Object> fire(@PathVariable String id, @Valid @RequestBody FireInput dto) {
        return blasts.fire(id, dto);
    }

    @PostMapping("/blasts/{id}/close")
    @RequirePermissions(Permissions.QUARRY_BLASTING__UPDATE)
    public Map<String, Object> close(@PathVariable String id) {
        return blasts.close(id);
    }

    @PostMapping("/blasts/{id}/cancel")
    @RequirePermissions(Permissions.QUARRY_BLASTING__UPDATE)
    public Map<String, Object> cancel(@PathVariable String id, @Valid @RequestBody CancelInput dto) {
        return blasts.cancel(id, dto.reason());
    }

    // ------------------------------------------------------------------ magazines & explosive registry

    @GetMapping("/magazines")
    @RequirePermissions(Permissions.QUARRY_BLASTING__READ)
    public List<Map<String, Object>> magazines(@RequestParam(required = false) String siteId) {
        return explosives.magazines(siteId);
    }

    @GetMapping("/magazines/{id}")
    @RequirePermissions(Permissions.QUARRY_BLASTING__READ)
    public Map<String, Object> magazine(@PathVariable String id) {
        return explosives.magazine(id);
    }

    /** Without {@code locationId} a new inventory location is created for the magazine. */
    @PostMapping("/magazines")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY_BLASTING__MAGAZINE)
    public Map<String, Object> createMagazine(@Valid @RequestBody MagazineInput dto) {
        return explosives.createMagazine(dto);
    }

    @PatchMapping("/magazines/{id}")
    @RequirePermissions(Permissions.QUARRY_BLASTING__MAGAZINE)
    public Map<String, Object> updateMagazine(@PathVariable String id, @Valid @RequestBody MagazineInput dto) {
        return explosives.updateMagazine(id, dto);
    }

    @GetMapping("/explosives")
    @RequirePermissions(Permissions.QUARRY_BLASTING__READ)
    public List<Map<String, Object>> explosives(@RequestParam(required = false) Boolean includeInactive) {
        return explosives.explosives(Boolean.TRUE.equals(includeInactive));
    }

    @PostMapping("/explosives")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY_BLASTING__MAGAZINE)
    public Map<String, Object> createExplosive(@Valid @RequestBody ExplosiveInput dto) {
        return explosives.createExplosive(dto);
    }

    @PatchMapping("/explosives/{id}")
    @RequirePermissions(Permissions.QUARRY_BLASTING__MAGAZINE)
    public Map<String, Object> updateExplosive(@PathVariable String id, @Valid @RequestBody ExplosiveInput dto) {
        return explosives.updateExplosive(id, dto);
    }

    // ------------------------------------------------------------------ explosive issues

    @GetMapping("/explosive-issues")
    @RequirePermissions(Permissions.QUARRY_BLASTING__READ)
    public PageResult<Map<String, Object>> issues(
            @RequestParam(required = false) @OneOf({"pending_approval", "approved", "rejected", "issued", "reconciled",
                "cancelled"}) String status,
            @RequestParam(required = false) String blastId,
            @RequestParam(required = false) String magazineId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return explosives.issues(new IssueQuery(status, blastId, magazineId, search, page, pageSize));
    }

    @GetMapping("/explosive-issues/{id}")
    @RequirePermissions(Permissions.QUARRY_BLASTING__READ)
    public Map<String, Object> issue(@PathVariable String id) {
        return explosives.issue(id);
    }

    /** Request explosives for a blast; decided in the approval engine (Approvals inbox). */
    @PostMapping("/explosive-issues")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY_BLASTING__REQUEST_EXPLOSIVE)
    public Map<String, Object> request(@Valid @RequestBody ExplosiveIssueInput dto) {
        return explosives.request(dto);
    }

    /** Re-read the approval outcome now (normally applied automatically). */
    @PostMapping("/explosive-issues/{id}/sync")
    @RequirePermissions(Permissions.QUARRY_BLASTING__REQUEST_EXPLOSIVE)
    public Map<String, Object> sync(@PathVariable String id) {
        return explosives.sync(id);
    }

    @PostMapping("/explosive-issues/{id}/issue")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.QUARRY_BLASTING__ISSUE_EXPLOSIVE)
    public Map<String, Object> issueStock(@PathVariable String id) {
        return explosives.issueStock(id);
    }

    @PostMapping("/explosive-issues/{id}/reconcile")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.QUARRY_BLASTING__ISSUE_EXPLOSIVE)
    public Map<String, Object> reconcile(@PathVariable String id, @Valid @RequestBody ReconcileInput dto) {
        return explosives.reconcile(id, dto);
    }

    @PostMapping("/explosive-issues/{id}/cancel")
    @RequirePermissions(Permissions.QUARRY_BLASTING__REQUEST_EXPLOSIVE)
    public Map<String, Object> cancelIssue(@PathVariable String id, @Valid @RequestBody CancelInput dto) {
        return explosives.cancel(id, dto.reason());
    }
}
