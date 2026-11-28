# IPR 1b evidence: effective time, count cut-off, freeze, count guards

Stream: engine owner A, branch `fix/ipr-1b` (worktree `/home/ierp-ipr-p0-checks`). Migrations V700, V701.
ADR-S088 (Accepted, P15-1), count spec `docs/spec/inventory-count-reconciliation.md`. Deviations:
`docs/spec/inventory.md` (Deviations 55–59) and `docs/spec/sync.md` (three IPR 1b entries).

Paths are relative to `backend/src/main/java/com/inventoryerp/`. Root-cause lines are for the branch base
(`HEAD` before 1b). Tests are in `backend/src/test/java/com/inventoryerp/`.

## How red and green were produced

- **Red:** `backend/src/main` changes (incl. V700/V701) set aside, tests kept, fresh DB (`erp-itdb-ipr0`), run
  `fastTest --tests '*StockEffectiveTimeIT' '*CountCutoffIT' '*CommonMathTest' '*SyncInventoryIT'`:
  `37 tests completed, 18 failed` (log kept in the stream's scratchpad as `ipr1b-red-evidence.log`). The FAILED lines
  are quoted per finding below.
- **Green:** the same classes plus the regression set, on a freshly reset DB; per-class counts in the last section.
- Sale-based CountCutoffIT cases first failed red on a test-fixture problem (a zero-priced product makes the POS
  sales journal empty: "A line must have either a debit or a credit"), not on the defect. The fixture was fixed
  (`sellable(...)`, sales price 100) and those cases were re-run red; see "Red, second run".

## Findings

| Finding | Root cause (file:line) | Fix commit | Regression IT (red → green) | Workflow IT | Integrity/GL asserted | Lock levels | Deviations |
|---|---|---|---|---|---|---|---|
| T10 / R3 effective time on every row | `inventory/application/StockService.java:1754` (`insertLedger` writes only `createdAt`) | `08d3548e` | `StockEffectiveTimeIT.backOfficeMoveUsesNow` red: `bad SQL grammar [SELECT "ledgerCode", "quantityChange", "effectiveAt" ...]` → green; `v700KeepsLedgerGuard` red: `Incorrect result size: expected 1, actual 0` → green | `StockEffectiveTimeIT.goodsReceiptBackdating` (GRN date-only → end of day) | yes (GRN, adjust, transfer cases) | none new (column write in the existing insert) | ADR-S088 "Implementation": value-only rows carry NOW |
| SW-7 accept and flag | `kernel/common/OccurredAt.java:40,43` (throws 400 for future / > 7 days) | `08d3548e` | `StockEffectiveTimeIT.lateOfflineOpAcceptedAtDeviceTime` red: `"error":"occurredAt is more than 7 days old — check the device clock"` → green; `futureOpStoredAtReceiveTime` red: `"error":"occurredAt is in the future — check the device clock"` → green; `replayKeepsIdempotency` red → green; `CommonMathTest.occurredAt` red: `occurredAt is in the future — check the device clock` → green; `OccurredAtFlagTest` (unit, new) | `lateOfflineOpAcceptedAtDeviceTime` (stock.out 30 days late: applied, `effectiveAt` = device time, `offline_late_sync` raised) | yes | none | sync.md: clamp-and-flag for every op type (P15-1) |
| M-1 offline moves at device time | `sync/application/SyncPushService.java:778` (op time only validated, never passed to the stock moves) | `08d3548e` + `5584b2ba` | `StockEffectiveTimeIT.lateOfflineOpAcceptedAtDeviceTime`; `CountCutoffIT.correctionLinks` red (no correction) → green | `CountCutoffIT.correctionLinks` (offline stock.out before a count, synced after it) | yes | none new | sync.md |
| SW-6 adjust / transfer honour `StockMove.date` | `StockService.java:998, 1051` (adjust: period check and journal on `Instant.now()`), `:1155, 1380` (transfer) | `08d3548e` | `StockEffectiveTimeIT.adjustmentHonoursItsDate` red (`bad SQL grammar` on the new column read; the journal date assertion follows) → green; `transferHonoursItsDate` → green | same | yes | none | ADR-S088: journals move to the document date |
| Closed-period traceability | `StockService.java:1051` (memo "Stock adjustment · name" only) | `08d3548e` | memo asserted in `adjustmentHonoursItsDate`; ledger note "occurred X, posted Y" written when the posting day differs (not asserted by an IT: no closed-period fixture in 1b) | — | — | none | `period_shifted` now compares effective vs posting period (report-only) |
| X-1a count carry-forward by effective time + submit drain + 409 | `inventory/application/InventoryCountService.java:601` (`createdAt > :since`), `:682` (submit never drains the location's POS jobs) | `5584b2ba` | `CountCutoffIT.submitDrainsQueuedSale`, `failedSaleBlocksSubmit`, `saleAfterCutoffNotCorrected` (red: see second run) → green | `submitDrainsQueuedSale` (sale during the count, queued, drained at submit, no variance) | yes | drain before any lock (job → invoice order unchanged); then L1 session FOR UPDATE, L4 `lockQuants` | inventory.md 56 |
| X-1b late transaction, exactly-once correction | no late-row handling anywhere (count spec §4 new) | `08d3548e` + `5584b2ba` | `CountCutoffIT.lateSaleCorrectedExactlyOnce` (two parallel pushes of the same op + two parallel job runs: one correction) → green; `correctionLinks` red (assertion, no correction row) → green | `lateSaleCorrectedExactlyOnce` | yes | correction runs in the late row's transaction under its L3/L4/L5; V701 unique index | inventory.md 56: AVCO-valued, posted today, forced lines included |
| X-1c sync `movedSince` / device count | `sync/application/SyncStockService.java:392` (`createdAt > ?`), `SyncPushService.java:1566` (counted + movedSince) | `08d3548e` + `5584b2ba` | `SyncInventoryIT.countMeasuredWhenCounted` red (old figure 13 stored) → green (8 stored, `countedAt` = device time, on-hand 13) ; `CountCutoffIT.deviceCountAtDeviceTime` | `deviceCountAtDeviceTime` | yes | — | sync.md (stored count figure) |
| X-1d beverage phantom gain | `beverage/application/BeverageCountService.java:472` (posts `countedMl` against on-hand now, no carry-forward) | `5584b2ba` | not covered by a new IT (see Not done) ; `BeverageIT` regression green | — | — | L1 session, then L3/L4 `lockQuants` | — |
| M-30 tank dip book level | `fleet/application/FleetFuelTankService.java:232` (`createdAt <= ?`) | `08d3548e` | `FleetFuelIT` regression green; no new red IT | — | — | — | — |
| Freeze | none (new) | `08d3548e` + `5584b2ba` | `CountCutoffIT.freezeRefusesManualMoves` red: `property freezeLocation should not exist` → green | same | yes | L3b `freeze:<org>:<location>`: shared per move (sorted), exclusive at start taken with `pg_try_advisory_xact_lock` polling (never queued) | inventory.md 57 |
| R2 count submit vs sales | — | `08d3548e` + `5584b2ba` | `CountCutoffIT.submitsRaceSales` red: `property freezeLocation should not exist` → green, no 40P01 | same | yes | as above | — |
| T12 S4 one draft per location | `InventoryCountService.java:298` (open draft per (location, type)) | `5584b2ba` | `CountCutoffIT.oneDraftPerLocation` red: both raced starts answered 201 → green (201 + 409; adjustment approval 409) | same | — | V701 unique index; L1 | inventory.md 58 |
| T12 S1 default cycle, opening gated | `InventoryCountService.java:249, 315`; web `InventoryCountPage.tsx:85` | `5584b2ba` | `CountCutoffIT.openingCountGated` red (no-type start created an opening count) → green | same | yes | — | inventory.md 58: no-type start without scope → 400 |
| T12 S7 row locks | `InventoryCountService.java:504` (`saveDraft`), `:682` (`submit`) | `5584b2ba` | covered by `submitsRaceSales` / `oneDraftPerLocation` (no dedicated red IT) | — | — | L1 FOR UPDATE | — |

## Red, second run (sale fixture fixed)

Same method (base `backend/src/main` from `fb91d736` checked out, V700/V701 and new main files removed, fresh DB),
`fastTest --tests '*CountCutoffIT'`: `10 tests completed, 9 failed` (log `ipr1b-red2.log` in the scratchpad).

```
FAILED CountCutoffIT.failedSaleBlocksSubmit(): [{... "countCode":"CNT-00001", ... "status":"submitted" ...   (submit not refused)
FAILED CountCutoffIT.submitsRaceSales(): [POST /inventory/counts/start → {"message":["property freezeLocation should not exist"] ...
FAILED CountCutoffIT.oneDraftPerLocation(): [[Raced[value=Res[status=201, ... "countType":"spot","status":"draft" ...   (both 201)
FAILED CountCutoffIT.lateSaleCorrectedExactlyOnce(): [sale −1, correction +1: the counted 9 stands]
FAILED CountCutoffIT.correctionLinks():   (no correction row)
FAILED CountCutoffIT.submitDrainsQueuedSale(): [drained by the submit]
FAILED CountCutoffIT.saleAfterCutoffNotCorrected(): [null]
FAILED CountCutoffIT.freezeRefusesManualMoves(): [POST /inventory/counts/start → {"message":["property freezeLocation should not exist"] ...
FAILED CountCutoffIT.openingCountGated():   (no-type start created an opening count)
```

`deviceCountAtDeviceTime` passes on the base code too, so it is a workflow IT only, not a regression IT; the red for X-1c is `SyncInventoryIT.countMeasuredWhenCounted` (first run).

## Final run (fresh DB)

Code at `5584b2ba` (docs `71d6be28`), DB `erp-itdb-ipr0` reset (`docker rm -f -v`, fresh `postgres:16`), one
invocation: `fastTest --tests <22 classes> test --tests '*ArchitectureTests' --tests '*ModularityTests'`
(log `ipr1b-final.log`). `207 tests completed, 1 failed` (fastTest), Architecture/Modularity 7/7.

| Class | Tests | ok | failed |
|---|---|---|---|
| inventory.StockEffectiveTimeIT | 8 | 8 | 0 |
| inventory.CountCutoffIT | 10 | 10 | 0 |
| kernel.common.CommonMathTest (incl. nested QtyMul 4, Beverage 5) | 10 | 10 | 0 |
| kernel.common.OccurredAtFlagTest | 3 | 3 | 0 |
| sync.SyncInventoryIT | 9 | 9 | 0 |
| sync.SyncIT | 10 | 10 | 0 |
| sync.SyncInventoryReplayIT | 7 | 7 | 0 |
| inventory.InventoryIT | 21 | 21 | 0 |
| inventory.OpeningStockIT | 2 | 2 | 0 |
| inventory.InventoryEngineGapsIT | 13 | 13 | 0 |
| inventory.InventoryEngineHardeningIT | 13 | 13 | 0 |
| inventory.InventoryHardeningIT | 7 | 6 | 1 (`ledgerGuardBypass`: "The url cannot be null", known IT_EXTERNAL_DB env failure) |
| inventory.InventoryIntegrityP0IT | 16 | 16 | 0 |
| inventory.InventoryLedgerImmutabilityTest | 2 | 2 | 0 |
| inventory.NegativeStockPolicyIT | 8 | 8 | 0 |
| inventory.audit.InventoryAuditConcurrencyIT | 8 | 8 | 0 |
| inventory.audit.InventoryAuditRemediationIT | 13 | 13 | 0 |
| beverage.BeverageIT | 4 | 4 | 0 |
| fleet.FleetFuelIT | 9 | 9 | 0 |
| pos.PosStockPostingIT | 10 | 10 | 0 |
| pos.PosStockPostingRecoveryIT | 2 | 2 | 0 |
| procurement.ProcurementIT | 22 | 22 | 0 |
| ArchitectureTests | 5 | 5 | 0 |
| ModularityTests | 2 | 2 | 0 |

Web: `pnpm web:typecheck` exit 0; `pnpm --filter @erp/web test` 22 files / 245 tests passed. The full Gradle build
was not run.

## Not done / partly done

- X-1d (beverage carry-forward) and M-30 (tank dip by effective time): fixed, covered only by the existing
  `BeverageIT` / `FleetFuelIT` regressions; no new red IT.
- Closed-period traceability: ledger note "occurred X, posted Y" written but not asserted (no closed-period fixture).
- T12 S7: row locks in place, no dedicated red IT.
- Back-office backdate limit (ADR-S088 limit 2) is enforced on goods receipts only; adjustments, transfers and other
  documents keep the period check only.

## Residual risks / new defects

- `StockService` late correction calls `adjust` on itself inside `SAVEPOINT late_count_correction`; if a proxied
  `StockPostingService` call throws inside it, Spring may already have marked the transaction rollback-only.
- A multi-location document can take L3b (freeze, shared) after L4 of an earlier line; mitigated by the exclusive
  side being a polled try-lock (10 s, then 409), never a queued wait.
- `deviceCountAtDeviceTime` is not discriminating (passes on the base code).
