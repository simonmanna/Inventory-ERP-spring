package com.inventoryerp.inventory.web;

import com.inventoryerp.inventory.application.PostingRuleAdminService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
 * {@code /inventory/posting-rules} (port of {@code posting-rule.http.controller.ts}). The Nest bodies are inline
 * object types, so the ValidationPipe skips them and any JSON object is accepted; here they are validated records with
 * exactly the Nest fields (deviation, docs/spec/inventory.md): unknown fields, an unknown movement type / side / account
 * source and a missing movementType / lineIndex / debitOrCredit are 400 instead of a 500 at insert or posting time.
 */
@RestController
@RequestMapping("/inventory/posting-rules")
public class PostingRuleController {

    /** Create body: the fields of the Nest inline {@code create} DTO. */
    public record CreatePostingRuleRequest(
            @NotNull(message = "movementType should not be empty") @OneOf({"STOCK_IN", "STOCK_OUT",
                "STOCK_TRANSFER_OUT", "STOCK_TRANSFER_IN", "ADJUSTMENT_GAIN", "ADJUSTMENT_LOSS", "WASTE",
                "EXPIRY_WRITE_OFF", "RETURN_RESTOCK", "RETURN_TO_SUPPLIER", "INTERNAL_CONSUMPTION", "PROMO_SAMPLE",
                "PRODUCTION_CONSUME", "PRODUCTION_OUTPUT", "REVALUATION", "COGS_CORRECTION"}) String movementType,
            @NotNull(message = "lineIndex should not be empty") @Min(value = 0, message = "lineIndex must not be"
                    + " less than 0") Integer lineIndex,
            @NotNull(message = "debitOrCredit should not be empty") @OneOf({"debit", "credit"}) String debitOrCredit,
            @OneOf({"account_mapping", "literal", "category_field", "product_field"}) String accountSource,
            String accountMappingKey,
            String literalAccountId,
            String productId,
            String categoryId) {}

    /** Update body: the fields of the Nest inline {@code update} DTO (omitted = unchanged). */
    public record UpdatePostingRuleRequest(
            @OneOf({"account_mapping", "literal", "category_field", "product_field"}) String accountSource,
            String accountMappingKey,
            String literalAccountId,
            Boolean isActive) {}

    private final PostingRuleAdminService svc;

    public PostingRuleController(PostingRuleAdminService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(Permissions.INVENTORY_POSTING_RULE__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String movementType,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String categoryId) {
        return svc.list(movementType, productId, categoryId);
    }

    @GetMapping("/overview")
    @RequirePermissions(Permissions.INVENTORY_POSTING_RULE__READ)
    public Map<String, Object> overview() {
        return svc.overview();
    }

    @GetMapping("/movement-types")
    @RequirePermissions(Permissions.INVENTORY_POSTING_RULE__READ)
    public List<Map<String, Object>> movementTypes() {
        return svc.movementTypes();
    }

    @GetMapping("/resolve-preview")
    @RequirePermissions(Permissions.INVENTORY_POSTING_RULE__READ)
    public Map<String, Object> resolvePreview(@RequestParam(required = false) String productId,
            @RequestParam(required = false) String movementType) {
        return svc.resolvePreview(productId, movementType);
    }

    @PostMapping("/restore-defaults")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INVENTORY_POSTING_RULE__CREATE)
    public Map<String, Object> restoreDefaults() {
        return svc.restoreDefaults();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INVENTORY_POSTING_RULE__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreatePostingRuleRequest dto) {
        return svc.create(EntityPatch.data(dto));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.INVENTORY_POSTING_RULE__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdatePostingRuleRequest dto) {
        return svc.update(id, EntityPatch.data(dto));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.INVENTORY_POSTING_RULE__DELETE)
    public void remove(@PathVariable String id) {
        svc.remove(id);
    }
}
