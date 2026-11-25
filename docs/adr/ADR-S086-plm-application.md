# ADR-S086 — PLM as a standalone application (Odoo-style), versions and ERP integration

- Status: accepted (built 2026-11-24, branch `feat/plm-app`; owner decisions of 2026-11-24 in §12, integrated with
  the manufacturing integrity fixes of PR #39 in §13)
- Builds on: [ADR-S063](ADR-S063-product-lifecycle-management.md) (lifecycle, ECR/ECO, stages, dispositions,
  documents), [ADR-S006](ADR-S006-manufacturing-production.md) package D (BOM versions), ADR-S008 (standard cost),
  ADR-S013 (quality lots), ADR-S065 (notification routing).
- Migrations: `V640__plm_types_tags.sql`, `V641__plm_revisions.sql`, `V642__plm_product_data_cost_policy.sql`,
  `V643__plm_settings_fai.sql`, `V644__plm_lifecycle_exceptions_fai_policy.sql` (owner decisions)
- Extension: no NestJS counterpart. Spec: [docs/spec/plm.md](../spec/plm.md).

## Context

ADR-S063 built a working PLM (lifecycle, change requests, staged change orders, dispositions, documents; 38 routes,
`PlmIT` green on `main` 785e3b81). An audit on 2026-11-24 against the owner's request ("PLM, ECOs, Versions, modular
like Odoo, integrated with accounting, inventory …") found these gaps:

| Area | Before | Gap |
|---|---|---|
| Modularity | PLM classes live inside the `manufacturing` Java module and are gated by `@RequiresModule("manufacturing")`; the web shows them as a group inside Production | Not an application of its own: no switch, no manifest, no dependency, no own menu. Manufacturing code calls PLM classes directly (production / BOM gates, ECO routes). |
| ECO types | One stage pipeline per organization | Odoo has ECO **types**, each with its own pipeline (e.g. "Engineering changes", "New product introduction"). |
| Tags, kanban state, comment-only reviewers | — | Odoo has tags, a blocked / ready flag and "comments only" approvers. |
| Versions | A revision letter on the PLM item and BOM version numbers | No record of what each revision **was** (BOM, routing, documents, product data, cost), no compare between revisions, no as-built trace (which revision an order produced), production orders cannot see that their BOM was superseded. |
| Conflicts | — | Applying an ECO whose base BOM was superseded meanwhile silently archives the newer version. |
| Product changes | A product ECO only moves the lifecycle state | Master data (name, description, barcode, weight, volume, image) cannot be changed under change control. |
| Accounting | Impact shows a unit-cost delta only | A BOM / routing change of a standard-costed product leaves the standard (and the stock value) stale; no financial impact view. |
| Inventory / purchasing / sales | Only production and BOM activation are gated | Obsolete or phased-out parts can still be bought, sold and auto-replenished. |
| Quality | — | No first-article inspection after a change. |
| Reporting | Overview counters | No change analysis (cycle time per type, time in stage), no obsolete-stock or stock-by-revision report. |

## Decision

### 1. `plm` is its own Spring Modulith module and its own application

**Code.** Everything PLM moves from `com.inventoryerp.manufacturing` to `com.inventoryerp.plm`
(`domain`, `application`, `web`; `PlmIT` to `com.inventoryerp.plm`). The module has its own row helper `PlmDb`
(the per-module convention: `MfgDb`, `SalesDb`, `CrmDb`, …). It depends on manufacturing (application, domain, spi),
inventory, procurement, quality, core and kernel; nothing depends on it.

Manufacturing no longer references PLM. Two SPIs in `manufacturing.spi` invert the old calls:

| SPI | Used by | PLM implementation | Without an implementation |
|---|---|---|---|
| `ProductionLifecycleGate` | `ProductionService.createOrder`, `BomService.activate` | `PlmGate` | no gate |
| `EngineeringChangeWorkflow` | `EngineeringService.revise` and the ADR-S006 routes `/engineering-changes/:id/approve`, `/reject`, `/apply` | `PlmChangeService` (stages, approvals, effectivity) | the original ADR-S006 behaviour (approve / reject / activate-and-apply) |

Cross-module lifecycle checks use the established core SPI pattern (`ProductStockGuard`): a new
`core.application.ProductLifecycleGuard`, implemented by PLM and consumed through `ObjectProvider` by procurement,
sales, back-office orders, invoicing and the POS (production asks it through `PlmGate`). No implementation means
legacy behaviour.

**Application switch (add-on semantics).** Manifests are registered with the `ModuleRegistry`: `manufacturing`
(new) and `plm`, an **add-on** of `manufacturing` (`ErpModule.addOn`). The module guard treats an add-on as on
wherever all of its dependencies are on, unless the organization switched it off (`OrganizationModule` row with
`isActive = false`). So existing tenants keep PLM exactly where they have manufacturing (no data migration), an
administrator can switch PLM off on the Modules screen, and switching manufacturing off switches PLM off with it.
Enabling an add-on whose dependency is off is refused: `Module 'plm' requires 'manufacturing'`. Only manifests that
declare dependencies get this check, so every Nest-ported module keeps its exact behaviour.

The kernel's module decision moves from `AccessGuardInterceptor` into `kernel.modules.ModuleAccess` (same rules,
plus add-ons) so the guard, the Modules routes and PLM's own gates ask one place.

PLM routes carry `@RequiresModule("plm")`. When PLM is off for an organization its gates stop applying (production,
BOM activation, purchasing, sales, replenishment) and the effectivity job skips the organization: switching the
application off removes its behaviour, as uninstalling an Odoo app does.

**Licence.** PLM is sold as part of manufacturing: `LicenseFeatures.featureForModule("plm")` is `manufacturing`, so
the licence ceiling is the same as before and no installed licence loses PLM.

**Deployment flag.** `app.modules.plm` (`ENABLE_PLM`, default `ENABLE_MANUFACTURING`) loads the PLM web layer and
manifest.

**Web.** PLM becomes a top-level sidebar section (Overview, Change orders, Change requests, Products, Documents,
Reports, Configuration) gated by the feature `plm`, which requires `manufacturing` and is licensed under it. The
Modules screen lists PLM with its dependency. API and screen paths stay under `/manufacturing/plm` (stored
notification links and API clients keep working).

### 2. ECO types, tags, kanban state, comment-only reviewers (V670)

- `PlmChangeType` (name, description, colour, sequence, default flag). Each type owns its stage pipeline
  (`PlmChangeStage.typeId`); stage names are unique per type; stages gain `folded` (kanban). Existing stages and
  ECOs are attached to a default type "Engineering changes" per organization. An organization without types gets
  the default type with New → Engineering review → Approved, as before.
- `EngineeringChange.changeTypeId` (the ECO type; the existing `type` stays the kind: bom / routing / product /
  document), `kanbanState` (`normal`, `blocked`, `ready`) and `blockedReason`. Changing the type of a draft ECO puts
  it in the first stage of the new type and voids its approvals.
- `PlmTag` + `PlmChangeTag`; ECO lists filter by type, tag and kanban state.
- Approver kind `comment`: listed as reviewer, may comment / approve / reject, never blocks a stage.
- `PlmChangeActivity.stageId` records the stage entered, for time-in-stage reporting.

### 3. Versions (V671)

- `PlmRevision`: one row per product revision — revision, `current` / `superseded`, effective from / to, the ECO
  that made it, and a snapshot of what it was: default active BOM (id, version), routing, released documents
  (number, version, revision, title), product data (code, name, description, barcode, weight, volume, UoM,
  category), unit cost (active standard, else cost price). Written when a product is registered (revision A) and on
  every revision bump; backfilled once for existing items.
- Revision codes are unique per product: a revision cannot be reused.
- Routes: list a product's revisions, read one (with its BOM lines), compare two (BOM lines and header, routing
  operations, documents added / removed / re-versioned, product data).
- **As-built** (`PlmAsBuilt`): when a production order of a PLM product completes (`manufacturing.order.completed`,
  after commit), PLM stores the revision it produced (the revision whose BOM the order used, else the revision current
  when the order was created), with the batch. Stock by revision: batches made by a recorded order are exact;
  batches no order recorded are `unknown`; stock without a batch is attributed to the most recent unbatched as-built
  orders first and labelled `estimated` (so the report never presents a guess as a fact).
- **Superseded BOM on open orders** (Odoo mrp_plm): `GET /orders/:orderId/bom-status` says whether the order's BOM
  is still the current version; `POST /orders/:orderId/update-bom` replaces a draft / confirmed order onto the current
  version with the ADR-S063 order replacement (same quantity, locations, schedule, pegs).
- **Conflicts and rebase.** A BOM ECO whose base version is no longer the current version cannot be applied:
  `Change ECO-… was raised against BOM-X v1, which is no longer the active version (BOM-X v2 is active); rebase ECO-…
  first.` `POST /changes/:id/rebase` does a
  three-way merge (base → current carried into the ECO's draft version, line by component and header field by
  field); a component changed on both sides keeps the ECO's value and is reported as a conflict. The merge is a pure
  function (`BomRebase`) with unit tests; the draft BOM is written through `BomService`.

### 4. Product master data under change control (V672)

A product ECO carries `productChanges` (whitelist: `name`, `description`, `barcode`, `weight`, `volume`, `image`) in
addition to, or instead of, a lifecycle target. Apply writes them through `ProductService.applyEngineeringChange`
(core API, same validation as a product edit for those fields) and bumps the revision when asked. The ECO shows
current → proposed values.

### 5. Accounting: standard cost and financial impact (V672)

`EngineeringChange.costPolicy` — `none`, `propose`, `activate` (null = the PLM setting, default `propose`) — applies
to BOM and routing changes of a product that is standard-costed (an active `ProductStandardCost`, or costing method
`STANDARD`):

- `propose`: on apply, the new default BOM's roll-up becomes a **draft** product standard (ADR-S008
  `proposeProductStandard`, source `bom_rollup`) for the normal approve / activate flow.
- `activate`: the same standard is approved and activated, which revalues the stock on hand with the ADR-S008
  journal (stock valuation against 5960 Standard Cost Revaluation). Naming `activate` needs `standard_cost:approve`
  (403 `costPolicy activate approves and activates a standard cost; it needs the standard_cost:approve permission (use
  propose).`); an organization default of `activate` is fitted to `propose` for a user without it. Segregation of
  duties is respected: when the applier may not approve their own standard, or activation is refused (closed period,
  …), the standard stays draft for a second person and the ECO records why. Engineering is never blocked by an
  accounting control, and an accounting control is never bypassed.

**Cost step and its fallback (owner decision B, 2026-11-25).** The cost step runs **after the apply commits**, never
inside it: an accounting refusal inside the apply transaction would mark it rollback-only (a savepoint cannot undo
that), so the ECO is applied first and its `costResult` says `status: pending`. After commit the step runs in its own
transactions, in two stages: (1) roll-up and draft standard, (2) approve + activate (activate policy only). The rules:

- A failure never undoes the applied ECO and never substitutes a cost. The roll-up is checked first: a component
  without a positive cost, or a roll-up that totals nothing, records `status: incomplete` (`… is incomplete: no cost
  for X.`) and **no standard is proposed** — no zero or partial cost reaches valuation.
- A stage that throws records `status: failed` with the reason (`Cost step failed: … No standard was proposed …` or
  `Activation refused: … Standard cost vN stays draft …`), a `failedAt` time, an ECO activity entry and an audit
  record (`kind: plm_cost_step_failed`, stage, reason). A standard proposed in stage 1 stays draft.
- Financial postings come only from a completed stage 2: the revaluation journal is written by the ADR-S008
  activation of a validated standard, nothing else.
- The status is visible: `costResult.status` on the ECO (pending, draft, active, incomplete, failed, skipped) and the
  change list filter `GET /manufacturing/plm/changes?costStatus=failed` for investigation. A step still pending
  after ten minutes (crash between commit and the after-commit step) is re-run by the effectivity job.

When `costPolicy` is not given, the organization default is fitted to the product: serial-tracked → `none`,
`activate` on a product not on standard cost → `propose`. Given explicitly, the same cases are refused
(`Product P is serial-tracked; standard cost does not apply.`, `Product P is costed at AVCO; only a product on
standard cost can have its standard activated by a change (use propose).`), and `costPolicy` on a non-BOM ECO is
refused (`costPolicy only applies to a BOM change.`).

The ECO stores `standardCostId`, `revaluationJournalId` and `costResult`. The impact view gains `financial`: whether
the product is standard-governed, current and proposed unit standard, on-hand quantity, the revaluation amount
(on hand × difference), and the value of stock the dispositions will scrap, quarantine or return. Products at AVCO /
FIFO are not revalued; the view says their future production cost changes by the roll-up delta.

**Who sees money (owner decision 4, §12).** Valuation figures are for users who may read inventory valuation
(`inventory_valuation:read`), the rule the stock valuation screens already follow, and the backend enforces it:
without the permission the figures are `null` and the response says `costVisible: false` — the financial view and
the reports; the ECO's `costResult` (policy, status, standard version and note remain); the impact view's unit-cost
delta and the unit cost of the stock its dispositions touch; and the unit cost recorded on revisions (list, read,
compare). The ECO activity log carries no figures (`Standard cost v3 activated; the stock on hand was revalued.`).
BOM inputs (overhead, subcontract cost per unit) are engineering data and stay visible wherever the BOM is, as on
the BOM screens.

### 6. Lifecycle policy across the ERP (V673, V674)

`PlmSettings` (one row per organization) holds a policy matrix — for each lifecycle state and activity (`purchase`,
`produce`, `sell`, `pos`, `replenish`), `allow` or `block` — plus defaults for new ECOs (cost policy, first-article
inspection). `GET /settings` returns the stored matrix merged over the defaults; `PUT /settings` changes only the cells
and defaults it is given, then stores the whole effective matrix (so a later change of the shipped defaults never
alters an organization that already saved its rules). The released row cannot be blocked
(`A released product is always allowed to be purchased, produced, sold and replenished.`).

Defaults (owner decisions 5 and 6, §12):

| State | purchase | produce | sell | pos | replenish |
|---|---|---|---|---|---|
| concept, design | allow | block | block | block | allow |
| prototype | allow | allow | block | block | allow |
| released | allow | allow | allow | allow | allow |
| phase_out | block | block | allow | allow | block |
| obsolete | block | block | block | block | block |

A product that has not completed its release is not sold, at the till or elsewhere; it may still be bought and
replenished (parts and samples for its development), and a prototype may be produced. A product being phased out
sells its existing stock but is no longer procured: not bought, not produced, not replenished. An obsolete product is
neither bought, produced, sold nor replenished. Each cell can be changed per organization.

**Authorized exceptions (V674).** A holder of `plm:exception` grants a `PlmLifecycleException`: one product under
PLM, one activity, a reason (required) and a last valid day (from today up to 366 days ahead on the organization's
calendar). While it is in force the policy treats that cell as allowed for that product. One exception per product
and activity can be in force at a time (`An exception for selling P-100 is already in force until 2026-12-31; revoke
it first.`; concurrent grants serialise on an advisory lock). Revoking ends it at once. Rows are never deleted: they
record who granted it, why and until when, and who revoked it and why. Both steps emit events. The product page shows
the effective rule per activity with the exception that lifts it (`GET /items/:id` gains `policy` and `exceptions`).
Routes: `GET /lifecycle-exceptions?productId&active`, `POST /lifecycle-exceptions`,
`POST /lifecycle-exceptions/:id/revoke`.

Enforced through `ProductLifecycleGuard` (`PlmLifecyclePolicy`): only for products registered in PLM, only while PLM
is on, and only for products **new to a document**. Lines already on a document stay editable, so nothing already
agreed is interrupted (owner decision 3).

| Activity | Checked at | Never checked |
|---|---|---|
| purchase | purchase request create; RFQ create (free lines); purchase order create, which also covers the requisition-to-order and RFQ-award conversions | receipts against placed orders; purchase order edits (header fields only); bills, supplier returns |
| produce | production order create (`ProductionLifecycleGate` → `PlmGate`), which also covers firming MRP suggestions; BOM activation of an obsolete product stays refused (ADR-S063) | orders already created and everything that happens to them (release, consumption, completion, rework, scrap) |
| sell | sales quotation and sales order lines (a quotation converted to an order is checked again); products added or swapped in by a sales-order change request (at request and approval); cash-sale drafts; back-office orders (`/orders`); customer invoices entered by hand (`InvoiceService.create` / `update`) | invoices copied from deliveries or orders, proformas from quotations, credit notes, deliveries, returns; contract, dunning and opening-balance invoices (account lines, no product) |
| pos | new lines on till orders (`/pos/orders`, checkout, tabs) and sales replayed from an offline till (`/sync/push`, owner decision A) | lines already on a ticket |
| replenish | purchase replenishment suggestions report blocked products with `needed: false` and `lifecycleBlocked: true`; raising purchase requests from alerts skips them and leaves their alerts open | — |

**Offline till sales (owner decision A, 2026-11-25).** Release rules apply to sales replayed from an offline till as
to online ones: the server re-validates every `sale.checkout` it replays. A refused replay commits nothing (the sale is
rolled back whole) and is quarantined as a `SyncOpDeadLetter` with `failureCode: LIFECYCLE_BLOCKED` and
`retryable: false`, so the device does not loop on it; online, the refusal carries the same `code`. The idempotency
and replay guarantees of ADR-S083 hold: a supervisor retries the held sale under its original `opId`
(`POST /sync/dead-letters/:id/retry`, "Retry sale" on the dead-letter screen), the server checks the rule again, the
sale posts exactly once, and a later push of the same op replays the stored result. The counter checkout
(`/pos/checkout`) is checked too.

Stock moves, receipts, transfers, counts and adjustments are never gated. The inventory and manufacturing integrity
controls (valuation, row locks, negative-stock rules, the PR #39 fixes in §13) are unchanged.

The refusal names the state and the replacement product: `Product P-100 is obsolete and cannot be purchased.
Replacement: P-200.`, `Product P-100 is in design and cannot be sold at the point of sale.`. Production keeps the
ADR-S063 wording (`Product P-100 is design in its lifecycle and cannot be produced.`).

### 7. Quality: first-article inspection (V673)

`EngineeringChange.firstArticle` (BOM / routing ECOs; default from settings, off unless the organization turns it on).
Applying such an ECO sets
`faiStatus = pending`. The first production order on the new version that completes (`completed` or `qc_hold`; for a
BOM ECO an order on the new BOM, for a routing ECO an order of the product created after the apply) opens a
final-stage quality lot through `QualityLotService.openLotIfPlanned` (source `plm_fai`, idempotent per ECO) →
`open`, event `plm.fai.opened`. Without a quality plan the ECO records `no_plan` and the result is recorded by hand
(`POST /changes/:id/first-article`, `plm:approve`). The lot's evaluation (`quality.lot.evaluated`) or decision
(`quality.lot.decided`) closes the inspection: `passed`, `failed` or `cancelled`. The ECO shows the lot, the order
and the outcome; every step is written to the ECO chatter (activity kind `fai`). Outcomes are recorded even while PLM
is switched off, so a lot opened earlier never stays dangling.

**Mandatory inspection (V674, owner decision 2).** Inspection stays optional by default. `PlmSettings.firstArticleRequired`
makes it mandatory for every BOM / routing change of the organization, `PlmChangeType.firstArticleRequired` for the
changes of one ECO type. A mandatory inspection cannot be opted out of: an explicit `firstArticle: false` is refused
(`A first-article inspection is mandatory for changes of type New product introduction.`), anything else sets it. A
change raised before the policy became mandatory is inspected anyway when it is applied.

### 8. Reports

- `/reports/changes?from&to&changeTypeId&groupBy` (default: the last 90 days in the organization's time zone):
  created / open / applied / cancelled / blocked, average and p90 cycle time (created → applied), average time to
  approval, first-pass rate (applied without a rejection or a rebase), product data changes, standards created,
  revaluations and their amount, rebases, first-article outcomes, counts by kind, and rows grouped by month, ECO
  type, stage, owner or priority.
- `/reports/obsolete-stock?state`: phase-out / obsolete products with on-hand quantity, value (method-correct, from
  `InventoryValuationService`), open purchase quantity and orders, open sales quantity, active BOMs still using the
  product, replacement and the date of the state change.
- `/reports/stock-by-revision?productId`: see §3.

### 9. Permissions and events

One new permission, `plm:exception`: grant and revoke lifecycle exceptions (V674 gives it to the Administrator roles,
as V440 did for the other PLM keys). Otherwise configuration (ECO types, tag edits, settings) uses `plm:manage`;
versions, reports, settings and exceptions read `plm:read`; rebase, kanban state and new tags `plm:change`; the manual
first-article result `plm:approve`; order BOM update and apply `plm:apply`. New events (outbox, `PlmEventPayload`):
`plm.revision.created`, `plm.change.rebased`, `plm.fai.opened`, `plm.lifecycle.exception_granted`,
`plm.lifecycle.exception_revoked`.

### 10. Demo data

`SEED_PLM_DEMO=true` (seed profile, empty database; `PlmDemoSeedContributor`, after the manufacturing demo) adds to
`DEMO` an office desk `PLM-DESK` with an active BOM, its parts under lifecycle management (steel leg being phased out
for an aluminium prototype), an ECO type "Product data", three tags, an open change request and two draft ECOs (a BOM
change with its draft version, a product data change). Off by default, like the other demo seeds.

### 11. Limitations (accepted)

- Rebase cannot clear a BOM header field that was emptied upstream (`BomService.update` ignores nulls), and the ECO's
  draft keeps its version number, so after a rebase version numbers may not follow apply order.
- MRP still suggests blocked products; firming the suggestion is refused with the policy's reason (no planning-time
  flag).
- An offline till can ring up a product it has synced before the server learns it is blocked; the replay is refused
  (owner decision A), so the customer has the goods and the sale waits as a `LIFECYCLE_BLOCKED` dead letter until a
  supervisor releases the product (or grants an exception) and retries it, or discards it.
- Stock by revision is exact only for batch-tracked output.

### 12. Owner decisions (2026-11-24)

The owner reviewed the defaults this ADR first proposed (lifecycle gates on phase-out / obsolete only, POS and
receipts never gated, cost policy propose, first article off, money masked, demo opt-in) and decided:

| # | Decision | Implemented as |
|---|---|---|
| 1 | Cost changes default to **propose**, not activate. | `defaultCostPolicy` defaults to `propose` (V674 column default; no settings row means propose); `activate` stays an explicit choice and falls back to propose for a product not on standard cost (§5) |
| 2 | First-article inspection is **optional** by default, with support for mandatory policies. | §7: off by default; `firstArticleRequired` on the settings and on ECO types (V674) |
| 3 | Existing POS sales and receiving against already-approved purchase orders are not unexpectedly interrupted. | §6: only products new to a document are checked; lines already on a ticket and receipts are never checked. Offline replays are checked since owner decision A (§6, below) |
| 4 | Financial and inventory valuation costs stay protected by backend authorization. | §5: `inventory_valuation:read`, now also on the ECO cost result, the impact view, dispositions and revisions; no figures in the activity log |
| 5 | Products that have not completed their release are blocked from **new sales** by default; released products sell normally. | §6: concept, design and prototype block `sell` and `pos` |
| 6 | Phase-out products sell existing stock but are not procured by default; obsolete products block new sales and purchasing, subject to **authorized exceptions**. | §6: phase-out blocks purchase, produce and replenish; obsolete blocks every activity; exceptions with `plm:exception` (V674) |

Readings submitted for review with the implementation:

- "Procurement" of a phase-out product includes making it: `produce` is blocked for phase-out by default (it was
  allowed before), as purchasing is.
- "Consistent across POS": the till follows the sales rules for new lines (decisions 5 and 6), while decision 3 keeps
  sales already rung up untouched (offline sales are checked on replay since decision A). The `pos` column can be relaxed on its own (e.g. prototypes
  sold as samples at the till).
- Concept and design products may still be bought (development parts) and replenished, and a prototype run may be
  produced: the decisions restrict sales and the procurement of retiring products, not development.
- Sales: a quotation converted to a sales order is checked again (the order is the sale); invoices copied from a
  delivery or an order are not (the sale was already made).
- Unchanged: the PLM demo seed is opt-in (`SEED_PLM_DEMO`).

### 13. Integration with the manufacturing integrity fixes (PR #39)

PR #39 (`fix/mfg-integrity`, ML-1 … ML-11) was merged to `main` first; this ADR's move of the PLM code was rebased
onto it and keeps every fix, in the PLM module or, where manufacturing must hold the rule with PLM switched off, in
manufacturing:

- apply, automatic apply, cancel and the direct approval lock the ECO row and re-check its status;
- approval freezes content: the draft BOM of an approved ECO refuses edits (`BomService`, which asks PLM through
  `EngineeringChangeWorkflow.draftEdited` for drafts), and so do its attached draft documents
  (`PlmDocumentService`); editing a draft ECO after an approval voids the approvals and
  sends it back to its first approval stage; a rebase rewrites the approved BOM, so it withdraws the approval too;
- applying a change whose base version is no longer active is refused (the §3 conflict wording names both versions);
- `plm.allowSelfApproval` (default false): nobody approves their own ECO, on the PLM routes and on the ADR-S006
  fallback routes alike;
- the last stage requiring approval is enforced; approval cannot be switched off on a stage with waiting ECOs
  (`plm.application.PlmStageService`);
- the effectivity job acts as the system actor and records a failure reason on the ECO;
- optional `limit` / `offset` on PLM items, item sub-lists and the legacy ECO list;
- V630 gives the PLM tables of V440 the standard RLS policy.

## Consequences

- Manufacturing works with PLM absent: the ADR-S006 routes fall back to their original behaviour.
- `@RequiresModule("plm")` replaces `@RequiresModule("manufacturing")` on PLM routes; with add-on semantics the
  outcome is identical until an organization switches PLM off.
- Purchasing, production, sales, back-office orders, manual invoices and new till lines of PLM-registered products can
  now be refused by policy; organizations not using PLM are unaffected (parity harness scenarios never register PLM
  items). Replenishment suggestions gain `lifecycleBlocked`.
- `costPolicy = activate` posts a revaluation journal at apply time, inside the apply transaction.
- ECO responses gain fields; no existing field or message changes, except the new conflict refusal on apply.
