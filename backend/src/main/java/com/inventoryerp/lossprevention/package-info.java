/**
 * Loss prevention (ENABLE_LOSS_PREVENTION, ADR-S077): an append-only ledger of what was recorded and what was observed,
 * reconciliation of the two, and cases a person reviews. Spec: docs/spec/loss-prevention.md.
 *
 * <p>This module only reads other modules. It never posts stock or journals and never changes a POS document.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Loss Prevention")
package com.inventoryerp.lossprevention;
