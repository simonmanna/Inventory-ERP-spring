package com.inventoryerp.portal.web;

import com.inventoryerp.accounting.application.ReportExportService.Export;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.portal.application.PortalQueryService;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The customer's side of the portal (PORTAL-1): read-only, always scoped to the signed-in customer. */
@RestController
@RequestMapping("/portal")
@RequiresModule("portal")
@ConditionalOnProperty(name = "app.modules.portal", havingValue = "true")
@Validated
public class PortalController {

    private final PortalQueryService portal;

    public PortalController(PortalQueryService portal) {
        this.portal = portal;
    }

    private static ResponseEntity<byte[]> pdf(String filename, byte[] content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename.replace("\"", "") + "\"")
                .body(content);
    }

    @GetMapping("/home")
    @RequirePermissions(Permissions.PORTAL__CUSTOMER)
    public Map<String, Object> home() {
        return portal.home();
    }

    @GetMapping("/orders")
    @RequirePermissions(Permissions.PORTAL__CUSTOMER)
    public PageResult<Map<String, Object>> orders(@RequestParam(required = false) String status,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(100) Integer pageSize) {
        return portal.orders(status, page, pageSize);
    }

    @GetMapping("/orders/{id}")
    @RequirePermissions(Permissions.PORTAL__CUSTOMER)
    public Map<String, Object> order(@PathVariable String id) {
        return portal.order(id);
    }

    @GetMapping("/deliveries")
    @RequirePermissions(Permissions.PORTAL__CUSTOMER)
    public PageResult<Map<String, Object>> deliveries(
            @RequestParam(required = false) @OneOf({"active", "done", "all"}) String scope,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(100) Integer pageSize) {
        return portal.deliveries(scope, page, pageSize);
    }

    @GetMapping("/deliveries/{id}")
    @RequirePermissions(Permissions.PORTAL__CUSTOMER)
    public Map<String, Object> delivery(@PathVariable String id) {
        return portal.delivery(id);
    }

    @GetMapping("/invoices")
    @RequirePermissions(Permissions.PORTAL__CUSTOMER)
    public PageResult<Map<String, Object>> invoices(@RequestParam(required = false) Boolean open,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(100) Integer pageSize) {
        return portal.invoices(open, page, pageSize);
    }

    @GetMapping("/invoices/{id}")
    @RequirePermissions(Permissions.PORTAL__CUSTOMER)
    public Map<String, Object> invoice(@PathVariable String id) {
        return portal.invoice(id);
    }

    @GetMapping("/invoices/{id}/pdf")
    @RequirePermissions(Permissions.PORTAL__CUSTOMER)
    public ResponseEntity<byte[]> invoicePdf(@PathVariable String id) {
        Export export = portal.invoicePdf(id);
        return pdf(export.filename(), export.content());
    }

    @GetMapping("/statement")
    @RequirePermissions(Permissions.PORTAL__CUSTOMER)
    public Map<String, Object> statement(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return portal.statement(from, to);
    }

    @GetMapping("/statement.pdf")
    @RequirePermissions(Permissions.PORTAL__CUSTOMER)
    public ResponseEntity<byte[]> statementPdf(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return pdf("statement.pdf", portal.statementPdf(from, to));
    }
}
