/**
 * Quarry &amp; extraction management (ENABLE_QUARRY, construction-materials Track C Phase 2.2): quarry sites,
 * extraction zones / benches / faces, material types and grades, extraction plans and targets, daily extraction logs
 * (shift, operator, mobile plant, loads × capacity or weighbridge tonnage), drilling &amp; blasting with explosives as
 * controlled stock (approval-gated issues from magazines), haulage face → ROM stockpile / crusher, ROM stockpiles as
 * inventory locations receiving raw stone at its extraction cost per tonne, losses, and the extraction vs target, cost
 * per tonne, losses and royalties reports. New module (no NestJS counterpart).
 *
 * <p>Spec: docs/spec/quarry.md, design: docs/adr/ADR-S012-quarry-and-extraction.md. Entities in {@code domain} are
 * exposed for queries; the services in {@code application} are the module's public API ({@code QuarryStockpileService}
 * for crushing 2.3).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Quarry")
package com.inventoryerp.quarry;
