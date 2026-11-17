# ADR-S077 — Loss prevention: exception intelligence, vision security, operations intelligence

- Status: accepted by the owner (2026-11-17). LP0 work package 1 (ledger and ingest) is built; later packages stop
  for review one at a time.
- Briefs: [`LP0-exception-intelligence.md`](../phases/LP0-exception-intelligence.md). The LP1 brief (camera platform)
  is written alongside LP0 and reviewed separately.
- Related: ADR-S002 (licensing), ADR-S004 (beverage controls), ADR-S017 (user data scope), ADR-S065 (notification
  routing), ADR-S070 (org-local accounting day).
- Extension: no NestJS counterpart. Every route is new and additive, under `/loss-prevention/*`.
- Migration: `V520__loss_prevention_ledger.sql` (WP1); one migration per later work package. Other worktrees reach
  V519, so renumber at merge if one of them has taken V520.

## Context

A business loses money where what physically happened differs from what was recorded: unrecorded sales, voids after
service, unexplained bottle variance, cash shortages. The ERP already records most of the "recorded" side. It has no
place that compares the two sides, raises a reviewable case, and keeps the evidence.

Facts from the code that shape the design:

| Fact | Consequence |
|---|---|
| `EventBus.publish` writes to the outbox in a detached transaction; a failed publish is logged and dropped | Events alone are not a complete source. Projection needs a read-only sweep as a backstop |
| Outbox handlers are dispatched at least once | Ingest must be idempotent |
| `pos.void.completed` and `pos.refund.completed` are declared but never published; refunds exist only as `PosRefund` rows | Refunds are projected by sweep, not by event |
| Event payloads carry ids only (`pos.sale.completed`: invoice id, number, cash session, total) | The projector enriches each fact with read-only queries |
| `InvoiceItemRecipeIngredient` stores the ingredients actually drawn for each sold line | Expected consumption is read from the sale-time snapshot, so a later recipe edit cannot change history |
| `KitchenTicket` has `startedAt`, `readyAt`, `servedAt`, `recallCount`, but no branch and no per-item times | Kitchen stage evidence is ticket-level; branch comes from the order |
| `CashSession` has `branchId`, `businessDate`, `closingExpected`, `closingCounted`, `closingDifference` | Cash variance needs no new data |
| The beverage module computes `varianceMl` against perpetual stock, with tare and calibration in the bottle config | Loss prevention never converts grams to millilitres itself |
| There is no no-sale or drawer-open event in the POS | That rule is out of LP0 (owner decision: deferred to LP2) |

## Decision

### 1. One module, three licensed areas

Module `com.inventoryerp.lossprevention` (`web` → `application` → `domain`), registered in `ModuleRegistry` like the
other optional modules.

| Area | Content | Licence key | Phase |
|---|---|---|---|
| `core` | Event ledger, ingest, reconciliation framework, rules, cases, reports | `lossPrevention` (`@RequiresModule("loss-prevention")`) | LP0 |
| `vision` | Edge agents, cameras, zones, evidence | `lpVision` (`@RequiresFeature`) | LP1+ |
| `insights` | Operational analytics | `lpInsights` (`@RequiresFeature`) | LP6 |

- `core` has no dependency on `vision`. A case works with no camera and no evidence.
- Each key is added when its area is built, in `LicenseFeatures` and the licence server's
  `Entitlements.KNOWN_FEATURES` together, and in `web/src/lib/features.ts` when its menu section exists.
- The Python edge agent lives in `edge/` with its own Dockerfile, version and CI job. It is the one component outside
  the pinned stack; this ADR is its authorisation. It is not built in LP0.

### 2. Read-only towards every other module

- POS checkout, stock posting, GL posting, beverage counts and KDS are not modified.
- Loss prevention subscribes to existing events and reads other modules through their exposed `domain` interfaces.
- It writes only its own tables. It never posts stock or journals and never changes a POS document.
- A failure in a loss-prevention handler must not fail or delay a POS request. This is proven by a test in LP0.

### 3. One append-only event ledger

Recorded facts (POS, KDS, cash, stock counts) and observed facts (cameras, sensors, later) go into one table,
`LpEvent`. Rows are never updated or deleted, except by the retention job.

**Envelope (contract version 1):**

| Field | Required | Meaning |
|---|---|---|
| `eventId` | yes | UUID, assigned by the sender; unique per organization |
| `source` | yes | Sending system, for example `erp-pos`, `pos-cafe`, `edge-agent` |
| `sourceEventId` | yes | The sender's own id. Unique with `source` per organization: the dedupe key |
| `organizationId` | server | Taken from the authenticated tenant, never from the body |
| `branchId` | external: yes | Branch or site. Internal projection resolves it; may be null |
| `deviceId` | no | Till, terminal, camera or agent |
| `actorId` | no | Staff member who performed the action (cashier, bartender, approver in payload) |
| `transactionId` | no | Invoice or sale |
| `orderId` | no | Order (an order may have several tickets and several payments) |
| `ticketId` | no | Kitchen ticket |
| `lineId` | no | Sale line or order item |
| `sessionId` | no | Cash session or count session |
| `businessDate` | no | Trading day, when the sender has one; otherwise derived (ADR-S070) |
| `occurredAt` | yes | When it happened, sender clock |
| `ingestedAt` | server | When the ledger stored it |
| `schemaVersion` | yes | Version of the payload schema for `type` |
| `type` | yes | Canonical type, below |
| `payload` | yes | Type-specific JSON |
| `evidenceLevel` | yes | `item`, `ticket`, `order`, `session` or `zone` |
| `evidenceSource` | yes | `pos`, `kds`, `stock`, `camera`, `sensor` or `manual` |
| `observationStatus` | yes | `observed`, `inferred` or `unavailable` |
| `confidence` | no | 0–1. Recorded POS facts are 1 |

The ledger also assigns `seq`, a strictly increasing number, used for reproducible runs (section 5).

**Canonical types, version 1:**

| Type | Level | Notes |
|---|---|---|
| `sale.completed` | order | Lines, quantities, discounts, payments in payload |
| `sale.line_voided` | item | Quantity, whether already fired to kitchen, reason, approver |
| `order.cancelled` | order | Reason, fired lines lost |
| `refund.issued` | order | Amount, items, stock disposition, approver |
| `override.approved` | order | Kind, approver, cashier |
| `cash_session.opened` / `.closed` / `.reconciled` | session | Expected, counted, difference on close |
| `cash.movement` | session | Pay-in, pay-out, banking |
| `kitchen.ticket_created` | ticket | Station, items |
| `kitchen.stage` | ticket | Stage: `preparation_started`, `prepared`, `dispatched`, `delivered`, `remade`, `wasted`, `cancelled` |
| `stock_count.line` | session | Product, system, counted, variance, reason code |
| `staff.session` | session | PIN login and logoff |

Rules for stage evidence:

- A KDS ticket marked ready gives `prepared` at **ticket** level with status **inferred** for its items. It is not
  proof that each item was prepared.
- `dispatched` and `delivered` are separate stages. One is never derived from the other.
- A stage nobody can observe is recorded as `unavailable`. An unavailable stage never creates a discrepancy.
- A rule states the minimum evidence level and status it accepts. Inferred ticket-level evidence cannot satisfy a
  rule that needs observed item-level evidence.

### 4. Ingest guarantees

- **At-least-once, idempotent.** A repeated (`source`, `sourceEventId`) is answered as a duplicate and stored once.
- **Any order.** Events are matched on `occurredAt` and correlation ids, never on arrival order.
- **Internal projection uses two paths, both idempotent on the same key:** outbox subscribers for speed, and a
  read-only sweep with a cursor for completeness. The sweep covers what has no event (refunds, count sessions) and
  whatever a dropped publish missed.
- **External systems** post batches to `POST /loss-prevention/events` as an API client holding the `lp:ingest`
  scope. The internal projector calls the same application service, so both follow one contract.
- **Scope of the first release:** this ERP's POS is the reference implementation. No external adapter is built. The
  NestJS POS-Cafe is the intended first external sender, later.

### 5. Reconciliation: versioned, reproducible, replayable

Every engine has the same shape: window → expected → observed → discrepancies. Engines are pure functions of ledger
events and configuration.

- **Window:** a unit to reconcile — a transaction, a cash session, a business day per cashier, a count session.
- **Settling time:** a window is reconciled only after it has been quiet for the engine's settling time. The time is
  configurable per engine, per organization, with a branch override.
- **Run:** each reconciliation of a window writes an `LpReconRun` that records:
  - the engine and its version;
  - the id and version of every rule used;
  - a snapshot of the configuration used (tolerances, settling time);
  - the input: window bounds plus the highest ledger `seq` included, with the event count and a hash.

  Because the ledger is append-only, "window + highest seq" identifies the exact inputs, so a run can be repeated.
- **Late event:** an event whose `occurredAt` falls in an already-reconciled window marks the window dirty. A job
  runs it again.
- **Supersede, never overwrite:** a new run points at the run it replaces. Discrepancies of the old run are marked
  superseded and kept.
- **Stable identity:** each discrepancy has a fingerprint (engine, rule, subject, window). The same finding in a new
  run keeps its case.
- **Replay:** a user with `lp:configure` can re-run a date range after changing a rule or tolerance. Two runs of
  the same window can be compared: which discrepancies appeared, disappeared or changed, and which cases stay open.
- **Effect on cases:** when a finding disappears, an open case gets a timeline entry and may close automatically as
  a false alarm. A closed case is never changed by a run.

### 6. Cases state facts, not blame

- States: `open` → `reviewing` → `closed`, with `escalated` as a state a review can enter and leave.
- Outcome, required on close:

| Outcome | Meaning |
|---|---|
| `explained` | A valid business reason, with a cause code |
| `confirmed_variance` | Recorded and physical quantities differ; the cause is not established |
| `false_alarm` | The detection or the reconciliation was wrong |
| `inconclusive` | Not enough evidence |

- There is no outcome that records misconduct. The system reports variances; people decide what follows.
- Cause codes reuse the beverage vocabulary (`spillage`, `breakage`, `complimentary`, `staff_drink`,
  `unrecorded_sale`, `bottle_transfer`, `measurement_error`, `unexplained`) and add `remake`, `waste`,
  `late_sync`, `training`.
- The case timeline (`LpCaseEvent`) is append-only.
- Deviation from the review: it listed "escalated" as an outcome. It is a state here, because an escalated case is
  still open and must end with one of the four outcomes.

### 7. Tenancy and security

- Every table carries `organizationId`; every query filters on it.
- Every row carries `branchId` where one can be resolved. Users with a restricted data scope (ADR-S017) see only
  their branches; a row without a branch is outside a restricted user's scope.
- Permissions (added to `packages/shared/src/permissions.ts`, then generated):
  - `lp:view` — dashboard, cases, reports;
  - `lp:investigate` — assign, note, review, close;
  - `lp:configure` — rules, settings, replay;
  - `lp:ingest` — post events (API clients only);
  - `lp:evidence` — view or download clips (LP1).
- A user who is the subject of a case cannot see or act on it, whatever their permissions.
- Camera credentials never reach the backend (LP1). No face recognition and no biometric identification, in any
  phase. Staff are attributed through their POS login.
- Every case view and every evidence access writes an audit row.

### 8. Edge agent principles (detail in the LP1 brief)

- The agent only makes outbound HTTPS connections. No inbound port is opened at the customer site.
- Short-lived tokens, obtained with credentials bound to one branch.
- Heartbeat, health and clip upload are started by the agent. Live view goes through a relay the agent connects out
  to.
- During an internet outage the agent keeps recording and queues observations.
- Clips are encrypted at rest, carry a SHA-256 taken at capture, and are kept with configurable time before and
  after the event.
- The detector is not chosen here. A separate evaluation compares candidates on accuracy, latency, memory, and the
  licences of code, pretrained weights and dependencies.

### 9. Delivery order

1. Architecture and contracts (this ADR, LP0 brief).
2. LP0 — exception intelligence, including bar variance from existing counts.
3. LP1 — camera platform.
4. LP3 — bar intelligence (measurement adapters, optional camera assistance).
5. LP4 — restaurant and cafe staged flow.
6. LP2 — cashier vision (and the deferred no-sale event, if still wanted).
7. LP5 — retail checkout count.
8. LP6, LP7 — operations intelligence, activity recognition research.

No vision code starts before a pilot site with real footage and POS data is named.

## Consequences

- A new optional module and one migration. Organizations without the licence key see nothing new.
- The parity harness is unaffected: no existing route, response or posting changes.
- Projection adds read load on POS tables. The sweep is cursor-based and bounded per run.
- The ledger grows with every sale. LP0 defines retention per organization.
- The canonical contract becomes a public interface. Changes after release need a new `schemaVersion`.

## Alternatives rejected

- **Separate tables for POS facts and camera observations.** Two ledgers need two ingest paths, two dedupe rules and
  a join for every engine.
- **Stream processing with event reordering.** Versioned re-runs give the same result with a full audit trail and no
  new infrastructure.
- **Publishing new events from POS for LP0.** It would touch POS code for something a read-only sweep can do.
- **Loss prevention inside the POS module.** It would tie the product to this POS and break the licence split.
