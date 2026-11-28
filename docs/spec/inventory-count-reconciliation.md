# Inventory count reconciliation with late transactions

- Status: **Proposed (IPR Phase 0, task T02); implementation Phase 1b (T11, T12).** Nothing here is built yet,
  except what §2 calls "today".
- Requirement: IPR plan rev 3, R3b; owner decision D1 (2026-11-27); finding X-1.
- Depends on: ADR-S088 (`effectiveAt` on every ledger row; readers use `COALESCE("effectiveAt", "createdAt")`).
- Migrations: V700 (`InventoryLedger."effectiveAt"`, `InventoryCountLine."measuredSeq"`, `InventoryCountSession."freezeLocation"`) and V701 (one-draft-per-location index, late-correction unique index), reserved in `docs/adr/migration-registry.md`. None in Phase 0.

Paths are relative to `backend/src/main/java/com/inventoryerp/`. Line numbers are for `origin/main` 0e670f12.

## 1. Definitions

**Cell.** One quant: (organisation, location, product, variant). A lot line narrows the cell to one
`InventoryBatch`; an "unlotted remainder" line is the cell minus its open lots (as `InventoryCountService.postings`
does today, `inventory/application/InventoryCountService.java:636-647`, `:655`).

**Effective time of a ledger row.** `E(r) = COALESCE(r."effectiveAt", r."createdAt")` (ADR-S088 §1).

**Cut-off `T_c`.** The moment the shelf was counted, per line:

| Count kind | Code path | `T_c` (from 1b) | What the code uses today |
|---|---|---|---|
| Back-office count (screen or scanner) | `InventoryCountService.saveDraft` → `submit` | `InventoryCountLine."countedAt"`: server time when the line's counted quantity last changed (`InventoryCountService.java:527`); if null, the session's `startedAt` (`:594`, `:628`) | same value, compared with `"createdAt"` |
| Warehouse ABC cycle count | `WarehouseAbcService.generateCycleCount` opens a normal count session (`inventory/application/WarehouseAbcService.java:172`) | as back-office count | as back-office count |
| Device count (`stock.count` sync op) | `SyncPushService.applyStockCount` (`sync/application/SyncPushService.java:1520-1577`) | The op's device `occurredAt`, after the ADR-S088 future-time clamp (§7) | `occurredAt` feeds `SyncStockService.movedSince` (`:1566`), but the line's `countedAt` is set to server **NOW at sync** by `saveDraft` (`:1573` → `InventoryCountService.java:527`) |
| Beverage bottle count | `BeverageCountService.saveDraft` → `submit` (`beverage/application/BeverageCountService.java:316`, `:418`) | `BottleCountLine."countedAt"`: server time when the line was weighed (`:351`) | **not used**: the variance posts against the on-hand at submit (§2.3) |

**Book at the cut-off.** `book(T_c) = Σ quantityChange` of the cell's ledger rows with `E(r) ≤ T_c`. A row whose
effective time equals `T_c` counts as before the cut-off.

**Variance (the formula).** For a counted line, measured at submit under the cell lock (L4):

```
net_after(T_c) = Σ quantityChange of the cell's rows already written with E(r) > T_c
now            = the cell's on-hand under the lock  (= book(T_c) + net_after(T_c))
variance       = counted − book(T_c)
               = counted + net_after(T_c) − now           ← what submit posts
```

This is today's formula (`InventoryCountService.java:662`, `delta = counted + net − nowQty`) with one change:
`net_after` selects by `E(r) > T_c` instead of `"createdAt" > T_c` (`:601`, `:652`). `force` (overwrite) keeps its
meaning: `variance = counted − now` with a reason (`:662`, `:788-791`).

**Measured sequence.** At submit, each counted line records the highest ledger `seq` (V654) of its cell that the
measurement saw (`InventoryCountLine."measuredSeq"`, new nullable column, V700). A row of the cell with
`seq > measuredSeq` was written after the count was measured. Under the L4 cell lock, inserts on a cell are serial
with the submit's measurement, so `seq` order is commit order for that cell. No clock comparison is needed.

**Late transaction.** A ledger row `r` of a counted cell with `seq > measuredSeq` of a submitted count line whose
`T_c > E(r)`. It happened before the count but reached the server after the count was submitted.

**Count variance row.** The `InventoryLedger` row posted by the count's adjustment for that line
(`referenceType = 'stock_adjustment'`, the session's `adjustmentId` → `adjCode`; `StockDocService.postCountVariances`,
`inventory/application/StockDocService.java:720`). A line with zero variance has none.

## 2. Today's behaviour (defect X-1)

All three paths compare against row write time or not at all. Online POS sales also lag: the stock posting job
writes the ledger row when it drains (worker every 30 s), not at checkout.

### 2.1 Back-office count: `"createdAt"` instead of effective time

`InventoryCountService.postings` (`InventoryCountService.java:588-662`) reads rows with `"createdAt" > :since`
(`:599-602`) and keeps a row as "after the count" when `createdAt` is after the line's `countedAt` (`:650-656`).

Example X-1a (pre-cutoff sale, written before submit). Book 10. Online POS sale of 1 at 09:55; its job drains at
10:02 (`createdAt` 10:02). Line counted at 10:00: counter sees 9, enters 9. Submit at 10:30.
- now = 9; net_after (by `createdAt`) = −1;
- delta = 9 + (−1) − 9 = **−1**: a phantom loss of 1. Stock ends at 8; the shelf has 9.

Example X-1b (pre-cutoff offline sale, written after submit). Book 10. Offline till sells 1 at 09:55. Count at
10:00 enters 9; submit 10:30: delta = 9 + 0 − 10 = −1 (loss, correct at that moment). The till syncs at 11:00 and
the sale issues 1 more: stock **8**, shelf 9. The loss is counted twice; nothing links the two rows.

### 2.2 Device count: `movedSince` by `"createdAt"`

`SyncStockService.movedSince` (`sync/application/SyncStockService.java:385-393`) sums rows with
`"createdAt" > occurredAt` and adds them to the counted figure (`SyncPushService.java:1562-1568`). The line's
`countedAt` then becomes server NOW, so `postings` finds no later rows.

Example X-1c. Book 10. Till A sells 1 offline at 09:55, syncs at 11:00 (`createdAt` 11:00). Device B counts at
10:00 (sees 9), syncs at 12:00.
- movedSince(10:00) = −1 (the 11:00 row), so counted' = 9 + (−1) = 8;
- now = 9; delta = 8 − 9 = **−1**: a phantom loss. Stock 8, shelf 9.

### 2.3 Beverage count: no carry-forward at all

`BeverageCountService.submit` (`beverage/application/BeverageCountService.java:465-482`) creates an embedded
adjustment with `qtyActual = countedMl` (`:469-471`). `StockDocService.createAdjustment` stores
`qtySystem` = the on-hand **at submit** (`StockDocService.java:577-578`), and the embedded approval posts
`qtyActual − qtySystem` (`:636`, `:676`; the drift check is skipped for embedded documents). The line's `countedAt`
is never read. The displayed `varianceMl` is against `systemMl` taken when the session started
(`BeverageCountService.java:252-257`, `:403`), so the screen and the posting disagree.

Example X-1d. Bottle cell 700 ml. Weighed at 10:00: 700 ml. POS sells a 30 ml shot at 10:05 (drained 10:06).
Submit at 10:30: now = 670; delta = 700 − 670 = **+30**: a phantom gain. The sold shot is put back.

## 3. Policy table (from 1b)

| # | Case | Treatment | Variance effect |
|---|---|---|---|
| A | Tx effective **after** `T_c`, written **before** submit | In `net_after`: carried forward. Not part of the variance | none |
| B | Tx effective **before** `T_c`, written **before** submit | In `book(T_c)` (not in `net_after`). The variance excludes it; no double count | none |
| C | Tx effective **before** `T_c`, written **after** submit (late) | The tx posts normally (issue and COGS kept). The engine then posts a **linked count correction** (§4) | the count's variance is offset by the tx quantity |
| D | Tx effective **after** `T_c`, written **after** submit | Normal movement | none |
| E | Duplicate of an offline tx | Stopped before any ledger row by the op id / idempotency (`SyncPushService.java:388-395`, `:436-443`) and, for POS jobs, by `postedLineKeys` (`pos/application/PosInvoiceService.java:1747-1777`). The correction is unique per (count line, late ledger row) (§4.4): exactly once | none extra |
| F | Tx from a device disconnected during a freeze | Accepted on sync; never refused or dead-lettered for the freeze. Then A–D apply. Listed on the count's late-transactions list | per A–D |
| G | Tx flagged `clockSkew` | Effective at server receive time (so after `T_c`, case A or D). **No automatic correction.** Listed for manual review | none automatic |

### 3.1 Worked examples (all with the fix)

Book 10 before each example; unit cost 2.00; `T_c` = 10:00; submit at 10:30 unless stated.

**A.** Sale of 1 at 10:10, written 10:12. Counter sees 10 at 10:00, enters 10. now = 9, net_after = −1.
Variance = 10 + (−1) − 9 = **0**. Stock 9. Correct.

**B.** Offline sale of 1 at 09:55, synced and drained at 10:20 (`effectiveAt` 09:55, `createdAt` 10:20). Counter
enters 9. now = 9, net_after (E > 10:00) = 0. Variance = 9 + 0 − 9 = **0**. Stock 9. (Today: −1, X-1a.)

**C.** Offline sale of 1 at 09:55, synced at 11:00. Counter enters 9.
1. Submit 10:30: now = 10, net_after = 0, variance = **−1**. Variance row V: `adjustment_out` −1 at 2.00
   (Dr Adjustment loss 2.00 / Cr Stock 2.00). Stock 9.
2. 11:00: sale row S: `issue` −1 (Dr COGS / Cr Stock at the issue cost). Stock 8.
3. Same transaction as S: correction row K: `adjustment_in` +1 at 2.00, `reversesLedgerId = V`,
   `sourceLineId = <count line>`, `referenceType = 'inventory_count_late_correction'`, `referenceId = S.id`,
   note "Late sale INV-… of 09:55 was before count CNT-… (10:00)". Journal: Dr Stock 2.00 / Cr Adjustment loss
   2.00. Stock **9** = counted.
Net: quantity 9; the loss of 2.00 is reversed; COGS keeps the sale. Both pieces of evidence stay.

**C, receipt direction.** Offline GRN of 5 received at 09:50, synced 11:00. Counter sees 15 at 10:00, enters 15.
Submit: variance **+5** (row V, `adjustment_in` +5, Dr Stock / Cr Adjustment gain). 11:00: GRN row +5 (Dr Stock /
Cr GRNI): stock 20. Correction K: `adjustment_out` −5 at V's unit cost, `reversesLedgerId = V`: Dr Adjustment gain /
Cr Stock. Stock **15**; the gain is gone; GRNI still matches the bill.

**C, late tx larger than the variance.** Count enters 7 at 10:00 (book 10 → variance −3, row V). An offline sale of
5 at 09:55 syncs at 11:00: stock 7 − 5 = 2. Correction K = +5 (the tx quantity, not capped), `reversesLedgerId = V`.
Stock **7** = counted. The count really found 2 more than the system knew once the sale is known; K shows it: V −3 +
K +5 = net gain 2. (The returned-more-than-issued check of IPR 0b must exclude this `referenceType`; a correction
may exceed its variance row by design.)

**C, line had no variance.** Count enters 10 at 10:00 (book 10, no row V). The 09:55 sale of 1 syncs at 11:00:
stock 9. Correction K = +1 with `reversesLedgerId = null`, `sourceLineId = <count line>`, value at current cost,
journal Dr Stock / Cr Adjustment gain. Stock **10** = counted (the shelf had one more than the system believed).

**C, several counts.** Counts at 10:00 (enters 9, variance −1) and 14:00 (enters 9, variance 0), both submitted
before the 09:55 sale syncs at 16:00. Only the **first** submitted count whose `T_c` is after 09:55 is corrected:
K +1 against the 10:00 count. Stock 9. The 14:00 count measured a book already corrected by the 10:00 variance, so
correcting it too would double the fix.

**D.** Offline sale of 1 at 10:10, synced 11:00. Counter enters 10. Submit: variance 0. 11:00: sale −1. Stock 9.
No correction (E = 10:10 > `T_c`).

**E.** The till resends the 09:55 sale with the same `opId`: the server replays the stored result; no second issue
row; no second correction. If two engine paths raced to post the same correction, the unique index (§4.4) refuses
the second insert and the transaction retries and finds K present.

**F.** Location frozen 09:50–10:40 for count CNT-1 (§6). Till A is offline the whole time and sells 1 at 10:05
(after `T_c`) and 1 at 09:55 (before). It syncs at 12:00. Both sales post (freeze does not apply to sales). 10:05
→ case D. 09:55 → case C: correction K +1 against CNT-1. Both appear on CNT-1's late-transactions list.

**G.** Till clock is 2 h ahead: it stamps a sale "12:00" while the server time is 10:01. `12:00 > now + 5 min`, so
`effectiveAt` = 10:01 (receive time) and the row is flagged `clockSkew`. Against a count with `T_c` 10:00 it is case
A/D; no correction. It appears on the count's late list and in the discrepancy report for a person to judge.

## 4. The linked count correction (case C)

### 4.1 Which count

For a newly written row `r` of cell `x` (lot-narrowed when `r` has a `batchId` and the count line is a lot line):

```sql
SELECT l.id AS line_id, l."countedAt", s.id AS session_id, s."adjustmentId"
FROM "InventoryCountLine" l
JOIN "InventoryCountSession" s ON s.id = l."sessionId"
WHERE s."organizationId" = :org AND s.status = 'submitted' AND s."locationId" = :loc
  AND l."productId" = :product AND l."variantId" IS NOT DISTINCT FROM :variant
  AND l."countedQty" IS NOT NULL
  AND l."measuredSeq" IS NOT NULL AND l."measuredSeq" < :rSeq      -- written after the count was measured
  AND COALESCE(l."countedAt", s."startedAt") > :rEffectiveAt         -- but happened before the cut-off
ORDER BY s."submittedAt", s.id
LIMIT 1;
```

Lines from before V700 have `measuredSeq` null and are never corrected (no reliable cut-off evidence).

### 4.2 What is posted

| Field | Value |
|---|---|
| Ledger type | `adjustment_in` when `r.quantityChange < 0`, else `adjustment_out` |
| Quantity | `−r.quantityChange` (same size as the late tx, opposite sign) |
| Lot / variant | `r`'s lot and variant |
| Unit cost | The variance row's `unitCost` when one exists (it reverses that valuation); else the engine's current cost |
| `reversesLedgerId` | The line's count variance row V, or null when the line had no variance |
| `sourceLineId` | The count line id |
| `referenceType` / `referenceId` | `inventory_count_late_correction` / `r.id` |
| Notes | `Late <ref> of <E(r)> was before count <countCode> (<T_c>)` |
| `effectiveAt` | `T_c` of the count line |
| GL | Mirror of V's accounts: Dr Stock / Cr Adjustment loss for a late issue; Dr Adjustment gain / Cr Stock for a late receipt. An `opening` count posts its variance against the opening-balance equity account (`StockDocService.postCountVariances`, last argument), so its correction does too |
| Posting date | V's posting date while that period is open; else `PostingService.openDateOrToday` (ADR-S088 closed-period rule) |

### 4.3 Exclusions (no automatic correction; listed for review)

- Serial-tracked cells. A count marks the missing serial as lost; the late sale then tries to issue that same
  serial. Fixing it needs a person (re-receive the serial into the count variance or reverse the variance unit).
- Rows flagged `clockSkew` (case G).
- Reversal rows (`reversesLedgerId` set) and value-only rows (`cost_adjustment`): they are corrections themselves.
- Count lines posted with `force` (overwrite): the counter chose to overwrite later movements; the late row is
  listed only.
- Counts submitted before V700 (`measuredSeq` null).

### 4.4 Exactly once

- Partial unique index (V701): `("organizationId", "sourceLineId", "referenceId") WHERE "referenceType" =
  'inventory_count_late_correction'`. One correction per (count line, late ledger row).
- The correction runs inside the late row's own transaction, after the engine holds the cell's L4 lock and the
  `StockItem` row (L5). No new lock level.

### 4.5 Visibility

- `GET /inventory/counts/{id}` gains an additive `lateTransactions` array: late rows of the count's cells (corrected
  or listed), with `ledgerCode`, `effectiveAt`, `createdAt`, quantity, source, correction ledger code or the
  exclusion reason.
- Report-only integrity check `count_late_uncorrected`: late rows that match §4.1 but have no correction and no
  exclusion. Not in `INTEGRITY_VALUE_KINDS`; never blocks a close.

## 5. Submit drains pending POS jobs (D1)

Before `submit` opens its transaction, it processes the pending `StockPostingJob`s of the location whose lines
touch a counted cell, each in its own transaction (as `SyncPushService.drainStockPostings`,
`sync/application/SyncPushService.java:1584-1598`). Inside the submit transaction, after the cell locks, it
re-reads them: if any is still `failed` or held, submit returns **409** "`<n>` sale stock posting(s) at this
location are not done; resolve them in the Posting Monitor, then submit the count again". This makes case B the
normal case for online sales and keeps case C for offline ones. The drain never runs while the count session row
is locked, so the job → invoice lock order is not inverted.

## 6. Freeze

- `InventoryCountSession."freezeLocation"` boolean (V700, default false), set when the session starts.
- While a **draft** session with the flag exists, server-side moves at that location are refused by the engine with
  409 "Location `<name>` is frozen for count `<countCode>`": transfers in or out, adjustments, stock-out, waste,
  direct stock, GRN posting, production consumption and output, fleet dispatch.
- Exempt: the session's own variance posting; POS sale stock jobs and sync sale ops (a sale is never refused or
  dead-lettered); reversals of a sale (refund restock). Exempt moves are handled by §3.
- Lock: L3b `freeze:<org>:<location>`, shared for moves, exclusive while the freeze starts or ends (IPR R2 table).
- One draft session per location (V701 partial unique index, S4) so two freezes never overlap.
- Disconnected devices cannot see the freeze (case F). Optional later, with the owner's OK and an Android change:
  publish the flag in sync pull.

## 7. Clock skew

- Device `occurredAt` later than server now + `inventory.maxFutureEffectiveMinutes` (default 5): stored as server
  receive time, flagged `clockSkew` (ADR-S088 §4). For a device count, `T_c` is then the receive time.
- A device clock that runs **behind** cannot be detected from one op. Report-only heuristic: an op whose
  `occurredAt` is earlier than the same device's previous op (by `deviceSeq`) is listed in the discrepancy report.
- Evidence ordering never uses device time: running balances and FIFO keep the server `seq`.

## 8. Tests (Phase 1b)

- `CountCutoffIT`: cases A–G with the numbers in §3.1, for back-office, device and beverage counts; integrity green
  after each.
- Regression ITs for X-1a, X-1b, X-1c, X-1d: red on `origin/main`, green after.
- Concurrency (R2): count submit in parallel with a sale on a frozen and on a non-frozen location; late sync in
  parallel with a second count submit: exactly one correction.
