# ADR-S013: Quality management (`quality`) — specifications, inspection lots, decisions, NCR / CAPA, lab SPI, traceability

- Status: Accepted (autonomous, 2026-10-30)
- Brief: [`AUTONOMOUS_BUILD_CONSTRUCTION.md`](../AUTONOMOUS_BUILD_CONSTRUCTION.md) Phase 2.6; requirements
  [§3.5](../requirements/construction-materials-erp.md) (quality control and product specifications)
- Spec: [`docs/spec/quality.md`](../spec/quality.md) — route table, data model, permissions, state machines, public API
- Migration: `V991__quality_management.sql` (placeholder number, renumbered at merge)
- Related: ADR-S006 (manufacturing QC plans, scrap reasons, `QC-QUARANTINE`), ADR-S009 (fleet deliveries),
  2.1 multi-stage manufacturing (stage gates call this module), 2.3 crushing (grading of stockpiles), 2.4 blocks
  (compressive strength 7/28 days, downgrade to second grade)

## Context

The client tests what it buys (cement, sand), what it makes (crushed aggregates: grading / sieve analysis, moisture,
flakiness; concrete blocks: compressive strength, dimensions, breakage) and what it ships. Requirements §3.5 ask for
product specifications, incoming / in-process / final inspection, quarantined and rejected stock, batch traceability,
nonconformance records with corrective actions, quality reports by product / batch / line / supplier and an
external-laboratory integration.

**Audit of what exists (kept unchanged):**

- `manufacturing` (ADR-S006 C): `QcPlan` (a checklist `[{key, label, type, required, min, max}]` per BOM / product),
  `ScrapReason` master, `ProductionQcCheck` (one decision per `qc_hold` order: passed / failed / rework quantities),
  the virtual `QC-QUARANTINE` location (created on first use) and the routes `/manufacturing/qc-plans`,
  `/scrap-reasons`, `/production-orders/:id/qc|rework|qc-plan`. These are production-order scoped and checklist only:
  no versioned specifications, no sampling, no per-sample results, no sieve grading, no supplier / incoming side, no
  NCR / CAPA, no CoA.
- `inventory`: batches (`InventoryBatch`, one row per location and batch number), the append-only `InventoryLedger`
  (`batchId`, `referenceType` / `referenceId`), `StockService` issue / receive / transfer. No quality status on stock:
  "quarantine" is purely a location (`QC-QUARANTINE`).
- `procurement`: GRN post publishes `goods_receipt.posted` (ad-hoc / draft GRN) and `purchase_order.received`
  (PO receive); no quality hook.

## Decision

### 1. A new generic module, not an extension of the manufacturing QC tables

New Spring Modulith module **`com.inventoryerp.quality`** (`web → application → domain`, `application` and `domain`
are named interfaces). Reasons: the model is used by procurement (incoming), manufacturing (in-process / final) and
fleet (delivery CoA, customer complaints → NCR); putting it into `manufacturing` would make procurement / fleet depend
on manufacturing and would tie it to `ENABLE_MANUFACTURING`. The manufacturing QC artefacts **stay as they are**
(Nest parity + ADR-S006 routes and ITs unchanged); they are the light "checklist at completion" path of a production
order. The quality module generalises them:

| Manufacturing (ADR-S006) | Quality (this ADR) |
|---|---|
| `QcPlan.items` checklist | `QualitySpecification` + `QualitySpecParameter` (versioned, numeric / sieve / boolean / text, test method, critical flag) and `QualityInspectionPlan` (stage, product / supplier / stage key, sampling, hold policy) |
| `ProductionQcCheck` (one decision) | `QualityInspectionLot` + `QualityLotResult` (per sample) + `QualityLotDecision` (hold / quarantine / release / reject / downgrade / cancel, partial quantities) |
| `QC-QUARANTINE` location | **the same location** (looked up by code, created on first use through the new inventory API `VirtualLocationService.ensure`) |
| `ScrapReason` | reused as free-text reason codes on reject (no second master) |

**Dependency direction** (cycle-free, `ModularityTests`): `quality → inventory, accounting, kernel, shared`.
Manufacturing (2.1 stage gates, 2.4 curing release), fleet and procurement may call `quality.application`; quality
never imports manufacturing / procurement / fleet types. It reacts to their **events by name** (outbox subscribers)
and reads their rows for traceability and lot defaults with **read-only, org-scoped SQL** over their tables
(`GoodsReceiptNote/Line`, `ProductionOrder/Material/Output`, `WorkOrder`, `FleetLoad/LoadLine`,
`FleetDeliveryOrder`, `FleetCustomerSite`), the same pattern fleet uses for `HrEmployee` / `Expense`. Quality never
writes another module's tables; stock moves through `StockService`, GL through `PostingService`.

### 2. Availability and licensing

**Core-available**: no `ENABLE_QUALITY` flag, no `@RequiresModule`, no licence feature. Quality is a cross-cutting
control (like approvals or audit) that procurement-only customers need too; access is controlled by the `quality:*`
permissions only. The web shows the "Quality" sidebar section to users holding `quality:read`.

### 3. Model

- **Specification** (`code` + `version`, `draft → active → superseded | archived`): one active version per code;
  `activate` supersedes the previous active version; `revise` copies an active / superseded version into a new draft
  (`version + 1`). Parameters are editable only on drafts, so a lot always evaluates against the immutable version it
  was opened with (`QualityInspectionLot.specificationId`).
- **Parameter kinds**: `numeric` (`minValue` / `maxValue` / `targetValue`, unit, test method, decimals), `sieve`
  (grading envelope `[{sieveMm, minPassingPct, maxPassingPct}]`; a sample is the % passing per sieve; checked per
  sieve, and % passing must not increase as the sieve gets smaller), `boolean` (expected `true` / `false`), `text`
  (informational, or must equal `expectedText`). `isCritical = false` makes an out-of-spec value a *deviation*
  (warning) instead of a failure.
- **Inspection plan**: `stage ∈ incoming | in_process | final`, product (required), optional supplier (incoming) or
  `stageKey` (in-process: the 2.1 stage / routing-operation code), the specification (its *code* — a lot opens with
  the active version), **sampling** (`fixed n`, `percent`, `sqrt` = ⌈√N⌉ + 1, `per_quantity` = ⌈N / q⌉, clamped to
  `minSamples..maxSamples`), **hold policy** (`quarantine` = move the lot quantity into `QC-QUARANTINE` when the lot
  opens, `hold` = status flag only, `none`), `independentRelease` (SoD, default on), `autoNcr` (raise an NCR when the
  lot fails, default on). The most specific active plan wins (supplier / stage key match before generic).
- **Inspection lot** (`QL-000001`): stage, product / variant / batch, quantity, location, source
  (`goods_receipt` + line, `production_order` + stage key, `manual`, …), supplier, work centre, sample size, the
  resolved specification version, `stockControl`:
  - `quality` — decisions move stock (`quarantine` transfer → `QC-QUARANTINE`, `release` transfer back or to a chosen
    location, `reject` issue as `waste` or `return_to_supplier`, `downgrade` reclassification);
  - `source` — the source module owns the stock (WIP of a stage, a manufacturing `qc_hold` order): quality records the
    decision and publishes `quality.lot.decided`; the source module moves the stock.
- **Status**: `open → hold | quarantined`; any open state → decisions with quantities; when the open quantity reaches 0
  the lot is `closed` (`releasedQty` / `rejectedQty` / `downgradedQty` record the disposition); `cancel` from any
  non-closed state with no decided quantity. **Result** (`pending | pass | fail`) is re-evaluated on every result entry.
- **Decisions** are rows (`QualityLotDecision`) with quantity, from / to location, ledger codes, journal entry, reason,
  concession flag, user, time; each is audited (`AuditService.recordInTx`, entity `QualityInspectionLot`).
  - Releasing a **failed** (or not yet evaluated) lot needs a `concessionReason` (deviation permit, recorded).
  - **SoD**: with `independentRelease`, the user who recorded the results cannot release / reject / downgrade the lot.
  - **Downgrade**: issue the first-grade product (skip GL) and receive the second-grade product at the carried value
    (or a lower unit cost) as `adjustment_out` / `adjustment_in`, reference `quality_downgrade`; one balanced journal
    entry Dr stock account of the second grade + Dr `stock_adjustment_expense` (write-down) / Cr stock account of the
    first grade, posted only when accounts differ or there is a write-down (posting key per decision). Upward
    revaluation is refused.
  - Reject / downgrade post GL: they require `Idempotency-Key`; the lot row is locked `FOR UPDATE` per decision.
- **Inventory change (additive)**: `StockService.transfer` honours `StockMove.batchNumber` when it is given (moves that
  lot only). No existing caller sets it on a transfer, so every current transfer is unchanged.
- **NCR** (`NCR-00001`): source (incoming / in_process / final / customer / supplier / internal), severity, lot /
  product / batch / supplier / customer / work centre, quantity, disposition, root cause; `open → investigating →
  action → closed`, `cancelled`. Closing needs every linked CAPA closed or cancelled.
- **CAPA** (`CAPA-00001`, optional NCR): root cause + method (5-why, fishbone, …), owner, due date, actions
  (`QualityCapaAction`: corrective / preventive, owner, due date, `open → done | cancelled`); `open → in_progress →
  verification → closed`, `cancelled`. Verification (effectiveness) by a user other than the CAPA owner (SoD); close
  needs all actions done / cancelled. `overdue` query (actions open past due, CAPAs open past due) for Phase 5 alerts.
- **Lab SPI** `LabResultAdapter` (`code()`, `fetch(LabFetchRequest)`) with `csv` (import of
  `lotNumber, parameterCode, value | sieveMm + passingPct, sampleNo, testedAt, labReference` rows), `rest` (HTTP GET
  `${QUALITY_LAB_REST_URL}/results?lot=…`, bearer `QUALITY_LAB_REST_TOKEN`, JSON array of the same fields) and
  `simulator` (deterministic results inside the specification, for tests and demos). Results map to lot parameters by
  code; every import is a `QualityLabImport` row with counts and per-row errors.
- **Traceability**: a tree over (product, batch) nodes. Backward: GRN lines (supplier + supplier lot), production
  outputs → order → consumed materials (recursing on their batches), quality downgrades. Forward: production
  materials / `production_consume` ledger rows → orders → outputs (recursing), fleet trip load lines / `fleet_trip`
  ledger rows → delivery order → customer + site, downgrades, other ledger issues (sales, waste) as leaves. Each batch
  node carries its inspection lots. Depth-limited and cycle-safe.
- **Reports**: lots, evaluated, passed, failed, pass rate, rejected + downgraded quantity, NCR count, grouped by
  product, batch, work centre or supplier over a period / stage.

### 4. Hooks and public API

- **Incoming (procurement)**: outbox subscribers on `goods_receipt.posted` and `purchase_order.received` open one
  lot per GRN line whose product has an active incoming plan (idempotent per GRN line), applying the plan's hold
  policy. GRN routes, JSON and ITs are unchanged. A GRN whose stock sits in quarantine cannot be reversed until the lot
  is released (inventory refuses the reversal for lack of stock at the warehouse) — intended.
- **Stage gate (2.1)**: `QualityGateService.gate(sourceType, sourceId, stageKey)` →
  `GateStatus(required, passed, lotId, lotNumber, status, result)`; `required` = an active in-process plan exists for
  the order's output product and stage, or a lot was opened for it; `passed` = a lot exists with result `pass` (or a
  concession) and a release decision. HTTP: `GET /quality/gate`.
- **Open a lot from code**: `QualityLotService.openLot(OpenLot)` (idempotent per source / line / stage key),
  `QualityPlanService.findPlan(stage, productId, supplierId, stageKey)`.
- **Events** (`packages/shared/src/events.ts`): `quality.lot.created`, `quality.lot.evaluated`, `quality.lot.decided`,
  `quality.ncr.raised`, `quality.capa.closed`. 2.1 / 2.4 subscribe to `quality.lot.decided` for `stockControl =
  source` lots.

### 5. Permissions

`quality:read`, `quality:configure` (specifications, plans), `quality:inspect` (open lots, record results, lab
imports), `quality:hold` (hold / quarantine), `quality:release`, `quality:dispose` (reject / downgrade / cancel),
`quality:ncr`, `quality:capa`. Granted to every `Administrator` role by the migration.

## Consequences

- One generic quality model for all three source modules; manufacturing's checklist QC keeps working untouched and can
  be retired later by 2.4 once final lots replace it (not in this change).
- Quality reads other modules' tables by SQL for traceability — a schema change there must keep these read queries in
  step (listed in `QualityTraceabilityService` / `QualityLotService`).
- Downgrade uses `adjustment_out` / `adjustment_in` ledger types (no new enum value): stock reports show them as
  adjustments with reference `quality_downgrade`.
- No ShedLock job: overdue CAPA alerts are a query (`GET /quality/capa-actions/overdue`); Phase 5 wires notifications.
