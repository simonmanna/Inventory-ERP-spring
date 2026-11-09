# ADR-S063 — Product lifecycle management (PLM)

- Status: accepted (built 2026-11-09, branch `phase/plm-lifecycle`)
- Builds on: [ADR-S006](ADR-S006-manufacturing-production.md) package D (BOM versions, engineering change
  orders, compare, where-used) and ADR-S060 (multi-stage routings).
- Migrations: `V440__plm_product_lifecycle.sql`, `V441__plm_dispositions.sql` (apply queue + disposition result)
- Extension: no NestJS counterpart. Same module `com.inventoryerp.manufacturing`; routes under
  `/manufacturing/plm`; spec [docs/spec/manufacturing.md § ADR-S063](../spec/manufacturing.md).

## Context

ADR-S006 gave manufacturing BOM **versions** and a minimal **ECO**. You could revise a BOM into a draft version,
approve or reject it, apply it, and compare two versions. Several things were missing:

- **No product lifecycle.** You couldn't mark a product as still in design, a prototype, released, being phased out
  or obsolete. Nothing stopped production of a product that engineering had not released, or a new BOM using an
  obsolete part.
- **No change intake.** There was no record of why a change was asked for (cost, quality, customer, supplier,
  regulatory) or of who triaged it.
- **Single-step approval.** One `bom:activate` holder approved. There were no review stages, no named approvers and
  no rejection that blocks the change.
- **BOM-only changes.** Routings are immutable in the Nest contract (create / delete only), so a change of operations
  meant a new routing and manual re-pointing of BOMs. Product and document changes had no change record at all.
- **No effectivity, impact or dispositions.** Applying was immediate. Nobody could see open orders on the old
  version, stock of removed parts, the cost delta or the parent assemblies affected.
- **No controlled engineering documents.** There was no store for drawings, specifications or work instructions with
  versions, revisions and release status.

## Decision

### 1. Data model (V440)

| Table | Purpose |
|---|---|
| `PlmItem` | One per product under PLM: `lifecycleState` (concept, design, prototype, released, phase_out, obsolete), `revision` (A, B, …), owner, replacement product, released / obsolete timestamps. Unique per org × product. |
| `PlmLifecycleEvent` | History: `registered`, `transition` (from → to), `revision` (from → to), with the ECO that caused it. |
| `PlmChangeStage` (+ `PlmChangeStageApprover`) | The org's ECO pipeline, ordered by `sequence`. `requiresApproval` per stage; approvers are users, `required` or `optional`. **The last stage means approved.** Defaults New → Engineering review (approval) → Approved are created the first time an org touches ECOs. |
| `PlmChangeRequest` | ECR: `ECR-00001`, reason code, priority, due date, status new → accepted / rejected → converted, link to the ECO. |
| `EngineeringChange` (extended) | `type` (bom, routing, product, document), `stageId`, `priority`, `description`, `productId`, `requestId`, `routingId` / `newRoutingId`, `targetLifecycleState`, `newRevision`, `effectivity` (asap / date) + `effectiveDate`, `stockDisposition`, `wipDisposition`, `ownerId`, `rejectedReason`. `bomId` / `newBomId` become nullable; existing rows get `productId` from their BOM. |
| `PlmChangeApproval` | Approval decisions per ECO × stage × user; `voided` when the ECO is moved back before that stage. |
| `PlmChangeActivity` | ECO log: created, comment, stage, approval, status, applied, edited. |
| `PlmDocument` | Engineering documents: `DOC-00001` × `version`, `revision`, `docType`, status draft → released → superseded / obsolete; file in `/files` (`fileId`) and/or an external `url`. |

Six new permissions are granted to every `Administrator` role in the migration: `plm:read`, `plm:manage` (items,
documents, stages), `plm:request` (ECRs), `plm:change` (create and edit ECOs, move stages, convert ECRs),
`plm:approve` (ECO decisions, ECR review) and `plm:apply` (apply and cancel ECOs).

### 2. Lifecycle and gates

Allowed transitions: concept → design / obsolete; design → concept / prototype / released / obsolete;
prototype → design / released / obsolete; released → phase_out / obsolete; phase_out → released / obsolete;
obsolete → design. Further rules:

- **Release** needs an active BOM when the product has any BOM.
- **Obsolete** is refused while the product is a component of an active BOM whose output is not obsolete, or while
  it has open production orders.

Gates apply **only to products registered in PLM**, so organizations that don't use PLM keep ADR-S006 behaviour
exactly (`PlmGate`):

- **Production orders** (`ProductionService.createOrder`) are refused for concept / design / obsolete products.
  Prototype runs are allowed.
- **BOM activation** is refused for an obsolete output or when any component is obsolete.

`POST /items/register-active` registers every product with an active BOM as released, revision A, so an existing
catalogue can be onboarded in one step.

### 3. Change orders

- **bom**: the ADR-S006 `revise` (unchanged clone semantics). Only one open ECO per BOM.
- **routing**: an inactive copy `"<name> (ECO-…)"`, including the ADR-S060 stage set-ups matched by operation
  sequence. Its operations are edited with `PUT /changes/:id/routing-operations` while the ECO is a draft, because
  routings have no edit route in the Nest contract. Apply activates the copy, re-points every non-archived BOM from
  the old routing and deactivates the old one.
- **product**: a lifecycle transition, validated when the ECO is created or edited and again at apply.
- **document**: draft documents attached to the ECO. They cannot be released on their own while the ECO is open.

**Stages.** Moving forwards through a stage that requires approval needs that stage's approvals to be complete:

- every required approver approved, or, when the stage has no named approvers, at least one `plm:approve` holder;
- no rejection outstanding;
- approval stages cannot be skipped.

The approval that completes a stage advances the ECO one stage. Reaching the last stage sets `status = approved`.
Moving backwards voids the approvals of the target stage and every later stage, and an approved ECO returns to
draft.

The ADR-S006 routes keep their paths, permissions and messages:

- `/engineering-changes/:id/approve` fast-tracks a draft to the last stage, recording the approval.
- `/reject` cancels it.
- `/apply` uses the PLM apply.

**Apply** happens in this order:

1. **Waits** when the ECO is not ready: its effective date is still in the future on the **organization's own
   calendar** (`Organization.timezone`, UTC fallback), or, for a BOM change with `stockDisposition=use_up`, stock of a
   removed component is still on hand. The ECO is then queued (`applyQueued=true`), the response (201) carries
   `pending` with the reason, and `PlmEffectivityJob` (hourly at :05, ShedLock) applies it when the reason clears,
   each ECO in its own transaction. `{"force": true}` switches over now despite unused stock; it never overrides the
   effective date. The legacy `/engineering-changes/:id/apply` forces.
2. Performs the type-specific action, then (BOM changes) runs the stock and WIP dispositions (§3.1).
3. Releases the attached draft documents.
4. Bumps the product's PLM revision: the explicit `newRevision`, or the next letter for bom / routing / document
   changes. Product changes bump only when `newRevision` is given.
5. Logs the change and publishes `plm.change.applied`.

**Cancel** soft-deletes the draft BOM version and the inactive routing copy, and detaches draft documents.

**Impact** (`GET /changes/:id/impact`) reports:

- open production orders on the old BOM (or the product);
- removed components, with on-hand stock and other active uses (obsolescence risk = stock but no other use);
- added components, with their lifecycle state;
- changed lines;
- the unit-cost delta from two roll-ups, run in their own read-only transaction so a roll-up error can't doom the
  request;
- BOMs a routing change will re-point, the where-used tree, and a list of warnings.

The impact view also carries the `dispositionPlan` (what apply will do, per component × location and per open
order) and `pending`.

#### 3.1 Dispositions (V441)

They apply to BOM changes, for **removed components** (a component still used by another active BOM is kept and
listed, because its stock is still needed). Stock counts per location, excluding transit and `QC-QUARANTINE`; cost is
the running average (fallback cost price).

| `stockDisposition` | On apply |
|---|---|
| `use_up` | Wait until the stock is consumed (queue, above). Forced: nothing moves, skipped entries say what is still on hand. |
| `scrap` | One **draft** stock adjustment per location counting the components to zero (reason `other`). |
| `rework` | One **draft** immediate transfer per location into the QC quarantine location. |
| `return_to_supplier` | One **draft** inbound debit note (`returned_goods`) per supplier × location, at unit cost, to the preferred `SupplierProduct` supplier; components without one are skipped ("no supplier on file"). |
| `none` | Nothing. |

Documents are created as drafts so the normal inventory / procurement approval and posting rules (and GL) still apply.

| `wipDisposition` | On apply |
|---|---|
| `continue` | Open orders finish on the old BOM. |
| `update` / `rework` | Draft and confirmed orders on the old BOM are **replaced**: the old order is cancelled ("Replaced under ECO-…"), a new one is created on the new BOM with the same quantity, locations, schedule, batch, request and plan, demand pegs and plan lines are re-pointed, and it is confirmed again if the old one was. |
| `rework` | In-progress orders also get `qcRequired=true` and a note. |

Multi-stage and multi-level (pegged) orders are kept and listed for manual handling. The outcome is stored in
`EngineeringChange.dispositionResult` (documents, replaced / flagged / kept orders, skipped) and summarised in the
activity log.

### 4. Events

`plm.change.created`, `plm.change.stage_changed`, `plm.change.approved`, `plm.change.applied`, `plm.request.created`
and `plm.lifecycle.changed` go through the outbox with an `organizationId` payload (`PlmEventPayload` in
`packages/shared`).

### 5. Web

`/manufacturing/plm` (overview), `/products` (+ `/:id` cockpit), `/requests`, `/changes` (kanban by stage, + `/:id`),
`/documents` and `/settings` (stages and approvers). The nav has a "PLM" group, and the old Engineering & Costing page
links to the change board.

## Consequences

- ECO responses gain fields. Existing fields and messages are unchanged.
- ECO statuses keep the ADR-S006 vocabulary (draft, approved, applied, rejected); the stage carries the review detail.
  "Cancel" stores `rejected`.
- `POST /boms/:id/revise` now refuses a second open ECO on the same BOM:
  "BOM X already has an open change ECO-…".
- `POST /changes/:id/apply` can return an approved (queued) ECO instead of an applied one; clients check `status`
  and `pending`.
- Disposition documents are drafts: someone still posts the scrap adjustment, the quarantine transfer or the debit
  note. Multi-stage / pegged orders are not replaced automatically.
- Effectivity is per calendar day in the org time zone; the job runs hourly, so a switch-over happens within the hour
  after local midnight (or after the last stock is consumed).
