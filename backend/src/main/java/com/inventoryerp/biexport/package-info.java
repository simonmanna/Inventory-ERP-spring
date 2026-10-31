/**
 * BI export (Phase 5, docs/integrations.md): the {@code reporting} views of migration V111 and a CSV route over them.
 * Reads those views and nothing else, so it depends on no business module.
 */
@org.springframework.modulith.ApplicationModule(displayName = "BI export")
package com.inventoryerp.biexport;
