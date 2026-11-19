# LP0 — Exception intelligence: implementation brief

- Status: **authorised by the owner (2026-11-17). WP1–WP8 built on the branch**, at the owner's request without the
  review stop after each package. Not verified against live POS traffic. Changes made while building are in
  section 12.
- ADR: [`ADR-S077`](../adr/ADR-S077-loss-prevention.md). Read it first; this brief does not repeat its reasoning.
- Branch: `phase/LP0-exception-intelligence`. One PR per work package (section 9), with a review stop after each.
- Extension: no NestJS counterpart, so there is no parity scenario and no Jest spec to port.

## 1. Goal

A customer with no cameras can switch on the module and get:

- cases raised from POS, kitchen, cash-session and beverage-count data already in the system;
- an investigation workflow with a factual outcome on every case;
- a monthly performance report for the owner;
- a documented ingest contract another POS could use later.

## 2. Out of scope

- Cameras, edge agent, evidence clips (LP1).
- Any change to POS, inventory, accounting, beverage or KDS code, including a no-sale event (deferred to LP2).
- Adapters for external POS systems. The ingest route exists and is tested; no adapter is written.
- Operational analytics (LP6).

## 3. Data model

One migration per work package (WP1: `V520`, `LpEvent` only; renumbered V560–V562 at merge). Table and column names follow the baseline style
(`"LpEvent"`, `"organizationId"`). Services use `JdbcClient` and maps, like the other extension modules; there are no
JPA entities. All tables have `organizationId`; all except rules and
settings have a nullable `branchId`.

| Table | Purpose | Key points |
|---|---|---|
| `LpEvent` | Append-only ledger | Envelope of ADR-S077 §3; `seq` identity; unique (`organizationId`, `source`, `sourceEventId`); indexes on (`organizationId`, `type`, `occurredAt`) and on each correlation id |
| `LpProjectionCursor` | Sweep position per source table | One row per organization and source |
| `LpRule` | A rule and its current settings | Code, engine, enabled, severity, parameters (JSON), current version |
| `LpRuleVersion` | Every past state of a rule | Immutable; a rule edit inserts a version |
| `LpSetting` | Settling time per engine, retention | Per organization, optional branch override |
| `LpWindow` | A unit to reconcile | Engine, key, bounds, state: `pending`, `settling`, `reconciled`, `dirty`; latest run |
| `LpReconRun` | One reconciliation of one window | Engine version, rule versions, configuration snapshot, highest `seq`, input count and hash, trigger, superseded run |
| `LpDiscrepancy` | One finding of one run | Rule version, fingerprint, expected, observed, delta, unit, exposure amount and method, subject, evidence event ids, `active` or `superseded` |
| `LpCase` | Investigation | Number (`kernel/sequence`), fingerprint, state, outcome, cause code, severity, subject user, assignee |
| `LpCaseEvent` | Append-only case timeline | Created, assigned, note, state change, finding changed, closed, reopened |

Database rules:

- `LpEvent`, `LpRuleVersion`, `LpReconRun` and `LpCaseEvent` are insert-only in application code. A trigger on
  `LpEvent` refuses updates.
- Check constraints on every code column (state, outcome, evidence level, source and status).
- Money and quantities are `numeric`; no floating point.

## 4. Projection from this ERP

Component `PosFactProjector` in `lossprevention/application`. Both paths call the same ingest service and use the
source row id in `sourceEventId`, so a fact that arrives by both paths is stored once.

| Canonical type | Fast path (outbox subscriber) | Sweep (read-only, by cursor) | Enrichment |
|---|---|---|---|
| `sale.completed` | `pos.sale.completed` | Posted POS invoices | Lines, discounts, payments, cashier, branch, cash session |
| `sale.line_voided` | `pos.order.item_voided` | — | Fired quantity from the order item; branch from the order |
| `order.cancelled` | `pos.order.cancelled` | — | Order, cashier, branch |
| `refund.issued` | none exists | `PosRefund` rows | Invoice, original cashier, approver, cash session |
| `override.approved` | `pos.override.approved` | `PosApprovalGrant` rows | Cashier and manager from the grant |
| `cash_session.*` | `cash.session.opened`, `.closed`, `.reconciled` | `CashSession` rows | Expected, counted, difference, business date |
| `cash.movement` | `cash.movement.recorded`, `cash.banking.recorded` | — | Session, amount, kind |
| `kitchen.ticket_created`, `kitchen.stage` | `pos.kds.ticket_created`, `pos.kds.ticket_updated` | `KitchenTicket` rows | Stage from `startedAt`, `readyAt`, `servedAt`, `recallCount`; branch from the order |
| `stock_count.line` | none exists | Approved `BottleCountSession` and lines | Branch from the count location |
| `staff.session` | `pos.pin.login`, `pos.pin.logoff` | — | — |

Rules for the projector:

- It only reads. It runs in its own transaction, after the source transaction has committed.
- A handler error is caught, logged and retried by the sweep. It never reaches the POS request.
- The sweep is bounded per run and scheduled with ShedLock.
- Kitchen stages from KDS are written with evidence level `ticket` and status `inferred` (ADR-S077 §3).
- `businessDate` comes from the cash session when there is one, otherwise from the organization's local day.

**To confirm when building WP2** (not traced yet):

1. How a whole-sale void is recorded. `pos.void.completed` is declared but never published.
2. Whether `PosApprovalGrant` rows remain after use. If not, cashier attribution for overrides comes from the audit
   log.
3. Which outbox event names the Android sync path produces for offline sales.

## 5. Rules in LP0

Each rule has a code, an engine, parameters with defaults, and a severity. Defaults are starting points and are
confirmed with the owner before WP4.

**Cashier engine**

| Code | Finding | Window | Default parameter |
|---|---|---|---|
| `C01` | Item voided after it was fired to the kitchen | Event | Any fired quantity |
| `C02` | Order cancelled with fired lines | Event | Any fired line |
| `C03` | Refund at or above a threshold | Event | Amount set per organization |
| `C04` | Refund rate per cashier above the branch baseline | Cashier per business day | 3 × branch median, at least 3 refunds |
| `C05` | Override rate per cashier above the branch baseline | Cashier per business day | 3 × branch median, at least 5 overrides |
| `C06` | Same manager approving the same cashier repeatedly | Pair per 7 days | 60 % of the cashier's overrides, at least 5 |
| `C07` | Discount rate per cashier above the branch baseline | Cashier per business day | 2 × branch median |
| `C08` | Cash session difference above tolerance | Cash session | Amount set per organization |
| `C09` | Repeated short sessions for one cashier | Last 10 sessions | 4 short sessions |
| `C10` | Kitchen ticket recalled or cancelled after ready | Event | Any |

**Bar engine**

| Code | Finding | Window | Default parameter |
|---|---|---|---|
| `B01` | Unexplained variance above tolerance | Count session line | Product tolerance, else the organization's |
| `B02` | Same cause code on one product in consecutive counts | Last 4 counts | 3 of 4 |
| `B03` | Waterfall does not tie to system stock | Count session line | Any difference above rounding |

Bar waterfall per product, between two approved counts:

| Line | Source |
|---|---|
| Opening stock | Previous approved count |
| + Receipts, + transfers in, − transfers out | Stock ledger |
| − Sold consumption | `InvoiceItemRecipeIngredient` of sales in the interval (sale-time snapshot) |
| − Recorded complimentary, staff drinks, spillage, breakage | Stock adjustments and count-line cause codes |
| = Expected closing | Calculated |
| Actual closing | `countedMl` of the count line |
| Unexplained variance | Expected − actual, less the explained part |

- The beverage module's `varianceMl` stays the authoritative total. The waterfall explains it; it does not replace
  it.
- `B03` is a data-quality finding, not a loss finding: it raises a low-severity case and blocks `B01` for that line.
- Millilitres are taken as stored. Tare, calibration and unit conversion remain in the beverage module.

**Settling time defaults:** cashier events 10 minutes; cashier daily windows 2 hours after the business day ends;
cash session 10 minutes after close; bar at count approval. All configurable.

## 6. Routes

All under `/loss-prevention`, all with `@RequiresModule("loss-prevention")`. List routes use the standard page
envelope. Money-free, so no `Idempotency-Key` requirement except where noted.

| Verb and path | Permission | Purpose |
|---|---|---|
| `POST /events` | `lp:ingest` | Batch ingest, up to 500. Per-event result: `accepted`, `duplicate` or `rejected` with reason |
| `GET /events` | `lp:investigate` | Browse the ledger by type, time, correlation id |
| `GET /dashboard` | `lp:view` | Open cases, by engine, by branch, trend |
| `GET /cases` | `lp:view` | Filter by state, outcome, engine, branch, subject, date |
| `GET /cases/{id}` | `lp:view` | Case, findings, timeline, linked events |
| `POST /cases/{id}/assign` | `lp:investigate` | Set assignee |
| `POST /cases/{id}/notes` | `lp:investigate` | Add a note |
| `POST /cases/{id}/start-review` | `lp:investigate` | `open` → `reviewing` |
| `POST /cases/{id}/escalate` | `lp:investigate` | → `escalated`, with reason |
| `POST /cases/{id}/close` | `lp:investigate` | Outcome required; cause code required for `explained` |
| `POST /cases/{id}/reopen` | `lp:configure` | Reason required |
| `GET /rules`, `GET /rules/{code}` | `lp:view` | Rules with current parameters |
| `PUT /rules/{code}` | `lp:configure` | Change parameters; writes a new version |
| `GET /rules/{code}/versions` | `lp:view` | History |
| `GET /settings`, `PUT /settings` | `lp:configure` | Settling times, retention |
| `GET /runs`, `GET /runs/{id}` | `lp:view` | Run history with inputs and configuration |
| `POST /runs/replay` | `lp:configure` | Re-run an engine over a date range; idempotency key required |
| `GET /runs/{id}/compare/{otherId}` | `lp:view` | Findings added, removed, changed |
| `GET /reports/performance` | `lp:view` | Section 7 |
| `GET /reports/by-cashier`, `GET /reports/bar-variance` | `lp:view` | Detail reports |

Common rules:

- Every read is filtered by organization and by the user's branch scope.
- A user never receives a case in which they are the subject; the detail route answers 404.
- Reading a case writes an audit row.
- Error messages are fixed in `docs/spec/loss-prevention.md` before the controllers are written.

## 7. Performance report

Per organization or branch, for a period:

- cases generated, reviewed, closed, and the count for each outcome;
- cases by engine, by rule, by branch, by shift;
- median time to review and to close;
- trend against the previous period;
- exposure estimate per rule, always shown with its calculation method.

The report states on its face that a confirmed variance is not a finding of loss or misconduct.

## 8. Web

A "Loss Prevention" navigation section, gated by the `lossPrevention` feature key:

| Screen | Content |
|---|---|
| Dashboard | Open cases, severity, trend |
| Cases | List with filters; detail with findings, linked events and timeline; close dialog with outcome and cause |
| Rules | Parameters, enable or disable, version history |
| Runs | History, replay, comparison |
| Reports | Performance, by cashier, bar variance |

## 9. Work packages

Each is one PR. Stop for owner review after each.

| WP | Content | Done when |
|---|---|---|
| WP1 ✅ | Migration, ingest service, `POST /events`, `GET /events`, permissions, module registration, licence keys | Duplicate, out-of-order and oversized batches behave as specified; tenant isolation test passes |
| WP2 | `PosFactProjector`: subscribers and sweep; the three open points of section 4 resolved | A POS sale, void, refund, override, session close, ticket and count each produce exactly one ledger row by either path; a projector failure does not fail the POS request |
| WP3 | Windows, settling, runs, supersede, late-event recalculation, replay, comparison | A late event produces a new run; the old run and its findings remain; a replay with a changed tolerance is comparable to the original |
| WP4 | Cashier rules `C01`–`C10` | Each rule has a positive and a negative unit test on synthetic events |
| WP5 | Bar waterfall and `B01`–`B03` | Waterfall ties to the beverage variance on seeded counts; a recipe edit after the sale does not change expected consumption |
| WP6 | Cases, timeline, outcomes, subject exclusion, audit, notification hand-off | Authorisation matrix and branch-scope tests pass; subject cannot read their own case |
| WP7 | Reports and web screens | Screens work with zero console errors |
| WP8 | `docs/spec/loss-prevention.md`, integration contract with JSON schemas, `PORT_STATUS.md` row | Documents match the code |

## 10. Tests

- **Unit (JUnit, no Spring):** every engine and rule, on synthetic event lists. Engines are pure functions, so most
  coverage is here.
- **Integration (extend `ApiTestSupport`):** ingest, projection, runs, cases, authorisation per route, organization
  isolation, branch scope.
- **Delivery faults:** the same batch twice; events in reverse order; an event arriving after its window was
  reconciled; a sweep and a subscriber delivering the same fact.
- **Reproducibility:** re-running a stored run from its recorded inputs gives identical findings.
- **Non-interference:** existing POS, beverage and cash-session test classes pass unchanged.
- **Build rule:** `compileJava compileTestJava` once and `fastTest --tests` for the affected classes. No full build
  unless the owner asks.

## 11. Decisions for the owner

| # | Question | Recommendation |
|---|---|---|
| 1 | Approve ADR-S077 and this brief | Yes, then WP1 |
| 2 | Rule thresholds in section 5 | Accept as defaults; tune on real data before selling |
| 3 | Ledger retention | 24 months, configurable |
| 4 | Is `lossPrevention` sold separately or bundled with POS plans | Separately, as in ADR-S077 §1 |
| 5 | Is the subject of a case told that a case exists | No in LP0; revisit with legal advice per country |

## 12. Changes made while building

**WP1 (2026-11-17)**

| Brief said | Built | Reason |
|---|---|---|
| One migration for all tables | One migration per work package | Later tables can still change in review without altering an applied migration |
| Hand-written entities | `JdbcClient` and maps | The convention of the other extension modules (alerts, quarry) |
| Module name `lossprevention` | `loss-prevention`; Java package stays `lossprevention` | Module names are kebab-case (`fixed-asset`) and match the route prefix |
| Licence keys `lossPrevention`, `lpVision`, `lpInsights` added together | Only `lossPrevention` | A key that nothing enforces could be sold and not switched on; the other two arrive with LP1 and LP6 |
| `lp:ingest` for API clients only | Any caller holding `lp:ingest` | The permission is the control; an administrator can test the feed |
| `eventId` as the row id | Separate row `id`; `eventId` unique per organization | Another organization's `eventId` must never collide |
| — | `GET /events/{id}` added | Needed by the case screen |
| — | Web feature entry (`features.ts`) not added | It gates a menu section that does not exist until WP7 |

**WP2–WP8 (2026-11-17)**

| Brief said | Built | Reason |
|---|---|---|
| Review stop after each work package | Built straight through | Owner's instruction |
| Windows per transaction, cash session, cashier-day, count session | One window per engine, branch and business day | One mechanism for event rules and day rules; day rules run only when the day is final |
| Subscribers and sweep as two code paths | Subscribers trigger the sweep of one source | One mapping from a row to an event, so both paths cannot disagree |
| `LpSetting` per engine | One JSON value set per organization, optional branch override | Fewer tables; same behaviour |
| Case view written to the audit log | Written to the case timeline (`viewed`) | The audit log's action list is a fixed database enum with no "view" value |
| Notification hand-off | `lp.case.opened` published; no routing rule added | Routing rules are configuration of ADR-S065 and need an owner decision on recipients |
| `GET /reports/performance` "by shift" | Left out | A shift is not on the case; needs a decision on what a shift means per branch |
| Bar waterfall computed by the engine | Components computed by the projection and sent in the event | The engine stays a pure function of ledger events, and another POS can send the same figures |
| `B03` blocks `B01` | `B03` is off by default; it blocks `B01` only when switched on | The tie-out assumes stock in millilitres and has not been checked on real counts |
| `C03` "refund without original sale" | Threshold and rate rules only | Every POS refund here is tied to an invoice |
| Retention job | Built 2026-11-19 at the owner's request ("code and develop all the remaining ones"): nightly, days with an open case kept, older events refused at ingest and replay | — |
| Five screens | One page with five tabs; the case opens in a dialog | Matches the Alerts page |
| — | `GET /runs/{id}/verify`, `POST /projection/run` added | Reproducibility check; a way to run the sweep on demand |
| — | `cash.movement` and `staff.session` not projected | No LP0 rule reads them |

Still open from section 4: how a whole-sale void is recorded was not traced. The sweep reads cancelled orders and
voided order items; an invoice voided after posting is not projected.

Notes on ordering: `seq` values are assigned at insert but become visible at commit, so a lower `seq` can appear after a
higher one. A run must not rely on "highest seq" alone to detect late arrivals; ingest marks the window dirty.
