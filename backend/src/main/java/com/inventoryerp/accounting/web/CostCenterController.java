package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.CostCenterService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import java.util.Map;
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

/** {@code /cost-centers} (port of {@code cost-center.controller.ts}; bodies are untyped in Nest). */
@RestController
@RequestMapping("/cost-centers")
public class CostCenterController {

    private final CostCenterService svc;

    public CostCenterController(CostCenterService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(Permissions.COST_CENTER__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return svc.list(JsNumbers.number(query.get("page")), JsNumbers.number(query.get("pageSize")), query.get("type"));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.COST_CENTER__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return svc.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COST_CENTER__CREATE)
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> dto) {
        return svc.create(dto == null ? Map.of() : dto);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.COST_CENTER__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @RequestBody(required = false) Map<String, Object> dto) {
        return svc.update(id, dto == null ? Map.of() : dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.COST_CENTER__DELETE)
    public void remove(@PathVariable String id) {
        svc.remove(id);
    }
}
