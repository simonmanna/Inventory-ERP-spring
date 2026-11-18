package com.inventoryerp.inventory.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Readiness gaps h-02 F2: the costing-change lock protocol. Every stock move holds, before it reads a product's costing
 * method, lot tracking or active standard, the organisation key and the product key ({@link StockService#costingLockKey})
 * shared. The re-costing services take them exclusively, so a move runs wholly before a re-costing (which waits for it)
 * or wholly after it (reading the new method):
 * <ul>
 *   <li>cost policy activation (AVCO ↔ FIFO of every product in scope): the organisation key exclusively;</li>
 *   <li>standard cost activation and the cost price change of a STANDARD product: the organisation key shared and the
 *       product key exclusively;</li>
 *   <li>value changes that read the method like a move (revaluation post / reverse, lineage exception assignment): both
 *       keys shared, like a move.</li>
 * </ul>
 * The organisation key is always taken before the product key, and the quant locks after both, so the protocol adds no
 * lock cycle to the engine's order (organisation, product, quant advisory key, {@code StockItem} row).
 */
final class CostingLocks {

    private CostingLocks() {}

    /** Cost policy activation: no stock move (or valuation change) of the organisation runs while it re-costs. */
    static void lockOrganisation(JdbcClient jdbc, String org) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param(StockService.costingLockKey(org, null))
                .query().listOfRows();
    }

    /** Standard cost activation / cost price change: no stock move of the product runs while it is re-costed. */
    static void lockProduct(JdbcClient jdbc, String org, String productId) {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(hashtext(?)::bigint), pg_advisory_xact_lock(hashtext(?)::bigint)")
                .params(StockService.costingLockKey(org, null), StockService.costingLockKey(org, productId)).query()
                .listOfRows();
    }

    /** A valuation change that reads the product's method as a move does: both keys shared. */
    static void shareProduct(JdbcClient jdbc, String org, String productId) {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(hashtext(?)::bigint),"
                        + " pg_advisory_xact_lock_shared(hashtext(?)::bigint)")
                .params(StockService.costingLockKey(org, null), StockService.costingLockKey(org, productId)).query()
                .listOfRows();
    }

    /**
     * The quants of a product, locked in the engine's order and re-read under the locks: with {@code advisory}, first the
     * advisory key of each quant in (variantKey, locationId) order (as {@link LayerCostWriter} and the engine take
     * them), then the rows {@code FOR UPDATE}. A cost policy activation passes {@code advisory = false}: it holds the
     * organisation key, so no move runs, and one advisory key per quant of every product in scope would not fit the
     * lock table of a large catalogue.
     */
    static List<Map<String, Object>> lockQuants(JdbcClient jdbc, String org, String productId, boolean nonZeroOnly,
            boolean advisory) {
        String filter = nonZeroOnly ? " AND quantity <> 0" : "";
        if (advisory) {
            List<Map<String, Object>> keys = jdbc.sql("SELECT \"variantKey\", \"locationId\" FROM \"StockItem\" WHERE"
                    + " \"organizationId\" = ? AND \"productId\" = ?" + filter).params(org, productId).query()
                    .listOfRows();
            keys.stream().sorted(Comparator.comparing((Map<String, Object> k) -> Objects.toString(k.get("variantKey"), ""))
                            .thenComparing(k -> (String) k.get("locationId")))
                    .forEach(k -> jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)")
                            .param(StockService.quantLockKey(org, productId, Objects.toString(k.get("variantKey"), ""),
                                    (String) k.get("locationId")))
                            .query().listOfRows());
        }
        return jdbc.sql("SELECT id, \"variantId\", \"variantKey\", \"locationId\", quantity, \"runningAverageCost\" FROM"
                + " \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ?" + filter + " ORDER BY \"variantKey\","
                + " \"locationId\" FOR UPDATE").params(org, productId).query().listOfRows();
    }
}
