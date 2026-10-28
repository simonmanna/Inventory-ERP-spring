package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.MfgCommands.CalendarInput;
import com.inventoryerp.manufacturing.application.MfgCommands.QcPlanInput;
import com.inventoryerp.manufacturing.application.MfgCommands.ReworkInput;
import com.inventoryerp.manufacturing.application.MfgCommands.ScheduleInput;
import com.inventoryerp.manufacturing.application.MfgCommands.ScrapReasonInput;
import com.inventoryerp.manufacturing.application.MfgCommands.TimeLogInput;
import com.inventoryerp.manufacturing.application.QualityService;
import com.inventoryerp.manufacturing.application.ShopFloorService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** Shop floor and quality (ADR-S006 package C): calendars, scheduling, time logs, rework, scrap reasons, QC plans. */
@RestController
@RequestMapping("/manufacturing")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class ShopFloorController {

    private final ShopFloorService shop;
    private final QualityService quality;

    public ShopFloorController(ShopFloorService shop, QualityService quality) {
        this.shop = shop;
        this.quality = quality;
    }

    @GetMapping("/work-centers/{id}/calendar")
    @RequirePermissions(Permissions.WORK_CENTER__READ)
    public Map<String, Object> calendar(@PathVariable String id) {
        return shop.calendar(id);
    }

    @PutMapping("/work-centers/{id}/calendar")
    @RequirePermissions(Permissions.WORK_CENTER__UPDATE)
    public Map<String, Object> saveCalendar(@PathVariable String id, @Valid @RequestBody CalendarInput dto) {
        return shop.saveCalendar(id, dto);
    }

    @PostMapping("/production-orders/{id}/schedule")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public List<Map<String, Object>> schedule(@PathVariable String id,
            @Valid @RequestBody(required = false) ScheduleInput dto) {
        return shop.schedule(id, dto == null ? null : dto.startAt());
    }

    @GetMapping("/schedule")
    @RequirePermissions(Permissions.WORK_ORDER__READ)
    public List<Map<String, Object>> scheduleView(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String workCenterId) {
        return shop.scheduleView(from, to, workCenterId);
    }

    @GetMapping("/work-orders/{id}/time-logs")
    @RequirePermissions(Permissions.WORK_ORDER__READ)
    public List<Map<String, Object>> timeLogs(@PathVariable String id) {
        return shop.timeLogs(id);
    }

    @PostMapping("/work-orders/{id}/time-logs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> addTimeLog(@PathVariable String id, @Valid @RequestBody TimeLogInput dto) {
        return shop.addTimeLog(id, dto);
    }

    @PostMapping("/production-orders/{id}/rework")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__QC)
    public Map<String, Object> rework(@PathVariable String id, @Valid @RequestBody(required = false) ReworkInput dto) {
        return shop.rework(id, dto);
    }

    @GetMapping("/production-orders/{id}/qc-plan")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__READ)
    public Map<String, Object> qcPlan(@PathVariable String id) {
        return quality.planForOrder(id);
    }

    @GetMapping("/scrap-reasons")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__READ)
    public List<Map<String, Object>> reasons() {
        return quality.listReasons();
    }

    @PostMapping("/scrap-reasons")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__QC)
    public Map<String, Object> createReason(@Valid @RequestBody ScrapReasonInput dto) {
        return quality.createReason(dto);
    }

    @PatchMapping("/scrap-reasons/{id}")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__QC)
    public Map<String, Object> updateReason(@PathVariable String id, @Valid @RequestBody ScrapReasonInput dto) {
        return quality.updateReason(id, dto);
    }

    @DeleteMapping("/scrap-reasons/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__QC)
    public void removeReason(@PathVariable String id) {
        quality.removeReason(id);
    }

    @GetMapping("/qc-plans")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__READ)
    public List<Map<String, Object>> plans(@RequestParam(required = false) String productId) {
        return quality.listPlans(productId);
    }

    @PostMapping("/qc-plans")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__QC)
    public Map<String, Object> createPlan(@Valid @RequestBody QcPlanInput dto) {
        return quality.createPlan(dto);
    }

    @PatchMapping("/qc-plans/{id}")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__QC)
    public Map<String, Object> updatePlan(@PathVariable String id, @Valid @RequestBody QcPlanInput dto) {
        return quality.updatePlan(id, dto);
    }

    @DeleteMapping("/qc-plans/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__QC)
    public void removePlan(@PathVariable String id) {
        quality.removePlan(id);
    }
}
