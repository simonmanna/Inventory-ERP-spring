package com.inventoryerp.invoicing.web;

import com.inventoryerp.accounting.application.ReportExportService.Export;
import com.inventoryerp.invoicing.application.InvoiceDeliveryService;
import com.inventoryerp.invoicing.application.OpeningBalanceService;
import com.inventoryerp.invoicing.application.OpeningBalanceService.AccountLine;
import com.inventoryerp.invoicing.application.OpeningBalanceService.OpeningInput;
import com.inventoryerp.invoicing.application.OpeningBalanceService.PartnerLine;
import com.inventoryerp.invoicing.application.StatementService;
import com.inventoryerp.invoicing.application.TaxReportService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-S005 finance extensions on the invoicing side (no Nest counterpart): opening balances, tax summary,
 * customer / supplier statements (JSON, PDF, e-mail) and invoice PDF / e-mail.
 */
@RestController
public class FinanceExtensionsController {

    static final String DATE = "^\\d{4}-\\d{2}-\\d{2}.*$";
    static final String DATE_MSG = "must be a valid ISO 8601 date string";
    static final String NUM = "must be a number conforming to the specified constraints";

    /** One GL opening line. */
    public record AccountLineRequest(
            @NotNull(message = "must be a string") String accountId,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal debit,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal credit,
            String description) {}

    /** One customer / supplier opening balance. */
    public record PartnerLineRequest(
            @NotNull(message = "must be a string") String partnerId,
            @NotNull(message = NUM) @DecimalMin(value = "0.01", message = "must not be less than 0.01") BigDecimal amount,
            @Pattern(regexp = DATE, message = DATE_MSG) String dueDate,
            @Size(max = 120, message = "must be shorter than or equal to 120 characters") String reference) {}

    /** {@code POST /opening-balances}. */
    public record OpeningBalanceRequest(
            @NotNull(message = DATE_MSG) @Pattern(regexp = DATE, message = DATE_MSG) String date,
            @Size(max = 120, message = "must be shorter than or equal to 120 characters") String reference,
            @Valid List<AccountLineRequest> accounts,
            @Valid List<PartnerLineRequest> customers,
            @Valid List<PartnerLineRequest> suppliers) {}

    /** E-mail body for invoices and statements. */
    public record EmailRequest(
            @Email(message = "to must be an email") String to,
            @Size(max = 2000, message = "must be shorter than or equal to 2000 characters") String message) {}

    private final OpeningBalanceService openings;
    private final TaxReportService tax;
    private final StatementService statements;
    private final InvoiceDeliveryService delivery;

    public FinanceExtensionsController(OpeningBalanceService openings, TaxReportService tax,
            StatementService statements, InvoiceDeliveryService delivery) {
        this.openings = openings;
        this.tax = tax;
        this.statements = statements;
        this.delivery = delivery;
    }

    private static ResponseEntity<byte[]> pdf(String filename, byte[] content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename.replace("\"", "") + "\"")
                .body(content);
    }

    // ------------------------------------------------------------------ opening balances

    @GetMapping("/opening-balances")
    @RequirePermissions(Permissions.OPENING_BALANCE__READ)
    public Map<String, Object> openingBalances() {
        return openings.list();
    }

    @PostMapping("/opening-balances")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.OPENING_BALANCE__POST)
    public Map<String, Object> postOpeningBalances(@Valid @RequestBody OpeningBalanceRequest dto) {
        return openings.post(new OpeningInput(dto.date(), dto.reference(),
                dto.accounts() == null ? null : dto.accounts().stream()
                        .map(a -> new AccountLine(a.accountId(), a.debit(), a.credit(), a.description())).toList(),
                partners(dto.customers()), partners(dto.suppliers())));
    }

    private static List<PartnerLine> partners(List<PartnerLineRequest> in) {
        return in == null ? null : in.stream()
                .map(p -> new PartnerLine(p.partnerId(), p.amount(), p.dueDate(), p.reference())).toList();
    }

    // ------------------------------------------------------------------ tax

    @GetMapping("/reports/tax/summary")
    @RequirePermissions(Permissions.REPORT__ACCOUNTING)
    public Map<String, Object> taxSummary(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
        return tax.summary(from, to, branchId);
    }

    // ------------------------------------------------------------------ statements

    @GetMapping("/reports/ar/customers/{partnerId}/statement")
    @RequirePermissions(Permissions.REPORT__AR)
    public Map<String, Object> customerStatement(@PathVariable String partnerId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return statements.statement("ar", partnerId, from, to);
    }

    @GetMapping("/reports/ar/customers/{partnerId}/statement.pdf")
    @RequirePermissions(Permissions.REPORT__AR)
    public ResponseEntity<byte[]> customerStatementPdf(@PathVariable String partnerId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return pdf("statement.pdf", statements.pdf("ar", partnerId, from, to));
    }

    @PostMapping("/reports/ar/customers/{partnerId}/statement/email")
    @RequirePermissions(Permissions.REPORT__AR)
    public Map<String, Object> emailCustomerStatement(@PathVariable String partnerId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @Valid @RequestBody(required = false) EmailRequest dto) {
        return statements.email("ar", partnerId, from, to, dto == null ? null : dto.to(),
                dto == null ? null : dto.message());
    }

    @GetMapping("/reports/ap/suppliers/{partnerId}/statement")
    @RequirePermissions(Permissions.REPORT__AR)
    public Map<String, Object> supplierStatement(@PathVariable String partnerId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return statements.statement("ap", partnerId, from, to);
    }

    @GetMapping("/reports/ap/suppliers/{partnerId}/statement.pdf")
    @RequirePermissions(Permissions.REPORT__AR)
    public ResponseEntity<byte[]> supplierStatementPdf(@PathVariable String partnerId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return pdf("statement.pdf", statements.pdf("ap", partnerId, from, to));
    }

    // ------------------------------------------------------------------ invoice delivery

    @GetMapping("/invoices/{id}/pdf")
    @RequirePermissions(Permissions.INVOICE__READ)
    public ResponseEntity<byte[]> invoicePdf(@PathVariable String id) {
        Export export = delivery.pdf(id);
        return pdf(export.filename(), export.content());
    }

    @PostMapping("/invoices/{id}/email")
    @RequirePermissions(Permissions.INVOICE__SEND)
    public Map<String, Object> emailInvoice(@PathVariable String id,
            @Valid @RequestBody(required = false) EmailRequest dto) {
        return delivery.email(id, dto == null ? null : dto.to(), dto == null ? null : dto.message());
    }
}
