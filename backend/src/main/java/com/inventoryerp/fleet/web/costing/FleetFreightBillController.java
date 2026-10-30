package com.inventoryerp.fleet.web.costing;

import com.inventoryerp.fleet.application.FleetFreightBillService;
import com.inventoryerp.fleet.application.FleetReportCommands.CancelFreightBill;
import com.inventoryerp.fleet.application.FleetReportCommands.FreightBillQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.FreightCandidateQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.GenerateFreightBills;
import com.inventoryerp.fleet.application.FleetReportCommands.InboundFreight;
import com.inventoryerp.fleet.application.FleetReportCommands.MatchFreightBill;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Third-party transporter freight bills (fleet 1.9): collect closed transporter trips per transporter and period,
 * match the supplier's invoice (AP vendor bill + freight accrual, tolerance / variance approval), cancel, and
 * capitalise inbound freight on a goods receipt as landed cost. Match, cancel and inbound post to the GL: they
 * require an {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/fleet/freight-bills")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetFreightBillController {

    private final FleetFreightBillService bills;

    public FleetFreightBillController(FleetFreightBillService bills) {
        this.bills = bills;
    }

    @GetMapping
    @RequirePermissions(Permissions.FLEET_FREIGHT__READ)
    public PageResult<Map<String, Object>> list(
            @RequestParam(required = false) @OneOf({"draft", "disputed", "billed", "cancelled"}) String status,
            @RequestParam(required = false) @OneOf({"outbound", "inbound"}) String direction,
            @RequestParam(required = false) String transporterPartnerId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return bills.list(new FreightBillQuery(status, direction, transporterPartnerId, search, page, pageSize));
    }

    @GetMapping("/lookups")
    @RequirePermissions(Permissions.FLEET_FREIGHT__READ)
    public Map<String, Object> lookups(@RequestParam(required = false) String search) {
        return bills.lookups(search);
    }

    @GetMapping("/candidates")
    @RequirePermissions(Permissions.FLEET_FREIGHT__READ)
    public Map<String, Object> candidates(@RequestParam(required = false) String transporterPartnerId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return bills.candidates(new FreightCandidateQuery(transporterPartnerId, from, to));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.FLEET_FREIGHT__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return bills.get(id);
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_FREIGHT__BILL)
    public Map<String, Object> generate(@Valid @RequestBody GenerateFreightBills dto) {
        return bills.generate(dto);
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    @RequirePermissions(Permissions.FLEET_FREIGHT__BILL)
    public Map<String, Object> removeLine(@PathVariable String id, @PathVariable String lineId) {
        return bills.removeLine(id, lineId);
    }

    @PostMapping("/{id}/match")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_FREIGHT__BILL)
    public Map<String, Object> match(@PathVariable String id, @Valid @RequestBody MatchFreightBill dto) {
        return bills.match(id, dto);
    }

    @PostMapping("/{id}/cancel")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_FREIGHT__BILL)
    public Map<String, Object> cancel(@PathVariable String id, @Valid @RequestBody CancelFreightBill dto) {
        return bills.cancel(id, dto);
    }

    @PostMapping("/inbound")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_FREIGHT__BILL)
    public Map<String, Object> inbound(@Valid @RequestBody InboundFreight dto) {
        return bills.inbound(dto);
    }
}
