package com.inventoryerp.sales.web;

import static com.inventoryerp.sales.web.SalesController.body;
import static com.inventoryerp.sales.web.SalesController.text;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.BranchSalesProfileService;
import com.inventoryerp.sales.application.CashSaleFormExporter;
import com.inventoryerp.sales.application.CashSaleService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/** Branch cash sales and branch sales profiles (ADR-S056, docs/spec/cash-sales.md §9). Spring-native routes. */
public final class CashSaleController {

    private CashSaleController() {}

    /** {@code /sales/cash-sales}: showroom capture and head-office processing. */
    @RestController
    @RequestMapping("/sales/cash-sales")
    public static class Submissions {

        private final CashSaleService svc;
        private final CashSaleFormExporter form;

        public Submissions(CashSaleService svc, CashSaleFormExporter form) {
            this.svc = svc;
            this.form = form;
        }

        @GetMapping
        @RequirePermissions(Permissions.SALES__CASH_SALE_READ)
        public Map<String, Object> list(@RequestParam Map<String, String> q) {
            return svc.list(q);
        }

        @GetMapping("/payment-modes")
        @RequirePermissions(Permissions.SALES__CASH_SALE_READ)
        public List<Map<String, Object>> paymentModes() {
            return svc.paymentModes();
        }

        @GetMapping("/dashboard")
        @RequirePermissions(Permissions.SALES__CASH_SALE_READ)
        public Map<String, Object> dashboard(@RequestParam(defaultValue = "branch") String scope) {
            return svc.dashboard(scope);
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.SALES__CASH_SALE_READ)
        public Map<String, Object> get(@PathVariable String id) {
            return svc.get(id);
        }

        /** The sale as the printable "CASH SALES FORM" Excel workbook. */
        @GetMapping("/{id}/excel")
        @RequirePermissions(Permissions.SALES__CASH_SALE_READ)
        public ResponseEntity<byte[]> formXlsx(@PathVariable String id) {
            CashSaleFormExporter.Export e = form.export(id);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, CashSaleFormExporter.CONTENT_TYPE)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + e.filename() + "\"")
                    .body(e.content());
        }

        /** The same form as an A4 PDF. */
        @GetMapping("/{id}/pdf")
        @RequirePermissions(Permissions.SALES__CASH_SALE_READ)
        public ResponseEntity<byte[]> formPdf(@PathVariable String id) {
            CashSaleFormExporter.Export e = form.exportPdf(id);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, CashSaleFormExporter.PDF_CONTENT_TYPE)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + e.filename() + "\"")
                    .body(e.content());
        }

        @GetMapping("/{id}/timeline")
        @RequirePermissions(Permissions.SALES__CASH_SALE_READ)
        public List<Map<String, Object>> timeline(@PathVariable String id) {
            return svc.timeline(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__CASH_SALE_CREATE)
        public Map<String, Object> create(@RequestBody Map<String, Object> b) {
            return svc.create(body(b));
        }

        @PatchMapping("/{id}")
        @RequirePermissions(Permissions.SALES__CASH_SALE_CREATE)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.update(id, body(b));
        }

        @PostMapping("/{id}/discard")
        @RequirePermissions(Permissions.SALES__CASH_SALE_CREATE)
        public Map<String, Object> discard(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.discard(id, text(b, "reason"));
        }

        @PostMapping("/{id}/payments")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__CASH_SALE_CREATE)
        public Map<String, Object> addPayment(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.addPayment(id, body(b));
        }

        @DeleteMapping("/{id}/payments/{paymentId}")
        @RequirePermissions(Permissions.SALES__CASH_SALE_CREATE)
        public Map<String, Object> removePayment(@PathVariable String id, @PathVariable String paymentId) {
            return svc.removePayment(id, paymentId);
        }

        /** Cash: confirmer or verifier; mobile money / bank: verifier (checked by the service). */
        @PostMapping("/{id}/payments/{paymentId}/verify")
        @RequirePermissions(Permissions.SALES__CASH_SALE_READ)
        public Map<String, Object> verifyPayment(@PathVariable String id, @PathVariable String paymentId,
                @RequestBody(required = false) Map<String, Object> b) {
            return svc.verifyPayment(id, paymentId, body(b));
        }

        @PostMapping("/{id}/confirm")
        @Idempotent
        @RequirePermissions(Permissions.SALES__CASH_SALE_CONFIRM)
        public Map<String, Object> confirm(@PathVariable String id) {
            return svc.confirm(id);
        }

        @PostMapping("/{id}/submit")
        @RequirePermissions(Permissions.SALES__CASH_SALE_SUBMIT)
        public Map<String, Object> submit(@PathVariable String id) {
            return svc.submit(id);
        }

        @PostMapping("/{id}/resubmit")
        @RequirePermissions(Permissions.SALES__CASH_SALE_SUBMIT)
        public Map<String, Object> resubmit(@PathVariable String id) {
            return svc.resubmit(id);
        }

        @PatchMapping("/{id}/delivery")
        @RequirePermissions(Permissions.SALES__CASH_SALE_CREATE)
        public Map<String, Object> updateDelivery(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.updateDelivery(id, body(b));
        }

        @PostMapping("/{id}/amend")
        @RequirePermissions(Permissions.SALES__CASH_SALE_SUBMIT)
        public Map<String, Object> amend(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.amend(id, body(b));
        }

        @PostMapping("/{id}/fiscal/correct")
        @RequirePermissions(Permissions.SALES__CASH_SALE_SUBMIT)
        public Map<String, Object> fiscalCorrect(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.fiscalCorrect(id, body(b));
        }

        @PostMapping("/{id}/fiscal/retry")
        @RequirePermissions(Permissions.SALES__CASH_SALE_READ)
        public Map<String, Object> fiscalRetry(@PathVariable String id) {
            return svc.fiscalRetry(id);
        }

        @PostMapping("/{id}/start-review")
        @RequirePermissions(Permissions.SALES__CASH_SALE_REVIEW)
        public Map<String, Object> startReview(@PathVariable String id) {
            return svc.startReview(id);
        }

        @PostMapping("/{id}/return")
        @RequirePermissions(Permissions.SALES__CASH_SALE_REVIEW)
        public Map<String, Object> returnForCorrection(@PathVariable String id,
                @RequestBody(required = false) Map<String, Object> b) {
            return svc.returnForCorrection(id, text(b, "reason"));
        }

        @PostMapping("/{id}/approve")
        @RequirePermissions(Permissions.SALES__CASH_SALE_APPROVE)
        public Map<String, Object> approve(@PathVariable String id) {
            return svc.approve(id);
        }

        @PostMapping("/{id}/reject")
        @RequirePermissions(Permissions.SALES__CASH_SALE_APPROVE)
        public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.reject(id, text(b, "reason"));
        }

        @PostMapping("/{id}/reopen")
        @RequirePermissions(Permissions.SALES__CASH_SALE_APPROVE)
        public Map<String, Object> reopen(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.reopen(id, text(b, "reason"));
        }

        @PostMapping("/{id}/assign")
        @RequirePermissions(Permissions.SALES__CASH_SALE_APPROVE)
        public Map<String, Object> assign(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.assign(id, text(b, "ownerUserId"));
        }

        @PostMapping("/{id}/generate-invoice")
        @Idempotent
        @RequirePermissions(Permissions.SALES__CASH_SALE_INVOICE)
        public Map<String, Object> generateInvoice(@PathVariable String id) {
            return svc.generateInvoice(id);
        }

        @PostMapping("/{id}/cancel-invoice")
        @Idempotent
        @RequirePermissions(Permissions.SALES__CASH_SALE_INVOICE)
        public Map<String, Object> cancelInvoice(@PathVariable String id,
                @RequestBody(required = false) Map<String, Object> b) {
            return svc.cancelInvoice(id, text(b, "reason"));
        }

        @PostMapping("/{id}/void")
        @Idempotent
        @RequirePermissions(Permissions.SALES__CASH_SALE_VOID)
        public Map<String, Object> voidSale(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.voidSale(id, body(b));
        }
    }

    /** {@code /sales/branch-profiles}: branch sales configuration. */
    @RestController
    @RequestMapping("/sales/branch-profiles")
    public static class Profiles {

        private final BranchSalesProfileService svc;

        public Profiles(BranchSalesProfileService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.SALES__CASH_SALE_READ)
        public List<Map<String, Object>> list() {
            return svc.list();
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.SALES__CASH_SALE_READ)
        public Map<String, Object> get(@PathVariable String id) {
            return svc.get(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__BRANCH_PROFILE_MANAGE)
        public Map<String, Object> create(@RequestBody Map<String, Object> b) {
            return svc.create(body(b));
        }

        @PatchMapping("/{id}")
        @RequirePermissions(Permissions.SALES__BRANCH_PROFILE_MANAGE)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.update(id, body(b));
        }
    }
}
