# IPR review 3 — inventory engine core fixes (stream R3)

Branch `fix/ipr-review3` (off the integration branch `fix/ipr-p15` at baf93fbc). Not pushed, not merged, not
deployed. No migration used (V723 stays free). Rules: R1 (no UPDATE/DELETE of ledger or posted journals; corrections
are compensating rows), R2 (lock order L1–L7), no auto-repair of data. Line numbers are from `fix/ipr-p15`.

## How red and green were run

- **Red.** All main-source fixes were saved as a patch (`git diff -- backend/src/main`), the main sources were checked
  out at the branch base (no `git stash`), and `IprReview3EngineIT` ran against the base code on `erp-itdb-ipr3`.
  Then the patch was applied back. Two tests were re-run red alone after a fixture correction (#6: the count line of
  a lot product; #4: a fixture that values consistently, see below).
- **Green.** Final run on a freshly reset `erp-itdb-ipr3` (`docker rm -f -v`, new `postgres:16`). Counts in §3.

## 1. Evidence per finding

All regression tests are in `backend/src/test/java/com/inventoryerp/inventory/audit/IprReview3EngineIT.java`. Every
scenario ends with the integrity report (checks, and report-only checks except `feature_risk` / `negative_quants`) and
the GL tie-out (`/reports/inventory/gl-tieout` + `gl_tieout_by_account`); #6 asserts the checks and the GL tie-out.

| Finding | Root cause (file:line, base) | Fix commit | Regression IT (red → green) | Workflow IT | Integrity / GL asserted | Lock levels | Deviations |
|---|---|---|---|---|---|---|---|
| #1 serial+lot transfer naming a lot | `StockService.transferSerials` :1585 → `pickSerials` :2166 only *preferred* the named lot, while the lot loop :1371 decremented only the named lot; `pinnedBatchIds` honoured by `lots()` :2136 but not by `pickSerials` | see §2 | `transferNamedLotStaysInLot` — red: `FAILED …transferNamedLotStaysInLot(): [L2's serial unit stays at the source]` → green | same (transfer, refused serial outside the lot) | yes / yes | unchanged: L3 costing → L3b freeze → L4 both quants (location order) → L5 → L6 lots / serials (read under L4) → L7 | inventory.md (review 3) |
| #2a take-back of a capture-optional receipt row | C-2 "pick the rest" `StockService.issue` :809-812 applied to reversals: `StockReversalService.reverseLedgerSource` :450-456, `issueBackReceipt` :684 pinned the received serials, then the engine picked other receipts' serials | see §2 | `reversalTakesBackUnserialisedUnits` — red: `[another receipt's serial unit stays]`; `issueBackTakesUnserialisedUnits` — red: `[another receipt's serial unit stays]` → green | same (reverse a GRN; cancel a bill: issue back + caller's journal) | yes / yes | reverseLedgerSource: caller's L1 → engine L3–L7; issueBackReceipt: L1 → L2 receipt rows `FOR UPDATE ORDER BY id` → L3–L7. `pinReceived` reads only | inventory.md (review 3) |
| #2b two rows of one document | `receivedSerials` :585 scoped by `receiptRef` (whole document); `inStock.size() <= qty` :454 failed per row → nothing pinned → oldest serials | see §2 | `reversalOfTwoRowsPinsEachRow` (strict reversal) — red: `[older receipt's serial unit stays]` → green | same | yes / yes | as #2a | inventory.md (review 3) |
| #3 issue naming a lot (MANUAL) | `StockService.issue` :812 `pickSerials(…preferLotNumber)` preferred, then other lots' serials before the lot's unserialised part | see §2 | `issueNamedLotStaysInLot` — red: `[L2's serial unit is not released]` → green | same (lot short: falls back to the other lot, noted) | yes / yes | unchanged: L3 → L3b → L4/L5 → L6 → L7 | inventory.md (review 3) |
| #4 blank cost, STANDARD | `receiveCore` :380 `currentCost` (quant average / last receipt / cost price) used as the *actual* cost against the standard (:498) → false PPV | see §2 | `standardBlankCostAtStandard` — red: `[blank cost = the governing standard (GRNI at 30)]` → green | same (AVCO bought at 12, sold out, switched to STANDARD at 10 — allowed with no stock; the empty quant kept average 12) | yes / yes | unchanged: L3 → L4/L5 → L7 | inventory.md (review 3) |
| #5 AVCO count gain, negative quant, average ≤ 0 | `StockService.adjust` :1141: gain at current cost, `newAverage` only when `currentQty ≥ 0` → average stays 0, value stranded | see §2 | `avcoCountGainOnNegativeQuant` — red: `[the quant takes the gain's cost]` → green | same (receive at 0, oversell, count up) | yes / yes | unchanged: L3 → L4/L5 → L7 (the cost correction is a value-only ledger row + journal under the same locks) | inventory.md (review 3) |
| #6 late count correction poisons the late movement | `correctLateCountRows` :1810-1825: pre-check covers variance accounts only; a refusal thrown through the proxied `StockPostingService` / `PostingService` marks the transaction rollback-only (the JDBC savepoint cannot undo it); `publishOnCommit` inside the rolled-back savepoint still fired | see §2 | `lateCorrectionRefusedByPostingKeepsMovement` (lot product; the correction's credit account requires an analytic dimension) — red: `FAILED …(): Transaction silently rolled back because it has been marked as rollback-only` → green (movement commits, no correction, `InventoryException late_count_correction_failed` open, `lateTransactions` shows `uncorrected`, no `stock.received` event from the rolled-back correction) | `CountCutoffIT` (E1 pre-check path, late sale exactly once) | checks / yes | no new level: runs in the late row's transaction under its L3/L4/L5; savepoint; exception row insert | count spec §4.4a |
| #7 freeze exemption for any session | `freezeExempt` :1724 returned true for any `countSessionId` | see §2 | `freezeExemptsOnlyItsSession` — red: `FAILED …freezeExemptsOnlyItsSession():` (no 409) → green | `CountCutoffIT.freezeRefusesManualMoves` | yes / yes | count posting: no L3b (unchanged, it holds L4), reads `InventoryCountSession` | count spec §6 |

## 2. What changed

- `StockService.pickSerials`: optional *only this lot number* filter (at the move's location); for a lot-tracked
  product whose move pins lots and no serial units, only units of those lots (or theirs first with `pinFallback`).
- `transferSerials`: a named lot restricts the pick to that lot (no fallback: the lot must cover the quantity, as
  before); named serial numbers outside the named lot are refused (400 "Serial number(s) … are not in batch … at the
  source location").
- `issue` (serial branch): a named lot (MANUAL) gives its serial units, then its unserialised part, then other lots'
  serial units and unserialised parts (rows noted "LOT … SHORT: drawn from other lots", as the lot-only path does).
  New `StockMove.unserialisedRestFirst`: units beyond the named serials leave as units without a serial first
  (`unserialisedFree`), other serial units only for the rest.
- `StockReversalService`: `pinReceived` shares a document's received serial units out across its rows (per pool:
  product, location, document, lot) and sets `unserialisedRestFirst`; the strict check compares per row
  (`min(qty, pool − claimed)`). Transfer-in rows keep the old behaviour.
- `receiveCore`: blank cost of a standard-governed product = its governing standard.
- `adjust` (AVCO, average ≤ 0, negative quant): `CostResolver.resolveReceiptCost` gives the new average and the
  negative-stock cost correction (`postCostCorrection`), as `receiveCore` does.
- Late corrections: `Savepoints.runIsolated` (kernel) + `TenantAwareTransactionManager.doSetRollbackOnly` hook: a
  participant failure inside the block is absorbed (the savepoint rolls it back) instead of marking the whole
  transaction rollback-only; a swallowed participant failure still fails the block; transactions begun inside the
  block (REQUIRES_NEW) keep Spring's default. `DetachedWrites` commit-only writes registered in the block are dropped
  on rollback (existing `Savepoints.run` behaviour). Failures — pre-check or thrown — insert an open
  `InventoryException` (`late_count_correction_failed`).
- `assertNotFrozen`: a count posting is checked against committed freezes (without L3b); only the freeze's own
  session passes.

## 3. Tests (final run, fresh `erp-itdb-ipr3`)

See the stream report for the per-class counts of the final run.

## 4. Known limits / notes

- #2: the pool of a document is matched by `receiptRef` (document) and lot; the serial table records no receipt
  ledger row, so units are shared out across rows in ledger order (exact as a set for a whole-document take-back, the
  only kind both helpers do). A row of a document that received the same product at two locations still compares
  against the whole pool, as before.
- #4: in a consistent database the governing standard and the quant average agree after an activation / cost-price
  revaluation (all quants, zero quantity included). The false PPV arises from a quant left by an earlier method
  (method change with no stock on hand) or legacy pre-R5b quants (average 0 → last receipt cost).
- #6: a refusal raised by Hibernate itself (a `PersistenceException` from a flush) still marks the transaction
  rollback-only; none of the correction's refusals are of that kind (validation precedes the journal insert).
