package com.inventoryerp.plm.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.plm.application.PlmChangeService;
import com.inventoryerp.plm.application.PlmExceptionService;
import com.inventoryerp.plm.application.PlmFaiService;
import com.inventoryerp.plm.application.PlmCommands.GrantException;
import com.inventoryerp.plm.application.PlmCommands.KanbanState;
import com.inventoryerp.plm.application.PlmCommands.RecordFirstArticle;
import com.inventoryerp.plm.application.PlmCommands.RevokeException;
import com.inventoryerp.plm.application.PlmCommands.SaveSettings;
import com.inventoryerp.plm.application.PlmCommands.SaveTag;
import com.inventoryerp.plm.application.PlmCommands.SaveType;
import com.inventoryerp.plm.application.PlmModule;
import com.inventoryerp.plm.application.PlmOrderService;
import com.inventoryerp.plm.application.PlmReportService;
import com.inventoryerp.plm.application.PlmRevisionService;
import com.inventoryerp.plm.application.PlmSettingsService;
import com.inventoryerp.plm.application.PlmTagService;
import com.inventoryerp.plm.application.PlmTypeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The PLM application's routes added by ADR-S086 (ECO types, tags and kanban state, versions, ERP integration,
 * settings and reports), next to the ADR-S063 routes of {@link PlmController} under the same path and module guard.
 */
@RestController
@RequestMapping("/manufacturing/plm")
@RequiresModule(PlmModule.NAME)
@ConditionalOnProperty(name = "app.modules.plm", havingValue = "true")
public class PlmAppController {

    private final PlmTypeService types;
    private final PlmTagService tags;
    private final PlmChangeService changes;
    private final PlmRevisionService revisions;
    private final PlmOrderService orders;
    private final PlmSettingsService settings;
    private final PlmFaiService fai;
    private final PlmReportService reports;
    private final PlmExceptionService exceptions;

    public PlmAppController(PlmTypeService types, PlmTagService tags, PlmChangeService changes,
            PlmRevisionService revisions, PlmOrderService orders, PlmSettingsService settings, PlmFaiService fai,
            PlmReportService reports, PlmExceptionService exceptions) {
        this.exceptions = exceptions;
        this.settings = settings;
        this.fai = fai;
        this.reports = reports;
        this.types = types;
        this.tags = tags;
        this.changes = changes;
        this.revisions = revisions;
        this.orders = orders;
    }

    private static Map<String, Object> ok() {
        return Map.of("success", true);
    }

    // ---- ECO types -------------------------------------------------------------------------------------------------

    @GetMapping("/change-types")
    @RequirePermissions(Permissions.PLM__READ)
    public List<Map<String, Object>> changeTypes() {
        return types.list();
    }

    @PostMapping("/change-types")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> createChangeType(@Valid @RequestBody SaveType dto) {
        return types.create(dto);
    }

    @PatchMapping("/change-types/{id}")
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> updateChangeType(@PathVariable String id, @Valid @RequestBody SaveType dto) {
        return types.update(id, dto);
    }

    @DeleteMapping("/change-types/{id}")
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> deleteChangeType(@PathVariable String id) {
        types.remove(id);
        return ok();
    }

    // ---- tags ------------------------------------------------------------------------------------------------------

    @GetMapping("/tags")
    @RequirePermissions(Permissions.PLM__READ)
    public List<Map<String, Object>> tags() {
        return tags.list();
    }

    /** Anyone who edits ECOs may add a tag while tagging one. */
    @PostMapping("/tags")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__CHANGE)
    public Map<String, Object> createTag(@Valid @RequestBody SaveTag dto) {
        return tags.create(dto);
    }

    @PatchMapping("/tags/{id}")
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> updateTag(@PathVariable String id, @Valid @RequestBody SaveTag dto) {
        return tags.update(id, dto);
    }

    @DeleteMapping("/tags/{id}")
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> deleteTag(@PathVariable String id) {
        tags.remove(id);
        return ok();
    }

    // ---- change orders ---------------------------------------------------------------------------------------------

    @PostMapping("/changes/{id}/kanban-state")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__CHANGE)
    public Map<String, Object> kanbanState(@PathVariable String id, @Valid @RequestBody KanbanState dto) {
        return changes.kanban(id, dto.kanbanState(), dto.blockedReason());
    }

    /** Record the first-article result by hand (no inspection lot was opened for it). */
    @PostMapping("/changes/{id}/first-article")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__APPROVE)
    public Map<String, Object> firstArticle(@PathVariable String id, @Valid @RequestBody RecordFirstArticle dto) {
        return fai.record(id, dto.result(), dto.note());
    }

    /** What the change does to the books: unit cost now vs the new version, revaluation, cost policy outcome. */
    @GetMapping("/changes/{id}/financial")
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> financial(@PathVariable String id) {
        return changes.financial(id);
    }

    /** Three-way merge of a BOM change onto the version that superseded its base. */
    @PostMapping("/changes/{id}/rebase")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__CHANGE)
    public Map<String, Object> rebase(@PathVariable String id) {
        return changes.rebase(id);
    }

    // ---- versions --------------------------------------------------------------------------------------------------

    @GetMapping("/items/{id}/revisions")
    @RequirePermissions(Permissions.PLM__READ)
    public List<Map<String, Object>> revisions(@PathVariable String id) {
        return revisions.list(id);
    }

    @GetMapping("/revisions/compare")
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> compareRevisions(@RequestParam String from, @RequestParam String to) {
        return revisions.compare(from, to);
    }

    @GetMapping("/revisions/{id}")
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> revision(@PathVariable String id) {
        return revisions.get(id);
    }

    @GetMapping("/items/{id}/as-built")
    @RequirePermissions(Permissions.PLM__READ)
    public List<Map<String, Object>> asBuilt(@PathVariable String id) {
        return revisions.asBuilt(id);
    }

    // ---- reports ---------------------------------------------------------------------------------------------------

    /** Engineering change analytics over a period, grouped by month, type, stage, owner or priority. */
    @GetMapping("/reports/changes")
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> changeReport(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String changeTypeId,
            @RequestParam(required = false) String groupBy) {
        return reports.changes(from, to, changeTypeId, groupBy);
    }

    /** Stock exposure of products being phased out or obsolete. */
    @GetMapping("/reports/obsolete-stock")
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> obsoleteStock(@RequestParam(required = false) String state) {
        return reports.obsoleteStock(state);
    }

    /** Stock on hand by the revision it was built on. */
    @GetMapping("/reports/stock-by-revision")
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> stockByRevision(@RequestParam(required = false) String productId) {
        return reports.stockByRevision(productId);
    }

    // ---- settings --------------------------------------------------------------------------------------------------

    @GetMapping("/settings")
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> settings() {
        return settings.get();
    }

    @PutMapping("/settings")
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> saveSettings(@Valid @RequestBody SaveSettings dto) {
        return settings.update(dto);
    }

    // ---- lifecycle exceptions (ADR-S086 §6) ------------------------------------------------------------------------

    @GetMapping("/lifecycle-exceptions")
    @RequirePermissions(Permissions.PLM__READ)
    public List<Map<String, Object>> lifecycleExceptions(@RequestParam(required = false) String productId,
            @RequestParam(required = false) Boolean active) {
        return exceptions.list(productId, active);
    }

    @PostMapping("/lifecycle-exceptions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__EXCEPTION)
    public Map<String, Object> grantLifecycleException(@Valid @RequestBody GrantException dto) {
        return exceptions.grant(dto);
    }

    @PostMapping("/lifecycle-exceptions/{id}/revoke")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__EXCEPTION)
    public Map<String, Object> revokeLifecycleException(@PathVariable String id,
            @Valid @RequestBody(required = false) RevokeException dto) {
        return exceptions.revoke(id, dto);
    }

    // ---- production orders -----------------------------------------------------------------------------------------

    @GetMapping("/orders/{orderId}/bom-status")
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> orderBomStatus(@PathVariable String orderId) {
        return orders.bomStatus(orderId);
    }

    /** Move a not-started order onto the current BOM version (replacement order). */
    @PostMapping("/orders/{orderId}/update-bom")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__APPLY)
    public Map<String, Object> updateOrderBom(@PathVariable String orderId) {
        return orders.updateBom(orderId);
    }
}
