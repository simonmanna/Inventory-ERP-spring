package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.MfgCommands.CancelOrder;
import com.inventoryerp.manufacturing.application.MfgCommands.CompleteOrder;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateOrder;
import com.inventoryerp.manufacturing.application.MfgCommands.RecordQc;
import com.inventoryerp.manufacturing.application.MfgCommands.StartOrder;
import com.inventoryerp.manufacturing.application.ProductionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /manufacturing/production-orders} (port of {@code production.controller.ts}). */
@RestController
@RequestMapping("/manufacturing/production-orders")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class ProductionController {

    private final ProductionService production;

    public ProductionController(ProductionService production) {
        this.production = production;
    }

    @GetMapping
    @RequirePermissions(Permissions.PRODUCTION_ORDER__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String outputProductId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return production.list(status, outputProductId, limit, offset);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return production.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateOrder dto) {
        return production.createOrder(dto);
    }

    @PostMapping("/{id}/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__CREATE)
    public Map<String, Object> confirm(@PathVariable String id) {
        return production.confirmOrder(id);
    }

    @PostMapping("/{id}/start")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__START)
    public Map<String, Object> start(@PathVariable String id,
            @Valid @RequestBody(required = false) StartOrder dto) {
        return production.startOrder(id, dto);
    }

    @PostMapping("/{id}/complete")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__COMPLETE)
    public Map<String, Object> complete(@PathVariable String id, @Valid @RequestBody CompleteOrder dto) {
        return production.completeOrder(id, dto);
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__CANCEL)
    public Map<String, Object> cancel(@PathVariable String id,
            @Valid @RequestBody(required = false) CancelOrder dto) {
        return production.cancelOrder(id, dto);
    }

    @PostMapping("/{id}/qc")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__QC)
    public Map<String, Object> recordQc(@PathVariable String id, @Valid @RequestBody RecordQc dto) {
        return production.recordQc(id, dto);
    }

    @PostMapping("/{id}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__CANCEL)
    public Map<String, Object> reverse(@PathVariable String id,
            @Valid @RequestBody(required = false) CancelOrder dto) {
        return production.reverseOrder(id, dto);
    }

    /** Make a finished good sellable at POS in one click. */
    @PostMapping("/finished-goods/{productId}/menu-item")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__CREATE)
    public Map<String, Object> createMenuItem(@PathVariable String productId) {
        return production.createMenuItemForProduct(productId);
    }
}
