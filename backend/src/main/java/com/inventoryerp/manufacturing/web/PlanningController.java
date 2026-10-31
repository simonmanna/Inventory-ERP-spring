package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.ForecastService;
import com.inventoryerp.manufacturing.application.PlanningCapacityService;
import com.inventoryerp.manufacturing.application.PlanningCommands.BackwardInput;
import com.inventoryerp.manufacturing.application.PlanningCommands.OrderPlanningInput;
import com.inventoryerp.manufacturing.application.PlanningCommands.PlanFromDemand;
import com.inventoryerp.manufacturing.application.PlanningCommands.RescheduleInput;
import com.inventoryerp.manufacturing.application.PlanningCommands.ScheduleOpenInput;
import com.inventoryerp.manufacturing.application.PlanningCommands.ShiftPlanInput;
import com.inventoryerp.manufacturing.application.PlanningCommands.TargetInput;
import com.inventoryerp.manufacturing.application.PlanningDemandService;
import com.inventoryerp.manufacturing.application.PlanningPegService;
import com.inventoryerp.manufacturing.application.PlanningReportService;
import com.inventoryerp.manufacturing.application.PlanningScheduleService;
import com.inventoryerp.manufacturing.application.PlanningShiftService;
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
 * Advanced planning &amp; scheduling (Track C 2.5, ADR-S017): demand and pegging, plans from demand, order priorities,
 * backward scheduling, the Gantt board, capacity and bottlenecks, the aggregate crushing plan, shift plans, targets and
 * the plan performance reports.
 */
@RestController
@RequestMapping("/manufacturing/planning")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class PlanningController {

    private final PlanningDemandService demand;
    private final PlanningPegService pegs;
    private final PlanningScheduleService schedule;
    private final PlanningCapacityService capacity;
    private final PlanningShiftService shifts;
    private final PlanningReportService reports;
    private final ForecastService forecasts;

    public PlanningController(PlanningDemandService demand, PlanningPegService pegs, PlanningScheduleService schedule,
            PlanningCapacityService capacity, PlanningShiftService shifts, PlanningReportService reports,
            ForecastService forecasts) {
        this.forecasts = forecasts;
        this.demand = demand;
        this.pegs = pegs;
        this.schedule = schedule;
        this.capacity = capacity;
        this.shifts = shifts;
        this.reports = reports;
    }

    // ------------------------------------------------------------------ demand & pegging

    @GetMapping("/demand")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
    public Map<String, Object> demand(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Boolean includeForecast,
            @RequestParam(required = false) Integer forecastWeeks,
            @RequestParam(required = false) Boolean includeStockPolicy,
            @RequestParam(required = false) String forecastMethod) {
        return demand.demand(from, to, includeForecast, forecastWeeks, includeStockPolicy, forecastMethod);
    }

    /** Smoothed demand per product for a horizon; {@code family}: aggregate or block. Plans nothing by itself. */
    @GetMapping("/forecast")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
    public Map<String, Object> forecast(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Integer weeks,
            @RequestParam(required = false) String family) {
        return forecasts.demandForecast(from, to, weeks, family);
    }

    @PostMapping("/plans/from-demand")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_PLAN__CREATE)
    public Map<String, Object> planFromDemand(@Valid @RequestBody PlanFromDemand dto) {
        return demand.planFromDemand(dto);
    }

    @GetMapping("/pegs")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
    public List<Map<String, Object>> pegs(@RequestParam(required = false) String productionOrderId,
            @RequestParam(required = false) String planId, @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String productId) {
        return pegs.pegs(productionOrderId, planId, partnerId, productId);
    }

    // ------------------------------------------------------------------ priorities & scheduling

    @GetMapping("/orders")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
    public List<Map<String, Object>> board(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset) {
        return schedule.board(status, limit, offset);
    }

    @PutMapping("/orders/{id}")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__UPDATE)
    public Map<String, Object> saveOrder(@PathVariable String id, @Valid @RequestBody OrderPlanningInput dto) {
        return schedule.saveOrderPlanning(id, dto);
    }

    @PostMapping("/orders/{id}/schedule-backward")
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> scheduleBackward(@PathVariable String id,
            @Valid @RequestBody(required = false) BackwardInput dto) {
        return schedule.scheduleBackward(id, dto);
    }

    @PostMapping("/schedule-open-orders")
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> scheduleOpenOrders(@Valid @RequestBody(required = false) ScheduleOpenInput dto) {
        return schedule.scheduleOpenOrders(dto);
    }

    @PostMapping("/work-orders/{id}/reschedule")
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> reschedule(@PathVariable String id, @Valid @RequestBody RescheduleInput dto) {
        return schedule.reschedule(id, dto);
    }

    @GetMapping("/gantt")
    @RequirePermissions(Permissions.WORK_ORDER__READ)
    public Map<String, Object> gantt(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String workCenterId) {
        return schedule.gantt(from, to, workCenterId);
    }

    // ------------------------------------------------------------------ capacity

    @GetMapping("/capacity")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
    public Map<String, Object> capacity(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String bucket,
            @RequestParam(required = false) String workCenterId) {
        return capacity.capacity(from, to, bucket, workCenterId);
    }

    @GetMapping("/aggregates")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
    public Map<String, Object> aggregates(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String plantId) {
        return capacity.aggregates(from, to, plantId);
    }

    // ------------------------------------------------------------------ shift plans

    @GetMapping("/shift-plans")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
    public PageResult<Map<String, Object>> shiftPlans(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String workCenterId,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return shifts.listShiftPlans(from, to, workCenterId, status, page, pageSize);
    }

    @PostMapping("/shift-plans")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_PLAN__CREATE)
    public Map<String, Object> createShiftPlan(@Valid @RequestBody ShiftPlanInput dto) {
        return shifts.createShiftPlan(dto);
    }

    @PutMapping("/shift-plans/{id}")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__UPDATE)
    public Map<String, Object> updateShiftPlan(@PathVariable String id, @Valid @RequestBody ShiftPlanInput dto) {
        return shifts.updateShiftPlan(id, dto);
    }

    @DeleteMapping("/shift-plans/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.PRODUCTION_PLAN__UPDATE)
    public void deleteShiftPlan(@PathVariable String id) {
        shifts.deleteShiftPlan(id);
    }

    @PostMapping("/shift-plans/{id}/publish")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__CONFIRM)
    public Map<String, Object> publishShiftPlan(@PathVariable String id) {
        return shifts.publishShiftPlan(id);
    }

    // ------------------------------------------------------------------ targets

    @GetMapping("/targets")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
    public List<Map<String, Object>> targets(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String scope) {
        return shifts.listTargets(from, to, scope);
    }

    @PostMapping("/targets")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_PLAN__CREATE)
    public Map<String, Object> createTarget(@Valid @RequestBody TargetInput dto) {
        return shifts.createTarget(dto);
    }

    @PutMapping("/targets/{id}")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__UPDATE)
    public Map<String, Object> updateTarget(@PathVariable String id, @Valid @RequestBody TargetInput dto) {
        return shifts.updateTarget(id, dto);
    }

    @DeleteMapping("/targets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.PRODUCTION_PLAN__UPDATE)
    public void deleteTarget(@PathVariable String id) {
        shifts.deleteTarget(id);
    }

    // ------------------------------------------------------------------ reports

    @GetMapping("/reports/shortfall")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public Map<String, Object> shortfall(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Boolean includeForecast) {
        return demand.shortfall(from, to, includeForecast);
    }

    @GetMapping("/reports/production-vs-plan")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public Map<String, Object> productionVsPlan(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String planId) {
        return reports.productionVsPlan(from, to, planId);
    }

    @GetMapping("/reports/plan-adherence")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public Map<String, Object> planAdherence(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return reports.planAdherence(from, to);
    }

    @GetMapping("/reports/production-vs-target")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public Map<String, Object> productionVsTarget(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return reports.productionVsTarget(from, to);
    }
}
