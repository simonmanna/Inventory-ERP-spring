package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.ProcessingCommands.CreateJob;
import com.inventoryerp.manufacturing.application.ProcessingCommands.JobCostInput;
import com.inventoryerp.manufacturing.application.ProcessingCommands.JobReason;
import com.inventoryerp.manufacturing.application.ProcessingCommands.PostJob;
import com.inventoryerp.manufacturing.application.ProcessingCommands.RecordOutput;
import com.inventoryerp.manufacturing.application.ProcessingCommands.RouteBody;
import com.inventoryerp.manufacturing.application.ProcessingCommands.TemplateBody;
import com.inventoryerp.manufacturing.application.ProcessingJobService;
import com.inventoryerp.manufacturing.application.ProcessingReportService;
import com.inventoryerp.manufacturing.application.ProcessingTemplateService;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Multi-stage processing (ADR-S060): versioned templates, jobs (one job = one step = one production order), optional
 * routes, stock by stage and lot, genealogy and stage margin. Routes that move stock or post journals require an
 * {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/manufacturing/processing")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.processing", havingValue = "true")
public class ProcessingController {

    private final ProcessingTemplateService templates;
    private final ProcessingJobService jobs;
    private final ProcessingReportService reports;

    public ProcessingController(ProcessingTemplateService templates, ProcessingJobService jobs,
            ProcessingReportService reports) {
        this.templates = templates;
        this.jobs = jobs;
        this.reports = reports;
    }

    // ------------------------------------------------------------------ templates

    @GetMapping("/templates")
    @RequirePermissions(Permissions.PROCESSING__READ)
    public List<Map<String, Object>> templates(@RequestParam(required = false) String status,
            @RequestParam(required = false) String inputProductId) {
        return templates.list(status, inputProductId);
    }

    @GetMapping("/templates/{id}")
    @RequirePermissions(Permissions.PROCESSING__READ)
    public Map<String, Object> template(@PathVariable String id) {
        return templates.get(id);
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCESSING__CREATE)
    public Map<String, Object> createTemplate(@Valid @RequestBody TemplateBody dto) {
        return templates.create(dto);
    }

    @PutMapping("/templates/{id}")
    @RequirePermissions(Permissions.PROCESSING__CREATE)
    public Map<String, Object> updateTemplate(@PathVariable String id, @Valid @RequestBody TemplateBody dto) {
        return templates.update(id, dto);
    }

    @PostMapping("/templates/{id}/new-version")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCESSING__CREATE)
    public Map<String, Object> newVersion(@PathVariable String id) {
        return templates.newVersion(id);
    }

    @PostMapping("/templates/{id}/activate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCESSING__CREATE)
    public Map<String, Object> activate(@PathVariable String id) {
        return templates.activate(id);
    }

    @PostMapping("/templates/{id}/archive")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCESSING__CREATE)
    public Map<String, Object> archive(@PathVariable String id) {
        return templates.archive(id);
    }

    // ------------------------------------------------------------------ jobs

    @GetMapping("/jobs")
    @RequirePermissions(Permissions.PROCESSING__READ)
    public PageResult<Map<String, Object>> jobs(@RequestParam(required = false) String status,
            @RequestParam(required = false) String templateId, @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return jobs.list(status, templateId, search, page, pageSize);
    }

    @GetMapping("/jobs/{id}")
    @RequirePermissions(Permissions.PROCESSING__READ)
    public Map<String, Object> job(@PathVariable String id) {
        return jobs.get(id);
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCESSING__POST)
    @Idempotent(required = true)
    public Map<String, Object> createJob(@Valid @RequestBody CreateJob dto) {
        return jobs.create(dto);
    }

    @PostMapping("/jobs/post")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCESSING__POST)
    @Idempotent(required = true)
    public Map<String, Object> postJob(@Valid @RequestBody PostJob dto) {
        return jobs.post(dto);
    }

    @PostMapping("/jobs/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCESSING__POST)
    public Map<String, Object> cancel(@PathVariable String id, @RequestBody(required = false) JobReason dto) {
        return jobs.cancel(id, dto == null ? null : dto.reason());
    }

    @PostMapping("/jobs/{id}/start")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCESSING__POST)
    @Idempotent(required = true)
    public Map<String, Object> start(@PathVariable String id) {
        return jobs.start(id);
    }

    @PostMapping("/jobs/{id}/costs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCESSING__POST)
    @Idempotent(required = true)
    public Map<String, Object> cost(@PathVariable String id, @Valid @RequestBody JobCostInput dto) {
        return jobs.addCost(id, dto);
    }

    @PostMapping("/jobs/{id}/output")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCESSING__POST)
    @Idempotent(required = true)
    public Map<String, Object> output(@PathVariable String id, @Valid @RequestBody RecordOutput dto) {
        return jobs.recordOutput(id, dto);
    }

    @PostMapping("/jobs/{id}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCESSING__REVERSE)
    @Idempotent(required = true)
    public Map<String, Object> reverse(@PathVariable String id, @RequestBody(required = false) JobReason dto) {
        return jobs.reverse(id, dto == null ? null : dto.reason());
    }

    // ------------------------------------------------------------------ routes

    @GetMapping("/routes")
    @RequirePermissions(Permissions.PROCESSING__READ)
    public List<Map<String, Object>> routes() {
        return templates.routes();
    }

    @PostMapping("/routes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCESSING__CREATE)
    public Map<String, Object> createRoute(@Valid @RequestBody RouteBody dto) {
        return templates.saveRoute(null, dto);
    }

    @PutMapping("/routes/{id}")
    @RequirePermissions(Permissions.PROCESSING__CREATE)
    public Map<String, Object> updateRoute(@PathVariable String id, @Valid @RequestBody RouteBody dto) {
        return templates.saveRoute(id, dto);
    }

    @DeleteMapping("/routes/{id}")
    @RequirePermissions(Permissions.PROCESSING__CREATE)
    public Map<String, Object> deleteRoute(@PathVariable String id) {
        templates.deleteRoute(id);
        return Map.of("deleted", true);
    }

    // ------------------------------------------------------------------ reports

    @GetMapping("/lots")
    @RequirePermissions(Permissions.PROCESSING__READ)
    public List<Map<String, Object>> lots(@RequestParam(required = false) String productId,
            @RequestParam(required = false) String search) {
        return reports.lots(productId, search);
    }

    @GetMapping("/wip")
    @RequirePermissions(Permissions.PROCESSING__READ)
    public List<Map<String, Object>> wip() {
        return reports.wip();
    }

    @GetMapping("/genealogy")
    @RequirePermissions(Permissions.PROCESSING__READ)
    public Map<String, Object> genealogy(@RequestParam String productId, @RequestParam String batchNumber,
            @RequestParam(required = false) String direction) {
        return reports.genealogy(productId, batchNumber, direction);
    }

    @GetMapping("/stage-margin")
    @RequirePermissions(Permissions.PROCESSING__READ)
    public List<Map<String, Object>> stageMargin(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return reports.stageMargin(from, to);
    }

    @GetMapping("/dashboard")
    @RequirePermissions(Permissions.PROCESSING__READ)
    public Map<String, Object> dashboard(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return reports.dashboard(from, to);
    }
}
