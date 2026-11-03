package com.inventoryerp.helpdesk.web;

import com.inventoryerp.helpdesk.application.HelpdeskConfigService;
import com.inventoryerp.helpdesk.application.HelpdeskConfigService.TeamInput;
import com.inventoryerp.helpdesk.application.HelpdeskConfigService.TemplateInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /helpdesk/teams|stages|types|letter-templates} (ADR-S045). */
@RestController
@RequestMapping("/helpdesk")
public class HelpdeskConfigController {

    public record TeamRequest(
            @Size(max = 40, message = "code must be at most 40 characters") String code,
            @NotBlank(message = "name should not be empty")
            @Size(max = 120, message = "name must be at most 120 characters") String name,
            String description,
            String email,
            String assignment,
            String leaderId,
            List<String> memberIds) {}

    public record TeamPatch(
            @Size(max = 120, message = "name must be at most 120 characters") String name,
            String description,
            String email,
            String assignment,
            String leaderId,
            List<String> memberIds,
            Boolean isActive) {}

    public record StageRequest(
            @NotBlank(message = "name should not be empty")
            @Size(max = 60, message = "name must be at most 60 characters") String name,
            Integer sequence,
            Boolean isClosed,
            Boolean folded) {}

    public record StagePatch(@Size(max = 60, message = "name must be at most 60 characters") String name,
            Integer sequence, Boolean isClosed, Boolean folded) {}

    public record TypeRequest(@NotBlank(message = "name should not be empty")
            @Size(max = 60, message = "name must be at most 60 characters") String name) {}

    public record ActiveRequest(@NotNull(message = "isActive should not be empty") Boolean isActive) {}

    public record TemplateRequest(
            @Size(max = 40, message = "code must be at most 40 characters") String code,
            @NotBlank(message = "name should not be empty") String name,
            String subject,
            @NotBlank(message = "body should not be empty") String body,
            String closingLine,
            String signatureName,
            String signatureTitle) {}

    public record TemplatePatch(String name, String subject, String body, String closingLine, String signatureName,
            String signatureTitle, Boolean isActive) {}

    private final HelpdeskConfigService config;

    public HelpdeskConfigController(HelpdeskConfigService config) {
        this.config = config;
    }

    @GetMapping("/teams")
    @RequirePermissions(Permissions.HELPDESK__TICKET_READ)
    public List<Map<String, Object>> teams() {
        return config.teams();
    }

    @GetMapping("/teams/{id}")
    @RequirePermissions(Permissions.HELPDESK__TICKET_READ)
    public Map<String, Object> team(@PathVariable String id) {
        return config.getTeam(id);
    }

    @PostMapping("/teams")
    @RequirePermissions(Permissions.HELPDESK__TEAM_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createTeam(@Valid @RequestBody TeamRequest r) {
        return config.createTeam(new TeamInput(r.code(), r.name(), r.description(), r.email(), r.assignment(),
                r.leaderId(), r.memberIds(), null));
    }

    @PatchMapping("/teams/{id}")
    @RequirePermissions(Permissions.HELPDESK__TEAM_MANAGE)
    public Map<String, Object> updateTeam(@PathVariable String id, @Valid @RequestBody TeamPatch r) {
        return config.updateTeam(id, new TeamInput(null, r.name(), r.description(), r.email(), r.assignment(),
                r.leaderId(), r.memberIds(), r.isActive()));
    }

    @PostMapping("/teams/{id}/stages")
    @RequirePermissions(Permissions.HELPDESK__TEAM_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createStage(@PathVariable String id, @Valid @RequestBody StageRequest r) {
        return config.createStage(id, r.name(), r.sequence(), r.isClosed(), r.folded());
    }

    @PatchMapping("/stages/{id}")
    @RequirePermissions(Permissions.HELPDESK__TEAM_MANAGE)
    public Map<String, Object> updateStage(@PathVariable String id, @Valid @RequestBody StagePatch r) {
        return config.updateStage(id, r.name(), r.sequence(), r.isClosed(), r.folded());
    }

    @DeleteMapping("/stages/{id}")
    @RequirePermissions(Permissions.HELPDESK__TEAM_MANAGE)
    public Map<String, Object> deleteStage(@PathVariable String id) {
        config.deleteStage(id);
        return Map.of("deleted", true);
    }

    @GetMapping("/types")
    @RequirePermissions(Permissions.HELPDESK__TICKET_READ)
    public List<Map<String, Object>> types() {
        return config.types();
    }

    @PostMapping("/types")
    @RequirePermissions(Permissions.HELPDESK__TEAM_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createType(@Valid @RequestBody TypeRequest r) {
        return config.createType(r.name());
    }

    @PatchMapping("/types/{id}")
    @RequirePermissions(Permissions.HELPDESK__TEAM_MANAGE)
    public Map<String, Object> setTypeActive(@PathVariable String id, @Valid @RequestBody ActiveRequest r) {
        return config.setTypeActive(id, r.isActive());
    }

    @GetMapping("/letter-templates")
    @RequirePermissions(Permissions.HELPDESK__TICKET_READ)
    public List<Map<String, Object>> templates() {
        return config.templates();
    }

    @PostMapping("/letter-templates")
    @RequirePermissions(Permissions.HELPDESK__TEAM_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createTemplate(@Valid @RequestBody TemplateRequest r) {
        return config.createTemplate(new TemplateInput(r.code(), r.name(), r.subject(), r.body(), r.closingLine(),
                r.signatureName(), r.signatureTitle(), null));
    }

    @PatchMapping("/letter-templates/{id}")
    @RequirePermissions(Permissions.HELPDESK__TEAM_MANAGE)
    public Map<String, Object> updateTemplate(@PathVariable String id, @Valid @RequestBody TemplatePatch r) {
        return config.updateTemplate(id, new TemplateInput(null, r.name(), r.subject(), r.body(), r.closingLine(),
                r.signatureName(), r.signatureTitle(), r.isActive()));
    }
}
