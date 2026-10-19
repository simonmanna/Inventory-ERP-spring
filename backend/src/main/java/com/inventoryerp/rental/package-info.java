/**
 * Rental catalog, reservations, agreements, returns, deposits (ENABLE_RENTAL). Port of apps/api/src/modules/rental.
 *
 * <p>Spec: docs/spec/rental.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Rental")
package com.inventoryerp.rental;
