# Module spec: quality (quality management)

Construction-materials build, Track C Phase 2.6. Design: [ADR-S013](../adr/ADR-S013-quality-management.md).
Requirements: [§3.5](../requirements/construction-materials-erp.md) (quality control and product specifications).
No NestJS counterpart (new module, additive). Migration `V27__quality_management.sql` (placeholder number).

## Purpose

One generic quality model used by procurement (incoming inspection of cement, sand, …), manufacturing (in-process
stage gates, final inspection of aggregates and blocks) and fleet (certificates with deliveries, customer complaints):
versioned specifications, inspection plans and lots with per-sample results (sieve grading included), hold /
quarantine / release / reject / downgrade decisions moving stock through `StockService`, NCR and CAPA, a laboratory
SPI, batch traceability and quality reports.

## Functional areas

| Area | What | Notes |
|---|---|---|
| Specifications | code + version, product / grade, standard, parameters (numeric min/max/target, sieve envelope, boolean, text; unit, test method, critical flag) | `draft → active → superseded / archived`; one active + one draft per code; parameters editable on drafts only; `revise` copies a version |
| Inspection plans | stage (incoming / in_process / final), product, supplier (incoming) or stage key (in-process), specification, sampling, hold policy, SoD, auto-NCR | most specific active plan wins |
| Inspection lots | `QL-000001`; source (GRN line, production order + stage, manual), batch, quantity, location, sample size, spec version, `stockControl` | status `open / hold / quarantined / closed / cancelled`; result `pending / pass / fail` |
| Results | per parameter × sample; sieve readings `[{sieveMm, passingPct}]`; manual, CSV, REST, simulator | re-evaluated on every entry; stored evaluation snapshot |
| Decisions | hold, quarantine, release, reject (scrap / return to supplier), downgrade (second-grade product), cancel | partial quantities; audited; event `quality.lot.decided` |
| NCR | `NCR-00001`, source, severity, lot / product / batch / supplier / customer / work centre, disposition, root cause | auto-raised when a lot fails (plan `autoNcr`) |
| CAPA | `CAPA-00001`, optional NCR, root cause + method, owner, due date, actions (corrective / preventive, owner, due) | verification SoD; overdue query |
| Lab SPI | `LabResultAdapter`: `csv` (import), `rest`, `simulator` | per-lot transactions; import log with row errors |
| Traceability | backward / forward tree over (product, batch) | GRN → supplier; production order inputs / outputs; downgrades; fleet trips → customer / site; other ledger issues |
| Reports | lots, evaluated, passed, failed, pass rate, rejected / downgraded qty, NCR count by product / batch / work centre / supplier | period (business dates) and stage filter |
| CoA | certificate of analysis PDF per lot (OpenPDF) | grading table per sieve parameter |

## Routes (45, all under `/api/v1`, core-available — no module flag)

| Method | Path | Permission |
|---|---|---|
| GET | `/quality/specifications` (`search, status, productId, page, pageSize`) | `quality:read` |
| GET | `/quality/specifications/:id` (parameters + versions) | `quality:read` |
| POST | `/quality/specifications` | `quality:configure` |
| PATCH | `/quality/specifications/:id` (draft only; `parameters` replaces the list) | `quality:configure` |
| POST | `/quality/specifications/:id/activate` | `quality:configure` |
| POST | `/quality/specifications/:id/revise` | `quality:configure` |
| POST | `/quality/specifications/:id/archive` | `quality:configure` |
| GET | `/quality/inspection-plans` (`search, stage, productId, active, page, pageSize`) | `quality:read` |
| GET | `/quality/inspection-plans/:id` | `quality:read` |
| POST | `/quality/inspection-plans` | `quality:configure` |
| PATCH | `/quality/inspection-plans/:id` | `quality:configure` |
| DELETE | `/quality/inspection-plans/:id` (soft) | `quality:configure` |
| GET | `/quality/inspection-lots` (`search, status, stage, result, productId, supplierPartnerId, sourceType, sourceId, batchNumber, page, pageSize`) | `quality:read` |
| GET | `/quality/inspection-lots/:id` (parameters with results, evaluation, decisions, NCRs, `openQty`) | `quality:read` |
| POST | `/quality/inspection-lots` (manual lot) | `quality:inspect` |
| POST | `/quality/inspection-lots/:id/results` | `quality:inspect` |
| POST | `/quality/inspection-lots/:id/hold` | `quality:hold` |
| POST | `/quality/inspection-lots/:id/quarantine` (Idempotency-Key optional) | `quality:hold` |
| POST | `/quality/inspection-lots/:id/release` (Idempotency-Key optional) | `quality:release` |
| POST | `/quality/inspection-lots/:id/reject` (**Idempotency-Key required**) | `quality:dispose` |
| POST | `/quality/inspection-lots/:id/downgrade` (**Idempotency-Key required**) | `quality:dispose` |
| POST | `/quality/inspection-lots/:id/cancel` (Idempotency-Key optional) | `quality:dispose` |
| GET | `/quality/inspection-lots/:id/certificate` (PDF) | `quality:read` |
| GET | `/quality/gate` (`sourceType, sourceId, stageKey`) | `quality:read` |
| GET | `/quality/ncrs` (`search, status, severity, source, lotId, supplierPartnerId, page, pageSize`) | `quality:read` |
| GET | `/quality/ncrs/:id` (with CAPAs) | `quality:read` |
| POST | `/quality/ncrs` | `quality:ncr` |
| PATCH | `/quality/ncrs/:id` | `quality:ncr` |
| POST | `/quality/ncrs/:id/status` | `quality:ncr` |
| GET | `/quality/capas` (`search, status, ownerUserId, ncrId, overdue, page, pageSize`) | `quality:read` |
| GET | `/quality/capas/:id` (with actions) | `quality:read` |
| POST | `/quality/capas` (with initial actions) | `quality:capa` |
| PATCH | `/quality/capas/:id` | `quality:capa` |
| POST | `/quality/capas/:id/status` | `quality:capa` |
| POST | `/quality/capas/:id/actions` | `quality:capa` |
| PATCH | `/quality/capa-actions/:actionId` | `quality:capa` |
| POST | `/quality/capa-actions/:actionId/complete` (`status: done / cancelled`) | `quality:capa` |
| GET | `/quality/capa-actions/overdue` | `quality:read` |
| GET | `/quality/lab-adapters` | `quality:read` |
| GET | `/quality/lab-imports` | `quality:read` |
| GET | `/quality/lab-imports/:id` | `quality:read` |
| POST | `/quality/lab-imports/csv` (`{content, filename}`) | `quality:inspect` |
| POST | `/quality/lab-imports/fetch` (`{adapter: rest / simulator, lotIds}`) | `quality:inspect` |
| GET | `/quality/traceability` (`productId, batchNumber, direction = backward / forward, depth 1–10`) | `quality:read` |
| GET | `/quality/reports/summary` (`groupBy = product / batch / work_center / supplier, stage, from, to`) | `quality:read` |

## Permissions

`quality:read`, `quality:configure`, `quality:inspect`, `quality:hold`, `quality:release`, `quality:dispose`,
`quality:ncr`, `quality:capa` (`packages/shared/src/permissions.ts` → `Permissions.QUALITY__*`); the migration grants
them to every `Administrator` role.

## Data model (V27)

| Table | Key columns |
|---|---|
| `QualitySpecification` | `code`, `version`, `status`, `productId`, `grade`, `standard`, `effectiveFrom`, `revisedFromId`; unique (org, code, version); one active / one draft per code (partial unique indexes) |
| `QualitySpecParameter` | `specificationId`, `code`, `kind`, `unit`, `testMethod`, `minValue` / `maxValue` / `targetValue`, `decimals`, `expectedText`, `sieveEnvelope` JSONB, `isCritical`, `sortOrder` |
| `QualityInspectionPlan` | `code`, `stage`, `productId`, `supplierPartnerId`, `stageKey`, `specificationId`, `samplingMethod`, `sampleSize`, `samplePct`, `perQuantity`, `minSamples`, `maxSamples`, `holdPolicy`, `independentRelease`, `autoNcr`, `isActive` |
| `QualityInspectionLot` | `lotNumber`, `stage`, `planId`, `specificationId`, product / variant / batch, `quantity`, `locationId`, `quarantineLocationId`, `stockControl`, `sourceType` / `sourceId` / `sourceLineId` / `sourceRef`, `stageKey`, `supplierPartnerId`, `workCenterId`, `sampleSize`, `status`, `result`, `evaluation` JSONB, `releasedQty` / `rejectedQty` / `downgradedQty`, `inspectedBy`; one live lot per (source, line, stage key) |
| `QualityLotResult` | `lotId`, `parameterId`, `sampleNo` (unique together), `numericValue`, `textValue`, `sieveResults` JSONB, `passed`, `source`, `labReference`, `testedAt` |
| `QualityLotDecision` | `lotId`, `decision`, `quantity`, from / to location, `disposition`, downgrade product / batch / qty, `unitCost`, `stockValue`, `writeDownValue`, `ledgerCodes`, `journalEntryId`, `reason`, `concession` |
| `QualityNcr` | `ncrNumber`, `title`, `source`, `severity`, `status`, lot / product / batch / supplier / customer / work centre, `quantityAffected`, `disposition`, `rootCause`, owner, closure |
| `QualityCapa` / `QualityCapaAction` | `capaNumber`, `ncrId`, `rootCause`, `rootCauseMethod`, owner, `dueDate`, `status`, effectiveness / verification; actions `kind`, `description`, owner, `dueDate`, `status` |
| `QualityLabImport` | `adapter`, `filename`, `status`, row counts, `errors` JSONB, `lotIds` JSONB |

Every table: `organizationId` + index, RLS `tenant_isolation`.

## State machines

- **Specification**: `draft → active` (supersedes the active version) `→ superseded`; any → `archived`.
- **Lot**: `open → hold | quarantined`; `hold → quarantined`; decisions with quantity from `open / hold / quarantined`;
  open quantity 0 → `closed`; `cancel` (no decided quantity) → `cancelled` (stock back from quarantine).
- **NCR**: `open → investigating → action → closed`; `open / investigating / action → cancelled`; `closed → open`.
  Close needs every CAPA closed / cancelled and a root cause unless `minor`. Creating a CAPA for an open /
  investigating NCR moves it to `action`.
- **CAPA**: `open → in_progress → verification → closed`; `verification → in_progress`; open states → `cancelled`.
  Completing an action starts an open CAPA. Verification needs ≥ 1 action and none open; closing needs effectiveness
  notes and a user other than the owner (403).

## Business rules

- **Evaluation** (`SpecEvaluator`): numeric within `[min, max]`; sieve: a reading for each envelope sieve within its
  band (sieve sizes compared numerically: `20` = `20.0`); boolean equals `expectedText` (default `true`; yes / pass / 1
  accepted); text equals `expectedText` (case-insensitive) or is non-blank. A parameter is pending until `sampleSize`
  samples are in unless a sample already fails; a failing non-critical parameter is a deviation. Lot result: fail if
  any critical fail, pending if any pending (or no parameters), else pass. Sieve readings are validated on entry:
  0–100 %, one reading per sieve, % passing not increasing on smaller sieves.
- **Sampling** (`SamplingCalculator`): fixed n | ⌈N·pct/100⌉ | ⌈√N⌉+1 | ⌈N/q⌉, clamped to min / max, ≥ 1.
- **Hold policy on open** (applyHoldPolicy default true): `quarantine` + `stockControl = quality` + location →
  transfer the lot quantity (that batch only) into `QC-QUARANTINE`; `quarantine` without stock or `hold` → status hold.
- **Release**: needs result `pass` or a `concessionReason` (recorded, `concession = true`). Quarantined stock goes back
  to `toLocationId` (default the lot location).
- **Reject**: `scrap` issues `waste` (GL: WASTE rule / waste expense); `return_to_supplier` (lot must have a supplier)
  issues `return_to_supplier` (Dr GRNI / Cr stock); the debit note stays a procurement document.
- **Downgrade**: to a different product; issue `adjustment_out` (no GL) + receive `adjustment_in` at carried value /
  `toQuantity` or a lower `unitCost`; balanced entry (source `quality_downgrade`, posting key per decision) Dr stock
  of the second grade + Dr `stock_adjustment_expense` (write-down) / Cr stock of the first grade when accounts differ
  or there is a write-down; upward revaluation refused.
- **SoD**: with `independentRelease` (plan default true) the user who recorded the results gets 403 on release /
  reject / downgrade.
- **Idempotency**: reject / downgrade require `Idempotency-Key`; replay answers the stored response.
- **Concurrency**: every decision locks the lot row (`FOR UPDATE`); a second contender sees the new state.
- **Incoming hook**: `goods_receipt.posted` / `purchase_order.received` → one lot per GRN line with an active incoming
  plan (base quantity = line quantity × `uomFactor`), idempotent; failures per line are logged and skipped.

## Events

`quality.lot.created`, `quality.lot.evaluated` (result changed), `quality.lot.decided` (`decision`, `quantity`,
`status`, `result`, `stockControl`, `sourceType`, `sourceId`, `stageKey`, `productId`, `batchNumber`),
`quality.ncr.raised`, `quality.capa.closed`. Consumed: `goods_receipt.posted`, `purchase_order.received`.

## GL postings

| Decision | Entry |
|---|---|
| quarantine / release / cancel | inventory transfer rules (`STOCK_TRANSFER_OUT/IN`, none by default) |
| reject scrap | issue `WASTE` (Dr waste / stock adjustment expense, Cr stock valuation) |
| reject return_to_supplier | Dr GRNI / Cr stock valuation |
| downgrade | Dr stock (second grade) + Dr `stock_adjustment_expense` (write-down) / Cr stock (first grade) — only when needed |

## Public API for other sub-modules

- **2.1 stage gates**: `QualityGateService.gate(sourceType, sourceId, stageKey)` → `GateStatus(required, passed, lotId,
  lotNumber, status, result, reason)`; open the stage lot with `QualityLotService.openLot(new OpenLot("in_process",
  outputProductId, variantId, batch, qty, uomId, null, "production_order", orderId, null, orderCode, stageKey, null,
  workCenterId, null, "source", true, null))`; subscribe to `quality.lot.decided` to unblock / move WIP.
- **2.3 crushing**: final lots on stockpile batches (`stage = final`, `stockControl = quality`, sieve parameters per
  size); `QualityTraceabilityService.trace` for ROM → crushed batch.
- **2.4 blocks**: in-process curing lots (7 / 28-day compressive strength as numeric parameters, 2+ samples), final
  lots before sale; downgrade to second grade through `QualityDecisionService.downgrade`; for orders still in
  manufacturing's `qc_hold`, open the lot with `stockControl = source` and call the order's `recordQc` on
  `quality.lot.decided`.
- **Fleet**: CoA via `QualityCertificateService.certificate(lotId)`; customer complaints → `QualityNcrService.create`
  (`source = customer`, `customerPartnerId`).
- Plans: `QualityPlanService.findPlan(stage, productId, supplierId, stageKey)`.

## Additive changes outside the module

- `inventory.application.StockService.transfer`: honours `StockMove.batchNumber` (moves that lot only, 400 when the
  lot holds too little). No existing caller sets a batch on a transfer.
- `inventory.application.VirtualLocationService.ensure(code, name)`: find-or-create a virtual location
  (`QC-QUARANTINE`) in its own transaction.
- `application.yml` / `.env.example`: `QUALITY_LAB_REST_URL`, `QUALITY_LAB_REST_TOKEN`, `QUALITY_LAB_REST_TIMEOUT_MS`.

## Web

`/quality/...` screens (sidebar section "Quality", `quality:read`): specifications (list, detail with parameter /
envelope editor, activate / revise / archive), inspection plans, inspection lots (list, detail with the result-entry
grid incl. sieve analysis, decisions, CoA print), NCRs, CAPAs (overdue filter), lab imports (CSV / adapter fetch),
traceability explorer (tree), reports.

## Tests

- Unit: `SpecEvaluatorTest` (numeric, sieve envelope, validation, boolean / text, overall), `SamplingCalculatorTest`,
  `CsvLabResultsTest`, `LabAdaptersTest` (simulator within spec, REST adapter against a local HTTP server).
- IT `QualityLotsIT`: specification versions; GRN → quarantined lot (exact batch) → fail → NCR → reject (idempotent,
  key required) → downgrade (stock + balanced GL write-down) → supplier report; pass → SoD 403 → partial / full
  release → gate; 2.1 gate with `stockControl = source`, concession, hold, cancel; lab CSV import (partial);
  CoA PDF; contended reject.
- IT `QualityNcrTraceIT`: NCR → CAPA lifecycle with SoD, overdue and close rules; traceability backward / forward on a
  seeded chain; reports by work centre / product; authorization matrix (45 routes); tenant isolation.

## Deviations

None (new module).

## Status

- [x] ADR-S013, migration, entities, services, controllers, permissions, events
- [x] Incoming hook (procurement events), stage-gate API (2.1), lab SPI (CSV / REST / simulator), CoA PDF
- [x] Traceability, reports, NCR / CAPA
- [x] Unit + integration tests
- [x] Web screens
- [ ] Needs owner input: the real laboratory system's API (REST adapter assumes `GET /results?lot=`), CoA layout /
      logo, whether downgrades should post the write-down to a dedicated "quality loss" account

## Stage-gate bridge (2.1 ↔ 2.6, merged 2026-10-30)
`manufacturing.application.QualityStageGateBridge` implements the 2.1 `StageQcCheckpointHook`: a QC-gated stage output
opens an in-process lot (`sourceType = production_order`, `sourceId` = order, `sourceLineId` = stage,
`stageKey` = the stage's `qcCheckpointCode`, `stockControl = source`, no hold policy, since the output already sits in
`QC-QUARANTINE`) through `QualityLotService.openLotIfPlanned`, which returns null instead of failing when no active
plan / specification applies (the gate then stays manual). On `quality.lot.decided` (payload now carries
`sourceLineId`): a release that closes the lot passes the stage gate, a reject or downgrade fails it; partial releases,
hold and quarantine leave it pending. Dependency direction: manufacturing → quality.application. Covered by
`MultiStageProductionIT.stageGateWithQualityLot`. Gap: one lot per stage; a second partial output of the same stage
reuses the first lot (its quantity is not increased).
