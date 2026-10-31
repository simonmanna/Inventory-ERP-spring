package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.MachineDataService;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ClockOffInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ClockOnInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.CompleteInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ConfirmDowntimeInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ConfirmProductionInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.CsvInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.DowntimeInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.EndDowntimeInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.MeterReadingInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.OpenShiftReportInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.PauseInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.PollInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ProductionReportInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ProfileInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.PushInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ReasonInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ReasonPatch;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.RejectInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ShiftInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ShiftPatch;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ShiftReportNote;
import com.inventoryerp.manufacturing.application.ShopFloorExecutionService;
import com.inventoryerp.manufacturing.application.ShopFloorReportService;
import com.inventoryerp.manufacturing.application.ShopFloorSetupService;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
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

/**
 * Shop-floor execution &amp; data capture (ADR-S015, Phase 2.7) under {@code /manufacturing/shop-floor}: live board,
 * operator terminal (queue, start / pause / complete, clock on / off, production reports, downtime, meters), shift
 * reports with supervisor approval, OEE, machine data (poll / push / CSV → drafts) and set-up (shifts, reasons,
 * work-centre profiles). Production-report posting and draft confirmation require an {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/manufacturing/shop-floor")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
@Validated
public class ShopFloorExecutionController {

    private final ShopFloorExecutionService exec;
    private final ShopFloorReportService reports;
    private final ShopFloorSetupService setup;
    private final MachineDataService machines;

    public ShopFloorExecutionController(ShopFloorExecutionService exec, ShopFloorReportService reports,
            ShopFloorSetupService setup, MachineDataService machines) {
        this.exec = exec;
        this.reports = reports;
        this.setup = setup;
        this.machines = machines;
    }

    // ------------------------------------------------------------------ board + terminal

    @GetMapping("/dashboard")
    @RequirePermissions(Permissions.WORK_ORDER__READ)
    public Map<String, Object> dashboard() {
        return reports.dashboard();
    }

    @GetMapping("/work-centers/{id}/queue")
    @RequirePermissions(Permissions.WORK_ORDER__READ)
    public Map<String, Object> queue(@PathVariable String id) {
        return exec.queue(id);
    }

    @PostMapping("/work-orders/{id}/start")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> start(@PathVariable String id) {
        return exec.start(id);
    }

    @PostMapping("/work-orders/{id}/pause")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> pause(@PathVariable String id, @Valid @RequestBody PauseInput dto) {
        return exec.pause(id, dto.reasonCode(), dto.notes());
    }

    @PostMapping("/work-orders/{id}/complete")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> complete(@PathVariable String id, @Valid @RequestBody(required = false) CompleteInput dto) {
        return exec.complete(id, dto == null ? null : dto.notes());
    }

    // ------------------------------------------------------------------ clock entries

    @GetMapping("/clock-entries")
    @RequirePermissions(Permissions.WORK_ORDER__READ)
    public PageResult<Map<String, Object>> clockEntries(@RequestParam(required = false) String workCenterId,
            @RequestParam(required = false) String userId, @RequestParam(required = false) Boolean open,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return exec.clockEntries(workCenterId, userId, open, from, to, page, pageSize);
    }

    @PostMapping("/clock-entries")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> clockOn(@Valid @RequestBody ClockOnInput dto) {
        return exec.clockOn(dto);
    }

    @PostMapping("/clock-entries/{id}/clock-off")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> clockOff(@PathVariable String id, @Valid @RequestBody(required = false) ClockOffInput dto) {
        return exec.clockOff(id, dto == null ? null : dto.notes());
    }

    // ------------------------------------------------------------------ production reports

    @GetMapping("/production-reports")
    @RequirePermissions(Permissions.WORK_ORDER__READ)
    public PageResult<Map<String, Object>> productionReports(@RequestParam(required = false) String workCenterId,
            @RequestParam(required = false) @OneOf({"draft", "posted", "rejected"}) String status,
            @RequestParam(required = false) @OneOf({"operator", "machine"}) String source,
            @RequestParam(required = false) String workOrderId, @RequestParam(required = false) String shiftReportId,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return exec.productionReports(workCenterId, status, source, workOrderId, shiftReportId, page, pageSize);
    }

    @PostMapping("/production-reports")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__COMPLETE)
    public Map<String, Object> report(@Valid @RequestBody ProductionReportInput dto) {
        return exec.report(dto);
    }

    @PostMapping("/production-reports/{id}/confirm")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__COMPLETE)
    public Map<String, Object> confirmProduction(@PathVariable String id,
            @Valid @RequestBody(required = false) ConfirmProductionInput dto) {
        return exec.confirmProduction(id, dto != null ? dto
                : new ConfirmProductionInput(null, null, null, null, null, null, null));
    }

    @PostMapping("/production-reports/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__COMPLETE)
    public Map<String, Object> rejectProduction(@PathVariable String id, @Valid @RequestBody RejectInput dto) {
        return exec.rejectProduction(id, dto.reason());
    }

    // ------------------------------------------------------------------ downtime

    @GetMapping("/downtime")
    @RequirePermissions(Permissions.WORK_ORDER__READ)
    public PageResult<Map<String, Object>> downtime(@RequestParam(required = false) String workCenterId,
            @RequestParam(required = false) @OneOf({"draft", "confirmed", "rejected"}) String status,
            @RequestParam(required = false) @OneOf({"operator", "machine"}) String source,
            @RequestParam(required = false) Boolean open, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return exec.downtime(workCenterId, status, source, open, from, to, page, pageSize);
    }

    @PostMapping("/downtime")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> logDowntime(@Valid @RequestBody DowntimeInput dto) {
        return exec.logDowntime(dto);
    }

    @PostMapping("/downtime/{id}/end")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> endDowntime(@PathVariable String id,
            @Valid @RequestBody(required = false) EndDowntimeInput dto) {
        return exec.endDowntime(id, dto);
    }

    @PostMapping("/downtime/{id}/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> confirmDowntime(@PathVariable String id,
            @Valid @RequestBody(required = false) ConfirmDowntimeInput dto) {
        return exec.confirmDowntime(id, dto != null ? dto : new ConfirmDowntimeInput(null, null, null));
    }

    @PostMapping("/downtime/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> rejectDowntime(@PathVariable String id, @Valid @RequestBody RejectInput dto) {
        return exec.rejectDowntime(id, dto.reason());
    }

    // ------------------------------------------------------------------ meters

    @GetMapping("/meter-readings")
    @RequirePermissions(Permissions.WORK_ORDER__READ)
    public PageResult<Map<String, Object>> meterReadings(@RequestParam(required = false) String workCenterId,
            @RequestParam(required = false) String meterCode, @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return exec.meterReadings(workCenterId, meterCode, page, pageSize);
    }

    @PostMapping("/meter-readings")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> readMeter(@Valid @RequestBody MeterReadingInput dto) {
        return exec.readMeter(dto);
    }

    // ------------------------------------------------------------------ shift reports + OEE

    @GetMapping("/shift-reports")
    @RequirePermissions(Permissions.WORK_ORDER__READ)
    public PageResult<Map<String, Object>> shiftReports(@RequestParam(required = false) String workCenterId,
            @RequestParam(required = false) @OneOf({"open", "submitted", "approved"}) String status,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return reports.list(workCenterId, status, from, to, page, pageSize);
    }

    @GetMapping("/shift-reports/{id}")
    @RequirePermissions(Permissions.WORK_ORDER__READ)
    public Map<String, Object> shiftReport(@PathVariable String id) {
        return reports.get(id);
    }

    @PostMapping("/shift-reports")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> openShiftReport(@Valid @RequestBody OpenShiftReportInput dto) {
        return reports.open(dto);
    }

    @PostMapping("/shift-reports/{id}/submit")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
    public Map<String, Object> submit(@PathVariable String id, @Valid @RequestBody(required = false) ShiftReportNote dto) {
        return reports.submit(id, dto == null ? null : dto.notes());
    }

    @PostMapping("/shift-reports/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SHOP_FLOOR__APPROVE)
    public Map<String, Object> approve(@PathVariable String id, @Valid @RequestBody(required = false) ShiftReportNote dto) {
        return reports.approve(id, dto == null ? null : dto.notes());
    }

    @PostMapping("/shift-reports/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SHOP_FLOOR__APPROVE)
    public Map<String, Object> reject(@PathVariable String id, @Valid @RequestBody RejectInput dto) {
        return reports.reject(id, dto.reason());
    }

    @GetMapping("/oee")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public Map<String, Object> oee(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String workCenterId) {
        return reports.oeeReport(from, to, workCenterId);
    }

    // ------------------------------------------------------------------ machine data

    @GetMapping("/machine-adapters")
    @RequirePermissions(Permissions.WORK_CENTER__READ)
    public List<Map<String, Object>> machineAdapters() {
        return machines.adapters();
    }

    @GetMapping("/machine-events")
    @RequirePermissions(Permissions.WORK_ORDER__READ)
    public PageResult<Map<String, Object>> machineEvents(@RequestParam(required = false) String workCenterId,
            @RequestParam(required = false) @OneOf({"drafted", "ignored", "error"}) String status,
            @RequestParam(required = false) String adapter, @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return machines.events(workCenterId, status, adapter, page, pageSize);
    }

    @PostMapping("/machine-data/poll")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SHOP_FLOOR__MACHINE_DATA)
    public Map<String, Object> poll(@Valid @RequestBody PollInput dto) {
        return machines.poll(dto);
    }

    @PostMapping("/machine-data/push")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SHOP_FLOOR__MACHINE_DATA)
    public Map<String, Object> push(@Valid @RequestBody PushInput dto) {
        return machines.push(dto);
    }

    @PostMapping("/machine-data/csv")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SHOP_FLOOR__MACHINE_DATA)
    public Map<String, Object> csv(@Valid @RequestBody CsvInput dto) {
        return machines.csv(dto.content(), dto.filename());
    }

    // ------------------------------------------------------------------ set-up

    @GetMapping("/shifts")
    @RequirePermissions(Permissions.WORK_ORDER__READ)
    public List<Map<String, Object>> shifts() {
        return setup.shifts();
    }

    @PostMapping("/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_CENTER__UPDATE)
    public Map<String, Object> createShift(@Valid @RequestBody ShiftInput dto) {
        return setup.createShift(dto);
    }

    @PatchMapping("/shifts/{id}")
    @RequirePermissions(Permissions.WORK_CENTER__UPDATE)
    public Map<String, Object> updateShift(@PathVariable String id, @Valid @RequestBody ShiftPatch dto) {
        return setup.updateShift(id, dto);
    }

    @PostMapping("/shifts/defaults")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_CENTER__UPDATE)
    public List<Map<String, Object>> defaultShifts() {
        return setup.defaultShifts();
    }

    @GetMapping("/downtime-reasons")
    @RequirePermissions(Permissions.WORK_ORDER__READ)
    public List<Map<String, Object>> reasons(@RequestParam(required = false) Boolean includeInactive) {
        return setup.reasons(includeInactive);
    }

    @PostMapping("/downtime-reasons")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_CENTER__UPDATE)
    public Map<String, Object> createReason(@Valid @RequestBody ReasonInput dto) {
        return setup.createReason(dto);
    }

    @PatchMapping("/downtime-reasons/{id}")
    @RequirePermissions(Permissions.WORK_CENTER__UPDATE)
    public Map<String, Object> updateReason(@PathVariable String id, @Valid @RequestBody ReasonPatch dto) {
        return setup.updateReason(id, dto);
    }

    @PostMapping("/downtime-reasons/defaults")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WORK_CENTER__UPDATE)
    public List<Map<String, Object>> defaultReasons() {
        return setup.defaultReasons();
    }

    @GetMapping("/work-center-profiles")
    @RequirePermissions(Permissions.WORK_CENTER__READ)
    public List<Map<String, Object>> profiles() {
        return setup.profiles();
    }

    @PutMapping("/work-center-profiles/{workCenterId}")
    @RequirePermissions(Permissions.WORK_CENTER__UPDATE)
    public Map<String, Object> saveProfile(@PathVariable String workCenterId, @Valid @RequestBody ProfileInput dto) {
        return setup.saveProfile(workCenterId, dto);
    }
}
