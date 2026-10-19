/**
 * Offline device sync protocol (/sync/pull, /sync/push, devices, dead letters). Port of apps/api/src/modules/sync and docs/legacy/sync-protocol.md.
 *
 * <p>Spec: docs/spec/sync.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Sync")
package com.inventoryerp.sync;
