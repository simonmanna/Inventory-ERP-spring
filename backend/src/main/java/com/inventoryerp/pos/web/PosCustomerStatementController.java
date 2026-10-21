package com.inventoryerp.pos.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosCustomerStatementService;
import com.inventoryerp.pos.application.PosSql;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /pos/customers/:partnerId/statement|credit} and {@code /pos/credit/open} (port of
 * {@code PosCustomerStatementController} + {@code PosCreditController}).
 */
@RestController
@RequestMapping("/pos")
public class PosCustomerStatementController {

    private final PosCustomerStatementService service;

    public PosCustomerStatementController(PosCustomerStatementService service) {
        this.service = service;
    }

    @GetMapping("/customers/{partnerId}/statement")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> statement(@PathVariable String partnerId) {
        return service.statement(partnerId);
    }

    @GetMapping("/customers/{partnerId}/credit")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> credit(@PathVariable String partnerId) {
        return service.creditStatus(partnerId);
    }

    @GetMapping("/credit/open")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> open(@RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String search, @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize) {
        return service.openCredit(partnerId, search, page == null || page.isEmpty() ? null : PosSql.num(page),
                pageSize == null || pageSize.isEmpty() ? null : PosSql.num(pageSize));
    }
}
