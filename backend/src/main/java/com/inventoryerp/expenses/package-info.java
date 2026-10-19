/**
 * Expense categories and expenses (approve/reject/pay/void). Port of apps/api/src/modules/expenses.
 *
 * <p>Spec: docs/spec/expenses.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Expenses")
package com.inventoryerp.expenses;
