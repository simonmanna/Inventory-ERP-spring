package com.inventoryerp.reporting.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.reporting.application.StatementLayoutService;
import com.inventoryerp.reporting.application.StatementLayoutService.Layout;
import com.inventoryerp.reporting.application.StatementLayoutService.Line;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /reports/fs-layouts} (FR-0; extension): financial statement layouts — which accounts feed which statement
 * line, and the subtotal formulas. Editing needs {@code report_library:manage}.
 */
@RestController
@RequestMapping("/reports/fs-layouts")
public class StatementLayoutController {

    public record CreateLayoutRequest(
            @NotBlank(message = "code should not be empty") String code,
            @NotBlank(message = "name should not be empty")
            @Size(max = 160, message = "name must be at most 160 characters") String name,
            String kind,
            String copyFrom) {}

    public record UpdateLayoutRequest(
            @Size(max = 160, message = "name must be at most 160 characters") String name,
            @NotEmpty(message = "lines should not be empty") @Size(max = 300, message = "lines must have at most 300"
                    + " entries") List<Line> lines) {}

    private final StatementLayoutService layouts;

    public StatementLayoutController(StatementLayoutService layouts) {
        this.layouts = layouts;
    }

    @GetMapping
    @RequirePermissions(Permissions.REPORT_LIBRARY__READ)
    public List<Map<String, Object>> list() {
        return layouts.list();
    }

    @GetMapping("/{code}")
    @RequirePermissions(Permissions.REPORT_LIBRARY__READ)
    public Layout get(@PathVariable String code) {
        return layouts.get(code);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPORT_LIBRARY__MANAGE)
    public Layout create(@Valid @RequestBody CreateLayoutRequest r) {
        return layouts.create(r.code(), r.name(), r.kind(), r.copyFrom());
    }

    @PutMapping("/{code}")
    @RequirePermissions(Permissions.REPORT_LIBRARY__MANAGE)
    public Layout update(@PathVariable String code, @Valid @RequestBody UpdateLayoutRequest r) {
        return layouts.update(code, r.name(), r.lines());
    }

    @PostMapping("/{code}/reset")
    @RequirePermissions(Permissions.REPORT_LIBRARY__MANAGE)
    public Layout reset(@PathVariable String code) {
        return layouts.reset(code);
    }

    @DeleteMapping("/{code}")
    @RequirePermissions(Permissions.REPORT_LIBRARY__MANAGE)
    public Map<String, Object> delete(@PathVariable String code) {
        layouts.delete(code);
        return Map.of("deleted", true);
    }
}
