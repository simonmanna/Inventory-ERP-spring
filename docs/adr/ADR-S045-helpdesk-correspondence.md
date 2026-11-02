# ADR-S045: Helpdesk and correspondence register

- Status: Accepted (owner chose "full helpdesk tickets" for letters, 2026-11-02; HD-0, HD-1, HD-2, HD-5 built)
- Spec: [`docs/spec/helpdesk.md`](../spec/helpdesk.md)
- Migration: `V239__helpdesk.sql`

## Context

The owner wants letters sent and received to be registered and attached in the system "like a helpdesk", next to
the record attachments of [ADR-S044](ADR-S044-document-management.md). There was no ticket or correspondence module.

## Decision

1. **New module `helpdesk`** (`com.inventoryerp.helpdesk`), permissions `helpdesk:*`, events `helpdesk.*`.
2. **One ticket model for every channel**: e-mail, portal, phone, walk-in, WhatsApp, other, and **letters**. A letter
   is a ticket with `channel = letter` and a direction (inbound / outbound), so letters get the same team, stage,
   assignee, thread, priority and attachments as any ticket.
3. **Numbering**: every ticket `HD-<year>-00001` (kernel sequence); letters additionally `IN-<year>-0001` /
   `OUT-<year>-0001`, so the correspondence register has its own gap-free yearly series.
4. **Teams and stages**: per-team stages (closed / folded flags), assignment manual, round robin or fewest open
   tickets; a default "Support" team with five stages and two letter templates is created on first use. Moving into
   or out of a closed stage needs `helpdesk:ticket:close`.
5. **Thread in the helpdesk module** (`HdTicketMessage`: note / reply / inbound / system) instead of reusing the
   `communication` conversations: that module is optional (`ENABLE_COMMUNICATION`) and chat-shaped; a ticket thread
   must always exist. A reply can be e-mailed through the existing SMTP sender (`NotificationsService`).
6. **Correspondence register**: their reference, our reference, letter date, received / sent time, reply-due date,
   confidentiality, dispatch method and tracking number, reply chain (`parentTicketId` / `repliedByTicketId`).
   Incoming scans and generated outgoing letters are DMS documents in the `correspondence` workspace under
   `Incoming/<year>` and `Outgoing/<year>`, linked to the ticket.
7. **Outgoing letters** are rendered to A4 PDF with OpenPDF from a template with `{{placeholders}}` (contact,
   partner, letter number, reply references, date, user, organization). No new dependency.
8. **E-mail ingestion (IMAP), SLA policies and the customer-portal ticket form** (HD-3, HD-4, HD-6) are not built
   yet; Spring Mail (Jakarta Mail) is already on the classpath for them.

## Consequences

- Letters, scans and replies are searchable in the DMS (extracted text, OCR when enabled).
- PDF letters use the base-14 Helvetica font: Latin script only until an embedded Unicode font is added.
