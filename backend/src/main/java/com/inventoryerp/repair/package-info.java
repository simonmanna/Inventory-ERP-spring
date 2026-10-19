/**
 * Repair orders, diagnosis, quotations, jobs, warranties (ENABLE_REPAIR). Port of apps/api/src/modules/repair.
 *
 * <p>Spec: docs/spec/repair.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Repair")
package com.inventoryerp.repair;
