package com.inventoryerp.fleet.web.weighbridge;

import com.inventoryerp.fleet.application.WeighbridgeCommands.ExceptionDecision;
import com.inventoryerp.fleet.application.WeighbridgeCommands.GoodsReceiptInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.ReasonInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.ReportQuery;
import com.inventoryerp.fleet.application.WeighbridgeCommands.StoredTareInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.TareInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.TicketInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.TicketPatch;
import com.inventoryerp.fleet.application.WeighbridgeCommands.TicketQuery;
import com.inventoryerp.fleet.application.WeighbridgeCommands.VoidInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.WeighInput;
import com.inventoryerp.fleet.application.WeighbridgeReportService;
import com.inventoryerp.fleet.application.WeighbridgeTicketService;
import com.inventoryerp.fleet.application.WeighbridgeTicketService.PrintedTicket;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Weighbridge tickets (fleet 1.4): queue, register, first / second weighing (device or manual), stored tare, cancel,
 * print (PDF, counted), void + reissue, goods receipt of inbound tickets; stored tares; out-of-tolerance exceptions
 * (approve / reject with separation of duties, sync with the approvals inbox); the discrepancy report.
 */
@RestController
@RequestMapping("/fleet/weighbridge")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class WeighbridgeTicketController {

    private final WeighbridgeTicketService tickets;
    private final WeighbridgeReportService reports;

    public WeighbridgeTicketController(WeighbridgeTicketService tickets, WeighbridgeReportService reports) {
        this.tickets = tickets;
        this.reports = reports;
    }

    @GetMapping("/tickets")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String queue,
            @RequestParam(required = false) String stationId,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean manual,
            @RequestParam(required = false) Boolean outOfTolerance,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return tickets.list(new TicketQuery(queue, stationId, subjectType, partnerId, productId, vehicleId, search,
                manual, outOfTolerance, from, to, page, pageSize));
    }

    @GetMapping("/tickets/summary")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public Map<String, Object> summary(@RequestParam(required = false) String stationId) {
        return tickets.queueCounts(stationId);
    }

    @GetMapping("/tickets/{id}")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return tickets.get(id);
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__WEIGH)
    public Map<String, Object> create(@Valid @RequestBody TicketInput dto) {
        return tickets.create(dto);
    }

    @PatchMapping("/tickets/{id}")
    @RequirePermissions(Permissions.WEIGHBRIDGE__WEIGH)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody TicketPatch dto) {
        return tickets.update(id, dto);
    }

    @PostMapping("/tickets/{id}/first-weigh")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__WEIGH)
    public Map<String, Object> firstWeigh(@PathVariable String id, @Valid @RequestBody WeighInput dto) {
        return tickets.firstWeigh(id, dto);
    }

    @PostMapping("/tickets/{id}/second-weigh")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__WEIGH)
    public Map<String, Object> secondWeigh(@PathVariable String id, @Valid @RequestBody WeighInput dto) {
        return tickets.secondWeigh(id, dto);
    }

    @PostMapping("/tickets/{id}/stored-tare")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__WEIGH)
    public Map<String, Object> storedTare(@PathVariable String id,
            @Valid @RequestBody(required = false) StoredTareInput dto) {
        return tickets.useStoredTare(id, dto);
    }

    @PostMapping("/tickets/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__WEIGH)
    public Map<String, Object> cancel(@PathVariable String id, @Valid @RequestBody ReasonInput dto) {
        return tickets.cancel(id, dto);
    }

    /** The ticket PDF; every call counts a copy (the first is the original). */
    @PostMapping("/tickets/{id}/print")
    @RequirePermissions(Permissions.WEIGHBRIDGE__WEIGH)
    public ResponseEntity<byte[]> print(@PathVariable String id) {
        PrintedTicket p = tickets.print(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + p.filename().replace("\"", "")
                        + "\"")
                .body(p.content());
    }

    @PostMapping("/tickets/{id}/void")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__VOID)
    public Map<String, Object> voidTicket(@PathVariable String id, @Valid @RequestBody VoidInput dto) {
        return tickets.voidTicket(id, dto);
    }

    /** Inbound ticket → its draft goods receipt (idempotent: an existing one is returned). */
    @PostMapping("/tickets/{id}/goods-receipt")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__WEIGH)
    public Map<String, Object> goodsReceipt(@PathVariable String id,
            @Valid @RequestBody(required = false) GoodsReceiptInput dto) {
        return tickets.goodsReceipt(id, dto == null ? null : dto.locationId());
    }

    @GetMapping("/tares")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public PageResult<Map<String, Object>> tares(@RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String plate,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return tickets.tares(vehicleId, plate, state, page, pageSize);
    }

    @PostMapping("/tares")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__WEIGH)
    public Map<String, Object> createTare(@Valid @RequestBody TareInput dto) {
        return tickets.createTare(dto);
    }

    @PostMapping("/tares/{id}/revoke")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__CONFIGURE)
    public Map<String, Object> revokeTare(@PathVariable String id, @Valid @RequestBody ReasonInput dto) {
        return tickets.revokeTare(id, dto);
    }

    @GetMapping("/exceptions")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public PageResult<Map<String, Object>> exceptions(@RequestParam(required = false) String status,
            @RequestParam(required = false) String stationId,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return tickets.exceptions(status, stationId, page, pageSize);
    }

    @PostMapping("/exceptions/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__APPROVE_EXCEPTION)
    public Map<String, Object> approve(@PathVariable String id,
            @Valid @RequestBody(required = false) ExceptionDecision dto) {
        return tickets.approveException(id, dto);
    }

    @PostMapping("/exceptions/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__APPROVE_EXCEPTION)
    public Map<String, Object> reject(@PathVariable String id,
            @Valid @RequestBody(required = false) ExceptionDecision dto) {
        return tickets.rejectException(id, dto);
    }

    @PostMapping("/exceptions/{id}/sync")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__APPROVE_EXCEPTION)
    public Map<String, Object> sync(@PathVariable String id) {
        return tickets.syncException(id);
    }

    /** Discrepancy report: completed tickets grouped by product / partner / station / vehicle / subject / day / month. */
    @GetMapping("/reports/discrepancies")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public Map<String, Object> discrepancies(@RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String stationId,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return reports.discrepancies(new ReportQuery(groupBy, stationId, subjectType, partnerId, productId, from, to));
    }
}
