# Notification routing — `notificationrouting`

| | |
|---|---|
| Nest source | none, new module |
| Design | [ADR-S065](../adr/ADR-S065-notification-routing.md) |
| Routes | 4, under `/notification-routing` |
| Switch | `NOTIFICATION_ROUTING_ENABLED` (default `true`) → `app.notification-routing.enabled` |
| Java package | `com.inventoryerp.notificationrouting` (`spi` is a named interface) |
| Migration | `V444__notification_routing.sql` (also `EventOutbox."actorId"`) |
| Permissions | `notification_routing:read`, `notification_routing:manage` (granted to Administrator) |
| Screen | Settings → Customization → Notification Routing (`/settings/notification-routing`) |

When a catalogued event happens, the people responsible for the record it concerns are told. The organization can
also add roles and named users, and choose the channels. The person who caused the event is never told. Inactive
users and users of other organizations are never told.

## Routes

| Route | Permission | Purpose |
|---|---|---|
| `GET /notification-routing/events` | `notification_routing:read` | Every event of the enabled modules: parties, defaults, what is in force, `customized` |
| `PUT /notification-routing/events/{event}` | `notification_routing:manage` | `{enabled, parties[], roleIds[], userIds[], channels[]}`; audited |
| `DELETE /notification-routing/events/{event}` | `notification_routing:manage` | Back to the defaults; audited |
| `GET /notification-routing/activity` | `notification_routing:read` | Latest routed events and how many people each reached (`limit` ≤ 200) |

Errors: an unknown event returns `404 Event not found`. These return `400`: `Unknown party <key>`, `Unknown channel
<c>`, `Role not found` and `User not found` (another organization's user also gets this one).

## Catalog

Defaults are in bold. All routes default to in-app only.

| Area | Event | Parties |
|---|---|---|
| sales | `sales.order.confirmed` | **owner**, **creator**, team leader |
| sales | `sales.order.held` | owner, creator, **team leader** (the owner hears via `notifyOnHold`) |
| sales | `sales.order.released` | **owner**, creator, team leader |
| sales | `sales.order.cancelled` | **owner**, **creator**, team leader |
| sales | `sales.order.amended` | **owner**, creator, team leader |
| sales | `sales.return.received` | **order owner**, **requester** |
| sales | `sales.cash_sale.approved` | **submitter**, **salesperson** |
| sales | `crm.quotation.approved` | **owner**, **creator** |
| sales | `crm.quotation.converted` | **owner**, creator |
| sales | `invoice.posted` / `invoice.paid` / `invoice.cancelled` | **salesperson**, **order owner** (cash-sale invoices skipped) |
| sales | `creditnote.issued` | **salesperson of the original invoice**, return requester |
| sales | `payment.received` (inbound, not refunds) | **invoice salesperson**, credit controller |
| sales | `crm.deal.assigned` (new) | **owner**, creator, team leader |
| sales | `crm.deal.won` / `crm.deal.lost` | **owner**, creator, **team leader** |
| purchasing | `purchase_request.approved` / `rejected` (not via the approval engine) | **requester** |
| purchasing | `purchase_request.converted` | **requester** |
| purchasing | `purchase_order.approved` (not via the approval engine) | **buyer**, requester |
| purchasing | `purchase_order.cancelled` / `purchase_order.closed` | **buyer**, **requester** |
| purchasing | `purchase_order.changed` | **buyer**, requester, **change requester** |
| purchasing | `goods_receipt.posted` | **buyer**, **requester** |
| purchasing | `bill.posted` | **PO buyer**, bill entered by |
| purchasing | `bill.duplicate_suspected` | **entered by**, **PO buyer** |
| purchasing | `rfq.awarded` | **creator**, **PR requesters** |
| inventory | `purchase_request.created` (reorder lines only) | **buyers** (`purchase_order:create`), requester |
| projects | `project.task.created` (project tasks) | **project manager** |
| projects | `project.task.stage_changed` (not into a closed stage) | **assignees**, creator, followers |
| projects | `project.task.completed` | **project manager when not following**, creator |
| projects | `task.assigned` (new, `/tasks`) | **assignee**, supervisor |
| helpdesk | `helpdesk.ticket.created` (unassigned only) | **team leader**, **team members** |
| helpdesk | `helpdesk.ticket.closed` | **creator**, **assignee** |
| hr | `hr.leave.requested` (new) | **manager** (supervisor + department manager), HR officers (`hr:leave`), employee |
| hr | `hr.leave.decided` (new) | **employee**, manager |
| maintenance | `fleet.workorder.created` | **technician**, creator, fleet maintenance managers |
| maintenance | `fleet.workorder.completed` | **creator**, **defect reporters**, **vehicle's driver**, managers |
| maintenance | `fixed_asset.assigned` | **custodian** (employee custodians only), creator |
| fleet | `fleet.defect.reported` | **fleet maintenance managers** |
| fleet | `fleet.incident.reported` | **fleet incident managers**, **driver's manager**, driver |
| fleet | `fleet.fine.charged` (charged to the driver) | **driver**, driver's manager |
| fleet | `fleet.trip.dispatched` (own fleet) | **driver** |
| fleet | `fleet.claim.settled` | **driver** |
| production | `manufacturing.order.completed` / `cancelled` (new) | **creator** |
| production | `plm.change.created` | **owner**, creator, **requester** |
| production | `plm.change.stage_changed` | **approvers of the new stage**, **owner**, creator, requester |
| production | `plm.change.approved` | **owner**, creator, **requester** |
| production | `plm.change.applied` | **owner**, creator, **requester**, **product owner** |
| production | `plm.request.created` | **product owner**, engineering approvers (`plm:approve`) |
| production | `plm.lifecycle.changed` | **product owner** |
| quality | `quality.lot.decided` (release / reject / downgrade) | **production order creator**, **lot creator** |
| quality | `quality.ncr.raised` | **owner**, **order creator when no owner** |
| quality | `quality.capa.created` (new) | **owner** |
| quality | `quality.capa.closed` | **NCR owner**, **CAPA owner**, **creator** |

Left out because the module already tells the right people:
- project task assigned, milestones, project updates
- helpdesk assignment
- DMS requests
- rejected or returned cash sales
- expense decisions
- CRM cases
- credit requests

## Add an event

1. Publish the event (add it to `packages/shared/src/events.ts`, then run `node tools/contracts2java/generate.mjs`).
2. Add a `RoutedEvent` to the module's `*NotificationRoutes` bean, as follows:
   - Make it conditional on the module's flag.
   - Write a record query that also checks the announced state.
   - Write party queries that filter on `:org`.
3. Run `NotificationRoutingIT`: it executes every query.

## Status

Built. `NotificationRoutingIT` (6 tests) passes: the catalog against the schema, routing (once per row, never the
actor, never inactive users, stale events skipped), settings, tenancy, validation, authorization and the outbox actor
stamp. The web screen typechecks; it has not been clicked through against a running server.
