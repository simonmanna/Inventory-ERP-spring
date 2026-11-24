# Spec — `plm` (product lifecycle management)

Extension: **no NestJS counterpart** (no parity scenarios; organizations that never register a PLM item see no
behaviour change). Decisions: [ADR-S063](../adr/ADR-S063-product-lifecycle-management.md) (lifecycle, change requests,
staged change orders, dispositions, documents) and [ADR-S086](../adr/ADR-S086-plm-application.md) (standalone
application, ECO types, versions, ERP integration). Route status: [PORT_STATUS.md](../PORT_STATUS.md), section
"Product lifecycle management".

## Module

- Package `com.inventoryerp.plm` (`domain`, `application`, `web`). It depends on manufacturing (`application`,
  `domain`, `spi`), core, inventory, procurement, quality, accounting (`InventoryValuationService`, standard cost) and
  kernel; no module depends on it.
- Manufacturing talks to PLM only through `manufacturing.spi`: `ProductionLifecycleGate` (production order create, BOM
  activation) and `EngineeringChangeWorkflow` (ECO revise / approve / reject / apply). Without PLM those fall back to
  the ADR-S006 behaviour.
- Purchasing and sales consult `core.application.ProductLifecycleGuard` (implemented by `PlmLifecyclePolicy`) through
  `ObjectProvider`; absent or switched off = no check.
- Application switch: manifest `plm`, an **add-on** of `manufacturing` (`ModuleRegistry`, `ModuleAccess`). On wherever
  manufacturing is on unless the organization switched it off on the Modules screen. Licensed under the
  `manufacturing` feature. Deployment flag `ENABLE_PLM` (default `ENABLE_MANUFACTURING`). Routes carry
  `@RequiresModule("plm")`; when PLM is off its gates, guard, subscribers and effectivity job stand down.
- API and screen paths stay under `/manufacturing/plm` (stored links keep working).

## Concepts

| Concept | Table | Notes |
|---|---|---|
| PLM item | `PlmItem` | A product under lifecycle management: revision, state (`concept`, `design`, `prototype`, `released`, `phase_out`, `obsolete`), owner, replacement. History in `PlmLifecycleEvent`. |
| Change request (ECR) | `PlmChangeRequest` | Proposal reviewed (accept / reject) and converted into an ECO. |
| Change order (ECO) | `EngineeringChange` | Kind (`type`): `bom`, `routing`, `product`, `document`. ECO type (`changeTypeId`) with its own stage pipeline, tags, kanban state, effectivity, dispositions, cost policy, first article. |
| ECO type | `PlmChangeType` | Own pipeline (`PlmChangeStage.typeId`); one default type per organization ("Engineering changes"). |
| Stage / approvers | `PlmChangeStage`, `PlmChangeStageApprover`, `PlmChangeApproval` | Approver kind `required`, `optional`, `comment` (never blocks). Stages may be `folded` on the board. |
| Tag | `PlmTag`, `PlmChangeTag` | Free classification of ECOs. |
| Activity | `PlmChangeActivity` | Chatter: comments, stage moves (`stageId`), approvals, FAI (`fai`). |
| Revision | `PlmRevision` | Snapshot of what a revision was (BOM, routing, documents, product data, unit cost); compare two. |
| As-built | `PlmAsBuilt` | Which revision a completed production order produced, with its batch. |
| Settings | `PlmSettings` | Lifecycle policy matrix, default cost policy, first-article default. |
| Document | `PlmDocument` | Engineering documents with versions, linked to ECOs. |

## Routes

ADR-S063 (38):

| Verb | Path | Permission | Status | Handler |
|---|---|---|---|---|
| GET | `/api/v1/manufacturing/plm/overview` | `plm:read` |  | PlmController.overview |
| GET | `/api/v1/manufacturing/plm/items` | `plm:read` |  | PlmController.items |
| POST | `/api/v1/manufacturing/plm/items` | `plm:manage` | 201 | PlmController.register |
| POST | `/api/v1/manufacturing/plm/items/register-active` | `plm:manage` | 201 | PlmController.registerActive |
| GET | `/api/v1/manufacturing/plm/items/:id` | `plm:read` |  | PlmController.item |
| PATCH | `/api/v1/manufacturing/plm/items/:id` | `plm:manage` |  | PlmController.updateItem |
| POST | `/api/v1/manufacturing/plm/items/:id/transition` | `plm:manage` | 201 | PlmController.transition |
| GET | `/api/v1/manufacturing/plm/stages` | `plm:read` |  | PlmController.stages |
| POST | `/api/v1/manufacturing/plm/stages` | `plm:manage` | 201 | PlmController.createStage |
| PATCH | `/api/v1/manufacturing/plm/stages/:id` | `plm:manage` |  | PlmController.updateStage |
| DELETE | `/api/v1/manufacturing/plm/stages/:id` | `plm:manage` |  | PlmController.deleteStage |
| PUT | `/api/v1/manufacturing/plm/stages/:id/approvers` | `plm:manage` |  | PlmController.approvers |
| GET | `/api/v1/manufacturing/plm/requests` | `plm:read` |  | PlmController.requests |
| GET | `/api/v1/manufacturing/plm/requests/:id` | `plm:read` |  | PlmController.request |
| POST | `/api/v1/manufacturing/plm/requests` | `plm:request` | 201 | PlmController.createRequest |
| PATCH | `/api/v1/manufacturing/plm/requests/:id` | `plm:request` |  | PlmController.updateRequest |
| POST | `/api/v1/manufacturing/plm/requests/:id/review` | `plm:approve` | 201 | PlmController.review |
| POST | `/api/v1/manufacturing/plm/requests/:id/convert` | `plm:change` | 201 | PlmController.convert |
| GET | `/api/v1/manufacturing/plm/changes` | `plm:read` |  | PlmController.changes |
| POST | `/api/v1/manufacturing/plm/changes` | `plm:change` | 201 | PlmController.createChange |
| GET | `/api/v1/manufacturing/plm/changes/:id` | `plm:read` |  | PlmController.change |
| PATCH | `/api/v1/manufacturing/plm/changes/:id` | `plm:change` |  | PlmController.updateChange |
| GET | `/api/v1/manufacturing/plm/changes/:id/impact` | `plm:read` |  | PlmController.impact |
| POST | `/api/v1/manufacturing/plm/changes/:id/stage` | `plm:change` | 201 | PlmController.moveStage |
| POST | `/api/v1/manufacturing/plm/changes/:id/decisions` | `plm:approve` | 201 | PlmController.decide |
| POST | `/api/v1/manufacturing/plm/changes/:id/comments` | `plm:read` | 201 | PlmController.comment |
| POST | `/api/v1/manufacturing/plm/changes/:id/documents` | `plm:change` | 201 | PlmController.linkDocuments |
| PUT | `/api/v1/manufacturing/plm/changes/:id/routing-operations` | `plm:change` |  | PlmController.routingOperations |
| POST | `/api/v1/manufacturing/plm/changes/:id/apply` | `plm:apply` | 201 | PlmController.apply |
| POST | `/api/v1/manufacturing/plm/changes/:id/cancel` | `plm:apply` | 201 | PlmController.cancel |
| GET | `/api/v1/manufacturing/plm/documents` | `plm:read` |  | PlmController.documents |
| GET | `/api/v1/manufacturing/plm/documents/:id` | `plm:read` |  | PlmController.document |
| POST | `/api/v1/manufacturing/plm/documents` | `plm:manage` | 201 | PlmController.createDocument |
| PATCH | `/api/v1/manufacturing/plm/documents/:id` | `plm:manage` |  | PlmController.updateDocument |
| POST | `/api/v1/manufacturing/plm/documents/:id/release` | `plm:manage` | 201 | PlmController.releaseDocument |
| POST | `/api/v1/manufacturing/plm/documents/:id/revise` | `plm:manage` | 201 | PlmController.reviseDocument |
| POST | `/api/v1/manufacturing/plm/documents/:id/obsolete` | `plm:manage` | 201 | PlmController.obsoleteDocument |
| DELETE | `/api/v1/manufacturing/plm/documents/:id` | `plm:manage` |  | PlmController.deleteDocument |

ADR-S086 (23):

| Verb | Path | Permission | Status | Handler |
|---|---|---|---|---|
| GET | `/api/v1/manufacturing/plm/change-types` | `plm:read` |  | PlmAppController.changeTypes |
| POST | `/api/v1/manufacturing/plm/change-types` | `plm:manage` | 201 | PlmAppController.createChangeType |
| PATCH | `/api/v1/manufacturing/plm/change-types/:id` | `plm:manage` |  | PlmAppController.updateChangeType |
| DELETE | `/api/v1/manufacturing/plm/change-types/:id` | `plm:manage` |  | PlmAppController.deleteChangeType |
| GET | `/api/v1/manufacturing/plm/tags` | `plm:read` |  | PlmAppController.tags |
| POST | `/api/v1/manufacturing/plm/tags` | `plm:change` | 201 | PlmAppController.createTag |
| PATCH | `/api/v1/manufacturing/plm/tags/:id` | `plm:manage` |  | PlmAppController.updateTag |
| DELETE | `/api/v1/manufacturing/plm/tags/:id` | `plm:manage` |  | PlmAppController.deleteTag |
| POST | `/api/v1/manufacturing/plm/changes/:id/kanban-state` | `plm:change` | 201 | PlmAppController.kanbanState |
| POST | `/api/v1/manufacturing/plm/changes/:id/first-article` | `plm:approve` | 201 | PlmAppController.firstArticle |
| GET | `/api/v1/manufacturing/plm/changes/:id/financial` | `plm:read` |  | PlmAppController.financial |
| POST | `/api/v1/manufacturing/plm/changes/:id/rebase` | `plm:change` | 201 | PlmAppController.rebase |
| GET | `/api/v1/manufacturing/plm/items/:id/revisions` | `plm:read` |  | PlmAppController.revisions |
| GET | `/api/v1/manufacturing/plm/revisions/compare` | `plm:read` |  | PlmAppController.compareRevisions |
| GET | `/api/v1/manufacturing/plm/revisions/:id` | `plm:read` |  | PlmAppController.revision |
| GET | `/api/v1/manufacturing/plm/items/:id/as-built` | `plm:read` |  | PlmAppController.asBuilt |
| GET | `/api/v1/manufacturing/plm/reports/changes` | `plm:read` |  | PlmAppController.changeReport |
| GET | `/api/v1/manufacturing/plm/reports/obsolete-stock` | `plm:read` |  | PlmAppController.obsoleteStock |
| GET | `/api/v1/manufacturing/plm/reports/stock-by-revision` | `plm:read` |  | PlmAppController.stockByRevision |
| GET | `/api/v1/manufacturing/plm/settings` | `plm:read` |  | PlmAppController.settings |
| PUT | `/api/v1/manufacturing/plm/settings` | `plm:manage` |  | PlmAppController.saveSettings |
| GET | `/api/v1/manufacturing/plm/orders/:orderId/bom-status` | `plm:read` |  | PlmAppController.orderBomStatus |
| POST | `/api/v1/manufacturing/plm/orders/:orderId/update-bom` | `plm:apply` | 201 | PlmAppController.updateOrderBom |

The ADR-S006 routes `/manufacturing/engineering-changes/:id/approve|reject|apply` run through
`EngineeringChangeWorkflow` (PLM stages and approvals) when PLM is on.

## Behaviour (ADR-S086)

**ECO types and kanban.** Each type gets the default pipeline (New → Engineering review → Approved) when created.
Changing the type of an open ECO moves it to the new type's first stage, voids its approvals and resets the kanban
state. `kanbanState` `blocked` takes an optional `blockedReason`; moving the ECO to another stage resets it to
`normal`. A type with open ECOs, or the default type, cannot be deleted.

**Product data ECO.** `productChanges` may set `name`, `description`, `barcode`, `weight`, `volume`, `image`
(`ProductService.ENGINEERING_FIELDS`); apply writes them through `ProductService.applyEngineeringChange`. A product ECO
needs a target state or product data: `Pick the target lifecycle state or set some product data.` (web) /
`productChanges only applies to a product change.` (API, other kinds).

**Versions.** Revisions are recorded on registration and on every revision bump; revision codes are never reused.
Compare returns product data, BOM header and lines (added / removed / changed), routing operations, documents and unit
cost. As-built rows are written after commit of `manufacturing.order.completed`.

**Conflicts and rebase.** A BOM ECO whose base version is no longer current cannot be applied:
`BOM X vN is no longer the current version (vM is active); rebase ECO-… first.` `POST /changes/:id/rebase` merges
base → current into the ECO's draft (pure function `BomRebase`); a component changed on both sides keeps the ECO's
value and is reported as a conflict. Apply locks the ECO row (`FOR UPDATE`), so two applies serialize.

**Superseded BOM on production orders.** `GET /orders/:orderId/bom-status` reports the order's BOM version, the
current version, whether the order can be switched (draft / confirmed, not started) or why not, and open ECOs on that
BOM. `POST /orders/:orderId/update-bom` rebuilds the order on the current version (ADR-S063 order replacement).

**Cost policy** (`none`, `propose`, `activate`; BOM ECOs only: `costPolicy only applies to a BOM change.`). When not
given, the organization default applies, fitted to the product: serial-tracked → `none`; `activate` on a product not
on standard cost → `propose`. Given explicitly, it is refused instead:
`Product P is serial-tracked; standard cost does not apply.` /
`Product P is costed at AVCO; only a product on standard cost can have its standard activated by a change (use propose).`
`propose` leaves a draft product standard (BOM roll-up); `activate` approves and activates it in the apply
transaction (stock revaluation against 5960) unless segregation of duties or a closed period refuses it, in which case
the standard stays draft / approved and the ECO records why. `GET /changes/:id/financial` shows the impact; money
fields are `null` with `costVisible: false` for users without `inventory_valuation:read`.

**Lifecycle policy** (`GET|PUT /settings`). Matrix state × activity (`purchase`, `sell`, `replenish`) of `allow` /
`block`. Defaults:

| State | purchase | sell | replenish |
|---|---|---|---|
| concept, design, prototype, released | allow | allow | allow |
| phase_out | block | allow | block |
| obsolete | block | block | block |

`released` can never be blocked: `A released product is always allowed to be purchased, sold and replenished.`
Other refusals: `lifecyclePolicy.X is not a lifecycle state (…).`, `lifecyclePolicy.S.A is not an activity (…).`,
`lifecyclePolicy.S.A must be allow or block.`

Where the guard applies (PLM items only, PLM on):

| Activity | Checked at | Not checked |
|---|---|---|
| purchase | purchase request create, RFQ create (free lines), purchase order create — including requisition-to-order and RFQ award conversions | receipts of orders already placed; header-only PO edits |
| sell | sales quotation and sales order line writes (create and update), for products **newly added** to the document | lines already on the document; POS (offline clients); invoicing |
| replenish | purchase replenishment suggestions (`needed: false`, `lifecycleBlocked: true`); raising requests from replenishment alerts skips blocked products (their alerts stay open) | — |

Refusal: `Product P is obsolete and cannot be purchased. Replacement: R.` (states read "a concept", "in design",
"a prototype", "being phased out", "obsolete"; activities "purchased", "sold", "replenished").

**First-article inspection.** `firstArticle` on BOM / routing ECOs (default from settings;
`firstArticle only applies to a BOM or routing change.`). On apply `faiStatus = pending`. The first production order on
the new version that completes (`completed` / `qc_hold`) opens a final-stage quality lot (source `plm_fai`) when a
quality plan applies → `open` (event `plm.fai.opened`), else `no_plan`. The lot's evaluation or decision closes it:
`passed`, `failed`, `cancelled`. `POST /changes/:id/first-article` records the result by hand, only in `no_plan`:
`N has no first-article inspection to record.`, `The first article of N is inspected on lot L; record the result there.`,
`The first article of N was already recorded (status).`,
`The first article of N is not made yet: no production order on the new version has completed.`

**Reports.**

- `GET /reports/changes?from&to&changeTypeId&groupBy` — default last 90 days (organization time zone). Created /
  open / applied / cancelled / blocked counts, average and p90 cycle time (created → applied), average time to
  approval, first-pass rate (applied without a rejection or rebase), product data changes, standards created,
  revaluations and their amount (masked without valuation access), rebases, first-article outcomes, counts by kind
  and rows grouped by `month`, `type`, `stage`, `owner` or `priority`. Errors: `from must not be after to.`,
  `groupBy must be one of month, type, stage, owner, priority.`
- `GET /reports/obsolete-stock?state` (`phase_out`, `obsolete` or both; else `state must be phase_out or obsolete.`) —
  on hand, value (method-correct, masked without valuation access), open purchase quantity and orders (status not
  received / closed / cancelled, lines short of quantity), open sales quantity (confirmed / in fulfilment), active
  BOMs using the product, replacement, date of the state change.
- `GET /reports/stock-by-revision?productId` — per PLM product: batches made by a recorded order are exact; batches
  not matched are `unknown`; stock without a batch is attributed to the most recent unbatched as-built orders first
  and labelled `estimated`. 404 `PLM item not found`.

## Events

`plm.change.created`, `plm.change.stage_changed`, `plm.change.approved`, `plm.change.applied`, `plm.request.created`,
`plm.lifecycle.changed` (ADR-S063); `plm.revision.created`, `plm.change.rebased`, `plm.fai.opened` (ADR-S086).
Payload `PlmEventPayload`. Subscribed: `manufacturing.order.completed` (as-built, first article),
`quality.lot.evaluated` / `quality.lot.decided` (first-article outcome; lots of source `plm_fai` only).

## Migrations

`V440__plm_product_lifecycle.sql`, `V441__plm_dispositions.sql` (ADR-S063); `V640__plm_types_tags.sql`,
`V641__plm_revisions.sql`, `V642__plm_product_data_cost_policy.sql`, `V643__plm_settings_fai.sql` (ADR-S086). New
tables have the standard RLS policy.

## Web

Sidebar section **PLM** (feature `plm`): Overview, Change orders (board per ECO type with folded stages, kanban state,
tags; table with filters), Change requests, Products & versions (product cockpit with a Revisions tab: history,
compare, as built), Engineering documents, PLM reports (change analytics, phase-out / obsolete stock, stock by
revision), PLM settings (ECO types and stages with approvers, tags, lifecycle rules and defaults). Change order detail:
type / tags / kanban state, conflict banner with rebase, Financial tab (BOM ECOs), Product data tab, first-article
panel. Production order detail: banner when the order's BOM was superseded, with "Use the current version".

## Demo data

`SEED_PLM_DEMO=true` (seed profile, empty database; `PlmDemoSeedContributor`, order 960) adds to `DEMO`: office desk
`PLM-DESK` with an active BOM, parts in lifecycle states (steel leg being phased out for an aluminium prototype), ECO
type "Product data", three tags, an open change request and two draft ECOs (BOM change with its draft version, product
data change).

## Tests

`PlmIT` (ADR-S063), `PlmModuleIT`, `PlmModuleAccessTest`, `PlmTypesIT`, `PlmVersionsIT`, `BomRebaseTest`,
`PlmErpIntegrationIT`, `PlmPolicyIT`, `PlmReportsIT`, `PlmDemoSeedIT`; shared fixtures `PlmTestSupport`.

## Deviations and limitations

- No Nest counterpart; nothing here changes a Nest-ported contract. ECO responses gain fields only.
- Rebase cannot clear a BOM header field that was set to empty upstream (BOM updates ignore nulls), and after a
  rebase the ECO's draft keeps its version number, so version numbers may not follow apply order.
- Stock by revision is exact only for batch-tracked output; the rest is an estimate and labelled so.
- The ADR-S063 `/changes/:id/impact` route is unchanged (not masked); the new financial view and reports mask money
  without `inventory_valuation:read`.
- POS sales are never gated (offline clients); receipts of placed purchase orders are never gated.
