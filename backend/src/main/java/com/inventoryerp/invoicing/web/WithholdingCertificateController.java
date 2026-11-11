package com.inventoryerp.invoicing.web;

import com.inventoryerp.invoicing.application.WithholdingCertificateService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /withholding-certificates}: withholding tax certificates and the filing report (ADR-S071; Spring-native). */
@RestController
@RequestMapping("/withholding-certificates")
public class WithholdingCertificateController {

    /** Correction body. */
    public record UpdateRequest(String number, String notes) {}

    private final WithholdingCertificateService certificates;

    public WithholdingCertificateController(WithholdingCertificateService certificates) {
        this.certificates = certificates;
    }

    @GetMapping
    @RequirePermissions(Permissions.WITHHOLDING__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String direction,
            @RequestParam(required = false) String partnerId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String page, @RequestParam(required = false) String pageSize) {
        return certificates.list(direction, partnerId, status, from, to, InvoicingDtos.parseInt(page, 1),
                InvoicingDtos.parseInt(pageSize, 25));
    }

    @GetMapping("/report")
    @RequirePermissions(Permissions.WITHHOLDING__READ)
    public Map<String, Object> report(@RequestParam(required = false) String direction,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return certificates.report(direction, from, to);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.WITHHOLDING__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return certificates.findOne(id);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.WITHHOLDING__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @RequestBody UpdateRequest dto) {
        return certificates.update(id, dto.number(), dto.notes());
    }
}
