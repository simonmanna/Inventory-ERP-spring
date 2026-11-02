/**
 * Document management (DMS, ADR-S044; Spring-native, no Nest reference): workspaces, folders, documents with versions,
 * tags, links to any ERP record (sales orders, purchase orders, expenses, invoices, CRM, helpdesk tickets ...),
 * workspace access rules, full-text search over extracted text (ADR-S046), expiry reminders, document requests,
 * approvals and external share links. Spec: docs/spec/dms.md.
 *
 * <p>File bytes stay in the kernel file vault ({@code kernel.files}); this module only references {@code File} rows.
 * Not to be confused with the {@code documents} module, which is the business-document engine (invoices, lifecycles,
 * templates). Other modules contribute record types through {@code dms.spi.DmsResourceProvider} and attach files
 * through {@code dms.spi.DmsAttachments}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Document management")
package com.inventoryerp.dms;
