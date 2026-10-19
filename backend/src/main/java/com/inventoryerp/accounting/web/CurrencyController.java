package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.CurrencyService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /currencies} (port of {@code currency.controller.ts}). */
@RestController
@RequestMapping("/currencies")
public class CurrencyController {

    /** {@code UpsertCurrencyDto}. */
    public record UpsertCurrencyRequest(
            @NotNull(message = "must be a string") String code,
            @NotNull(message = "must be a string") String symbol,
            @NotNull(message = "must be a string") String name,
            @Min(value = 0, message = "must not be less than 0") Integer decimalPlaces) {}

    /** {@code UpsertRateDto}. */
    public record UpsertRateRequest(
            @NotNull(message = "must be a string") String fromCode,
            @NotNull(message = "must be a string") String toCode,
            @NotNull(message = "must be a valid ISO 8601 date string")
            @Pattern(regexp = AccountingDtos.ISO_DATE, message = "must be a valid ISO 8601 date string") String asOf,
            @NotNull(message = "must be a number conforming to the specified constraints")
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal rate,
            String source) {}

    private final CurrencyService svc;

    public CurrencyController(CurrencyService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(Permissions.CURRENCY__READ)
    public List<Map<String, Object>> list() {
        return svc.listCurrencies();
    }

    @GetMapping("/rates")
    @RequirePermissions(Permissions.CURRENCY__READ)
    public List<Map<String, Object>> rates(@RequestParam(required = false) String fromCode,
            @RequestParam(required = false) String toCode, @RequestParam(required = false) String limit) {
        return svc.listRates(fromCode, toCode, limit == null || limit.isEmpty() ? 30 : (int) JsNumbers.number(limit));
    }

    @GetMapping("/rate")
    @RequirePermissions(Permissions.CURRENCY__READ)
    public Map<String, Object> rateAt(@RequestParam(required = false) String fromCode,
            @RequestParam(required = false) String toCode, @RequestParam(required = false) String asOf) {
        BigDecimal rate = svc.getRate(fromCode, toCode, EntityPatch.toInstant(asOf));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fromCode", fromCode);
        out.put("toCode", toCode);
        out.put("asOf", asOf);
        out.put("rate", rate.stripTrailingZeros().toPlainString());
        return out;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CURRENCY__CREATE)
    public void createCurrency(@Valid @RequestBody UpsertCurrencyRequest dto) {
        svc.upsertCurrency(dto.code(), dto.symbol(), dto.name(), dto.decimalPlaces() == null ? 2 : dto.decimalPlaces());
    }

    @PostMapping("/rates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CURRENCY__UPDATE)
    public void createRate(@Valid @RequestBody UpsertRateRequest dto) {
        svc.upsertRate(dto.fromCode(), dto.toCode(), EntityPatch.toInstant(dto.asOf()), dto.rate(),
                dto.source() == null ? "manual" : dto.source());
    }
}
