/**
 * Quality management (core-available, ADR-S013): versioned specifications, inspection plans and lots (incoming /
 * in-process / final), test results with sieve grading, hold / quarantine / release / reject / downgrade decisions,
 * NCR / CAPA, laboratory result import ({@code LabResultAdapter}), batch traceability and quality reports. New module
 * of the construction-materials build (no NestJS counterpart).
 *
 * <p>Spec: docs/spec/quality.md. Depends on inventory (stock), accounting (GL) and kernel only; manufacturing, procurement
 * and fleet call its {@code application} API or are observed through their events and read-only SQL.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Quality")
package com.inventoryerp.quality;
