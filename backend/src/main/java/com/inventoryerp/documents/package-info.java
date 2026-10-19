/**
 * DMS: documents, templates, relations, lifecycles, attachments, PDF rendering. Port of apps/api/src/modules/documents.
 *
 * <p>Spec: docs/spec/documents.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Documents")
package com.inventoryerp.documents;
