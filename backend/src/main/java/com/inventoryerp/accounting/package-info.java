/**
 * Chart of accounts, journals, GL posting, periods, FX, bank reconciliation, treasury, cash registers/sessions, money operations, financial reports. Port of apps/api/src/modules/accounting.
 *
 * <p>Spec: docs/spec/accounting.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Accounting")
package com.inventoryerp.accounting;
