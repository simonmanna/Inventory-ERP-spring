/**
 * Operational quality control (core-available, ADR-S087): Odoo-style control points that generate operator checks on
 * receipts, deliveries, manufacturing orders and work orders; check execution with immutable revisions and sample
 * records (instructions, pass / fail, measure, picture, worksheet); blocking of the source document; quarantine of
 * failed quantities; quality teams, alerts and SPC. Beyond parity (no NestJS counterpart).
 *
 * <p>Separate from the formal / laboratory quality module ({@code quality}, ADR-S013): manufacturing, procurement and
 * sales call this module's {@code application} API ({@code QcGate}); this module never imports them.
 *
 * <p>Spec: docs/spec/quality-control.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Quality control")
package com.inventoryerp.qualitycontrol;
