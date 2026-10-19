/**
 * Asset register, depreciation, lifecycle (ENABLE_ASSETS). Port of apps/api/src/modules/fixed-asset.
 *
 * <p>Spec: docs/spec/fixedasset.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Fixed assets")
package com.inventoryerp.fixedasset;
