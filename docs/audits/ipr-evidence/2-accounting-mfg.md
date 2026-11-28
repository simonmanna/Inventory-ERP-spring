# IPR Phase 2 — stream C evidence: accounting integrity, manufacturing WIP, quality hold

- Branch `fix/ipr-2` (worktree `/home/ierp-ipr-p0-docs-specs`, off `fix/ipr-p15` fb91d736). Not pushed, not merged,
  not deployed.
- Test DB: Testcontainers-style external Postgres 16 `erp-itdb-ipr3` (127.0.0.1:5703), reset before the evidence run.
- Migration: V702 `ProductionPartialOutput` (registry updated). V703–V705 not used.
- Policy sign-off table (T26): [inventory-accounting-policy](../../spec/inventory-accounting-policy.md).
- Deviations: module specs `inventory.md`, `manufacturing.md`, `quality.md`, `accounting.md`, `procurement.md`,
  `pos.md` (section "Deviations", marked pending owner sign-off). The register
  `docs/audits/2026-11-27-inventory-readiness.md` is not edited.


## 1. Closure table

Red = the regression IT run on `fix/ipr-p15` code (all `backend/src/main` changes stashed, fresh DB), FAILED line
copied from the run log. Green = the same ITs on this branch (final counts in §3). Workflow IT = the IT that runs
the business flow end to end. "Integrity / GL" = `assertIntegrityGreen` and `assertGlTieOut` called in the IT.

| Finding | Root cause (file:line, `fix/ipr-p15`) | Fix commit | Regression IT (red → green) | Workflow IT | Integrity / GL asserted | Lock levels | Deviations |
|---|---|---|---|---|---|---|---|
| GL-1 | GRNI GL side summed transaction-currency `debit`/`credit` — `procurement/application/GrniReconciliationService.java:160` | `e9d1d38f` (GRNI), `fa17c9ff` (valuation) | `IprPhase2AccountingIT.baseCurrencyGrniAndValuation` — red: `FAILED ...baseCurrencyGrniAndValuation(): [GRNI GL in base currency: only the receipt's 1,000 {... "glBalance":"4699" ...}]` → green | same (receipt + foreign and base journals) | yes / yes | read-only, none | procurement.md |
| GL-5 | Valuation GL tie-out by source and `valuation_vs_gl_by_product` summed `debit − credit` — `accounting/application/InventoryValuationService.java:366`, `inventory/application/InventoryIntegrityService.java:452` | `e9d1d38f` (GRNI), `fa17c9ff` (valuation) | same IT (assertGlTieOut after a 1 USD @ 3,700 entry offset by 3,700 UGX) | same | yes / yes | read-only | inventory.md |
| GL-2 (by-product) | STANDARD by-product: engine receives at standard, GL debited stock with the pool share — `manufacturing/application/ProductionService.java:604-608` | `2ee82a5d` | `ManufacturingLifecycleIT.byProducts` — red: the red run of this test on `fix/ipr-p15` stopped earlier, on the old WIP report (`FAILED ...byProducts(): [WIP reconciliation report by-product order in progress: {"glBalance":"1150","openOrdersWip":"1000","drift":"150",...}]`, the late-cost share left by the WIP-3 scenario); the GL-2 defect itself was shown by this branch's first green run, while the new by-product branch was not yet active (it read the costing method from `getProduct`, D-3): `FAILED ...byProducts(): [integrity checks by-products completed] Expecting empty but was: ["valuation_vs_gl ...` with the STANDARD by-product at 15 in the stock ledger and 100 in the GL → green | same (AVCO + STANDARD by-products, complete, reverse) | yes / yes + `wip_tieout` | L1 order → L3–L6 receipt → L7 journals (variance JE inside the completion transaction) | manufacturing.md |
| GL-2 (downgrade) | Downgrade to a STANDARD product: GL took the downgrade value, stock ledger the standard — `quality/application/QualityDecisionService.java:235` | `396e6f01` | `IprPhase2AccountingIT.qualityHoldQuarantineAndStandardDowngrade` (red on the X-2 step first, see X-2) → green | same | yes / yes | L1 lot → L3–L6 issue/receive → L7 | quality.md |
| GL-3 | Integrity findings closed with `pos:override`, no re-check — `pos/application/PosStockPostingService.java:214-231` | `fa17c9ff` | `IprPhase2AccountingIT.integrityFindingResolution` — red: `FAILED ...integrityFindingResolution(): [{"id":"…","status":"resolved",…}] expected: 403 but was: 201` → green | same (403, 400 while failing, 201 after the correcting journal) | yes / yes | exception row only; checks read-only | pos.md |
| GL-10 | Close counted value findings raised in the period only (`createdAt ≥ start`) and never re-ran the checks — `accounting/application/PeriodCloseService.java:190-195`, `PeriodCloseChecklistService.java:148-153` | `fa17c9ff` | `IprPhase2AccountingIT.periodCloseRunsValueChecks` — red: `FAILED ...periodCloseRunsValueChecks(): [{"journalEntryId":null,"netIncome":"0"}] expected: 400 but was: 200` → green | same (refused while dirty, closes after the correction) | – / yes | L1 `FiscalPeriod FOR UPDATE`, then read-only checks; no stock locks | accounting.md |
| GL-4 | Fallback for every issue type was COGS — `inventory/application/StockPostingService.java:794-803`; system rules hard-deletable — `PostingRuleAdminService.java:252` | `be2384d6` | `IprPhase2AccountingIT.postingFallbacks` — red: `FAILED ...postingFallbacks()` at `IprPhase2AccountingIT.java:157` (return to supplier debited COGS, not GRNI) → green | same | – | inside the existing issue path (L7 journal); rule delete: rule row only | inventory.md |
| GL-8 | Debit note credited the `stock_valuation` mapping — `procurement/application/DebitNotesService.java:617`, `:676` | `e9d1d38f` | `IprPhase2AccountingIT.debitNoteCreditsProductStockAccount` — red: `FAILED ...debitNoteCreditsProductStockAccount(): [the return credits the product's own stock account] expected: 800 but was: 1000.000000` → green | same (GRN, debit note, post) | yes / yes | unchanged (L1 note → L3–L6 issue → L7); `stockAccount` is a read | procurement.md |
| GL-11 / R4 | No WIP tie-out existed | `2ee82a5d` | `ManufacturingLifecycleIT.*` — red: `FAILED ...ManufacturingLifecycleIT.simpleFull(): no integrity check wip_tieout` (all 11) → green | `ManufacturingLifecycleIT` (11 scenarios) | yes / yes + `wip_tieout` + WIP report | read-only | inventory.md, manufacturing.md |
| WIP-3 (P1) | Run picked completed/closed orders only — `costing/application/CostAdjustmentService.java:88`, `:94`, `:136` | `2ee82a5d` | `ManufacturingLifecycleIT.lateCostOnCancelledOrder` — red: `FAILED ...lateCostOnCancelledOrder(): [/costing/cost-adjustment-runs → {"message":"Nothing to adjust: no late cost is waiting in WIP of a completed order","error":"Bad Request","statusCode":400}]` → green | same (GRN, order started, landed cost voucher, cancel, run) | yes / yes + `wip_tieout` | unchanged run path; layer lookup is a read | manufacturing.md |
| WIP-6 | Report expected material + overhead for in-progress and QC-hold orders, transaction currency, any status — `manufacturing/application/ProductionReportService.java:49-83`; `StageWipReportService.java:44-46` | `2ee82a5d` | `ManufacturingLifecycleIT.simpleFull` (order with overhead in progress) — red: `FAILED ...simpleFull(): [WIP reconciliation report in progress: {"glBalance":"400","openOrdersWip":"450","drift":"-50","balanced":false,...}]` (overhead 50 expected in WIP before completion) → green | every lifecycle scenario asserts `balanced` | yes | read-only | manufacturing.md |
| X-9 | No partial output for simple orders | `2ee82a5d` | `ManufacturingLifecycleIT.partialOutput` — red: `FAILED ...partialOutput(): [{"message":"Cannot POST /api/v1/manufacturing/production-orders/…/partial-output","error":"Not Found","statusCode":404}]` → green | same (2 partials, cancel refused, complete, reverse) | yes / yes + `wip_tieout` | L1 order (`lock`) → L3–L6 receipt → L7 journals | manufacturing.md |
| X-2 (D2) | Hold only flipped the status; stock stayed sellable — `quality/application/QualityDecisionService.java:78-95`, `QualityLotService.java:206-218` | `396e6f01` | `IprPhase2AccountingIT.qualityHoldQuarantineAndStandardDowngrade` — red: `FAILED ...qualityHoldQuarantineAndStandardDowngrade(): Incorrect result size: expected 1, actual 0` (no QC-QUARANTINE location: nothing moved) → green | same (hold → quarantine, `quality_hold_unquarantined` ok, downgrade, release back) | yes / yes | L1 lot → L3–L6 transfer (existing quarantine transfer) | quality.md |
| P15-5 | GQDEMO 600 — see §4 | none (read-only) | – | – | – | – | – |

WIP-1 = GL-4, WIP-2 = GL-2 (closed above). WIP-4 (WIP split across rule accounts) is covered by the tie-out, which
reads the mapped `wip` account and every active `PRODUCTION_CONSUME` debit / `PRODUCTION_OUTPUT` credit rule account
(`category_field` rules are not resolved: noted in the check). WIP-5 (no `wip` mapping): the check reports a
`wip_unmapped` row when manufacturing data exists. WIP-7 and WIP-8 are not changed (process-costing variance entries
are attributed by their posting key; legacy reversal rounding unchanged).

## 2. Lock levels of new or changed paths (R2: L1 document → L2 original ledger rows → L3 costing advisory → L4 quant
advisory → L5 `StockItem` → L6 lot/serial/reservation/bin → L7 journal)

| Path | Levels | Note |
|---|---|---|
| `POST /manufacturing/production-orders/:id/partial-output` | L1 (`ProductionService.lock`) → engine receipt L3–L6 → L7 (overhead absorption, output journals) | same order as `complete` |
| `complete` with partials, `cancel` refusal, `reverse` with partials | unchanged order (L1 → L2/L3–L6 → L7); partial rows read under L1 | the reversal issues the partial receipts after the main output, inside the same L1 |
| By-product at standard (GL-2) | inside `complete`: L1 → L3–L6 → L7 (variance JE) | the reversal reverses the variance JEs (L7) after the main variance JE |
| Downgrade at standard (GL-2) | L1 lot row → L3–L6 → L7 | variance journal posted after the downgrade journal |
| Quality hold → quarantine (X-2) | L1 lot row → L3–L6 (existing `quarantineStock` transfer) | no journal (same account) |
| Cost adjustment run, cancelled orders (WIP-3) | unchanged run path | adds a read of the cancel / reversal receipts |
| Period close live checks (GL-10) | L1 `FiscalPeriod FOR UPDATE` → read-only value checks | no stock lock taken |
| Resolve integrity finding (GL-3) | exception row update; read-only checks before it | – |
| Posting fallbacks / rule delete (GL-4), debit note account (GL-8) | unchanged | account resolution is a read |
| WIP tie-out, WIP report, GRNI / valuation reports | read-only transactions | – |

No new concurrency path takes locks in a new order, so no new `race` IT was added; the partial-output route takes the
same L1 order lock as `complete` and requires an Idempotency-Key (a retried request replays, never receives twice).

## 3. Test results

Final run on a freshly reset DB (`erp-itdb-ipr3`, 5703, reset 15:16 UTC), one `fastTest` invocation, branch
`fix/ipr-2` working tree equal to the commits above (before the route-matrix count fix). Full build **not** run
(CLAUDE.md build rule).

| Class | Tests | OK | Failed | Skipped |
|---|---|---|---|---|
| manufacturing.ManufacturingLifecycleIT | 11 | 11 | 0 | 0 |
| accounting.AccountingIT | 7 | 7 | 0 | 0 |
| accounting.CloseProcessIT | 8 | 8 | 0 | 0 |
| accounting.FiscalYearIT | 3 | 3 | 0 | 0 |
| accounting.PeriodCloseChecklistIT | 7 | 7 | 0 | 0 |
| accounting.PeriodReopenIT | 1 | 1 | 0 | 0 |
| inventory.InventoryEngineGapsIT | 13 | 13 | 0 | 0 |
| inventory.InventoryIT | 21 | 21 | 0 | 0 |
| inventory.InventoryIntegrityIT | 12 | 12 | 0 | 0 |
| inventory.InventoryIntegrityP0IT | 16 | 16 | 0 | 0 |
| inventory.ValuationGapsIT | 10 | 10 | 0 | 0 |
| inventory.audit.InventoryAuditTenancyLocationIT | 6 | 6 | 0 | 0 |
| inventory.audit.IprPhase2AccountingIT | 6 | 6 | 0 | 0 |
| manufacturing.CostingAuditIT | 17 | 17 | 0 | 0 |
| manufacturing.CrushingIT | 11 | 11 | 0 | 0 |
| manufacturing.ManufacturingCoreIntegrityIT | 11 | 11 | 0 | 0 |
| manufacturing.ManufacturingIT | 10 | 9 | 1 | 0 |
| manufacturing.ManufacturingReadyIT | 12 | 11 | 1 | 0 |
| manufacturing.MultiStageProductionIT | 12 | 12 | 0 | 0 |
| manufacturing.ProductionReversalOverheadIT | 1 | 1 | 0 | 0 |
| manufacturing.StandardProcessCostingIT | 4 | 4 | 0 | 0 |
| pos.PosStockPostingIT | 10 | 10 | 0 | 0 |
| procurement.LateCostWipCascadeIT | 5 | 5 | 0 | 0 |
| procurement.ProcureToPayGapsIT | 19 | 19 | 0 | 0 |
| procurement.ProcurementIT | 22 | 22 | 0 | 0 |
| procurement.PurchaseReadinessReceiptsIT | 9 | 9 | 0 | 0 |
| procurement.ReceivingQualityIT | 4 | 4 | 0 | 0 |
| quality.QualityLotsIT | 7 | 7 | 0 | 0 |
| quality.QualityNcrTraceIT | 5 | 5 | 0 | 0 |
| qualitycontrol.QualityControlFlowIT | 14 | 14 | 0 | 0 |
| quarry.QuarryIT | 9 | 9 | 0 | 0 |
| **Total (31 classes)** | **303** | **301** | **2** | **0** |

The two failures:

- `ManufacturingIT.authorization`: `expected: 297 but was: 298`: the route matrix counts the new X-9 route
  `POST /manufacturing/production-orders/:id/partial-output` (403 without `production_order:complete` proven by the
  matrix itself). Expected count raised to 298 in commit `6bc034c9`; re-run below.
- `ManufacturingReadyIT.cancelSurvivesReleaseFailure`: `bad SQL grammar [CREATE TRIGGER mfg_ready_fault_… ON
  "StockReservation" …]` at `ManufacturingReadyIT.java:157`: the test installs a fault trigger with schema-owner DDL;
  re-run below on a fresh DB.

Re-run on a second freshly reset DB (15:42 UTC) after `6bc034c9`:

| Class | Tests | OK | Failed | Skipped |
|---|---|---|---|---|
| manufacturing.ManufacturingIT | 10 | 10 | 0 | 0 |
| manufacturing.ManufacturingReadyIT | 12 | 11 | 1 | 0 |

`ManufacturingReadyIT.cancelSurvivesReleaseFailure` is **env-only** (same class as the known
`InventoryHardeningIT.ledgerGuardBypass`): with `IT_EXTERNAL_DB=true` no Flyway owner connection exists, so the test's
`owner(...)` DDL runs as the application role `erp`, which may not create a trigger on an owner-owned table. Proven on
the test DB (rolled back): `BEGIN; SET LOCAL ROLE erp; CREATE TRIGGER … ON "StockReservation" …` →
`ERROR: permission denied for table StockReservation`. On Testcontainers (CI) the owner connection is present. Stream
C does not touch reservation release.

`test --tests '*ArchitectureTests' --tests '*ModularityTests'`: ArchitectureTests 5/5, ModularityTests 2/2, BUILD SUCCESSFUL.

## 4. GQDEMO stock-account variance of 600 (P15-5) — read-only root cause

Read-only investigation on the restored live copy (127.0.0.1:5702, db `erp`, every statement inside
`BEGIN READ ONLY … ROLLBACK`, `SET LOCAL app.org_id`). No row was written; no repair is proposed (owner decision P15-5).

- Organisation GQDEMO `27b9014f-f773-48d5-ac57-7c3df7979bfe`, account 1400 `fbd4e65e-4f5e-4d22-91ec-d0ca7b0e4e4d`.
- GL (base) −63,123,452.585304 vs stock ledger −63,122,852.584432: variance **600.000872**. Base and transaction
  amounts are equal on every line, so currency plays no part.
- **600.00** = one stock ledger row with no journal: `InventoryLedger 8fa84ac4-c561-4c56-adcc-ead58ca72b59`,
  `ledgerCode` STK/OPENING, `referenceType` `opening_balance`, `referenceId` `seed`, product PRD-001 "Generic
  Widget", 100 × 6 = 600.00, created 2026-11-09 10:00:23.649. No `JournalEntry` with source `opening_balance` /
  `SEED-OPEN-INV` exists for GQDEMO (DEMO has exactly one).
- **0.000872** = 6-dp rounding on two `production_stage` documents (`57ce58e4-…` +0.000400, `6fefdd14-…` +0.000472):
  stock rows valued at 6 dp, journals at the rounded amounts. Within the 0.01 tolerance per document.
- **Root cause (seed code, still live for every organisation seeded the same way):** `SeedRunner.base()` runs only
  the contributors with order ≤ 300 (`kernel/seed/SeedRunner.java:46`, `:234-238`). `InventorySeedContributor`
  (order 300, `inventory/application/InventorySeedContributor.java:68-78`) inserts the opening ledger row, but the
  matching journal `SEED-OPEN-INV` is posted only by `PosSeedContributor.postOpeningInventory()` (order 400,
  `pos/application/PosSeedContributor.java:182`, `:269-305`), which `base()` skips. GQDEMO was seeded through
  `base()`; DEMO through the full seed. Reported as a new defect (§5, D-1).
- Query script: `/tmp/claude-0/-home-Inventory-ERP-spring-1/cd461f54-2f5b-4449-abd8-892d2ee4a6bb/scratchpad/q4.sql`
  (session scratchpad).

## 5. New defects found (not fixed here)

| Id | Severity | Defect | Where |
|---|---|---|---|
| D-1 | P2 | Organisations seeded with `SeedRunner.base()` get the opening stock ledger row without its journal (GQDEMO 600) | `kernel/seed/SeedRunner.java:46`, `:234-238`; `inventory/application/InventorySeedContributor.java:68-78`; `pos/application/PosSeedContributor.java:182`, `:269-305` |
| D-2 | P2 | A multi-stage order's stock reservation stays at the planned quantity while its stages consume (backflush / issue): the `reservations` integrity check shows `over_reserved` for every open multi-stage order; the reservation is only released at completion / cancel | `manufacturing/application/StageExecutionService.java:867`, `:1052` (release only); no partial consume in `inventory/application/StockReservationService.java:148-177` |
| D-3 | P3 | `ProductionService.getProduct` reads only `id`, `uomId`; callers that read other columns from it get null (found while building GL-2: the costing method) | `manufacturing/application/ProductionService.java:1402` (`fix/ipr-p15`) |

## 6. Engine requests (files stream C must not edit)

| File | Request |
|---|---|
| `StockService` | `INV_MOVE_TYPES.getOrDefault(moveType, "STOCK_OUT")` maps an unknown move type to a stock-out (COGS); it should fail closed like the GL-4 fallback does now |
| `StockService` / quarantine (X-3, stream D) | Refuse issues, sales and consumption from the QC-QUARANTINE location except through quality decisions; X-2 moves held stock there, the guard is what makes it unusable everywhere (with negative stock allowed, an issue from the sellable location can still go negative) |



## 7. Partly done / not done

| Item | State | Why / what is left |
|---|---|---|
| GL-11 `wip_tieout` | Partly | Built and green in every lifecycle scenario, but kept **report-only**: promoting it to a value check (so it holds the period close like GL-10) is an owner decision (policy table P8) |
| GL-11 tie-out accounts | Partly | WIP accounts = the mapped `wip` account plus active `PRODUCTION_CONSUME` debit / `PRODUCTION_OUTPUT` credit rule accounts; rules that resolve the account from a product-category field (`category_field`) are not followed |
| X-9 partial output | Partly | Single-stage, non-QC, non-STANDARD output only. STANDARD output, QC-required orders, abnormal rework and multi-stage orders are refused with a message (multi-stage already has stage output) |
| X-2 quarantine guard | Partly | The hold moves stock to QC-QUARANTINE; refusing issues *from* quarantine is stream D (X-3, engine request §6) |
| WIP-7 / WIP-8 | Not done | Unchanged (outside the stream C list) |
| C-3 / GL-6 receipt-cost policy | Not done | Wave 3 (policy table P2) |
| Concurrency | Not done | No new `race` IT; the new paths take the existing L1 locks (§2) and add no new lock level |
| GQDEMO 600 | Not repaired | Read-only by decision P15-5; root cause §4, defect D-1 |
