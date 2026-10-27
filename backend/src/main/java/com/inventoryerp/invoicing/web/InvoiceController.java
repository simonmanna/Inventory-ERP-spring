package com.inventoryerp.invoicing.web;

import com.inventoryerp.shared.validation.ArrayMinSize;
import static com.inventoryerp.invoicing.web.InvoicingDtos.DATE_MSG;
import static com.inventoryerp.invoicing.web.InvoicingDtos.ISO_DATE;

import com.inventoryerp.invoicing.application.InvoiceService;
import com.inventoryerp.invoicing.application.InvoiceService.InvoiceInput;
import com.inventoryerp.invoicing.application.InvoiceService.ListFilter;
import com.inventoryerp.invoicing.web.InvoicingDtos.InvoiceLine;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /invoices} (port of {@code invoice.controller.ts}). */
@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    /** {@code CreateInvoiceDto}. */
    public record CreateInvoiceRequest(
            @NotNull(message = "must be a string") String partnerId,
            @NotNull(message = DATE_MSG) @Pattern(regexp = ISO_DATE, message = DATE_MSG) String issueDate,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String dueDate,
            String paymentTermId,
            @OneOf({"cash", "card", "mobile_money", "mixed", "credit"}) String paymentMode,
            String fiscalPositionId,
            String invoicingJournalId,
            String salespersonId,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String deliveryDate,
            String deliveryAddress,
            String incoterm,
            String incotermLocation,
            String sourceDocument,
            String currencyId,
            BigDecimal exchangeRate,
            String reference,
            String notes,
            @NotNull(message = "must be an array") @ArrayMinSize(1)
            List<@Valid InvoiceLine> lines) {

        InvoiceInput toInput() {
            return new InvoiceInput(partnerId, issueDate, dueDate, paymentTermId, paymentMode, fiscalPositionId,
                    invoicingJournalId, salespersonId, deliveryDate, deliveryAddress, incoterm, incotermLocation,
                    sourceDocument, currencyId, exchangeRate, reference, notes, InvoicingDtos.lines(lines));
        }
    }

    private final InvoiceService invoices;

    public InvoiceController(InvoiceService invoices) {
        this.invoices = invoices;
    }

    @GetMapping
    @RequirePermissions(Permissions.INVOICE__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize, @RequestParam(required = false) String search,
            @RequestParam(required = false) String partnerId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String settlementStatus, @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        return invoices.list(new ListFilter(InvoicingDtos.parseInt(page, 1), InvoicingDtos.parseInt(pageSize, 25), search,
                partnerId, status, paymentStatus, settlementStatus, dateFrom, dateTo));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.INVOICE__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return invoices.findOne(id);
    }

    @PostMapping
    @Idempotent
    @RequirePermissions(Permissions.INVOICE__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateInvoiceRequest dto) {
        return invoices.create(dto.toInput());
    }

    @PostMapping("/journal-preview")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INVOICE__READ)
    public Map<String, Object> journalPreview(@Valid @RequestBody CreateInvoiceRequest dto) {
        return invoices.previewJournal(dto.toInput());
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.INVOICE__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody CreateInvoiceRequest dto) {
        return invoices.update(id, dto.toInput());
    }

    @PostMapping("/{id}/post")
    @Idempotent
    @RequirePermissions(Permissions.INVOICE__POST)
    public Map<String, Object> post(@PathVariable String id) {
        return invoices.post(id);
    }

    @PostMapping("/{id}/cancel")
    @Idempotent
    @RequirePermissions(Permissions.INVOICE__CANCEL)
    public Map<String, Object> cancel(@PathVariable String id) {
        return invoices.cancel(id);
    }
}
