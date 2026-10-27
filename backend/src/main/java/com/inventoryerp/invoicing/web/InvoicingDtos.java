package com.inventoryerp.invoicing.web;

import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.util.List;

/** Request records shared by the invoicing controllers. */
public final class InvoicingDtos {

    private InvoicingDtos() {}

    /** class-validator {@code @IsDateString()} (validator.js isISO8601, non-strict). */
    static final String ISO_DATE = "^\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}(:?\\d{2})?)?)?$";

    static final String DATE_MSG = "must be a valid ISO 8601 date string";

    /** {@code InvoiceLineDto}. */
    public record InvoiceLine(
            String productId,
            String accountId,
            String description,
            @DecimalMin(value = "0", message = "Quantity cannot be negative") BigDecimal quantity,
            @DecimalMin(value = "0.01", message = "Unit price must be at least 0.01") BigDecimal unitPrice,
            @DecimalMin(value = "0", message = "Discount percent cannot be negative")
            @DecimalMax(value = "100", message = "Discount percent cannot exceed 100") BigDecimal discountPercent,
            String taxId,
            @OneOf({"product", "section", "note"}) String lineType,
            String uomId) {

        LineInput toInput() {
            return LineInput.of(productId, accountId, description, quantity, unitPrice, discountPercent, taxId, lineType,
                    uomId);
        }
    }

    static List<LineInput> lines(List<InvoiceLine> lines) {
        return lines.stream().map(InvoiceLine::toInput).toList();
    }

    /** Nest {@code ParseIntPipe} after {@code DefaultValuePipe(def)}: {@link QueryParams#parseInt}. */
    static int parseInt(String raw, int def) {
        return QueryParams.parseInt(raw, def);
    }
}
