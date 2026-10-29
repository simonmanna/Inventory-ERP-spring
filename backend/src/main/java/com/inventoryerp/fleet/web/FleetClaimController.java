package com.inventoryerp.fleet.web;

import com.inventoryerp.fleet.application.DriverCommands.AllowanceRuleInput;
import com.inventoryerp.fleet.application.DriverCommands.AllowanceRulePatch;
import com.inventoryerp.fleet.application.DriverCommands.ClaimDecision;
import com.inventoryerp.fleet.application.DriverCommands.ClaimInput;
import com.inventoryerp.fleet.application.DriverCommands.ClaimLineInput;
import com.inventoryerp.fleet.application.DriverCommands.ClaimPatch;
import com.inventoryerp.fleet.application.DriverCommands.ClaimPayment;
import com.inventoryerp.fleet.application.DriverCommands.ClaimPayrollExport;
import com.inventoryerp.fleet.application.DriverCommands.ClaimQuery;
import com.inventoryerp.fleet.application.DriverCommands.ClaimReversal;
import com.inventoryerp.fleet.application.FleetAllowanceRuleService;
import com.inventoryerp.fleet.application.FleetClaimService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Driver allowance rules and driver allowance / trip-expense claims (fleet 1.2, ADR-S009): draft with lines and
 * receipts, submit → approval engine → approve / reject, then pay via expenses or export to hr payroll, reverse.
 * Pay, export and reverse move money: they require an {@code Idempotency-Key} and answer {@code 200} like every
 * idempotent route (the interceptor records and replays the response).
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetClaimController {

    private final FleetAllowanceRuleService rules;
    private final FleetClaimService claims;

    public FleetClaimController(FleetAllowanceRuleService rules, FleetClaimService claims) {
        this.rules = rules;
        this.claims = claims;
    }

    // ------------------------------------------------------------------ allowance rules

    @GetMapping("/allowance-rules")
    @RequirePermissions(Permissions.FLEET_DRIVER__READ)
    public List<Map<String, Object>> rules(@RequestParam(required = false) boolean includeInactive,
            @RequestParam(required = false) @OneOf({"per_trip", "per_day", "per_night"}) String basis) {
        return rules.list(includeInactive, basis);
    }

    @PostMapping("/allowance-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> createRule(@Valid @RequestBody AllowanceRuleInput dto) {
        return rules.create(dto);
    }

    @PatchMapping("/allowance-rules/{id}")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> updateRule(@PathVariable String id, @Valid @RequestBody AllowanceRulePatch dto) {
        return rules.update(id, dto);
    }

    @DeleteMapping("/allowance-rules/{id}")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> deleteRule(@PathVariable String id) {
        return rules.delete(id);
    }

    // ------------------------------------------------------------------ claims

    @GetMapping("/claims")
    @RequirePermissions(Permissions.FLEET_DRIVER__READ)
    public PageResult<Map<String, Object>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false)
            @OneOf({"draft", "submitted", "approved", "rejected", "paid", "exported", "cancelled"}) String status,
            @RequestParam(required = false) String driverId,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) @OneOf({"expenses", "payroll"}) String settlement,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return claims.list(new ClaimQuery(search, status, driverId, vehicleId, settlement, from, to, page, pageSize));
    }

    @GetMapping("/claims/{id}")
    @RequirePermissions(Permissions.FLEET_DRIVER__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return claims.get(id);
    }

    @PostMapping("/claims")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DRIVER__ALLOWANCE)
    public Map<String, Object> create(@Valid @RequestBody ClaimInput dto) {
        return claims.create(dto);
    }

    @PatchMapping("/claims/{id}")
    @RequirePermissions(Permissions.FLEET_DRIVER__ALLOWANCE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody ClaimPatch dto) {
        return claims.update(id, dto);
    }

    @PostMapping("/claims/{id}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DRIVER__ALLOWANCE)
    public Map<String, Object> addLine(@PathVariable String id, @Valid @RequestBody ClaimLineInput dto) {
        return claims.addLine(id, dto);
    }

    @DeleteMapping("/claims/{id}/lines/{lineId}")
    @RequirePermissions(Permissions.FLEET_DRIVER__ALLOWANCE)
    public Map<String, Object> removeLine(@PathVariable String id, @PathVariable String lineId) {
        return claims.removeLine(id, lineId);
    }

    /** Multipart: {@code file} plus optional {@code lineId} and {@code title}. */
    @PostMapping("/claims/{id}/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DRIVER__ALLOWANCE)
    public Map<String, Object> receipt(@PathVariable String id,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "lineId", required = false) String lineId,
            @RequestParam(value = "title", required = false) String title) throws IOException {
        if (file == null) {
            throw ApiException.badRequest("Missing file");
        }
        return claims.addReceipt(id, lineId, title, file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    @PostMapping("/claims/{id}/submit")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DRIVER__ALLOWANCE)
    public Map<String, Object> submit(@PathVariable String id) {
        return claims.submit(id);
    }

    @PostMapping("/claims/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DRIVER__ALLOWANCE)
    public Map<String, Object> cancel(@PathVariable String id) {
        return claims.cancel(id);
    }

    @PostMapping("/claims/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DRIVER__APPROVE_ALLOWANCE)
    public Map<String, Object> approve(@PathVariable String id,
            @Valid @RequestBody(required = false) ClaimDecision dto) {
        return claims.approve(id, dto);
    }

    @PostMapping("/claims/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DRIVER__APPROVE_ALLOWANCE)
    public Map<String, Object> reject(@PathVariable String id,
            @Valid @RequestBody(required = false) ClaimDecision dto) {
        return claims.reject(id, dto);
    }

    @PostMapping("/claims/{id}/sync")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DRIVER__APPROVE_ALLOWANCE)
    public Map<String, Object> sync(@PathVariable String id) {
        return claims.sync(id);
    }

    @PostMapping("/claims/{id}/pay")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_DRIVER__APPROVE_ALLOWANCE)
    public Map<String, Object> pay(@PathVariable String id, @Valid @RequestBody ClaimPayment dto) {
        return claims.pay(id, dto);
    }

    @PostMapping("/claims/{id}/export-payroll")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_DRIVER__APPROVE_ALLOWANCE)
    public Map<String, Object> exportPayroll(@PathVariable String id,
            @Valid @RequestBody(required = false) ClaimPayrollExport dto) {
        return claims.exportToPayroll(id, dto);
    }

    @PostMapping("/claims/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_DRIVER__APPROVE_ALLOWANCE)
    public Map<String, Object> reverse(@PathVariable String id, @Valid @RequestBody ClaimReversal dto) {
        return claims.reverse(id, dto);
    }

    @GetMapping("/lookups/payment-accounts")
    @RequirePermissions(Permissions.FLEET_DRIVER__APPROVE_ALLOWANCE)
    public List<Map<String, Object>> paymentAccounts() {
        return claims.paymentAccounts();
    }
}
