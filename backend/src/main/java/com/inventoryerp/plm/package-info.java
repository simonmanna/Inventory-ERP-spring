/**
 * Product lifecycle management (ADR-S063, its own application since ADR-S086): product lifecycle and revisions,
 * engineering change requests and orders with typed stage pipelines and approvals, versions, engineering documents,
 * and the lifecycle policy other modules consult. An add-on of manufacturing: it plugs into manufacturing through
 * {@code manufacturing.spi} and into purchasing, sales and inventory through {@code core.application}; nothing
 * depends on it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Product Lifecycle Management")
package com.inventoryerp.plm;
