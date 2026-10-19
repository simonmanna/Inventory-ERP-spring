/**
 * Bottle weigh counts and bar variance reports (ENABLE_BEVERAGE). Port of apps/api/src/modules/beverage.
 *
 * <p>Spec: docs/spec/beverage.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Beverage")
package com.inventoryerp.beverage;
