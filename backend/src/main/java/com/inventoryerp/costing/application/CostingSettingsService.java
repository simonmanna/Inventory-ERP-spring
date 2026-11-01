package com.inventoryerp.costing.application;

import com.inventoryerp.accounting.application.AccountMappingService;
import com.inventoryerp.accounting.application.AccountingPolicyService;
import com.inventoryerp.accounting.application.CostingDimensionGuard;
import com.inventoryerp.inventory.application.CostLineageService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.settings.SettingsService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.procurement.application.CostComponentService;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Costing &amp; Valuation settings (Cost Management CM-2, ADR-S032) — a <b>façade</b>, not a new store. Every value is
 * read from and written to the place that already owns it, so there is one source of truth:
 *
 * <ul>
 *   <li>setting registry: default costing method, negative stock, landed costs on/off, default allocation, branch
 *       dimension enforcement;</li>
 *   <li>inventory costing policy (dated FIFO / weighted average per category, IAS 2.25) — shown, changed on its own
 *       page;</li>
 *   <li>account mappings: stock, landed-cost clearing, price differences, NRV, revaluation, WIP, variances…;</li>
 *   <li>landed-cost components (procurement) with their cost eligibility;</li>
 *   <li>governed accounting policy elections and cost eligibility (accounting).</li>
 * </ul>
 */
@Service
public class CostingSettingsService {

    /** Account mapping keys shown on the page: key → label. */
    static final Map<String, String> ACCOUNTS = new LinkedHashMap<>();

    /** Keys created by Cost Management (not in the Nest mapping registry): set with force. */
    static final Set<String> COSTING_KEYS = Set.of("inventory_price_difference", "inventory_nrv_allowance",
            "inventory_nrv_expense", "inventory_revaluation", "landed_cost_clearing", "material_ledger_revaluation");

    static {
        ACCOUNTS.put("stock_valuation", "Stock valuation");
        ACCOUNTS.put("inventory_price_difference", "Inventory price differences (standard items)");
        ACCOUNTS.put("inventory_nrv_allowance", "Inventory NRV allowance (contra)");
        ACCOUNTS.put("inventory_nrv_expense", "Write-down to NRV");
        ACCOUNTS.put("inventory_revaluation", "Inventory revaluation");
        ACCOUNTS.put("landed_cost_clearing", "Landed cost clearing");
        ACCOUNTS.put("cogs", "Cost of goods sold");
        ACCOUNTS.put("wip", "Work in progress");
        ACCOUNTS.put("material_ledger_revaluation", "Material ledger revaluation");
    }

    private static final Set<String> METHODS = Set.of("AVCO", "FIFO", "STANDARD", "SPECIFIC");
    private static final Set<String> ALLOCATION = Set.of("value", "quantity", "equal", "weight", "volume", "manual");
    private static final Set<String> ENFORCEMENT = Set.of("off", "warn", "enforce");

    /** PUT body; nulls leave a value unchanged. */
    public record SettingsInput(String defaultCostingMethod, Boolean allowNegativeStock, Boolean landedCostEnabled,
            String defaultAllocationMethod, String dimensionEnforcement, Map<String, String> accounts) {}

    private final JdbcClient jdbc;
    private final SettingsService settings;
    private final SettingResolverService resolver;
    private final AccountMappingService mappings;
    private final AccountingPolicyService policies;
    private final CostComponentService components;
    private final CostLineageService lineage;
    private final CostingDimensionGuard dimensions;

    public CostingSettingsService(JdbcClient jdbc, SettingsService settings, SettingResolverService resolver,
            AccountMappingService mappings, AccountingPolicyService policies, CostComponentService components,
            CostLineageService lineage, CostingDimensionGuard dimensions) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.resolver = resolver;
        this.mappings = mappings;
        this.policies = policies;
        this.components = components;
        this.lineage = lineage;
        this.dimensions = dimensions;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** The whole page. */
    @Transactional
    public Map<String, Object> get() {
        List<Map<String, Object>> comps = components.list();
        for (Map<String, Object> c : comps) {
            c.put("eligibility", policies.eligibility("cost_component", (String) c.get("id")));
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT m.key, m.\"accountId\", a.code, a.name FROM"
                + " \"AccountMapping\" m LEFT JOIN \"Account\" a ON a.id = m.\"accountId\" WHERE m.\"organizationId\" = ?")
                .param(org()))) {
            mapped.put((String) m.get("key"), m);
        }
        List<Map<String, Object>> accounts = new ArrayList<>();
        ACCOUNTS.forEach((key, label) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) mapped.get(key);
            accounts.add(Obj.of("key", key, "label", label, "accountId", m == null ? null : m.get("accountId"),
                    "code", m == null ? null : m.get("code"), "name", m == null ? null : m.get("name")));
        });
        return Obj.of(
                "valuation", Obj.of(
                        "defaultCostingMethod", resolver.resolveString("inventory.defaultCostingMethod",
                                SettingContext.EMPTY),
                        "allowNegativeStock", resolver.resolveBool("inventory.allowNegativeStock", SettingContext.EMPTY),
                        "costPolicies", SqlRows.list(jdbc.sql("SELECT id, code, \"scopeType\", \"categoryId\", method,"
                                + " \"effectiveFrom\", status FROM \"InventoryCostPolicy\" WHERE \"organizationId\" = ? AND"
                                + " status IN ('approved', 'active') ORDER BY \"effectiveFrom\" DESC").param(org())),
                        "note", "Cost formulas are FIFO or weighted average (IAS 2.25), set per category by the dated"
                                + " costing policy; STANDARD is used only while it approximates actual cost (IAS 2.21)."),
                "landedCosts", Obj.of(
                        "enabled", resolver.resolveBool("costing.landedCost.enabled", SettingContext.EMPTY),
                        "defaultAllocationMethod", resolver.resolveString("costing.landedCost.defaultAllocationMethod",
                                SettingContext.EMPTY),
                        "components", comps),
                "accounts", accounts,
                "policies", policies.catalogue(),
                "eligibility", Obj.of("classifications", policies.classifications(), "unclassifiedAccounts",
                        policies.unclassifiedAccounts()),
                "dimensions", Obj.of("enforcement", resolver.resolveString("costing.dimensionEnforcement",
                        SettingContext.EMPTY)));
    }

    /** Write through to the owning stores. */
    @Transactional
    public Map<String, Object> update(SettingsInput in) {
        if (in.defaultCostingMethod() != null) {
            if (!METHODS.contains(in.defaultCostingMethod())) {
                throw ApiException.badRequest("defaultCostingMethod must be one of the following values: AVCO, FIFO,"
                        + " STANDARD, SPECIFIC");
            }
            settings.set("inventory.defaultCostingMethod", in.defaultCostingMethod(), null, null);
        }
        if (in.allowNegativeStock() != null) {
            settings.set("inventory.allowNegativeStock", in.allowNegativeStock(), null, null);
        }
        if (in.landedCostEnabled() != null) {
            settings.set("costing.landedCost.enabled", in.landedCostEnabled(), null, null);
        }
        if (in.defaultAllocationMethod() != null) {
            if (!ALLOCATION.contains(in.defaultAllocationMethod())) {
                throw ApiException.badRequest("defaultAllocationMethod must be one of the following values: value,"
                        + " quantity, equal, weight, volume, manual");
            }
            settings.set("costing.landedCost.defaultAllocationMethod", in.defaultAllocationMethod(), null, null);
        }
        if (in.dimensionEnforcement() != null) {
            if (!ENFORCEMENT.contains(in.dimensionEnforcement())) {
                throw ApiException.badRequest("dimensionEnforcement must be one of the following values: off, warn,"
                        + " enforce");
            }
            settings.set("costing.dimensionEnforcement", in.dimensionEnforcement(), null, null);
        }
        if (in.accounts() != null) {
            in.accounts().forEach((key, accountId) -> {
                if (!ACCOUNTS.containsKey(key)) {
                    throw ApiException.badRequest("Unknown account key " + key);
                }
                mappings.set(key, accountId, COSTING_KEYS.contains(key));
            });
        }
        return get();
    }

    /** Create the Cost Management fallback accounts and their mappings for this organisation (idempotent). */
    @Transactional
    public Map<String, Object> ensureAccounts() {
        lineage.priceDifferenceAccount();
        lineage.nrvAllowanceAccount();
        lineage.nrvExpenseAccount();
        lineage.revaluationAccount();
        return get();
    }

    /** Costing journal lines without a branch in [from, to] (dimension compliance, AD-6). */
    @Transactional(readOnly = true)
    public Map<String, Object> dimensionCompliance(String from, String to) {
        LocalDate f = day(from, LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1));
        LocalDate t = day(to, LocalDate.now(ZoneOffset.UTC));
        if (t.isBefore(f)) {
            throw ApiException.badRequest("to must not be before from");
        }
        List<Map<String, Object>> rows = dimensions.compliance(
                java.sql.Timestamp.valueOf(f.atStartOfDay()), java.sql.Timestamp.valueOf(t.plusDays(1).atStartOfDay()));
        return Obj.of("from", f.toString(), "to", t.toString(), "enforcement", resolver.resolveString(
                "costing.dimensionEnforcement", SettingContext.EMPTY), "missingBranch", rows);
    }

    private static LocalDate day(String raw, LocalDate dflt) {
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Dates must be valid ISO 8601 date strings");
        }
    }
}
