package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.SalesLostReasonService;
import com.inventoryerp.sales.application.SalesProformaService;
import com.inventoryerp.sales.application.SalesQuotationService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Proforma invoices, quotation output and the lost-reason catalog (SG-3, SG-4). */
@RestController
@RequestMapping("/sales")
public class SalesProformaController {

    private final SalesProformaService proformas;
    private final SalesQuotationService quotations;
    private final SalesLostReasonService reasons;

    public SalesProformaController(SalesProformaService proformas, SalesQuotationService quotations,
            SalesLostReasonService reasons) {
        this.proformas = proformas;
        this.quotations = quotations;
        this.reasons = reasons;
    }

    private static ResponseEntity<byte[]> pdf(byte[] content, String name) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".pdf\"").body(content);
    }

    @GetMapping("/proformas")
    @RequirePermissions(Permissions.SALES__ORDER_READ)
    public Map<String, Object> list(@RequestParam Map<String, String> q) {
        return proformas.list(q);
    }

    @GetMapping("/proformas/{id}")
    @RequirePermissions(Permissions.SALES__ORDER_READ)
    public Map<String, Object> get(@PathVariable String id) {
        return proformas.get(id);
    }

    @GetMapping("/proformas/{id}/pdf")
    @RequirePermissions(Permissions.SALES__ORDER_READ)
    public ResponseEntity<byte[]> pdf(@PathVariable String id) {
        return pdf(proformas.pdf(id), "proforma-" + id);
    }

    @PostMapping("/proformas/{id}/email")
    @RequirePermissions(Permissions.SALES__ORDER_WRITE)
    public Map<String, Object> email(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
        return proformas.email(id, SalesController.text(b, "to"));
    }

    @PostMapping("/orders/{id}/proforma")
    @Idempotent
    @RequirePermissions(Permissions.SALES__ORDER_WRITE)
    public Map<String, Object> fromOrder(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
        return proformas.fromOrder(id, SalesController.body(b));
    }

    @GetMapping("/quotations/{id}/pdf")
    @RequirePermissions(Permissions.SALES__QUOTATION_READ)
    public ResponseEntity<byte[]> quotationPdf(@PathVariable String id) {
        return pdf(quotations.pdf(id), "quotation-" + id);
    }

    @PostMapping("/quotations/{id}/email")
    @RequirePermissions(Permissions.SALES__QUOTATION_SEND)
    public Map<String, Object> quotationEmail(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> b) {
        return quotations.email(id, SalesController.text(b, "to"));
    }

    @GetMapping("/lost-reasons")
    @RequirePermissions(Permissions.SALES__QUOTATION_READ)
    public List<Map<String, Object>> lostReasons() {
        return reasons.list();
    }

    @PostMapping("/lost-reasons")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__CONFIGURE)
    public Map<String, Object> addLostReason(@RequestBody Map<String, Object> b) {
        return reasons.save(null, SalesController.body(b));
    }

    @PutMapping("/lost-reasons/{id}")
    @RequirePermissions(Permissions.SALES__CONFIGURE)
    public Map<String, Object> saveLostReason(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return reasons.save(id, SalesController.body(b));
    }
}
