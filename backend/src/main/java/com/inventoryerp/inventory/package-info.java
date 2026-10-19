/**
 * Locations, stock items, append-only ledger, costing (AVCO/FIFO/STANDARD/SPECIFIC), stock documents, counts, reservations, posting rules, reports. Port of apps/api/src/modules/inventory.
 *
 * <p>Spec: docs/spec/inventory.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Inventory")
package com.inventoryerp.inventory;
