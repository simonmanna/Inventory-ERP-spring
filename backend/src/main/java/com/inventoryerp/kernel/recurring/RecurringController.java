package com.inventoryerp.kernel.recurring;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /recurring} (port of {@code recurring.controller.ts}); session required, no permission. */
@RestController
@RequestMapping("/recurring")
public class RecurringController {

    /** {@code RecurringLineDto}. */
    public record LineRequest(String productId, @NotNull(message = "must be a string") String description,
            Object quantity, Object unitPrice, Object discountPercent, String taxId, String accountId) {}

    /** {@code RecurringTemplateDto}. */
    public record TemplateRequest(@NotNull(message = "must be a string") String partnerId, String currencyId,
            Object exchangeRate, String reference, String notes,
            @NotNull(message = "must be an array") List<@Valid LineRequest> lines) {}

    /** {@code CreateRecurringDto}. */
    public record CreateRequest(
            @NotNull(message = "must be a string") String name,
            @NotNull(message = "must be one of the following values: sales_invoice, credit_note, vendor_bill, debit_note")
            @OneOf({"sales_invoice", "credit_note", "vendor_bill", "debit_note"}) String documentType,
            @NotNull(message = "nested property template must be either object or array") @Valid TemplateRequest template,
            @NotNull(message = "must be one of the following values: daily, weekly, monthly, quarterly, yearly")
            @OneOf({"daily", "weekly", "monthly", "quarterly", "yearly"}) String frequency,
            @NotNull(message = "must be a valid ISO 8601 date string") String nextRunAt,
            String endDate) {}

    private final RecurringService svc;

    public RecurringController(RecurringService svc) {
        this.svc = svc;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return svc.list();
    }

    @GetMapping("/{id}")
    public Map<String, Object> findOne(@PathVariable String id) {
        return svc.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateRequest dto) {
        Instant next = iso("nextRunAt", dto.nextRunAt());
        Instant end = dto.endDate() == null ? null : iso("endDate", dto.endDate());
        return svc.create(dto.name(), dto.documentType(), dto.template(), dto.frequency(), next, end);
    }

    @PatchMapping("/{id}/pause")
    public Map<String, Object> pause(@PathVariable String id) {
        return svc.setStatus(id, "paused");
    }

    @PatchMapping("/{id}/resume")
    public Map<String, Object> resume(@PathVariable String id) {
        return svc.setStatus(id, "active");
    }

    @PatchMapping("/{id}/end")
    public Map<String, Object> end(@PathVariable String id) {
        return svc.setStatus(id, "ended");
    }

    private static Instant iso(String field, String value) {
        try {
            if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return Instant.parse(value + "T00:00:00Z");
            }
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException e) {
                return Instant.parse(value);
            }
        } catch (DateTimeParseException e) {
            throw com.inventoryerp.shared.web.ApiException.withBody(HttpStatus.BAD_REQUEST,
                    com.inventoryerp.kernel.web.GlobalExceptionHandler.nestBody(HttpStatus.BAD_REQUEST,
                            List.of(field + " must be a valid ISO 8601 date string")));
        }
    }
}
