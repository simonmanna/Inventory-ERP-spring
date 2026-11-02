# Cost flow — GL trace for every costing posting path (PC-0 deliverable)

- Roadmap: [`costing-production-ready-plan.md`](../roadmap/costing-production-ready-plan.md), PC-0 step 4
- Landed cost: [`landed-cost-gl-trace.md`](landed-cost-gl-trace.md) (done in LC-0)
- Proof comes at two levels:
  - **`CostingAuditIT`, exact (✅):** after every step, every new entry balances, the inventory/GL tie-out difference
    is unchanged, 1396 moves with the open adjunct, and **every account moved by the step's entries is expected**,
    by amount. Movement on an unexpected account fails the test.
  - **`BooksGuard`, uniform (🛡):** a JUnit extension on every accounting-regression suite. Around each test, every
    journal entry the test created balances and the tie-out difference is unchanged. Classification stays asserted by
    each suite's own GL expectations.
- Entries are recognised by id, not by clock: Hibernate stamps `createdAt` from the JVM clock, which can differ from
    the database clock.

**Conventions:**
- Accounts are named by mapping key (`stock_valuation`, `cogs`, …) or by chart code (1396, 1460, …).
- A STOCK_IN / STOCK_OUT / … posting rule, when configured for the product, replaces the default pair. The table shows
  the defaults (`StockPostingService.fallbackLines*`).
- ✅ exact (`CostingAuditIT` / `LandedCostAuditIT` / `SharedFxIT`). 🛡 guarded (the dedicated suite under `BooksGuard`). ⚠ known defect, fixed in the phase named.

## 1. Stock movements (`StockService` → `StockPostingService`)

| Event | Dr | Cr | Code | Stock-ledger row | Lineage target of a later cost | Status |
|---|---|---|---|---|---|---|
| Receipt (GRN) | `stock_valuation` | `grni_accrued` | `postReceipt` | `receipt` | the layer itself | ✅ |
| Sale / issue (AVCO, FIFO, STANDARD) | `cogs` | `stock_valuation` | `postIssue` → `fallbackLinesIssue` | `issue` | `cogs` | ✅ |
| Waste / expiry / internal use / promo | `stock_adjustment_expense` | `stock_valuation` | `postIssue` (movement types WASTE, EXPIRY_WRITE_OFF, INTERNAL_CONSUMPTION, PROMO_SAMPLE) | `waste` … | `scrap` | ✅ waste |
| Customer return restock | `stock_valuation` | `cogs` | `postReturnRestock` | `return_in` | layer | ✅ |
| Return to supplier | `grni_accrued` (debit note: AP) | `stock_valuation` | `postReturnToSupplier`; `DebitNotesService` (`skipGl` row + debit-note journal) | `return_to_supplier` | ⚠ `cogs` (LC-1, map F6) | ⚠ asserted only by `ProcurementIT` (not under `BooksGuard`); exact case added in LC-1 with the return policy |
| Transfer (immediate, or via transit) | stock (destination rule) | stock (source rule) | `postTransfer` | `transfer_out` + `transfer_in`, same `ledgerCode` | ⚠ `transfer` → adjunct for STANDARD, COGS for AVCO / lot (LC-1) | ✅ (value conserved) |
| Count / adjustment gain | `stock_valuation` | `stock_adjustment_income` | `postAdjustment` | `adjustment_in` | layer | ✅ countAdjustments |
| Count / adjustment loss | `stock_adjustment_expense` | `stock_valuation` | `postAdjustment` | `adjustment_out` | `cogs` | ✅ |
| Negative-stock cost correction (AVCO) | `cogs` ↔ `stock_valuation` | | `postNegativeStockCostCorrection` | — | — | LC-1 golden test |

## 2. Manufacturing (`ProductionService`, `StageExecutionService`, `ProcessCostingService`, `StandardCostService`)

| Event | Dr | Cr | Code | Status |
|---|---|---|---|---|
| Material issue to an order | `wip` | `stock_valuation` | `postProductionConsume` | ✅ production (WIP clears at completion) |
| Labour / overhead applied | non-staged order: **memo** on the order at absorption (no GL); at completion the output carries it: Dr stock / Cr `overhead_absorbed` | | `CostAbsorptionService` → `postProductionOutput`; staged orders via stage conversion; `ProcessCostingService` | ✅ absorption (memo + completion); 🛡 staged / process |
| Output (finished goods) | `stock_valuation` | `wip` | `postProductionOutput` | ✅ |
| Scrap / abnormal loss | `stock_adjustment_expense` / abnormal process loss | `wip` | `postProductionScrap` | 🛡 (`MultiStageProductionIT`, `CrushingIT`) |
| Standard-governed output | stock at standard + variance accounts (price, usage, labour, overhead, subcontract, yield) | `wip` | `StandardCostService.postProductionVariances` | 🛡 (`StandardProcessCostingIT`) |
| Late cost after completion (CM-10 run) | the output layers' lineage targets: adjunct / COGS / WIP / scrap | `wip` | `CostAdjustmentService` | 🛡 (`AdvancedCostingIT`); ⚠ a late landed charge after closure skips it (LC-1, F7) |

## 3. Valuation (`InventoryRevaluationService`, `StandardCostService`, `CostLineageService`, `MaterialLedgerService`)

| Event | Dr | Cr | Code | Status |
|---|---|---|---|---|
| Revaluation up (AVCO / FIFO) | stock | 5968 revaluation (or the document's account) | `InventoryRevaluationService` | ✅ |
| Revaluation down | 5968 | stock | same | ✅ revaluationDown |
| NRV write-down | 5975 NRV expense | 1397 NRV allowance (contra-inventory) | same | ✅ |
| NRV reversal (smaller gap) | 1397 | 5975, never beyond the allowance held (IAS 2.33) | same | ✅ nrvRecovery |
| New standard activated | stock ↔ standard cost revaluation variance account | | `StandardCostService` | ✅ standardActivation |
| Price-difference release (consumed share of open 1396 adjuncts) | `cogs` | 1396 | `CostLineageService.release` | ✅ (`ImportLandedScenarioIT`, `LandedCostAuditIT` STANDARD) |
| NRV allowance release (consumed share) | **code:** 1397 / Cr **NRV expense**; **ADR-S031:** "to cost of sales" | | same | ⚠ **V1:** classification differs from the ADR (COGS overstated, NRV expense understated; P&L total equal). `CostingAuditIT.nrvRelease` `@Disabled`, owner decision |
| Material ledger run (STANDARD actual costing) | stock ↔ 5965 material ledger revaluation | | `MaterialLedgerService` | 🛡 (`AdvancedCostingIT`) |
| Costing-method migration (AVCO ↔ FIFO) | none (value-neutral re-layering) | | `InventoryCostPolicyService` | 🛡 (`InventoryCostPolicyIT`) |

## 4. Period costing (`AbsorptionSettlementService`, `CostCycleService`, `CostAllocationService`)

| Event | Dr | Cr | Status |
|---|---|---|---|
| Absorption settlement (IAS 2.13) | output layers' lineage targets: 1396 / COGS / WIP / scrap | the absorbed / unabsorbed overhead accounts | 🛡 (`AbsorptionSettlementCausesIT`) |
| Financial allocation cycle | receivers (cost element preserved) | the sender's account | 🛡 (`AllocationCycleIT`) |

## 5. Quarry (`QuarryCostingService`, CM-5)

| Event | Dr | Cr | Status |
|---|---|---|---|
| Stripping above the ratio (IFRIC 20) | 1660 stripping asset | production cost | 🛡 (`QuarryIT`) |
| Stripping amortisation | ROM layers (lineage) | 1661 | 🛡 (suite of the section) |
| Rehabilitation initial | 1665 rehab asset | 2690 provision | 🛡 (`QuarryRehabMovementsIT`) |
| Unwinding | 7150 finance cost (never inventory) | 2690 | 🛡 (suite of the section) |
| Production disturbance | ROM layers (lineage) | 2690 | 🛡 (suite of the section) |
| Utilisation | 2690 | cash / payables | 🛡 (suite of the section) |

## 6. Fleet and distribution (`FleetFuelPostingService`, `FleetWorkOrderCostService`, `DistributionCostingService`)

| Event | Dr | Cr | Status |
|---|---|---|---|
| Fuel from own tank | fleet fuel expense (vehicle cost centre) | stock | 🛡 (`FleetCostingIT`) |
| Workshop part issue / return | maintenance expense / stock | stock / maintenance expense | 🛡 (suite of the section) |
| Delivery cost per trip | 6460 outbound freight | 5385 own fleet cost recharged | 🛡 (`DistributionBranchScenarioIT`) |
| Branch transfer | stock (destination branch) + 1299 due to | stock (source branch) + 1299 due from | 🛡 (suite of the section) |
| Inbound freight voucher | landed cost (see the landed trace) | 2155 | ✅ (LC-0) |

## 7. Projects (`ProjectCostService`, `ContractService`, CM-8)

| Event | Dr | Cr | Status |
|---|---|---|---|
| Stock issue to WBS | 1460 project WIP (project, WBS dimensions) | stock | 🛡 (`ProjectCostScenarioIT`) |
| Timesheet / equipment capture | 1460 | 5390 / 5391 absorbed | 🛡 (suite of the section) |
| Revenue over time (cumulative catch-up) | 1470 contract asset / 5610 cost of revenue | 4610 revenue / 1460 | 🛡 (`ContractRevenueScenarioIT`) |
| Onerous provision | 6990 | 2490 | 🛡 (suite of the section) |

## 8. FX (PC-0, ADR-S054)

| Event | Dr | Cr | Status |
|---|---|---|---|
| Foreign document posting | at the explicit rate, else the table rate; refused without one; snapshot | | ✅ `SharedFxIT` |
| Settlement at another rate | AP / AR relieved at the carrying rate; bank at the payment rate | `fx_gain` / `fx_loss` only | ✅ `SharedFxIT` |

## Coverage

- **Every row is exact (✅) or guarded (🛡)**, except return to supplier (⚠, LC-1).
- 🛡 rows rely on their dedicated suite's own GL assertions for classification. Moving them to exact checks happens as
  each area's phase is worked on: PC-1 (valuation), PC-4 (manufacturing / quarry), PC-5 (distribution / projects).
- Findings from this trace:
  - **V1:** NRV release classification, above. Owner decision.
  - **Absorption:** for non-staged orders it is a memo until completion. This is correct, but the trace previously
    implied an immediate Dr WIP. Now documented.
