# IPR Phase 0 results (2026-11-27), for owner review

| | |
|---|---|
| Branch | `fix/ipr-p0`: `origin/main` 0e670f12 plus four Phase 0 streams. |
| Rules followed | No migration. No change to the shared engine core. No change to existing stock, ledger, valuation, document status or posted journals. New checks are report-only. |
| Status | **Phase 0 is complete. STOP: Phase 1 needs the owner's explicit approval.** |

## 1. Deliverables

| Task | Deliverable | File |
|---|---|---|
| T01 | Migration registry: highest V696, ADR S087. V700–V719 reserved; V640–V644 burned on archive tags. Shared-core ownership; CODEOWNERS. | `docs/adr/migration-registry.md`, `docs/ARCHITECTURE.md` §11, `.github/CODEOWNERS` |
| T02 | ADR-S088: effective time, three limits, closed-period traceability. Count reconciliation spec R3b, with worked cases A–G and X-1a..d. | `docs/adr/ADR-S088-stock-movement-effective-time.md`, `docs/spec/inventory-count-reconciliation.md` |
| T02b | WIP valuation spec, traced from the postings, with the check definition and an SQL sketch. | `docs/spec/manufacturing-wip-reconciliation.md` |
| T03 | Transaction matrix: about 150 call sites in 16 modules, bucket definitions, gaps M-1..M-35. | `docs/spec/inventory-transaction-matrix.md` |
| T04 | Test harness: `assertIntegrityGreen`, `assertGlTieOut`, the `race` latch helper (fails on 40P01), the append-only trigger test, the ledger write scan. | `InventoryAuditSupport`, `InventoryLedgerImmutabilityTest`, `InventoryIntegrityP0IT` |
| T04b | Lock hierarchy L1–L7 with evidence, findings LK-1..LK-19, retry policy LK-R1, engine requests E-1..E-7. | `docs/audits/2026-11-27-ipr-lock-hierarchy-audit.md` |
| T05 | 12 report-only integrity checks, returned in new `reportOnlyChecks` fields. They are not value kinds, and a test proves they cannot hold a close. | `InventoryIntegrityService`, `InventoryIntegrityMonitor` |
| T06 | SELECT-only data-check SQL, one labelled query per defect plus a per-org summary. | `docs/audits/2026-11-27-inventory-data-checks.sql` |
| T07 | Feature-usage map: `GET /inventory/reports/feature-usage` with 44 features and the P2 → feature map. A daily `integrity_feature_risk` finding. | `InventoryFeatureUsageService`, `docs/spec/inventory-feature-risk-map.md` |
| T08 | Alerts reach people. `NotificationsService.sendToPermission` sends to the users who hold the permission, with dedupe; it is used by four monitors. New gauges `erp.stock.posting.jobs.failed` and `erp.inventory.integrity.open{kind}`; pending jobs now exclude failed and abandoned ones. Three alert rules. | kernel notifications, `BacklogMetrics`, `infra/monitoring/alerts.yml` |
| T09 | Recovery rehearsal scripts (restore copy, read-only data checks, migration rehearsal, failed-job rehearsal) and OPERATIONS §8b. `PosStockPostingRecoveryIT` proves exactly-once job recovery. | `scripts/ipr/*`, `docs/OPERATIONS.md` |

## 2. Test evidence

- **Combined run** on the integration branch, on a freshly reset owner-split Postgres 16 (RLS enforced), using
  `fastTest` with per-class counts from an init script. **98/98 passed:**

  | Class | Tests |
  |---|---|
  | InventoryIntegrityP0IT | 16 |
  | InventoryIntegrityIT | 12 |
  | InventoryEngineHardeningIT | 13 |
  | InventoryIntegrityAlertsIT | 3 |
  | PosStockPostingRecoveryIT | 2 |
  | PeriodCloseChecklistIT | 7 |
  | LedgerIntegrityIT | 6 |
  | PosStockPostingIT | 10 |
  | NegativeStockPolicyIT | 8 |
  | InventoryLedgerImmutabilityTest | 2 |
  | PostingMonitorAbandonIT | 1 |
  | AlertsIT | 7 |
  | QualityControlAlertsIT | 6 |
  | StandardCostingIntegrityIT | 5 |

- **Separate run** on a fresh DB: InventoryIT 21/21 (authorization matrix now 146 routes). `ArchitectureTests` 5/5 and
  `ModularityTests` 2/2 ran through `test --tests`.
- **Known pre-existing test weakness:** `InventoryIT.documents` reads the ledger by stock-out code with no org filter
  (`InventoryIT.java:282`), so it fails only when other classes share its DB.
- The full build did not run. CI runs it on push.

## 3. Historical data checks: restored copy of live

- **Source:** backup `pre-procdash-deploy-20261127-102621.dump`, restored into an isolated container. The live
  database was not touched.
- **How it ran:** `scripts/ipr/run-data-checks.sh` in one read-only transaction. 26 result sets, exit 0.
- **Scope:** 2 organisations, 270 ledger rows.

| Check | DEMO | GQDEMO | Reading |
|---|---|---|---|
| F1, F2, F3, X-1, S4, S1, C-1, C-2, GL-2 | 0 | 0 | No historical damage from these defects in the live data |
| C-3 zero-cost receipts | 1 (direct stock-in, 2 units, 2026-11-23) | 0 | The C-3 path has been used |
| GL-1 GRNI in foreign currency | 1 line: USD 800 vs base 2,960,000 | 0 | **The GRNI report is wrong today for DEMO** |
| Negative quants | 2 | 8 (e.g. GQ-BLOCK-6 −12,400, GQ-CEMENT −918) | Negative stock is allowed. GQDEMO stock GL is −63.1M |
| Unlinked restocks | 0 | 2 `cash_sale_void` rows, 2026-11-10 | From before the R1 fix (2026-11-23) |
| Period-shifted moves | 0 | 95 rows moved in November, posted in October | Seeded back-dated demo data; informational |

The new code was run against the same copy on a spare port, using the integrity report:

| Org | Value checks | Report-only checks |
|---|---|---|
| DEMO | ok | negative_quants (2) |
| GQDEMO | **`valuation_vs_gl` variance 600** (sub-ledger −63,122,852.58 vs GL −63,123,452.59). This is the one open integrity finding; it already holds GQDEMO's period close. | `gl_tieout_by_account` shows the same 600 on account 1400; negative_quants (8) |

No repair was made. Each repair needs its own approved plan.

## 4. Feature usage per organisation (R6)

| Org | Features in use | P2s promoted to blockers for this org |
|---|---|---|
| DEMO (15 of 44) | stock, lots, lot+expiry, variants, AVCO, manufacturing orders, processing jobs, vendor bills, bill auto-receive, procurement receipts, RTV/claims/stage, reservations, POS orders, multi-warehouse, CSV exports | X-8, X-9, F6 (= SW-2), F11, C-8, S13, S14, GL-7, S-6, S-8 |
| GQDEMO (13 of 44) | stock, AVCO, fleet delivery orders, manufacturing, quarry extraction, crushing runs, vendor bills, procurement receipts, RTV/claims/stage, reservations, sales-order reservations, multi-warehouse, CSV exports | X-9, F5, F11, S13, S14, GL-7, S-6, S-8 |

Neither org uses serial products today. C-1 and C-2 stay general blockers, because enabling serial tracking would
expose them at once.

## 5. New findings from Phase 0 (not yet in the register as P1)

| id | Severity proposed | Finding | Source |
|---|---|---|---|
| SW-7 | P1 candidate | Offline ops more than 7 days old or more than 5 minutes ahead are refused with 400 and dead-lettered. ADR-S088 proposes flagging them instead (`OccurredAt.java:15-16, 42`). | specs stream, verified |
| M-1 | P2 | Offline stock-in/out, transfers, adjustments and kitchen voids ignore `occurredAt`; they are dated at sync. | matrix |
| M-7 | P1 candidate (Nest parity) | A clean rental return moves RENT-CLEAN → RENT-STOCK, but nothing moved the unit out of RENT-OUT, so RENT-OUT stays inflated. | matrix |
| M-23 | P2 | A material-ledger run can double its value rows: the status read is unlocked and the UPDATE has no status predicate. | matrix |
| M-27 | P2 | `POST /sales/orders/{id}/reserve` runs with no transaction, so it can over-reserve. | matrix |
| M-29 | P2 | Bill cancel vs a concurrent GRN absorb: no supplier lock, unlocked read. | matrix |
| M-30 | P2 | Tank-dip book level uses ledger `createdAt` (same class as X-1). | matrix |
| LK-1 | P2, borderline P1 | GRN/PO receive updates PO rows before the supplier lock, while bill post takes the supplier lock first: a deadlock cycle. Verified. | lock audit |
| LK-3, LK-5 | P2 | SalesOrder vs SalesOrderLine order; 19 multi-cell paths do not sort their cells. | lock audit |
| LK-19, LK-R1 | P2 | There is no `lock_timeout`, and no in-process retry. A 409 is cached against its idempotency key, so a same-key retry replays the 409. | lock audit |
| WIP-3 | P1 candidate | Late landed-cost WIP shares of a reversed or cancelled order stay in WIP permanently (`CostAdjustmentService.java:88, 93`). | WIP spec |
| WIP-6 | P2 | The existing `ProductionReportService.wipReconciliation` reports false drift. | WIP spec |
| OPS-1 | P2 | The null-user notification defect also exists in `FiscalPeriodCoverageService.java:168` and `FleetReminderService.java:175`. | ops stream |
| OPS-2 | P2 | Approval notifications by permission include inactive users (`ApprovalsNotificationsSubscriber:~80`). | ops stream |

## 6. Decisions requested from the owner before Phase 1

1. **Approve Phase 1** (stock integrity: 1a delivery/returns, 1b effective time + counts + freeze, 1c serial/lot, 1d
   restock links).
2. **ADR-S088 SW-7:** turn the offline 7-day / 5-minute refusals into accepted-and-flagged operations (recommended),
   or keep refusing them.
3. **Count spec proposals:** sales and POS jobs are exempt from a freeze; serial cells get no automatic late
   correction (manual review).
4. **Promote to P1:** M-7 (rental RENT-OUT; Nest parity, so a deliberate deviation), WIP-3, and LK-1 (fix the receipt
   lock order).
5. **Feature-risk tuning:** S13, S-6 and S-8 apply to every org that keeps stock, so `feature_risk` fires everywhere.
   Either keep them (strict) or move them to a general backlog.
6. **GQDEMO's open 600 GL variance:** investigate (read-only) as part of Phase 2, or accept it as demo data.

## 7. Environment

- Phase 0 test DBs `erp-itdb-ipr0` and `erp-itdb-ipr1`, and the live copy `erp-livecopy-ipr` (port 5702), are
  kept until owner review.
- The stream worktrees `/home/ierp-ipr-p0-*` are kept.
