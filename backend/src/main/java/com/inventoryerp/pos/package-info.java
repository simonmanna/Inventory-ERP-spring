/**
 * Cafe point of sale: orders, tabs, tables, split bills, menu, modifiers, KDS, holds, loyalty, receipts/printing, POS reports, public QR menu. Port of apps/api/src/modules/pos.
 *
 * <p>Spec: docs/spec/pos.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "POS")
package com.inventoryerp.pos;
