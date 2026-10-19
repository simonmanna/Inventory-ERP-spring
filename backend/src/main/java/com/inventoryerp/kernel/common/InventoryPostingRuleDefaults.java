package com.inventoryerp.kernel.common;

import java.util.List;

/**
 * Canonical default inventory posting rules (port of {@code packages/shared/src/inventory-posting-rules.ts}),
 * shared by org bootstrap and the "restore defaults" action. Each entry is a movement-type default resolved
 * through AccountMapping keys.
 */
public final class InventoryPostingRuleDefaults {

    private InventoryPostingRuleDefaults() {}

    public record Rule(String movementType, int lineIndex, String debitOrCredit, String accountSource,
            String accountMappingKey) {}

    private static final String MAP = "account_mapping";
    private static final String CAT = "category_field";

    private static Rule r(String type, int line, String side, String source, String key) {
        return new Rule(type, line, side, source, key);
    }

    public static final List<Rule> RULES = List.of(
            r("STOCK_IN", 0, "debit", MAP, "stock_valuation"),
            r("STOCK_IN", 1, "credit", MAP, "grni_accrued"),
            r("STOCK_OUT", 0, "debit", MAP, "cogs"),
            r("STOCK_OUT", 1, "credit", MAP, "stock_valuation"),
            r("RETURN_RESTOCK", 0, "debit", MAP, "stock_valuation"),
            r("RETURN_RESTOCK", 1, "credit", MAP, "cogs"),
            r("ADJUSTMENT_GAIN", 0, "debit", MAP, "stock_valuation"),
            r("ADJUSTMENT_GAIN", 1, "credit", MAP, "stock_adjustment_income"),
            r("ADJUSTMENT_LOSS", 0, "debit", MAP, "stock_adjustment_expense"),
            r("ADJUSTMENT_LOSS", 1, "credit", MAP, "stock_valuation"),
            r("WASTE", 0, "debit", MAP, "stock_adjustment_expense"),
            r("WASTE", 1, "credit", MAP, "stock_valuation"),
            r("EXPIRY_WRITE_OFF", 0, "debit", CAT, "stock_adjustment_expense"),
            r("EXPIRY_WRITE_OFF", 1, "credit", MAP, "stock_valuation"),
            r("INTERNAL_CONSUMPTION", 0, "debit", CAT, "stock_adjustment_expense"),
            r("INTERNAL_CONSUMPTION", 1, "credit", MAP, "stock_valuation"),
            r("PROMO_SAMPLE", 0, "debit", CAT, "stock_adjustment_expense"),
            r("PROMO_SAMPLE", 1, "credit", MAP, "stock_valuation"),
            r("STOCK_TRANSFER_OUT", 0, "debit", MAP, "stock_valuation"),
            r("STOCK_TRANSFER_OUT", 1, "credit", MAP, "stock_valuation"),
            r("STOCK_TRANSFER_IN", 0, "debit", MAP, "stock_valuation"),
            r("STOCK_TRANSFER_IN", 1, "credit", MAP, "stock_valuation"),
            r("RETURN_TO_SUPPLIER", 0, "debit", MAP, "grni_accrued"),
            r("RETURN_TO_SUPPLIER", 1, "credit", MAP, "stock_valuation"),
            r("PRODUCTION_CONSUME", 0, "debit", MAP, "wip"),
            r("PRODUCTION_CONSUME", 1, "credit", MAP, "stock_valuation"),
            r("PRODUCTION_OUTPUT", 0, "debit", MAP, "stock_valuation"),
            r("PRODUCTION_OUTPUT", 1, "credit", MAP, "wip"),
            r("REVALUATION", 0, "debit", MAP, "stock_valuation"),
            r("REVALUATION", 1, "credit", MAP, "stock_valuation"));
}
