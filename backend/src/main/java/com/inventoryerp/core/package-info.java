/**
 * Organizations, branches, partners, products, UoM, taxes, fiscal periods/positions, payment terms. Port of apps/api/src/modules/core.
 *
 * <p>Spec: docs/spec/core.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Core master data")
package com.inventoryerp.core;
