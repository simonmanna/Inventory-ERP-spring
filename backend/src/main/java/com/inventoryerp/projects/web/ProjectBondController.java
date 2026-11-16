package com.inventoryerp.projects.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.projects.application.ProjectBondService;
import com.inventoryerp.projects.application.ProjectBondService.ActionInput;
import com.inventoryerp.projects.application.ProjectBondService.BondInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Bonds and guarantees register, lifecycle and exposure (ADR-S074 §1; extension, no NestJS counterpart). */
@RestController
@RequestMapping("/project-bonds")
public class ProjectBondController {

    public record BondRequest(String bondType, String direction, String projectId, String subcontractId,
            String counterpartyId, String issuer, String issuerType, String bondNumber, BigDecimal amount,
            String currencyCode, String issueDate, String expiryDate, String documentId, String notes) {

        BondInput input() {
            return new BondInput(bondType, direction, projectId, subcontractId, counterpartyId, issuer, issuerType,
                    bondNumber, amount, currencyCode, issueDate, expiryDate, documentId, notes);
        }
    }

    public record BondActionRequest(String date, BigDecimal amount, String expiryDate, BigDecimal margin,
            BigDecimal commission, String bankAccountId, String recoveryTo, String wbsId, String reason) {

        ActionInput input() {
            return new ActionInput(date, amount, expiryDate, margin, commission, bankAccountId, recoveryTo, wbsId,
                    reason);
        }
    }

    private final ProjectBondService bonds;

    public ProjectBondController(ProjectBondService bonds) {
        this.bonds = bonds;
    }

    @GetMapping
    @RequirePermissions(Permissions.BOND__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String direction,
            @RequestParam(required = false) String bondType) {
        return bonds.list(projectId, status, direction, bondType);
    }

    @GetMapping("/exposure")
    @RequirePermissions(Permissions.BOND__READ)
    public Map<String, Object> exposure() {
        return bonds.exposure();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.BOND__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return bonds.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BOND__MANAGE)
    public Map<String, Object> create(@RequestBody BondRequest r) {
        return bonds.create(r.input());
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.BOND__MANAGE)
    public Map<String, Object> update(@PathVariable String id, @RequestBody BondRequest r) {
        return bonds.update(id, r.input());
    }

    @PostMapping("/{id}/activate")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.BOND__POST)
    public Map<String, Object> activate(@PathVariable String id, @RequestBody BondActionRequest r) {
        return bonds.activate(id, r.input());
    }

    @PostMapping("/{id}/extend")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.BOND__POST)
    public Map<String, Object> extend(@PathVariable String id, @RequestBody BondActionRequest r) {
        return bonds.extend(id, r.input());
    }

    @PostMapping("/{id}/reduce")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.BOND__POST)
    public Map<String, Object> reduce(@PathVariable String id, @RequestBody BondActionRequest r) {
        return bonds.reduce(id, r.input());
    }

    @PostMapping("/{id}/release")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.BOND__POST)
    public Map<String, Object> release(@PathVariable String id, @RequestBody BondActionRequest r) {
        return bonds.release(id, r.input());
    }

    @PostMapping("/{id}/call")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.BOND__POST)
    public Map<String, Object> call(@PathVariable String id, @RequestBody BondActionRequest r) {
        return bonds.call(id, r.input());
    }
}
