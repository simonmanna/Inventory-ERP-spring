package com.inventoryerp.accounting.application;

import java.time.Instant;
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

    /**
     * IPR review 2 (R2-1): the value checks that fail as of {@code asOf} (the end of the period being closed): the
     * historical stock value from the stock ledger against the GL dated up to {@code asOf}. A check that has no
     * history (current quants against the ledger) runs only while {@code asOf} is not in the past.
     */
    List<Failure> failing(Instant asOf);
}
