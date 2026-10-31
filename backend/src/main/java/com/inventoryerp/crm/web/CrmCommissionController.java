package com.inventoryerp.crm.web;

import com.inventoryerp.crm.application.CrmCommissionService;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /commissions} (SALES-4): rules, calculation, approval and export to payroll. */
@RestController
@RequestMapping("/commissions")
public class CrmCommissionController {

    public record CalculateRequest(LocalDate from, LocalDate to) {}

    public record ApproveRequest(@NotEmpty(message = "should not be empty") List<String> ids) {}

    public record ExportRequest(LocalDate to) {}

    private final CrmCommissionService commissions;

    public CrmCommissionController(CrmCommissionService commissions) {
        this.commissions = commissions;
    }

    @GetMapping("/rules")
    @RequirePermissions(Permissions.COMMISSION__READ)
    public List<Map<String, Object>> rules() {
        return commissions.rules();
    }

    @PostMapping("/rules")
    @RequirePermissions(Permissions.COMMISSION__MANAGE)
    public Map<String, Object> createRule(@RequestBody Map<String, Object> body) {
        return commissions.saveRule(null, body);
    }

    @PutMapping("/rules/{id}")
    @RequirePermissions(Permissions.COMMISSION__MANAGE)
    public Map<String, Object> updateRule(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return commissions.saveRule(id, body);
    }

    @DeleteMapping("/rules/{id}")
    @RequirePermissions(Permissions.COMMISSION__MANAGE)
    public Map<String, Object> deleteRule(@PathVariable String id) {
        return commissions.deleteRule(id);
    }

    @PostMapping("/calculate")
    @RequirePermissions(Permissions.COMMISSION__MANAGE)
    public Map<String, Object> calculate(@Valid @RequestBody CalculateRequest dto) {
        return commissions.calculate(dto.from(), dto.to());
    }

    @GetMapping("/entries")
    @RequirePermissions(Permissions.COMMISSION__READ)
    public PageResult<Map<String, Object>> entries(@RequestParam(required = false) String status,
            @RequestParam(required = false) String employeeId, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize) {
        return commissions.entries(status, employeeId, from, to, QueryParams.parseInt(page, 1),
                QueryParams.parseInt(pageSize, 25));
    }

    @GetMapping("/summary")
    @RequirePermissions(Permissions.COMMISSION__READ)
    public List<Map<String, Object>> summary(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return commissions.summary(from, to);
    }

    @PostMapping("/approve")
    @RequirePermissions(Permissions.COMMISSION__APPROVE)
    public Map<String, Object> approve(@Valid @RequestBody ApproveRequest dto) {
        return commissions.approve(dto.ids());
    }

    @PostMapping("/export-payroll")
    @RequirePermissions(Permissions.COMMISSION__APPROVE)
    public Map<String, Object> exportToPayroll(@Valid @RequestBody ExportRequest dto) {
        return commissions.exportToPayroll(dto.to());
    }
}
