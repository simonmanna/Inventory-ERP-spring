package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Resolves GL accounts for inventory movements (port of {@code posting-rule.service.ts}). Resolution order:
 * product override → category default → movement-type default; each rule line resolves its account from an
 * AccountMapping key, a literal account, or a product / category account field (with fallbacks).
 */
@Service
public class InventoryPostingRuleService {

    /** One resolved line of {@link #resolveStructure}. */
    public record StructureLine(String debitAccountId, String creditAccountId, String accountSource) {}

    private static final Map<String, String> PRODUCT_FIELDS = Map.of(
            "stock_valuation", "inventoryAccountOverrideId",
            "cogs", "cogsAccountOverrideId",
            "stock_adjustment_income", "varianceGainAccountOverrideId",
            "stock_adjustment_expense", "shrinkageAccountOverrideId",
            "damage", "damageAccountOverrideId",
            "expiry", "expiryAccountOverrideId");

    private static final Map<String, String> CATEGORY_FIELDS = Map.of(
            "stock_valuation", "inventoryAccountId",
            "cogs", "cogsAccountId",
            "stock_adjustment_income", "varianceGainAccountId",
            "stock_adjustment_expense", "shrinkageAccountId",
            "damage", "damageAccountId",
            "expiry", "expiryAccountId",
            "promo_expense", "promoExpenseAccountId",
            "internal_use", "internalUseAccountId");

    private final JdbcClient jdbc;
    private final AccountResolverService accounts;

    public InventoryPostingRuleService(JdbcClient jdbc, AccountResolverService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    /**
     * Ready-to-post lines for a product + movement type + value; empty when the value is not positive.
     * Throws the Nest "No posting rule configured" error when no rule exists at any level.
     */
    public List<PostingRequest.Line> resolve(String movementType, String productId, BigDecimal totalValue,
            String description) {
        if (totalValue.signum() <= 0) {
            return List.of();
        }
        Map<String, Object> product = product(productId);
        List<Map<String, Object>> rules = loadRules(movementType, productId, (String) product.get("categoryId"));
        if (rules.isEmpty()) {
            throw ApiException.badRequest("No posting rule configured for movement type '" + movementType
                    + "'. Go to Accounting > Posting Rules to set one up.");
        }
        Map<String, Object> category = category((String) product.get("categoryId"));
        String value = JsNumbers.dec(totalValue);
        List<MutableLine> lines = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            String accountId = resolveAccount(rule, product, category);
            if (accountId == null) {
                continue;
            }
            boolean debit = "debit".equals(rule.get("debitOrCredit"));
            MutableLine existing = null;
            for (MutableLine l : lines) {
                if (l.accountId.equals(accountId) && (debit ? l.debit != null : l.credit != null)) {
                    existing = l;
                    break;
                }
            }
            if (existing != null) {
                if (debit) {
                    existing.debit = JsNumbers.dec(Money.dec(existing.debit).add(totalValue));
                } else {
                    existing.credit = JsNumbers.dec(Money.dec(existing.credit).add(totalValue));
                }
                continue;
            }
            MutableLine entry = new MutableLine(accountId,
                    description != null ? description : "Inventory " + movementType);
            if (debit) {
                entry.debit = value;
            } else {
                entry.credit = value;
            }
            lines.add(entry);
        }
        List<PostingRequest.Line> out = new ArrayList<>();
        for (MutableLine l : lines) {
            out.add(new PostingRequest.Line(l.accountId, l.debit, l.credit, null, l.description, null, null, null));
        }
        return out;
    }

    /** Account ids per rule line without values (UI preview). */
    public List<StructureLine> resolveStructure(String movementType, String productId) {
        Map<String, Object> product = product(productId);
        List<Map<String, Object>> rules = loadRules(movementType, productId, (String) product.get("categoryId"));
        if (rules.isEmpty()) {
            return List.of();
        }
        Map<String, Object> category = category((String) product.get("categoryId"));
        List<StructureLine> out = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            boolean debit = "debit".equals(rule.get("debitOrCredit"));
            out.add(new StructureLine(debit ? resolveAccount(rule, product, category) : null,
                    debit ? null : resolveAccount(rule, product, category), (String) rule.get("accountSource")));
        }
        return out;
    }

    // ------------------------------------------------------------------ internals

    private static final class MutableLine {
        final String accountId;
        final String description;
        String debit;
        String credit;

        MutableLine(String accountId, String description) {
            this.accountId = accountId;
            this.description = description;
        }
    }

    private Map<String, Object> product(String productId) {
        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL LIMIT 1")
                .params(productId, TenantContext.organizationId()).query().listOfRows();
        if (rows.isEmpty()) {
            throw ApiException.badRequest("Product not found");
        }
        return rows.getFirst();
    }

    /** {@code include: {category: true}} — the relation is not soft-delete filtered. */
    private Map<String, Object> category(String categoryId) {
        if (categoryId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM \"ProductCategory\" WHERE id = ?")
                .param(categoryId).query().listOfRows();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Product override, then category default, then movement-type default; most specific per (side, line). */
    private List<Map<String, Object>> loadRules(String movementType, String productId, String categoryId) {
        String sql = "SELECT * FROM \"InventoryPostingRule\" WHERE \"organizationId\" = ? AND \"movementType\"::text = ?"
                + " AND \"isActive\" = true AND (\"productId\" = ? OR (\"productId\" IS NULL AND \"categoryId\" IS NULL)"
                + (categoryId != null ? " OR (\"productId\" IS NULL AND \"categoryId\" = ?)" : "") + ")"
                + " ORDER BY \"productId\" DESC NULLS LAST, \"categoryId\" DESC NULLS LAST, \"lineIndex\" ASC";
        var spec = jdbc.sql(sql).param(TenantContext.organizationId()).param(movementType).param(productId);
        if (categoryId != null) {
            spec = spec.param(categoryId);
        }
        List<Map<String, Object>> all = spec.query().listOfRows();
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> rule : all) {
            String key = rule.get("debitOrCredit") + ":" + rule.get("lineIndex");
            if (seen.add(key)) {
                out.add(rule);
            }
        }
        return out;
    }

    private String resolveAccount(Map<String, Object> rule, Map<String, Object> product, Map<String, Object> category) {
        String key = (String) rule.get("accountMappingKey");
        String source = String.valueOf(rule.get("accountSource"));
        switch (source) {
            case "account_mapping" -> {
                return key == null ? null : accounts.byMapping(key);
            }
            case "literal" -> {
                return (String) rule.get("literalAccountId");
            }
            case "category_field" -> {
                if (category == null) {
                    return accounts.byMapping(key != null ? key : "stock_valuation");
                }
                if (key == null) {
                    return null;
                }
                Object id = category.get(CATEGORY_FIELDS.getOrDefault(key, key));
                if (id != null) {
                    return id.toString();
                }
                return mappingOrNull(key);
            }
            case "product_field" -> {
                if (key == null) {
                    return null;
                }
                Object override = product.get(PRODUCT_FIELDS.getOrDefault(key, key));
                if (override != null) {
                    return override.toString();
                }
                if (category != null) {
                    Object catId = category.get(CATEGORY_FIELDS.getOrDefault(key, key));
                    if (catId != null) {
                        return catId.toString();
                    }
                }
                return mappingOrNull(key);
            }
            default -> {
                return null;
            }
        }
    }

    private String mappingOrNull(String key) {
        try {
            return accounts.byMapping(key);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
