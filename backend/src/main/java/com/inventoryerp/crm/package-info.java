/**
 * Deals, activities, pipeline analytics. Port of apps/api/src/modules/crm.
 *
 * <p>Spec: docs/spec/crm.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "CRM")
package com.inventoryerp.crm;
