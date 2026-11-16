package com.inventoryerp.kernel.settings;

import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Company / developer / feature settings (port of {@code company-settings.controller.ts}). Reference
 * data (accounts, journals, taxes, currencies) is read with plain SQL so the kernel does not depend on
 * the accounting/core modules.
 */
@Service
public class CompanySettingsService {

    private static final List<String[]> ACCOUNTING_KEYS = List.of(
            new String[] {"incomeAccountId", "accounting.incomeAccountId"},
            new String[] {"expenseAccountId", "accounting.expenseAccountId"},
            new String[] {"defaultSalesTaxId", "accounting.defaultSalesTaxId"},
            new String[] {"exchangeDifferenceJournalId", "accounting.exchangeDifferenceJournalId"},
            new String[] {"defaultSalesJournalId", "accounting.defaultSalesJournalId"},
            new String[] {"exchangeGainAccountId", "accounting.exchangeGainAccountId"},
            new String[] {"exchangeLossAccountId", "accounting.exchangeLossAccountId"},
            new String[] {"productIncomeAccountId", "accounting.productIncomeAccountId"},
            new String[] {"productExpenseAccountId", "accounting.productExpenseAccountId"},
            new String[] {"fiscalYearStartMonth", "accounting.fiscalYearStartMonth"},
            new String[] {"allCurrencyCodes", "accounting.allCurrencyCodes"});

    private final SettingsService settings;
    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public CompanySettingsService(SettingsService settings, JdbcClient jdbc, JsonMapper mapper) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCompanySettings() {
        String orgId = TenantContext.organizationId();
        Map<String, Object> byKey = new LinkedHashMap<>();
        for (var s : settings.listEffective("accounting", SettingContext.EMPTY, true)) {
            byKey.put((String) s.get("key"), s.get("value"));
        }
        Map<String, Object> invDefaults = settings.getInventoryDefaults();
        String currencyCode = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(orgId)
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("Organization not found"));

        var accounts = jdbc.sql("""
                        SELECT id, code, name, "reportSection"::text AS "reportSection" FROM "Account"
                        WHERE "organizationId" = ? AND "isActive" = true ORDER BY code ASC""")
                .param(orgId)
                .query((rs, n) -> row("id", rs.getString("id"), "code", rs.getString("code"), "name", rs.getString("name"),
                        "reportSection", rs.getString("reportSection")))
                .list();
        var journals = jdbc.sql("""
                        SELECT id, code, name FROM "Journal" WHERE "organizationId" = ? AND "isActive" = true ORDER BY code ASC""")
                .param(orgId)
                .query((rs, n) -> row("id", rs.getString("id"), "code", rs.getString("code"), "name", rs.getString("name")))
                .list();
        var taxes = jdbc.sql("""
                        SELECT id, name, code, rate FROM "Tax" WHERE "organizationId" = ? AND "isActive" = true ORDER BY name ASC""")
                .param(orgId)
                .query((rs, n) -> row("id", rs.getString("id"), "name", rs.getString("name"), "code", rs.getString("code"),
                        "rate", rs.getBigDecimal("rate")))
                .list();
        var currencies = jdbc.sql("SELECT code, name, symbol FROM \"Currency\" WHERE \"isActive\" = true ORDER BY code ASC")
                .query((rs, n) -> row("code", rs.getString("code"), "name", rs.getString("name"), "symbol", rs.getString("symbol")))
                .list();

        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("incomeAccountId", "expenseAccountId", "defaultSalesTaxId", "exchangeDifferenceJournalId",
                "defaultSalesJournalId", "exchangeGainAccountId", "exchangeLossAccountId", "productIncomeAccountId",
                "productExpenseAccountId")) {
            out.put(k, orNull(byKey.get("accounting." + k)));
        }
        Object currenciesValue = byKey.get("accounting.allCurrencyCodes");
        out.put("allCurrencyCodes", truthy(currenciesValue) ? currenciesValue : List.of());
        out.put("costingMethod", invDefaults.get("costingMethod"));
        out.put("baseCurrencyCode", currencyCode);
        Object fy = byKey.get("accounting.fiscalYearStartMonth");
        out.put("fiscalYearStartMonth", fy == null ? 1 : fy);
        out.put("_references", row("accounts", accounts, "journals", journals, "taxes", taxes, "currencies", currencies));
        return out;
    }

    @Transactional
    public Map<String, Object> putCompanySettings(Map<String, Object> dto) {
        String orgId = TenantContext.organizationId();
        for (String[] k : ACCOUNTING_KEYS) {
            if (dto.get(k[0]) != null) {
                settings.set(k[1], dto.get(k[0]), null, null);
            }
        }
        if (dto.get("costingMethod") != null) {
            settings.set("inventory.defaultCostingMethod", dto.get("costingMethod"), null, null);
        }
        if (dto.get("baseCurrencyCode") != null) {
            jdbc.sql("UPDATE \"Organization\" SET \"currencyCode\" = ?, \"updatedAt\" = now() WHERE id = ?")
                    .params(dto.get("baseCurrencyCode"), orgId)
                    .update();
        }
        return getCompanySettings();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getFeatures() {
        Map<String, Object> s = orgSettings(TenantContext.organizationId());
        Object features = s.get("features");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("features", truthy(features) ? features : Map.of());
        out.put("sidebarTagline", sidebarTagline(s));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDeveloperSettings() {
        String orgId = TenantContext.organizationId();
        Map<String, Object> s = orgSettings(orgId);
        String name = jdbc.sql("SELECT name FROM \"Organization\" WHERE id = ?").param(orgId).query(String.class).single();
        Object features = s.get("features");
        Map<String, Object> out = new LinkedHashMap<>();
        Object logo = s.get("logoUrl");
        out.put("logoUrl", truthy(logo) ? logo : null);
        out.put("name", name);
        out.put("features", truthy(features) ? features : Map.of());
        out.put("sidebarTagline", sidebarTagline(s));
        return out;
    }

    /**
     * The line shown under the organization name in the sidebar header (owner-requested addition, not in Nest).
     * {@code null} when never set or blank, so the web client falls back to its default label.
     */
    private static String sidebarTagline(Map<String, Object> settings) {
        return settings.get("sidebarTagline") instanceof String t && !t.isBlank() ? t : null;
    }

    /**
     * Saves the developer settings. {@code sidebarTagline} is only written when {@code taglineGiven}; a blank value
     * clears it.
     */
    @Transactional
    public Map<String, Object> putDeveloperSettings(String name, String logoUrl, boolean logoGiven, Map<String, Object> features,
            String sidebarTagline, boolean taglineGiven) {
        String orgId = TenantContext.organizationId();
        Map<String, Object> current = orgSettings(orgId);
        if (name != null) {
            jdbc.sql("UPDATE \"Organization\" SET name = ?, \"updatedAt\" = now() WHERE id = ?").params(name, orgId).update();
        }
        if (logoGiven || features != null || taglineGiven) {
            Map<String, Object> next = new LinkedHashMap<>(current);
            if (taglineGiven) {
                String t = sidebarTagline == null ? "" : sidebarTagline.strip();
                if (t.isEmpty()) {
                    next.remove("sidebarTagline");
                } else {
                    next.put("sidebarTagline", t);
                }
            }
            if (logoGiven) {
                next.put("logoUrl", logoUrl);
            }
            if (features != null) {
                next.put("features", features);
            }
            jdbc.sql("UPDATE \"Organization\" SET settings = ?::jsonb, \"updatedAt\" = now() WHERE id = ?")
                    .params(mapper.writeValueAsString(next), orgId)
                    .update();
        }
        return getDeveloperSettings();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> orgSettings(String orgId) {
        String json = jdbc.sql("SELECT settings::text FROM \"Organization\" WHERE id = ?").param(orgId)
                .query((rs, n) -> new String[] {rs.getString(1)})
                .optional()
                .orElseThrow(() -> ApiException.notFound("Organization not found"))[0];
        Object v = json == null ? null : mapper.readValue(json, Object.class);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    /** JS truthiness for JSON values ({@code x || fallback}). */
    static boolean truthy(Object v) {
        if (v == null || Boolean.FALSE.equals(v)) {
            return false;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Number n) {
            return n instanceof BigDecimal b ? b.signum() != 0 : n.doubleValue() != 0;
        }
        return true;
    }

    private static Object orNull(Object v) {
        return truthy(v) ? v : null;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
