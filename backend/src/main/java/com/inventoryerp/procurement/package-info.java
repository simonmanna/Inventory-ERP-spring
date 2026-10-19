/**
 * Purchase requests/orders, goods receipts, landed costs, debit notes, three-way match. Port of apps/api/src/modules/procurement.
 *
 * <p>Spec: docs/spec/procurement.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Procurement")
package com.inventoryerp.procurement;
