package com.inventoryerp.inventory.application;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IPR S-2: the one place that decides whether the caller may see unit costs and stock values. Quantities stay visible
 * under {@code inventory:read}; costs and values need {@code inventory_valuation:read} (the permission of the cost
 * explanation and the integrity checks). Without it the cost fields come back {@code null} and the screens hide them.
 */
public final class CostVisibility {

    /** Cost fields of the stock documents (headers and lines) and their ledger rows. */
    public static final Set<String> DOCUMENT_COST_KEYS = Set.of("unitCost", "totalCost", "totalValue",
            "runningAverageCost", "debit", "credit", "value", "pendingValue", "expiryValue");

    private CostVisibility() {}

    /** Whether the caller holds {@code inventory_valuation:read}. */
    public static boolean seesCost() {
        return TenantContext.has(Permissions.INVENTORY_VALUATION__READ);
    }

    /** Null the given cost / value fields of a row (present keys only). */
    public static void hideCost(Map<String, Object> row, String... keys) {
        if (row == null) {
            return;
        }
        for (String k : keys) {
            if (row.containsKey(k)) {
                row.put(k, null);
            }
        }
    }

    /** {@link #hideCost} on every map nested anywhere in {@code node} (maps and lists are walked). */
    @SuppressWarnings("unchecked")
    public static void hideCostDeep(Object node, Set<String> keys) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (keys.contains(e.getKey()) && !(e.getValue() instanceof Map) && !(e.getValue() instanceof List)) {
                    e.setValue(null);
                } else {
                    hideCostDeep(e.getValue(), keys);
                }
            }
        } else if (node instanceof List<?> list) {
            list.forEach(v -> hideCostDeep(v, keys));
        }
    }
}
