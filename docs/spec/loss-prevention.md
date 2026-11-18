# Loss prevention — `lossprevention`

| | |
|---|---|
| Phase | LP0 built; LP1 (camera platform), LP2 (cashier vision), LP3 (bar intelligence), LP4 (restaurant and cafe), LP5 (retail checkout and store room), LP6a (camera-free metrics), LP6b (camera metrics) and LP7 (activity research) coded, not yet built or tested by the owner |
| Nest source | none, new module |
| Design | [ADR-S077](../adr/ADR-S077-loss-prevention.md), [LP0 brief](../phases/LP0-exception-intelligence.md), [LP1 brief](../phases/LP1-camera-platform.md), [LP2 brief](../phases/LP2-cashier-vision.md), [LP3 brief](../phases/LP3-bar-intelligence.md), [LP4 brief](../phases/LP4-restaurant-cafe.md), [LP5 brief](../phases/LP5-retail-checkout.md), [LP6a brief](../phases/LP6a-camera-free-metrics.md), [LP6b brief](../phases/LP6b-camera-metrics.md), [LP7 brief](../phases/LP7-activity-research.md) |
| Routes | 83, under `/loss-prevention`: 27 from LP0, 38 from LP1, 5 from LP3, 7 from LP6a, 3 from LP6b, 3 from LP7 |
| Feature flag | `ENABLE_LOSS_PREVENTION` → `app.modules.lossprevention`; `VITE_ENABLE_LOSS_PREVENTION` shows the menu entry |
| Module name | `loss-prevention` (`@RequiresModule`); licence features `lossPrevention`, for cameras `lpVision`, for operations metrics `lpInsights` |
| Java package | `com.inventoryerp.lossprevention` |
| Migrations | `V560` ledger, `V561` rules, settings, windows, runs, findings, `V562` cases, `V572` camera platform (agents, cameras, zones, commands, heartbeats, evidence, access trail), `V573` the `cashier_vision` engine, `V574` operations metric summaries, `V575` pour meters and the `bar_pour`, `kitchen_flow`, `service_vision` engines, `V576` the `retail_vision` engine, `scan_area` and `queue` zones, the `table` link, activity reviews |
| Permissions | `lp:view`, `lp:investigate`, `lp:configure`, `lp:ingest`, `lp:evidence` |
| Screen | `/loss-prevention`: overview, cases, reports, reconciliation runs, rules; with `lpVision`, cameras (`lp:configure`) and evidence (`lp:evidence`); with `lpInsights`, insights (`lp:view`); pour meters (`lp:configure`); with `lpVision`, research (`lp:investigate`) |
| Schedule | `app.lossprevention.cron` (default every 2 minutes): sweep, then run due windows. `app.lossprevention.vision-cron` (every minute): silent agents go offline, commands lapse. `app.lossprevention.vision-housekeeping-cron` (03:17 daily): evidence retention, heartbeat history. `app.lossprevention.insights-cron` (every 5 minutes, at :40): compute dirty metric days |
| Contract | [`event-envelope.schema.json`](loss-prevention/event-envelope.schema.json), [`payloads.schema.json`](loss-prevention/payloads.schema.json) |

## Routes

`POST` answers 201, except `POST /runs/replay`, which answers 200 like every route behind an `Idempotency-Key`,
and the edge agent's own routes (LP1), which answer 200 except enrolment (201).

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
`kitchen.ticket_created`, `kitchen.stage`, `stock_count.line`, `staff.session`, and from LP5 `stock.movement`
(section "Retail checkout and store room (LP5)").

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
- `engine must be one of: cashier, bar, cashier_vision, bar_pour, kitchen_flow, service_vision, retail_vision` (the engines registered)
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
| `stock_movements` | `InventoryLedger` lines at a location linked to a live `storage` zone (LP5) | `stock.movement` | `inventory-ledger:<id>` |

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

## Vision security (LP1): camera platform

Licence feature `lpVision` (`@RequiresFeature`), on top of `lossPrevention`. Every route below is under
`/loss-prevention` and needs the module switched on. The backend never connects to a camera: an edge agent on the
customer's network reads the cameras, keeps the video, and sends observations and clips out over HTTPS.

Hierarchy: organization → branch → edge agent → camera → zone. An agent belongs to one branch and writes events for
that branch only.

### Administration routes

| Verb and path | Permission | Purpose |
|---|---|---|
| `GET /agents` | `lp:configure` | Agents with health; a plain array |
| `POST /agents` | `lp:configure` + `lp:ingest` | `{ branchId, name }`; answers the agent with `enrolmentCode` (shown once) and `enrolmentCodeExpiresAt`. The agent's credentials carry `lp:ingest`, so the caller must hold it |
| `GET /agents/{id}` | `lp:configure` | The agent with `cameras`, the last 60 `heartbeats` and its `timeline` |
| `PUT /agents/{id}` | `lp:configure` | `{ name, desiredModelVersion?, desiredAgentVersion? }` |
| `POST /agents/{id}/enrolment-code` | `lp:configure` + `lp:ingest` | A new one-time code (15 minutes); enrolling with it replaces the agent's credentials |
| `POST /agents/{id}/revoke` | `lp:configure` | `{ reason? }`; its credentials stop working at once |
| `GET /models` | `lp:configure` | Detector models this installation offers (`LP_VISION_MODELS`) and agent releases (`LP_EDGE_RELEASES`) |
| `GET /cameras` | `lp:configure` | `branchId`, `agentId` filters; a plain array |
| `POST /cameras` | `lp:configure` | `{ agentId, name, streamAlias, enabled?, analysed?, notes? }` |
| `GET /cameras/{id}` | `lp:configure` | The camera with its zones |
| `PUT /cameras/{id}` | `lp:configure` | `{ name, streamAlias, enabled?, analysed?, notes? }` |
| `POST /cameras/{id}/snapshot` | `lp:configure` + `lp:evidence` | Asks the agent for a still image; answers `{ commandId }` |
| `GET /cameras/{id}/snapshot` | `lp:configure` + `lp:evidence` | The latest still (`image/jpeg`, at most 10 minutes old, kept in memory only); `X-Snapshot-Taken-At` says when it arrived; 404 until the agent sent one |
| `POST /cameras/{id}/live` | `lp:evidence` | Starts live view: `{ sessionId, streamUrl, expiresAt, endsAt, maxFps }`; connect by `expiresAt` (5 minutes); the view ends at `endsAt` (30 minutes) |
| `DELETE /live/{sessionId}` | `lp:evidence` | Stops live view |
| `GET /live/{sessionId}/stream?token=` | signed link | `multipart/x-mixed-replace` JPEG stream; the token in `streamUrl` is the authorization (5 minutes to connect) |
| `GET /zones` | `lp:configure` | `cameraId`, `branchId`, `includeRetired` filters; a plain array |
| `GET /zones/{id}` | `lp:configure` | The zone with `effectiveParams` (its `params` over the type's defaults) |
| `POST /zones` | `lp:configure` | `{ cameraId, name, zoneType, polygon, inSide?, linkedType?, linkedId?, params?, enabled? }` |
| `PUT /zones/{id}` | `lp:configure` | `{ name, polygon, inSide?, linkedType?, linkedId?, params?, enabled? }`; the type does not change |
| `DELETE /zones/{id}` | `lp:configure` | Retires the zone; its past observations keep pointing at it |
| `POST /zones/{id}/calibrate` | `lp:configure` | A `cash_drawer` zone only: asks the agent to take the current picture as the zone's resting state (a closed drawer); `{ commandId }` |

A camera record holds a display name and a **stream alias**. The stream address and its password are entered on the
edge box and never reach the backend: an alias with `://`, `@`, `:` or `/` is refused. An alias is unique per
agent (409).

Every change to a camera or zone raises the agent's `configVersion` and queues a `config_changed` command.

### Zones

A zone is a polygon on the camera picture with one purpose. Points are `[x, y]`, each from 0 to 1 (x to the right, y
down). A **counting line** is a zone of exactly 2 points, A then B; `inSide` (`left` or `right` of A → B) is the side
an object enters when it crosses "in".

| `zoneType` | Shape | `linkedType` | Feeds |
|---|---|---|---|
| `cash_drawer` | polygon over the drawer | `till` (required) | V-C1 |
| `checkout` | polygon over the customer side of the till | `till` (required) | V-C2, V-C3 |
| `bar_counter` | polygon | `station` or `location` | LP3 |
| `kitchen_pass` | line or polygon | `station` | LP4 |
| `cafe_counter` | line or polygon | `counter` or `till` | LP4 |
| `dining` | polygon | `table_area` or `table` | LP4, LP6b table occupancy |
| `storage` | line or polygon | `location` | LP5 V-S1 |
| `entrance` | line | none (the branch) | LP6b footfall |
| `scan_area` | polygon over the scanner, where an item is held to be read | `till` (required) | LP5 V-R1, V-R2 |
| `queue` | polygon over the waiting line | `till` | LP6b queues |

`till` is a `CashRegister` id of the organization (and of the camera's branch when the register has one); `location`
is an `InventoryLocation` id; `table` (LP6b) is a `PosTable` id of the organization (`Table not found`). Other
links are free text up to 200 characters.

`params` is a JSON object (at most 4096 bytes) merged over the defaults of the zone type; the agent reads them:

| `zoneType` | Default `params` |
|---|---|
| `cash_drawer` | `method` `reference_diff`, `openThreshold` 0.18, `minOpenSeconds` 1, `minClosedSeconds` 2, `preRollSeconds` 20, `postRollSeconds` 30 |
| `checkout` | `presenceMethod` `detector`, `minPresenceSeconds` 3, `clipMinDwellSeconds` 90, `occupancyEverySeconds` 60, `preRollSeconds` 10, `postRollSeconds` 10 |
| `entrance` | `classes` `["person"]` |
| `kitchen_pass` | `classes` `["plate"]`, `preRollSeconds` 20, `postRollSeconds` 30 |
| `cafe_counter` | `classes` `["cup"]`, `preRollSeconds` 20, `postRollSeconds` 30 |
| `bar_counter` | `objects` `["bottle"]`, `preRollSeconds` 20, `postRollSeconds` 30 |
| `dining` | `classes` `["plate", "person"]`, `occupancyEverySeconds` 300 |
| `storage` | `classes` `["person", "bag"]`, `preRollSeconds` 20, `postRollSeconds` 30 |
| `scan_area` | `classes` `["item"]`, `flowDirection` `left_to_right`, `laneMargin` 0.15, `preRollSeconds` 10, `postRollSeconds` 10 |
| `queue` | `classes` `["person"]`, `minWaitSeconds` 5, `occupancyEverySeconds` 60 |

Every type also has `minConfidence` 0.5. `presenceMethod` `motion` counts presence from movement inside the polygon
when no person detector is installed. `flowDirection` is the way items travel on the picture from the unscanned side
to the bagging side: `left_to_right`, `right_to_left`, `top_to_bottom` or `bottom_to_top`; `laneMargin` is from 0 to
1. Any zone may also carry `activityResearch` (true or false, default false) and `activityRecognizers` (names): they
are passed to the agent unchanged (LP7).

### Agent protocol

The agent only makes outbound HTTPS calls. Its credentials are an API client bound to its agent row: scope
`lp:ingest`, one branch. Every route below except enrolment needs that token: a user, or another API client, gets
403 `Only an enrolled edge agent can call this route`.

1. **Enrol.** `POST /agents/enrol` (public, at most 10 calls a minute per address) with
   `{ code, hostname?, agentVersion? }` answers 201
   `{ agentId, organizationId, branchId, clientId, clientSecret, tokenUrl, apiBase, scope, heartbeatSeconds }`;
   `tokenUrl` and `apiBase` are paths on the server the agent called (`/api/v1/oauth/token`, `/api/v1`). The code
   works once and for 15 minutes; case, spaces and dashes do not matter. A wrong, used or expired code answers 400
   `Enrolment code is invalid or has expired`. Enrolling again with a new code replaces the secret; the old one
   stops working. Enrolment is public, so the organization's module switch is not checked there: with the module
   off, every other route refuses the credentials it hands out.
2. **Token.** `POST /oauth/token` (`grant_type=client_credentials`, scope `lp:ingest`): a bearer token for one hour.
3. **Configuration.** `GET /agents/config`:

   ```json
   { "agentId": "…", "branchId": "…", "configVersion": 4, "heartbeatSeconds": 60, "commandWaitSeconds": 25,
     "clock": { "maxDriftMs": 2000 },
     "evidence": { "bufferDays": 7, "maxClipSeconds": 600, "maxClipBytes": 67108864, "chunkBytes": 1048576 },
     "model": null, "release": null,
     "cameras": [ { "id": "…", "name": "Till 1", "streamAlias": "till-1", "enabled": true, "analysed": true,
       "zones": [ { "id": "…", "name": "Drawer", "zoneType": "cash_drawer", "polygon": [[0.4, 0.6], [0.6, 0.6],
         [0.6, 0.8], [0.4, 0.8]], "inSide": null, "linkedType": "till", "linkedId": "…",
         "params": { "method": "reference_diff", "openThreshold": 0.18 } } ] } ] }
   ```

   `model` is `{ version, detector, url, sha256, signature, inputSize, classes }` or `null` (no detector installed:
   only `reference_diff` and `motion` work). `release` is `{ version, image, digest, signature }` or `null`. The
   agent checks both signatures (Ed25519, vendor key) before using either. Signed text: model
   `version|sha256|url`, release `version|image|digest`.
4. **Heartbeat** every `heartbeatSeconds`: `POST /agents/heartbeat`

   ```json
   { "agentVersion": "0.1.0", "modelVersion": null, "configVersion": 4, "clockDriftMs": 12,
     "diskFreeBytes": 52000000000, "diskTotalBytes": 120000000000, "queueLength": 0,
     "observedThrough": "2026-11-17T10:00:00Z",
     "cameras": [ { "cameraId": "…", "streamState": "ok", "fps": 5.0, "lastFrameAt": "2026-11-17T10:00:01Z",
       "observedThrough": "2026-11-17T10:00:00Z" } ] }
   ```

   answers `{ serverTime, health, healthReasons, configVersion, unknownCameras, commands }` (`commands` as in step
   5; `unknownCameras` lists camera ids that are not the agent's). `observedThrough` is the agent's watermark:
   every observation up to that instant has been sent; a camera's own `observedThrough` is that camera's, and the
   vision rules use it when it is there. A watermark never moves back, and one in the future counts as the
   server's now. `streamState` is `ok`, `connecting`, `lost` or `disabled`; `fps` 0 to 1000.
5. **Commands.** `GET /agents/commands?waitSeconds=25` waits up to 25 seconds and answers `{ commands: [ … ] }` as
   soon as one is queued. A command is `{ id, kind, payload, createdAt, expiresAt }`; the agent reports
   `POST /agents/commands/{id}/result` `{ status: done | failed, message?, code? }`, answered
   `{ id, state, firstReport }`. A command delivered and not reported is delivered again after 2 minutes; the agent
   ignores an id it has done. A failed `clip_upload` with `code` `not_found` makes the clip `missing`; any other
   failed upload puts it back `on_agent`, to be asked for again. A failed `live_start` ends the live view.

   | `kind` | `payload` | Agent does |
   |---|---|---|
   | `config_changed` | `{ configVersion }` | Reads the configuration again |
   | `snapshot` | `{ cameraId }` | `POST /agents/snapshots/{commandId}`, body `image/jpeg` (at most 1.5 MB) |
   | `calibrate` | `{ cameraId, zoneId }` | Takes the current picture as the zone's resting state |
   | `clip_upload` | `{ evidenceId, cameraId, clipId, from, to }` | Uploads the clip `clipId`, or cuts `from`–`to` from its buffer when `clipId` is null |
   | `live_start` | `{ sessionId, cameraId, fps, maxWidth, expiresAt }` | Pushes frames to `POST /agents/live/{sessionId}/frames` until told to stop |
   | `live_stop` | `{ sessionId }` | Stops pushing |

6. **Observations.** `POST /events`, the ordinary ingest, with the vision types below. `branchId` must be the
   agent's; `deviceId` must be a zone of one of its cameras.
7. **Clips.** When the agent keeps a clip for an observation it registers it at once:
   `POST /agents/clips` `{ clips: [ { clipId, cameraId, zoneId?, startedAt, endedAt, sha256, byteSize, contentType,
   eventIds? } ] }` (at most 100; `eventIds` are the envelope `eventId`s of the observations it belongs to), answered
   `{ registered, results: [ { clipId, status, evidenceId?, linkedEvents?, reason? } ] }`. Each clip is
   `registered`, `duplicate` (sent before: the same `evidenceId`) or `rejected` with a reason, such as
   `Camera not found for this agent`. The clip stays on the edge box until it is requested.
8. **Upload** (only when requested, resumable, chunks of at most 1 MiB, a clip of at most 64 MiB):
   `GET /evidence/{id}/upload` answers `{ evidenceId, receivedBytes, totalBytes, complete, state }`;
   `POST /evidence/{id}/upload?offset=&total=&sha256=` with the chunk as the body (`application/octet-stream`; the
   body is read as bytes whatever its type) appends it at `offset` and answers the same status. When the last byte
   arrives the backend checks the SHA-256 against the one registered at capture, encrypts the clip and stores it
   in the ERP's file storage. A clip cut from the buffer (`clipId` null) registers its SHA-256 and size with its
   first chunk, and its type with `&contentType=` (default `video/mp4`). A wrong `offset` answers 409
   `Expected offset <receivedBytes>`; a checksum or size other than the registered one answers 409
   `Checksum does not match the clip registered at capture` and the clip becomes `integrity_failed`; a clip nobody
   asked for answers 409 `The clip was not requested`. Sending a chunk of a finished upload again changes nothing.
9. **Live view.** `POST /agents/live/{sessionId}/frames`, body `image/jpeg` (at most 1.5 MB, 4 frames a second;
   faster frames are dropped), answers `{ continue }`; `false` means stop. The backend keeps only the latest frame
   in memory and never stores video.

Live views and stills live in the memory of the API instance that started them. Behind several API instances,
send `/loss-prevention/cameras/*/snapshot`, `/cameras/*/live`, `/live/*`, `/agents/snapshots/*` and
`/agents/live/*` to one instance (sticky routing), or run one; LP1 does not share them between instances.

### Vision event types

All carry `evidenceSource` `camera` (or `sensor`), `evidenceLevel` `zone`, the zone id as `deviceId`, a
`confidence`, and `payload.cameraId`. A clip, when kept, is `payload.clipId`.

| Type | Sent when | Required in `payload` |
|---|---|---|
| `vision.line_crossed` | A tracked object crosses a counting line | `class`, `direction` (`in`, `out`), `count` (1 or more) |
| `vision.dwell` | A presence in a zone ends | `class`, `startedAt`, `endedAt`; also `durationSeconds`, `maxCount` |
| `vision.state_changed` | A fixed object changes state (a drawer opens) | `object`, `to`; also `from`, `secondsInPreviousState` |
| `vision.handling` | A hand takes or returns an object; on a `scan_area`, an item carried past the scanner (`action` `bypassed`) | `object`, `action` |
| `vision.occupancy` | Periodic count in a zone | `count` (0 or more); also `class`, `periodSeconds` |
| `vision.gap` | A camera had no usable picture | `from`, `to`, `reason` (`stream_lost`, `agent_down`, `clock_drift`, `disabled`); `observationStatus` `unavailable` |
| `vision.activity` | (LP7, research) A recognizer saw an activity in a zone | `activity`, `startedAt`, `endedAt`, `recognizer`; also `score` (0 to 1), `trackIds` |

Besides the payload checks, an observation is refused (that event only, in the batch answer) when the zone is
unknown or not at the event's branch (`Zone not found`), when an edge agent sends it for a zone of another agent
(`Zone not found for this agent`), or when `payload.cameraId` is not the zone's camera
(`payload.cameraId is not the camera of the zone`). An edge agent's events of any type must carry its own branch
(`An edge agent sends events for its own branch only`).

### Evidence

| Verb and path | Permission | Purpose |
|---|---|---|
| `GET /evidence` | `lp:evidence` | `caseId`, `cameraId`, `zoneId`, `state`, `from`, `to`, `page`, `pageSize`; page envelope |
| `GET /evidence/{id}` | `lp:evidence` | Metadata, linked cases, observations and the access trail |
| `GET /evidence/{id}/content?download=` | `lp:evidence` | The clip, decrypted and checked against its SHA-256; recorded as `view` or `download` |
| `GET /evidence/{id}/export` | `lp:evidence` | A ZIP with the clip, `manifest.json` (metadata, SHA-256, cases) and the access trail; recorded |
| `POST /evidence/{id}/request` | `lp:evidence` | `{ caseId?, reason? }`: ask the agent to upload the clip |
| `POST /evidence/range` | `lp:evidence` | `{ cameraId, from, to, caseId?, reason? }`: a clip cut from the agent's buffer, at most 10 minutes |

States: `on_agent` (registered, still on the edge box), `requested`, `uploading`, `uploaded`, `missing` (the agent no
longer has it), `integrity_failed` (the upload did not match its SHA-256), `deleted` (retention).

`from` and `to` of the list take a date (the whole day, in the organization's zone) or an ISO-8601 instant. Reading
a clip answers 409 `The clip is not uploaded yet` before upload, 409 `The edge agent no longer has this clip` when
it is `missing`, 409 `The clip does not match its checksum; it may have been altered` when the check fails, and 410
`The clip was deleted under the retention rules` after retention. Asking for a clip whose agent is revoked answers
409 `The edge agent of this camera is not enrolled or was revoked`.

- A clip is uploaded only when a case needs it or an investigator asks. When a case opens from a vision finding,
  the clips of its observations, or a clip of the till around the POS event, are requested and linked to the case.
- Access: `lp:evidence`, the caller's branches, and never by the subject of a linked case (404). Every view,
  download, export, request, snapshot and live view is written to `LpEvidenceAccess`, which is append-only.
- Integrity: the SHA-256 taken at capture is checked at upload and again at every view, download and export. A
  mismatch answers 409 and is recorded.
- Storage: AES-256-GCM before it reaches the file storage, key from `LP_EVIDENCE_KEY` (else `ENCRYPTION_KEY`, else
  the JWT access secret).
- Retention, per organization with a branch override (settings below): the edge box keeps unflagged video and
  clips not uploaded for `evidenceBufferDays`; an uploaded clip linked to an open case is kept `evidenceCaseDays`
  after upload; any other uploaded clip `evidenceClosedCaseDays` after its last case closed, or after upload when it
  has no case. The vision job deletes expired clips and records each deletion.

### Health

A heartbeat sets the agent `online`, or `degraded` with reasons: `stream_lost` (an analysed camera is not `ok`),
`disk_low` (less than 10 % or 5 GB free), `clock_drift` (more than `maxClockDriftMs`), `queue_backlog` (more than
1000 unsent observations). No heartbeat for `agentSilentMinutes` makes it `offline`. Each change is written to the
agent's timeline and published as `lp.agent.health_changed`. The hourly alert type `lp_agent_health` (alerts
module) raises one alert per agent that has been `offline` (critical) or `degraded` (warning) for at least the
type's threshold in hours (default 0), linked to `/loss-prevention`.

The agent stops sending observations while its clock is off by more than `maxClockDriftMs` and reports the period as
`vision.gap` (`clock_drift`). A vision rule never finds anything in a gap, nor after the agent's `observedThrough`.

### Settings added

| Setting | Default | Meaning |
|---|---|---|
| `visionSettleMinutes` | 15 | A dirty vision window runs this long after it first became dirty |
| `agentSilentMinutes` | 5 | No heartbeat for this long makes an agent offline |
| `maxClockDriftMs` | 2000 | Above this the agent stops sending observations |
| `evidenceBufferDays` | 7 | Kept on the edge box: video and clips not uploaded |
| `evidenceCaseDays` | 365 | Uploaded clip of an open case |
| `evidenceClosedCaseDays` | 90 | Uploaded clip after its case closed, or with no case |
| `heartbeatRetentionDays` | 14 | Heartbeat history kept for the agent screen |

### Configuration (LP1)

| Variable | Default | Meaning |
|---|---|---|
| `LP_EVIDENCE_KEY` | empty | Secret the evidence key is derived from (scrypt). Empty: `ENCRYPTION_KEY`, else the JWT access secret. A clip is read only with the key it was stored with |
| `LP_VISION_MODELS` | `[]` | JSON array of `{ version, detector?, url (https), sha256, signature, inputSize?, classes? }`; an incomplete entry is logged and ignored |
| `LP_EDGE_RELEASES` | `[]` | JSON array of `{ version, image, digest (sha256:…), signature }` |
| `LP_VISION_DEFAULT_MODEL` | empty | The model an agent gets unless it is set to another |
| `LP_VISION_CRON` | `20 * * * * *` | The minute job above |
| `LP_VISION_HOUSEKEEPING_CRON` | `0 17 3 * * *` | The nightly job above |

No detector model ships with LP1: until one is configured, an agent runs only the methods that need none (the
drawer reference picture and motion). Detection accuracy has not been measured on real footage.

## Cashier vision (LP2)

Engine `cashier_vision`. It runs for a branch only when the branch has an active `cash_drawer` or `checkout` zone.
It reads drawer and presence observations, the POS events of the same till, and the cash sessions that say who was
on the till.

The till of a POS event is `payload.tillId`; else the till of its cash session (`cash_session.opened` carries
`payload.tillId`); else `deviceId`. This ERP's projection writes the `CashRegister` id as `tillId`.

| Code | Finding | Parameters (default) | Severity |
|---|---|---|---|
| `V-C1` | Drawer opened with no sale, refund or cash movement near it | `windowBeforeSeconds` 60, `windowAfterSeconds` 120, `minConfidence` 0.6 | warning |
| `V-C2` | Refund or void with no customer at the till | `windowBeforeSeconds` 120, `windowAfterSeconds` 60, `minPresenceSeconds` 5, `minConfidence` 0.5 | warning |
| `V-C3` | Customer served with no sale recorded | `minDwellSeconds` 90, `windowBeforeSeconds` 0, `windowAfterSeconds` 300, `minConfidence` 0.6 | info |
| `C11` | No-sale drawer opens by one cashier well above the branch | `multiplier` 3, `minCount` 5 (cashier engine, final day) | warning |

- V-C1: a drawer opening is explained by a `sale.completed`, `refund.issued`, `cash.movement`, `cash_drawer.opened`
  or a cash session opening or closing at the same till within the window.
- V-C2: needs a `checkout` zone on the till. The refund or void is skipped, never found, when the window is not fully
  observed: inside a `vision.gap` of that zone, or after the agent's `observedThrough`.
- V-C3: counted only while a cash session is open on the till, when the till has sessions in the ledger.
- The subject of V-C1 and V-C3 is the cashier of the session open on the till; of V-C2, the person who refunded or
  voided. Staff are identified through their POS login only.
- A run whose window has a gap is stored with `observationGap` true.

New POS type `cash_drawer.opened` (level `session`): `payload.reason` is `sale`, `refund`, `cash_movement`,
`no_sale`, `float` or `other`, and `payload.tillId`. This ERP's POS records no drawer openings, so its projection
sends none; C11 and the POS side of V-C1 use it when another POS sends it.

The projection adds `payload.tillId` to sales, voids, cancellations, refunds and cash sessions, and projects cash
pay-ins, pay-outs, supplier payments and adjustments as `cash.movement` (source `cash_movements`).

## Bar intelligence (LP3)

Brief: [LP3](../phases/LP3-bar-intelligence.md). Nothing here is measured on a real bar; meter readings and camera
observations are trusted as sent.

### Pour meters

Routes, all `lp:configure`, module `loss-prevention` only (meters are not cameras, so no `lpVision`):

| Verb and path | Purpose |
|---|---|
| `GET /measurement-devices?branchId&includeRetired` | Plain array; each with `branchName`, `agentName`, `productName`, `lastReadingAt`, `readings7d` |
| `POST /measurement-devices` | `{ branchId, name, kind, externalId, agentId?, vendor?, productId?, station?, standardPourMl?, enabled? }`; 201 |
| `GET /measurement-devices/{id}` | One device |
| `PUT /measurement-devices/{id}` | `{ name, agentId?, vendor?, productId?, station?, standardPourMl?, enabled? }`; branch, kind and `externalId` are fixed |
| `POST /measurement-devices/{id}/retire` | 201; retired, never deleted |

`kind` is `pour_spout`, `flow_meter` or `dispenser`. A change to a device read by an agent raises that agent's
`configVersion` (the old and the new agent); `GET /agents/config` lists the agent's devices not retired under
`measurementDevices` (`id`, `kind`, `vendor`, `externalId`, `productId`, `station`, `standardPourMl`, `enabled`).

Messages: 404 `Measurement device not found`; 400 `kind must be one of: pour_spout, flow_meter, dispenser`,
`Branch not found`, `Agent not found`, `A revoked agent cannot read measurement devices`, `The agent is at another
branch`, `Product not found`, `standardPourMl must be above 0 and at most 5000`, `A retired measurement device cannot be
changed`, `The measurement device is already retired`, `externalId must be 1 to 200 characters`; 409 `Another
measurement device at this branch already uses this externalId`.

### Event types

| Type | Level, source, status | `deviceId` | Payload |
|---|---|---|---|
| `pour.measured` | `item`, `sensor`, `observed` | The device id | `volumeMl` (above 0, at most 5000); optional `productId`, `startedAt`, `endedAt`, `vendorEventId` |
| `measurement.gap` | `item`, `sensor`, `unavailable` | The device id | `from`, `to`, `reason`: `device_offline`, `adapter_down`, `clock_drift`, `disabled` |

Messages: `evidenceLevel must be item for type '<type>'`, `evidenceSource must be sensor for type '<type>'`,
`deviceId must name the measurement device for type '<type>'`, `observationStatus must be observed for type
'pour.measured'`, `observationStatus must be unavailable for type 'measurement.gap'`, `payload.volumeMl must be a
number above 0 and at most 5000`, `payload.<field> must hold 1 to 200 characters when given`. At ingest:
`Measurement device not found` (unknown, or at another branch), `Measurement device not found for this agent` (an edge
agent sending for a device it does not read), `The measurement device was retired before this event`.

The edge agent reads meters with adapters configured on the box (`LP_MEASUREMENT_ADAPTERS`): see `edge/README.md`.

### Engine `bar_pour`

A calendar day of the branch, like the vision engines; it runs only where the branch has a `bar_counter` zone or a
measurement device, settles with `visionSettleMinutes`, and records the branch's devices in the run's context.

| Code | Finding | Parameters (default) | Severity, runs |
|---|---|---|---|
| `B04` | More volume poured of a product than its sales drew | `tolerancePercent` 5, `minExcessMl` 100 | warning, final day |
| `B05` | Pours well above the standard measure at one device | `tolerancePercent` 15, `minCount` 5, `minSharePercent` 20 | info, final day |
| `V-B1` | Bottle poured at the bar counter with no drink sale near it | `windowBeforeSeconds` 120, `windowAfterSeconds` 600, `minConfidence` 0.6, `minTiltSeconds` 1 | info, every run |
| `V-B2` | Pour seen by the camera with no meter reading | `windowSeconds` 30, `minConfidence` 0.6, `minTiltSeconds` 1 | warning, every run |

- The product of a reading is `payload.productId`, else the device's. Sold volume is Σ `lines[].ingredients[].quantity`
  of the product on the day's sales. B04 does not run on a day with a sale line without `ingredients`.
- V-B1 and V-B2 read `vision.handling` with `action` `tilted` on a `bar_counter` zone. A drink sale has a line that drew
  an ingredient; a sale without the recipe data counts as one.
- V-B2 needs the zone linked to a `station` and a live meter with the same station (compared without case). It is
  skipped, never found, inside a `measurement.gap` of one of those meters or past the watermark of the agent that
  reads one; a meter posted by an outside system has no watermark.

The projection now writes `ingredients` (`productId`, `quantity` in the base unit, `totalValue`) on every
`sale.completed` line, from the recipe snapshot of the sale (`InvoiceItemRecipeIngredient`).

## Restaurant and cafe (LP4)

Brief: [LP4](../phases/LP4-restaurant-cafe.md).

| Code | Engine | Finding | Parameters (default) | Severity, runs |
|---|---|---|---|---|
| `K01` | `kitchen_flow` | Food served from the kitchen with no sale recorded | — | warning, final day |
| `K02` | `kitchen_flow` | Remakes at one station above the threshold | `maxRatePercent` 10, `minCount` 3 | info, final day |
| `V-K1` | `service_vision` | More plates left the kitchen pass than the tickets account for | `bucketMinutes` 60, `slackMinutes` 10, `tolerancePercent` 25, `minExcess` 3, `minConfidence` 0.5 | warning, every run |
| `V-F1` | `service_vision` | More items handed over at the cafe counter than were sold | `bucketMinutes` 60, `slackMinutes` 5, `tolerancePercent` 20, `minExcess` 3, `minConfidence` 0.5 | warning, every run |

- `kitchen_flow` is a business-day engine with no camera; it settles with `cashierSettleMinutes`. It reads the next
  business day's sales, cancellations and stages too (`lookaheadDays`): such an event makes the earlier reconciled
  window dirty again, so a tab paid after midnight withdraws its K01 finding.
- K01: a ticket with a `dispatched` or `delivered` stage that day and no `cancelled` stage, whose `orderId` or
  `transactionId` has no `sale.completed` (that day, the day before, or the next) and whose order was not cancelled.
- K02: per station, tickets with a `remade` stage ÷ tickets created that day.
- `service_vision` is a calendar-day engine; it runs only where the branch has a `kitchen_pass` or `cafe_counter`
  zone, and settles with `visionSettleMinutes`. It counts `vision.line_crossed` with direction `out` of any class but
  `person` (draw the line with `inSide` on the kitchen or staff side), per period of `bucketMinutes`.
- V-K1 compares with Σ `itemCount` of the tickets of the zone's station (all, when the zone has no station link)
  first `prepared` or `dispatched` in the period widened by `slackMinutes`. V-F1 compares with Σ line quantities of
  the sales at the zone's till (a zone linked to a `counter`, or not linked: all sales of the branch); its exposure
  is the excess × the average price of the items sold in the period.
- A period is skipped, never found, when a ticket in it has no `itemCount`, when the zone has a `vision.gap` in it,
  or when it ends after the camera's watermark; the run is then marked `observationGap`.

The projection now writes `itemCount` (quantities on the ticket not cancelled) on `kitchen.ticket_created` and every
`kitchen.stage`, and `orderType` on `kitchen.ticket_created`. Tickets projected before LP4 carry none.

## Operations insights, camera-free (LP6a)

Licence feature `lpInsights` (`@RequiresFeature`), on top of `lossPrevention`. Eight metrics computed from the
ledger; none needs a camera. Brief: [LP6a](../phases/LP6a-camera-free-metrics.md).

### How the summaries are kept

- Every accepted event of a metric type (`sale.completed`, `sale.line_voided`, `refund.issued`,
  `cash_session.closed`, `kitchen.ticket_created`, `kitchen.stage`, `stock_count.line`) marks its branch and
  business day dirty in `LpMetricDay`; a `kitchen.stage` also marks the day before, for tickets created before
  midnight. A late event makes a computed day dirty again.
- The insights job (`LpInsightsJob`, ShedLock `loss-prevention-insights`) computes days that have been dirty for at
  least 60 seconds, oldest first, at most 200 per organization per run. One day = one transaction: its rows in
  `LpMetricValue` are deleted and written again. A day marked again while it was computed stays dirty (`version`).
  A failing day keeps its error in `lastError` and is tried again on the next run.
- V574 marks every day already in the ledger dirty, so existing data is computed by the first runs.
- `LpMetricValue`: per branch, day, hour (0–23 in the business time zone, `-1` the whole day), metric, dimension
  (`''`, `actor`, `station`, `table`, `product`, `session`) and key: a `count`, a `total`, and for durations `p50`
  and `p90` of that day and hour. Hourly rows exist for the branch total and per actor or station.
- Payload numbers are read with `lp_num`, which treats an unreadable value as missing instead of failing the day.

### Metrics

| Metric | How it is calculated | Stored as |
|---|---|---|
| Kitchen preparation time | First `kitchen.stage` `prepared` − `kitchen.ticket_created`, per ticket created that day; per station and hour. A negative time (sender clock) is left out | `kitchen_prep` |
| Order-to-serve time | First `dispatched` − created, same rules | `kitchen_serve` |
| Recall rate | Tickets with a `remade` stage ÷ tickets created | `kitchen_recalls`, `kitchen_tickets` |
| Table turnover | Sales with `payload.tableId` ÷ table-days (distinct tables with a sale, per day). The service period is the business day | `table_orders`, `tables_used`, `sale_table_recorded` (coverage: not historical) |
| Average sale, items per sale | Amount charged ÷ sales; Σ line quantities ÷ sales. A sale sent again (same `transactionId`) counts once, as its latest copy | `sales`, `sale_items` |
| Void, refund, discount rates | Voided amount ÷ amount sold (a line counts once, at its largest quantity); refunded amount ÷ amount sold; discount ÷ (amount sold + discount). Also voids and refunds per 100 sales | `voids`, `refunds`, `sale_discount` |
| Cash session accuracy | Sessions whose last closing difference is within rule C08's `amount` (whether or not C08 is on) ÷ sessions; without that amount, sessions balanced to zero ÷ sessions | `cash_session` per session |
| Pour yield | Σ `soldMl` ÷ Σ consumed, consumed = `soldMl − varianceMl` (variance is counted − system), over count lines that carry both; a line sent again replaces the earlier copy. Cost of consumed = consumed × `costPerMl` where sent | `pour_sold_ml`, `pour_consumed_ml`, `pour_consumed_cost` |

A ratio with a zero denominator is `null`. Periods sum counts and totals; percentiles are not summed: the period
shows the slowest day's 90th percentile.

### Routes

| Route | Permission | |
|---|---|---|
| `GET /insights/summary?from&to&branchId` | `lp:view` | The eight metrics, the same for the period before, a trend per day, `status` (`pendingDays`, `failedDays`, `lastComputedAt`), `coverage` (per area `periodDays`, `daysWithData`, `historical`; table turnover also `sales`, `salesRecordingTable`, `recordedShare`, `firstRecordedDay`, `partial`), `definitions`, `note`. Default: last 30 days |
| `GET /insights/hourly` | `lp:view` | 24 rows: sales, average sale, voids, refunds, kitchen tickets, average preparation and order-to-serve |
| `GET /insights/kitchen` | `lp:view` | Per station |
| `GET /insights/staff?groupBy=role\|person` | `lp:view`; `person` also `lp:investigate` | Sales, average sale, items per sale, void, refund and discount rates. Role: a user's first role name (alphabetical); an actor that is not a user has none. `person` without `lp:investigate`: 403 `Figures per person need the lp:investigate permission`. Other values: 400 `groupBy must be one of: role, person` |
| `GET /insights/tables` | `lp:view` | Per table (`PosTable` name and number when it is one of this ERP's) |
| `GET /insights/pour` | `lp:view` | Per product, lowest yield first |
| `POST /insights/recompute` `{ from, to, branchId? }` | `lp:configure` | 200 `{ from, to, days }`: marks the days with metric events dirty. At most 92 days: 400 `A recompute covers at most 92 days`; 400 `from and to must be ISO dates (YYYY-MM-DD)`, `to must not be before from` |

The projection now also writes `payload.tableId` on `sale.completed` (the order's table). Sales projected before
this change carry none and are not in table turnover.

## Retail checkout and store room (LP5)

Brief: [LP5](../phases/LP5-retail-checkout.md). The zones need `lpVision`; nothing new is licensed. Nothing here has
been measured at a real checkout or store room.

| Code | Engine | Finding | Parameters (default) | Severity, default |
|---|---|---|---|---|
| `V-R1` | `retail_vision` | More items passed the scanner than the sale records | `maxBatchMinutes` 20, `tolerancePercent` 10, `minExcess` 2, `minConfidence` 0.5 | warning, **off** |
| `V-R2` | `retail_vision` | Items carried past the scanner that the sale does not explain | `maxBatchMinutes` 20, `minCount` 1, `minConfidence` 0.5 | warning, **off** |
| `V-S1` | `retail_vision` | Goods carried out of a store room with no stock movement recorded | `bucketMinutes` 60, `slackMinutes` 30, `minCount` 2, `minConfidence` 0.5 | warning, on |

- `retail_vision` is a calendar-day engine; it runs only where the branch has a `scan_area` or `storage` zone,
  settles with `visionSettleMinutes`, and reads events up to 30 minutes either side of the day.
- V-R1 and V-R2 ship **off** ("behind a flag"): an organization turns them on with `PUT /rules/{code}` once a pilot
  has measured the count error per transaction batch (target: at most 5 %).

**Batches** (V-R1, V-R2). Per `scan_area` zone, the `sale.completed` and `order.cancelled` events at the zone's till
(the event's `payload.tillId`, else its `deviceId`) close batches. A batch is the zone's observations after the
previous closing event at that till and at or before this one, and not more than `maxBatchMinutes` (1 to 30) before
it. Only batches closed by a sale in the day are judged. A batch is skipped, never found, when the sale has no
`lines`, when a `refund.issued` at the till falls in it, when the zone has a `vision.gap` overlapping it, or when the
sale is after the camera's watermark (the run is then marked `observationGap`).

- Items on the sale: per line, a whole quantity counts as that many items, a fractional quantity (a weighed item) as
  1, zero or less as 0. Voided items, counted the same way over the `sale.line_voided` events of the sale's order (a
  line voided in steps counts once, at its largest quantity), were passed over the scanner too. Expected = items on
  the sale + voided items.
- **V-R1.** Passed = Σ `count` of `vision.line_crossed` with direction `out` − Σ with direction `in`, of any class
  but `person`, with confidence ≥ `minConfidence`; never below 0. A finding when passed − expected ≥ `minExcess` and
  passed > expected × (1 + `tolerancePercent`/100).
- **V-R2.** Bypassed = the `vision.handling` events with action `bypassed` (any object but `person`, confidence ≥
  `minConfidence`), one item each. Explained = max(0, expected − passed): items on the sale the camera did not see
  pass the scanner (a heavy item keyed in by hand). A finding when bypassed − explained ≥ `minCount`.
- Subject: the sale's cashier (`actorId`, subject type `actor`), else the till. Unit `count`. Exposure, an estimate:
  the excess × the sale's average item price (sale total ÷ items on the sale). Key: zone and transaction.

**V-S1.** Per `storage` zone linked to a `location` (other storage zones are skipped) and period of `bucketMinutes`
while the zone was live: carried = Σ `count` of `vision.line_crossed` with direction `out` of any class but `person`,
confidence ≥ `minConfidence` (draw a line with `inSide` on the store-room side; on a polygon, "out" is leaving it).
A finding when carried ≥ `minCount` and no `stock.movement` with direction `out` at the zone's location falls in the
period widened by `slackMinutes` (0 to 30) either side. Subject: the location. No exposure: what was carried is not
known. Skipped like a batch on a gap or after the watermark.

### Event type `stock.movement`

A line of a stock ledger at one stock location (core, schema version 1). `evidenceLevel` `item`, `evidenceSource`
`stock`, `observationStatus` `observed`; `lineId` is the ledger line, `actorId` who recorded it.

| Payload | Rule |
|---|---|
| `locationId` | required, 1–200 characters |
| `direction` | `in` or `out` |
| `quantity` | above 0, a number or a decimal string (the size of the change; this ERP's projection writes a string) |
| `kind`, `productId`, `referenceType`, `referenceId` | optional: the ERP's movement type and the document it belongs to |

Messages: `evidenceLevel must be item for type 'stock.movement'`, `payload.locationId is required for type
'stock.movement'`, `payload.direction must be one of: in, out`, `payload.quantity must be a number above 0`.

Projection source `stock_movements`: `InventoryLedger` lines with a non-zero quantity change, except
`cost_adjustment`, at locations linked to a live `storage` zone. The event's branch is the location's, else the
zone's. Lines at other locations are not projected: the ledger would grow with every sale. A location linked later
is read from where the source's cursor is then.

## Operations insights from cameras (LP6b)

Brief: [LP6b](../phases/LP6b-camera-metrics.md). Licence `lpInsights`; the counts come from `lpVision` zones.
Camera figures count people and tables; they never identify anyone. How accurately a camera counts has not been
measured at any site, and every response says so (`cameraNote`).

The summaries are kept as in LP6a. `vision.line_crossed`, `vision.dwell`, `vision.occupancy` and `vision.gap` also
mark their day dirty; an occupancy period belongs to the day and hour it started, so one reported in the first hour
of a day also marks the day before.

| Metric | How it is calculated | Stored as (dimension `zone`, key = zone id) |
|---|---|---|
| Footfall | People crossing an `entrance` line (`class` `person`, or none): Σ `count` of direction `in`, and of `out`; per hour of `occurredAt` | `footfall_in`, `footfall_out` |
| Conversion | Sales ÷ people in, over the days with footfall (and per hour, over the same days) | read time |
| Queue wait | `vision.dwell` of `queue` zones (`class` `person`): one per person who waited at least `minWaitSeconds`; seconds = `durationSeconds` (else `endedAt − startedAt`); hour of `occurredAt` (the end of the wait) | `queue_wait` (count, total, p50, p90) |
| Queue length | `vision.occupancy` of `queue` zones (`class` `person`): seconds observed (Σ `periodSeconds`) and person-seconds (Σ `count` × `periodSeconds`); average length = person-seconds ÷ seconds | `queue_seconds`, `queue_length` (p50, p90 of the period counts) |
| Table occupancy | `vision.occupancy` of `dining` zones (`class` `person`): seconds observed, seconds with a person; rate = occupied ÷ observed | `table_seconds`, `table_occupied` |
| Seatings, wait to order | A seating: a period with a person right after a period without one (the two adjoining). Wait: from the seating's period start to the first `kitchen.ticket_created` whose `payload.tableId` is the zone's linked table, before the table is next empty and within 4 hours. Only zones linked to a `table` have a wait | `table_seatings`, `table_wait_order` (count, total, p50, p90) |
| Camera gaps | `vision.gap` of `entrance`, `queue` and `dining` zones: seconds from `from` to `to`, on the day the gap is stored, for its whole length | `camera_gap` (day rows only) |

The projection now also writes `payload.tableId` on `kitchen.ticket_created` (the order's table). Tickets projected
before LP6b carry none.

| Route | Permission | Response |
|---|---|---|
| `GET /insights/footfall?from&to&branchId` | `lp:view` | `{ from, to, branchId, totals: { in, out, sales, conversionRate }, days: [{ day, in, out, sales, conversionRate }], hours: [{ hour, in, out, sales, conversionRate }] (24), zones: [{ zoneId, name, cameraName, in, out, gapSeconds }], definitions, note, cameraNote }`. Sales count only on days with footfall |
| `GET /insights/queues?from&to&branchId` | `lp:view` | `{ from, to, rows: [{ zoneId, name, tillId, waits, averageWaitSeconds, worstDayP90WaitSeconds, observedSeconds, averageLength, worstDayP90Length, gapSeconds }], hours: [{ hour, waits, averageWaitSeconds, averageLength }] (24), definitions, note, cameraNote }` |
| `GET /insights/occupancy?from&to&branchId` | `lp:view` | `{ from, to, rows: [{ zoneId, name, linkedType, linkedId, tableName, tableNumber, observedSeconds, occupiedSeconds, occupancyRate, seatings, ordered, averageWaitToOrderSeconds, worstDayP90WaitToOrderSeconds, gapSeconds }], hours: [{ hour, occupancyRate, seatings, averageWaitToOrderSeconds }] (24), definitions, note, cameraNote }` |

`GET /insights/summary` gains `metrics.camera` (also under `previous`): `{ footfall: { in, out, sales,
conversionRate }, queues: { waits, averageWaitSeconds, worstDayP90WaitSeconds, averageLength }, tables: {
occupancyRate, seatings, ordered, averageWaitToOrderSeconds, worstDayP90WaitToOrderSeconds } }`, `coverage.camera`
`{ periodDays, daysWithData, gapSeconds, measured: false }` and `cameraNote`. Rates are 0 to 1 with 4 decimals,
seconds have 1 decimal, lengths 2; a ratio with a zero denominator is `null`.

## Activity recognition research (LP7)

Brief: [LP7](../phases/LP7-activity-research.md). Licence `lpVision`. Shadow mode: no rule reads an activity, so an
activity never makes a finding or a case. No face recognition, no pose of a person, no identity: an activity is about
what happens to objects in a zone.

Event type `vision.activity` (a vision type, schema version 1), sent by the agent only for a zone whose
`params.activityResearch` is true:

| Payload | Rule |
|---|---|
| `activity` | 1–64 characters: lowercase letters, digits or `_`, starting with a letter |
| `startedAt`, `endedAt` | ISO-8601 instants, the end not before the start |
| `recognizer` | 1–200 characters, `name@version` |
| `score` | optional, 0 to 1 |
| `trackIds` | optional, the tracker's numbers for the objects involved (they identify nothing outside one camera's track) |

Messages: `payload.activity must be 1 to 64 characters: lowercase letters, digits or '_', starting with a letter`,
`payload.recognizer is required for type 'vision.activity'`, `payload.score must be between 0 and 1`, and the
`startedAt`/`endedAt` messages of `vision.dwell`.

| Route (`/loss-prevention/research`) | Permission | |
|---|---|---|
| `GET /activities?from&to&branchId&zoneId&activity&recognizer&verdict&page&pageSize` | `lp:investigate` | `{ data: [{ eventId, seq, branchId, zoneId, zoneName, zoneType, cameraId, activity, recognizer, score, startedAt, endedAt, occurredAt, clipId, reviews, review: null \| { id, verdict, label, note, reviewedBy, reviewerName, createdAt } }], meta }`, newest first. `eventId` is the ledger row's id (as in finding details), the id the review route takes. `verdict`: `unreviewed`, `correct`, `incorrect` or `unsure` (of the latest review) |
| `POST /activities/{eventId}/review` `{ verdict, label?, note? }` | `lp:investigate` | 201 `{ id, eventId, verdict, label, note, reviewedBy, createdAt }`. Reviews are append-only; the latest one counts |
| `GET /activities/summary?from&to&branchId` | `lp:investigate` | `{ from, to, rows: [{ recognizer, activity, events, reviewed, correct, incorrect, unsure, precision }], relabels: [{ recognizer, activity, label, count }], note }`; precision = correct ÷ (correct + incorrect), `null` when none |

Messages: 404 `Activity not found` (not a `vision.activity` of the caller's organization and branches); 400
`verdict must be one of: correct, incorrect, unsure`, `label must be 1 to 64 characters: lowercase letters, digits or
'_', starting with a letter`, `A label is given only with the verdict incorrect`, `note holds at most 1000
characters`; on the list, 400 `verdict must be one of: unreviewed, correct, incorrect, unsure`. `from` and `to`
default to the last 30 days. `pageSize` defaults to 50, at most 200.

`LpActivityReview` (V576): one row per review, append-only like the case timeline.

## Storage

`LpEvent`, `LpRuleVersion`, `LpReconRun`, `LpCaseEvent`, `LpAgentEvent`, `LpEvidenceAccess` and `LpActivityReview` are append-only: a
trigger refuses any update. Deleting is left possible for a retention job; the ledger's is not built. Evidence
retention (LP1) deletes the stored clip, marks its row `deleted` and records the deletion in the access trail.
`seq` is the ledger order, which is arrival order and not event-time order.

## Deviations

None from Nest (no counterpart). Differences from the LP0 brief as first written are listed in the brief, section 12.
Differences from the LP6 design (owner approval pending) are listed in the LP6a brief, section 6.
Design choices of LP5, LP6b and LP7 that need the owner's approval are listed in their briefs (section "Open
items").

The detector evaluation harness (`lp-edge-eval`, edge package) has no backend part; see
[its brief](../phases/LP-detector-evaluation.md).
