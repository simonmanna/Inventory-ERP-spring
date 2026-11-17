# Loss prevention — `lossprevention`

| | |
|---|---|
| Phase | LP0, all eight work packages built |
| Nest source | none, new module |
| Design | [ADR-S077](../adr/ADR-S077-loss-prevention.md), [LP0 brief](../phases/LP0-exception-intelligence.md) |
| Routes | 27, under `/loss-prevention` |
| Feature flag | `ENABLE_LOSS_PREVENTION` → `app.modules.lossprevention`; `VITE_ENABLE_LOSS_PREVENTION` shows the menu entry |
| Module name | `loss-prevention` (`@RequiresModule`); licence feature `lossPrevention` |
| Java package | `com.inventoryerp.lossprevention` |
| Migrations | `V520` ledger, `V521` rules, settings, windows, runs, findings, `V522` cases |
| Permissions | `lp:view`, `lp:investigate`, `lp:configure`, `lp:ingest` |
| Screen | `/loss-prevention`: overview, cases, reports, reconciliation runs, rules |
| Schedule | `app.lossprevention.cron` (default every 2 minutes): sweep, then run due windows |
| Contract | [`event-envelope.schema.json`](loss-prevention/event-envelope.schema.json), [`payloads.schema.json`](loss-prevention/payloads.schema.json) |

## Routes

`POST` answers 201, except `POST /runs/replay`, which answers 200 like every route behind an `Idempotency-Key`.

| Verb and path | Permission | Purpose |
|---|---|---|
| `POST /events` | `lp:ingest` | Batch ingest; one outcome per event |
| `GET /events`, `GET /events/{id}` | `lp:investigate` | The ledger |
| `GET /dashboard` | `lp:view` | Open cases and the 30-day trend |
| `GET /cases`, `GET /cases/{id}` | `lp:view` | Cases; the detail records the view on the case timeline |
| `POST /cases/{id}/assign` | `lp:investigate` | `{ assigneeId }`; empty clears |
| `POST /cases/{id}/notes` | `lp:investigate` | `{ note }` |
| `POST /cases/{id}/start-review` | `lp:investigate` | `open` or `escalated` → `reviewing` |
| `POST /cases/{id}/escalate` | `lp:investigate` | `{ reason }`; `open` or `reviewing` → `escalated` |
| `POST /cases/{id}/close` | `lp:investigate` | `{ outcome, causeCode?, note? }` |
| `POST /cases/{id}/reopen` | `lp:configure` | `{ reason }`; `closed` → `reviewing` |
| `GET /rules`, `GET /rules/{code}`, `GET /rules/{code}/versions` | `lp:view` | Rules and their history |
| `PUT /rules/{code}` | `lp:configure` | `{ enabled, severity, params }`; writes the next version |
| `GET /settings`, `PUT /settings` | `lp:configure` | `{ branchId?, values }` |
| `GET /runs`, `GET /runs/{id}` | `lp:view` | Run history; a run with its findings |
| `GET /runs/{id}/compare/{otherId}` | `lp:view` | Two runs of one window: added, removed, changed |
| `GET /runs/{id}/verify` | `lp:view` | Repeats the run from its recorded input; `identical` true or false |
| `POST /runs/replay` | `lp:configure` | `{ engine, from, to, branchId? }`, at most 92 days; `Idempotency-Key` required |
| `POST /projection/run` | `lp:configure` | Sweep the ERP's records and run due windows now |
| `GET /reports/performance`, `/reports/by-cashier`, `/reports/bar-variance` | `lp:view` | `from`, `to`, `branchId`; default: the last 30 days |

Visibility, on every read and action:

- only the caller's organization;
- only the caller's branches when their data scope is restricted (ADR-S017);
- never a case whose subject is the caller: the list leaves it out and every other route answers 404.

List filters: `type`, `source`, `branchId`, `deviceId`, `actorId`, `transactionId`, `orderId`, `ticketId`, `lineId`,
`sessionId`, `from`, `to`, `page`, `pageSize` (default 50, at most 200). `from` and `to` take a date (the whole day, in
the organization's zone) or an ISO-8601 instant.

A user with a restricted data scope (ADR-S017) sees only events of their branches. An event without a branch is
outside a restricted scope.

## Ingest

Request: `{ "events": [ … ] }`, 1 to 500 events. Unknown fields are refused. `organizationId` is not a field: the
organization is the caller's.

| Field | Required | Rule |
|---|---|---|
| `eventId` | yes | UUID; unique per organization |
| `source` | yes | 1–40 characters: lowercase letters, digits, `-`, `_` |
| `sourceEventId` | yes | At most 200 characters. With `source`, the key that stores an event once |
| `type` | yes | A canonical type (below) |
| `schemaVersion` | yes | 1 |
| `occurredAt` | yes | ISO-8601 instant with offset; at most 24 hours ahead of the server; any age |
| `evidenceLevel` | yes | `item`, `ticket`, `order`, `session`, `zone` |
| `evidenceSource` | yes | `pos`, `kds`, `stock`, `camera`, `sensor`, `manual` |
| `observationStatus` | yes | `observed`, `inferred`, `unavailable` |
| `payload` | yes | JSON object, at most 65 536 bytes |
| `branchId` | when the organization has branches | An existing branch |
| `businessDate` | no | `YYYY-MM-DD`. Default: the day of `occurredAt` in the organization's zone |
| `confidence` | no | 0 to 1 |
| `deviceId`, `actorId`, `transactionId`, `orderId`, `ticketId`, `lineId`, `sessionId` | no | 1–200 characters |

Canonical types, version 1: `sale.completed`, `sale.line_voided`, `order.cancelled`, `refund.issued`,
`override.approved`, `cash_session.opened`, `cash_session.closed`, `cash_session.reconciled`, `cash.movement`,
`kitchen.ticket_created`, `kitchen.stage`, `stock_count.line`, `staff.session`.

`kitchen.stage` needs `payload.stage`: `preparation_started`, `prepared`, `dispatched`, `delivered`, `remade`,
`wasted` or `cancelled`. The payload of every type is in [`payloads.schema.json`](loss-prevention/payloads.schema.json).
Only `payload.stage` is enforced at ingest; a rule skips an event that lacks a field it needs.

Response:

```json
{ "accepted": 1, "duplicates": 1, "rejected": 1,
  "results": [ { "eventId": "…", "source": "…", "sourceEventId": "…", "status": "accepted", "seq": 42, "reason": null } ] }
```

- `accepted`: stored; `seq` is its position in the ledger.
- `duplicate`: the same `source` and `sourceEventId` is already stored. The first copy is kept, whatever the new one
  contains.
- `rejected`: not stored; `reason` says why. The other events of the batch are not affected.

A batch can be sent again safely. Events may arrive in any order.

## Messages

Other routes:

- 404: `Event not found`, `Case not found`, `Run not found`, `Rule not found`, `Window not found`
- `severity must be one of: info, warning, critical`
- `Unknown parameter '<name>' for rule <code>`
- `Parameter '<name>' must be a number of 0 or more`
- `Unknown setting '<name>'`
- `Setting '<name>' must be a whole number of 0 or more`
- `engine must be one of: cashier, bar`
- `<field> must be an ISO date (YYYY-MM-DD)`
- `to must not be before from`
- `A replay covers at most 92 days`
- `Runs of different windows cannot be compared`
- `A closed case cannot be changed; reopen it first`
- `Assignee not found`
- `The subject of a case cannot be assigned to it`
- `Only an open or escalated case can go to review`
- `Only an open or reviewing case can be escalated`
- `outcome must be one of: explained, confirmed_variance, false_alarm, inconclusive`
- `causeCode is required for an explained case`
- `causeCode must be one of: …`
- `Only a closed case can be reopened`

Ingest, whole batch, 400:

- `events is required`
- `events must hold between 1 and 500 events`
- `an event must not be null`
- `<field> must be an ISO date (YYYY-MM-DD) or an ISO-8601 instant` (list filters `from`, `to`)

Per event, in `reason`:

- `eventId must be a UUID`
- `source must be 1 to 40 characters: lowercase letters, digits, '-' or '_'`
- `sourceEventId is required and holds at most 200 characters`
- `Unknown event type '<type>'`
- `Unsupported schemaVersion <n> for type '<type>'`
- `occurredAt must be an ISO-8601 instant`
- `occurredAt is more than 24 hours in the future`
- `businessDate must be an ISO date (YYYY-MM-DD)`
- `<field> must hold 1 to 200 characters when given`
- `evidenceLevel must be one of: item, ticket, order, session, zone`
- `evidenceSource must be one of: pos, kds, stock, camera, sensor, manual`
- `observationStatus must be one of: observed, inferred, unavailable`
- `confidence must be between 0 and 1`
- `payload must be a JSON object`
- `payload is larger than 65536 bytes`
- `payload.stage must be one of: preparation_started, prepared, dispatched, delivered, remade, wasted, cancelled`
- `branchId is required`
- `Branch not found`
- `eventId is already used by another event`

## Projection from this ERP

`LpProjectionService` reads POS, cash, kitchen, beverage and stock tables and writes ledger events with source
`erp-pos`. It writes nothing else. Each source keeps a cursor and re-reads the last 5 minutes, so a row committed late
is not missed; a row read twice is one event.

| Source | Rows | Event | `sourceEventId` |
|---|---|---|---|
| `sales` | `Invoice` with a cash session, posted | `sale.completed` | `invoice:<id>` |
| `voids` | `OrderItem` cancelled or with `voidedQty` | `sale.line_voided` | `order-item-void:<id>:<qty>` |
| `cancellations` | `Order` cancelled | `order.cancelled` | `order-cancel:<id>` |
| `refunds` | `PosRefund` | `refund.issued` | `pos-refund:<id>` |
| `overrides` | `PosApprovalGrant` | `override.approved` | `pos-approval:<id>` |
| `cash_sessions` | `CashSession` | `cash_session.opened`, `.closed`, `.reconciled` | `cash-session:<id>:<what>[:<closedAt>]` |
| `kitchen` | `KitchenTicket` | `kitchen.ticket_created`, `kitchen.stage` | `kitchen-ticket:<id>:<stage>…` |
| `stock_counts` | Submitted `BottleCountSession` lines | `stock_count.line` | `bottle-count-line:<id>` |

- Fast path: `LpProjectionSubscribers` sweeps one source when POS, cash or kitchen announces a change. The handler
  runs from the outbox worker, after the announcing transaction.
- Kitchen stages are ticket-level and `inferred`. "Served" on the kitchen display is recorded as `dispatched`.
- A new organization is read back `backfillDays` (default 30).
- `cash.movement` and `staff.session` are in the contract but are not projected yet; no LP0 rule reads them.

Assumptions to check on real data:

- A POS sale is an `Invoice` with `cashSessionId` and `postedAt`.
- `Invoice.discountTotal` holds all discount given on the sale (read by C07).
- `OrderItem.kitchenPrintedQty` still shows what was fired after the item is voided (read by C01).
- Stock of a bottle-counted product is kept in millilitres (read by B03, which is off by default for this reason).
- `PosApprovalGrant` rows are still there when the sweep runs.

## Reconciliation

A window is one engine, one branch and one business day. An accepted event makes its window dirty.

| Setting | Default | Meaning |
|---|---|---|
| `cashierSettleMinutes` | 10 | A dirty cashier window runs this long after it first became dirty |
| `barSettleMinutes` | 0 | The same for the bar engine |
| `dailySettleMinutes` | 120 | A business day is final this long after it ends |
| `backfillDays` | 30 | How far back the first sweep reads |
| `retentionMonths` | 24 | Stored only; nothing deletes ledger rows yet |

- Rules that compare a whole day (`finalOnly`) run only once the day is final. Until then a run may find less.
- Each run stores the engine version, every rule version, the settings, the highest ledger `seq` it read, the event
  count and a hash of the events.
- A new run marks the previous findings of its window `superseded`. Nothing is deleted or overwritten.
- A finding's fingerprint (engine, rule, branch, day, key) is stable across runs, so it keeps its case.
- An event that arrives for a reconciled window makes it dirty again (`late_event`), and also the later windows that
  look back at that day.

## Rules

| Code | Finding | Parameters (default) | Runs |
|---|---|---|---|
| `C01` | Item voided after it was sent to the kitchen | — | Every run |
| `C02` | Order cancelled after items were sent to the kitchen | — | Every run |
| `C03` | Refund at or above the threshold | `amount` (not set) | Every run |
| `C04` | Refunds by one cashier well above the branch | `multiplier` 3, `minCount` 3 | Final day |
| `C05` | Overrides for one cashier well above the branch | `multiplier` 3, `minCount` 5 | Final day |
| `C06` | One manager approves most of a cashier's overrides | `sharePercent` 60, `minCount` 5, `days` 7 | Final day |
| `C07` | Discount rate of one cashier well above the branch | `multiplier` 2, `minSales` 10, `minRatePercent` 5 | Final day |
| `C08` | Cash session difference above tolerance | `amount` (not set) | Every run |
| `C09` | Repeated short cash sessions for one cashier | `sessions` 10, `minShort` 4 | Every run |
| `C10` | Kitchen ticket recalled or cancelled after it was ready | — | Every run |
| `B01` | Unexplained bottle variance above tolerance | `toleranceMl` 30 | Every run |
| `B02` | Same cause given for one product in consecutive counts | `counts` 4, `minRepeats` 3 | Every run |
| `B03` | Stock movements do not tie to system stock | `roundingMl` 1; **off by default** | Every run |

- A rule with a parameter that is not set does not run (`configured: false`): `C03` and `C08` need an amount in the
  organization's currency.
- "Well above the branch" means above `multiplier` × the median of the cashiers active that day.
- `B01` uses the line's own `toleranceMl` when the sender gives one. A cause other than `unexplained` or
  `unrecorded_sale` explains the line; `B02` then watches for the same cause being given repeatedly.
- `B03`, when on, holds back `B01` for a line whose movements do not tie.

## Cases

- One case per finding fingerprint, numbered `LP-000001` per organization.
- States: `open` → `reviewing` ⇄ `escalated` → `closed`.
- Outcomes: `explained` (cause required), `confirmed_variance`, `false_alarm`, `inconclusive`. None records misconduct.
- Cause codes: `spillage`, `breakage`, `complimentary`, `staff_drink`, `unrecorded_sale`, `bottle_transfer`,
  `measurement_error`, `unexplained`, `remake`, `waste`, `late_sync`, `training`.
- When a later run no longer finds the variance, the case gets a timeline entry. If nobody had started on it, it
  closes as `false_alarm` with `closedBy` empty; if the variance is found again, such a case reopens. A case a person
  closed is never changed by a run.
- The timeline (`LpCaseEvent`) is append-only and includes every view.
- Opening a case publishes `lp.case.opened`.

## Storage

`LpEvent`, `LpRuleVersion`, `LpReconRun` and `LpCaseEvent` are append-only: a trigger refuses any update. Deleting is
left possible for a retention job, which is not built.
`seq` is the ledger order, which is arrival order and not event-time order.

## Deviations

None from Nest (no counterpart). Differences from the LP0 brief as first written are listed in the brief, section 12.
