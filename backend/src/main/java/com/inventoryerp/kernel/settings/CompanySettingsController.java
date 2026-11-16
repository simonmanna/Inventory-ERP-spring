package com.inventoryerp.kernel.settings;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /settings/company|features|developer} (port of {@code company-settings.controller.ts}). */
@RestController
@RequestMapping("/settings")
public class CompanySettingsController {

    /** {@code CompanySettingsDto} (all optional). */
    public record CompanySettingsRequest(
            String incomeAccountId, String expenseAccountId, String defaultSalesTaxId, String exchangeDifferenceJournalId,
            String defaultSalesJournalId, String exchangeGainAccountId, String exchangeLossAccountId,
            String productIncomeAccountId, String productExpenseAccountId, String costingMethod, String baseCurrencyCode,
            List<String> allCurrencyCodes, Object fiscalYearStartMonth) {}

    private final CompanySettingsService company;

    public CompanySettingsController(CompanySettingsService company) {
        this.company = company;
    }

    @GetMapping("/company")
    @RequirePermissions(Permissions.SETTING__READ)
    public Map<String, Object> getCompanySettings() {
        return company.getCompanySettings();
    }

    @PutMapping("/company")
    @RequirePermissions(Permissions.SETTING__UPDATE)
    public Map<String, Object> putCompanySettings(@RequestBody CompanySettingsRequest dto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incomeAccountId", dto.incomeAccountId());
        m.put("expenseAccountId", dto.expenseAccountId());
        m.put("defaultSalesTaxId", dto.defaultSalesTaxId());
        m.put("exchangeDifferenceJournalId", dto.exchangeDifferenceJournalId());
        m.put("defaultSalesJournalId", dto.defaultSalesJournalId());
        m.put("exchangeGainAccountId", dto.exchangeGainAccountId());
        m.put("exchangeLossAccountId", dto.exchangeLossAccountId());
        m.put("productIncomeAccountId", dto.productIncomeAccountId());
        m.put("productExpenseAccountId", dto.productExpenseAccountId());
        m.put("costingMethod", dto.costingMethod());
        m.put("baseCurrencyCode", dto.baseCurrencyCode());
        m.put("allCurrencyCodes", dto.allCurrencyCodes());
        m.put("fiscalYearStartMonth", dto.fiscalYearStartMonth());
        return company.putCompanySettings(m);
    }

    /** Authenticated but unprivileged: every user needs it to render the sidebar. */
    @GetMapping("/features")
    public Map<String, Object> getFeatures() {
        return company.getFeatures();
    }

    @GetMapping("/developer")
    @RequirePermissions(Permissions.SETTING__READ)
    public Map<String, Object> getDeveloperSettings() {
        return company.getDeveloperSettings();
    }

    @PutMapping("/developer")
    @RequirePermissions(Permissions.SETTING__UPDATE)
    @SuppressWarnings("unchecked")
    public Map<String, Object> putDeveloperSettings(@RequestBody Map<String, Object> dto) {
        for (String k : dto.keySet()) {
            if (!List.of("logoUrl", "name", "features", "sidebarTagline").contains(k)) {
                throw com.inventoryerp.shared.web.ApiException.withBody(org.springframework.http.HttpStatus.BAD_REQUEST,
                        com.inventoryerp.kernel.web.GlobalExceptionHandler.nestBody(
                                org.springframework.http.HttpStatus.BAD_REQUEST, List.of("property " + k + " should not exist")));
            }
        }
        Object features = dto.get("features");
        Object tagline = dto.get("sidebarTagline");
        if (tagline != null && (!(tagline instanceof String t) || t.strip().length() > 60)) {
            throw com.inventoryerp.shared.web.ApiException.withBody(org.springframework.http.HttpStatus.BAD_REQUEST,
                    com.inventoryerp.kernel.web.GlobalExceptionHandler.nestBody(org.springframework.http.HttpStatus.BAD_REQUEST,
                            List.of("sidebarTagline must be a string of at most 60 characters")));
        }
        return company.putDeveloperSettings((String) dto.get("name"), (String) dto.get("logoUrl"),
                dto.containsKey("logoUrl"),
                features instanceof Map<?, ?> f ? (Map<String, Object>) f : null,
                (String) tagline, dto.containsKey("sidebarTagline"));
    }
}
