package com.inventoryerp.accounting.application;

import java.util.List;

/**
 * IPR GL-10: the inventory checks on stock value (valuation vs stock ledger, valuation vs GL, per product), run live for
 * the current organisation. Declared here and implemented by the inventory module, which depends on this one, so the
 * period close can re-run them without a dependency cycle.
 */
public interface InventoryValueChecks {

    /** One failing check: its key, the number of rows that break it and its note. */
    record Failure(String key, long failures, String note) {}

    /**
     * The value checks that fail now for the current organisation (tenant context). A check on the GL is left out while
     * {@code inventory.glTieOut.enabled} is off, as the daily monitor does.
     */
    List<Failure> failing();
}
