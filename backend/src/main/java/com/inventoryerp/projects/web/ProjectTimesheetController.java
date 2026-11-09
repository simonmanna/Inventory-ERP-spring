package com.inventoryerp.projects.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.projects.application.ProjectInsightsService;
import com.inventoryerp.projects.application.ProjectTimesheetService;
import com.inventoryerp.projects.application.ProjectTimesheetService.LogInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project time and reporting (ADR-S062; extension): {@code /project-timesheets} (lines, weekly grid, timer) and
 * {@code /project-reports} (dashboard, task / timesheet analysis, workload, Gantt).
 */
@RestController
public class ProjectTimesheetController {

    public record LogRequest(String projectId, String taskId, String userId, String date,
            @NotNull(message = "hours should not be empty") BigDecimal hours,
            @Size(max = 2000, message = "description must be at most 2000 characters") String description,
            Boolean isBillable) {}

    public record CellRequest(String projectId, String taskId, @NotBlank(message = "date should not be empty")
            String date, BigDecimal hours, String userId) {}

    public record TimerStartRequest(@NotBlank(message = "taskId should not be empty") String taskId) {}

    public record TimerStopRequest(@Size(max = 2000, message = "description must be at most 2000 characters")
            String description) {}

    private final ProjectTimesheetService time;
    private final ProjectInsightsService insights;

    public ProjectTimesheetController(ProjectTimesheetService time, ProjectInsightsService insights) {
        this.time = time;
        this.insights = insights;
    }

    @GetMapping("/project-timesheets")
    @RequirePermissions(Permissions.PROJECT_TIMESHEET__LOG)
    public List<Map<String, Object>> list(@RequestParam(required = false) String projectId,
            @RequestParam(required = false) String taskId, @RequestParam(required = false) String userId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return time.list(projectId, taskId, userId, from, to);
    }

    @PostMapping("/project-timesheets")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT_TIMESHEET__LOG)
    public Map<String, Object> log(@Valid @RequestBody LogRequest r) {
        return time.log(new LogInput(r.projectId(), r.taskId(), r.userId(), r.date(), r.hours(), r.description(),
                r.isBillable()));
    }

    @GetMapping("/project-timesheets/week")
    @RequirePermissions(Permissions.PROJECT_TIMESHEET__LOG)
    public Map<String, Object> week(@RequestParam(required = false) String start,
            @RequestParam(required = false) String userId) {
        return time.week(start, userId);
    }

    @PutMapping("/project-timesheets/week")
    @RequirePermissions(Permissions.PROJECT_TIMESHEET__LOG)
    public Map<String, Object> setCell(@Valid @RequestBody CellRequest r) {
        return time.setCell(r.projectId(), r.taskId(), r.date(), r.hours(), r.userId());
    }

    @GetMapping("/project-timesheets/timer")
    @RequirePermissions(Permissions.PROJECT_TIMESHEET__LOG)
    public Map<String, Object> timer() {
        return time.timer();
    }

    @PostMapping("/project-timesheets/timer/start")
    @RequirePermissions(Permissions.PROJECT_TIMESHEET__LOG)
    public Map<String, Object> start(@Valid @RequestBody TimerStartRequest r) {
        return time.startTimer(r.taskId());
    }

    @PostMapping("/project-timesheets/timer/stop")
    @RequirePermissions(Permissions.PROJECT_TIMESHEET__LOG)
    public Map<String, Object> stop(@Valid @RequestBody TimerStopRequest r) {
        return time.stopTimer(r.description());
    }

    @PostMapping("/project-timesheets/timer/discard")
    @RequirePermissions(Permissions.PROJECT_TIMESHEET__LOG)
    public Map<String, Object> discard() {
        return time.discardTimer();
    }

    @PatchMapping("/project-timesheets/{id}")
    @RequirePermissions(Permissions.PROJECT_TIMESHEET__LOG)
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return time.update(id, body);
    }

    @DeleteMapping("/project-timesheets/{id}")
    @RequirePermissions(Permissions.PROJECT_TIMESHEET__LOG)
    public Map<String, Object> delete(@PathVariable String id) {
        return time.delete(id);
    }

    @GetMapping("/project-reports/dashboard")
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public Map<String, Object> dashboard() {
        return insights.dashboard();
    }

    @GetMapping("/project-reports/tasks")
    @RequirePermissions(Permissions.PROJECT_REPORT__READ)
    public List<Map<String, Object>> taskAnalysis(@RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String projectId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return insights.taskAnalysis(groupBy, projectId, from, to);
    }

    @GetMapping("/project-reports/timesheets")
    @RequirePermissions(Permissions.PROJECT_REPORT__READ)
    public List<Map<String, Object>> timesheetAnalysis(@RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String projectId, @RequestParam(required = false) String userId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return insights.timesheetAnalysis(groupBy, projectId, userId, from, to);
    }

    @GetMapping("/project-reports/workload")
    @RequirePermissions(Permissions.PROJECT_REPORT__READ)
    public List<Map<String, Object>> workload(@RequestParam(required = false) String projectId) {
        return insights.workload(projectId);
    }

    @GetMapping("/project-reports/gantt")
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public Map<String, Object> gantt(@RequestParam(required = false) String projectId) {
        return insights.gantt(projectId);
    }
}
