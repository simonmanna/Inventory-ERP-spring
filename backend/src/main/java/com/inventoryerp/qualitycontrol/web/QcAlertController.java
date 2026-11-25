package com.inventoryerp.qualitycontrol.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.AlertInput;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.AlertPatch;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.AlertQuery;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.EscalateInput;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.MoveInput;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.RootCauseInput;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.RootCausePatch;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.StageInput;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.StagePatch;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.TagInput;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.TagPatch;
import com.inventoryerp.qualitycontrol.application.QcAlertConfigService;
import com.inventoryerp.qualitycontrol.application.QcAlertService;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Quality alerts and their settings (ADR-S087 §8, QC3). */
@RestController
@RequestMapping("/quality-control")
@Validated
public class QcAlertController {

    private final QcAlertService alerts;
    private final QcAlertConfigService config;

    public QcAlertController(QcAlertService alerts, QcAlertConfigService config) {
        this.alerts = alerts;
        this.config = config;
    }

    // ------------------------------------------------------------------ alerts

    @GetMapping("/alerts")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) String stageId, @RequestParam(required = false) String teamId,
            @RequestParam(required = false) String responsibleUserId,
            @RequestParam(required = false) @Min(0) @Max(3) Integer priority,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String checkId,
            @RequestParam(required = false) String ncrId, @RequestParam(required = false) String tagId,
            @RequestParam(required = false) Boolean open,
            @RequestParam(required = false) @OneOf({"manual", "auto"}) String origin,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return alerts.list(new AlertQuery(search, stageId, teamId, responsibleUserId, priority, productId, checkId, ncrId,
                tagId, open, origin, page, pageSize));
    }

    @GetMapping("/alerts/kanban")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public Map<String, Object> kanban(@RequestParam(required = false) String search,
            @RequestParam(required = false) String teamId, @RequestParam(required = false) String responsibleUserId,
            @RequestParam(required = false) @Min(0) @Max(3) Integer priority,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String tagId) {
        return alerts.kanban(new AlertQuery(search, null, teamId, responsibleUserId, priority, productId, null, null,
                tagId, null, null, null, null));
    }

    @GetMapping("/alerts/{id}")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return alerts.get(id);
    }

    @PostMapping("/alerts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY_CONTROL__ALERT)
    public Map<String, Object> create(@Valid @RequestBody AlertInput dto) {
        return alerts.create(dto);
    }

    @PatchMapping("/alerts/{id}")
    @RequirePermissions(Permissions.QUALITY_CONTROL__ALERT)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody AlertPatch dto) {
        return alerts.update(id, dto);
    }

    @PostMapping("/alerts/{id}/move")
    @RequirePermissions(Permissions.QUALITY_CONTROL__ALERT)
    public Map<String, Object> move(@PathVariable String id, @Valid @RequestBody MoveInput dto) {
        return alerts.move(id, dto);
    }

    /** Escalates to a nonconformity of the quality module; repeated calls return the same NCR. */
    @PostMapping("/alerts/{id}/escalate")
    @RequirePermissions(Permissions.QUALITY_CONTROL__ALERT)
    public Map<String, Object> escalate(@PathVariable String id, @Valid @RequestBody(required = false) EscalateInput dto) {
        return alerts.escalate(id, dto);
    }

    // ------------------------------------------------------------------ stages, tags, root causes

    @GetMapping("/alert-stages")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public List<Map<String, Object>> stages(@RequestParam(required = false) Boolean active) {
        return config.stages(active);
    }

    @PostMapping("/alert-stages")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> createStage(@Valid @RequestBody StageInput dto) {
        return config.createStage(dto);
    }

    @PatchMapping("/alert-stages/{id}")
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> updateStage(@PathVariable String id, @Valid @RequestBody StagePatch dto) {
        return config.updateStage(id, dto);
    }

    @GetMapping("/tags")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public List<Map<String, Object>> tags() {
        return config.tags();
    }

    @PostMapping("/tags")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> createTag(@Valid @RequestBody TagInput dto) {
        return config.createTag(dto);
    }

    @PatchMapping("/tags/{id}")
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> updateTag(@PathVariable String id, @Valid @RequestBody TagPatch dto) {
        return config.updateTag(id, dto);
    }

    @GetMapping("/root-causes")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public List<Map<String, Object>> rootCauses(@RequestParam(required = false) Boolean active) {
        return config.rootCauses(active);
    }

    @PostMapping("/root-causes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> createRootCause(@Valid @RequestBody RootCauseInput dto) {
        return config.createRootCause(dto);
    }

    @PatchMapping("/root-causes/{id}")
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> updateRootCause(@PathVariable String id, @Valid @RequestBody RootCausePatch dto) {
        return config.updateRootCause(id, dto);
    }
}
