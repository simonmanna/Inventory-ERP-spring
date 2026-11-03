# Landed cost — GL trace (LC-0 deliverable 1)

- Roadmap: [`landed-cost-plan.md`](../roadmap/landed-cost-plan.md)
- Map: [`landed-cost-implementation-map.md`](landed-cost-implementation-map.md)
- Tests: `LandedCostAuditIT`
- ADR: [ADR-S053](../adr/ADR-S053-landed-cost-erp-grade.md)

**Account roles:**

| Role | Account |
|---|---|
| stock | mapping `stock_valuation`, or the STOCK_IN rule account |
| adjunct | 1396 price difference (`CostLineageService.priceDifferenceAccount`) |
| WIP | mapping `wip` |
| COGS | mapping `cogs` |
| input tax | mapping `tax_receivable` |
| expense | mapping `default_expense`, or the component / charge account |
| clearing | 2155 Landed Cost Clearing |
| accrual | 2156 Landed Cost Accrual |
| AP | the supplier's payable, or `accounts_payable` |

Columns in every table below:
- **Code**: where the entry is posted.
- **Stock-ledger row**: the `InventoryLedger` row the event writes.
- **Lineage target**: `CostValueAdjustmentLine.target`.

✅ = the current behaviour is correct and `LandedCostAuditIT` proves it.
⚠ = a defect, fixed in the phase named.

## 1. Goods receipt

| Dr | Cr | Code | Stock-ledger row | Lineage |
|---|---|---|---|---|
| stock (qty × unit cost) | GRNI | `GoodsReceiptsService` → `StockService.receive` → `StockPostingService.postReceipt` | `receipt`, ref `goods_receipt` | becomes the receipt layer |

## 2. Voucher post, by charge treatment

`LandedCostVoucherService.post` → `compute` / `allocate` → `LandedCostLayers.apply` → `StockPostingService.postLandedVoucher`.

In the table, `b` = charge base (amount × rate) and `t` = tax base (tax × rate). The credit side is the charge's credit
account, or clearing 2155 when it has none.

| Treatment | Dr | Cr | Stock-ledger row | Lineage | Proven |
|---|---|---|---|---|---|
| capitalise, no tax | stock (on-hand share) + COGS (consumed share); STANDARD: adjunct + WIP + COGS | clearing `b` | value-only row (`landed_cost_voucher`) | AVCO/lot: `on_hand` / consumed (⚠ LC-1: not lineage-based); STANDARD: lineage | ✅ 1a–1c |
| capitalise, recoverable tax | as above, plus input tax `t` (one line) | clearing `b + t` | as above | as above | ✅ VAT |
| capitalise, **non-recoverable** tax (LC-0) | stock / COGS on `b + t` | clearing `b + t` | value row on `b + t` | as above | ✅ VAT |
| expense, recoverable tax | expense `b` (cost centre) + input tax `t` | clearing `b + t` | — | — | ✅ VAT |
| expense, non-recoverable (LC-0) | expense `b + t` | clearing `b + t` | — | — | ✅ VAT |
| `recoverable_tax` | input tax `b` | clearing `b` | — | — | ✅ (tax on it rejected, LC-0) |
| unreviewed tax (`taxRecoverable = NULL`) | **refused** at post | — | — | — | ✅ guard |

## 3. Supplier bills

| Event | Dr | Cr | Code | Proven |
|---|---|---|---|---|
| `generate` (one bill per supplier + invoice) | clearing `b + t` | AP (supplier), bill in the charge currency at the voucher rate | `raiseSupplierBills` → `VendorBillService.createPostedForLandedCost` | ✅ 4, 6: 2155 net 0 |
| `link` (existing bill booked to clearing) | the bill already did Dr clearing / Cr AP | — | — | `LandedCostVoucherIT` |
| `none` | the clearing stays open until a bill or journal clears it; the clearing report shows it | — | `LandedCostReportService.clearing` | — |

## 4. Payment

| Event | Dr | Cr | Proven |
|---|---|---|---|
| Pay a generated bill | AP | bank | ✅ 4 |
| Pay a foreign-currency bill at another rate | AP (carrying rate) | bank (payment rate) + realised FX on `fx_gain` / `fx_loss` | ✅ G1 fixed in PC-0 (ADR-S054), case 6b |

## 5. Sale or issue

| Dr | Cr | Code | Lineage |
|---|---|---|---|
| COGS (at the current unit cost, which includes earlier landed cost) | stock | `StockService.issue` → `postIssue` | a later charge sees this row as `issue` → `cogs` |

## 6. Production

| Event | Dr | Cr | Lineage | Status |
|---|---|---|---|---|
| Issue to an open order | WIP | stock | `production_consume` → `wip` | ⚠ LC-1: the AVCO landed path doesn't use lineage, so the share goes to COGS (case 2 `@Disabled`) |
| Output | stock (FG) | WIP | `production_output` | — |
| Late charge after completion | WIP of the order (`targetRef` = order), cascaded to the FG layer by the CM-10 run | — | `LineageFollower.target` (completed order stays `wip`; cancelled → `cogs`) | ✅ LC-1 (`completedOrderCascades`) |
| CM-10 cascade run | FG targets | WIP | `CostAdjustmentService` | existing |

## 7. Transfer

| Dr | Cr | Code | Status |
|---|---|---|---|
| stock (destination) | stock (source); the out and in rows share one `ledgerCode` and an `InventoryTransferLineage` row | `StockService.transfer` → `postTransfer` | ✅ LC-1: a later landed charge follows the goods (`LineageFollower`) — on hand raised in the pool where they are, consumption at the destination to its target, unpaired lineage parked in 1396 with a `LineageException` (case 3, `LandedCostLineageIT`) |

## 8. Purchase return

| Dr | Cr | Code | Status |
|---|---|---|---|
| AP / debit note | stock | `DebitNotesService` (`return_to_supplier`, `skipGl` stock row) | ✅ LC-1: a later landed charge sends the returned share to target `return` → `costing.landedCost.returnPolicy`: `expense` / `defect_claim` Dr 5976 Landed Cost on Returned Goods; `recover_from_supplier` Dr 1396 (parked, awaiting the supplier credit) |

## 9. Release run

| Dr | Cr | Code |
|---|---|---|
| COGS (consumed share of an open adjunct) | 1396 | `CostLineageService.release` |
| — (transferred share moves to the destination pool's adjunct; same 1396, no entry) | — | `CostLineageService.release` → `moveAdjunct` (LC-1) |

## 10. Lineage exceptions and credit vouchers (LC-1)

| Event | Dr | Cr | Code |
|---|---|---|---|
| Assign the receiving in row | each target of the distribution from that row (stock / WIP / COGS / 5976) | 1396 | `LineageExceptionService.assign` |
| Write off | the chosen account | 1396 | `LineageExceptionService.writeOff` |
| Credit voucher | clearing 2155 | each lineage target, mirrored (negative charges) | `LandedCostVoucherService.createCredit` → `post` |
| Voucher reversal (lineage mode) | exact reversal of the entry, then Dr stock / adjunct · Cr COGS / WIP / 5976 for what was consumed since | | `LandedCostLayers.reverseLineage` |

## 10. Accrual

| Event | Dr | Cr | Proven |
|---|---|---|---|
| Accrue (rate × layer value) | adjunct (on hand) / COGS (sold) | accrual 2156 | ✅ 5 |
| Clear against a posted voucher | accrual 2156 | the same targets, exactly where they went (the open adjunct, released or consumed shares) | ✅ 5: 2156 and 1396 back to 0 for actual both above and below the estimate |
| Reverse (no voucher) | 2156 | the same targets | `ImportLandedScenarioIT` |

## 11. Reversals

| Event | Entries | Proven |
|---|---|---|
| Voucher reversal | (1) journal reversed, (2) `LandedCostLayers.remove` takes the per-unit increment off what is still on hand, (3) `postLandedReclass`: Dr stock / Cr COGS for the share sold since, so the landed cost leaves stock and COGS completely | ✅ 1a, 1b (AVCO, lot, STANDARD), 1c |
| Refused while a generated bill has payments (G4 fixed) | — | ✅ 4 |
| Unreviewed tax on a posted voucher | reversal allowed (decision D5) | ✅ VAT guard |
| Legacy per-receipt reversal | same engine | `ImportLandedScenarioIT` |

## 12. Controls proven

- Concurrent posts → one 200, one 409, stock moved once (case 7).
- Books locked → refused, nothing moves; after unlock it posts (case 8). Policy: a late charge for a closed period must
  be dated in the open period. The voucher is never back-dated into the lock.
- Concurrent duplicate supplier invoice → one voucher, one 409 (case 9).
- Two vouchers on one receipt layer on top of each other (case 10).
- Tenant isolation (case 11).

## Findings

| # | Finding | Phase |
|---|---|---|
| G1 | No realised FX on payments anywhere | **Fixed in PC-0** (ADR-S054) |
| G2 | A foreign-currency posting with no configured rate inside an outer transaction fails the whole voucher (500, silent rollback): `PostingService.rate` catches the `ApiException` of `@Transactional CurrencyService.getRate`, but the participating transaction is already rollback-only. Hits the generated USD bill of a voucher (case 6 `@Disabled`). Proposed fix (shared accounting code, owner approval): `getRate` with `noRollbackFor = ApiException.class`, or a non-throwing `findRate`. Related G3: when a rate **is** configured, `rate()` takes the table rate over the document's own rate, so a bill at another rate can leave 2155 open. Verify in case 6 after G2 | **Fixed in PC-0** (ADR-S054): `findRate` never throws; explicit document rate → table → refuse; rate snapshot per entry |
| G4 | **Fixed in LC-0:** the "bill has payments" guard checked only `status = 'posted'`, but a fully paid bill moves on to another status, so a voucher whose bill was paid could be reversed. The guard now covers any bill that is not cancelled and has `amountPaid > 0` | LC-0 ✅ case 4 |
| F2, F5, F6, F7 | AVCO / lot / transfer / return / closed-order lineage | **Fixed in LC-1** (`LandedCostLineageIT`, `AvcoLandedMathTest`, audit cases 2 and 3 enabled) |
| — | `taxRecoverable` source of truth | LC-0 (V290): `Tax.recoverability`, the org default, an explicit flag + `landed_cost:override_tax` |
