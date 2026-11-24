# ADR-S086 — PLM as a standalone application (Odoo-style), versions and ERP integration

- Status: accepted (built 2026-11-24, branch `feat/plm-app`)
- Builds on: [ADR-S063](ADR-S063-product-lifecycle-management.md) (lifecycle, ECR/ECO, stages, dispositions,
  documents), [ADR-S006](ADR-S006-manufacturing-production.md) package D (BOM versions), ADR-S008 (standard cost),
  ADR-S013 (quality lots), ADR-S065 (notification routing).
- Migrations: `V640__plm_types_tags.sql`, `V641__plm_revisions.sql`, `V642__plm_product_data_cost_policy.sql`,
  `V643__plm_settings_fai.sql`
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
`core.application.ProductLifecycleGuard`, implemented by PLM and consumed through `ObjectProvider` by procurement
and sales. No implementation means legacy behaviour.

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

### 2. ECO types, tags, kanban state, comment-only reviewers (V640)

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

### 3. Versions (V641)

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
  `BOM X vN is no longer the current version (vM is active); rebase ECO-… first.` `POST /changes/:id/rebase` does a
  three-way merge (base → current carried into the ECO's draft version, line by component and header field by
  field); a component changed on both sides keeps the ECO's value and is reported as a conflict. The merge is a pure
  function (`BomRebase`) with unit tests; the draft BOM is written through `BomService`.

### 4. Product master data under change control (V642)

A product ECO carries `productChanges` (whitelist: `name`, `description`, `barcode`, `weight`, `volume`, `image`) in
addition to, or instead of, a lifecycle target. Apply writes them through `ProductService.applyEngineeringChange`
(core API, same validation as a product edit for those fields) and bumps the revision when asked. The ECO shows
current → proposed values.

### 5. Accounting: standard cost and financial impact (V642)

`EngineeringChange.costPolicy` — `none`, `propose`, `activate` (null = the PLM setting, default `propose`) — applies
to BOM and routing changes of a product that is standard-costed (an active `ProductStandardCost`, or costing method
`STANDARD`):

- `propose`: on apply, the new default BOM's roll-up becomes a **draft** product standard (ADR-S008
  `proposeProductStandard`, source `bom_rollup`) for the normal approve / activate flow.
- `activate`: the same standard is approved and activated in the apply transaction, which revalues the stock on hand
  with the ADR-S008 journal (stock valuation against 5960 Standard Cost Revaluation). Segregation of duties is
  respected: when the applier may not approve their own standard, or activation is refused (closed period, …), the
  approval / activation is rolled back to a savepoint, the standard stays draft / approved for a second person, and the
  ECO records why. Engineering is never blocked by an accounting control, and an accounting control is never bypassed.

When `costPolicy` is not given, the organization default is fitted to the product: serial-tracked → `none`,
`activate` on a product not on standard cost → `propose`. Given explicitly, the same cases are refused
(`Product P is serial-tracked; standard cost does not apply.`, `Product P is costed at AVCO; only a product on
standard cost can have its standard activated by a change (use propose).`), and `costPolicy` on a non-BOM ECO is
refused (`costPolicy only applies to a BOM change.`).

The ECO stores `standardCostId`, `revaluationJournalId` and `costResult`. The impact view gains `financial`: whether
the product is standard-governed, current and proposed unit standard, on-hand quantity, the revaluation amount
(on hand × difference), and the value of stock the dispositions will scrap, quarantine or return. Products at AVCO /
FIFO are not revalued; the view says their future production cost changes by the roll-up delta. Money in the
financial view and the reports is `null` (with `costVisible: false`) for users without `inventory_valuation:read`,
the rule the stock valuation screens already follow.

### 6. Lifecycle policy across the ERP (V643)

`PlmSettings` (one row per organization) holds a policy matrix — for each lifecycle state and activity (`purchase`,
`sell`, `replenish`), `allow` or `block` — plus defaults for new ECOs (cost policy, first-article inspection).
`GET /settings` returns the stored matrix merged over the defaults; `PUT /settings` changes only the cells and defaults
it is given, then stores the whole effective matrix (so a later change of the shipped defaults never alters an
organization that already saved its rules).
Defaults:

| State | purchase | sell | replenish |
|---|---|---|---|
| concept, design, prototype | allow | allow | allow |
| released | allow | allow | allow (cannot be blocked) |
| phase_out | block | allow | block |
| obsolete | block | block | block |

The defaults block only what is retiring, so turning PLM on never stops an organization buying or selling products
that are still being designed; an organization that wants stricter gates (e.g. no sale before release) sets them.

Enforced through `ProductLifecycleGuard` (`PlmLifecyclePolicy`) — only for products registered in PLM, only while
PLM is on:

- purchase: purchase request create, RFQ create (free lines; request lines were checked when raised), purchase order
  create — which also covers the requisition-to-order and RFQ-award conversions. Purchase order edits change header
  fields only. Receipts of orders already placed are not gated (the goods are coming);
- sell: sales quotation and sales order line writes (create and update), for products **newly added** to the
  document — lines already on a document stay editable;
- replenish: purchase replenishment suggestions report blocked products with `needed: false` and
  `lifecycleBlocked: true`; raising purchase requests from replenishment alerts skips them and leaves their alerts
  open.

The refusal names the state and the replacement product: `Product P-100 is obsolete and cannot be purchased.
Replacement: P-200.` POS and invoicing are not gated (offline clients, Nest parity).

### 7. Quality: first-article inspection (V643)

`EngineeringChange.firstArticle` (BOM / routing ECOs; default from settings). Applying such an ECO sets
`faiStatus = pending`. The first production order on the new version that completes (`completed` or `qc_hold`; for a
BOM ECO an order on the new BOM, for a routing ECO an order of the product created after the apply) opens a
final-stage quality lot through `QualityLotService.openLotIfPlanned` (source `plm_fai`, idempotent per ECO) →
`open`, event `plm.fai.opened`. Without a quality plan the ECO records `no_plan` and the result is recorded by hand
(`POST /changes/:id/first-article`, `plm:approve`). The lot's evaluation (`quality.lot.evaluated`) or decision
(`quality.lot.decided`) closes the inspection: `passed`, `failed` or `cancelled`. The ECO shows the lot, the order
and the outcome; every step is written to the ECO chatter (activity kind `fai`). Outcomes are recorded even while PLM
is switched off, so a lot opened earlier never stays dangling.

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

No new permission keys: configuration (ECO types, tag edits, settings) uses `plm:manage`, versions, reports and
settings read `plm:read`, rebase, kanban state and new tags `plm:change`, the manual first-article result
`plm:approve`, order BOM update and apply `plm:apply`. New events (outbox, `PlmEventPayload`): `plm.revision.created`,
`plm.change.rebased`, `plm.fai.opened`.

### 10. Demo data

`SEED_PLM_DEMO=true` (seed profile, empty database; `PlmDemoSeedContributor`, after the manufacturing demo) adds to
`DEMO` an office desk `PLM-DESK` with an active BOM, its parts under lifecycle management (steel leg being phased out
for an aluminium prototype), an ECO type "Product data", three tags, an open change request and two draft ECOs (a BOM
change with its draft version, a product data change). Off by default, like the other demo seeds.

### 11. Limitations (accepted)

- Rebase cannot clear a BOM header field that was emptied upstream (`BomService.update` ignores nulls), and the ECO's
  draft keeps its version number, so after a rebase version numbers may not follow apply order.
- The ADR-S063 `/changes/:id/impact` route is unchanged (its unit-cost delta stays visible to `plm:read`); the new
  financial view and the reports mask money.
- Stock by revision is exact only for batch-tracked output.

### 12. Defaults chosen without the owner (to confirm)

1. Lifecycle defaults block only phase-out / obsolete (table in §6), not sale before release.
2. Sales documents: only newly added products are checked; existing lines stay editable.
3. Purchase order receipts are never gated; the POS is never gated.
4. Default cost policy `propose`; `activate` falls back to `propose` for products not on standard cost.
5. First article off by default; results recorded by hand only when no quality plan applies.
6. Report money masked by `inventory_valuation:read`.
7. PLM demo seed opt-in (`SEED_PLM_DEMO`).

## Consequences

- Manufacturing works with PLM absent: the ADR-S006 routes fall back to their original behaviour.
- `@RequiresModule("plm")` replaces `@RequiresModule("manufacturing")` on PLM routes; with add-on semantics the
  outcome is identical until an organization switches PLM off.
- Purchasing and sales of PLM-registered products can now be refused by policy; organizations not using PLM are
  unaffected (parity harness scenarios never register PLM items). Replenishment suggestions gain `lifecycleBlocked`.
- `costPolicy = activate` posts a revaluation journal at apply time, inside the apply transaction.
- ECO responses gain fields; no existing field or message changes, except the new conflict refusal on apply.
