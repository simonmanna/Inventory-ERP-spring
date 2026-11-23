/**
 * Gate pass (ADR-S085): the gate-out document for the driver, vehicle or person who takes goods out through the gate.
 * Issued beside every delivery point (sales delivery / trip, sales order and invoice handover, branch cash sale, stock
 * transfer, warehouse pick order, rental checkout, repair return, supplier return), printed with a QR code, verified
 * and let out by the gate guard. New module (no NestJS counterpart).
 *
 * <p>Spec: docs/spec/gate-pass.md. Reads its source documents with read-only SQL. At gate-out it marks the source
 * "departed" through the owning module's application API (fleet trip depart, sales handover / cash sale delivery
 * status) where that step posts nothing. Nothing depends on this module.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Gate pass")
package com.inventoryerp.gatepass;
