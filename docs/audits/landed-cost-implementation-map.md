# Landed cost — technical implementation map (LC-0 step 0)

- Roadmap: [`docs/roadmap/landed-cost-plan.md`](../roadmap/landed-cost-plan.md) (rev 3.1, approved 2026-11-02)
- Status: **for owner review**. LC-0 step 1 (GL trace + `LandedCostAuditIT`) starts only after sign-off.
- Method: built from reading the code. Nothing has been changed yet.

## 1. Summary — what the code changes in the plan

The plan assumed a few things that the code does differently. The rows marked **⚠** change the design or the scope.

| # | Plan assumption | What the code actually does | Effect |
|---|---|---|---|
| F1 | Transfer out/in rows have no link, so a lineage table is needed | **`StockService.transfer` (`:855`) takes one `ledgerCode` per move and writes the `transfer_out` and `transfer_in` rows with that same code (`:983`, `:986`).** The pair is exact 1:1 for all history. Splits happen at the pool level: dispatch A→transit is one move, and each partial receipt transit→B or transit→C is its own move | ⚠ **No heuristic backfill needed.** `InventoryTransferLineage` can be filled deterministically from `ledgerCode` pairs. A split is the transit pool's own lineage, which the recursion already walks. See decision D1 |
| F2 | Batch transfers write lot-specific ledger rows | Transfer ledger rows have **`batchId = NULL`** even for lot-tracked products. Lots move by creating destination `InventoryBatch` rows (oldest first, `:895–925`) | ⚠ The lot path in `LandedCostLayers` (batch `unitCost` at the receipt location, rest → COGS) **undervalues transferred lots**. Lot lineage must follow through the pool's paired rows plus the destination batch by `batchNumber` |
| F3 | Negative-stock over-allocation needs a new cap | `AvcoLineageCalculator.distribute` already does `q = min(-change, pool)`, stops when `pool ≤ 0`, and resets the epoch when the layer is exhausted. Units that filled earlier negative stock go to `negative_stock` | ✅ The cap exists. LC-1 adds the golden test (100 available, issue 120 → never more than 100 distributed) and the "settlement never redistributes" assertion |
| F4 | Rounding residual goes to the last share | The calculator puts the residual on **on-hand**, or on the largest consumption when nothing is on hand | Record this rule in the ADR rather than change it. The identity Σ = c still holds exactly |
| F5 | AVCO valuation needs a lineage engine | It exists (`CostLineageService.distribute`, `byTarget`, `recordAdjustment`) and STANDARD already uses it (`LandedCostLayers.standardSplit`). **AVCO and lots do not**: `bumpAverage` uses `min(pool qty, receipt qty)` | Confirmed defect. LC-1 routes AVCO and lots through lineage |
| F6 | Returns get a `return` target | `AvcoLineageCalculator.target`: `return_to_supplier` (written by `DebitNotesService:314`) falls into `default → cogs`. `CostValueAdjustmentLine_target` has no `return` value | ⚠ Defect: landed cost on returned goods goes to COGS today. Needs a new target value (migration) and a mapping |
| F7 | WIP → FG on completion needs wiring | **CM-10 `CostAdjustmentService` already cascades** `target = 'wip'` shares onto the order's output layers after completion (a run, unique per share, reversible) | ✅ Reuse it. **Gap:** a late charge distributed *after* the order closed sees `production_consume` with a closed order and goes to **COGS directly** (`target(...)` when `openProductionOrder = false`), even if the FG is still on hand. LC-1: for a closed order, cascade straight onto the output layers through the CM-10 path |
| F8 | Tax recoverability default comes from the tax code | **No source exists.** `Tax` has `type`, `rate`, `isInclusive`, `isCompound`, `vatCategory`, `accountId` and no recoverability. Landed charges carry only `taxAmount` with no tax code | ⚠ Decision D3 needed |
| F9 | Credit vouchers carry negative charges | `LandedCostCharge_amount_chk CHECK (amount > 0)` (V1). `voucherType` CHECK allows only `receipt` and `voucher` (V12) | The migration widens both: `voucherType` gets `credit`, and the amount check becomes `amount <> 0` with the sign tied to `voucherType` |
| F10 | `CostValueAdjustment.adjustmentType` covers landed accruals | The V224 CHECK list has no `landed_accrual`; V229 (CM-6) extended it | Verify the live constraint list in step 1. New values (`landed_credit`, transfer follow-ups) need a constraint migration |
| F11 | Approval limits exist | `ApprovalLimitService.assertWithin(entityType, amount, branch, dept, user, …)` (CTRL-1) | ✅ Reuse with `entityType = 'landed_cost_voucher'` |

## 2. Schema involved

| Table | Origin | Columns that matter |
|---|---|---|
| `LandedCost` | V1, V12 | `voucherType` (receipt / voucher), `status` (draft / submitted / approved / posted / reversed / cancelled), `goodsReceiptId`, `shipmentId`, `costCenterId`, `creditAccountId`, `allocationMethod`, totals (`total`, `capitalized`, `expensed`, `tax`), `journalEntryId`, `reversalJournalEntryId`, submitted/approved/reversed at and by |
| `LandedCostReceipt` | V12 | `(landedCostId, goodsReceiptId)` unique |
| `LandedCostCharge` | V1, V12, V14 | `amount > 0` CHECK, `costComponentId`, `supplierId`, `supplierInvoiceRef`, `invoiceDate`, `currencyCode`, `exchangeRate`, `baseAmount`, `taxAmount`, `treatment`, `allocationMethod`, accounts, `costCenterId`, `appliesToLineIds[]`, `manualAllocations jsonb`, `billMode` (generate / link / none), `vendorBillId`, `dueDate`. Index on `(org, supplier, ref)` that is **not unique** |
| `LandedCostAllocation` | V1, V12 | qty, on hand, basis, allocated, capitalized, expensed, unit cost before/after, `chargeId`, `goodsReceiptId`, `basisMethod`, `sharePct`, `roundingAmount`, `reversedAmount`. **No stored weight / volume / UoM** (LC-2) |
| `Shipment`, `ShipmentReceipt`, `CostComponent` | V12 | — |
| `LandedCostAccrual`, `…Line`, `…Rule` | V229 | One open accrual per GRN. No cleared/remaining amounts, no FX columns, no effective dates (LC-4) |
| `CostValueAdjustment` | V224 | `adjustmentType`, source, product/variant/location/batch, `layerLedgerId`, `amount`, `onHandAmount`, `consumedAmount`, `valuationComponent`, `openAmount`, `anchorLedgerId`, `journalEntryId`, `reversedAt` |
| `CostValueAdjustmentLine` | V224 | `consumptionLedgerId`, `target` ∈ {on_hand, cogs, wip, scrap, transfer, negative_stock}, `targetRef`, `amount` |
| `InventoryLedger` | V1, V224 | `ledgerCode` (**pair key for transfers**), `type StockMoveType`, `qtyBefore`, `quantityChange`, `balanceAfter`, `unitCost`, `totalValue`, `referenceType/Id`, `valuationComponent`, `batchId` (NULL on transfers) |
| `StockTransfer`, `StockTransferItem`, `StockTransferReceipt` | V1 | `mode` (immediate / transit), `transitLocId`, per item: dispatched / received / damaged / short / recalled quantities. Receipt `lines jsonb` |
| `Tax` | V1 | No recoverability column (F8) |

`StockMoveType` values: receipt, issue, adjustment_in/out, transfer_in/out, opening_balance, waste, return_in,
return_to_supplier, expiry_write_off, internal_use, promo_sample, production_consume, production_output,
reversal_in/out, cost_adjustment.

## 3. Posting flows (file:line)

| Flow | Where | Stock ledger | GL |
|---|---|---|---|
| GRN | `procurement/application/GoodsReceiptsService` → `StockService.receive` | `receipt`, ref `goods_receipt` | Dr stock / Cr GRNI |
| Per-receipt landed cost | `LandedCostService.post` → `LandedCostLayers.apply` → `StockPostingService.postLandedCost` (`:272`) | value-only row + `CostValueAdjustment` (CM-1) | Dr stock or 1396 / WIP / COGS, Cr the chosen account |
| Voucher post | `LandedCostVoucherService.post` (`:349`) → `compute` / `allocate` (`:578`) → `postLandedVoucher` (`:323`) → `raiseSupplierBills` (`:958`) | same as above | Dr targets + input tax + expense / Cr clearing 2155 (or credit account) + generated bill Dr 2155 / Cr AP |
| Voucher and legacy reversal | `reverseLocked` (`:429`) → `LandedCostLayers.remove` → `posting.reverse` → `postLandedReclass` | negative value rows + reclass rows | journal reversal + Dr COGS / Cr stock for the share sold since |
| Transfer immediate | `StockDocService.approveTransfer` (`:700`) → `StockService.transfer` (`:828`) | out + in rows, **same `ledgerCode`** | `postTransfer` (STOCK_TRANSFER_OUT/IN rules) |
| Transfer transit | `dispatchTransfer` (`:778`, A→transit), `receiveTransfer` (`:812`; transit→B per receipt; damaged/short issues `:872–877`), `recallTransfer` (`:905`) | one pair per move | same |
| Production issue / output | `ProductionService` (`:829`), `StageExecutionService` (`:869`), `ProcessCostingService` (`:354`) | `production_consume` / `production_output` | Dr WIP / Cr stock; output `postProductionOutput` (`StockPostingService:461`) Dr stock / Cr WIP |
| WIP late-cost cascade | `costing/CostAdjustmentService` (CM-10) | value rows on output layers | Dr targets / Cr WIP |
| Purchase return | `DebitNotesService` (`:314`, `return_to_supplier`, `skipGl`) | issue row | debit-note journal |
| Sale | POS / invoicing → `StockService.issue` | `issue` | COGS posting |
| Adjunct release | `CostLineageService.release` (`:338`) | — | Dr COGS / Cr 1396 for the consumed open adjunct |
| Accrual | `LandedCostAccrualService` accrue / clear / reverse → `LineageCapitalisation.spread`, `debits`, `record` | value rows (`landed_accrual`) | Dr 1396 / COGS / Cr 2156; the clear does the reverse |
| GL tie-out | `GET /reports/inventory/gl-tieout` (`InventoryValuationController:32`) | — | stock subledger vs GL; 1396 included (`InventoryValuationService:168`) |

## 4. Lineage coverage vs plan targets

| Target | Covered today | Gap / LC phase |
|---|---|---|
| on_hand (AVCO) | calculator yes; landed path **no** (`bumpAverage`) | LC-1 |
| on_hand (lot / serial) | lot by batch qty at the receipt location, serial by `receiptRef` | LC-1, transfers (F2) |
| transfer | target `transfer` → 1396 adjunct (STANDARD only) | LC-1: follow the `ledgerCode` pair into the destination pool, recursively |
| wip | yes (open order) | closed order → COGS today (F7) |
| cogs, scrap | yes | — |
| negative_stock | yes, capped | golden test |
| return | **no** (→ cogs) | LC-1 (F6) |

## 5. Tests affected

- Existing tests that must stay green (figures change only where a transfer or later receipt is involved):
  - `procurement/LandedCostVoucherIT` (8 tests: laptopImport, multiSupplierShipment, supplierBillValidation,
    lateChargesAfterPartialSale, mixedShipmentByValueAndWeight, handlingByQuantity, chargeTreatments,
    shipmentFifoAndIsolation).
  - `procurement/ImportLandedScenarioIT` (2).
  - `costing/ValuationIntegrityIT` (lineage, revaluation, nrv, standardLanded, authorization).
  - `costing/CostCloseIT`.
  - `costing/AdvancedCostingIT` (CM-10 cascade).
  - `fleet/FleetCostingIT` (inbound freight voucher).
  - The `ProcurementIT` authorization count.
- New tests:
  - `LandedCostAuditIT` (LC-0).
  - `AvcoLandedMathTest`, pure JUnit (LC-1).
  - `LandedCostEndToEndIT` (after LC-3).
- Every new IT creates its own organisation and fixtures (no seed or demo data).

## 6. Proposed migrations

V290–V299 are reserved for this roadmap. Confirm none exists on `main` before creating.

| V | Phase | Content |
|---|---|---|
| V290 | LC-0 | `LandedCostCharge."taxRecoverable" boolean NULL`; backfill NULL where `taxAmount > 0`, else true |
| V291 | LC-1 | `InventoryTransferLineage` (id, org, transferId, sourceLedgerId, destinationLedgerId, quantity, createdAt, unique(src, dst)); deterministic backfill from `ledgerCode` pairs (out ↔ in); `LineageException`; `CostValueAdjustmentLine_target` + `return`; `adjustmentType` widened; `rootAdjustmentId` + `sourceReceiptLedgerId` on `CostValueAdjustmentLine` |
| V292 | LC-1 | `LandedCost.voucherType` + `credit`, `originalVoucherId`; `LandedCostCharge` amount check `<> 0` with a sign rule |
| V293 | LC-2 | `approvedHash`, `approvedDisposition jsonb`, `postedDisposition jsonb` on `LandedCost`; `supplierInvoiceRefNorm`, `live`, `duplicateOverrideId` on charges; `InvoiceDuplicateOverride`; two partial unique indexes; weight / volume / UoM columns on `LandedCostAllocation` |
| V294 | LC-4 | Rule columns (name, effective dates, rateType, currency, uom, weightBasis, expectedSettlementDays); `LandedCostAccrualClearing`; cleared / remaining / status / FX columns on `LandedCostAccrual` |
| V295+ | LC-7 | Tariff and customs tables |

## 7. Decisions needed before step 1

| # | Question | Recommendation |
|---|---|---|
| D1 | Keep a physical `InventoryTransferLineage` table, or derive pairs from `ledgerCode`? | **Keep the table** (approved design, explicit quantities, fast lookups), filled by the transfer posting and backfilled exactly from `ledgerCode`. Historic ambiguity should be ~0, so `LineageException` covers only structural defects |
| D2 | Default return policy (`recover_from_supplier` / `expense` / `defect_claim`) | `expense` (no receivable without a basis), per org |
| D3 | Source of truth for tax recoverability (F8) | Add `Tax.recoverability` (`full` \| `none`) and an optional `taxId` on the charge. The default comes from the component's tax or the charge's tax; with no tax set, the org setting `costing.landedCost.taxRecoverableDefault` decides, and it must be set explicitly (no silent true). Historic `taxAmount > 0` charges → NULL (review list) |
| D4 | Rounding residual rule (F4) | Keep the calculator rule (residual → on-hand, else largest consumption) and document it |
| D5 | NULL-tax guard on historic vouchers | It also blocks **reversal** of historic taxed vouchers until reviewed. Accept, or exempt reversal (a reversal only mirrors what was posted). Recommend: **exempt reversal**, block every new posting path |

## 8. Next (after sign-off)

LC-0 step 1:
1. `docs/audits/landed-cost-gl-trace.md`.
2. `LandedCostAuditIT` scenarios 1–11 (LC-1 and LC-2 cases `@Disabled`).
3. The `recoverable_tax` + `taxAmount` guard.
4. V290 with the D3/D5 outcome.
5. ADR-S053 skeleton.
