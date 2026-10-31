package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.CrushingCommands.ReasonInput;
import com.inventoryerp.manufacturing.application.CrushingCommands.RunInput;
import com.inventoryerp.manufacturing.application.CrushingCommands.SurveyInput;
import com.inventoryerp.manufacturing.application.CrushingReportService;
import com.inventoryerp.manufacturing.application.CrushingRunService;
import com.inventoryerp.manufacturing.application.CrushingSurveyService;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Crushing &amp; screening operations (Track C 2.3, ADR-S016): crushing runs by shift (draft entry from the plant
 * operator's phone / tablet, post through the multi-stage engine, reverse), finished-aggregate stockpiles, stockpile
 * surveys with approval (adjustment + GL) and the yield, cost-per-tonne and plant-hours reports. Stock / GL-moving
 * routes require an {@code Idempotency-Key}; creating a run accepts one (a phone that retries never doubles it).
 */
@RestController
@RequestMapping("/manufacturing/crushing")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class CrushingOperationsController {

    private final CrushingRunService runs;
    private final CrushingSurveyService surveys;
    private final CrushingReportService reports;

    public CrushingOperationsController(CrushingRunService runs, CrushingSurveyService surveys,
            CrushingReportService reports) {
        this.runs = runs;
        this.surveys = surveys;
        this.reports = reports;
    }

    // ------------------------------------------------------------------ runs

    @GetMapping("/runs")
    @RequirePermissions(Permissions.CRUSHING__READ)
    public PageResult<Map<String, Object>> runs(@RequestParam(required = false) String plantId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String shift,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String search, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return runs.list(plantId, status, shift, from, to, search, page, pageSize);
    }

    @PostMapping("/runs")
    @Idempotent
    @RequirePermissions(Permissions.CRUSHING_RUN__RECORD)
    public Map<String, Object> createRun(@Valid @RequestBody RunInput dto) {
        return runs.create(dto);
    }

    @GetMapping("/runs/{id}")
    @RequirePermissions(Permissions.CRUSHING__READ)
    public Map<String, Object> run(@PathVariable String id) {
        return runs.get(id);
    }

    @PutMapping("/runs/{id}")
    @RequirePermissions(Permissions.CRUSHING_RUN__RECORD)
    public Map<String, Object> updateRun(@PathVariable String id, @Valid @RequestBody RunInput dto) {
        return runs.update(id, dto);
    }

    @DeleteMapping("/runs/{id}")
    @RequirePermissions(Permissions.CRUSHING_RUN__RECORD)
    public Map<String, Object> deleteRun(@PathVariable String id) {
        runs.remove(id);
        return Map.of("id", id, "deleted", true);
    }

    @GetMapping("/runs/{id}/preview")
    @RequirePermissions(Permissions.CRUSHING__READ)
    public Map<String, Object> preview(@PathVariable String id) {
        return runs.preview(id);
    }

    @PostMapping("/runs/{id}/post")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CRUSHING_RUN__POST)
    public Map<String, Object> post(@PathVariable String id) {
        return runs.post(id);
    }

    @PostMapping("/runs/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CRUSHING_RUN__POST)
    public Map<String, Object> reverse(@PathVariable String id, @Valid @RequestBody ReasonInput dto) {
        return runs.reverse(id, dto.reason().trim());
    }

    // ------------------------------------------------------------------ stockpiles & surveys

    @GetMapping("/stockpiles")
    @RequirePermissions(Permissions.CRUSHING__READ)
    public List<Map<String, Object>> stockpiles(@RequestParam(required = false) String kind) {
        return reports.stockpiles(kind);
    }

    @GetMapping("/stockpile-surveys")
    @RequirePermissions(Permissions.CRUSHING__READ)
    public PageResult<Map<String, Object>> surveys(@RequestParam(required = false) String stockpileId,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return surveys.list(stockpileId, status, page, pageSize);
    }

    @PostMapping("/stockpile-surveys")
    @Idempotent
    @RequirePermissions(Permissions.CRUSHING_SURVEY__RECORD)
    public Map<String, Object> createSurvey(@Valid @RequestBody SurveyInput dto) {
        return surveys.create(dto);
    }

    @GetMapping("/stockpile-surveys/{id}")
    @RequirePermissions(Permissions.CRUSHING__READ)
    public Map<String, Object> survey(@PathVariable String id) {
        return surveys.get(id);
    }

    @PutMapping("/stockpile-surveys/{id}")
    @RequirePermissions(Permissions.CRUSHING_SURVEY__RECORD)
    public Map<String, Object> updateSurvey(@PathVariable String id, @Valid @RequestBody SurveyInput dto) {
        return surveys.update(id, dto);
    }

    @DeleteMapping("/stockpile-surveys/{id}")
    @RequirePermissions(Permissions.CRUSHING_SURVEY__RECORD)
    public Map<String, Object> deleteSurvey(@PathVariable String id) {
        surveys.remove(id);
        return Map.of("id", id, "deleted", true);
    }

    @PostMapping("/stockpile-surveys/{id}/submit")
    @RequirePermissions(Permissions.CRUSHING_SURVEY__RECORD)
    public Map<String, Object> submit(@PathVariable String id) {
        return surveys.submit(id);
    }

    @PostMapping("/stockpile-surveys/{id}/approve")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CRUSHING_SURVEY__APPROVE)
    public Map<String, Object> approve(@PathVariable String id) {
        return surveys.approve(id);
    }

    @PostMapping("/stockpile-surveys/{id}/reject")
    @RequirePermissions(Permissions.CRUSHING_SURVEY__APPROVE)
    public Map<String, Object> reject(@PathVariable String id, @Valid @RequestBody ReasonInput dto) {
        return surveys.reject(id, dto.reason().trim());
    }

    @PostMapping("/stockpile-surveys/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CRUSHING_SURVEY__APPROVE)
    public Map<String, Object> reverseSurvey(@PathVariable String id, @Valid @RequestBody ReasonInput dto) {
        return surveys.reverse(id, dto.reason().trim());
    }

    // ------------------------------------------------------------------ reports

    @GetMapping("/reports/yield")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public Map<String, Object> yieldReport(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String plantId) {
        return reports.yieldReport(from, to, plantId);
    }

    @GetMapping("/reports/cost-per-tonne")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public Map<String, Object> costPerTonne(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String plantId) {
        return reports.costPerTonne(from, to, plantId);
    }

    @GetMapping("/reports/plant-hours")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public Map<String, Object> plantHours(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String plantId) {
        return reports.plantHours(from, to, plantId);
    }
}
