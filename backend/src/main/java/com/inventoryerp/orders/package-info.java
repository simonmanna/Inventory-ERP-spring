/**
 * Generic sales orders (ENABLE_ORDERS). Port of apps/api/src/modules/orders.
 *
 * <p>Spec: docs/spec/orders.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Orders")
package com.inventoryerp.orders;
