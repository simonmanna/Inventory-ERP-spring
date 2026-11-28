# IPR review 2 — stream R2 evidence: fixes from the independent accounting review

- Branch `fix/ipr-review2` (worktree `/home/ierp-ipr-p0-docs-specs`, off `fix/ipr-p15` 3f530272; `fix/ipr-p15`
  ddfc6103 (stream R1 merged) merged in before the S-2 / R2-5 work). Not pushed, not merged, not deployed.
- Test DB: external Postgres 16 `erp-itdb-ipr3` (127.0.0.1:5703, owner-split, app under RLS).
- Migration: none (V723 not used).
- Regression ITs: `backend/src/test/java/com/inventoryerp/inventory/audit/IprReview2FixesIT.java` (each test in a
  fresh isolated organisation) and `OpeningStockImportIT.draftJournalIsNotReconciled`. Red = the ITs committed first
  (`7d16aa90`, `e2732424`) and run before their fix was in the main code; the FAILED lines are copied from the run
  logs. Green = the same ITs after the fix commits (counts in §3). Red-run logs kept in the session scratchpad
  (`r2-red.log`, `r2-red2.log`, `r2-red3.log`).
- Deviations (pending owner sign-off) recorded in `docs/spec/accounting.md` (GL-10 paragraph),
  `docs/spec/inventory.md`, `docs/spec/manufacturing.md` (WIP-3 paragraph),
  `docs/spec/manufacturing-wip-reconciliation.md` §5.1 / §5.3, `docs/spec/inventory-accounting-policy.md` P7,
  `docs/spec/analytics-kpis.md` (Inventory, cost visibility).

## 1. Closure table

| Finding | Root cause (file:line, `fix/ipr-p15`) | Fix commit | Regression IT (red → green) | Workflow IT | Integrity / GL asserted | Lock levels | Deviations |
|---|---|---|---|---|---|---|---|
| R2-1 (Major) | The period close re-ran the value checks **now**: `accounting/application/PeriodCloseService.java:206` called `valueChecks.failing()`; `inventory/application/InventoryValueCheckGate.java:33` → `InventoryIntegrityService.valueChecks()` `:262-264` (no date); `valuationVsGl` `:461` `glTieOut(null, …)` = current stock vs GL `postingDate ≤ today`; `valuationVsGlByProduct` read every GL line with no posting-date filter (a future-dated journal counted); `valuationVsSubledger` current quants; the open-finding count `PeriodCloseService.java:202-204` (and `PeriodCloseChecklistService.java:150-153`) had no date at all | `111b6f50` | `IprReview2FixesIT.laterVarianceDoesNotHoldClose` — red: `FAILED ...laterVarianceDoesNotHoldClose(): [closing June 2008 with a variance raised only after it: {"message":"Cannot close 'IPR 2008-06': the inventory value check(s) valuation_vs_gl (1 row(s); variance -75 (sub-ledger 100, GL 175)), valuation_vs_gl_by_product (1 row(s); cutover …) fail now. …` → green. `…periodVarianceFixedLaterStillHolds` — red: `FAILED ...periodVarianceFixedLaterStillHolds(): [{"journalEntryId":null,"netIncome":"0"}] expected: 400 but was: 200` (June closed although June's GL was 75 over the ledger) → green. `…futureDatedJournalDoesNotHoldClose` — red: `FAILED ...futureDatedJournalDoesNotHoldClose(): [closing June 2008 with a future-dated journal: {"message":"Cannot close 'IPR 2008-06': the inventory value check(s) valuation_vs_gl_by_product (1 row(s); …) fail now. …` → green. `…findingRaisedInPeriodHolds` is a guard (green before and after: a finding raised up to the period end still holds) | `IprPhase2AccountingIT.periodCloseRunsValueChecks` (GL-10, updated: the variance is dated in the period it holds; refused, corrected in the period, closes) | yes / yes (each R2-1 test ends with `assertGlTieOut` + `assertIntegrityGreen`, except the refusal-only `findingRaisedInPeriodHolds`) | Unchanged: `FiscalPeriod` row `FOR UPDATE` (period lock, accounting) then reads only; the as-of checks add read-only SQL, no inventory lock (L1–L7 not taken) | accounting.md (GL-10 paragraph), inventory.md, inventory-accounting-policy.md P7 |
| R2-2 (Minor) | `manufacturing/application/WipTieOutService.java:195-199`: a `quarry_extraction*` WIP line was added to `quarry` **and** `glOther`, so one line failed twice (`quarry_extraction` and `late_cost_and_other`) | `f241f10c` | `IprReview2FixesIT.quarryWipLineCountedOnce` — red: `FAILED ...quarryWipLineCountedOnce(): [WIP tie-out {"key":"wip_tieout","ok":false,…,"failures":2,…,"rows":[{"kind":"late_cost_and_other",…` → green (one row, `quarry_extraction`) | `ManufacturingLifecycleIT.quarryExtraction`, `QuarryIT` (green) | yes / yes | read-only report, no lock | manufacturing-wip-reconciliation.md §5.3 |
| R2-3 (Minor, plausible → confirmed) | `WipTieOutService.java:104-115`: every account an active `PRODUCTION_CONSUME` debit / `PRODUCTION_OUTPUT` credit rule resolves to was WIP, so an expense account used by a rule that expenses consumption pulled every ordinary expense posting on it into `late_cost_and_other` | `f241f10c` | `IprReview2FixesIT.expenseRuleAccountIsNotWip` — red: `FAILED ...expenseRuleAccountIsNotWip(): [WIP reconciliation {"glBalance":"60","openOrdersWip":"0","drift":"60","balanced":false,…"failingObjects":[{"kind":"late_cost_and_other",…,"glWip":"60",…` → green. Fix: a rule account of a known non-asset type (account `reportSection`, else its category's) counts only for lines a cost object explains (order, stage posting, process), so an order whose consumption is expensed ties as before; unexplained lines on it are left out and reported in the `wip_tieout` note (`non-asset rule account(s) … left out`). The mapped `wip` account and accounts of unknown type always count | `ManufacturingLifecycleIT`, `ManufacturingResidualsIT`, `StandardProcessCostingIT` (green) | yes / yes | read-only, no lock | manufacturing-wip-reconciliation.md §5.1 |
| R2-4 (Minor, deviation) | `costing/application/CostAdjustmentService.java:142-149`: the late-cost layers of a cancelled order were only `production_order_cancel` / `production_reversal` receipts (by order code); a reversed multi-stage order returns its materials as `production_stage_reversal` (referenceId = each stage posting), so it had no layers and its share went to COGS | `fb4817ef` | `IprReview2FixesIT.multiStageLateCostFollowsReturnedMaterials` — red: `FAILED ...multiStageLateCostFollowsReturnedMaterials(): [the share of reversed order MO-00001 went to cost of sales]` → green (no COGS line; the share follows the returned flour, on hand → adjunct; run line = the WIP share) | same IT (GRN, multi-stage order, stage-1 backflush output, landed cost voucher into WIP, `POST /production-orders/:id/stages/reverse`, cost adjustment run) + `ManufacturingLifecycleIT.lateCostOnCancelledOrder` (single-stage, green) | yes (+ report-only except `feature_risk`, + `wip_tieout`) / yes | Unchanged run path: advisory `cost-adjustment:<org>` → reads (one more read of `ProductionStagePosting`) → L7 journal | manufacturing.md (WIP-3 paragraph updated) |
| R2-5 (Minor) | `inventory/application/OpeningStockImportService.java:1346-1351` (after the R1 merge): the run reconciliation summed every `JournalEntry` citing the batch, drafts included | `c22f3e8c` (done here on the integrator's instruction once R1 was merged) | `OpeningStockImportIT.draftJournalIsNotReconciled` — red: `FAILED ...draftJournalIsNotReconciled(): [{"runId":…,"complete":true,"reconciled":false,…,"ledgerValue":"10","journalAmount":"20","equityCredit":"20","valuationDelta":"10",…` → green (`AND e.status::text = 'posted'`) | `OpeningStockImportIT` (whole class, green) | yes / yes | read-only | none (bug fix, no Nest parity: IPR feature) |
| S-2 (analytics, integrator request) | `analytics/application/InventoryKpiService.java:127` used the internal `reports.stockHealth(q)` (values always on) and the dashboard's own SQL values (`VALUE`, count / survey variance values, below-minimum drill-down `amount`) for every holder of `analytics:inventory`, with or without `inventory_valuation:read` | `c22f3e8c` | `IprReview2FixesIT.inventoryAnalyticsHidesValuesWithoutValuationRead` — red: `FAILED ...inventoryAnalyticsHidesValuesWithoutValuationRead(): [stockValue in {"domain":"inventory",…,"tiles":[{"key":"stockValue","value":"100","format":"money",…` (role with `analytics:inventory` only) → green: money tiles, aging chart values, table `value` columns and the `below-minimum` / `stockpile-surveys` drill-down `amount` are `null` through `CostVisibility`; counts and quantities stay; the admin still sees 100 | `AnalyticsIT` (green) | n/a (read-only dashboard) | read-only | analytics-kpis.md (Inventory, cost visibility) |

## 2. Requests to other streams

- None open. Item 5 (opening-import reconciliation status filter) was first to be a request to the integrator
  (R1 owned `OpeningStockImportService`); after R1 was merged the integrator asked R2 to do it, so it is fixed here
  (R2-5).
- Engine requests: none. No engine-core, `ProductionService` or stream-E3 file was edited (`InventoryValuationService`
  is only called: `glTieOut(asOf)` already supports a historical date).

## 3. Test runs

Final run on a **freshly reset** `erp-itdb-ipr3` (`docker rm -f -v` + new container), branch head `c22f3e8c` code,
one Gradle invocation (`spotlessApply fastTest --tests … --continue test --tests '*ArchitectureTests' --tests
'*ModularityTests'`), counts from the init script:

| Class | Tests | OK | Failed |
|---|---|---|---|
| inventory.audit.IprReview2FixesIT (R2-1 ×4, R2-2, R2-3, R2-4, S-2) | 8 | 8 | 0 |
| inventory.OpeningStockImportIT (incl. R2-5) | 10 | 10 | 0 |
| inventory.audit.IprPhase2AccountingIT (GL-10 updated) | 6 | 6 | 0 |
| inventory.audit.IprReview1IT | 11 | 11 | 0 |
| inventory.InventoryIntegrityP0IT | 16 | 16 | 0 |
| accounting.AccountingIT | 7 | 7 | 0 |
| accounting.CloseProcessIT | 8 | 8 | 0 |
| accounting.LedgerIntegrityIT | 6 | 6 | 0 |
| accounting.PeriodBoundsIT | 2 | 2 | 0 |
| accounting.PeriodCloseChecklistIT | 7 | 7 | 0 |
| accounting.PeriodReopenIT | 1 | 1 | 0 |
| analytics.AnalyticsIT | 9 | 9 | 0 |
| costing.AdvancedCostingIT | 5 | 5 | 0 |
| costing.CostCloseIT | 3 | 3 | 0 |
| manufacturing.CrushingIT | 11 | 11 | 0 |
| manufacturing.ManufacturingLifecycleIT | 11 | 8 | 3 (pre-existing on `fix/ipr-p15`, §4.2) |
| manufacturing.ManufacturingResidualsIT | 7 | 7 | 0 |
| manufacturing.MultiStageProductionIT | 12 | 12 | 0 |
| manufacturing.ProcessingIT | 19 | 19 | 0 |
| manufacturing.StageIntegrityIT | 7 | 7 | 0 |
| manufacturing.StandardProcessCostingIT | 4 | 4 | 0 |
| procurement.LandedCostLineageIT | 12 | 12 | 0 |
| procurement.LateCostWipCascadeIT | 5 | 5 | 0 |
| quarry.QuarryIT | 9 | 9 | 0 |
| quarry.QuarryRehabMovementsIT | 4 | 4 | 0 |
| ArchitectureTests | 5 | 5 | 0 |
| ModularityTests | 2 | 2 | 0 |

Baseline for the three `ManufacturingLifecycleIT` failures: the same class run on a freshly reset DB with the
`fix/ipr-p15` main code (`git checkout fix/ipr-p15 -- backend/src/main`, restored afterwards): 11 tests, 8 ok,
3 failed — `rework`, `byProducts`, `zeroYieldAndLoss`, identical messages. The full build was not run.

## 4. New defects found (not fixed here)

1. **Closing a period whose P&L account has a balance against its normal side fails.**
   `accounting/application/PeriodCloseService.java` (closing lines, `Line closing = creditNormal ? new Line(acct, bal, 0)
   : new Line(acct, 0, bal)`, posted by `postClosingEntry`): a debit-normal expense account with a net credit in the
   period (e.g. an adjustment that credits `stock_adjustment_expense`) gives a negative `bal`, and the CLOSING entry is
   refused with "Line amounts cannot be negative". Found while writing `periodVarianceFixedLaterStillHolds` (worked
   around in the test with a balance-sheet counter account). Not fixed: outside this stream, needs an accounting
   owner decision (flip the side when `bal < 0`).
2. **`ManufacturingLifecycleIT.rework`, `byProducts`, `zeroYieldAndLoss` fail on `fix/ipr-p15` itself** (run on a
   freshly reset DB with the `fix/ipr-p15` main code: 11 tests, 8 ok, 3 failed — the same three as on this branch):
   - `rework`: starting the rework order is refused, "Stock in QC Quarantine (QC-QUARANTINE) is on hold: it leaves
     only through a quality release, rejection or downgrade, …" (`inventory/application/StockService.java:2280`): the
     QC split moves the rework quantity to quarantine (X-2 / D2) and the rework order then consumes it from there.
   - `byProducts` / `zeroYieldAndLoss`: report-only `unlinked_restocks` fails for `production_reversal` outflows
     written after that type's first linked row — the output / partial / by-product issues of
     `manufacturing/application/ProductionService.java:1119-1141` carry no `reversesLedgerId` while
     `returnMaterials` (`:1145`) links its inflows, and `RESTOCK_SOURCES` treats `production_reversal` as both
     directions (`InventoryIntegrityService.java` `RESTOCK_SOURCES`). Shared org in the class, so the later
     `zeroYieldAndLoss` inherits it.
   Not caused by R2 (R2 touches neither path); `ProductionService` and the engine are not R2's to edit.
