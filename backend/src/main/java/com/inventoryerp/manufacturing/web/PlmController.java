package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.PlmChangeService;
import com.inventoryerp.manufacturing.application.PlmCommands.ApplyChange;
import com.inventoryerp.manufacturing.application.PlmCommands.Cancel;
import com.inventoryerp.manufacturing.application.PlmCommands.Comment;
import com.inventoryerp.manufacturing.application.PlmCommands.ConvertRequest;
import com.inventoryerp.manufacturing.application.PlmCommands.CreateChange;
import com.inventoryerp.manufacturing.application.PlmCommands.Decide;
import com.inventoryerp.manufacturing.application.PlmCommands.LinkDocuments;
import com.inventoryerp.manufacturing.application.PlmCommands.MoveStage;
import com.inventoryerp.manufacturing.application.PlmCommands.RegisterItem;
import com.inventoryerp.manufacturing.application.PlmCommands.ReviewRequest;
import com.inventoryerp.manufacturing.application.PlmCommands.RoutingOperations;
import com.inventoryerp.manufacturing.application.PlmCommands.SaveDocument;
import com.inventoryerp.manufacturing.application.PlmCommands.SaveRequest;
import com.inventoryerp.manufacturing.application.PlmCommands.SaveStage;
import com.inventoryerp.manufacturing.application.PlmCommands.StageApprovers;
import com.inventoryerp.manufacturing.application.PlmCommands.Transition;
import com.inventoryerp.manufacturing.application.PlmCommands.UpdateChange;
import com.inventoryerp.manufacturing.application.PlmCommands.UpdateItem;
import com.inventoryerp.manufacturing.application.PlmDocumentService;
import com.inventoryerp.manufacturing.application.PlmLifecycleService;
import com.inventoryerp.manufacturing.application.PlmRequestService;
import com.inventoryerp.manufacturing.application.PlmStageService;
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
 * Product lifecycle management (ADR-S063): lifecycle items, change requests (ECR), staged change orders (ECO),
 * engineering documents and the ECO stage configuration. Spring-native, under {@code /manufacturing/plm}.
 */
@RestController
@RequestMapping("/manufacturing/plm")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class PlmController {

    private final PlmLifecycleService lifecycle;
    private final PlmStageService stages;
    private final PlmRequestService requests;
    private final PlmChangeService changes;
    private final PlmDocumentService documents;

    public PlmController(PlmLifecycleService lifecycle, PlmStageService stages, PlmRequestService requests,
            PlmChangeService changes, PlmDocumentService documents) {
        this.lifecycle = lifecycle;
        this.stages = stages;
        this.requests = requests;
        this.changes = changes;
        this.documents = documents;
    }

    private static Map<String, Object> ok() {
        return Map.of("success", true);
    }

    // ---- overview & lifecycle items ------------------------------------------------------------------------------

    @GetMapping("/overview")
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> overview() {
        return lifecycle.overview();
    }

    @GetMapping("/items")
    @RequirePermissions(Permissions.PLM__READ)
    public List<Map<String, Object>> items(@RequestParam(required = false) String state,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String q) {
        return lifecycle.list(state, productId, q);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> register(@Valid @RequestBody RegisterItem dto) {
        return lifecycle.register(dto);
    }

    @PostMapping("/items/register-active")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> registerActive() {
        return lifecycle.registerActive();
    }

    @GetMapping("/items/{id}")
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> item(@PathVariable String id) {
        return lifecycle.detail(id);
    }

    @PatchMapping("/items/{id}")
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> updateItem(@PathVariable String id, @Valid @RequestBody UpdateItem dto) {
        return lifecycle.update(id, dto);
    }

    @PostMapping("/items/{id}/transition")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> transition(@PathVariable String id, @Valid @RequestBody Transition dto) {
        return lifecycle.transition(id, dto);
    }

    // ---- ECO stages ------------------------------------------------------------------------------------------------

    @GetMapping("/stages")
    @RequirePermissions(Permissions.PLM__READ)
    public List<Map<String, Object>> stages() {
        return stages.list();
    }

    @PostMapping("/stages")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> createStage(@Valid @RequestBody SaveStage dto) {
        return stages.create(dto);
    }

    @PatchMapping("/stages/{id}")
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> updateStage(@PathVariable String id, @Valid @RequestBody SaveStage dto) {
        return stages.update(id, dto);
    }

    @DeleteMapping("/stages/{id}")
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> deleteStage(@PathVariable String id) {
        stages.remove(id);
        return ok();
    }

    @PutMapping("/stages/{id}/approvers")
    @RequirePermissions(Permissions.PLM__MANAGE)
    public List<Map<String, Object>> approvers(@PathVariable String id, @Valid @RequestBody StageApprovers dto) {
        return stages.setApprovers(id, dto.approvers());
    }

    // ---- change requests -------------------------------------------------------------------------------------------

    @GetMapping("/requests")
    @RequirePermissions(Permissions.PLM__READ)
    public List<Map<String, Object>> requests(@RequestParam(required = false) String status,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String q) {
        return requests.list(status, productId, q);
    }

    @GetMapping("/requests/{id}")
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> request(@PathVariable String id) {
        return requests.get(id);
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__REQUEST)
    public Map<String, Object> createRequest(@Valid @RequestBody SaveRequest dto) {
        return requests.create(dto);
    }

    @PatchMapping("/requests/{id}")
    @RequirePermissions(Permissions.PLM__REQUEST)
    public Map<String, Object> updateRequest(@PathVariable String id, @Valid @RequestBody SaveRequest dto) {
        return requests.update(id, dto);
    }

    @PostMapping("/requests/{id}/review")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__APPROVE)
    public Map<String, Object> review(@PathVariable String id, @Valid @RequestBody ReviewRequest dto) {
        return requests.review(id, dto);
    }

    @PostMapping("/requests/{id}/convert")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__CHANGE)
    public Map<String, Object> convert(@PathVariable String id, @Valid @RequestBody ConvertRequest dto) {
        return requests.convert(id, dto);
    }

    // ---- change orders ---------------------------------------------------------------------------------------------

    @GetMapping("/changes")
    @RequirePermissions(Permissions.PLM__READ)
    public List<Map<String, Object>> changes(@RequestParam(required = false) String status,
            @RequestParam(required = false) String stageId, @RequestParam(required = false) String type,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String q) {
        return changes.list(status, stageId, type, productId, q);
    }

    @PostMapping("/changes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__CHANGE)
    public Map<String, Object> createChange(@Valid @RequestBody CreateChange dto) {
        return changes.create(dto);
    }

    @GetMapping("/changes/{id}")
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> change(@PathVariable String id) {
        return changes.get(id);
    }

    @PatchMapping("/changes/{id}")
    @RequirePermissions(Permissions.PLM__CHANGE)
    public Map<String, Object> updateChange(@PathVariable String id, @Valid @RequestBody UpdateChange dto) {
        return changes.update(id, dto);
    }

    @GetMapping("/changes/{id}/impact")
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> impact(@PathVariable String id) {
        return changes.impact(id);
    }

    @PostMapping("/changes/{id}/stage")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__CHANGE)
    public Map<String, Object> moveStage(@PathVariable String id, @Valid @RequestBody MoveStage dto) {
        return changes.moveStage(id, dto.stageId());
    }

    @PostMapping("/changes/{id}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__APPROVE)
    public Map<String, Object> decide(@PathVariable String id, @Valid @RequestBody Decide dto) {
        return changes.decide(id, dto);
    }

    @PostMapping("/changes/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> comment(@PathVariable String id, @Valid @RequestBody Comment dto) {
        return changes.comment(id, dto.message());
    }

    @PostMapping("/changes/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__CHANGE)
    public Map<String, Object> linkDocuments(@PathVariable String id, @Valid @RequestBody LinkDocuments dto) {
        return changes.linkDocuments(id, dto.documentIds());
    }

    @PutMapping("/changes/{id}/routing-operations")
    @RequirePermissions(Permissions.PLM__CHANGE)
    public Map<String, Object> routingOperations(@PathVariable String id, @Valid @RequestBody RoutingOperations dto) {
        return changes.saveRoutingOperations(id, dto.operations());
    }

    @PostMapping("/changes/{id}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__APPLY)
    public Map<String, Object> apply(@PathVariable String id, @Valid @RequestBody(required = false) ApplyChange dto) {
        return changes.apply(id, dto != null && Boolean.TRUE.equals(dto.force()));
    }

    @PostMapping("/changes/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__APPLY)
    public Map<String, Object> cancel(@PathVariable String id, @Valid @RequestBody(required = false) Cancel dto) {
        return changes.cancel(id, dto == null ? null : dto.reason());
    }

    // ---- engineering documents -------------------------------------------------------------------------------------

    @GetMapping("/documents")
    @RequirePermissions(Permissions.PLM__READ)
    public List<Map<String, Object>> documents(@RequestParam(required = false) String productId,
            @RequestParam(required = false) String changeId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String docType, @RequestParam(required = false) String q) {
        return documents.list(productId, changeId, status, docType, q);
    }

    @GetMapping("/documents/{id}")
    @RequirePermissions(Permissions.PLM__READ)
    public Map<String, Object> document(@PathVariable String id) {
        return documents.get(id);
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> createDocument(@Valid @RequestBody SaveDocument dto) {
        return documents.create(dto);
    }

    @PatchMapping("/documents/{id}")
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> updateDocument(@PathVariable String id, @Valid @RequestBody SaveDocument dto) {
        return documents.update(id, dto);
    }

    @PostMapping("/documents/{id}/release")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> releaseDocument(@PathVariable String id) {
        return documents.release(id);
    }

    @PostMapping("/documents/{id}/revise")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> reviseDocument(@PathVariable String id) {
        return documents.revise(id);
    }

    @PostMapping("/documents/{id}/obsolete")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> obsoleteDocument(@PathVariable String id) {
        return documents.obsolete(id);
    }

    @DeleteMapping("/documents/{id}")
    @RequirePermissions(Permissions.PLM__MANAGE)
    public Map<String, Object> deleteDocument(@PathVariable String id) {
        documents.remove(id);
        return ok();
    }
}
