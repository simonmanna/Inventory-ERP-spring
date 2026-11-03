package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.TaxSettlementService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /tax-settlements}: VAT settlement and tax-period lock (ACC-7 extension; Nest has no counterpart). */
@RestController
@RequestMapping("/tax-settlements")
public class TaxSettlementController {

    /** Period to settle (inclusive dates). */
    public record SettleRequest(
            @NotNull(message = "must be a valid ISO 8601 date string")
            @Pattern(regexp = AccountingDtos.ISO_DATE, message = "must be a valid ISO 8601 date string") String from,
            @NotNull(message = "must be a valid ISO 8601 date string")
            @Pattern(regexp = AccountingDtos.ISO_DATE, message = "must be a valid ISO 8601 date string") String to) {}

    private final TaxSettlementService settlements;

    public TaxSettlementController(TaxSettlementService settlements) {
        this.settlements = settlements;
    }

    @GetMapping
    @RequirePermissions(Permissions.REPORT__ACCOUNTING)
    public List<Map<String, Object>> list() {
        return settlements.list();
    }

    @GetMapping("/preview")
    @RequirePermissions(Permissions.REPORT__ACCOUNTING)
    public Map<String, Object> preview(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return settlements.preview(start(from), end(to));
    }

    @PostMapping
    @Idempotent
    @RequirePermissions(Permissions.JOURNAL_ENTRY__CREATE)
    public Map<String, Object> settle(@Valid @RequestBody SettleRequest dto) {
        return settlements.settle(start(dto.from()), end(dto.to()));
    }

    private static Instant start(String day) {
        return day == null || day.isEmpty() ? null
                : LocalDate.parse(day.substring(0, 10)).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant end(String day) {
        return day == null || day.isEmpty() ? null
                : LocalDate.parse(day.substring(0, 10)).atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
    }
}
