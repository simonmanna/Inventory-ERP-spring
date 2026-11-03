package com.inventoryerp.invoicing.web;

import com.inventoryerp.shared.validation.ArrayMinSize;
import static com.inventoryerp.invoicing.web.InvoicingDtos.DATE_MSG;
import static com.inventoryerp.invoicing.web.InvoicingDtos.ISO_DATE;

import com.inventoryerp.invoicing.application.CreditNoteService;
import com.inventoryerp.invoicing.application.CreditNoteService.CreditNoteInput;
import com.inventoryerp.invoicing.web.InvoicingDtos.InvoiceLine;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /credit-notes} (port of {@code credit-note.controller.ts}). */
@RestController
@RequestMapping("/credit-notes")
public class CreditNoteController {

    /** {@code CreateCreditNoteDto}. */
    public record CreateCreditNoteRequest(
            @NotNull(message = "must be a string") String partnerId,
            @NotNull(message = DATE_MSG) @Pattern(regexp = ISO_DATE, message = DATE_MSG) String issueDate,
            String currencyId,
            String reference,
            String notes,
            String reversedDocumentId,
            @NotNull(message = "must be an array") @ArrayMinSize(1)
            List<@Valid InvoiceLine> lines) {}

    private final CreditNoteService creditNotes;

    public CreditNoteController(CreditNoteService creditNotes) {
        this.creditNotes = creditNotes;
    }

    @GetMapping
    @RequirePermissions(Permissions.CREDIT_NOTE__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return creditNotes.list(QueryParams.page(query));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.CREDIT_NOTE__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return creditNotes.findOne(id);
    }

    @PostMapping
    @Idempotent
    @RequirePermissions(Permissions.CREDIT_NOTE__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateCreditNoteRequest dto) {
        return creditNotes.create(new CreditNoteInput(dto.partnerId(), dto.issueDate(), dto.currencyId(), dto.reference(),
                dto.notes(), dto.reversedDocumentId(), InvoicingDtos.lines(dto.lines())));
    }

    @PostMapping("/{id}/post")
    @Idempotent
    @RequirePermissions(Permissions.CREDIT_NOTE__POST)
    public Map<String, Object> post(@PathVariable String id, @RequestParam(required = false) String disposition) {
        return creditNotes.post(id, "scrap".equals(disposition) ? "scrap" : "restock");
    }

    /** {@code POST /credit-notes/:id/allocate} body (ACC-4 extension: open-item matching of an unapplied credit). */
    public record AllocateCreditRequest(
            @NotNull(message = "must be a string") String documentId,
            @NotNull(message = "must be a number conforming to the specified constraints") java.math.BigDecimal amount) {}

    @PostMapping("/{id}/allocate")
    @Idempotent
    @RequirePermissions(Permissions.CREDIT_NOTE__POST)
    public Map<String, Object> allocate(@PathVariable String id, @Valid @RequestBody AllocateCreditRequest dto) {
        return creditNotes.allocate(id, dto.documentId(), dto.amount());
    }
}
