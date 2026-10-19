/**
 * pg_dump/restore, encryption, offsite copy, restore tests. Port of apps/api/src/modules/backup.
 *
 * <p>Spec: docs/spec/backup.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Backup")
package com.inventoryerp.backup;
