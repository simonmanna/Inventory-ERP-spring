# IPR residuals of Phases 1a and 2 — stream H evidence

- Branch `fix/ipr-12-residuals` (worktree `/home/ierp-ipr-p0-docs-specs`, off `fix/ipr-p15` 43b50363). Not pushed,
  not merged, not deployed.
- Test DB: external Postgres 16 `erp-itdb-ipr3` (127.0.0.1:5703, owner-split roles), reset before the evidence run.
- Migrations: none. V703–V705 stay unused (registry updated to say so).
- Deviations: `fleet.md` (from-invoice refusal), `invoicing.md` (F11), `manufacturing.md` (D-2, WIP-8, WIP-7, D-3),
  `inventory.md` (GL-11 refinement, D-1), `manufacturing-wip-reconciliation.md` (status note). All pending owner
  sign-off. The register `docs/audits/2026-11-27-inventory-readiness.md` is not edited.
- No engine file was edited (`StockService`, `StockMove`, `StockReversalService`, `CostResolver`, `LayerCostWriter`,
  `InventoryLedger`, `StockPostingService` untouched). `StockMove.inLockOrder` is called, not changed.

## 1. Closure table

Red = the regression IT on the branch base (`43b50363`, this branch's `backend/src/main` reversed; for F5 and LK-3,
whose fixes were already on the base, the guard `assertNotReservedForSalesOrders` and the order lock in
`SalesBillingService.recomputeInvoiced` were disabled for the red run only), FAILED line from the run log. Green =
the same IT on this branch (counts in §3). "Integrity / GL" = `assertIntegrityGreen` (or the stricter `green()` of
the manufacturing ITs: checks, report-only checks except `feature_risk`, WIP report, `wip_tieout`) and
`assertGlTieOut`.

| Finding | Root cause (file:line, base `43b50363`) | Fix commit | Regression IT (red → green) | Workflow IT | Integrity / GL asserted | Lock levels | Deviations |
|---|---|---|---|---|---|---|---|
| fromInvoice latent defect (P1) | `from-invoice` ignored `data.stockIssuedBy`: a posted sales-order invoice got an `invoiced` order that never issues, a draft one a `dispatch` order that overwrote the mark and issued again — `fleet/application/FleetDeliveryOrderService.java:450` | `ad0ff711` | `DeliveryResidualsIT.salesOrderInvoiceNotDeliveredFromInvoice` — red: `FAILED ...salesOrderInvoiceNotDeliveredFromInvoice(): [a posted sales-order invoice: {"id":"…","code":"DO-000001",…,"sourceType":"invoice",…}]` (201, expected 409) → green | same (posted and draft refused, the order's own delivery issues once: 50 → 46 → 41) | yes (`assertClean` = integrity + GL) | L1 invoice FOR UPDATE, refusal before writes | fleet.md |
| F5 refusals (dedicated IT) | fixed on the base (stream B); this IT pins invoice auto-issue, issue-undelivered, fleet dispatch and cash-sale confirm | – (IT only, in `ad0ff711`) | `DeliveryResidualsIT.reservationRefusalOnInvoiceAndFleetPaths` — red (guard off): `FAILED ...reservationRefusalOnInvoiceAndFleetPaths(): [{"id":"…","documentNumber":"INV-2026-000001",…}]` (posted, expected 400); `…reservationRefusalOnCashSaleConfirm` — red: `FAILED ...reservationRefusalOnCashSaleConfirm(): [{"id":"…","number":"CSS-CSE5-2026-00001",…}]` (confirmed, expected 400) → green | same (the free part still issues; cancelling the order frees the rest) | yes / yes | L1 document → L4 cell → L6 reservation read | none new |
| LK-3 race IT | fixed on the base (`ff463c3b`); forced interleaving added | – (IT only) | `DeliveryResidualsIT.salesOrderBeforeLines` — red (order lock removed): `FAILED ...salesOrderBeforeLines(): [deadlock (40P01): … SELECT status FROM "SalesOrder" WHERE id = ? AND "organizationId" = ? FOR UPDATE]; ERROR: deadlock detected` → green | close-short via the API + the subscriber's `recomputeInvoiced`, both waiting on a held order row | yes / yes | L1 `SalesOrder` → L1 `SalesOrderLine` | none |
| F11 | unit cost through a JS number: `new BigDecimal(JsNumbers.toJsString(Money.div(subtotal, qty).doubleValue()))` — `invoicing/application/InvoicingWorkflowsInitializer.java:609` (caller, not the engine) | `5e04f35e` | `IprResidualsIT.billReceiptCostIsDecimal` — red: `FAILED ...billReceiptCostIsDecimal(): [receipt value = the bill line's subtotal] expected: 89999999999.999999 but was: 90000000000.000000` → green | bill with auto-receive, post | yes / yes | none new | invoicing.md |
| D-2 | stage postings never touched the order's reservation (only completion/cancel released it) — `manufacturing/application/StageExecutionService.java:1220-1228` | `864310a9` | `ManufacturingResidualsIT.stageConsumptionReducesReservation` — red: `FAILED ...stageConsumptionReducesReservation(): [half consumed] expected: 50 but was: 100.000000`; `ManufacturingLifecycleIT.multiStage` (tolerance removed) — red: `FAILED ...multiStage(): [integrity checks stage 1 partial] Expecting empty but was: ["reservations [{"kind":"over_reserved",…` → green | same (consume 50/50, reverse a posting → 50 again, complete → 0) | yes / yes + `wip_tieout` | after the posting: per cell in `StockMove.inLockOrder` order L4 (`lockCell`) → L6 reservation | manufacturing.md |
| D-3 | `getProduct` selected `id, "uomId"` only — `manufacturing/application/ProductionService.java:1603-1605` | `cccb411e` | `ManufacturingResidualsIT.getProductReturnsColumns` — red: `FAILED ...getProductReturnsColumns(): expected: "STANDARD" but was: "null"` → green | every manufacturing IT in §3 (callers unchanged; none read another column) | – | none | manufacturing.md (no contract change) |
| GL-11 refinement | rule accounts read with `accountSource IN ('account_mapping','literal')` — `manufacturing/application/WipTieOutService.java:102` | `cccb411e` | `ManufacturingResidualsIT.wipTieOutFollowsCategoryFieldRules` — red: `FAILED ...wipTieOutFollowsCategoryFieldRules(): [WIP reconciliation report in progress on the category's WIP account: {"glBalance":"0","openOrdersWip":"400","drift":"-400","balanced":false,…` → green | same (category_field rules, in progress and completed) | yes / yes + `wip_tieout` | read-only | inventory.md |
| WIP-7 | order attribution by `sourceId` ran before the process posting-key match — `manufacturing/application/WipTieOutService.java:163` | `cccb411e` | `ManufacturingResidualsIT.processVarianceAttributedByPostingKey` — red: `FAILED ...processVarianceAttributedByPostingKey(): [WIP reconciliation report process period closed with a standard variance: {…"failingObjects":[{"kind":"order","orderCode":"MO-00001","status":"confirmed","glWip":"200…` → green | same (process code = an order code, standard output, period close) | yes / yes + `wip_tieout` | read-only | manufacturing.md |
| WIP-8 | legacy reversal valued at `outputUnitCost × qty` — `manufacturing/application/ProductionService.java:1109` | `cccb411e` | `ManufacturingResidualsIT.legacyReversalPutsBackThePool` — red: `FAILED ...legacyReversalPutsBackThePool(): [WIP tie-out after the legacy reversal: {"key":"wip_tieout","ok":false,…,"tolerance":"0.01","failures":1,…,"rows":[{"kind":"order",…` (the 0.10 left in WIP) → green | same (pool 1,000,000 / 300,000, output `totalCost` cleared, reverse) | integrity (engine residue on the beads only, see §5) / GL = stock ledger + `wip_tieout` + WIP report | unchanged (L1 order → L2 → L3–L6 → L7) | manufacturing.md |
| D-1 | the base seed wrote the opening ledger row without a journal (the POS seed, order 400, journalled it) — `inventory/application/InventorySeedContributor.java:77` | `ecd3415f` | `IprResidualsIT.baseSeedJournalsOpeningStock` — red: `FAILED ...baseSeedJournalsOpeningStock(): [the opening stock journal] expected: 1L but was: 0L` → green | same (base contributors only; a second run writes nothing) | yes / yes | seed transaction only | inventory.md; GQDEMO = owner action §4 |
| R2: partial output vs complete | concurrency proof | IT in `cccb411e` | `ManufacturingResidualsIT.partialOutputVersusComplete` (green on base and branch; `race`, 40P01 fails) | – | `green()` | both L1 order first | none |
| R2: QC hold vs issue | concurrency proof | IT in `cccb411e` | `ManufacturingResidualsIT.qualityHoldVersusIssue` (green on base and branch; exactly one of hold / issue wins) | – | yes / yes | L4/L5 cell | none |


## 2. Lock levels (R2)

| Path | Order |
|---|---|
| `fromInvoice` refusal | L1 invoice `Document` FOR UPDATE, then reads; refuses before any write |
| Invoice post / issue-undelivered F5 refusal | L1 invoice → L4 quant advisory (`assertNotReservedForSalesOrders` takes `lockCellAdvisory`) → L6 reservation read; refusal rolls back before L7 |
| Fleet dispatch F5 refusal | L1 trip → L1 delivery orders → L4 per cell → L6 reservation read |
| Cash-sale confirm F5 refusal | L1 cash sale → L4 → L6 |
| LK-3 (close-short vs invoice recompute) | both take L1 `SalesOrder` FOR UPDATE, then L1 `SalesOrderLine` rows; proven with a held order row and two waiters (`DeliveryResidualsIT.salesOrderBeforeLines`) |
| D-2 re-plan | inside the stage posting (L1 order → L3–L6 issue/receive → L7), then per cell in `StockMove.inLockOrder` order: L4 quant advisory (`lockCell`) → L6 reservation upsert / consume; no new lock above L4 after a journal |
| WIP-8 reversal | unchanged (L1 order → L2 original ledger rows → L3–L6 → L7); the legacy value is a read of the order's ledger rows |
| Partial output vs complete (race) | both L1 production order FOR UPDATE first; loser sees the winner's state |
| QC hold vs issue (race) | hold: L1 lot → L4/L5 cells of the quarantine transfer; issue: L4/L5 of the same cell; serialised on L4 |
| F11, D-1, D-3, GL-11, WIP-7 | no new locks (value computation, seed, select, read-only check) |

No path takes a lower level after a higher one. Both race ITs use `InventoryAuditSupport.race` (a 40P01 or a timeout
fails the test).

## 3. Test results

Run 1 — freshly reset DB (`docker rm -f -v` + new container), `spotlessApply fastTest` over 26 classes, at commit
`cccb411e` (238 tests, 237 ok, 1 failed):

| Class | Tests | OK | Failed |
|---|---|---|---|
| fleet.DeliveryResidualsIT (new) | 4 | 4 | 0 |
| inventory.audit.IprResidualsIT (new) | 2 | 2 | 0 |
| manufacturing.ManufacturingResidualsIT (new) | 7 | 6 | 1 (WIP-8, superseded by run 2) |
| manufacturing.ManufacturingLifecycleIT (D-2 tolerance removed) | 11 | 11 | 0 |
| manufacturing.MultiStageProductionIT | 12 | 12 | 0 |
| manufacturing.StageIntegrityIT | 7 | 7 | 0 |
| manufacturing.ShopFloorExecutionIT | 4 | 4 | 0 |
| manufacturing.PrecastManufacturingIT | 11 | 11 | 0 |
| manufacturing.Phase2ExitIT | 1 | 1 | 0 |
| manufacturing.StandardProcessCostingIT | 4 | 4 | 0 |
| manufacturing.ManufacturingIT | 10 | 10 | 0 |
| fleet.DeliveryLifecycleIT | 8 | 8 | 0 |
| fleet.DeliveryConcurrencyIT | 3 | 3 | 0 |
| fleet.FleetDispatchIT | 12 | 12 | 0 |
| invoicing.InvoicingIT | 8 | 8 | 0 |
| procurement.ProcurementIT | 22 | 22 | 0 |
| procurement.PurchaseReadinessBillsIT | 14 | 14 | 0 |
| sales.SalesBillingIT | 7 | 7 | 0 |
| sales.CashSaleIT | 25 | 25 | 0 |
| inventory.InventoryIntegrityP0IT | 16 | 16 | 0 |
| inventory.StockReservationOverviewIT | 3 | 3 | 0 |
| inventory.audit.InventoryAuditConservationIT | 13 | 13 | 0 |
| inventory.audit.IprPhase2AccountingIT | 6 | 6 | 0 |
| kernel.KernelIT | 18 | 18 | 0 |
| accounting.GlControlPermissionsIT | 5 | 5 | 0 |
| accounting.verification.Acc0IdempotencyIT | 5 | 5 | 0 |

The WIP-8 failure of run 1 was the test, not the fix: after the reversal the engine leaves 0.10 of ledger value on the
beads at quantity 0 (§5, E-1), which the strict `green()` counted. The test now asserts the finding itself (WIP tie-out
and WIP report after the reversal, GL = stock ledger, and that the only failing check is that engine residue); its red
run on the base fails on `wip_tieout` (closure table).

Run 2 — freshly reset DB again, `spotlessApply fastTest --tests '*ManufacturingResidualsIT' test --tests
'*ArchitectureTests' --tests '*ModularityTests'` with the final WIP-8 test:

| Class | Tests | OK | Failed |
|---|---|---|---|
| manufacturing.ManufacturingResidualsIT | 7 | 7 | 0 |
| ArchitectureTests | 5 | 5 | 0 |
| ModularityTests | 2 | 2 | 0 |

Not run: the full build, `check`, the demo-seed suites. Known external-DB-only failures (`InventoryHardeningIT
.ledgerGuardBypass`, `InventoryAuditCrashBoundaryIT`, `ManufacturingReadyIT.cancelSurvivesReleaseFailure`) are outside
this set.


## 4. D-1 owner action (GQDEMO, not done by code)

The organisations seeded before this branch keep their opening stock row without its journal (R1: no auto-repair).
For GQDEMO the gap is the widget's 100 @ 6 = 600 (stream C §4 traced it to `InventorySeedContributor`). The owner
repairs it with a compensating journal through the normal journal API, after confirming the figure on the live
`/reports/inventory/gl-tieout` (`difference` = 600 on the stock valuation account):

- `POST /accounting/journal-entries` with `date` = the go-live / cut-over date, memo "Opening stock of the demo
  seed (IPR D-1)", lines Dr the stock valuation account (`stock_valuation` mapping, 1400 in the demo chart) 600, Cr
  retained earnings (`retained_earnings` mapping) 600; then `POST /accounting/journal-entries/:id/post`.
- Re-run `/reports/inventory/gl-tieout`: within tolerance. No ledger row is touched.

## 5. New defects found (not fixed here)

- **E-1 (engine request, `StockService` / `CostResolver`, not edited here): AVCO residue at quantity 0.** A receipt
  whose unit cost does not terminate at 6 dp (production output: pool 1,000,000 over 300,000 = 3.333333…) keeps the
  ledger value (1,000,000) while the average is stored at 6 dp; valuation reads qty × average (999,999.90:
  `valuation_vs_subledger` and the GL tie-out fail by 0.10 while the stock is on hand), and issuing the whole quantity
  takes 999,999.90 and leaves 0.10 of ledger value at quantity 0. Request: when an issue empties a quant, take its
  remaining ledger value (and/or carry the average at more places). Seen in
  `ManufacturingResidualsIT.legacyReversalPutsBackThePool`.
- **N-1 (P2, manufacturing): two material lines of one product reserve only the last line.** `ProductionService.confirm`
  calls `reserveSoft` once per material line (`manufacturing/application/ProductionService.java:396-397`); the
  reservation upserts one row per (source, cell), so a BOM with the same component twice reserves the second line's
  quantity only, and `reservations` shows `under_reserved` after confirm. D-2's re-plan sums all lines and corrects it
  at the first stage posting of a multi-stage order; single-stage orders are not re-planned. Not fixed (confirm path,
  outside D-2).
- **Process note (no data effect):** the git stash list is shared by every worktree of the repository. During the
  red run, `git stash pop` in this worktree popped stream E2's stash `On fix/ipr-1cd: e2-main-green` (25b8fe66), which
  had been pushed on top of this stream's stash. It was put back with `git stash store` under its original message and
  SHA within about a minute, the files it had applied here were reverted, and this stream's stash was applied by SHA
  and dropped by verified ref. Later red runs used `git diff | git apply -R` instead of the stash. Streams should not
  use `git stash` for red runs.

