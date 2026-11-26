package com.inventoryerp.fleet.application;

/**
 * Sales readiness R4d (DEL-4): a check a source module runs before any route dispatches a trip of its documents
 * (approval, quality control). Fleet owns the port and calls every gate from {@link FleetDispatchService#dispatchAt},
 * so the fleet trip routes enforce the same controls as the source module's own routes.
 */
public interface DispatchGate {

    /**
     * Refuse (throw) when the trip may not leave yet. Runs under the trip lock, before any stock or ledger write.
     *
     * @param tripId     the trip (FleetLoad) about to be dispatched
     * @param sourceType the delivery order's source type (e.g. {@code sales_order}), or null
     */
    void beforeDispatch(String tripId, String sourceType);
}
