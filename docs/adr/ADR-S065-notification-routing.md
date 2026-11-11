# ADR-S065 — Notification routing: tell the responsible people when something happens

- Status: accepted (built 2026-11-10)
- Migration: `V444__notification_routing.sql`
- Extension: no NestJS counterpart. New module `com.inventoryerp.notificationrouting`; routes under
  `/notification-routing`; spec [docs/spec/notification-routing.md](../spec/notification-routing.md).

## Context

The delivery layer existed: the `Notification` table with in-app, e-mail, SMS and push channels, per-user opt-outs, the
bell, scheduled alerts (ADR-S021) and approval notifications. Who gets told about what was decided module by module,
though:

- Most of the 248 domain events reached nobody. A salesperson did not learn that their order was cancelled, a buyer
  that goods arrived, a requester that their purchase request was rejected, an employee that their leave was decided.
- The two rule engines target fixed people. Automation rules (ADR-S029) and communication rules notify a named
  user, a role or a permission. Nothing looked up who is responsible for the record that an event is about.
- Manufacturing orders, HR leave, the task module, opportunity assignment and CAPA creation published no event at all.

## Decision

1. **The owning module declares what is routable.** It does this through `notificationrouting.spi.RoutedEventSource`,
   the same shape as `alerts.spi.AlertSource`. Each `RoutedEvent` gives:
   - the event name, and the area (also the user's opt-out category)
   - the payload field holding the record id
   - a record query (`:id`, `:org`) whose columns feed the title, body and link templates
   - the **parties**: the people responsible for the record (salesperson, assignee, requester, manager, ...), each a
     query returning `"User".id`s, or a payload field, or the holders of a permission
   - which parties are told by default

   A disabled module's source bean is not created, so its events are neither offered nor subscribed to.
2. **Per-organization routes.** With no `NotificationRoute` row the defaults apply: on, default parties, in-app. An
   admin can:
   - switch an event off
   - pick parties
   - add roles and named users
   - choose channels (in-app, e-mail, push, SMS)

   Changes are audited.
3. **The router** subscribes to every catalogued event on the outbox. For each delivery it does the following:
   - Claims the outbox row once (`NotificationRouteClaim`), so a retry caused by another subscriber does not notify
     twice.
   - Reads the record. **No row means nothing to announce.** `EventBus` writes the outbox row after the publishing
     transaction, even on rollback, so each record query puts the announced state in its `WHERE`.
   - Collects the recipients, keeps active users of the organization, and drops the actor. Nobody is told about what
     they did themselves.
   - Sends one notification per user and channel through `NotificationsService`, which honours each user's opt-out
     for that category.
   - Never throws. A failure is logged and counted (`notification_routing.failed`), and the outbox row still ships.
4. **The outbox records the actor.** `EventOutbox."actorId"` is stamped from the tenant context at publish.
   `OutboxHandlers.subscribeDelivery` hands subscribers the row id and actor as well as the payload. Existing
   `subscribe(name, payload -> ...)` handlers are unchanged.
5. **New events, only where an area had none:**
   - `hr.leave.requested`, `hr.leave.decided`
   - `manufacturing.order.completed`, `manufacturing.order.cancelled` (single-stage `ProductionService`)
   - `task.assigned`
   - `crm.deal.assigned`
   - `quality.capa.created`

   They are added to `packages/shared/src/events.ts` and published through `EventBus`.
6. **Events already notified by the module stay out of the catalog**, so nobody is told twice:
   - project task assigned, milestone reached, project update posted (`PmSupport.notifyUsers`)
   - helpdesk ticket assigned
   - DMS requests
   - rejected or returned cash sales
   - expense decisions

   Where a module notifies some parties, the route defaults to the others. Examples: a sales-order hold goes to the
   team leader, and a completed project task goes to a project manager who is not following it. Decisions taken
   through the approval engine are skipped for purchase requests and orders, because `approval.decided` already told
   the requester.
7. **Kill switch:** `NOTIFICATION_ROUTING_ENABLED=false` stops subscribing. The settings stay editable.

## Consequences

- Adding an event means one `RoutedEvent` in the owning module. `NotificationRoutingIT.catalogQueriesRun` runs every
  record and party query against the schema, so a typo fails the build, not production.
- A broken party query fails the event, because Postgres aborts the transaction. That is why the IT above exists.
- Some responsibilities are guesses because the schema has no owner column:
  - A production order's creator stands in for the planner.
  - The holders of `fleet_maintenance:update` / `fleet_incident:update` stand in for the fleet manager.
  - An asset custodian is reached only when `assignedToType = employee`.
  - `"Document"."salespersonId"` is resolved as both a user id and an HR employee id, because it holds either.
- Not built yet (follow-ups):
  - escalation when nobody acts
  - digest e-mails
  - events for stock transfers, repair status, multi-stage production orders, work-order stops, PLM rejections and
    supplier-claim decisions
  - list-only screens (purchase requests, transfers, expenses) cannot deep-link to one record
