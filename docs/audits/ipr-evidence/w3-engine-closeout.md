# IPR wave 3 — engine and accounting close-out (stream E3)

Branch `fix/ipr-w3` (off `fix/ipr-p15`, merged with `fix/ipr-p15` at 3f530272 for stream H). Not pushed, not merged,
not deployed. No migration used (V716–V719 stay spare). Rules: R1 (no UPDATE/DELETE of ledger or posted journals;
corrections are compensating rows), R2 (lock order L1–L7), no auto-repair of data.

## How red and green were run

- **Red.** All fixes were saved as a patch (`git diff -- backend/src/main packages`), the main sources were checked out
  at the merge base of the branch (no `git stash`), the one test that calls a new method (`InvoiceGoodsRoundingTest`)
  was moved aside, and the new and changed ITs ran against the base code. Then the patch was applied back.
- **Green.** Final run on a freshly reset `erp-itdb-ipr0` (`docker rm -f -v`, new `postgres:16`). Counts are in §4.

## 1. Evidence per finding

| Finding | Root cause (file:line, base) | Fix commit | Regression IT (red → green) | Workflow IT | Integrity / GL asserted | Lock levels | Deviations |
|---|---|---|---|---|---|---|---|
| C-3 / GL-6 (D3) receipt cost | blank cost → 0 / cost price: `StockService.receiveCore`; AVCO cost-price fallback `InventoryValuationService.currentValueByQuant` (`avg_cost` CASE); GRN `GoodsReceiptsService` no-cost line → costPrice; PO receive `PurchaseOrdersService` | 654b2163, 1f1de2b0 | `IprWave3EngineIT.receiptCostPolicy` — red: `FAILED …receiptCostPolicy()` (blank cost valued at cost price, 140 ≠ 100) → green | same (direct stock-in, zero cost with reason) | yes / yes | unchanged: L3 → L4 quant → L5 → L7 | inventory.md 67 |
| E-1 AVCO 6 dp drift | `runningAverageCost numeric(20,6)`; valuation = qty × average | 654b2163 | `IprWave3EngineIT.avcoNonTerminatingCost` — red: `valuation_vs_subledger … "valuationValue":"999999.9","ledgerValue":"999999.99999","difference":"-0.09999"` → green | `ManufacturingResidualsIT.legacyReversalPutsBackThePool` (now asserts no failing check) | yes / yes (on hand, part issued, zero) | read-only | inventory.md 69 |
| S13 waste / expiry accounts | `StockPostingService.fallbackLinesIssue` (WASTE / EXPIRY → `stock_adjustment_expense`) | 654b2163 | `IprWave3EngineIT.wasteAndExpiryAccounts` — red: `FAILED …wasteAndExpiryAccounts()` (waste account delta 0 ≠ 8) → green | same | yes / yes | L7 only | inventory.md 68 |
| S13 goods in transit | `StockPostingService.postTransfer` (rules only, no transit account); `inventoryAccountIds` without `stock_in_transit` | 654b2163 | `IprWave3EngineIT.goodsInTransit` — red: `FAILED …goodsInTransit(): [dispatched into transit]` → green | same (dispatch, loss in transit, arrival) | yes / yes (+ `gl_tieout_by_account` while in transit) | L7 only (stamp swap) | inventory.md 68 (opt-in) |
| Material-ledger per-product GL | `MaterialLedgerService.post`: `determination.mapped("stock_valuation")` for price-difference value rows | b9c4c199 | `AdvancedCostingIT.materialLedger` — red: `FAILED …materialLedger()` (stock valuation +45, adjunct 0) → green (adjunct 1396 = 45, checks ok, reversal → 0) | same | `valuation_vs_gl_by_product`, `gl_tieout_by_account`, `valuation_vs_subledger` | L1 run row → L7 | costing.md |
| X-8 timing change leaves sale unissued | `PosEventSubscribers` ORDER_CLOSED enqueues only under `at_payment`; `generateInvoice` enqueues only under `at_invoice` | 17b483e7 | `IprPosRefundRestockIT.timingChangeStillIssues` — red: `FAILED …timingChangeStillIssues(): [the closed sale got its one job]` → green | same (bill, switch timing, pay, drain) | yes (report-only too) / yes | job insert only; drain as before | pos.md 18 |
| 1d F6 vendor-bill cancel | `InvoicingWorkflowsInitializer.returnBillReceivedStock` issued by FIFO / average, unlinked, no shortfall check | 1f1de2b0 | `IprWave3EngineIT.billCancelIssuesBack` — red: `[bill receipt names its bill line]` → green; `InventoryConcurrencyIT.billCancelRacesSale` — red: `both won: -4.000000` → green | same | yes / yes | L1 bill → L2 receipt rows FOR UPDATE → L4 → L5 → L6 lots → L7 | inventory.md 72, invoicing.md |
| 1d sourceLineId GRN / PO / bill | moves without `sourceLine` | 1f1de2b0 | part of `billCancelIssuesBack` (bill line) | — | — | none | inventory.md 72 |
| SW-4 production links | `ProductionService` consume / output without `sourceLine`; reversal issues without `.reverses` | 807226c6 | `ManufacturingResidualsIT.reversalLinksItsReceipts` — red: `[consume / output rows without their line]` → green | same (complete, reverse) | `green()` (integrity + report-only + GL + WIP) | reads only | manufacturing.md |
| E-1a production consume / SO delivery honour reservations | consume move without `honourReservations`; fleet SO delivery checked outside the engine | 807226c6, 1f1de2b0 | `InventoryConcurrencyIT.reservationRacesIssue` (green both runs: the engine guard existed; proves the merged guard under race) | — | yes / yes | L4 → L5 → L6 | inventory.md 71 |
| Reservation guard merge | two copies (`StockService.assertSalesReservationsLeft`, `StockReservationService.assertNotReservedForSalesOrders`) | 654b2163 | existing F5 ITs (`DeliveryResidualsIT.reservationRefusalOn*`) green | — | — | unchanged | inventory.md 71 |
| E-2a `InvoiceGoodsService` base qty | `qty × factor` | 1f1de2b0 | covered by fleet/invoicing regression | — | — | none | invoicing.md |
| Pre-R4 POS restock cap | `PosInvoiceService.restockLine` proportional share not net of earlier returns | 17b483e7 | `IprPosRefundRestockIT.preR4RestockCapped` — red: `[2 still out come back, not 3 × 2 / 2]` → green | same (own organisation) | yes (report-only too) / yes | L1 invoice → engine L2–L7 | pos.md 17 |
| Review BLOCKER refund over-restock | `PosInvoiceService.restockLinked`: share of `quantity − refundedQty`, `refundedQty` counts waste / no_return | 17b483e7 | `IprPosRefundRestockIT.noReturnThenRestockDoesNotOverRestock` — red: `[3 of 10 issued, not 10 × 3 / 6]` → green | same | yes / yes | unchanged | pos.md 17 |
| Review MAJOR unit rounding | `InvoiceGoodsService.undelivered` `base / factor` HALF_UP 3 dp; `FleetDeliveryOrderService.fromInvoiceAwaiting` HALF_UP | 1f1de2b0 | `InvoiceGoodsRoundingTest` (unit; new helper, no red run possible against the base) | — | — | none | invoicing.md, fleet.md |
| PLM rework quarantine release | no release path (X-3 guard keeps quarantine closed) | ccc5d50b | `PlmIT.returnAndRework` — red: `Cannot POST …/rework-release` (404) → green | same | — | L1 ECO FOR UPDATE → engine L3–L6 | plm.md |
| E1 rollback-only risk | `StockService.correctLateCountRows`: proxied `postAdjustment` throw marks the tx rollback-only; SAVEPOINT cannot undo it | 654b2163 | `CountCutoffIT.unpostableCorrectionKeepsTheMovement` — red: `"status":"failed","httpStatus":500,"error":"Transaction silently rolled back because it has been marked as rollback-only"` → green | `CountCutoffIT.correctionLinks` | GL tie-out | unchanged | inventory.md 70 |
| N-1 duplicate component | `ProductionService.confirm` reserves per line; upsert sets quantity | 807226c6 | `ManufacturingResidualsIT.sameComponentTwiceReservesBoth` — red: `[both lines reserved]` → green | same (start, complete) | `green()` | L1 → per cell L4 → L5 → L6, product order | manufacturing.md |
| Review: negative remaining pool | `ProductionService.complete` checks `pool < 0` before subtracting partials; partials skip by-products expected at 0 | 807226c6 | `ManufacturingResidualsIT.byproductExpectedZeroAndPartials` — red: complete → 500 (ledger insert with a negative cost) → green | same | `green()` | unchanged | — |
| Review: partial overhead account | partial used `postOverheadAbsorption`; reversal reversed only the latest rated entry | 807226c6 | **no red IT** (needs work-centre rates with credit accounts; not built) | `ManufacturingLifecycleIT.partialOutput` green | — | L1 → L7 | — |
| Review: backdated partial output | no `date` / `effectiveAt` on the move, no back-date limit | 807226c6 | `ManufacturingResidualsIT.backdatedPartialOutput` — red: 200 with the order body → green 400 | — | `green()` | unchanged | — |
| Review: legacy reversal refused | `fgBase` from `producedQty` already includes partials | 807226c6 | `ManufacturingResidualsIT.legacyReversalWithPartials` — red: `Cannot reverse: finished goods are no longer fully in stock` → green | same | `green()` | unchanged | — |
| New defect: rework order cannot start (X-3 × rework) | found by the broad regression: `ProductionService.start` consumes the rework order's held units from QC quarantine; the X-3 guard (`StockService.assertGuardedLocation`) refused it | 37693d24 | `ManufacturingLifecycleIT.rework` — red in the first final run: `Stock in QC Quarantine (QC-QUARANTINE) is on hold` → green | `ShopFloorIT.abnormalRework` (setup now puts the extra flour on hold by an immediate transfer, X-3 refuses a manual stock-in) | `green()` | unchanged | manufacturing.md |
| Test infra `owner()` fallback | `InventoryAuditSupport.owner()` needed Testcontainers details | b181b22d | `OpeningStockImportIT` (local override removed) | — | — | — | — |

## 2. T19 `InventoryConcurrencyIT` and reused race ITs

New (in-process, `SET LOCAL lock_timeout = '20s'`, `race` helper — a 40P01 fails the test):
`billCancelRacesSale`, `doubleBillCancel`, `reservationRacesIssue`.

Reused (already prove their case; not repeated): `fleet/DeliveryConcurrencyIT:111` (credit note + invoice cancel +
POD), `:140` (issue-undelivered + delivery order); `IprPosRefundRestockIT.refundRacesOrderCancel`;
`IprSerialRestockIT.parallelSameSerial`, `.parallelLastUnit`, `.parallelReserveAndIssue`;
`CountCutoffIT.submitsRaceSales` (frozen and not frozen), `CountCutoffIT.oneDraftPerLocation`;
`costing/MaterialLedgerRaceIT`.

## 3. Not done / partly done

- **Optional `StockService.lockFreezeExclusive(message)`** — not done.
- **Partial overhead to rated accounts** — fixed, no red IT (see table).
- **Complete backdate limit** — `receiveOutput` now dates the receipt; the back-date limit is applied to partial output
  only (completion keeps Nest behaviour).
- **Goods in transit limits** — residue sweep / reversal cost difference at a transit location still post to the stock
  account; turning the setting on with stock in transit is not reclassified (owner item).
- **Web** — no `zeroCostReason` input on the stock-in form (follow-up).

## 4. Final run (fresh DB)

First full run (`final1`, fresh DB, commits up to 18233806): 310 classes, 2003 tests, 1996 ok, 7 failed. Triage:

| Failure | Cause | Action |
|---|---|---|
| `GraniteQuarryDemoSeedIT.worksheet` (1400 = 17,555,600 ≠ 17,555,000) | D3: the seeded widget stock has a blank cost and is now received at its current cost (600) | expectation updated (93117389) |
| `MaterialLedgerRaceIT` "one post's delta" | the test pinned the material-ledger defect this wave fixed | now asserts all checks green and adjunct = 45 (93117389) |
| `InventoryEngineGapsIT.negativeUnitCostRefused` | D3: explicit 0 needs `zeroCostReason` | reason added (93117389) |
| `InventoryHardeningIT.ledgerGuardBypass` "url cannot be null" | external DB: no Flyway owner URL | environment fallback as in `owner()` (93117389) |
| `ManufacturingLifecycleIT.rework` | **new defect**, X-3 × rework (table §1) | fixed 37693d24 |
| `ShopFloorIT.abnormalRework` | test put stock into QC quarantine by a manual stock-in (X-3 refuses) | setup by transfer (37693d24) |
| `ManufacturingReadyIT.cancelSurvivesReleaseFailure` | env only: `CREATE TRIGGER` needs the owner role on the external DB (known since wave 1.5) | none |

Second full run (`final2`, freshly reset DB, HEAD 93117389):



Patterns: `com.inventoryerp.{inventory,pos,sales,procurement,manufacturing,qualitycontrol,quality,fleet,plm,costing,invoicing}.*`
and `*AuthorizationMatrixIT`. **308 classes, 1986 tests, 1964 ok, 22 failed, 0 skipped** (`CartVectorParityTest` prints
its count twice; counted once).

The 22 failures, none in code this wave changed:

- `ManufacturingReadyIT.cancelSurvivesReleaseFailure` (1): env only, as in `final1`.
- 21 in the known UTC / business-date window (the run passed 21:00 UTC; DEMO's business date is Kampala, already
  tomorrow): `AbsorptionSettlementCausesIT` 5, `LandedCostCustomsIT` 1, `LandedCostProfitabilityIT` 8,
  `LandedCostReportsIT` 6, `PurchaseReadinessPayIT.apAgingTiesToTheLedger` 1 (reconciliation `asOf` = UTC today leaves
  out the Kampala-dated payment: difference 200). The same 5 classes were **37 / 37 green in `final1`** on a fresh DB
  before 21:00 UTC; the only main-code change between the runs is 37693d24 (rework orders only).

`ManufacturingLifecycleIT` 11 / 11 (integrator request: `rework` fixed by 37693d24 in `ProductionService`;
`byProducts` and `zeroYieldAndLoss` — `unlinked_restocks` on the reversal's output / by-product issues — were already
fixed by 807226c6, which links those issues with `reversesLedgerId`; the detector was **not** narrowed).

`test --tests '*ArchitectureTests' --tests '*ModularityTests'`: ArchitectureTests 5 / 5, ModularityTests 2 / 2.

Totals per package (tests / ok / failed):

| Package | Classes | Tests | OK | Failed |
|---|---|---|---|---|
| AuthorizationMatrixIT | 1 | 2 | 2 | 0 |
| costing | 20 | 61 | 61 | 0 |
| fleet | 44 | 278 | 278 | 0 |
| inventory | 52 | 398 | 398 | 0 |
| invoicing | 16 | 97 | 97 | 0 |
| manufacturing | 47 | 297 | 291 | 6 |
| plm | 15 | 62 | 62 | 0 |
| pos | 21 | 144 | 144 | 0 |
| procurement | 35 | 299 | 283 | 16 |
| quality | 6 | 22 | 22 | 0 |
| qualitycontrol | 11 | 67 | 67 | 0 |
| sales | 40 | 259 | 259 | 0 |
| **all** | 308 | 1986 | 1964 | 22 |

<details><summary>Per-class counts (tests ok failed)</summary>

```
AuthorizationMatrixIT 2 2 0
costing.AdvancedCostingIT 5 5 0
costing.AllocationCycleIT 5 5 0
costing.AllocationExceptionRegisterTest 1 1 0
costing.CostCloseIT 3 3 0
costing.CostExplanationIT 2 2 0
costing.CostingReversalIT 1 1 0
costing.CostingSettingsIT 5 5 0
costing.DistributionBranchScenarioIT 2 2 0
costing.GraniteQuarryDemoSeedIT 1 1 0
costing.MaterialLedgerRaceIT 1 1 0
costing.ProfitabilityReconciliationIT 2 2 0
costing.SimulationIsolationIT 1 1 0
costing.SimulationIsolationTest 1 1 0
costing.ValuationIntegrityIT 5 5 0
costing.application.AbcCalculatorTest 4 4 0
costing.application.AbsorptionSettlementCalculatorTest 4 4 0
costing.application.AllocationCalculatorTest 7 7 0
costing.application.DistributionCostCalculatorTest 4 4 0
costing.application.MaterialLedgerCalculatorTest 4 4 0
costing.application.SimulationEngineTest 3 3 0
fleet.DeliveryConcurrencyIT 3 3 0
fleet.DeliveryLifecycleIT 8 8 0
fleet.DeliveryResidualsIT 4 4 0
fleet.FleetClaimsIT 8 8 0
fleet.FleetCostingIT 8 8 0
fleet.FleetDispatchIT 12 12 0
fleet.FleetDriverAppIT 5 5 0
fleet.FleetDriversIT 8 8 0
fleet.FleetFuelIT 9 9 0
fleet.FleetIncidentsIT 6 6 0
fleet.FleetMaintenanceIT 8 8 0
fleet.FleetPhase1EndToEndIT 3 3 0
fleet.FleetPlantIT 7 7 0
fleet.FleetRegistryIT 14 14 0
fleet.FleetRemindersPayrollIT 3 3 0
fleet.FleetTelematicsIT 5 5 0
fleet.FleetTyresIT 5 5 0
fleet.FleetWeighbridgeDispatchIT 7 7 0
fleet.FleetWeighbridgeIT 14 14 0
fleet.application.AllowanceCalculatorTest 4 4 0
fleet.application.DispatchPoliciesTest 11 11 0
fleet.application.DispatchPoliciesTest$Charges 2 2 0
fleet.application.DispatchPoliciesTest$Freight 1 1 0
fleet.application.DispatchPoliciesTest$Planner 6 6 0
fleet.application.DispatchPoliciesTest$StateMachine 2 2 0
fleet.application.DriverEligibilityPolicyTest 5 5 0
fleet.application.FleetCostingPoliciesTest 9 9 0
fleet.application.FleetDeliveryNoteWeighLineTest 4 4 0
fleet.application.FleetMaintenancePoliciesTest 12 12 0
fleet.application.FleetMaintenancePoliciesTest$Availability 4 4 0
fleet.application.FleetMaintenancePoliciesTest$Due 4 4 0
fleet.application.FleetMaintenancePoliciesTest$Rules 4 4 0
fleet.application.FleetPoliciesTest 11 11 0
fleet.application.FleetPoliciesTest$Compliance 2 2 0
fleet.application.FleetPoliciesTest$Meter 4 4 0
fleet.application.FleetPoliciesTest$Status 4 4 0
fleet.application.FleetSafetyPoliciesTest 9 9 0
fleet.application.FuelPoliciesTest 8 8 0
fleet.application.LoadLineQuantitiesTest 9 9 0
fleet.application.RestTelematicsProviderTest 2 2 0
fleet.application.WeighbridgeDispatchRulesTest 3 3 0
fleet.application.WeighbridgePoliciesTest 10 10 0
fleet.application.WeightAllocationTest 3 3 0
fleet.application.WeightAllocationTest 8 8 0
inventory.CountCutoffIT 11 11 0
inventory.InventoryBranchScopeIT 7 7 0
inventory.InventoryCostPolicyIT 2 2 0
inventory.InventoryEngineGapsIT 13 13 0
inventory.InventoryEngineHardeningIT 13 13 0
inventory.InventoryFeatureRiskMapDocTest 1 1 0
inventory.InventoryHardeningIT 7 7 0
inventory.InventoryIT 21 21 0
inventory.InventoryIntegrityAlertsIT 3 3 0
inventory.InventoryIntegrityIT 12 12 0
inventory.InventoryIntegrityP0IT 16 16 0
inventory.InventoryLedgerImmutabilityTest 2 2 0
inventory.InventoryReadsIT 4 4 0
inventory.InventoryRegisterIT 3 3 0
inventory.InventoryReportsHardeningIT 8 8 0
inventory.InventoryReturnsIdentityIT 8 8 0
inventory.InventoryUnitTest 24 24 0
inventory.InventoryUnitTest$CostResolverSpec 17 17 0
inventory.InventoryUnitTest$DecimalString 1 1 0
inventory.InventoryUnitTest$StockPostingSpec 6 6 0
inventory.InventoryValuationReportIT 3 3 0
inventory.NegativeStockPolicyIT 8 8 0
inventory.OpeningStockIT 2 2 0
inventory.OpeningStockImportIT 9 9 0
inventory.ReplenishmentCalculatorTest 4 4 0
inventory.ReplenishmentIT 10 10 0
inventory.StandardCostingIntegrityIT 5 5 0
inventory.StockConcurrencyIT 6 6 0
inventory.StockEffectiveTimeIT 8 8 0
inventory.StockLockOrderTest 1 1 0
inventory.StockReservationOverviewIT 3 3 0
inventory.ValuationGapsIT 10 10 0
inventory.VariantIntegrityIT 8 8 0
inventory.WarehouseHardeningIT 5 5 0
inventory.WarehouseIT 11 11 0
inventory.WarehouseReplenishmentGapsIT 13 13 0
inventory.application.AbcClassifierTest 3 3 0
inventory.application.AvcoLandedMathTest 6 6 0
inventory.application.AvcoLineageCalculatorTest 5 5 0
inventory.audit.InventoryAuditConcurrencyIT 8 8 0
inventory.audit.InventoryAuditConservationIT 13 13 0
inventory.audit.InventoryAuditCrashBoundaryIT 9 9 0
inventory.audit.InventoryAuditRemediationIT 13 13 0
inventory.audit.InventoryAuditTenancyLocationIT 6 6 0
inventory.audit.InventoryConcurrencyIT 3 3 0
inventory.audit.IprBranchScopeCostIT 3 3 0
inventory.audit.IprPhase2AccountingIT 6 6 0
inventory.audit.IprPosRefundRestockIT 6 6 0
inventory.audit.IprResidualsIT 2 2 0
inventory.audit.IprSerialRestockIT 16 16 0
inventory.audit.IprStockControlsIT 9 9 0
inventory.audit.IprWave3EngineIT 5 5 0
invoicing.CartVectorParityTest 9 9 0
invoicing.CreditControlIT 9 9 0
invoicing.FxDepositsIT 1 1 0
invoicing.FxPaymentsIT 3 3 0
invoicing.InvoicingIT 8 8 0
invoicing.MobileMoneyIT 7 7 0
invoicing.MobileMoneyProviderTest 3 3 0
invoicing.SalesAnalysisIT 2 2 0
invoicing.SharedFxIT 8 8 0
invoicing.SupplierPaymentScheduleIT 3 3 0
invoicing.TaxCalculationServiceTest 11 11 0
invoicing.TaxCalculationServiceTest 22 22 0
invoicing.TaxPackIT 1 1 0
invoicing.VendorBillListIT 3 3 0
invoicing.application.CustomerDepositReceiptPdfTest 4 4 0
invoicing.application.InvoiceGoodsRoundingTest 3 3 0
manufacturing.AbsorptionSettlementCausesIT 6 1 5
manufacturing.CostingAuditIT 17 17 0
manufacturing.CostingEngineeringIT 2 2 0
manufacturing.CrushingIT 11 11 0
manufacturing.DemandForecastCalculatorTest 6 6 0
manufacturing.ManufacturingCoreIntegrityIT 11 11 0
manufacturing.ManufacturingCostingIT 6 6 0
manufacturing.ManufacturingHardeningIT 5 5 0
manufacturing.ManufacturingIT 10 10 0
manufacturing.ManufacturingIntegrityIT 4 4 0
manufacturing.ManufacturingLifecycleIT 11 11 0
manufacturing.ManufacturingReadyIT 12 11 1
manufacturing.ManufacturingResidualsIT 12 12 0
manufacturing.MrpPlanningIT 1 1 0
manufacturing.MultiStageProductionIT 12 12 0
manufacturing.Phase2ExitIT 1 1 0
manufacturing.PlanningIT 10 10 0
manufacturing.PlanningIntegrityIT 10 10 0
manufacturing.PrecastManufacturingIT 11 11 0
manufacturing.ProcessCostingGuardsIT 1 1 0
manufacturing.ProcessingIT 19 19 0
manufacturing.ProductionMaterialsOnHandIT 1 1 0
manufacturing.ProductionReversalOverheadIT 1 1 0
manufacturing.ProductionUnitsIT 4 4 0
manufacturing.ReplenishmentMrpIT 7 7 0
manufacturing.ShopFloorExecutionIT 4 4 0
manufacturing.ShopFloorIT 3 3 0
manufacturing.StageIntegrityIT 7 7 0
manufacturing.StandardProcessCostingIT 4 4 0
manufacturing.StandardUnitsYieldIT 1 1 0
manufacturing.application.BatchScalerTest 4 4 0
manufacturing.application.BomExplodeTest 10 10 0
manufacturing.application.CapacityCalculatorTest 3 3 0
manufacturing.application.CostAbsorptionCalculatorTest 4 4 0
manufacturing.application.CrushingCalculatorTest 8 8 0
manufacturing.application.DemandNettingTest 3 3 0
manufacturing.application.JointCostAllocatorTest 11 11 0
manufacturing.application.MachineAdaptersTest 2 2 0
manufacturing.application.MachineEventParserTest 3 3 0
manufacturing.application.MaturityCalculatorTest 2 2 0
manufacturing.application.OeeCalculatorTest 4 4 0
manufacturing.application.OrderRollupTest 4 4 0
manufacturing.application.PlanningCalendarTest 11 11 0
manufacturing.application.ProcessCostCalculatorTest 5 5 0
manufacturing.application.ShiftClockTest 4 4 0
manufacturing.application.StageYieldCalculatorTest 6 6 0
manufacturing.application.VarianceCalculatorTest 3 3 0
plm.BomRebaseTest 6 6 0
plm.PlmAuditFixesIT 2 2 0
plm.PlmDemoSeedIT 1 1 0
plm.PlmErpIntegrationIT 4 4 0
plm.PlmExceptionsIT 5 5 0
plm.PlmIT 8 8 0
plm.PlmIntegrityIT 8 8 0
plm.PlmModuleAccessTest 5 5 0
plm.PlmModuleIT 2 2 0
plm.PlmOfflineTillIT 1 1 0
plm.PlmPolicyIT 4 4 0
plm.PlmReportsIT 3 3 0
plm.PlmTypesIT 5 5 0
plm.PlmVersionsIT 5 5 0
plm.application.PlmOwnerDefaultsTest 3 3 0
pos.BusinessZoneTest 2 2 0
pos.OrderCancelStockIT 10 10 0
pos.PosCashDrawerIT 6 6 0
pos.PosFlows2IT 5 5 0
pos.PosFlows3IT 3 3 0
pos.PosFlows4IT 6 6 0
pos.PosFlowsIT 4 4 0
pos.PosHardeningIT 5 5 0
pos.PosMenuIT 4 4 0
pos.PosMoneyIT 8 8 0
pos.PosSaleIT 14 14 0
pos.PosStockPostingIT 10 10 0
pos.PosStockPostingRecoveryIT 2 2 0
pos.PosTablesIT 9 9 0
pos.PosUnitTest 18 18 0
pos.PosUnitTest$LineSignature 3 3 0
pos.PosUnitTest$MutationPolicy 7 7 0
pos.PosUnitTest$Pricing 8 8 0
pos.PosVariantIntegrityIT 16 16 0
pos.PostingMonitorAbandonIT 1 1 0
pos.SalesReturnsIT 3 3 0
procurement.ImportLandedScenarioIT 2 2 0
procurement.LandedCostAccrualManagementIT 5 5 0
procurement.LandedCostAuditIT 22 22 0
procurement.LandedCostControlsIT 10 10 0
procurement.LandedCostCustomsIT 6 5 1
procurement.LandedCostEndToEndIT 2 2 0
procurement.LandedCostGapsIT 17 17 0
procurement.LandedCostLineageIT 12 12 0
procurement.LandedCostProfitabilityIT 10 2 8
procurement.LandedCostReportsIT 6 0 6
procurement.LandedCostVoucherIT 8 8 0
procurement.LateCostWipCascadeIT 5 5 0
procurement.PlanningIT 4 4 0
procurement.PoBillApplicationIT 4 4 0
procurement.ProcureToPayGapsIT 19 19 0
procurement.ProcurementControlIT 9 9 0
procurement.ProcurementControlsGapsIT 12 12 0
procurement.ProcurementControlsIT 6 6 0
procurement.ProcurementIT 22 22 0
procurement.ProcurementIntegrityIT 13 13 0
procurement.ProcurementSupplierPolicyIT 8 8 0
procurement.ProductUnitsIT 14 14 0
procurement.PurchaseReadinessBillsIT 14 14 0
procurement.PurchaseReadinessPayIT 10 9 1
procurement.PurchaseReadinessReceiptsIT 9 9 0
procurement.PurchaseReadinessReturnsIT 9 9 0
procurement.ReadinessBlockersIT 9 9 0
procurement.ReceiptBillLockOrderIT 3 3 0
procurement.ReceivingQualityIT 4 4 0
procurement.RequisitionToOrderIT 4 4 0
procurement.SourceToPayEndToEndIT 1 1 0
procurement.SourcingIT 3 3 0
procurement.SupplierClaimSettlementIT 11 11 0
procurement.SupplierPerformanceIT 3 3 0
procurement.application.SupplierPerformanceServiceTest 3 3 0
quality.QualityLotsIT 7 7 0
quality.QualityNcrTraceIT 5 5 0
quality.application.CsvLabResultsTest 2 2 0
quality.application.LabAdaptersTest 2 2 0
quality.application.SamplingCalculatorTest 1 1 0
quality.application.SpecEvaluatorTest 5 5 0
qualitycontrol.MeasureEvaluationTest 3 3 0
qualitycontrol.QcBlockingPolicyTest 4 4 0
qualitycontrol.QcBlockingPolicyTest 6 6 0
qualitycontrol.QualityControlAlertsIT 6 6 0
qualitycontrol.QualityControlFlowIT 14 14 0
qualitycontrol.QualityControlIT 10 10 0
qualitycontrol.QualityControlStatsIT 3 3 0
qualitycontrol.TestQuantityCalculatorTest 3 3 0
qualitycontrol.WorksheetEvaluatorTest 5 5 0
qualitycontrol.application.QcCheckGeneratorTest 5 5 0
qualitycontrol.application.SpcCalculatorTest 8 8 0
sales.CashSaleIT 25 25 0
sales.CentralPricingIT 5 5 0
sales.SalesAccessIT 4 4 0
sales.SalesAccountingPolicyIT 4 4 0
sales.SalesAgreementIT 3 3 0
sales.SalesAtpIT 4 4 0
sales.SalesBillingIT 7 7 0
sales.SalesCollectionsIT 4 4 0
sales.SalesCommissionTargetIT 5 5 0
sales.SalesCreditPolicyIT 8 8 0
sales.SalesCustomerIT 5 5 0
sales.SalesCustomerWeightIT 5 5 0
sales.SalesDeliveryIT 8 8 0
sales.SalesDeliveryManagementIT 8 8 0
sales.SalesFoundationIT 8 8 0
sales.SalesFulfilmentIT 2 2 0
sales.SalesHandoverIT 5 5 0
sales.SalesInsightsIT 4 4 0
sales.SalesInvoiceIntegrityIT 3 3 0
sales.SalesInvoiceabilityIT 9 9 0
sales.SalesLifecycleIntegrityIT 4 4 0
sales.SalesOrderIT 9 9 0
sales.SalesOrderPolicyIT 5 5 0
sales.SalesPricingIT 4 4 0
sales.SalesPricingToolsIT 7 7 0
sales.SalesQuantityBucketsIT 4 4 0
sales.SalesQuotationProductionIT 15 15 0
sales.SalesQuoteProformaIT 11 11 0
sales.SalesReadinessR2R4IT 8 8 0
sales.SalesReadinessR6IT 12 12 0
sales.SalesReportCenterIT 3 3 0
sales.SalesReturnFlowIT 6 6 0
sales.SalesReturnIT 10 10 0
sales.SalesStabilizationIT 10 10 0
sales.SalesStatementIT 5 5 0
sales.SalesWeighbridgeIT 8 8 0
sales.VariantPricingIT 5 5 0
sales.application.CashSaleFormExporterTest 2 2 0
sales.application.SalesDocumentPdfTest 1 1 0
sales.application.SalesQuotationPdfTest 4 4 0
```

</details>

## 5. Owner sign-off items

Inventory deviations 67–72, POS 17–18, the manufacturing / invoicing / fleet / PLM / costing entries above, the new
setting `inventory.transitGlSeparation`, the new mapping keys `stock_waste_expense` / `stock_expiry_expense`, and the new
route `POST /plm/changes/:id/rework-release`.

## 6. Engine requests and new defects

Engine requests (not done here):

- `StockService.lockFreezeExclusive(message)` (optional item, not built).
- GIT: route the R5b residue sweep and the reversal cost difference at a transit location to Stock In Transit; a
  reclassification step for turning `inventory.transitGlSeparation` on with stock already in transit.
- Web: `zeroCostReason` input on the direct stock-in form (the API refuses an explicit 0 without it).

New defects:

- **Fixed (37693d24):** a rework order could not start: its consumption out of QC quarantine was refused by the X-3
  hold (`StockService.assertGuardedLocation`, `StockService.java:2337`; caller `ProductionService` consume loop).
- **Open (test only):** `ManufacturingReadyIT.approvedChangeFreezesSetup` takes the *last* PLM stage named
  "Engineering review" (`ManufacturingReadyIT.java:259-262`); on a reused DB another change type's stage of that name
  wins and the test fails ("Stage Engineering review belongs to another change type"). Green on a fresh DB.
- **Open (test only, known):** the UTC / business-date window ITs listed in §4.
