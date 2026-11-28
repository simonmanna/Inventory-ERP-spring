package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.invoicing.application.CustomerDepositService;
import com.inventoryerp.sales.application.SalesAtpService;
import com.inventoryerp.sales.application.CashSaleFiscalService;
import com.inventoryerp.sales.application.SalesBillingService;
import com.inventoryerp.sales.application.SalesCashReversalService;
import com.inventoryerp.sales.application.SalesCashSaleService;
import com.inventoryerp.sales.application.SalesFulfilmentService;
import com.inventoryerp.sales.application.SalesOrderChangeService;
import com.inventoryerp.sales.application.SalesOrderService;
import com.inventoryerp.sales.application.SalesQuotationService;
import com.inventoryerp.sales.application.SalesQuotationTemplateService;
import com.inventoryerp.sales.application.SalesReportService;
import com.inventoryerp.sales.application.SalesSettingsService;
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

/**
 * Sales / order-to-cash routes (ADR-S011): quotations, sales orders, change requests, reports and settings. New
 * routes beyond the NestJS reference; {@code /crm/quotations} remains as a facade over the same service.
 */
public final class SalesController {

    private SalesController() {}

    static Map<String, Object> body(Map<String, Object> b) {
        return b == null ? new HashMap<>() : b;
    }

    static String text(Map<String, Object> b, String key) {
        Object v = body(b).get(key);
        return v == null ? null : v.toString();
    }

    /** {@code /sales/quotations}: quotation lifecycle (same service as {@code /crm/quotations}). */
    @RestController
    @RequestMapping("/sales/quotations")
    public static class QuotationController {

        private final SalesQuotationService svc;

        public QuotationController(SalesQuotationService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.SALES__QUOTATION_READ)
        public Map<String, Object> list(@RequestParam Map<String, String> q) {
            return svc.list(q);
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.SALES__QUOTATION_READ)
        public Map<String, Object> get(@PathVariable String id) {
            return svc.get(id);
        }

        @GetMapping("/{id}/revisions")
        @RequirePermissions(Permissions.SALES__QUOTATION_READ)
        public List<Map<String, Object>> revisions(@PathVariable String id) {
            return svc.revisions(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> create(@RequestBody Map<String, Object> b) {
            return svc.create(body(b));
        }

        @PutMapping("/{id}")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.update(id, body(b));
        }

        @DeleteMapping("/{id}")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> delete(@PathVariable String id) {
            return svc.delete(id);
        }

        @PostMapping("/{id}/submit")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> submit(@PathVariable String id) {
            return svc.submit(id);
        }

        @PostMapping("/{id}/approve")
        @RequirePermissions(Permissions.SALES__QUOTATION_APPROVE)
        public Map<String, Object> approve(@PathVariable String id,
                @RequestBody(required = false) Map<String, Object> b) {
            return svc.approve(id, text(b, "comment"));
        }

        @PostMapping("/{id}/reject")
        @RequirePermissions(Permissions.SALES__QUOTATION_APPROVE)
        public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.reject(id, text(b, "reason"));
        }

        @PostMapping("/{id}/send")
        @RequirePermissions(Permissions.SALES__QUOTATION_SEND)
        public Map<String, Object> send(@PathVariable String id) {
            return svc.send(id);
        }

        /** Body {@code {optionalLineIds?}}: the optional products the customer took. */
        @PostMapping("/{id}/accept")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> accept(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.accept(id, SalesQuotationService.optionalLineIds(b));
        }

        @PostMapping("/{id}/duplicate")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> duplicate(@PathVariable String id) {
            return svc.duplicate(id);
        }

        /** Price lines in the editor as saving will price them (header context + variant / unit / quantity). */
        @PostMapping("/resolve-prices")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> resolvePrices(@RequestBody(required = false) Map<String, Object> b) {
            return svc.previewPrices(body(b));
        }

        @PostMapping("/{id}/decline")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> decline(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.decline(id, text(b, "reason"), text(b, "lostReasonId"));
        }

        @PostMapping("/{id}/cancel")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> cancel(@PathVariable String id) {
            return svc.cancel(id);
        }

        @PostMapping("/{id}/revise")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> revise(@PathVariable String id) {
            return svc.revise(id);
        }

        @PostMapping("/{id}/convert")
        // converting twice would create two sales documents: the Idempotency-Key is mandatory (production-readiness P1)
        @Idempotent(required = true)
        @RequirePermissions(Permissions.SALES__QUOTATION_CONVERT)
        public Map<String, Object> convert(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            Map<String, Object> in = body(b);
            in.putIfAbsent("target", "sales_order");
            return svc.convert(id, in);
        }
    }

    /**
     * {@code /sales/quotation-templates}: reusable lines, notes and terms for repeat offers (sales or CRM quotation
     * users).
     */
    @RestController
    @RequestMapping("/sales/quotation-templates")
    public static class QuotationTemplateController {

        private final SalesQuotationTemplateService svc;

        public QuotationTemplateController(SalesQuotationTemplateService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(value = {Permissions.SALES__QUOTATION_READ, Permissions.CRM__QUOTATION_READ}, anyOf = true)
        public List<Map<String, Object>> list() {
            return svc.list();
        }

        @GetMapping("/{id}")
        @RequirePermissions(value = {Permissions.SALES__QUOTATION_READ, Permissions.CRM__QUOTATION_READ}, anyOf = true)
        public Map<String, Object> get(@PathVariable String id) {
            return svc.get(id);
        }

        /** Body {@code {name, quotationId, description?}}. */
        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(value = {Permissions.SALES__QUOTATION_WRITE, Permissions.CRM__QUOTATION_WRITE}, anyOf = true)
        public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> b) {
            return svc.create(body(b));
        }

        @DeleteMapping("/{id}")
        @RequirePermissions(value = {Permissions.SALES__QUOTATION_WRITE, Permissions.CRM__QUOTATION_WRITE}, anyOf = true)
        public Map<String, Object> delete(@PathVariable String id) {
            return svc.delete(id);
        }
    }

    /** {@code /sales/orders}: B2B sales orders. */
    @RestController
    @RequestMapping("/sales/orders")
    public static class OrderController {

        private final SalesOrderService svc;
        private final SalesOrderChangeService changes;
        private final SalesAtpService atp;
        private final SalesFulfilmentService fulfilment;
        private final SalesBillingService billing;
        private final CustomerDepositService deposits;
        private final SalesCashSaleService cashSales;
        private final CashSaleFiscalService fiscal;
        private final SalesCashReversalService reversals;

        public OrderController(SalesOrderService svc, SalesOrderChangeService changes, SalesAtpService atp,
                SalesFulfilmentService fulfilment, SalesBillingService billing, CustomerDepositService deposits,
                SalesCashSaleService cashSales, CashSaleFiscalService fiscal, SalesCashReversalService reversals) {
            this.billing = billing;
            this.deposits = deposits;
            this.cashSales = cashSales;
            this.fiscal = fiscal;
            this.reversals = reversals;
            this.svc = svc;
            this.changes = changes;
            this.atp = atp;
            this.fulfilment = fulfilment;
        }

        /** ADR-S094 C3: orders and cash sales (filter {@code saleType}); each type with its own read permission. */
        @GetMapping
        @RequirePermissions(value = {Permissions.SALES__ORDER_READ, Permissions.SALES__CASH_SALE_READ}, anyOf = true)
        public Map<String, Object> list(@RequestParam Map<String, String> q) {
            return svc.list(q);
        }

        /** ADR-S094 C3: the payment modes a cash sale can take (cash, mobile money, bank, card). */
        @GetMapping("/payment-modes")
        @RequirePermissions(value = {Permissions.SALES__CASH_SALE_READ, Permissions.SALES__CASH_SALE_CREATE}, anyOf = true)
        public List<Map<String, Object>> paymentModes() {
            return cashSales.paymentModes();
        }

        @GetMapping("/{id}")
        @RequirePermissions(value = {Permissions.SALES__ORDER_READ, Permissions.SALES__CASH_SALE_READ}, anyOf = true)
        public Map<String, Object> get(@PathVariable String id) {
            return svc.get(id);
        }

        @GetMapping("/{id}/revisions")
        @RequirePermissions(value = {Permissions.SALES__ORDER_READ, Permissions.SALES__CASH_SALE_READ}, anyOf = true)
        public List<Map<String, Object>> revisions(@PathVariable String id) {
            return svc.revisions(id);
        }

        /** ADR-S094 C3: the cash sale's timeline (tenders, verification, confirm, fiscal). */
        @GetMapping("/{id}/events")
        @RequirePermissions(value = {Permissions.SALES__ORDER_READ, Permissions.SALES__CASH_SALE_READ}, anyOf = true)
        public List<Map<String, Object>> events(@PathVariable String id) {
            return cashSales.events(id);
        }

        /** ADR-S094 C3: a new sale; {@code saleType: cash} drafts a cash sale at the caller's branch. */
        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(value = {Permissions.SALES__ORDER_WRITE, Permissions.SALES__CASH_SALE_CREATE}, anyOf = true)
        public Map<String, Object> create(@RequestBody Map<String, Object> b) {
            SalesOrderService.requireType(SalesOrderService.saleType(body(b)), Permissions.SALES__ORDER_WRITE,
                    Permissions.SALES__CASH_SALE_CREATE);
            return svc.create(body(b));
        }

        @PutMapping("/{id}")
        @RequirePermissions(value = {Permissions.SALES__ORDER_WRITE, Permissions.SALES__CASH_SALE_CREATE}, anyOf = true)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
            svc.requireFor(id, Permissions.SALES__ORDER_WRITE, Permissions.SALES__CASH_SALE_CREATE);
            return svc.update(id, body(b));
        }

        @DeleteMapping("/{id}")
        @RequirePermissions(value = {Permissions.SALES__ORDER_WRITE, Permissions.SALES__CASH_SALE_CREATE}, anyOf = true)
        public Map<String, Object> delete(@PathVariable String id) {
            svc.requireFor(id, Permissions.SALES__ORDER_WRITE, Permissions.SALES__CASH_SALE_CREATE);
            return svc.delete(id);
        }

        /**
         * Confirm a draft. ADR-S094 C3: a cash sale is confirmed with its {@code handover} and runs pickup, invoice,
         * receipts and the fiscal outbox in one transaction.
         */
        @PostMapping("/{id}/confirm")
        @Idempotent(required = true)
        @RequirePermissions(value = {Permissions.SALES__ORDER_CONFIRM, Permissions.SALES__CASH_SALE_CONFIRM}, anyOf = true)
        public Map<String, Object> confirm(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.confirm(id, b);
        }

        /** ADR-S094 C3: capture a tender line on a draft cash sale. */
        @PostMapping("/{id}/payments")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__CASH_SALE_CREATE)
        public Map<String, Object> addPayment(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return cashSales.addPayment(id, body(b));
        }

        @DeleteMapping("/{id}/payments/{paymentId}")
        @RequirePermissions(Permissions.SALES__CASH_SALE_CREATE)
        public Map<String, Object> removePayment(@PathVariable String id, @PathVariable String paymentId) {
            return cashSales.removePayment(id, paymentId);
        }

        /** ADR-S094 C3: verify or fail a pending tender line (cash: the confirmer or a verifier; others: a verifier). */
        @PostMapping("/{id}/payments/{paymentId}/verify")
        @RequirePermissions(value = {Permissions.SALES__CASH_SALE_VERIFY_PAYMENT, Permissions.SALES__CASH_SALE_CONFIRM},
                anyOf = true)
        public Map<String, Object> verifyPayment(@PathVariable String id, @PathVariable String paymentId,
                @RequestBody(required = false) Map<String, Object> b) {
            return cashSales.verifyPayment(id, paymentId, body(b));
        }

        /** ADR-S094 C3: the cash sale's fiscal operations. */
        @GetMapping("/{id}/fiscal")
        @RequirePermissions(Permissions.SALES__CASH_SALE_READ)
        public List<Map<String, Object>> fiscal(@PathVariable String id) {
            svc.require(id);
            return fiscal.forSale(id);
        }

        /** ADR-S094 C3: send the cash sale's open fiscal operations again (stable references; never a second document). */
        @PostMapping("/{id}/fiscal/retry")
        @RequirePermissions(Permissions.SALES__CASH_SALE_READ)
        public List<Map<String, Object>> retryFiscal(@PathVariable String id) {
            svc.require(id);
            return fiscal.retryForSale(id);
        }

        /**
         * ADR-S094 C3b (§5.5): settle a fiscal operation whose outcome is unknown by hand, after checking with the
         * provider ({@code resolution}: {@code issued} with its {@code fiscalNumber}, or {@code not_received}: sent
         * again under the same reference; a {@code note}).
         */
        @PostMapping("/{id}/fiscal/{fiscalId}/resolve")
        @RequirePermissions(Permissions.SALES__CASH_SALE_REVIEW)
        public Map<String, Object> resolveFiscal(@PathVariable String id, @PathVariable String fiscalId,
                @RequestBody Map<String, Object> b) {
            svc.require(id);
            return fiscal.resolve(id, fiscalId, body(b));
        }

        /**
         * ADR-S094 C3b (§7.4): reverse a completed cash sale — credit-note approval if required, the fiscal cancel, then
         * in one transaction the return of every line, the credit note, the refund over the tenders (or the credit kept)
         * and the sale cancelled as reversed. A money route: an Idempotency-Key is required.
         */
        @PostMapping("/{id}/reverse")
        @Idempotent(required = true)
        @RequirePermissions(Permissions.SALES__CASH_SALE_VOID)
        public Map<String, Object> reverse(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return reversals.reverse(id, body(b));
        }

        /** ADR-S094 §4.2: send a draft that needs approval to its approvers (explicit; never done by confirm). */
        @PostMapping("/{id}/submit-approval")
        @RequirePermissions(value = {Permissions.SALES__ORDER_WRITE, Permissions.SALES__CASH_SALE_SUBMIT}, anyOf = true)
        public Map<String, Object> submitApproval(@PathVariable String id,
                @RequestBody(required = false) Map<String, Object> b) {
            svc.requireFor(id, Permissions.SALES__ORDER_WRITE, Permissions.SALES__CASH_SALE_SUBMIT);
            return svc.submitApproval(id, text(b, "justification"));
        }

        /** ADR-S094 §4.2: take a submission back (the submitter); the draft can be edited again. */
        @PostMapping("/{id}/withdraw-approval")
        @RequirePermissions(value = {Permissions.SALES__ORDER_WRITE, Permissions.SALES__CASH_SALE_SUBMIT}, anyOf = true)
        public Map<String, Object> withdrawApproval(@PathVariable String id,
                @RequestBody(required = false) Map<String, Object> b) {
            svc.requireFor(id, Permissions.SALES__ORDER_WRITE, Permissions.SALES__CASH_SALE_SUBMIT);
            return svc.withdrawApproval(id, text(b, "reason"));
        }

        /** ADR-S094 §4.4: keep the sale's open credit on the customer's account; the sale is settled. */
        @PostMapping("/{id}/keep-credit")
        @RequirePermissions(Permissions.SALES__CREDIT_NOTE_WRITE)
        public Map<String, Object> keepCredit(@PathVariable String id,
                @RequestBody(required = false) Map<String, Object> b) {
            return svc.keepCredit(id, text(b, "reason"));
        }

        @PostMapping("/{id}/approve")
        @Idempotent(required = true)
        @RequirePermissions(value = {Permissions.SALES__ORDER_APPROVE, Permissions.SALES__CASH_SALE_APPROVE}, anyOf = true)
        public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            svc.requireFor(id, Permissions.SALES__ORDER_APPROVE, Permissions.SALES__CASH_SALE_APPROVE);
            return svc.approve(id, text(b, "comment"));
        }

        @PostMapping("/{id}/reject")
        @RequirePermissions(value = {Permissions.SALES__ORDER_APPROVE, Permissions.SALES__CASH_SALE_APPROVE}, anyOf = true)
        public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            svc.requireFor(id, Permissions.SALES__ORDER_APPROVE, Permissions.SALES__CASH_SALE_APPROVE);
            return svc.reject(id, text(b, "reason"));
        }

        @PostMapping("/{id}/hold")
        @RequirePermissions(Permissions.SALES__ORDER_HOLD_RELEASE)
        public Map<String, Object> hold(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.hold(id, text(b, "reason"));
        }

        @PostMapping("/{id}/release")
        @RequirePermissions(Permissions.SALES__ORDER_HOLD_RELEASE)
        public Map<String, Object> release(@PathVariable String id) {
            return svc.release(id);
        }

        @PostMapping("/{id}/cancel")
        @RequirePermissions(Permissions.SALES__ORDER_CANCEL)
        public Map<String, Object> cancel(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.cancel(id, text(b, "reason"));
        }

        @PostMapping("/{id}/duplicate")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__ORDER_WRITE)
        public Map<String, Object> duplicate(@PathVariable String id) {
            return svc.duplicate(id);
        }

        @PostMapping("/{id}/lines/{lineId}/close-short")
        @RequirePermissions(Permissions.SALES__ORDER_CLOSE_SHORT)
        public Map<String, Object> closeShort(@PathVariable String id, @PathVariable String lineId,
                @RequestBody(required = false) Map<String, Object> b) {
            return svc.closeShort(id, lineId, text(b, "reason"));
        }

        /**
         * ADR-S094 C2b: credit what the line invoiced beyond what was delivered (posted quantity credit notes, released
         * against the contract liability), then close it short. A money route: an Idempotency-Key is required.
         */
        @PostMapping("/{id}/lines/{lineId}/credit-undelivered")
        @Idempotent(required = true)
        @RequirePermissions({Permissions.SALES__ORDER_CLOSE_SHORT, Permissions.SALES__CREDIT_NOTE_WRITE})
        public Map<String, Object> creditUndelivered(@PathVariable String id, @PathVariable String lineId,
                @RequestBody(required = false) Map<String, Object> b) {
            return billing.creditUndelivered(id, lineId, text(b, "reason"));
        }

        @GetMapping("/{id}/atp")
        @RequirePermissions(Permissions.SALES__ORDER_READ)
        public Map<String, Object> atp(@PathVariable String id) {
            svc.require(id);
            return atp.forOrder(id);
        }

        @PostMapping("/{id}/lines/{lineId}/source-location")
        @RequirePermissions(Permissions.SALES__ORDER_CONFIRM)
        public Map<String, Object> pickLocation(@PathVariable String id, @PathVariable String lineId,
                @RequestBody Map<String, Object> b) {
            svc.require(id);
            atp.pickLocation(id, lineId, text(b, "locationId"));
            return svc.get(id);
        }

        @GetMapping("/{id}/reservations")
        @RequirePermissions(Permissions.SALES__ORDER_READ)
        public Map<String, Object> reservations(@PathVariable String id) {
            svc.require(id);
            return atp.reservationsOf(id);
        }

        @PostMapping("/{id}/reserve")
        @RequirePermissions(Permissions.SALES__ORDER_CONFIRM)
        public Map<String, Object> reserve(@PathVariable String id) {
            svc.require(id);
            return atp.reserveOrder(id);
        }

        @PostMapping("/{id}/delivery-orders")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__ORDER_CONFIRM)
        public Map<String, Object> deliver(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return fulfilment.createDelivery(id, body(b));
        }

        @GetMapping("/{id}/fulfilment")
        @RequirePermissions(Permissions.SALES__ORDER_READ)
        public Map<String, Object> fulfilment(@PathVariable String id) {
            return fulfilment.fulfilment(id);
        }

        @PostMapping("/{id}/rebuild")
        @RequirePermissions(Permissions.SALES__CONFIGURE)
        public List<Map<String, Object>> rebuild(@PathVariable String id) {
            svc.require(id);
            return fulfilment.rebuild(id);
        }

        @PostMapping("/{id}/lines/{lineId}/billable")
        @RequirePermissions(Permissions.SALES__BILLING_OVERRIDE)
        public Map<String, Object> billable(@PathVariable String id, @PathVariable String lineId,
                @RequestBody Map<String, Object> b) {
            return fulfilment.overrideBillable(id, lineId, body(b));
        }

        @GetMapping("/{id}/billing")
        @RequirePermissions(Permissions.SALES__ORDER_READ)
        public Map<String, Object> billing(@PathVariable String id) {
            return billing.preview(id);
        }

        @PostMapping("/{id}/invoices")
        @Idempotent(required = true)
        @RequirePermissions(Permissions.SALES__INVOICE_CREATE)
        public Map<String, Object> invoice(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return billing.createInvoice(id, body(b));
        }

        @PostMapping("/{id}/deposits")
        @Idempotent(required = true)
        @RequirePermissions(Permissions.SALES__DEPOSIT_RECORD)
        public Map<String, Object> deposit(@PathVariable String id, @RequestBody Map<String, Object> b) {
            Map<String, Object> o = svc.require(id);
            Map<String, Object> in = body(b);
            Object amount = in.get("amount");
            deposits.assertAdvanceAllowed(id);
            return deposits.receive(new CustomerDepositService.DepositInput(String.valueOf(o.get("partnerId")), id,
                    amount == null ? null : new java.math.BigDecimal(amount.toString()), text(b, "paymentMethod"),
                    text(b, "accountId"), text(b, "receivedDate"), text(b, "reference"), text(b, "notes"),
                    text(b, "currencyCode"), in.get("exchangeRate") == null ? null : new java.math.BigDecimal(in.get(
                            "exchangeRate").toString())));
        }

        @PostMapping("/{id}/apply-deposits")
        @Idempotent(required = true)
        @RequirePermissions(Permissions.SALES__DEPOSIT_RECORD)
        public List<Map<String, Object>> applyDeposits(@PathVariable String id, @RequestBody Map<String, Object> b) {
            svc.require(id);
            return billing.applyDeposits(id, text(b, "documentId"));
        }

        @GetMapping("/{id}/change-requests")
        @RequirePermissions(Permissions.SALES__ORDER_READ)
        public List<Map<String, Object>> changeRequests(@PathVariable String id) {
            return changes.list(id);
        }

        @PostMapping("/{id}/change-requests")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__ORDER_AMEND)
        public Map<String, Object> requestChange(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return changes.request(id, body(b));
        }
    }

    /** {@code /sales/change-requests}: decide amendments of confirmed orders. */
    @RestController
    @RequestMapping("/sales/change-requests")
    public static class ChangeRequestController {

        private final SalesOrderChangeService svc;

        public ChangeRequestController(SalesOrderChangeService svc) {
            this.svc = svc;
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.SALES__ORDER_READ)
        public Map<String, Object> get(@PathVariable String id) {
            return svc.get(id);
        }

        @PostMapping("/{id}/approve")
        @Idempotent(required = true)
        @RequirePermissions(Permissions.SALES__ORDER_AMEND_APPROVE)
        public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.approve(id, text(b, "comment"));
        }

        @PostMapping("/{id}/reject")
        @RequirePermissions(Permissions.SALES__ORDER_AMEND_APPROVE)
        public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.reject(id, text(b, "comment"));
        }

        @PostMapping("/{id}/withdraw")
        @RequirePermissions(Permissions.SALES__ORDER_AMEND)
        public Map<String, Object> withdraw(@PathVariable String id) {
            return svc.withdraw(id);
        }
    }

    /** {@code /sales/reports}, {@code /sales/settings}. */
    @RestController
    @RequestMapping("/sales")
    public static class AdminController {

        private final SalesReportService reports;
        private final SalesSettingsService settings;
        private final SalesAtpService atp;
        private final SalesBillingService billing;

        public AdminController(SalesReportService reports, SalesSettingsService settings, SalesAtpService atp,
                SalesBillingService billing) {
            this.billing = billing;
            this.reports = reports;
            this.settings = settings;
            this.atp = atp;
        }

        @PostMapping("/atp")
        @RequirePermissions(Permissions.SALES__ORDER_READ)
        public Map<String, Object> atp(@RequestBody Map<String, Object> b) {
            return atp.check(body(b));
        }

        @PostMapping("/invoices/batch")
        @Idempotent(required = true)
        @RequirePermissions(Permissions.SALES__INVOICE_CREATE)
        public List<Map<String, Object>> batch(@RequestBody Map<String, Object> b) {
            return billing.batch(body(b));
        }

        @GetMapping("/reports/backorders")
        @RequirePermissions(Permissions.SALES__REPORT_READ)
        public List<Map<String, Object>> backorders() {
            return atp.backorders();
        }

        @GetMapping("/reports/summary")
        @RequirePermissions(Permissions.SALES__REPORT_READ)
        public Map<String, Object> summary() {
            return reports.summary();
        }

        @GetMapping("/settings")
        @RequirePermissions(Permissions.SALES__ORDER_READ)
        public Map<String, Object> settings() {
            return settings.settings();
        }

        @PutMapping("/settings")
        @RequirePermissions(Permissions.SALES__CONFIGURE)
        public Map<String, Object> update(@RequestBody Map<String, Object> b) {
            return settings.update(body(b));
        }
    }
}
