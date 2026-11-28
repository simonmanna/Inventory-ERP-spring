# IPR Phase 4, T32: opening-stock import (stream G evidence)

Branch `fix/ipr-4-opening`, based on `fix/ipr-p15` at `43b50363`. Test DB: owner-split Postgres 16 `erp-itdb-ipr1`
on port 5701. Requirement R5; finding **S-5** (no opening-stock import). Design: [ADR-S090](../../adr/ADR-S090-opening-stock-import.md).
Migration: `V715__opening_stock_import.sql` (V716–V719 unused).

No engine core file was edited (`StockService`, `StockMove`, `StockReversalService`, `StockPostingService`,
`CostResolver`, `LayerCostWriter`, `InventoryLedger` untouched). The import uses their public / package APIs:
`StockService.receive`, `StockService.assertStockPeriodOpen`, `StockService.freezeLockKey`,
`StockPostingService.stockAccount` / `stampLine`, `CostingLocks.shareProduct`, `StockMove.inLockOrder`.

## Red run

S-5 is an absence finding. Against the base (`43b50363`) the regression IT cannot run: `OpeningStockImportIT` refers
to `OpeningStockImportController`, which does not exist, and every route it calls answers 404. That is the red state;
there is no older behaviour to compare with.

## Evidence table

| Finding | Root cause (file:line) | Fix commit | Regression IT (red → green) | Workflow IT | Integrity / GL asserted | Lock levels | Deviations |
|---|---|---|---|---|---|---|---|
| S-5 opening-stock import (R5) | absence: opening stock only through an opening count (`InventoryCountService.java:361, 441-463`), one location and line at a time | see commits below | `OpeningStockImportIT` (all 9 tests). Red: no controller / 404 (see above) | `happyPathWithLotAndSerialProducts`, `failedBatchIsRetried`, `largeImportSplitsIntoBatches`, `parallelSubmitOfTheSameRun` | yes: every scenario ends with `assertIntegrityGreen` and `assertGlTieOut`, plus the run reconciliation | import: approval gate in REQUIRES_NEW (`ApprovalRequest` only), run + batch insert (no stock locks); each batch: L1 run row → L1 batch row (`FOR UPDATE`) → L3 costing org + products (id order, shared) → L3b location freeze key exclusive (polled try-lock, never queued) → revalidation → engine L3/L3b (re-entrant) → L4 quant → L5 `StockItem` → L6 lot / serial → L7 journal. Failure record: L1 run row → batch update | inventory spec "IPR Phase 4"; ADR-S090 sign-off items |

## R5 requirement → test

| R5 clause | Implementation | Test |
|---|---|---|
| Atomic per batch | one `REQUIRES_NEW` transaction per batch; a failure rolls the whole batch back | `failedBatchIsRetried` (injected crash on the second batch's ledger insert: nothing of it written, the first batch posted, run `partial`) |
| ≤ 2,000 rows per batch, per location | `BATCH_ROWS`; DB check `rowCount <= 2000` | `largeImportSplitsIntoBatches` (2,500 rows → 2,000 + 500) |
| Run with batch status, run-level reconciliation, complete only when every batch posted | `OpeningStockImportRun.status` from its batches; `reconciliation.complete` | `failedBatchIsRetried` (`complete = false` while a batch is failed) |
| Failed batch retried idempotently | `retry`: batch row lock + status; posted → 409 | `failedBatchIsRetried`, `parallelSubmitOfTheSameRun` (4 parallel retries → 1 × 200, 3 × 409) |
| Idempotency-Key per (org, key); replay returns the first result | kernel `@Idempotent(required = true)` | `replaySameKeyReturnsFirstResult`; missing key 400 in `authorization` |
| Unique `(organizationId, importHash)` on the batch (and on the run) | V715 unique indexes | `sameFileTwiceRefusedByImportHash`; `parallelSubmitOfTheSameRun` (4 parallel submits with different keys → 1 × 200, 3 × 409) |
| Preview: per-row validation, exact GL journal, no writes, `previewToken` (rows + snapshot) | `preview`, `batchToken`, `previewToken` | `happyPathWithLotAndSerialProducts` (journal totals), `locationWithHistoryIsRefused` (row errors, no token) |
| Submit needs the token; approval bound to it; recomputed under L3; 409 "Preview is stale — preview and approve again" | `importRun` (token check, approval workflow entity id = token), `postBatch` (locked revalidation) | `stalePreviewIsRefused` (costing method changed → 409, nothing written); `failedBatchIsRetried` (stale retry → 409, re-approved at the new `batchToken`) |
| Batch id on every ledger row and on the journal; reconciliation endpoint | ledger `referenceType = 'opening_stock_import'`, `referenceId` = batch id; journal `sourceType` / `sourceId` the same | `happyPathWithLotAndSerialProducts`, `assertReconciled` in every posting test |
| Only locations with no ledger history | row error; locked recheck | `locationWithHistoryIsRefused` |
| Needs approval | `approve: true` + `inventory_valuation:post`; optional `opening_stock_import` workflow | `authorization` (`approve: false` → 400, manage-only → 403) |

## Tests

Final run on a freshly reset DB (`docker rm -f -v erp-itdb-ipr1`, new `postgres:16`): see "Test results" below.

## Test results

Run on a freshly reset `erp-itdb-ipr1` (2026-11-27), one Gradle call: `spotlessApply compileTestJava fastTest
--tests …` plus `test --tests '*ArchitectureTests' --tests '*ModularityTests'`. BUILD SUCCESSFUL in 10m 45s.

```
COUNT com.inventoryerp.AuthorizationMatrixIT: 2 tests, 2 ok, 0 failed, 0 skipped
COUNT com.inventoryerp.inventory.InventoryIT: 1 tests, 1 ok, 0 failed, 0 skipped   (authorizationMatrix, 153 routes)
COUNT com.inventoryerp.inventory.InventoryIntegrityP0IT: 16 tests, 16 ok, 0 failed, 0 skipped
COUNT com.inventoryerp.inventory.InventoryLedgerImmutabilityTest: 2 tests, 2 ok, 0 failed, 0 skipped
COUNT com.inventoryerp.inventory.OpeningStockIT: 2 tests, 2 ok, 0 failed, 0 skipped
COUNT com.inventoryerp.inventory.OpeningStockImportIT: 9 tests, 9 ok, 0 failed, 0 skipped
COUNT com.inventoryerp.kernel.TenantRowSecurityIT: 3 tests, 3 ok, 0 failed, 0 skipped
COUNT com.inventoryerp.ArchitectureTests: 5 tests, 5 ok, 0 failed, 0 skipped
COUNT com.inventoryerp.ModularityTests: 2 tests, 2 ok, 0 failed, 0 skipped
```

Web: `opening-stock-csv.test.ts` (8) and the cache-policy test green; `pnpm web:typecheck` clean. The full
`./gradlew build` was not run.

## Web

- `/inventory/opening-stock` (`OpeningStockImportPage`): CSV upload (template download), preview with row errors and
  the journal, approve and import, run list and status, batch retry (with re-approval of a stale batch at its new
  token), reconciliation display.
- `features/inventory/opening-stock-csv.ts` (column mapping, aliases, limits) with `opening-stock-csv.test.ts`.
- Query keys `opening-stock-runs`, `opening-stock-run`, `opening-stock-reconciliation` classified `never` in the
  offline cache policy (run state changes while batches post).

## Engine requests

None needed. Optional: a public `StockService.lockFreezeExclusive` variant that takes the caller's refusal message
(the import copies its polled try-lock with its own message instead of calling it).
