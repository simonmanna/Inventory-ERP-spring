# ADR-S087 — Quality control (Odoo-style operator checks)

- Status: approved (owner, 2026-11-24: "Approve all of it and build all the quality management module feature"); implemented (QC1–QC4) on stacked PRs #52, #53, #55 and the QC4 PR — not merged, not deployed.
  QC1 implemented; QC2–QC4 follow as stacked PRs. Merge and deploy need the owner.
- Extension: no NestJS counterpart. A new module `com.inventoryerp.qualitycontrol` with new routes under
  `/api/v1/quality-control`; no existing route, response or table changed.
- Migrations: `V660__quality_control.sql` (QC1). V661–V669 are reserved for this module.
- Spec: `docs/spec/quality-control.md`.
- Related: ADR-S013 (formal quality: specifications, lots, NCR, CAPA, CoA). That module and the manufacturing
  `QcPlan` / `qc_hold` path are **not modified**.

## Context

The owner wants Odoo-style operational quality control in production and manufacturing
(https://www.odoo.com/app/quality): control points that create checks on receipts, manufacturing orders, work orders
and deliveries; operator checks of five types (instructions, pass/fail, measure, picture, worksheet); blocking of the
operation until a check is done; quality alerts handled by teams; and SPC statistics.

ADR-S013 already covers the formal and laboratory side (specifications, inspection lots, quarantine decisions, NCR,
CAPA, certificates of analysis, traceability). The owner decided (2026-11-24) to build a **separate** module beside
it rather than extend it: `quality` keeps the formal and lab records; `qualitycontrol` owns control points, operator
checks, blocking, alerts and SPC. The two meet only through explicit escalation (an alert raises an NCR, §8).

The plan went through three review rounds (rev 1 → rev 3). The corrections of those reviews are part of the
specification below: lifecycle with immutable execution revisions, corrections after source closure, concurrency-safe
generation identity, per-line sampling semantics, independent blocking flags, atomic receipt / quarantine, per-resource
DMS write rights, and statistically sound SPC.

## Naming
- **Package:** `com.inventoryerp.qualitycontrol`.
- **Routes:** `/api/v1/quality-control/...`.
- **Tables:** `Qc*`.
- **Numbers:** `QCC-000001` for checks and `QCA-00001` for alerts, from `SequenceService`.
- **Events:** `quality_control.*`.
- **Web:** pages in `pages/quality-control/*`. The nav entry "Quality Control" sits in the Manufacturing zone of
  `app-shell.tsx`, or in the Inventory zone when manufacturing is off.
- **Availability:** core-available, with no `@RequiresModule` (same as ADR-S013).

### Permissions
Defined in `packages/shared/src/permissions.ts`, then `node tools/contracts2java/generate.mjs`.

| Action | Permission |
|---|---|
| View points, checks, alerts, attachments, statistics | `quality_control:read` |
| Configure points, teams, templates, failure classes (stages, tags, causes in QC3) | `quality_control:configure` |
| Execute pending checks, record samples, attach check photos | `quality_control:check` |
| Create, move and edit alerts, attach alert evidence | `quality_control:alert` |
| Waive, reset, override a failure, retry quarantine, open a correction check | `quality_control:manage` |

- Operators (`check`) can never reset or waive.
- The Manager role defaults to `read`, `check` and `alert`.
- Administrator gets every permission, through the migration and through `CorePermissionSync`.

## Decision: specification

### 1. Control points and configuration history
**Fields:**
- Title.
- `operation ∈ receipt | delivery | manufacturing | work_order`.
- Products and categories (empty means all). Supplier (receipt only). Routing operation (work_order only).
- Team and responsible user.
- `checkType ∈ instructions | pass_fail | measure | picture | worksheet`.
- Instructions, step document, failure message, and `failureClassRequired`.
- Measure settings: `norm`, `toleranceMin`, `toleranceMax`, `unit`, `decimals`.
- Worksheet template.
- Sampling: `controlPer`, `testQtyPct`, `minSampleQty`.
- Frequency: `frequency`, `randomPct`, `periodValue` / `periodUnit`.
- Blocking: `blocking` (default true) and `blockOnFailure` (default false). See §5.
- `autoAlert` (default true).
- `receiptMode ∈ pre_receipt | quarantine_on_receipt` (receipt only).
- `failureLocationId` (default `QC-QUARANTINE`).
- SPC subgrouping (§12) and `isActive`.

**Versioning:**
- Every create or update writes an immutable `QcControlPointVersion` row: version n, the full config as JSON,
  changedBy, changedAt and a reason.
- A check stores the id of the version it was generated under, plus a snapshot of the norm, tolerances, instructions
  and template version.
- A later edit never changes an existing check.

**Deactivation:**
- No new checks are generated.
- Existing checks stay valid, so pending ones still block until they are done or waived.
- The API answer and the UI both say this.

### 2. Check lifecycle, execution revisions and corrections
**Structure:**
- `QcCheck` is the requirement.
- `QcCheckExecution` holds the attempts. Each attempt is one immutable row with `revision` (1, 2, …), result, measured
  values, worksheet answers, evaluation, failure class, picture attachment, executedBy and executedAt.
- `QcCheck` carries `currentRevision` and the current `status`.
- Every transition also appends to `QcCheckEvent` (from, to, revision, by, at, reason, payload) and calls
  `AuditService.recordInTx`.

**States:**
- `pending → passed | failed`. This is an execution and writes revision n.
- `pending → cancelled`. Only `sync` or `cancelFor` do this, with a reason.
- `pending | failed → waived`. Needs `manage`, a **reason** and a **disposition**
  (`accepted_as_is | quantity_excluded | reworked | other`, with `excludedQty` when the quantity is excluded). A
  `passed` check cannot be waived.
- `passed | failed | waived → pending`. This is a **reset**: it needs `manage` and a reason, and only while the source
  is open. It opens execution cycle n+1. Revision n stays immutable and stays visible as superseded.

**Source closure (pre-completion checks):**
- When the source completes or posts, every `phase = pre_completion` check is stamped `sourceClosed = true`.
- After that, re-execution, reset and waiver all return 400.
- **A correction after closure** never modifies the original check. A `manage` user opens a correction check
  (`origin = correction`, `correctsCheckId` = the original). It is a new, independent check with its own executions;
  in QC3 it is linked to an alert or NCR. The original stays as it was recorded.

**Post-receipt checks (`phase = post_receipt`, QC2):**
- They are generated *by* the receipt post. They are not stamped `sourceClosed` by that post.
- They stay executable until they are decided.
- Their decision drives the stock release (§7). The `phase` column keeps them from being confused with closed
  pre-completion checks.

**Concurrency:**
- Executing a check locks its row (`FOR UPDATE`) and requires `pending`.
- A second operator gets 409 `Quality check QCC-000012 is already passed.`

**Manual checks (`origin = manual`, QC1):**
- They can exist without a control point.
- They may reference a source document (`sourceType` / `sourceId` / `sourceRef`) for traceability, plus product,
  variant and batch.
- They **never take part in blocking**. The gate counts only `origin = generated` checks.
- They join SPC only when they are linked to a measure control point and `includeInSpc` is set (the default is false
  for manual checks).
- Users can raise alerts from them by hand (QC3). They never auto-alert.

### 3. Generation identity and idempotency (QC1 schema, QC2 use)
**Keys:**
- `baseKey = controlPointId | sourceType | sourceId | workOrderId | scope`. Scope is `-` for `operation`,
  `productId:variantId` for `product`, and `sourceLineId` for `quantity`.
- `generationKey = baseKey#seq`. Seq 0 is the original check; 1, 2, … are supplementary checks.

**Safe seq allocation:**
- `QcGenerationCounter(organizationId, baseKey, nextSeq)` with
  `INSERT … ON CONFLICT (organizationId, baseKey) DO UPDATE SET "nextSeq" = "nextSeq" + 1 RETURNING`.
- The row lock makes concurrent allocations serial, so no two transactions can get the same seq.

**Uniqueness:**
- A PostgreSQL partial unique index on `("organizationId", "generationKey") WHERE status <> 'cancelled'`.
- Inserts use `ON CONFLICT … DO NOTHING`.
- A cancelled check keeps its row and history. Only its key is freed.

**Serialisation:**
- `sync` takes `pg_advisory_xact_lock(hashtextextended(org|sourceType|sourceId, 0))`, a 64-bit key.
- This hash is **only a lock key**. A collision only causes extra serialisation. It is never used as an identity or a
  security boundary; the unique index above is the identity guarantee.

### 4. Sampling, sample records and frequency
**`controlPer`** (example: an MO with A=100, B=200, C=300 at 10%):
- `operation`: one check per document, or per work order for `work_order`. No test quantity.
- `product`: one check per distinct matching product and variant. `coveredQty` is that product's total.
- `quantity`: one check per matching line, so **3 checks testing 10 / 20 / 30**, each computed independently.

**Test quantity:**
- `testQty = ceil(coveredQty × testQtyPct / 100)` at the product UoM's decimal scale. A whole-unit UoM gives whole
  units.
- It is clamped to `[minSampleQty (default 1), coveredQty]`.
- `coveredQty` (what the check vouches for) and `testQty` (what must physically be sampled) are stored separately.

**Sample records** (table and API in QC1):
- `QcCheckSample` holds `sampleRef`, batch / lot, `sampleQty`, sampledBy, sampledAt, result, `measureValue`, notes
  and the execution revision.
- A measure or pass/fail execution can carry one or more samples. The execution fails if any sample fails, after the
  operator confirms.
- For generated `quantity` checks (QC2), passing needs recorded `Σ sampleQty ≥ testQty`.
- Measure samples also feed SPC subgroups (§12).

**Sampling unit:**
- The line's UoM, in base quantity.
- Random sampling decides *whether a check is generated* for a document, product or line. The physical units actually
  tested are identified by the sample records.

**Frequency scope:** per control point for `operation`; per (point, product) for `product` and `quantity`.
- `random`: the roll happens once per base key and is persisted in `QcSamplingDecision` (key, selected, roll, pct).
  It is never re-rolled. It uses an injectable `QcRandom`.
- `periodic`: `QcPointSchedule(controlPointId, productId, lastGeneratedAt)` is upserted under a row lock in the
  generating transaction. The reference event is check generation.

**Quantity changes on an open source:**

| Check | Change | Behaviour |
|---|---|---|
| pending | quantity changes | recompute `coveredQty` / `testQty` (recorded as an event) |
| done | quantity up | supplementary check `#n` covering the increase |
| done | quantity down | unchanged |
| pending | line removed | cancelled with reason `source line removed` |
| passed / failed / waived | line removed | kept, with its alerts |
| any | line restored | new check (key freed) |

### 5. Blocking semantics: `blocking` and `blockOnFailure` are independent
One evaluator, `QcBlockingPolicy`, is the **only** place these flags are interpreted. Every service and the UI badge
use it.

| `blocking` | `blockOnFailure` | Pending check | Failed check (not waived) |
|---|---|---|---|
| true | false | blocks | allows (auto alert in QC3) |
| true | true | blocks | blocks |
| false | false | allows | allows |
| false | true | allows | **blocks** |

- Waived and cancelled checks never block.
- Manual checks (§2) never block.
- A point deactivated after generation does not affect its existing checks.

### 6. `QcGate` contract and hook points (QC2)
`QcGate` is a named interface in `qualitycontrol.application`.
- It runs with `@Transactional(propagation = MANDATORY)` inside the caller's transaction.
- It never imports manufacturing, procurement or sales; those modules call it.
- Enforcement happens on the server, inside the business method, before any stock or GL write.

**Operations:**
- `sync(QcSourceDoc)`.
- `assertComplete(sourceType, sourceId[, workOrderId])`. It locks the source's checks and applies `QcBlockingPolicy`.
  On a block it returns 400 `Quality checks are pending: QCC-000012, QCC-000013.`
- `onCompleted(source, stockContext)`.
- `cancelFor(sourceType, sourceId)`.

**Hook points (verified on origin/main):**

| Source | sync | assertComplete + onCompleted | cancelFor |
|---|---|---|---|
| MO | `ProductionService.confirmOrder` (:358), `startOrder` (:392) | `completeOrder` (:493) | `cancelOrder` (:771) |
| Work order | `WorkOrderService.generateForOrder` (:52), `start` (:137) | `complete` (:171); `ShopFloorExecutionService.complete` delegates to it | MO cancel |
| Receipt | `GoodsReceiptsService.createDraft` (:145) and draft edits | `GoodsReceiptsService.post` (:369, also behind `createAdhoc`) **and** the inline post in `PurchaseOrdersService.receive` (~:833) | `cancelDraft` |
| Delivery | `SalesDeliveryService.create` (:434), `update` (:637) | `issue` (:778) | `cancel` (:799) |

**Receipt modes:**
- `pre_receipt`: a draft GRN is blocked until its checks pass. A one-shot receive that matches a `pre_receipt` point
  is **refused** with 400 `Quality checks are required before this receipt is posted. Save it as a draft goods
  receipt, complete the checks, then post.` QC2 confirms that a draft GRN can be created from a PO, and adds that path
  if it cannot.
- `quarantine_on_receipt`: see §7.

**Deliveries:**
- QC never issues part of a delivery.
- To ship only the good quantity, the user reduces the load line (`update` triggers `sync`).
- A `manage` user then waives the failed check with `disposition = quantity_excluded` and `excludedQty`.
- If the line is removed instead, the failed check stays as history.

**Cash-sale direct-invoice delivery (PR #25):** QC2 verifies whether this path bypasses `SalesDeliveryService`.
If it does, QC2 either hooks it or records an explicit exclusion for the owner to sign off. It is never left silently
uncovered.

### 7. Inventory integrity and atomicity (QC2 acceptance conditions)
**Movement rules:**
- Only `StockService.transfer` moves stock, preserving batch, variant and UoM. There is no second ledger and no
  event-driven stock move.
- The ledger reference `qc_check:<id>:<revision>` is the idempotency key. It is checked before any move.
- Before moving, verify the location and the stock on hand for that product, variant and batch. Clamp to the
  available, unreserved quantity. Never move more than `failedQty`.
- Transfers are at carried cost: no valuation change, and the existing transfer GL rules apply.

**Check fields:** `failedQty`, `quarantinedQty`, `quarantineStatus ∈ not_required | pending | completed | failed`,
`quarantineError`, `quarantineMoveRef`.

**Mandatory atomic units (QC2 acceptance conditions):**
1. **Receipt post + initial quarantine move + post-receipt check generation:** one transaction. If any part fails, the
   whole receipt rolls back.
2. **Pass decision + stock release:** one transaction. A check can never show passed while its release has not
   happened.
3. **Fail decision + quarantine retention or release of the remainder:** one transaction.
4. **Invariant:** no quantity under a pending post-receipt check is ever available. An IT asserts that available stock
   excludes the quarantine location until the check passes.

**Pre-completion failures (MO or draft GRN):**
- The failed-quantity move runs inside the completion transaction.
- If the move is impossible (stock reserved or consumed in the meantime), the completion still commits. The check then
  records `quarantineStatus = failed` and `quarantineError`, raises an alert, and appears on the "Quarantine
  exceptions" view.
- From that view a `manage` user can retry with `POST /quality-control/checks/:id/quarantine/retry`.
- A quantity is never shown as quarantined unless it was actually moved.

### 8. Alerts and the NCR relationship (QC3)
**Alert fields:** team, responsible, priority 0–3, tags, root cause, failure class, product and batch, work centre,
source, supplier, corrective action, preventive action.

**History:**
- `QcAlertStageHistory` records from, to, by, at and a comment.
- `QcAlertAssignmentHistory` records the team and responsible before and after, by, at.
- Default stages: New, Confirmed, Action Proposed, Solved (closed).

**Idempotency:**
- At most one auto alert per failed check: a unique index `(organizationId, checkId) WHERE origin = 'auto'`, written
  with `ON CONFLICT DO NOTHING`.
- At most one NCR per alert: a unique `QcAlert.ncrId` link, set under a row lock, so repeated escalation returns the
  existing NCR.

**Closing:** closing or resolving an alert **never** changes its check, an NCR or a CAPA.

**Mapping to the existing quality module:**

| Quality Control | Existing Quality (ADR-S013) |
|---|---|
| Failed check | Potential non-conformance (escalate explicitly) |
| Quality alert | Operational investigation and response |
| Alert root cause | Copied to the NCR's root cause on escalation |
| Alert corrective / preventive action | Proposed CAPA actions (copied on escalation; CAPA is the formal record) |
| Resolved alert | Does not close the NCR |
| Passed check | Does not close an NCR |

- Escalation calls `QualityNcrService.create`. The dependency is `qualitycontrol → quality` only.
- Traceability works in both directions: `QcAlert.ncrId`, and an NCR `externalRef = QCA-…` (if that NCR column already
  exists; otherwise it is shown through a qualitycontrol lookup, with no change to the `quality` schema).
- Notifications go through `RoutedEventSource`. An aging `AlertSource` is added.

### 9. Failure classification (QC1)
- `QcFailureClass` master, with defaults created on first use: material, dimensional, strength, moisture, packaging,
  documentation, process deviation.
- A failed execution must carry a class when the point sets `failureClassRequired`.
- QC3 alerts and the QC4 Pareto chart use the class.

### 10. Worksheets (QC1)
**Field definition:** `{key, label, type: text|number|checkbox|select|date|photo, required, min, max, decimals,
options, passValues, expected, maxLength, showIf: {field, equals}}`.

**Versioning:** templates are versioned. Editing a template that has been used creates version n+1. A check pins the
version and keeps a snapshot.

**The backend `WorksheetEvaluator` is authoritative:**
- Unknown keys and wrong types are rejected.
- Numbers are checked for decimals and range.
- Select answers must be one of the options, and pass only on `passValues`.
- Checkboxes pass only when they equal `expected`.
- Fields hidden by `showIf` are neither required nor evaluated.
- The per-field reasons are stored on the execution.
- Frontend validation is only a convenience.
- The "Correct / Confirm" step uses a separate dry-run route, `POST /quality-control/checks/:id/evaluate`, which
  returns `{result, evaluation, failureMessage}` and saves nothing. `execute` always saves. (The plan's
  `confirm=false` flag on `execute` was dropped: one route would otherwise return two response shapes.)

### 11. Security
**Tenancy:** every table has `organizationId` and the **standard V214 RLS policy**, written inline in the V560 form.

**DMS attachments.** `QcResourceProvider` sets write rights per resource:

| Resource | Read | Write |
|---|---|---|
| `qc_control_point` (instructions and step documents) | `read` | `configure` |
| `qc_check` (photos, sample evidence) | `read` | `check` |
| `qc_alert` (evidence, QC3) | `read` | `alert` |

- A cross-tenant download is denied, and an IT covers it.
- Waivers, resets, corrections, overrides and quarantine retries are all audited.

### 12. SPC and statistics (QC4: the statistical spec needs separate approval)
**Rational subgroups:**
- Chosen per point: `spcSubgroup ∈ individual (default) | consecutive(n=2..10) | batch | work_order |
  time_window(h)`, with an optional split by work centre.
- A source document alone is never a subgroup.

**Charts:**
- X̄-R with A2 / D3 / D4 / d2, or I-MR with σ̂ = MR̄ / 1.128.
- UCL and LCL come only from the data, over a stored baseline window or as labelled trial limits.
- USL and LSL come from the check snapshots, are drawn separately, and are never used to derive control limits.
- A change in the spec version splits the series.

**Minimums:** limits are labelled provisional below 20 subgroups (or 25 individual values).

**Capability:**
- Cp, Cpk, Cpu or Cpl are reported only when all of these hold: spec limits exist, there are at least 30 valid values
  within one spec version, the baseline has no rule-1 violation, and σ̂ > 0.
- Otherwise the answer is `{valid: false, reason}`.
- With zero variation, the result is "undefined (zero variation)".

**Data handling:**
- Missing, non-numeric and cancelled values are excluded and counted.
- The response always includes the method, sigma estimator, n, k, the baseline, the excluded count and the dataset
  check and sample ids.
- Western Electric rules 1–4 are selectable; the default is rules 1 and 4.
- `BigDecimal` with `DECIMAL64` throughout.
- Before any production use, the calculations are validated against published reference datasets.
- Also included: pass rate and trend, failures by point, product, work centre, team and class, and a Pareto chart.

### 13. Future-proofing (not built; the model supports it)
- **Inspection stages:** the `operation` values map to IQC (receipt), IPQC (work_order), FQC (manufacturing) and OQC
  (delivery).
- **Supplier quality:** `supplierPartnerId` on checks and alerts.
- **Quality cost:** `failedQty`, the failure class and product cost.
- **CoA and the formal lab path:** stay in ADR-S013, reached through NCR.

**Not ported from Odoo:** "Register consumed / produced", "Print label", Odoo Spreadsheet, and the PLM and
Maintenance links.


## Alternatives rejected

- **Extend ADR-S013** (add control points and operator checks to `quality`). Rejected by the owner: the formal lab
  workflow (specs, lots, CoA) and the operator workflow (quick checks on the shop floor) have different users,
  lifecycles and blocking rules; mixing them would make both harder to change. NCR / CAPA stay the formal record.
- **Reuse the manufacturing `QcPlan` / `qc_hold` path.** It covers only production orders, has no check types, no
  receipts or deliveries, and no alerts. It stays as it is.
- **Event-driven stock moves for quarantine.** Rejected: a check could show passed while its release had not happened.
  Quarantine moves run inside the deciding transaction, through `StockService.transfer` only (§7).
- **Hash-based generation identity.** `hashtext` is 32-bit; a collision would merge two checks. Identity is the
  `generationKey` text with a partial unique index; the hash is only an advisory-lock key (§3).
- **Control limits from specification limits.** Statistically wrong; UCL / LCL come from the data only (§12).

## Implementation notes (QC1)

- Persistence follows the `quality` module: hand-written entities (column catalogue, `@Filter` tenancy, ddl validate)
  and plain JDBC through `QcDb`. Every table has the standard V214 RLS policy, written inline.
- `QcCheckExecution`, `QcCheckSample`, `QcCheckEvent` and `QcControlPointVersion` are append-only: a trigger refuses
  UPDATE (and DELETE) on them.
- Audit rows use the existing `AuditAction` values (the enum is a DB type owned by the kernel): execute → `update`,
  waive → `approve`, reset → `reopen`, correction / new template version → `create`, activate / deactivate →
  `update`. The exact operation is in the `op` field of the audit payload, and the precise transition is in
  `QcCheckEvent`.
- DMS: three resource providers (`qc_control_point`, `qc_check`; `qc_alert` in QC3). Read needs
  `quality_control:read`; write needs `configure` / `check` / `alert` respectively.
- Manual checks may name a source document (`production_order`, `work_order`, `goods_receipt`, `sales_delivery`); the
  id must exist in the tenant. They never block.

## Implementation notes (QC2)

- `QcGate` (`MANDATORY` propagation) is called from the business methods; the four modules hold it through an
  optional setter (`@Autowired(required = false)`), so their existing constructions and unit tests are unchanged.
  Hook calls, all before the method's stock or GL writes unless stated:
  - `ProductionService`: `confirmOrder` and `startOrder` → `sync` (planned output, base units); `completeOrder` →
    `assertComplete` (the order and its work orders) and, after the main output is received, `onCompleted` (failed
    quantities move from the receive location); `cancelOrder` → `cancelFor` (the order and its work orders).
  - `WorkOrderService`: `generateForOrder` and `start` → `sync` (source `work_order`); `complete` → `assertComplete`
    and `onCompleted` (no stock). `ShopFloorExecutionService.complete` goes through it.
  - `GoodsReceiptsService`: `createDraft` → `sync`; `post` → `assertComplete` inside the posting transaction, then
    `onCompleted` after the stock receive and before the voucher; `cancelDraft` → `cancelFor`. `createAdhoc` returns
    the draft (with `qualityChecksPending: true`) when its checks block.
  - `PurchaseOrdersService.receive`: the approval branch's draft → `sync`; the one-shot receive is refused when a
    `pre_receipt` point matches (any frequency), and otherwise calls `onCompleted` in the same transaction (atomic unit 1).
  - `SalesDeliveryService`: `create` and a re-plan in `update` → `sync`; `issue` → `assertComplete` before dispatch,
    `onCompleted` after (closes the checks, no stock); `cancel` → `cancelFor`.
- **Exclusion (needs owner sign-off):** the cash-sale direct-invoice path (`CashSaleService.confirm`, PR #25) and a
  plain sales-invoice post issue stock without a delivery, so delivery control points do not run on them. Hooking
  them would need a check workflow inside the till/invoice flow, which is out of QC2's scope. A delivery control point
  therefore covers deliveries made through Sales → Deliveries only.
- Execution after source closure stays a 400 (§2): a non-blocking pre-completion check still pending at completion
  remains pending and can only be followed up by a correction check.
- Quarantine-on-receipt points must check per product or per quantity (a receipt-level check has no single product
  to hold): `Quarantine on receipt needs a check per product or per quantity.`
- A decided post-receipt check (stock moved) cannot be reset: `Quality check X has moved stock, so it cannot be reset.
  Open a correction check instead.` A post-receipt check never blocks its receipt (it is created by the post).
- Failed pre-completion quantities move from the document's stock location (MO: where the output was received; a
  production order with its own ADR-S013 QC hold already receives into `QC-QUARANTINE`, so nothing moves). Deliveries
  never move stock for QC; a draft-GRN pre-receipt failure has nothing received yet, so its quantity is only recorded.
- Ledger references: `qc_check / <checkId>:receipt`, `<checkId>:<revision>:release`, `<checkId>:<revision>:failed`.
  Each leg is looked up before it moves, so a retry never moves twice.
- Random sampling persists every roll (`QcSamplingDecision`), selected or not; periodic sampling also records its
  due / not-due decision per base key, so re-syncing a document never changes whether it has a check.

## Implementation notes (QC3)

- Migration V661: `QcAlertStage`, `QcTag`, `QcRootCause`, `QcAlert`, `QcAlertTag`, `QcAlertStageHistory`,
  `QcAlertAssignmentHistory` (the two history tables are append-only by trigger), standard RLS on all seven.
- Default stages (New 10, Confirmed 20, Action Proposed 30, Solved 40 closed) are created on first use. A stage that
  still holds alerts cannot be deactivated. Moving to a closed stage sets `closedAt`; moving out clears it.
- Auto alert: `QcCheckService.execute` calls `QcAlertService.autoForFailedCheck` in the same transaction when a
  generated check fails and its point has `autoAlert`. The insert is `ON CONFLICT ("organizationId", "checkId") WHERE
  origin = 'auto' DO NOTHING`; the alert number is taken only after a row was inserted, so a reset + second failure
  neither duplicates the alert nor burns a number. Priority 2 when the check blocks, else 1. Manual and correction
  checks never auto-alert.
- Escalation locks the alert row; an alert with `ncrId` returns that NCR (`created: false`). The NCR gets title
  `QCA-…: <title>`, source from the check operation (receipt → incoming, manufacturing / work order → in_process,
  delivery → final, otherwise internal), `quantityAffected` = the check's `failedQty`, root cause and proposed
  corrective / preventive actions in the description, owner = the alert's responsible. `QualityNcr` has no
  `externalRef` column, so the reverse link is shown through `GET /quality-control/alerts?ncrId=` (no change to the
  `quality` schema).
- A correction check can be linked to an alert only if it corrects the alert's check.
- Notifications (`RoutedEventSource`, area "quality"): alert created → responsible + team members; assigned →
  responsible; resolved (stage_changed to a closed stage) → creator. Alert scan type `quality_alert_open` (daily,
  threshold 3 days, weekly repeat; priority ≥ 2 is critical). `AlertsIT`'s catalogue count goes from 10 to 11.
- Audit: alerts use the existing `AuditAction` values (`create`, `update`) with `op` = `move` / `escalate`.

## Implementation notes (QC4)

- `SpcCalculator` (pure, `BigDecimal` / `DECIMAL64`): I-MR for individual values (σ̂ = MR̄/1.128, limits
  x̄ ± 3·MR̄/1.128, MR UCL = 3.267·MR̄); X̄-R for a constant subgroup size 2..10 (x̿ ± A2·R̄, D3·R̄ / D4·R̄, σ̂ = R̄/d2);
  varying sizes use σ̂ = mean(Rᵢ/d2(nᵢ)) and per-subgroup limits x̿ ± 3σ̂/√nᵢ. A2 / D3 / D4 are the values printed in
  the NIST/SEMATECH e-Handbook 6.3.2.1; d2 the standard values (d2(2) = 1.128 as in 6.3.2.2).
- **Reference validation (§12 acceptance)** in `SpcCalculatorTest`: the e-Handbook 6.3.2.2 flow-rate dataset
  (x̄ 50.81, MR̄ 1.8778, UCL 55.8041, LCL 45.8159, tolerance 0.0001), the 6.1.6 capability example (Cp 1.0, Cpu 0.6667,
  Cpl 1.3333, Cpk 0.6667) and the 6.3.2.1 constants table (A2 = 3/(d2·√n) agrees for n = 2..10). The e-Handbook
  pages have no raw X̄-R dataset, and no Montgomery dataset could be sourced verbatim, so X̄-R is verified against the
  printed formulas and constants on a constructed dataset — **an open item for the owner**: supply a reference X̄-R
  dataset (e.g. Montgomery's piston rings) if it must be validated against published raw data before production use.
- Data: the current execution of each SPC-included, non-cancelled measure check of the point; recorded sample values
  when present (in sample order), else the execution's value. Missing values and cancelled checks are counted in
  `excluded`. A change of the snapshot's toleranceMin / toleranceMax / norm starts a new segment; the latest segment
  is analysed unless `segment` is given.
- Subgroups: `individual`; `consecutive` (n values in time order, per work centre when split; the incomplete tail is
  excluded and counted); `batch`, `work_order` and `time_window` group by key (no key → excluded and counted), split
  into chunks of at most 10 values in time order. `spcSplitByWorkCenter` keeps work centres in separate subgroups.
- Limits are trial limits from all subgroups unless `baseline` = k (the first k subgroups). Provisional below 20
  subgroups (X̄-R) or 25 values (I-MR). Capability needs a spec limit, ≥ 30 values in the segment, no rule-1 signal in
  the baseline and σ̂ > 0 (else `undefined (zero variation)`); Cp only with both limits; Cpk = min(Cpu, Cpl).
- Western Electric rules 1–4 on the centre chart (zones from each point's own limits; rule 4 = 8 in a row), plus "R"
  for a range beyond its limits. Default rules 1 and 4.
- Summary / Pareto count checks by `doneAt` UTC day (inclusive range, default the last 90 days). Pass rate =
  passed ÷ (passed + failed); waived checks are reported but are not quality decisions.

## Phases

### QC1: Foundation

One PR (`feat/quality-control-qc1`), built through internal checkpoints. A checkpoint is a commit with its targeted
tests green.

**Step 0: preflight**
- Re-run the cross-branch check for ADR and migration numbers.
- Create the worktree off a freshly fetched `origin/main`.
- Write memories: a project memory with the owner decisions and phase gates, and a feedback memory to verify ADR and
  migration numbers across all branches.

**QC1-A: database and domain**
- `V660__quality_control.sql` creates `QcTeam`, `QcTeamMember`, `QcControlPoint`, `QcControlPointVersion`,
  `QcWorksheetTemplate`, `QcFailureClass`, `QcCheck`, `QcCheckExecution`, `QcCheckSample`, `QcCheckEvent`,
  `QcGenerationCounter`, `QcSamplingDecision` and `QcPointSchedule`.
  - `QcCheck` columns include `origin`, `phase`, `generationKey`, `controlPointVersionId`, the snapshot JSONB,
    `coveredQty` / `testQty`, the quarantine fields, `sourceClosed`, `correctsCheckId` and `includeInSpc`.
  - Also: CHECK constraints for enums, the partial unique generation index, org indexes, the standard RLS policy, and
    the `Permission` rows plus the Administrator grant (V560 pattern).
  - The QC2 tables are created now so the schema is reviewed once. The alert tables come in QC3 (V661+).
- Hand-written entities with `@Filter` tenancy (as in `quality/domain/QualityNcr.java`).
- Permissions in `permissions.ts`, then regenerate `Permissions.java`.
- `QcDb` (modelled on `quality/application/QualityDb.java`).
- Gate: compile, plus `ApplicationIT` and `ModularityTests`.

**QC1-B: configuration**
- `QcTeamService`.
- `QcControlPointService` (CRUD, versioning, deactivation).
- `QcWorksheetService` (versioned templates).
- `QcFailureClassService`.
- `QcBlockingPolicy` (pure; its unit tests cover all four combinations, even though QC2 is its first caller).
- `TestQuantityCalculator` (pure).
- Controllers with `@RequirePermissions`.

**QC1-C: check engine**
- `QcCheckService`:
  - manual and correction checks;
  - execution per type, each writing a `QcCheckExecution` revision: instruction acknowledgement, pass, fail with a
    failure class, measure with samples (out of tolerance with `confirm=false` returns the evaluation without saving,
    for the "Correct / Confirm" step), picture (needs an attachment) and worksheet (`WorksheetEvaluator`);
  - waive with a disposition;
  - reset opening a new revision;
  - source-closed guards;
  - row locks;
  - the `QcCheckEvent` log and audit.
- `QcResourceProvider` with per-resource write rights.

**QC1-D: web**
- `features/quality-control/api.ts`.
- Pages:
  - Overview.
  - Control Points list and detail, with version history.
  - Teams.
  - Worksheet Templates: a field builder with versions.
  - Failure Classes.
  - Checks list and detail: execution panel per type, tolerance dialog, sample entry, photo upload, worksheet form,
    execution revisions and event history.
- `routes.tsx` mounted in `App.tsx`, plus the nav entry.

**QC1-E: verification**
- Unit tests: `WorksheetEvaluatorTest`, `MeasureEvaluationTest`, `TestQuantityCalculatorTest` (rounding, minimum
  sample, per-line, the 100/200/300 example) and `QcBlockingPolicyTest` (four combinations, plus waived, cancelled and
  manual checks).
- `QualityControlIT extends ApiTestSupport` covers:
  - CRUD and versioning;
  - every check type, including samples;
  - re-executing a completed check returns 409;
  - two concurrent executions, where exactly one wins;
  - reset creates revision 2 and revision 1 stays intact;
  - waive or reset without `manage` returns 403, and a waiver without a reason or disposition returns 400;
  - the source-closed guard, with a correction check left as an independent record (`sourceClosed` set directly in
    the test);
  - a template edit leaves an existing check pinned to its version;
  - a deactivated point keeps its checks;
  - a duplicate active generation key is rejected by the DB, and a cancelled key can be reused;
  - concurrent `QcGenerationCounter` allocation gives distinct seqs;
  - control-point attachment write with only `check` returns 403;
  - cross-tenant reads and attachment downloads are denied;
  - `assertAuthorizationMatrix("com.inventoryerp.qualitycontrol")` with its route count.
- Web: run the repo's tsc and lint scripts, then smoke-test in org **DEMO**.
- Docs: `docs/adr/ADR-S087-quality-control.md` (§1–§13, phases and gates, alternatives rejected),
  `docs/spec/quality-control.md`, and a `docs/PORT_STATUS.md` section titled "Quality control (ADR-S087,
  extension)".

## Verification commands (QC1)
- In the worktree: `cd backend && ./gradlew compileJava compileTestJava -q`, once per checkpoint batch.
- Use a dedicated or reset DB (see the fastTest reused-DB memory), then run:
  `./gradlew fastTest --tests '*QualityControlIT' --tests '*WorksheetEvaluatorTest' --tests '*MeasureEvaluationTest'
  --tests '*TestQuantityCalculatorTest' --tests '*QcBlockingPolicyTest' --tests '*ApplicationIT'
  --tests '*ModularityTests' --tests '*ArchitectureTests'`.
- Run `./gradlew spotlessApply` before each commit.
- **Never** run `./gradlew build`, `check` or a bare `test`. The done report lists the tests that ran and says the full
  build did not.
- **DEMO smoke test:**
  1. Create a measure point and a worksheet template.
  2. Run a manual check with two samples, one out of tolerance, and confirm it, so it fails with a failure class.
  3. Try to re-execute it and expect 409.
  4. Reset it as a manager, which opens revision 2.
  5. Pass it.
  6. Waive another check with a disposition.
  7. Expect zero console errors throughout.
- Commit `feat(quality-control): QC1 foundation …` and open the PR. Continue to QC2; there is no merge or deploy.

### QC2: Triggers, blocking and quarantine (branch `feat/quality-control-qc2`, stacked on qc1)

**Backend:**
- `QcCheckGenerator` (pure): matching, `controlPer`, frequency, quantity reconciliation per §4.
- `QcGate` (named interface, `MANDATORY` propagation), `QcSourceDoc`, `QcQuarantineService` (§7 rules, built on
  `StockService.transfer` and `VirtualLocationService.ensure`).
- Hook calls at the §6 hook points (manufacturing, procurement, sales), 2 to 6 lines each.
- If needed, a PO → draft GRN path for `pre_receipt` mode.
- Verify the PR #25 cash-sale direct-invoice delivery path, and either hook it or document the exclusion in the ADR.
- Route `POST /quality-control/checks/:id/quarantine/retry` and a "Quarantine exceptions" query.
- Sample-quantity enforcement for generated `quantity` checks.
- Migration V662 only if a QC1 column needs adjusting.

**Web:**
- A "Quality checks" panel with a pending-count badge on `ProductionOrderDetailPage`, GRN detail and delivery detail.
- A step-by-step operator flow on the shop-floor work-order view: instructions, then checks in sequence, then
  Complete becomes enabled.
- Blocked-action errors link to the pending checks.
- A Quarantine exceptions page.

**Tests:**
- `QcCheckGeneratorTest`: every `controlPer` × `frequency`, reconciliation rows, persisted random decision, periodic
  race.
- `QualityControlFlowIT`:
  - MO complete blocked, then allowed;
  - work order and shop-floor complete blocked;
  - draft GRN post blocked;
  - one-shot receive refused under `pre_receipt`;
  - `quarantine_on_receipt`: stock is unavailable until the check passes, the pass releases it atomically, and a fail
    keeps `failedQty` and releases the rest;
  - a forced release failure rolls back the pass;
  - a receipt whose quarantine move fails rolls back entirely;
  - delivery issue blocked, then waived with `quantity_excluded`;
  - a quarantine exception, then a retry;
  - duplicate and concurrent `sync` produce no duplicates;
  - a completed check survives a source edit;
  - a supplementary check is created on a quantity increase;
  - cancel cascade;
  - an org with no control points sees unchanged behaviour.
- Rerun the existing ITs of the hooked modules: `*Production*IT`, `*WorkOrder*IT`, `*ShopFloor*IT`,
  `*GoodsReceipt*IT`, `*PurchaseOrder*IT`, `*SalesDelivery*IT`.

### QC3: Alerts and teams (branch `feat/quality-control-qc3`, stacked on qc2)

**Backend:**
- `V661__quality_control_alerts.sql`: `QcAlert`, `QcAlertStage`, `QcAlertStageHistory`, `QcAlertAssignmentHistory`,
  `QcTag`, `QcAlertTag`, `QcRootCause`. Same RLS and index conventions.
- `QcAlertService`: CRUD, stage moves, assignment, the auto alert on failure (`ON CONFLICT DO NOTHING`), manual
  alerts from a check, a document or the shop floor, NCR escalation per §8 (idempotent), and the correction-check
  link.
- `QcNotificationRoutes` (`RoutedEventSource`) and `QcAlertAgingSource` (`AlertSource`).
- Events `quality_control.alert.*` added to `packages/shared/src/events.ts`.

**Web:**
- Alerts kanban using `@dnd-kit` (pattern: `web/src/pages/crm/PipelinePage.tsx`): priority sort, quick add, folded
  stages.
- Alert detail page with stage and assignment history.
- Settings pages for stages, tags and root causes.
- A "Quality alert" button on failed checks and on the document panels.

**Tests:** `QualityControlAlertsIT`:
- stage and assignment history;
- one auto alert under retry;
- closing an alert leaves its check unchanged;
- repeated NCR escalation returns the same NCR;
- notification routing;
- tenant isolation;
- alert-evidence write needs `alert`;
- the matrix route count.

### QC4: SPC and statistics (branch `feat/quality-control-qc4`, stacked on qc3)

**Backend:**
- `SpcCalculator` (pure, `BigDecimal`): the §12 subgroups, X̄-R / I-MR, baseline, provisional limits, capability
  rules, zero variation, Western Electric rules 1–4.
- `QcStatisticsService` with routes `GET /quality-control/statistics/summary`, `/statistics/pareto` and
  `/statistics/spc`.

**Web:** a Statistics page using `recharts`:
- pass-rate trend;
- Pareto chart by failure class and by control point;
- control chart with CL / UCL / LCL and USL / LSL drawn separately, flagged points and a provisional badge;
- capability tiles with a reason when the result is invalid.

**Tests:**
- `SpcCalculatorTest` against published reference datasets: the NIST/SEMATECH e-Handbook and Montgomery examples for
  X̄-R, I-MR and Cp/Cpk, expected values within a stated tolerance.
- Further cases: insufficient data, zero variation, a spec-version split, excluded values.
- `QualityControlStatsIT`.

