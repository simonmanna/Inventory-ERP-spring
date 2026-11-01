/**
 * Intercompany & multi-company (roadmap 2.7, ADR-S028): company groups of organizations, company switching for users
 * who hold an account in several member companies, intercompany partners, sales invoices mirrored as vendor bills,
 * intercompany journals posted in two companies at once, intercompany reconciliation and group consolidation with
 * eliminations. Each company stays its own tenant; this module never widens a tenant, it works company by company
 * through the accounting, invoicing and core application APIs. New module (no NestJS counterpart).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Intercompany")
package com.inventoryerp.intercompany;
