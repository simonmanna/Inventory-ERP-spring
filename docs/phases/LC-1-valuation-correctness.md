# LC-1 — Landed cost valuation correctness (done-report)

- Plan: [`docs/roadmap/landed-cost-plan.md`](../roadmap/landed-cost-plan.md) § LC-1 · ADR: [ADR-S053](../adr/ADR-S053-landed-cost-erp-grade.md) § LC-1
- Migration: `V292__lc1_transfer_lineage_returns_credit.sql`

## Delivered

| Plan item | Where | Test |
|---|---|---|
| Transfer lineage table, written by the transfer posting, exact backfill | V292, `StockService.transfer` | every transfer IT; `LandedCostLineageIT` |
| Distribution follows transfers (recursive, pro rata over links, open remainder in transit) | `LineageFollower` | `multiHop`, audit case 3 |
| Cycle / depth guard, unpaired → 1396 + `LineageException` | `LineageFollower` | `cycleDetected`, `unpairedThenAssigned` |
| Exception workspace (assign, write off; audited) | `LineageExceptionService`, `GET/POST /inventory/lineage-exceptions` | `unpairedThenAssigned`, `unpairedWrittenOff` (incl. 403) |
| AVCO maths + golden test, negative-stock cap | `AvcoLineageCalculator` (unchanged), `AvcoLandedMathTest` | 6 golden cases |
| AVCO / lot / STANDARD voucher path on lineage | `LandedCostLayers.applyLineage` | audit 1b–3, `lotFollowsTransfer` |
| Production WIP (open and completed orders → CM-10 cascade) | `LineageFollower.target` | audit case 2, `completedOrderCascades` |
| Returns policy (expense / recover_from_supplier / defect_claim) | `costing.landedCost.returnPolicy`, 5976 | `returnPolicyExpense`, `returnPolicyRecover` |
| STANDARD adjunct released at the destination | `CostLineageService.release` → `moveAdjunct` | `standardAdjunctFollowsTransfer` |
| Accruals follow transfers | `LineageCapitalisation.spreadFollowing` | `accrualFollowsTransfer`, audit case 5 |
| Reversal mirrors the posting (incl. transfers since posting) | `LandedCostLayers.reverseLineage` | `reverseFollowsLaterTransfer`, `multiHop`, audit 1a–3 |
| Credit vouchers | `createCredit`, `POST …/{id}/credit` | `creditVoucher` |

## Verification

- `fastTest` on `*LandedCost*`, `*ImportLanded*`, `*ValuationIntegrity*`, `*ProcurementIT`, `*FleetCosting*`,
  `*AdvancedCosting*`, `*CostCloseIT`, `*DistributionBranch*`, `*InventoryIT`, `*CostingAuditIT`: green.
- Every scenario asserts after each step: balanced entries, GL tie-out unchanged, 1396 movement = open adjunct
  movement, exact per-account classification.
- `LandedCostVoucherIT` figures did not change (none of its cases involves a transfer before posting).

## Not in LC-1 (by plan)

- Approval hash / reapproval, duplicate normalisation, allocation validation → LC-2.
- Reconciliation and trace endpoints → LC-3 (the lineage data they need is in place).
- UI for credit vouchers and the exception workspace → LC-5.
- Auto-release of an in-transit remainder when the receipt posts: the remainder is parked with an `in_transit`
  exception and released by *assign*; automatic release on receipt is left for LC-3 together with the reconciliation.
