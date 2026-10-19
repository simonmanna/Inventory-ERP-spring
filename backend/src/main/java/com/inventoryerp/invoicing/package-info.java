/**
 * Invoices, credit notes, vendor bills, payments and allocations, AR/AP reports. Port of apps/api/src/modules/invoicing.
 *
 * <p>Spec: docs/spec/invoicing.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Invoicing")
package com.inventoryerp.invoicing;
