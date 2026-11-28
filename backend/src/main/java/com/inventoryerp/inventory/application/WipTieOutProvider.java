package com.inventoryerp.inventory.application;

import com.inventoryerp.inventory.application.InventoryIntegrityService.IntegrityCheck;

/**
 * IPR GL-11 / R4: the manufacturing WIP tie-out, per cost object (production order, process costing, late costs).
 * Declared here and implemented by the manufacturing module, which depends on this one, so the integrity report can
 * list it without a dependency cycle ({@link IncomingSupplyProvider} pattern).
 */
public interface WipTieOutProvider {

    /** The {@link InventoryIntegrityService#WIP_TIEOUT} check for the current organisation (organisation level). */
    IntegrityCheck wipTieOut();
}
