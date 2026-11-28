# ADR-S088 — Stock movement effective time

- Status: **Proposed (Phase 0); implementation Phase 1b** (IPR task T10). Not built. Needs owner acceptance of
  Phase 0 before any code or migration is written.
- Source: IPR plan rev 3, requirement R3; audit finding X-1 (counts compare against row write time). Owner decision
  D1 (2026-11-27): effective time on every movement, and counts compare against it.
- Extension: no NestJS counterpart. Nest has no effective time either; this is an improvement labelled as such.
  Existing routes and responses do not change. New JSON fields are additive.
- Migration: `V700` (reserved in `docs/adr/migration-registry.md`): `InventoryLedger."effectiveAt"` timestamp(3),
  **nullable, no default, no backfill**. Not written in Phase 0.
- Spec: `docs/spec/inventory-count-reconciliation.md` (how counts use the effective time).
- Related: ADR-S070 (org-local accounting day), ADR-S072 (POS stock posting job), ADR-S083 (offline hybrid, device
  `occurredAt`).

Paths below are relative to `backend/src/main/java/com/inventoryerp/`. Line numbers are for `origin/main` 0e670f12.

## Context

A stock ledger row has one timestamp today: `"createdAt"`, set by the database when the row is written.
`StockService.insertLedger` (`inventory/application/StockService.java:1746-1768`) writes
`"createdAt" = NOW`, where `NOW = date_trunc('milliseconds', clock_timestamp())` (`:1411`). Nothing on the row says
when the goods physically moved.

The two times can be far apart:

| Event | Business time | Row written |
|---|---|---|
| Online POS sale | Checkout | When the stock posting job drains (the worker runs every 30 s; a failed job retries later) |
| Offline POS sale | The till's checkout time (`occurredAt`) | When the device syncs, then the job drains: minutes to days later |
| Offline device stock-out (`stock.out`) | The device's `occurredAt` | At sync |
| GRN entered the next morning | Yesterday's delivery (`receivedAt`) | Today |

Counts compare against `"createdAt"`. `InventoryCountService.postings` (`inventory/application/InventoryCountService.java:588-662`)
and `SyncStockService.movedSince` (`sync/application/SyncStockService.java:385-393`) treat any row written after the
count as "moved after the count". A sale made before the count but synced after it is therefore counted twice
(defect X-1). The worked cases are in `docs/spec/inventory-count-reconciliation.md` §2.

The journal date (posting date) is a separate value, `StockMove.date` (`inventory/application/StockMove.java:74`).
It is already honoured by `issue` and `receiveReturn` (`StockService.java:171`, `:623`) and by receipts through
`GlReceiptContext.date` (`:270`, `:467`). It is **ignored** by `adjust` and `transfer`, which always use
`Instant.now()`:

| Line | What uses `Instant.now()` |
|---|---|
| `StockService.java:998` | `adjust`: period check |
| `:1051`, `:1054` | `adjust`: `postAdjustment` journal date, AVCO residue sweep |
| `:1137` | `adjust` (layered path): `postAdjustment` journal date |
| `:1155` | `transfer`: period check |
| `:1380` | `transfer`: `postTransfer` journal date |
| `:1388`, `:1393` | `transfer`: cost correction and AVCO residue sweep dates |

### Constraint: the ledger is append-only

The trigger `inventory_ledger_append_only` (`V1__baseline.sql:17176`) runs `guard_inventory_ledger()`
(`V1__baseline.sql:2204-2219`) before every UPDATE or DELETE. It refuses any change except an FK clearing
`batchId` / `serialId`, or an owner-only evidence purge. A backfill
`UPDATE "InventoryLedger" SET "effectiveAt" = ...` would raise on the first row. Historical rows keep a null
effective time for ever.

## Decision

### 1. Three times, three meanings

| Field | Meaning | Set by | Changes after write |
|---|---|---|---|
| `effectiveAt` (new, ledger) | When the goods physically moved: business time | The engine, from `StockMove.effectiveAt` (new); the caller passes the source below | Never |
| `createdAt` (existing, ledger) | When the ledger row was written: server time | Database `NOW` (unchanged) | Never |
| posting date (existing, journal `date`) | Accounting date of the journal | `StockMove.date` → `PostingService`; moved to an open date by `PostingService.openDateOrToday` (`accounting/application/PostingService.java:316-318`) when the effective date is closed | Never |

**Readers** use `COALESCE("effectiveAt", "createdAt")` everywhere a business time is needed. For historical rows
(null) this is exactly today's behaviour. **Ordering** of evidence (running balance, `qtyBefore` /
`balanceAfter`, FIFO layer order) stays on the server sequence (`seq`, V654) and is never re-sorted by the device
clock.

### 2. Source of `effectiveAt` per event

Every source below was read in code. "now" = server time at the engine call.

| Event | Source today (what the code passes as `StockMove.date`, or nothing) | `effectiveAt` from 1b | Evidence |
|---|---|---|---|
| POS sale (online and offline), combo, recipe, extras | `Invoice.issueDate`, else `Order.createdAt`, clamped by `openDateOrToday` | The **unclamped** sale time (`Invoice.issueDate`, else `Order.createdAt`). The clamped value stays the posting date | `pos/application/PosInvoiceService.java:1892-1911` (`datedForStock`), `:1923-1928` (`dated`) |
| — where `Invoice.issueDate` comes from | Checkout `occurredAt` (device or web), else now | same | `PosInvoiceService.java:480`, `:523` |
| Sync op (any device op) | `occurredAt` is validated (`OccurredAt.resolve`) but **not** passed to stock moves, except as below | Device `occurredAt` (after the limits in §3) | `sync/application/SyncPushService.java:778`, `kernel/common/OccurredAt.java:21-46` |
| `sale.checkout`, `tab.settle` | `occurredAt` → checkout → `Invoice.issueDate` | as POS sale | `SyncPushService.java:831-845` |
| `stock.in` / `stock.out` | **Nothing**: `DirectStockService.directIn/Out` get no date, so the engine uses now | Device `occurredAt` | `SyncPushService.java:1091-1112` |
| `stock.count` | `occurredAt` only feeds `movedSince`; line `countedAt` is server NOW at sync | Count cut-off = `occurredAt` (count spec §1) | `SyncPushService.java:1116`, `:1562-1568`; `InventoryCountService.java:527` |
| `grn.receive` (offline) | `receivedAt = occurredAt` | `occurredAt` | `sync/application/SyncStockService.java:343` |
| `transfer.dispatch` / `transfer.receive` / `stock.adjust` (offline) | Document stamped `occurredAt` (`stamp`); the moves use now | Device `occurredAt` | `SyncStockService.java:99`, `:164`, `:300-325`, `:533-536` |
| GRN post (back office) | `GoodsReceiptNote.receivedAt`, else now | `receivedAt` | `procurement/application/GoodsReceiptsService.java:537-538`, `:580` |
| Fleet trip dispatch | Dispatch time (`dispatchAt(id, odometer, Instant.now())` from the route; seeds pass a time) | Dispatch time | `fleet/application/FleetDispatchService.java:1074`, `:1082`, `:1136` |
| Invoice auto-issue | `Document.issueDate` | `issueDate` | `invoicing/application/InvoicingWorkflowsInitializer.java:189` |
| Cash sale confirm | Sale post date | Sale date | `sales/application/CashSaleService.java:932` |
| Credit-note cancel restock | `posting.reversalDate(issueDate)` | Cancel time (now): the goods leave when the cancel is done | `invoicing/application/CreditNoteService.java:832` |
| Adjustment, transfer, stock-out, waste, direct stock (back office) | now (see the table above for adjust/transfer) | Document date when the document has one, else now | `StockService.java:998`, `:1155` |
| Count variance | now (through `adjust`) | The count cut-off `T_c` of the line (count spec) | `InventoryCountService.java:750-752` |
| Reversals (`reverseLedgerSource`, `returnFromSource`) | now | now: a reversal is a new physical or corrective event | call-site sweep, RLS/RFS |
| Anything else | now | now | – |

A caller that passes nothing gets `effectiveAt = now`, so a missed call site is safe: it behaves as today.

### 3. Three separate limits

The limits are independent. One never stands in for another.

| Limit | Setting | Default | Applies to | Today |
|---|---|---|---|---|
| Max future effective time | `inventory.maxFutureEffectiveMinutes` (new) | 5 | Any `effectiveAt`, device `occurredAt`, document date | Hard-coded 5 min in `OccurredAt.FUTURE_SKEW` (`kernel/common/OccurredAt.java:16`), and it **refuses** the op (400) |
| Max historical backdating | `inventory.maxBackdateDays` (new) | 7 | Back-office documents only: adjustment, transfer, GRN, direct stock | GRN `receivedAt` has no limit except the period lock; sync ops are capped by the hard-coded `OccurredAt.MAX_AGE` 7 days (`:15`), which also **refuses** (400) |
| Accounting posting restrictions | Existing `FiscalPeriodControl` (`accounting/application/FiscalPeriodControl.java:81-134`): books lock date, closed/locked period, `accounting.futurePostingLimitDays` (`:28`, `:39-49`, `:96-103`; registry default 3650, `kernel/settings/SettingRegistry.java:213-216`) | existing | The journal's **posting date**, never the effective time | unchanged |

The stock-side period check `StockService.assertStockPeriodOpen` (`StockService.java:116-136`) keeps checking the
**posting date**, not the effective time.

### 4. Rules

**Future time.** An `effectiveAt` later than server now + `maxFutureEffectiveMinutes`:
- is stored as the server receive time;
- is flagged `clockSkew` on the ledger note, and an `InventoryException` of kind `offline_clock_skew` (report-only)
  lists it in the discrepancy report (`SyncStockService.discrepancies`, `:402`);
- is **never refused for a sale**. This changes today's behaviour: `OccurredAt.check` (`OccurredAt.java:36-46`)
  throws 400 and `SyncPushService.runOp` (`:778`) sends the op to the dead-letter queue (`:481`). In 1b the sale path
  clamps and flags instead. For non-sale ops the owner decides in the sign-off table whether to clamp or keep the 400.

**Backdated back-office document.** Effective date older than now − `maxBackdateDays` → 400
"`<Document>` date is more than N days in the past". Within the limit but in a closed or locked period → refused by
the existing `assertStockPeriodOpen` (unchanged message).

**Offline ops.** Exempt from `maxBackdateDays`: the device may have been offline for longer. An older op is accepted,
keeps its true `effectiveAt`, and is flagged `lateSync` (report-only exception). This changes today's
`OccurredAt.MAX_AGE` refusal for sync ops; it needs the owner's sign-off because it widens what a device may submit.

**Closed period.** The goods moved when they moved; the books stay closed:
- `effectiveAt` keeps the true time;
- the posting date moves to the first open date (`PostingService.openDateOrToday`), as the POS drain already does
  (`PosInvoiceService.java:1905`);
- the ledger note records `occurred <effectiveAt>, posted <posting date>`. The POS drain already writes
  "Stock and COGS dated `<date>`: the sale date `<date>` is in a closed or locked period"
  (`PosInvoiceService.java:1906-1908`) — 1b makes every path do the same;
- the journal line memo carries the `ledgerCode`. Today only issue journals carry it, inside the posting key
  `inventory:issue:<org>:<ledgerCode>` (`StockService.java:868`); adjustments and transfers carry only
  "Stock adjustment · `<product>`" (`:1052`);
- a new report-only check, **period-shifted movements**, lists ledger rows whose effective period differs from the
  posting period of their journal. It joins the IPR 0b integrity checks and never blocks a close.

**Journal date consistency.** `adjust` and `transfer` use `StockMove.date` (defaulting to now) for the period check
and every journal they post (the eight lines in the Context table). Their callers that already pass a date then get
it honoured. This is an engine change (shared core, R8).

**Ordering.** Running balances, FIFO layers and evidence ordering keep the server sequence. Effective time is used
only to decide *which period* and *which side of a count cut-off* a movement falls on.

### 5. Migration V700 (Phase 1b, not now)

```sql
ALTER TABLE "InventoryLedger" ADD COLUMN "effectiveAt" timestamp(3);   -- nullable, no default, no backfill
CREATE INDEX CONCURRENTLY ... ON "InventoryLedger" ("organizationId", "locationId", "productId",
    COALESCE("effectiveAt", "createdAt"));                               -- count and period-shift reads
-- plus InventoryCountSession."freezeLocation" (count spec §6) and InventoryCountLine."measuredSeq" (count spec §1)
```

- `ADD COLUMN` without a default does not touch existing rows, so the append-only trigger does not fire.
- The column type matches `"createdAt"` (`timestamp(3)`, UTC wall clock) so `COALESCE` compares like with like.
- A test asserts the trigger `inventory_ledger_append_only` is still enabled after V700 (R1, `pg_trigger`).
- The previous application image runs on the new schema (it never names the column), so the rollback path in R7
  holds.
- The entity `InventoryLedger` maps the column read-only; `insertLedger` writes it.

## Consequences

- Counts can tell "before the count" from "written after the count" (count spec). X-1 is fixed for new rows.
- Historical rows (null `effectiveAt`) keep today's behaviour. Counts across the V700 boundary are no worse than
  today.
- Adjust and transfer journals move to the document date. A user who enters yesterday's adjustment now posts it
  yesterday (if open), not today. Visible in the period reports; part of the 1b release note.
- Two refusals become flags (future sale time, old offline op). Both need the owner's sign-off.
- Shared-core change: `StockMove`, `StockService.insertLedger`, `adjust`, `transfer`. Only the engine owner builds
  it (`docs/ARCHITECTURE.md` §11).

## Tests (Phase 1b)

- `StockEffectiveTimeIT`: each source in §2 writes the expected `effectiveAt`; a call site that passes nothing writes
  now.
- Adjust and transfer with a past open date post the journal on that date; with a closed date they are refused.
- Offline sale with `occurredAt` in a closed period: ledger `effectiveAt` = sale time, journal date = first open day,
  note and memo present, row listed by the period-shifted report.
- Future `occurredAt` beyond the limit: clamped, flagged, sale accepted (no dead letter).
- V700 trigger test and the old-image-on-new-schema check (R7).
