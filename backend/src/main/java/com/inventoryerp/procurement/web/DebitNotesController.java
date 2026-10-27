package com.inventoryerp.procurement.web;

import com.inventoryerp.shared.validation.ArrayMinSize;
import static com.inventoryerp.procurement.web.ProcurementDtos.ARR;
import static com.inventoryerp.procurement.web.ProcurementDtos.DATE_MSG;
import static com.inventoryerp.procurement.web.ProcurementDtos.ISO_DATE;
import static com.inventoryerp.procurement.web.ProcurementDtos.MIN1;
import static com.inventoryerp.procurement.web.ProcurementDtos.NUM;
import static com.inventoryerp.procurement.web.ProcurementDtos.STR;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.DebitNotesService;
import com.inventoryerp.procurement.application.DebitNotesService.CreateInput;
import com.inventoryerp.procurement.application.DebitNotesService.LineInput;
import com.inventoryerp.shared.validation.OneOf;
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

/** {@code /procurement/debit-notes} (port of {@code debit-notes.controller.ts}). */
@RestController
@RequestMapping("/procurement/debit-notes")
public class DebitNotesController {

    /** {@code DebitNoteLineDto}. */
    public record DebitNoteLineRequest(
            String productId,
            @NotNull(message = STR) String description,
            @NotNull(message = NUM) BigDecimal quantity,
            @NotNull(message = NUM) BigDecimal unitPrice,
            String taxId,
            String notes,
            String uomId) {}

    /** {@code CreateDebitNoteDto}. */
    public record CreateDebitNoteRequest(
            @NotNull(message = "must be one of the following values: outbound, inbound")
            @OneOf({"outbound", "inbound"}) String direction,
            @NotNull(message = STR) String partnerId,
            String documentId,
            @NotNull(message = "must be one of the following values: price_adjustment, returned_goods, overcharge,"
                    + " correction, other")
            @OneOf({"price_adjustment", "returned_goods", "overcharge", "correction", "other"}) String reason,
            String reasonNote,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String issueDate,
            String currencyCode,
            BigDecimal exchangeRate,
            String notes,
            String locationId,
            @NotNull(message = ARR) @ArrayMinSize(1) List<@Valid DebitNoteLineRequest> lines) {}

    private final DebitNotesService svc;

    public DebitNotesController(DebitNotesService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(Permissions.DEBIT_NOTE__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String direction,
            @RequestParam(required = false) String status) {
        return svc.list(direction, status);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.DEBIT_NOTE__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return svc.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.DEBIT_NOTE__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateDebitNoteRequest dto) {
        return svc.create(new CreateInput(dto.direction(), dto.partnerId(), dto.documentId(), dto.reason(),
                dto.reasonNote(), dto.issueDate(), dto.currencyCode(), dto.exchangeRate(), dto.notes(), dto.locationId(),
                dto.lines().stream().map(l -> new LineInput(l.productId(), l.description(), l.quantity(), l.unitPrice(),
                        l.taxId(), l.notes(), l.uomId())).toList()));
    }

    @PatchMapping("/{id}/post")
    @RequirePermissions(Permissions.DEBIT_NOTE__POST)
    public Map<String, Object> post(@PathVariable String id) {
        return svc.post(id);
    }

    @PatchMapping("/{id}/cancel")
    @RequirePermissions(Permissions.DEBIT_NOTE__CANCEL)
    public Map<String, Object> cancel(@PathVariable String id) {
        return svc.cancel(id);
    }
}
