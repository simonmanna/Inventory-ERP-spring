package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.licensing.LicenseFeatures;
import com.inventoryerp.kernel.licensing.RequiresFeature;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.SalesAgreementService;
import com.inventoryerp.sales.application.SalesCommissionService;
import com.inventoryerp.sales.application.SalesRebateService;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Agreements, rebates (licence {@code salesAgreements}), commissions and targets (ADR-S011 S9). */
public final class SalesAgreementController {

    private SalesAgreementController() {}

    private static Map<String, Object> body(Map<String, Object> b) {
        return b == null ? new HashMap<>() : b;
    }

    private static LocalDate date(Map<String, Object> b, String k) {
        Object v = body(b).get(k);
        return v == null || v.toString().isBlank() ? null : LocalDate.parse(v.toString().substring(0, 10));
    }

    /** {@code /sales/agreements}: blanket / contract agreements and their releases. */
    @RestController
    @RequestMapping("/sales/agreements")
    @RequiresFeature(LicenseFeatures.SALES_AGREEMENTS)
    public static class Agreements {

        private final SalesAgreementService svc;

        public Agreements(SalesAgreementService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.SALES__AGREEMENT_READ)
        public List<Map<String, Object>> list(@RequestParam Map<String, String> q) {
            return svc.list(q);
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.SALES__AGREEMENT_READ)
        public Map<String, Object> get(@PathVariable String id) {
            return svc.get(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__AGREEMENT_MANAGE)
        public Map<String, Object> create(@RequestBody Map<String, Object> b) {
            return svc.create(body(b));
        }

        @PutMapping("/{id}")
        @RequirePermissions(Permissions.SALES__AGREEMENT_MANAGE)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.update(id, body(b));
        }

        @PostMapping("/{id}/activate")
        @RequirePermissions(Permissions.SALES__AGREEMENT_MANAGE)
        public Map<String, Object> activate(@PathVariable String id) {
            return svc.activate(id);
        }

        @PostMapping("/{id}/close")
        @RequirePermissions(Permissions.SALES__AGREEMENT_MANAGE)
        public Map<String, Object> close(@PathVariable String id) {
            return svc.close(id);
        }

        @PostMapping("/{id}/cancel")
        @RequirePermissions(Permissions.SALES__AGREEMENT_MANAGE)
        public Map<String, Object> cancel(@PathVariable String id) {
            return svc.cancel(id);
        }
    }

    /** {@code /sales/rebates}: programmes, accrual runs, settlement. */
    @RestController
    @RequestMapping("/sales/rebates")
    @RequiresFeature(LicenseFeatures.SALES_AGREEMENTS)
    public static class Rebates {

        private final SalesRebateService svc;

        public Rebates(SalesRebateService svc) {
            this.svc = svc;
        }

        @GetMapping("/programs")
        @RequirePermissions(Permissions.SALES__AGREEMENT_READ)
        public List<Map<String, Object>> programs() {
            return svc.programs();
        }

        @PostMapping("/programs")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__REBATE_MANAGE)
        public Map<String, Object> create(@RequestBody Map<String, Object> b) {
            return svc.saveProgram(null, body(b));
        }

        @PutMapping("/programs/{id}")
        @RequirePermissions(Permissions.SALES__REBATE_MANAGE)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.saveProgram(id, body(b));
        }

        @GetMapping("/accruals")
        @RequirePermissions(Permissions.SALES__AGREEMENT_READ)
        public List<Map<String, Object>> accruals(@RequestParam Map<String, String> q) {
            return svc.accruals(q);
        }

        @PostMapping("/accrue")
        @RequirePermissions(Permissions.SALES__REBATE_MANAGE)
        public Map<String, Object> accrue(@RequestBody(required = false) Map<String, Object> b) {
            return svc.accrue(date(b, "asOf"));
        }

        @PostMapping("/accruals/{id}/settle")
        @Idempotent
        @RequirePermissions(Permissions.SALES__REBATE_MANAGE)
        public Map<String, Object> settle(@PathVariable String id) {
            return svc.settle(id);
        }
    }

    /** {@code /sales/commissions}: rules, preview, monthly statements; {@code /sales/targets}. */
    @RestController
    @RequestMapping("/sales")
    public static class Commissions {

        private final SalesCommissionService svc;

        public Commissions(SalesCommissionService svc) {
            this.svc = svc;
        }

        @GetMapping("/commissions/rules")
        @RequirePermissions(Permissions.SALES__COMMISSION_MANAGE)
        public List<Map<String, Object>> rules() {
            return svc.rules();
        }

        @PostMapping("/commissions/rules")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__COMMISSION_MANAGE)
        public Map<String, Object> createRule(@RequestBody Map<String, Object> b) {
            return svc.saveRule(null, body(b));
        }

        @PutMapping("/commissions/rules/{id}")
        @RequirePermissions(Permissions.SALES__COMMISSION_MANAGE)
        public Map<String, Object> updateRule(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.saveRule(id, body(b));
        }

        @GetMapping("/commissions/preview")
        @RequirePermissions(Permissions.SALES__COMMISSION_MANAGE)
        public Map<String, Object> preview(@RequestParam(required = false) String month,
                @RequestParam(required = false) String salespersonId) {
            return svc.preview(month, salespersonId);
        }

        @GetMapping("/commissions/statements")
        @RequirePermissions(Permissions.SALES__COMMISSION_READ)
        public List<Map<String, Object>> statements(@RequestParam Map<String, String> q) {
            return svc.statements(q);
        }

        @GetMapping("/commissions/statements/{id}")
        @RequirePermissions(Permissions.SALES__COMMISSION_READ)
        public Map<String, Object> statement(@PathVariable String id) {
            return svc.statement(id);
        }

        @PostMapping("/commissions/statements")
        @RequirePermissions(Permissions.SALES__COMMISSION_MANAGE)
        public List<Map<String, Object>> generate(@RequestBody(required = false) Map<String, Object> b) {
            Map<String, Object> in = body(b);
            Object sp = in.get("salespersonId");
            return svc.generate(in.get("month") == null ? null : in.get("month").toString(),
                    sp == null || sp.toString().isBlank() ? null : sp.toString());
        }

        @PostMapping("/commissions/statements/{id}/approve")
        @RequirePermissions(Permissions.SALES__COMMISSION_MANAGE)
        public Map<String, Object> approve(@PathVariable String id) {
            return svc.approve(id);
        }

        @PostMapping("/commissions/statements/{id}/adjustments")
        @RequirePermissions(Permissions.SALES__COMMISSION_MANAGE)
        public Map<String, Object> adjust(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.addAdjustment(id, b);
        }

        @PostMapping("/commissions/statements/{id}/disputes")
        @RequirePermissions(Permissions.SALES__COMMISSION_READ)
        public Map<String, Object> dispute(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.dispute(id, b);
        }

        @PostMapping("/commissions/disputes/{id}/resolve")
        @RequirePermissions(Permissions.SALES__COMMISSION_MANAGE)
        public Map<String, Object> resolveDispute(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.resolveDispute(id, b);
        }

        @PostMapping("/commissions/statements/{id}/paid")
        @com.inventoryerp.kernel.idempotency.Idempotent
        @RequirePermissions(Permissions.SALES__COMMISSION_MANAGE)
        public Map<String, Object> paid(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.markPaid(id, b == null ? Map.of() : b);
        }

        @GetMapping("/commissions/statements/{id}/pdf")
        @RequirePermissions(Permissions.SALES__COMMISSION_READ)
        public org.springframework.http.ResponseEntity<byte[]> pdf(@PathVariable String id) {
            return org.springframework.http.ResponseEntity.ok().header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "application/pdf")
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"commission-" + id + ".pdf\"").body(svc.pdf(id));
        }

        @PostMapping("/commissions/statements/{id}/cancel")
        @RequirePermissions(Permissions.SALES__COMMISSION_MANAGE)
        public Map<String, Object> cancel(@PathVariable String id) {
            return svc.cancel(id);
        }

        @GetMapping("/targets")
        @RequirePermissions(Permissions.SALES__REPORT_READ)
        public List<Map<String, Object>> targets(@RequestParam(required = false) String month) {
            return svc.targets(month);
        }

        @GetMapping("/targets/achievement")
        @RequirePermissions(Permissions.SALES__REPORT_READ)
        public List<Map<String, Object>> achievement(@RequestParam(required = false) String month) {
            return svc.achievement(month);
        }

        @PostMapping("/targets")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__TARGET_MANAGE)
        public Map<String, Object> createTarget(@RequestBody Map<String, Object> b) {
            return svc.saveTarget(null, body(b));
        }

        @PutMapping("/targets/{id}")
        @RequirePermissions(Permissions.SALES__TARGET_MANAGE)
        public Map<String, Object> updateTarget(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.saveTarget(id, body(b));
        }

        @DeleteMapping("/targets/{id}")
        @RequirePermissions(Permissions.SALES__TARGET_MANAGE)
        public Map<String, Object> deleteTarget(@PathVariable String id) {
            return svc.deleteTarget(id);
        }
    }
}
