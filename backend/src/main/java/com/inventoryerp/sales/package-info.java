/**
 * Sales / order-to-cash (ADR-S011): quotations, sales orders and amendments, pricelists, agreements, returns and
 * collections. Owns commercial commitments and orchestrates fulfilment (fleet), stock (inventory), billing
 * (invoicing) and credit through their application APIs — it never writes their tables. New module of the
 * construction-materials build (no NestJS counterpart).
 *
 * <p>Spec: docs/spec/sales.md, phases: docs/spec/sales-phases.md, design: docs/adr/ADR-S011-sales-o2c.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Sales")
package com.inventoryerp.sales;
