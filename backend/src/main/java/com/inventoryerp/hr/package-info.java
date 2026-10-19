/**
 * Employees, attendance, leave, payroll (ENABLE_HR). Port of apps/api/src/modules/hr.
 *
 * <p>Spec: docs/spec/hr.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "HR")
package com.inventoryerp.hr;
