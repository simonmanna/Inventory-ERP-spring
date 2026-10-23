package com.inventoryerp.pos.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.SalesReturnsService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /returns} — sales returns register (post-parity addition, no Nest counterpart). Creating a return goes
 * through {@code POST /pos/invoices/:id/refund}; these routes list, inspect and prepare returns.
 */
@RestController
@RequestMapping("/returns")
public class SalesReturnsController {

    private final SalesReturnsService svc;

    public SalesReturnsController(SalesReturnsService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> list(@RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize, @RequestParam(required = false) String search,
            @RequestParam(required = false) String stockDisposition, @RequestParam(required = false) String channel,
            @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo) {
        return svc.list(orDefault(page, 1), Math.min(orDefault(pageSize, 20), 100), search, stockDisposition, channel,
                dateFrom, dateTo);
    }

    @GetMapping("/invoices")
    @RequirePermissions(Permissions.POS__REFUND)
    public List<Map<String, Object>> returnableInvoices(@RequestParam(required = false) String search) {
        return svc.returnableInvoices(search);
    }

    @GetMapping("/invoices/{invoiceId}")
    @RequirePermissions(Permissions.POS__REFUND)
    public Map<String, Object> returnableInvoice(@PathVariable String invoiceId) {
        return svc.returnableInvoice(invoiceId);
    }

    @GetMapping("/registers")
    @RequirePermissions(Permissions.POS__REFUND)
    public List<Map<String, Object>> openRegisters() {
        return svc.openRegisters();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return svc.get(id);
    }

    private static int orDefault(String raw, int def) {
        try {
            int v = raw == null ? def : Integer.parseInt(raw);
            return v < 1 ? def : v;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
