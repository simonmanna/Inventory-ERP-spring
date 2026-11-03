/**
 * Helpdesk and correspondence (ADR-S045; Spring-native, no Nest reference): teams with stages and assignment rules,
 * tickets from e-mail / portal / phone / walk-in / WhatsApp and letters, an internal-note and reply thread, and the
 * incoming / outgoing correspondence register (letter numbers, references, dispatch, reply chains, letter templates
 * rendered to PDF). Files are DMS documents linked to the ticket. Spec: docs/spec/helpdesk.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Helpdesk")
package com.inventoryerp.helpdesk;
