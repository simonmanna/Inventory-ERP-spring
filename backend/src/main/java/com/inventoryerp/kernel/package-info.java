/**
 * Platform kernel: auth, staff, tenancy, idempotency, settings, audit, events/outbox, approvals, files, notifications, feature flags, webhooks, sequences, health. Port of apps/api/src/kernel.
 *
 * <p>Spec: docs/spec/kernel.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Kernel")
package com.inventoryerp.kernel;
