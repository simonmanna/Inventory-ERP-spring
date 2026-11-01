/**
 * Customization platform (roadmap 2.5, ADR-S029): custom fields on core records, custom objects and their records,
 * per-organization print templates (designer, preview, HTML / PDF rendering) and event-driven automation rules. New
 * module (no NestJS counterpart). API clients and the OAuth token endpoint live in {@code kernel.apiclients}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Customization")
package com.inventoryerp.customization;
