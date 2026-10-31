package com.inventoryerp.alerts.web;

import com.inventoryerp.alerts.application.AlertCommands.EventQuery;
import com.inventoryerp.alerts.application.AlertCommands.RuleInput;
import com.inventoryerp.alerts.application.AlertCommands.RunInput;
import com.inventoryerp.alerts.application.AlertEngine;
import com.inventoryerp.alerts.application.AlertEventService;
import com.inventoryerp.alerts.application.AlertRuleService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /alerts} (ADR-S021): settings per alert type, alerts raised, the exception report and a scan on demand. */
@RestController
@RequestMapping("/alerts")
@RequiresModule("alerts")
@ConditionalOnProperty(name = "app.modules.alerts", havingValue = "true")
public class AlertController {

    private final AlertRuleService rules;
    private final AlertEventService events;
    private final AlertEngine engine;

    public AlertController(AlertRuleService rules, AlertEventService events, AlertEngine engine) {
        this.rules = rules;
        this.events = events;
        this.engine = engine;
    }

    @GetMapping("/rules")
    @RequirePermissions(Permissions.ALERTS__READ)
    public List<Map<String, Object>> rules() {
        return rules.list();
    }

    @PutMapping("/rules/{type}")
    @RequirePermissions(Permissions.ALERTS__MANAGE)
    public Map<String, Object> saveRule(@PathVariable String type, @Valid @RequestBody RuleInput dto) {
        return rules.save(type, dto);
    }

    @GetMapping("/events")
    @RequirePermissions(Permissions.ALERTS__READ)
    public PageResult<Map<String, Object>> events(@RequestParam(required = false) String alertType,
            @RequestParam(required = false) String severity, @RequestParam(required = false) String status,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return events.list(new EventQuery(alertType, severity, status, from, to, page, pageSize));
    }

    @GetMapping("/events/export.csv")
    @RequirePermissions(Permissions.ALERTS__READ)
    public ResponseEntity<byte[]> exportEvents(@RequestParam(required = false) String alertType,
            @RequestParam(required = false) String severity, @RequestParam(required = false) String status,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=utf-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"alerts.csv\"")
                .body(events.exportCsv(new EventQuery(alertType, severity, status, from, to, null, null)));
    }

    @PostMapping("/events/{id}/acknowledge")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ALERTS__READ)
    public Map<String, Object> acknowledge(@PathVariable String id) {
        return events.acknowledge(id);
    }

    @GetMapping("/exception-report")
    @RequirePermissions(Permissions.ALERTS__READ)
    public Map<String, Object> exceptionReport(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return events.exceptionReport(new EventQuery(null, null, null, from, to, null, null));
    }

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ALERTS__MANAGE)
    public Map<String, Object> run(@Valid @RequestBody(required = false) RunInput dto) {
        return engine.run(null, dto == null ? null : dto.alertType());
    }
}
