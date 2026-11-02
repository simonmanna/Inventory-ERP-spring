# Helpdesk — tickets and correspondence register

Extension module (not in the Nest reference). Design: [ADR-S045](../adr/ADR-S045-helpdesk-correspondence.md).
Tests: `HelpdeskIT`. Built: HD-0 (design), HD-1 (core), HD-2 (letters), HD-5 (web). Not built: HD-3 (IMAP e-mail
ingestion), HD-4 (SLA policies), HD-6 (portal form).

## Routes

| Verb | Path | Permission | Purpose |
|---|---|---|---|
| GET | `/helpdesk/teams[/{id}]` | `helpdesk:ticket:read` | Teams with members, stages, open count (default team created on first use) |
| POST / PATCH | `/helpdesk/teams[/{id}]` | `helpdesk:team:manage` | `{name, code, description, email, assignment: manual|round_robin|balanced, leaderId, memberIds, isActive}` |
| POST | `/helpdesk/teams/{id}/stages` | `helpdesk:team:manage` | Add a stage `{name, sequence, isClosed, folded}` |
| PATCH / DELETE | `/helpdesk/stages/{id}` | `helpdesk:team:manage` | Edit; delete when unused and not the last |
| GET / POST / PATCH | `/helpdesk/types[/{id}]` | read / manage | Ticket types |
| GET / POST / PATCH | `/helpdesk/letter-templates[/{id}]` | read / manage | Letter templates (`{{placeholders}}`) |
| GET | `/helpdesk/tickets` | `helpdesk:ticket:read` | `?teamId&stageId&assigneeId&mine&unassigned&priority&channel&direction&state=open|closed&q&partnerId&relatedResType&relatedResId&page&pageSize` |
| POST | `/helpdesk/tickets` | `helpdesk:ticket:write` | Create (number `HD-<year>-00001`, first stage, auto-assignment) |
| GET / PATCH | `/helpdesk/tickets/{id}` | read / write | Detail (messages, DMS attachments, stages, parent / children, related record) / edit (`version` → 409 when stale) |
| DELETE | `/helpdesk/tickets/{id}` | `helpdesk:team:manage` | Soft delete |
| POST | `/helpdesk/tickets/{id}/assign` | `helpdesk:ticket:assign` | `{assigneeId}` (null = unassign); notifies the assignee |
| POST | `/helpdesk/tickets/{id}/stage` | write (+ `helpdesk:ticket:close` into / out of a closed stage) | `{stageId, resolution}` |
| POST | `/helpdesk/tickets/{id}/close`, `/reopen` | `helpdesk:ticket:close` | Close (first closed stage) / reopen (first open stage) |
| GET / POST | `/helpdesk/tickets/{id}/messages` | read / write | Thread; post `{kind: note|reply|inbound, body, sendEmail}` (reply e-mails the contact) |
| GET | `/helpdesk/letters` | read | Register: `?direction&year&q&state&awaitingReply&partnerId&page&pageSize` |
| POST | `/helpdesk/letters/incoming` | write | Multipart: letter fields + scans (`file`) → `IN-<year>-0001`, scans into Correspondence/Incoming/<year> |
| POST | `/helpdesk/letters/outgoing` | write | `{ticket: {...}, letter: {templateId, body, closingLine, signatureName, signatureTitle}}` → `OUT-<year>-0001` + PDF |
| POST | `/helpdesk/tickets/{id}/reply-letter` | write | Outgoing letter replying to an incoming one (chain + "replied") |
| POST | `/helpdesk/tickets/{id}/render-letter` | write | Re-render the PDF (new DMS document) |
| POST | `/helpdesk/tickets/{id}/dispatch` | `helpdesk:letter:dispatch` | `{method: post|registered_post|courier|hand|email|fax, trackingNumber, sentAt}` |
| GET | `/helpdesk/dashboard` | `helpdesk:report:read` | Open / mine / unassigned / urgent, letters awaiting reply / overdue, avg first response, by team / channel / priority, recent |

## Letter placeholders

`{{contact.name}}`, `{{partner.name}}`, `{{subject}}`, `{{body}}`, `{{letter.number}}`, `{{ticket.number}}`,
`{{date}}`, `{{user.name}}`, `{{organization.name}}`, and for replies `{{reply.letterNumber}}`,
`{{reply.externalRef}}`, `{{reply.letterDate}}`, `{{reply.subject}}`.

## Events

`helpdesk.ticket.created|assigned|stage_changed|replied|closed`, `helpdesk.letter.received|dispatched`.

## Web

`/helpdesk` (overview), `/helpdesk/tickets` (board per team with drag-between-stages, or list), `/helpdesk/tickets/:id`
(stage bar, thread with notes / replies / logged messages, assignee, correspondence panel, reply-with-letter,
dispatch, attachments), `/helpdesk/letters` (incoming / outgoing register, register incoming, new outgoing),
`/helpdesk/settings` (teams, members, assignment, stages, ticket types, letter templates).
