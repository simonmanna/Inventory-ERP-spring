package com.inventoryerp.invoicing.application;

import java.util.List;
import java.util.Map;

/**
 * Extra checks before a supplier bill is posted (ADR-PROCUREMENT-001 wave 5), implemented by the purchasing module
 * (duplicate detection). Runs inside the posting transaction after the match and approval gates.
 */
public interface VendorBillPostGuard {

    /**
     * Inspect the bill about to post ({@code bill} = the document row). Throw an {@code ApiException} to refuse the
     * post; return warnings to show with the posted bill (empty = nothing to report).
     */
    List<String> beforePost(String billId, Map<String, Object> bill);
}
