# ADR-S021: Scheduled alerts and exception reports

- Status: Accepted (autonomous, 2026-10-31). Built without compiling or running anything (Phase 5 rule).
- Brief: [`AUTONOMOUS_BUILD_CONSTRUCTION.md`](../AUTONOMOUS_BUILD_CONSTRUCTION.md) Phase 5
- Spec: [`docs/spec/alerts.md`](../spec/alerts.md)
- Migration: `V110__alerts.sql`

## Context

Phase 5 needs alerts for nine kinds of exception whose data lives in ten modules. Several modules already had part of
this: replenishment alerts, the fleet reminder log, the CAPA overdue query. Other modules may not depend on each other at
will, and `manufacturing` and `hr` expose no application layer at all.

## Decision

1. **A new module `alerts` that depends on `kernel` only, with an SPI.** Each owning module implements
   `alerts.spi.AlertSource` next to its own data and returns what is true now. The engine collects the beans and knows
   none of them. A central service reading ten modules' tables would have broken the module rule or forced named
   interfaces onto every module.
2. **Two scans.** `ALERTS_HOURLY_CRON` for what the yard acts on today (stock, weighbridge, curing) and
   `ALERTS_DAILY_CRON` for the rest. Both use ShedLock and run per organization in its tenant scope.
3. **The claim is the de-duplication.** `AlertEvent` is unique on organization, type, subject and period. A subject is
   inserted before anything is sent, so a re-run, a second node or a restart sends nothing twice. A source puts the state
   into the subject key where a change of state must alert again (near the limit, then over it).
4. **One digest per type and run.** Ten licences expiring is one e-mail. A single item keeps its own wording. Every
   subject still has its own row in the list.
5. **Claim in a transaction, deliver after it.** A slow mail server must not hold a transaction. A crash in between loses
   a bell or an e-mail, not the alert, which stays in the list.
6. **Settings in `AlertRule`, defaults in code.** A row exists only once an organization changes a type, so a new type
   reaches every organization without a data migration.
7. **Off by default** (`ENABLE_ALERTS`). An existing POS-only install gets no new messages after an upgrade.
8. **In-app and e-mail through `NotificationsService`; WhatsApp through `communication`.** `alerts` cannot depend on
   `communication`, which is optional and exposes no application layer. It publishes `alert.raised`, and a subscriber in
   `communication` sends it.

## Consequences

- A type with no enabled source raises nothing, silently. `alerts.failed` counts source failures.
- Acknowledging records that someone saw an alert. It does not silence the subject: if it is still true next period, it
  alerts again. Silencing needs a longer `repeatEvery` or the rule switched off.
- Fleet reminders keep running beside the alerts on their own log. Their in-app row has no user, so nobody sees it in the
  bell; the alert is what reaches a person.
- WhatsApp reaches a staff user only through a partner with the same e-mail and a WhatsApp identity, whose conversation
  has the channel attached. WhatsApp's own rules on business-initiated messages still apply.
