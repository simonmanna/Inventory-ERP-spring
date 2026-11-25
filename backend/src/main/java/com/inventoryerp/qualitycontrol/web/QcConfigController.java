package com.inventoryerp.qualitycontrol.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.qualitycontrol.application.QcCommands.ControlPointInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.ControlPointQuery;
import com.inventoryerp.qualitycontrol.application.QcCommands.FailureClassInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.FailureClassPatch;
import com.inventoryerp.qualitycontrol.application.QcCommands.StatusReason;
import com.inventoryerp.qualitycontrol.application.QcCommands.TeamInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.TeamPatch;
import com.inventoryerp.qualitycontrol.application.QcCommands.TemplateInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.TemplatePatch;
import com.inventoryerp.qualitycontrol.application.QcControlPointService;
import com.inventoryerp.qualitycontrol.application.QcFailureClassService;
import com.inventoryerp.qualitycontrol.application.QcTeamService;
import com.inventoryerp.qualitycontrol.application.QcWorksheetService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Quality-control configuration (ADR-S087): teams, failure classes, worksheet templates, control points. */
@RestController
@RequestMapping("/quality-control")
@Validated
public class QcConfigController {

    private final QcTeamService teams;
    private final QcFailureClassService failureClasses;
    private final QcWorksheetService worksheets;
    private final QcControlPointService points;

    public QcConfigController(QcTeamService teams, QcFailureClassService failureClasses, QcWorksheetService worksheets,
            QcControlPointService points) {
        this.teams = teams;
        this.failureClasses = failureClasses;
        this.worksheets = worksheets;
        this.points = points;
    }

    // ------------------------------------------------------------------ teams

    @GetMapping("/teams")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public List<Map<String, Object>> listTeams() {
        return teams.list();
    }

    @GetMapping("/teams/{id}")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public Map<String, Object> getTeam(@PathVariable String id) {
        return teams.get(id);
    }

    @PostMapping("/teams")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> createTeam(@Valid @RequestBody TeamInput dto) {
        return teams.create(dto);
    }

    @PatchMapping("/teams/{id}")
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> updateTeam(@PathVariable String id, @Valid @RequestBody TeamPatch dto) {
        return teams.update(id, dto);
    }

    // ------------------------------------------------------------------ failure classes

    @GetMapping("/failure-classes")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public List<Map<String, Object>> listFailureClasses(@RequestParam(required = false) Boolean active) {
        return failureClasses.list(active);
    }

    @PostMapping("/failure-classes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> createFailureClass(@Valid @RequestBody FailureClassInput dto) {
        return failureClasses.create(dto);
    }

    @PatchMapping("/failure-classes/{id}")
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> updateFailureClass(@PathVariable String id, @Valid @RequestBody FailureClassPatch dto) {
        return failureClasses.update(id, dto);
    }

    // ------------------------------------------------------------------ worksheet templates

    @GetMapping("/worksheet-templates")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public List<Map<String, Object>> listTemplates(@RequestParam(required = false) Boolean active) {
        return worksheets.list(active);
    }

    @GetMapping("/worksheet-templates/{id}")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public Map<String, Object> getTemplate(@PathVariable String id) {
        return worksheets.get(id);
    }

    @PostMapping("/worksheet-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> createTemplate(@Valid @RequestBody TemplateInput dto) {
        return worksheets.create(dto);
    }

    @PatchMapping("/worksheet-templates/{id}")
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> updateTemplate(@PathVariable String id, @Valid @RequestBody TemplatePatch dto) {
        return worksheets.update(id, dto);
    }

    // ------------------------------------------------------------------ control points

    @GetMapping("/control-points")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public PageResult<Map<String, Object>> listPoints(@RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"receipt", "delivery", "manufacturing", "work_order"}) String operation,
            @RequestParam(required = false) @OneOf({"instructions", "pass_fail", "measure", "picture", "worksheet"})
            String checkType,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return points.list(new ControlPointQuery(search, operation, checkType, active, page, pageSize));
    }

    @GetMapping("/control-points/{id}")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public Map<String, Object> getPoint(@PathVariable String id) {
        return points.get(id);
    }

    @PostMapping("/control-points")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> createPoint(@Valid @RequestBody ControlPointInput dto) {
        return points.create(dto);
    }

    @PutMapping("/control-points/{id}")
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> updatePoint(@PathVariable String id, @Valid @RequestBody ControlPointInput dto) {
        return points.update(id, dto);
    }

    @PostMapping("/control-points/{id}/activate")
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> activatePoint(@PathVariable String id, @Valid @RequestBody(required = false) StatusReason dto) {
        return points.setActive(id, true, dto != null ? dto.reason() : null);
    }

    @PostMapping("/control-points/{id}/deactivate")
    @RequirePermissions(Permissions.QUALITY_CONTROL__CONFIGURE)
    public Map<String, Object> deactivatePoint(@PathVariable String id,
            @Valid @RequestBody(required = false) StatusReason dto) {
        return points.setActive(id, false, dto != null ? dto.reason() : null);
    }
}
