# IPR review 1 fixes — stream R1 evidence (security, kernel, inventory reads, web)

- Branch `fix/ipr-review1` (worktree `/home/ierp-ipr-p0-docs-matrix`, off `fix/ipr-p15` 3f530272). Not pushed, not
  merged, not deployed.
- Test DB: external Postgres 16 `erp-itdb-ipr1` (127.0.0.1:5701), reset (`docker rm -f -v`) before the evidence run.
- Migration: `V722__opening_stock_import_preview.sql` (new table `OpeningStockImportPreview`, RLS; no existing row
  changed; ledger trigger untouched), recorded in `docs/adr/migration-registry.md`.
- Deviations (pending owner sign-off): `inventory.md`, `pos.md`, `sync.md`, `sales.md`, `fleet.md`, `kernel.md`,
  each under "IPR review 1". The register `docs/audits/2026-11-27-inventory-readiness.md` is not edited.
- No engine file was edited (`StockService`, `StockMove`, `StockReversalService`, `StockPostingService`,
  `CostResolver`, `LayerCostWriter`, `InventoryLedger`). E3's areas untouched: `PosInvoiceService.restockLine`,
  `OpeningStockImportService` freeze lock, `DirectStockService` receipt cost, `InventoryValuationService` (only
  called, read-only), `PlmDispositionService`. No ledger row or posted journal is updated or deleted; no data repair.

## 1. Closure table

Red = the regression IT on the branch base (this branch's `backend/src/main` reverted, only the N1 test hook
`InventoryCountService.drainObserver` kept), FAILED line from the run logs. Three tests needed a second red run after
test-setup fixes (R1-1 ×2, R1-N5) and R1-3 a third (the first R1-3 scenario passed on the base: one transfer alone
in transit has the transit average equal to its own cost, so the scenario was made sharper — two transfers in transit
at 50 and 10). Green = this branch (counts in §3). Integrity / GL = `assertIntegrityGreen` / `assertGlTieOut`
(`InventoryAuditSupport`).

| Finding | Root cause (file:line, base 3f530272) | Fix commit | Regression IT (red → green) | Workflow IT | Integrity / GL asserted | Lock levels | Deviations |
|---|---|---|---|---|---|---|---|
| 1 Online `occurredAt` flags dropped | callers used `OccurredAt.resolve` and discarded `Resolved.flagged()`: `pos/application/PosService.java:123,641,664`, `PosInvoiceService.java:487,898`, `PosCashDrawerService.java:75`, `accounting/application/CashSessionService.java:375,478,861` | `6537d7d0` | `IprReview1IT.posSaleTimeFlags` — red: `FAILED ...posSaleTimeFlags(): [one flag for the sale] expected: 1L but was: 0L`; `…cashSessionOpenTimeFlags` — red: `expected: 1L but was: 0L` (IprReview1IT.java:253) → green | same ITs (settle 30 days old: issueDate = sent, one `offline_late_sync`; 10 min ahead: receive time, one `offline_clock_skew`, `channel=online`); `PosFlowsIT`, `PosCashDrawerIT`, `OccurredAtFlagTest`, `PeriodCloseChecklistIT` | yes / yes (POS test) | none new: the flag row is an insert in the caller's transaction, after its locks; no lock taken | pos.md, kernel.md |
| 2 Transit-loss threshold split | threshold compared this receipt's loss only — `inventory/application/StockDocService.java:1218-1240` (`transitLossControl`) | `302a7dd3` | `IprReview1IT.transitLossThresholdIsCumulative` — red: `FAILED ...transitLossThresholdIsCumulative(): [{"id":"…","transferCode":"TRF-00002",…,"status":"completed",…}]` (second receipt, cumulative 110 > 100, accepted with 200; expected 400) → green | same (60 accepted, then 50 more refused quoting 110, accepted with reason + approver) | yes / yes | gate (no lock; approval engine on its own connection) → L1 `StockTransfer` FOR UPDATE → engine L3–L6 unchanged; `writtenOffInTransit` is an unlocked ledger read, re-measured under L1 | inventory.md |
| 3 Transit-loss value | loss valued with `unitCostAt` (transit running average, else `costPrice`) — `StockDocService.java:1228` | `302a7dd3` | `IprReview1IT.transitLossValuedAtTransitCost` — **not reproduced red**: both scenarios (one FIFO transfer; two FIFO transfers at 50 and 10 in transit together) pass on the base, because the engine keeps the transit `runningAverageCost` > 0, so the `costPrice` fallback is not reached. Green on the branch; the fix makes the value source the transfer's own layers (carried cost, else its lot/serial layers, else the transit valuation) | same | yes / yes | as finding 2 | inventory.md |
| 4 Movement-analysis costs | `movementAnalysis` returned values with no `CostVisibility` check — `inventory/application/InventoryReportsService.java:642-752` | `96796f86` | `IprReview1IT.movementAnalysisHidesValues` — red: `FAILED ...movementAnalysisHidesValues(): [summary.valueIn {"movements":2,"valueIn":50,"valueOut":10,…}]` → green | same (admin still sees values); `InventoryReportsHardeningIT` | yes / – (read) | read only | inventory.md |
| 5 Opening-import SoD | approval gate had no makers; the previewer could import under the pair — `inventory/application/OpeningStockImportService.java:903` | `c53d5b0c` | `IprReview1IT.openingImportPreviewerCannotImport` — red: `FAILED ...openingImportPreviewerCannotImport(): [{"id":"…","runCode":"OSI-00001","status":"completed",…}]` (maker imported; expected 403) → green | same (checker imports 200); `OpeningStockImportIT` (pair off: one person previews and imports) | yes / yes | preview insert in its own short transaction, no lock; import path unchanged (E3's freeze lock) | inventory.md; V722 |
| 6 Data-source unwrap | `unwrap(DataSource.class)` returned the Hikari pool — `kernel/tenancy/TenantBindingDataSource.java:70-78` | `b75bf2c3` | `TenantBindingDataSourceTest.unwrapNeverLeaksThePoolAsADataSource` — red: `FAILED ...unwrapNeverLeaksThePoolAsADataSource()` (pool returned) → green | `TenantRlsBackstopIT`; the whole run boots Flyway through the wrapper | – (unit) | none | kernel.md |
| 7 SoD alias approval | `approvedByAlias` accepted any approved request, consumed or old — `kernel/approvals/SegregationOfDutiesService.java:189` | `7df5e1ca` | `IprReview1IT.consumedAliasApprovalDoesNotClearThePair` — red: `FAILED ...consumedAliasApprovalDoesNotClearThePair(): [{"id":"…","transferCode":"TRF-00001",…,"status":"partially_received",…}]` (receiver's own approval accepted; expected 403) → green | same; `ApprovalControlsIT`, `IprStockControlsIT` | yes / yes | read only (inside the existing L1 transfer lock) | kernel.md |
| 8 Release of document reservations | `releaseForUser` had no `assertManual` — `inventory/application/StockReservationService.java:241` | `329db145` | `IprReview1IT.manualRouteReleasesManualOnly` — red: `FAILED ...manualRouteReleasesManualOnly(): [{"released":0}]` (expected 400) → green | same (manual still releases 1); `IprStockControlsIT` C-5 updated to expect 400; `StockReservationOverviewIT` | yes / – | unchanged (reservation rows, L6) | inventory.md |
| 9 Web runs list | runs card had no error state — `web/src/pages/inventory/OpeningStockImportPage.tsx` (runs card) | `15f72db4` | `opening-stock-csv.test.ts › runsListState` — red: `TypeError: runsListState is not a function` (2 failed) → green (10/10) | `pnpm --filter @erp/web test` 268/268, `pnpm web:typecheck` clean | – | – | none (UI only) |
| N1 Count drain inside a transaction | `submit()` drained inside `@Transactional` — `inventory/application/InventoryCountService.java:861-874`; sync `stock.count` inside `opTx` — `sync/application/SyncPushService.java:1133` | `e894fee9` | `IprReview1IT.countDrainRunsOutsideATransaction` — red: `FAILED ...countDrainRunsOutsideATransaction(): [screen submit]` (observer saw a transaction) → green (screen and device: observer only ever false) | same; `CountCutoffIT`, `StockEffectiveTimeIT`, `SyncLedgerCoverageIT`, `SyncInventoryIT` | yes / – | drain jobs each commit alone with **no** lock held by the caller; then L1 session FOR UPDATE → engine as before | inventory.md, sync.md |
| N2 Returns without a fleet code skipped | `issueLedgerCodes` looked only at `FleetLoadLine.stockLedgerCode` — `sales/application/SalesReturnService.java:299`, skip at `:283` | `8634745b` | `SalesReturnIT.returnAgainstInvoiceIssue` — red: `FAILED ...returnAgainstInvoiceIssue(): [2 of the 3 issued come back]` → green | same (no-issue line: nothing restocked, one open `sales_return_unmatched`); all 11 `SalesReturnIT` | yes / yes | unchanged: return row → `returnFromSource` (engine L3–L6, capped by what the issue has out); exception insert, no lock | sales.md |
| N3 Fleet legacy part NPE | `List.of(null)` — `fleet/application/FleetWorkOrderCostService.java:185` | `9e05d2f3` | `FleetMaintenanceIT.legacyPartLineReturns` — red: `FAILED ...legacyPartLineReturns(): […/return → {"message":"Internal Server Error","statusCode":500,…"name":"NullPointerException"}]` → green (return 2 → onHand 9, amount −50; more → 400) | all 9 `FleetMaintenanceIT` | – / journal amount asserted | unchanged (work-order line → engine) | fleet.md |
| N4 Approval gate under the transfer lock | `newTx` `checkOrRequestApproval` after `lockTransfer` — `StockDocService.java:1085,1248` | `302a7dd3` | `IprReview1IT.transitLossGateBeforeTheTransferLock` — red: `FAILED ...transitLossGateBeforeTheTransferLock(): [the request exists while the transfer row is still locked]` → green | same (pending-request 400 after the lock is released) | yes / – | gate first (no lock), then L1; a loss that grew under L1 → 409 | inventory.md |
| N5 Device spot count 409 behind another draft | `applyStockCount` called `counts.start` which refused (S4) — `SyncPushService.java:1547` | `e894fee9` | `IprReview1IT.deviceCountHeldBehindAnotherDraft` — red: `FAILED ...deviceCountHeldBehindAnotherDraft(): [{"status":"failed","httpStatus":409,"error":"Location Held count store already has an open cycle count CNT-00001; …"}]` → green | same (op applied, one `offline_held_posting`, one draft, on-hand 10) | yes / – | read-only check before `counts.start`; exception insert; no lock | sync.md |

## 2. Notes per finding

- **1 (online `occurredAt` flags).** The HTTP callers (`PosService` ×3, `PosInvoiceService:487/898`,
  `PosCashDrawerService:75`, `CashSessionService:375/478/861`) now call `OccurredAt.resolveRecorded(value, source)`.
  The kernel holds only an SPI (`OccurredAt.FlagRecorder`); the pos module installs `OccurredAtFlagRecorder` (it owns
  `InventoryException`), so accounting does not depend on pos (ModularityTests green). The row joins the caller's
  transaction (a refused sale leaves no flag) and is written once per request (dedup on kind, person and device
  time, because a checkout resolves the same time for order, invoice and payment). Two callers named in the review
  are not defects: `SyncPushService:1576` and `SyncStockService:235` run inside the sync op's offline binding, whose
  flag `runOp` already persists — `resolveRecorded` skips recording under an offline binding. The setting text of
  `inventory.maxBackdateDays` no longer claims refusal for these times.
- **7 (alias approvals).** Count approvals are never consumed and a restarted count gets a new session id, so the
  consumed case that matters is `stock_transfer_loss` (one approval per receipt). The test proves that a consumed
  approval by another person no longer clears the receiver's own approval of the next loss.
- **6 (unwrap).** `DataSourceUnwrapper.unwrapRoot` (spring-boot-jdbc 4.1.2) falls back to
  `DelegatingDataSource.getTargetDataSource()` when `unwrap` returns the same object, so Flyway's derived pool still
  resolves; the unit test asserts it.
- **N1.** `submitChecked` is no longer `@Transactional`: scope check (read-only tx) → `drainSales` with no
  transaction → submit in one transaction. The sync `stock.count` drains in `runInBoundary` before `opTx`, inside
  `ledger.outside` (its commits never anchor the op). `drainSales` throws when called inside a transaction.
  `stock.count` stays `OP_TRANSACTION` (crash-replay coverage in `SyncLedgerCoverageIT` unchanged).
- **N4.** The approval gate (approval engine on its own connection) runs on an unlocked read before `lockTransfer`;
  under the lock the loss is measured again and a growth beyond 0.005 is a 409 "record the receipt again". SoD,
  `consumeApproval` and the PIN check stay under the lock.
- **N5.** Checked before `counts.start` (a thrown refusal would mark the op transaction rollback-only). The held row
  is `offline_held_posting` (counted lines in `payload.lines`); S4 (one open draft per location) is kept.

## 3. Evidence run (fresh DB)

Fresh `erp-itdb-ipr1` (removed with its volume and recreated), one Gradle invocation (`fastTest --tests … test
--tests '*ArchitectureTests' --tests '*ModularityTests'`), `BUILD SUCCESSFUL`, 0 failed, 0 skipped. The full build did
not run.

| Class | Tests | | Class | Tests |
|---|---|---|---|---|
| IprReview1IT | 11/11 | | ApprovalControlsIT | 10/10 |
| TenantBindingDataSourceTest | 1/1 | | TenantRlsBackstopIT | 3/3 |
| SalesReturnIT | 11/11 | | OccurredAtFlagTest | 3/3 |
| FleetMaintenanceIT | 9/9 | | PosCashDrawerIT | 6/6 |
| IprStockControlsIT | 9/9 | | PosFlowsIT | 4/4 |
| IprResidualsIT | 2/2 | | PeriodCloseChecklistIT | 7/7 |
| IprPosRefundRestockIT | 3/3 | | SyncLedgerCoverageIT | 6/6 |
| OpeningStockImportIT | 9/9 | | SyncOpCatalogTest | 4/4 |
| OpeningStockIT | 2/2 | | SyncInventoryIT | 9/9 |
| CountCutoffIT | 10/10 | | SyncInventoryReplayIT | 7/7 |
| StockEffectiveTimeIT | 8/8 | | SyncInventoryRecoveryIT | 2/2 |
| InventoryReportsHardeningIT | 8/8 | | SyncCashCloseIT | 3/3 |
| StockReservationOverviewIT | 3/3 | | SyncSalesIT | 9/9 |
| InventoryBranchScopeIT | 7/7 | | SyncOpsIT | 3/3 |
| ArchitectureTests | 5/5 | | ModularityTests | 2/2 |

Web: `pnpm web:typecheck` clean; `pnpm --filter @erp/web test` 26 files, 268/268.

## 4. New defects found (not in the review)

- `sync/application/SyncPushService.java:789` (base): `runOp` wrote the time flag (`stockOps.flagTime`) outside
  `ledger.outside`, so for a `SERVICE_TRANSACTIONS` op the flag's commit anchored the op's ledger row before the
  business commit. Fixed here (wrapped in `ledger.outside`).
- `accounting/application/PeriodCloseService.java:123` (base): `OPEN_SALE_LINE_EXCEPTIONS` counted the report-only
  `offline_clock_skew` / `offline_late_sync` rows, so a flagged late sync held the period close with "sale lines
  whose stock/COGS was not posted". Fixed here (excluded).
- `analytics/application/InventoryKpiService.java:127`: calls `reports.stockHealth(q)` and may expose values to a
  caller without `inventory_valuation:read` (analytics module, outside this stream; not changed).
