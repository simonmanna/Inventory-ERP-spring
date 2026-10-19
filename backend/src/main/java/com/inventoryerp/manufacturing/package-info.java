/**
 * BOMs, production orders, MRP, routings, work orders (ENABLE_MANUFACTURING). Port of apps/api/src/modules/manufacturing.
 *
 * <p>Spec: docs/spec/manufacturing.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Manufacturing")
package com.inventoryerp.manufacturing;
