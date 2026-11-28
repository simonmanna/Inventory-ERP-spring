# Inventory production readiness — audit 2026-11-27 and remediation record (IPR)

| | |
|---|---|
| Audited code | `main` 0e670f12, the live build (CI green). |
| Method | Read-only static review by six reviewers (engine, stock documents and warehouse, GL, procurement and sales, POS/sync/manufacturing/rental/repair/QC, security/API/web/ops), plus a separate call-site sweep. Key findings were re-traced in code by the integrator; they are marked ✔. |
| Verdict | **NOT production-ready.** There is no P0. There are 18 P1s, most of them where inventory meets other modules. The owner's reviewer agreed (NO-GO). |
| Programme | Inventory Production Readiness (IPR), plan rev 3. Phase 0: PR #83. Phases 1–5: built on `fix/ipr-p15` (owner request 2026-11-27, "build all phases from phase 1 to phase 5 in one go"), see **Phases 1–5 status** below. Merge and production deployment wait for the owner. |

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
✔ = re-traced by the integrator.

¹ **fixed** = closure steps 1–6 are recorded in the stream's evidence file (`docs/audits/ipr-evidence/`, mapping
below) and an independent agent review has run (step 7, first half). The finding becomes **closed** when the owner's
reviewer signs off (step 7, second half).

### P1: stock quantity

| id | Finding | Evidence | Ph | St |
|---|---|---|---|---|
| F1 ✔ | When a delivery order is cancelled or short-closed after its invoice posted, the invoice keeps `stockIssuedBy`. Its posting already skipped the issue, so the goods are never issued. | `FleetDeliveryOrderService.java:440-443, 595-600`; `InvoicingWorkflowsInitializer.java:173-176` | 1a | fixed ¹ |
| F2 | A sales return on an `ordered`-policy line that was invoiced but never delivered restocks goods that never left. | `SalesReturnService.java:176-182, 241-253` | 1a | fixed ¹ |
| F3 ✔ | Fleet POD and load returns are restocked without `reverses`. A later credit note or invoice cancel restocks the same units again. | `FleetDispatchService.java:1667-1685` | 1a | fixed ¹ |
| X-1 ✔ | Counts carry forward by ledger `createdAt`, not by business time. A POS job that posts after the count, or an offline sale, is deducted twice. | `SyncStockService.java:385-393`; `InventoryCountService.java:601-652`; `BeverageCountService.java:477-480` | 1b | fixed ¹ |
| S4 | Overlapping count sessions on one location double-post the variance. The duplicate-draft catch can never fire because there is no unique index. | `InventoryCountService.java:276, 298-302, 640-662` | 1b | fixed ¹ |
| C-1 ✔ | A serial issue never decrements its lot (serial+lot products), and transfers move lots separately from serials. | `StockService.java:721-787, 1220-1360` | 1c | fixed ¹ |
| C-2 ✔ | The serial list is not checked against the quantity. Qty 1 with 2 serials issues 2 units. | `StockService.java:726-768`; `FleetDispatchService.java:1602` | 1c | fixed ¹ |

### P1: accounting

| id | Finding | Evidence | Ph | St |
|---|---|---|---|---|
| S1 ✔ | The count type defaults to `opening`, which posts losses to retained earnings. Opening counts are not limited to locations with no history. | `InventoryCountPage.tsx:85`; `InventoryCountService.java:249, 315, 752`; `StockPostingService.java:550-556` | 1b | fixed ¹ |
| GL-1 ✔ | The GRNI reconciliation sums transaction-currency `debit`/`credit` instead of the base amounts. | `GrniReconciliationService.java:153-164` | 2 | fixed ¹ |
| GL-2 | STANDARD by-products and QC downgrades post the GL at the allocated or new value, while the ledger uses the standard. | `ProductionService.java:590-609, 1190`; `QualityDecisionService.java:223, 274-288` | 2 | fixed ¹ |
| C-3 / GL-6 | A blank receipt cost enters AVCO stock at 0, while the valuation falls back to cost price, so valuation ≠ ledger ≠ GL. | `DirectStockService.java:104`; `StockService.java:303`; `InventoryValuationService.java:209` | 2 | fixed ¹ |
| GL-3 ✔ | The `pos:override` permission can resolve an integrity value finding with a note, and period close does not re-check. | `PosStockPostingService.java:204-229`; `PeriodCloseService.java:190-199` | 2 | fixed ¹ |
| GL-4 | If a posting rule is missing, production consumption and return-to-supplier silently fall back to COGS. | `StockPostingService.java:780-804` | 2 | fixed ¹ |
| GL-11 | There is no WIP tie-out per manufacturing order. Promoted to P1 by R4. | absence | 2 | fixed ¹ |

### P1: controls and security

| id | Finding | Evidence | Ph | St |
|---|---|---|---|---|
| S2 ✔ | The SoD pairs (stock adjustment, write-off, valuation) are off by default; nothing seeds them. | `SegregationOfDutiesService.java:63-97, 115` | 3 | fixed ¹ |
| S3 | A transit short or damaged receipt writes off any quantity without approval, SoD or a reason. | `StockDocService.java:988-1068` | 3 | fixed ¹ |
| S-1 ✔ | Branch-restricted users with two or more locations get 400 on stock levels, item detail and reports, and the web shows a blank list. | `LocationScope.java:61-79`; `InventoryQueryService.java:138, 261` | 3 | fixed ¹ |
| S-2 | Cost fields reach users without `inventory_valuation:read` (items, item detail, stock levels, register, waste/stock-out detail). | `InventoryQueryService.java:102, 253-343`; `InventoryRegisterService.java:235`; `StockDocService.java:405` | 3 | fixed ¹ |
| S-3 ✔ | Integrity and tie-out notifications have no recipient (`userId` null). There is no metric and no alert rule. | `InventoryIntegrityMonitor.java:81`; `NotificationInboxService.java:37` | 0 | fixed ¹ (Phase 0, PR #83) |
| X-2 ✔ | A quality-module hold only sets the status. Held stock can still be sold or consumed. | `QualityDecisionService.java:72-81` | 3 | fixed ¹ |
| S5 | A replenishment alert gets stuck when its transfer or request is cancelled, and the rule goes silent. | `ReplenishmentService.java:391-396, 519-538` | 3 | fixed ¹ |
| S-5 | There is no opening-stock import and no inventory go-live checklist. | absence | 4 | fixed ¹ |

### Gaps found by the call-site sweep (P1 under the G1 "no unlinked restock" gate)

Ids `SW-n` here; the full matrix register `M-1..M-35` lives in `docs/spec/inventory-transaction-matrix.md` (Gaps) and the lock-order register `LK-1..LK-19` in `docs/audits/2026-11-27-ipr-lock-hierarchy-audit.md`.

| id | Finding | Evidence | Ph | St |
|---|---|---|---|---|
| SW-1 | POS refund restock has no `reverses`. Refund plus order cancel or void may restock twice (to verify). It also restocks combo and recipe components without lot or serial (X-5). | `PosInvoiceService.java:1393, 1437` | 1d | fixed ¹ |
| SW-2 | Vendor-bill cancel issues from current layers with no pin and no on-hand check (F6). | `InvoicingWorkflowsInitializer.java:318-325` | 1d | fixed ¹ |
| SW-3 | Fleet work-order part return, quarry explosive return and project cost reversal restock without `reverses`. | `FleetWorkOrderCostService.java:175`; `QuarryExplosiveService.java:557`; `ProjectCostService.java:449` | 1d | fixed ¹ |
| SW-4 | Production reverse only prefers the produced lots; it does not link them. | `ProductionService.java:936-944` | 1d | fixed ¹ |
| SW-5 | `sourceLineId` is missing on GRN, PO receive, bill auto-receive (dropped by `receiveFromBill`) and production. | `StockService.java:158-162` | 1d | fixed ¹ |
| SW-7 | Offline ops with `occurredAt` more than 7 days old or more than 5 minutes ahead are refused with 400 and dead-lettered: an old offline sale never posts until resolved by hand. ADR-S088 proposes flag-not-refuse (owner sign-off). | `OccurredAt.java:15-16, 42`; `SyncPushService.java:778` | 1b | fixed ¹ |
| SW-6 | `adjust` and `transfer` date their journal at now and ignore `StockMove.date`. | `StockService.java:998, 1051, 1137, 1155, 1380` | 1b | fixed ¹ |

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

## Phases 1–5 status (2026-11-28)

Built on `fix/ipr-p15`, stacked on `fix/ipr-p0` (PR #83). Each stream recorded closure steps 1–6 per finding in its
evidence file: root cause, fix commit, regression IT red → green, workflow IT, integrity and GL tie-out asserted, and
lock levels. The integrator merged each stream and ran four independent agent reviews (security, sales/fleet,
accounting, engine core). Every review finding was fixed by a follow-up stream with its own red → green tests.

| Stream | Scope | Evidence |
|---|---|---|
| A (1b) | V700/V701 effective time; count cut-off, drain, freeze, late corrections; S4, S1, S7; SW-6; SW-7 accept-and-flag (P15-1); M-30 | `ipr-evidence/1b-effective-time-counts.md` |
| B (1a) | F1 (D4, issue-undelivered), F2, F3, LK-1 (P1, P15-3), F5 | `ipr-evidence/1a-delivery-returns.md` |
| E2 (1c/1d) | C-1, C-2, C-7, C-9, X-3, C-8, SW-1/X-5, SW-3, SW-5, F6 helper, GL-7 | `ipr-evidence/1cd-serial-restock.md` |
| C (2) | GL-1/GL-5, GL-2, GL-3/GL-10, GL-4, GL-8, GL-11/R4 WIP tie-out, WIP-3 (P1), WIP-6, X-9, X-2 (D2), P15-5 investigation | `ipr-evidence/2-accounting-mfg.md` |
| D (3) | S-1, S-2, S2 (P15-6), S3, S-4, S9, C-5/S-7/S14, S5, S-8, OPS-1, OPS-2 | `ipr-evidence/3-security-controls.md` |
| F (4) | M-7 (P1, P15-3, Nest deviation), M-23, S-6 (ADR-S093), integrity web page, go-live checklist (OPERATIONS §8c), R6 promoted P2s | `ipr-evidence/4-golive-p2.md` |
| G (4) | S-5 opening-stock import per R5 (V715, ADR-S090), web upload, preview, approval, reconciliation | `ipr-evidence/4-opening-stock.md` |
| H | Residuals: F11, D-1, D-2, D-3, GL-11 refinement, WIP-7, WIP-8, LK-3 race | `ipr-evidence/12-residuals.md` |
| E3 (wave 3) | C-3/GL-6 (D3), E-1 AVCO drift, S13, material-ledger GL, X-8, F6/SW-2 bill cancel, SW-4, `sourceLineId`, T19 `InventoryConcurrencyIT`, N-1, PLM rework release | `ipr-evidence/w3-engine-closeout.md` |
| R1 | Security and sales/fleet review fixes (V722) | `ipr-evidence/review1-fixes.md` |
| R2 | Accounting review fixes: close checks as of period end, WIP tie-out, stage-reversal late cost, S-2 analytics | `ipr-evidence/review2-fixes.md` |
| R3 | Engine-core review fixes (serial+lot picking, take-back serials, STANDARD blank cost, savepoint, freeze exemption) | `ipr-evidence/review3-engine.md` |
| Integrator | Period close with an abnormal-balance P&L account (`CloseProcessIT.abnormalBalanceAccountCloses`, red → green); web zero-cost reason input | commits on `fix/ipr-p15` |

**Partly done (recorded, not hidden):**
- X-1d beverage carry-forward and M-30 tank dip: fixed, but covered only by existing tests (no red IT).
- X-9 partial output: single-stage, non-QC, non-STANDARD output only.
- S-8 CSV negatives: fixed, could not be shown red.
- Partial-output overhead to rated accounts (E3 review item 2): fixed without a red IT.
- Back-date limit applies to partial output; Complete keeps the Nest behaviour.
- Goods in transit (opt-in `inventory.transitGlSeparation`, default off): the residue sweep and the transit reversal
  difference still post to the stock account; no reclassification when the setting is switched on.
- Optional `lockFreezeExclusive` variant: not built.

**Owner sign-off items** (nothing below is in force on live until the owner merges and deploys):

| # | Item |
|---|---|
| 1 | P15-1: late (> 7 days) and ahead (> 5 min) offline ops accepted and flagged, never refused; ADR-S088 Accepted |
| 2 | P15-2: a count freeze does not block sales or POS; serial cells go to manual review |
| 3 | P15-3: M-7, WIP-3 and LK-1 as P1; M-7 deviates from Nest on purpose |
| 4 | P15-4: `feature_risk` stays strict |
| 5 | P15-5: GQDEMO 600 variance: root cause is the seed's opening ledger row with no journal (D-1). Repair = an owner-approved compensating journal (Dr 1400 / Cr retained earnings, 600); not made |
| 6 | P15-6: future limit 5 min, back-office backdating 7 days; SoD pairs active for new orgs only. Existing orgs and one-person orgs unchanged |
| 7 | S-6 system mode stays unrestricted (ADR-S093) |
| 8 | S3 transit-loss approval threshold default 0 (every loss needs approval) |
| 9 | M-7: inspection of a rental line with nothing returned |
| 10 | ADR-S090 opening import: equity = retained earnings; poster approves unless a workflow; zero cost refused; STANDARD at standard; dated now; no reverse-import |
| 11 | E-1a reservation-honouring defaults; PLM rework release from quarantine (`POST /plm/changes/:id/rework-release`) |
| 12 | A device count that collides with an open back-office count is held and flagged |
| 13 | `sales_return_unmatched` and late-sync flags: report-only, do not block close |
| 14 | Close checks run as of the period end; `valuation_vs_subledger` skipped for a past period |
| 15 | Non-asset rule accounts are not WIP; analytics money values hidden without `inventory_valuation:read` |
| 16 | D3: an explicit 0 receipt cost needs `zeroCostReason` and `inventory_valuation:manage` |
| 17 | New mapping keys `stock_waste_expense`, `stock_expiry_expense`; setting `inventory.transitGlSeparation` (off) |
| 18 | Promote `wip_tieout` from report-only to a close-blocking value check |
| 19 | Every Deviations entry added to `docs/spec/*.md` by the streams |

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
