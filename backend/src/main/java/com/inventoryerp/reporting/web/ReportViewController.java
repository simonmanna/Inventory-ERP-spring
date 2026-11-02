package com.inventoryerp.reporting.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.reporting.application.SavedViewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /reports/views} (FR-0; extension): saved report filters, private or shared. */
@RestController
@RequestMapping("/reports/views")
public class ReportViewController {

    public record ViewRequest(
            @NotBlank(message = "reportId should not be empty") String reportId,
            @NotBlank(message = "name should not be empty")
            @Size(max = 120, message = "name must be at most 120 characters") String name,
            Map<String, Object> params,
            Boolean shared) {}

    private final SavedViewService views;

    public ReportViewController(SavedViewService views) {
        this.views = views;
    }

    @GetMapping
    @RequirePermissions(Permissions.REPORT_LIBRARY__READ)
    public List<Map<String, Object>> list(@RequestParam String reportId) {
        return views.list(reportId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPORT_LIBRARY__READ)
    public Map<String, Object> create(@Valid @RequestBody ViewRequest r) {
        return views.create(r.reportId(), r.name(), r.params(), Boolean.TRUE.equals(r.shared()));
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.REPORT_LIBRARY__READ)
    public Map<String, Object> delete(@PathVariable String id) {
        views.delete(id);
        return Map.of("deleted", true);
    }
}
