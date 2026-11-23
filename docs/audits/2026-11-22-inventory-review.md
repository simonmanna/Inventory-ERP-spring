# Inventory review 2026-11-22 — findings and hardening status

Owner-approved plan: hardening batches R0–R5 with four release gates (G1 quantity, G2 financial, G3 security and
concurrency, G4 operational). The engine core is sound (one move engine, move and journal in one transaction, posting
keys, locking, tenancy, idempotency); the defects sit in rental, returns and reversals, offline sync, branch scope,
location master data and STANDARD costing.

- Read-only data checks for historical damage: [2026-11-22-inventory-data-checks.sql](2026-11-22-inventory-data-checks.sql).
  Not run against any live database; no repair scripts. Each repair needs its own owner-approved plan.
- Live checks: `GET /api/v1/inventory/reports/integrity` and the daily integrity monitor (R0, see
  [spec](../spec/inventory.md#integrity-checks-r0)).

Note on numbering: P0-1, P1-1, P1-2, P1-3 and P1-13 carry the review's own ids. P1-4 to P1-12 are numbered here in
batch order from the plan's batch sections; map them back to the review text before quoting them elsewhere.

## Status

| id | finding | batch | status | test |
|---|---|---|---|---|
| R0-a | No automated reconciliation: ledger vs quant vs lots vs serials, valuation vs stock ledger vs GL, transit, reservations, offline queue | R0 | done | `InventoryIntegrityIT` |
| R0-b | No daily monitor raising integrity findings; value findings do not hold the period close | R0 | done | `InventoryIntegrityIT`, `PeriodCloseChecklistIT` |
| R0-c | No read-only assessment of historical data before repairs | R0 | done (queries written and validated on a test DB; not run on live data) | `docs/audits/2026-11-22-inventory-data-checks.sql` |
| P0-1 | Rental fee lines (RENT / LATE / DAMAGE / EXTEND) issue stock on the POS invoice; a missing fee product falls back to the rented product | R1 | fixed (R1) | `RentalIT` |
| P1-1 | Returns and reversals (cash-sale void, invoice cancel, credit-note restock, sales return, production `returnMaterials`, debit-note reversal) rebuild lot, expiry and serial identity from the product's current config: new auto-numbered lots, serials left `issued` | R1 | fixed (R1) | `InventoryReturnsIdentityIT`, `CashSaleIT` |
| P1-2 | Credit note on a cash-sale or fleet invoice finds no issue rows and falls back to a default location and today's cost | R1 | fixed (R1) | `InventoryReturnsIdentityIT` |
| P1-3 | Offline refund / void runs before the invoice's pending stock jobs are drained | R1 | fixed (R1) | `SyncInventoryRecoveryIT` |
| P1-4 | Stock reversal does not re-check the user's branch scope inside the moving transaction | R2 | fixed (R2) | `InventoryBranchScopeIT` |
| P1-5 | Transfer approve / dispatch / recall / cancel / receive do not re-check source or destination scope after the transfer lock | R2 | fixed (R2) | `InventoryBranchScopeIT` |
| P1-6 | Reservations: reserve, release, consume, list and overview not scope-checked or filtered; list unbounded | R2 | fixed (R2) | `InventoryBranchScopeIT` |
| P1-7 | Locations can be removed, deactivated, retyped or moved to another branch while they hold stock or live documents; manufacturing and rental insert locations directly | R2 | fixed (R2) | `InventoryBranchScopeIT` |
| P1-8 | STANDARD receipt with no active standard enters at actual cost without PPV | R3 | fixed (R3) | `StandardCostingIntegrityIT` |
| P1-9 | STANDARD returns not received at the standard; serial-tracked products accepted as STANDARD | R3 | fixed (R3) | `StandardCostingIntegrityIT` |
| P1-10 | Stock journals carry no product dimension: no product-level GL reconciliation | R3 | fixed (R3; per-product GL from the V613 cutover) | `StandardCostingIntegrityIT`, `InventoryIntegrityIT` |
| P1-11 | Order-level stock timings (`at_confirmation`, `at_fulfillment_complete`) can double-post when the timing changes | R4 | fixed (timings deprecated, one job per sale `sale:<orderId>`, old keys honoured) | `OrderCancelStockIT.oneJobPerSale`, `.orderTimingsDeprecated`, `KernelUnitTest` |
| P1-12 | Order cancel does not reverse a posted `pos_order` issue (or partial / returned states) | R4 | fixed (cancel table in pos.md; voided fired line issued once) | `OrderCancelStockIT` (pending, processing, posted, partially posted, partially returned, kitchen void, reopen, worker) |
| R4-a | Pick orders ignore reversals / returns of their source document; a document with a live pick order can be reversed | R4 | fixed | `WarehouseIT.pickOrderAndDocumentReversal` |
| P1-13 | Offline transfer receipt waiting for its dispatch is not crash-safe and can be applied twice | R1 | fixed (R1) | `SyncInventoryRecoveryIT` |
| P2-1 | Lock order: count submit, reservations per-quant lock, `createWave` id order, approval gates before locks | R5 | fixed (R5a: `createWave` id order; R5b: count submit and landed cost take the costing locks first, reservations the quant lock before the row, multi-line reservations sorted, count approval gate before locks with re-measure; other gates already ran before quant locks) | `InventoryEngineHardeningIT.countSubmitTakesCostingLockFirst`, `.reservationTakesQuantLockFirst`, `.landedCostTakesCostingLock`, `StockConcurrencyIT` |
| P2-2 | Transfer reversals do not pin ledger lots; count lines by lot number; serial insert without conflict check; production reversal does not issue the produced lot | R5 | fixed (R5b; transfers received before R5b have no recorded destination layers and reverse oldest-first) | `InventoryEngineHardeningIT.transferReversalReturnsTransferredLot`, `.countAdjustsTheCountedLot`, `.concurrentSameSerialReceipt`, `.productionReversalIssuesProducedLot` |
| P2-3 | Transfer ledger values ignore serial / standard cost; AVCO residue not swept at zero; landed cost without `CostingLocks.shareProduct` | R5 | fixed (R5b; residue swept only below 1.00 and only when the move posts GL) | `InventoryEngineHardeningIT.serialTransferValue`, `.standardTransferValue`, `.avcoResidueSwept`, `.landedCostTakesCostingLock` |
| P2-4 | Reports: `/ledger` and `/movements` day parsing, N+1 includes, bad `tolerance` not a 400, stock-health fallback, `closingValue` source, cost fields without `inventory_valuation:read` | R5 | fixed (R5a; R5b: also the ledger, ledger detail, item-movement and movement-summary cost fields) | `InventoryReportsHardeningIT`, `InventoryEngineHardeningIT.costFieldsNeedValuationPermission` |
| P2-5 | Validation: `@Positive` / `@Size` missing on stock-out lines, lists, notes and replenishment DTOs | R5 | fixed (R5a) | `InventoryReportsHardeningIT.requestLimits` |
| P2-6 | Warehouse: moves ignore `reservedInBin`, disabling settings blocks open work, reconcile pass 2 leaves stale tasks, ABC GET writes | R5 | fixed (R5a) | `WarehouseHardeningIT` |
| P2-7 | Integration: repair parts without variant, engine cost or reversal route; rental transfers without variant and serial | R5 | fixed (R5a: repair parts; R5b: rental checkout / return / swap / cleaning transfers carry the unit's variant and serial) | `RepairIT.parts`, `RepairIT.partReturn`, `InventoryEngineHardeningIT.rentalTransferCarriesVariantAndSerial` |
| P2-8 | Stock-document lists unpaged; Idempotency-Key not required on approve / dispatch / ship | R5 | fixed (R5a: lists capped + date window, keys required on approve / dispatch / ship; stock-out approve keeps the key optional — no client calls it) | `InventoryReportsHardeningIT.boundedDocumentLists`, `WarehouseHardeningIT.keysRequired` |
| P2-9 | Missing index `StockTransferItem(organizationId, productId)` | R5 | fixed (R5a, V615) | migration `V615__inventory_hardening_r5a.sql` |
| P2-10 | Negative-stock policy undocumented and without concurrency / offline ITs | R5 | fixed (R5a: policy documented as it behaves; ITs) | `NegativeStockPolicyIT` |
| P2-11 | Audit trail: no `recordedOffline` in ledger detail; `reversesLedgerId`, performer and approver not shown | R5 | fixed (R5b, additive JSON: `reverses`, `performedByName`, `responsibleByName`, `approvedByName`, `recordedOffline`) | `InventoryEngineHardeningIT.ledgerDetailAuditTrail` |

## Release gates

Evidence from the R5b verification run on 2026-11-23: `fastTest` on a private owner-split Postgres 16 (external DB,
reset between groups of about seven classes), 423 tests in 46 classes, 0 failed, 0 skipped. The full build did not
run. The known external-DB-only failures (`InventoryHardeningIT.ledgerGuardBypass`, `InventoryAuditCrashBoundaryIT`)
were not in the set. No browser test of the web screens; no data check was run on live data.

| Gate | Pass when (plan) | Evidence (class: tests run, all passed) | Status |
|---|---|---|---|
| **G1 Quantity integrity** (R0, R1, R4) | All R0 quantity, lot, serial, transit and reservation checks are green after every new IT scenario. No IT shows a duplicate movement, a lost lot or serial, or a reversal at the wrong lot or cost. The P0/P1 regression ITs pass. | InventoryIntegrityIT 12, InventoryReturnsIdentityIT 8, SyncInventoryRecoveryIT 2, OrderCancelStockIT 10, PosStockPostingIT 10, NegativeStockPolicyIT 7, WarehouseIT 11, RentalIT 10, CashSaleIT 19, InventoryEngineHardeningIT 13 (integrity report all-green after each scenario in its org; transfer and production reversals at their own lots, count by lot id, serial race) | met with exceptions: transfers received before R5b reverse their destination lots oldest-first (no recorded layers); the integrity assertion runs after every scenario of the R5b IT and the R5a negative-stock IT, not after every scenario of every older IT |
| **G2 Financial integrity** (R0, R3) | The valuation-vs-sub-ledger check is 0 per product. The GL tie-out is within 0.01 per org and account, and per product after the cutover. STANDARD receipt, return and revaluation ITs tie out. | StandardCostingIntegrityIT 5, InventoryIntegrityIT 12, InventoryCostPolicyIT 2, ValuationGapsIT 10, InventoryValuationReportIT 3, InventoryAuditConservationIT 13, LandedCostGapsIT 18, LandedCostLineageIT 12, LandedCostVoucherIT 8, PeriodCloseChecklistIT 6, InventoryEngineHardeningIT 13 (`valuation_vs_gl` and `valuation_vs_gl_by_product` green after serial / STANDARD transfers and the AVCO residue sweep) | met with exceptions: per-product GL only from the V613 cutover (history by source document); an AVCO residue of 1.00 or more is not swept and shows in the report |
| **G3 Security and concurrency** (R1 offline, R2, R5 negative stock) | Branch-A against branch-B ITs return 403/404 for reversal, reservation and transfer source actions. Concurrent and offline exactly-once ITs pass (replayed opId, concurrent `applyAwaiting`, simulated crash through `afterRunHook`). The negative-stock policy ITs pass. | InventoryBranchScopeIT 7, InventoryAuditTenancyLocationIT 6, SyncInventoryIT 9, SyncInventoryReplayIT 7, SyncInventoryRecoveryIT 2, SyncIT 10, NegativeStockPolicyIT 7, StockConcurrencyIT 6, InventoryAuditConcurrencyIT 8, InventoryEngineGapsIT 13, StockLockOrderTest 1, InventoryEngineHardeningIT 13 (lock order: count submit, reservations, landed cost; parallel reservations / issues; serial race) | met with exceptions: `InventoryAuditCrashBoundaryIT` (crash boundary) fails only on the external test DB and was not run here; lock-order proofs cover count submit, reservations and landed cost, the other paths by code review |
| **G4 Operational reliability** (R5) | Report, warehouse and replenishment ITs pass. Integrity monitor alerts work end to end. Recovery runbook in `docs/OPERATIONS.md`. | InventoryReportsHardeningIT 8, InventoryReadsIT 4, InventoryRegisterIT 3, InventoryIT 21, WarehouseHardeningIT 5, WarehouseReplenishmentGapsIT 13, ReplenishmentIT 10, StockReservationOverviewIT 3, InventoryIntegrityIT 12 (monitor raises / resolves `integrity_*`), PeriodCloseChecklistIT 6; runbook `OPERATIONS.md` §8a | met with exceptions: the screens (movement report, ledger, movement detail dashes) passed `tsc` and `eslint` but were not browser-tested; the monitor is proven by ITs, not on a deployed stack |

Also run: ManufacturingIT 10, MultiStageProductionIT 11, RepairIT 11, SalesAtpIT 4, SalesFulfilmentIT 2,
SalesOrderPolicyIT 5, PosSaleIT 14, RentalLifecycleIT 8, InventoryAuditRemediationIT 13, InventoryUnitTest 24,
KernelUnitTest 8; `ModularityTests` 2 and `ArchitectureTests` 5 (`test` task, after `spotlessApply`).

## R5b notes

- Engine lock order and the approval gates: [spec deviations 44–45](../spec/inventory.md#deviations). Count submit is
  refused with 409 only when stock moved between the unlocked and the locked measurement in a way the gate could judge
  differently; otherwise the locked variance posts.
- Lots, serials and values: deviations 46–50. Rental: 51. Ledger detail and cost gating: 52–53.
- No migration (V616 unused).

## Post-merge fix (2026-11-23): negative-stock cost corrections in the ledger

Found while rehearsing the deploy on a copy of live: the integrity report flagged DEMO `SRV-001` in
`valuation_vs_subledger`. A receipt into a negative AVCO quant at a new cost posts the negative-stock cost correction
journal, but wrote no ledger row, so Σ ledger value drifted from the valuation and the GL (which agreed). Every such
receipt would have raised `integrity_valuation_vs_subledger` and blocked period close. Fixed in the engine
([spec deviation 54](../spec/inventory.md#deviations)); `NegativeStockPolicyIT` (e) proves it (fails without the fix).
The one historical case on live (`SRV-001`, the only cost-correction journal in any organisation) was repaired with
the owner's approval by `2026-11-23-srv001-cost-correction-backfill.sql` (rehearsed on a restored copy); the product,
a service that should not count stock, was then zeroed by a stock-in at cost 0 and set to not track inventory.

## R0 notes and deviations from the plan

- **Serials vs quantity.** The default serial policy (`capture_optional`) allows quantity-only movements, so a quant may
  hold units without a serial. The check is therefore: in-stock serials ≤ max(0, quantity) always, and = quantity
  where `inventory.serialPolicy` resolves to `required` (per product, category and location).
- **Serial state.** Receipts and transfers write one ledger row for several serials without `serialId`; only issues
  carry it. A serial's movements are its own rows plus the receipt row matched through `receiptRef`. Serials with no
  such movement are not judged.
- **Valuation vs GL** delegates to the existing tie-out: organisation level, every inventory control account together
  (not per account or branch). Product-level GL tie-out waits for the product dimension on stock journals (R3).
  The monitor does not raise it while `inventory.glTieOut.enabled` is off.
- **Transit** also subtracts `qtyRecalled` (the engine's own outstanding formula). Under a location filter only the
  per-transfer part runs (transfers touching the location); Σ open = transit-location quantity is organisation level.
- **Reservations** compare with on-hand + incoming supply (the replenishment providers: purchases, production,
  transfers into the location). Sources judged: `invoice`, `sales_order_line`, `production_order`,
  `processing_job_input`; other source types (manual, free text) cannot be resolved and are not judged.
- **Period close.** A value finding (`integrity_valuation_vs_subledger`, `integrity_valuation_vs_gl`) raised since the
  period started holds the close and is a blocking checklist item (`inventory_integrity_value`). Quantity findings
  are excluded from the generic open-exception gate: they never hold a close. Because the existing tie-out variance
  was advisory, the GL tie-out now also holds the close through the integrity monitor when it fails (owner plan:
  "blocks period close only for the value checks").
- No migration was needed.
