/**
 * Fleet & logistics (ENABLE_FLEET): vehicle &amp; equipment registry, meters and compliance documents (1.1); drivers,
 * dispatch and trips, weighbridge, fuel, maintenance, tyres / incidents, telematics and fleet costing follow in
 * sub-modules 1.2–1.9. New module of the construction-materials build (no NestJS counterpart).
 *
 * <p>Spec: docs/spec/fleet.md, design: docs/adr/ADR-S009-fleet-and-logistics.md. Entities in {@code domain} are
 * exposed so other modules may read them; the services in {@code application} are the module's public API (other
 * modules and later fleet sub-areas call them, never the tables).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Fleet")
package com.inventoryerp.fleet;
