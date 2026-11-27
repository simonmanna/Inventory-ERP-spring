# Inventory production readiness — audit 2026-11-27 and remediation record (IPR)

| | |
|---|---|
| Audited code | `main` 0e670f12, the live build (CI green). |
| Method | Read-only static review by six reviewers (engine, stock documents and warehouse, GL, procurement and sales, POS/sync/manufacturing/rental/repair/QC, security/API/web/ops), plus a separate call-site sweep. Key findings were re-traced in code by the integrator; they are marked ✔. |
| Verdict | **NOT production-ready.** There is no P0. There are 18 P1s, most of them where inventory meets other modules. The owner's reviewer agreed (NO-GO). |
| Programme | Inventory Production Readiness (IPR), plan rev 3. **Phase 0 only** is approved. Phase 1 needs the owner's approval of the Phase 0 results. Production deployment is NO-GO. |

## Closure rule

A finding is closed only when all of these are recorded in its row:
1. root cause;
2. fix commit;
3. a regression IT that fails on `origin/main`;
4. a workflow IT;
5. the integrity report green after the scenario;
6. the GL tie-out green;
7. independent review: a fresh reviewer given only the diff, plus the owner's reviewer.

A code change alone never closes a finding.

## Owner decisions (2026-11-27)

| id | Question | Decision |
|---|---|---|
| D1 | Count cut-off | Every movement gets an effective time, and counts compare against it. A session can also be frozen. Submit first drains pending POS jobs for the counted items. |
| D2 | Quality-module hold | Moves the stock to QC quarantine, using the existing transfer. |
| D3 | Receipt cost left blank | Blank means current cost (location average, then last receipt, then cost price). An explicit 0 needs a reason plus `inventory_valuation:manage`. |
| D4 | Delivery order cancelled after its invoice posted | The invoice goes back to "awaiting delivery". A new "issue undelivered" action covers customer collection. A check flags undelivered posted invoices. |

## Corrections to the audit (verified in code)

- **S-4 is narrower than reported.** Direct stock-in and stock-out need a named approver plus PIN by default
  (`SettingRegistry.java:51-56`, default true). The real gaps:
  - a holder of the approver permission may approve their own move;
  - a null `approvedById` matches any active user (`StaffAttribution.java:89`).
- **S-5 was wrong on one point.** `inventory.glTieOut.enabled` defaults to **true** (`SettingRegistry.java:137`), not
  false.

## Findings register

Abbreviations: Sev = severity (P1 or P2); Ph = IPR phase (1–4; BL = P2 backlog unless promoted by R6); St = status.
✔ = re-traced by the integrator. Every status is **open**.

### P1: stock quantity

| id | Finding | Evidence | Ph | St |
|---|---|---|---|---|
| F1 ✔ | When a delivery order is cancelled or short-closed after its invoice posted, the invoice keeps `stockIssuedBy`. Its posting already skipped the issue, so the goods are never issued. | `FleetDeliveryOrderService.java:440-443, 595-600`; `InvoicingWorkflowsInitializer.java:173-176` | 1a | open |
| F2 | A sales return on an `ordered`-policy line that was invoiced but never delivered restocks goods that never left. | `SalesReturnService.java:176-182, 241-253` | 1a | open |
| F3 ✔ | Fleet POD and load returns are restocked without `reverses`. A later credit note or invoice cancel restocks the same units again. | `FleetDispatchService.java:1667-1685` | 1a | open |
| X-1 ✔ | Counts carry forward by ledger `createdAt`, not by business time. A POS job that posts after the count, or an offline sale, is deducted twice. | `SyncStockService.java:385-393`; `InventoryCountService.java:601-652`; `BeverageCountService.java:477-480` | 1b | open |
| S4 | Overlapping count sessions on one location double-post the variance. The duplicate-draft catch can never fire because there is no unique index. | `InventoryCountService.java:276, 298-302, 640-662` | 1b | open |
| C-1 ✔ | A serial issue never decrements its lot (serial+lot products), and transfers move lots separately from serials. | `StockService.java:721-787, 1220-1360` | 1c | open |
| C-2 ✔ | The serial list is not checked against the quantity. Qty 1 with 2 serials issues 2 units. | `StockService.java:726-768`; `FleetDispatchService.java:1602` | 1c | open |

### P1: accounting

| id | Finding | Evidence | Ph | St |
|---|---|---|---|---|
| S1 ✔ | The count type defaults to `opening`, which posts losses to retained earnings. Opening counts are not limited to locations with no history. | `InventoryCountPage.tsx:85`; `InventoryCountService.java:249, 315, 752`; `StockPostingService.java:550-556` | 1b | open |
| GL-1 ✔ | The GRNI reconciliation sums transaction-currency `debit`/`credit` instead of the base amounts. | `GrniReconciliationService.java:153-164` | 2 | open |
| GL-2 | STANDARD by-products and QC downgrades post the GL at the allocated or new value, while the ledger uses the standard. | `ProductionService.java:590-609, 1190`; `QualityDecisionService.java:223, 274-288` | 2 | open |
| C-3 / GL-6 | A blank receipt cost enters AVCO stock at 0, while the valuation falls back to cost price, so valuation ≠ ledger ≠ GL. | `DirectStockService.java:104`; `StockService.java:303`; `InventoryValuationService.java:209` | 2 | open |
| GL-3 ✔ | The `pos:override` permission can resolve an integrity value finding with a note, and period close does not re-check. | `PosStockPostingService.java:204-229`; `PeriodCloseService.java:190-199` | 2 | open |
| GL-4 | If a posting rule is missing, production consumption and return-to-supplier silently fall back to COGS. | `StockPostingService.java:780-804` | 2 | open |
| GL-11 | There is no WIP tie-out per manufacturing order. Promoted to P1 by R4. | absence | 2 | open |

### P1: controls and security

| id | Finding | Evidence | Ph | St |
|---|---|---|---|---|
| S2 ✔ | The SoD pairs (stock adjustment, write-off, valuation) are off by default; nothing seeds them. | `SegregationOfDutiesService.java:63-97, 115` | 3 | open |
| S3 | A transit short or damaged receipt writes off any quantity without approval, SoD or a reason. | `StockDocService.java:988-1068` | 3 | open |
| S-1 ✔ | Branch-restricted users with two or more locations get 400 on stock levels, item detail and reports, and the web shows a blank list. | `LocationScope.java:61-79`; `InventoryQueryService.java:138, 261` | 3 | open |
| S-2 | Cost fields reach users without `inventory_valuation:read` (items, item detail, stock levels, register, waste/stock-out detail). | `InventoryQueryService.java:102, 253-343`; `InventoryRegisterService.java:235`; `StockDocService.java:405` | 3 | open |
| S-3 ✔ | Integrity and tie-out notifications have no recipient (`userId` null). There is no metric and no alert rule. | `InventoryIntegrityMonitor.java:81`; `NotificationInboxService.java:37` | 0 | open |
| X-2 ✔ | A quality-module hold only sets the status. Held stock can still be sold or consumed. | `QualityDecisionService.java:72-81` | 3 | open |
| S5 | A replenishment alert gets stuck when its transfer or request is cancelled, and the rule goes silent. | `ReplenishmentService.java:391-396, 519-538` | 3 | open |
| S-5 | There is no opening-stock import and no inventory go-live checklist. | absence | 4 | open |

### Gaps found by the call-site sweep (P1 under the G1 "no unlinked restock" gate)

Ids `SW-n` here; the full matrix register `M-1..M-35` lives in `docs/spec/inventory-transaction-matrix.md` (Gaps) and the lock-order register `LK-1..LK-19` in `docs/audits/2026-11-27-ipr-lock-hierarchy-audit.md`.

| id | Finding | Evidence | Ph | St |
|---|---|---|---|---|
| SW-1 | POS refund restock has no `reverses`. Refund plus order cancel or void may restock twice (to verify). It also restocks combo and recipe components without lot or serial (X-5). | `PosInvoiceService.java:1393, 1437` | 1d | open |
| SW-2 | Vendor-bill cancel issues from current layers with no pin and no on-hand check (F6). | `InvoicingWorkflowsInitializer.java:318-325` | 1d | open |
| SW-3 | Fleet work-order part return, quarry explosive return and project cost reversal restock without `reverses`. | `FleetWorkOrderCostService.java:175`; `QuarryExplosiveService.java:557`; `ProjectCostService.java:449` | 1d | open |
| SW-4 | Production reverse only prefers the produced lots; it does not link them. | `ProductionService.java:936-944` | 1d | open |
| SW-5 | `sourceLineId` is missing on GRN, PO receive, bill auto-receive (dropped by `receiveFromBill`) and production. | `StockService.java:158-162` | 1d | open |
| SW-7 | Offline ops with `occurredAt` more than 7 days old or more than 5 minutes ahead are refused with 400 and dead-lettered: an old offline sale never posts until resolved by hand. ADR-S088 proposes flag-not-refuse (owner sign-off). | `OccurredAt.java:15-16, 42`; `SyncPushService.java:778` | 1b | open |
| SW-6 | `adjust` and `transfer` date their journal at now and ignore `StockMove.date`. | `StockService.java:998, 1051, 1137, 1155, 1380` | 1b | open |

### P2 backlog

These are promoted to P1 for any organisation that uses the feature (R6).

- **POS, sync, rental, repair, manufacturing:**
  - X-3: quarantine and rental locations have no engine guard.
  - X-4: an offline stock-out is dead-lettered when negative stock is off.
  - X-5: covered by SW-1.
  - X-6: an online refund within about 30 s of the sale fails.
  - X-7: the rental serial is only preferred.
  - X-8: a timing change can leave a sale unissued.
  - X-9: simple production has no partial output.
  - X-10: kitchen waste has no outbox.
  - X-11: repair "reserve" holds no stock.
- **Sales and procurement:**
  - F4: a multi-variant return cannot be credited.
  - F5: sales-order reservations are enforced only at dispatch.
  - F6: covered by SW-2.
  - F7: a return to vendor defaults to the first warehouse.
  - F8: there is no serial capture on PO/GRN.
  - F9: the invoice issues from the oldest warehouse.
  - F10: return locations are not scope-checked.
  - F11: the bill receipt cost goes through a `double`.
- **Engine:**
  - C-4: a count delta ignores strict negative stock (non-lot products).
  - C-5 / S-7 / S14: manual reservations accept any source type.
  - C-6: a revaluation reverse flattens FIFO lots.
  - C-7: `transfer()` ignores `serialNumbers` (scheduled in 1c).
  - C-8: tracking or costing changes are not locked against moves.
  - C-9: serials are auto-picked oldest-first.
- **Stock documents and warehouse:**
  - S6: an adjustment line naming a lot uses the whole-cell quantity as its basis.
  - S7: count drafts are not locked (scheduled in 1b).
  - S8: a stock-out cannot be cancelled.
  - S9: the SoD exemption never matches.
  - S10: a count adjustment can be reversed while the session stays submitted.
  - S11: bin sub-ledger drift and orphan pick orders.
  - S12: count DTO limits.
  - S13: GL granularity.
- **Accounting:**
  - GL-5: base-currency tie-out (scheduled with GL-1).
  - GL-7: no residue sweep on moves that skip the GL.
  - GL-8: a debit note credits org stock valuation (scheduled in Phase 2).
  - GL-9: revaluation date and quantity basis.
  - GL-10: the close gate only sees findings raised since the period started (scheduled with GL-3).
- **Ops and performance:**
  - S-6: RLS lets everything through outside a tenant transaction.
  - S-8: CSV export turns negative numbers into text.
  - S-9: stats polling.
  - S-10: report paging.

## Release gates

The gates (G1 quantity, G2 financial, G3 security and concurrency, G4 operational, G5 regression and recovery, G6 data
per organisation) are defined in the plan. Their status is filled in at each gate.

| Gate | Status | Evidence |
|---|---|---|
| G1 | not started | |
| G2 | not started | |
| G3 | not started | |
| G4 | not started | |
| G5 | not started | |
| G6 | not started | |

## Phase 0 deliverables

Phase 0 is complete (2026-11-27). Deliverables, test evidence (98/98 affected tests, InventoryIT 21/21, architecture
and modularity green), the read-only data checks on a restored live copy, the feature-usage report per organisation and
the new findings (SW-7, M-7, WIP-3, LK-1 and others) are in `docs/audits/2026-11-27-ipr-phase0-results.md`.
Nothing in Phase 0 changed stock, ledger rows, valuation, document statuses or posted journals.
**Phase 1 waits for the owner's explicit approval.**
