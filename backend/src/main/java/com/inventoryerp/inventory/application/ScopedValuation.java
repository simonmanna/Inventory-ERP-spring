package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.InventoryValuationService;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IPR S-1: the accounting valuation by costing method over a set of locations (the valuation service takes one
 * location or all). {@code null} locations = every location; one location is passed through; several are read
 * organisation-wide and kept to those locations, row by row, so the figures equal the sum of the single-location
 * calls.
 */
final class ScopedValuation {

    private ScopedValuation() {}

    /** {@link InventoryValuationService#currentValueByQuant(String, Collection)} kept to {@code locations}. */
    static List<Map<String, Object>> byQuant(InventoryValuationService valuation, List<String> locations,
            Collection<String> productIds) {
        if (locations == null || locations.size() == 1) {
            return valuation.currentValueByQuant(LocationScope.single(locations), productIds);
        }
        Set<String> keep = new HashSet<>(locations);
        return valuation.currentValueByQuant(null, productIds).stream()
                .filter(r -> keep.contains((String) r.get("location_id"))).toList();
    }

    /** {@link InventoryValuationService#qtyAndValueByProduct} kept to {@code locations}. */
    static Map<String, BigDecimal[]> byProduct(InventoryValuationService valuation, Collection<String> productIds,
            List<String> locations) {
        if (locations == null || locations.size() == 1) {
            return valuation.qtyAndValueByProduct(productIds, LocationScope.single(locations));
        }
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
        for (Map<String, Object> r : byQuant(valuation, locations, productIds)) {
            BigDecimal[] s = sums.computeIfAbsent((String) r.get("product_id"),
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            s[0] = s[0].add(SqlRows.dec(r, "qty"));
            s[1] = s[1].add(SqlRows.dec(r, "value"));
        }
        return sums;
    }
}
