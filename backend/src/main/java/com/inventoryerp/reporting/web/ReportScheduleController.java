package com.inventoryerp.reporting.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.reporting.application.ReportScheduleService;
import com.inventoryerp.reporting.application.ReportScheduleService.ScheduleInput;
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

/** {@code /reports/schedules} (FR-0; extension): scheduled report delivery by e-mail and in-app. */
@RestController
@RequestMapping("/reports/schedules")
@RequirePermissions(Permissions.REPORT_LIBRARY__SCHEDULE)
public class ReportScheduleController {

    public record ScheduleRequest(
            @NotBlank(message = "reportId should not be empty") String reportId,
            @NotBlank(message = "name should not be empty")
            @Size(max = 120, message = "name must be at most 120 characters") String name,
            Map<String, Object> params,
            @NotBlank(message = "format should not be empty") String format,
            @NotBlank(message = "frequency should not be empty") String frequency,
            Integer dayOfWeek,
            Integer dayOfMonth,
            Integer hourOfDay,
            List<String> recipients) {}

    public record ActiveRequest(@NotNull(message = "isActive should not be empty") Boolean isActive) {}

    private final ReportScheduleService schedules;

    public ReportScheduleController(ReportScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return schedules.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody ScheduleRequest r) {
        return schedules.create(new ScheduleInput(r.reportId(), r.name(), r.params(), r.format(), r.frequency(),
                r.dayOfWeek(), r.dayOfMonth(), r.hourOfDay(), r.recipients()));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> setActive(@PathVariable String id, @Valid @RequestBody ActiveRequest r) {
        return schedules.setActive(id, r.isActive());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        schedules.delete(id);
        return Map.of("deleted", true);
    }

    @PostMapping("/{id}/run")
    public Map<String, Object> runNow(@PathVariable String id) {
        return schedules.runNow(id);
    }
}
