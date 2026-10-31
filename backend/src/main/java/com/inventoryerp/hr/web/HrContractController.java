package com.inventoryerp.hr.web;

import com.inventoryerp.hr.application.HrContractService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /hr/contracts} (HR-2): employment contract records, activation, termination and renewal. */
@RestController
@RequestMapping("/hr/contracts")
@RequiresModule("hr")
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrContractController {

    private final HrContractService contracts;

    public HrContractController(HrContractService contracts) {
        this.contracts = contracts;
    }

    @GetMapping
    @RequirePermissions(Permissions.HR__CONTRACT)
    public Map<String, Object> list(@RequestParam Map<String, String> query) {
        return contracts.list(query);
    }

    @GetMapping("/expiring")
    @RequirePermissions(Permissions.HR__CONTRACT)
    public Map<String, Object> expiring(@RequestParam Map<String, String> query) {
        return contracts.expiring(query);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.HR__CONTRACT)
    public Map<String, Object> get(@PathVariable String id) {
        return contracts.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__CONTRACT)
    public Map<String, Object> create(@RequestBody Map<String, Object> dto) {
        return contracts.create(dto);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.HR__CONTRACT)
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return contracts.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.HR__CONTRACT)
    public Map<String, Object> delete(@PathVariable String id) {
        return contracts.delete(id);
    }

    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__CONTRACT)
    public Map<String, Object> activate(@PathVariable String id) {
        return contracts.activate(id);
    }

    @PostMapping("/{id}/terminate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__CONTRACT)
    public Map<String, Object> terminate(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return contracts.terminate(id, dto);
    }

    @PostMapping("/{id}/renew")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__CONTRACT)
    public Map<String, Object> renew(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return contracts.renew(id, dto);
    }
}
