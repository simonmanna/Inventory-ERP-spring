/**
 * Advanced costing remainder (roadmap 3.1, ADR-S030): material-ledger periodic actual costing of standard-costed
 * products, standard-cost variance analysis and activity-based costing. New module (no NestJS counterpart); reads
 * inventory, invoicing and manufacturing data, posts only through the accounting posting engine.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Costing")
package com.inventoryerp.costing;
