package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.BomService;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateBom;
import com.inventoryerp.manufacturing.application.MfgCommands.UpdateBom;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /manufacturing/boms} (port of {@code bom.controller.ts}). */
@RestController
@RequestMapping("/manufacturing/boms")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class BomController {

    private final BomService boms;

    public BomController(BomService boms) {
        this.boms = boms;
    }

    @GetMapping
    @RequirePermissions(Permissions.BOM__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String outputProductId,
            @RequestParam(required = false) String status) {
        return boms.list(outputProductId, status);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.BOM__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return boms.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BOM__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateBom dto) {
        return boms.create(dto);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.BOM__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateBom dto) {
        return boms.update(id, dto);
    }

    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BOM__ACTIVATE)
    public Map<String, Object> activate(@PathVariable String id) {
        return boms.activate(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.BOM__DELETE)
    public void remove(@PathVariable String id) {
        boms.remove(id);
    }
}
