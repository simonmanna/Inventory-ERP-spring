/**
 * Kanban tasks, templates, labels, auto-rules (ENABLE_TASKS). Port of apps/api/src/modules/task.
 *
 * <p>Spec: docs/spec/task.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Tasks")
package com.inventoryerp.task;
