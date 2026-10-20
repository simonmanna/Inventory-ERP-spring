package com.inventoryerp.inventory.web;

import com.inventoryerp.inventory.application.PostingRuleAdminService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
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
 * object types, so the ValidationPipe skips them: any JSON object is accepted.
 */
@RestController
@RequestMapping("/inventory/posting-rules")
public class PostingRuleController {

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
    public Map<String, Object> create(@RequestBody Map<String, Object> dto) {
        return svc.create(dto);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.INVENTORY_POSTING_RULE__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return svc.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.INVENTORY_POSTING_RULE__DELETE)
    public void remove(@PathVariable String id) {
        svc.remove(id);
    }
}
