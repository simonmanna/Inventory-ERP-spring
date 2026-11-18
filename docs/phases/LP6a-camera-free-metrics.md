# LP6a — Operations insights without cameras

Status: coded 2026-11-18 on `phase/LP0-exception-intelligence`, not committed. Approved provisionally in review on
2026-11-18 (deviations 1-3 approved; 4-6 awaiting review); the review's added checks are in §7. Not complete until the
owner's full build and the 80 % coverage gate pass.
Design: the "Operations intelligence (LP6)" section of the loss-prevention design document; ADR-S077 §2 (licence tier
`insights`). Spec: [loss-prevention.md, "Operations insights, camera-free (LP6a)"](../spec/loss-prevention.md).

## 1. Scope

The eight metrics of the LP6 design that need no camera, computed from the event ledger LP0 already fills:

1. Kitchen preparation time (created → ready), per station and hour
2. Order-to-serve time (created → dispatched)
3. Kitchen recall rate
4. Table turnover
5. Average sale and items per sale, per role or person, hour and branch
6. Void, refund and discount rates, per role or person, hour and branch
7. Cash session accuracy
8. Pour yield per product

Out of scope (LP6b): footfall, conversion, queue length and wait, table occupancy and wait for service. They need
`vision.*` observations and a chosen detector.

## 2. What was built

| Part | Where |
|---|---|
| Migration: `LpMetricDay` (dirty days), `LpMetricValue` (summary rows), `lp_num`, RLS, backfill of existing days | `V574__loss_prevention_insights.sql` |
| Dirty marking at ingest, the per-day computation (one SQL statement per source), recompute | `LpInsightsService` |
| Job, every 5 minutes, ShedLock `loss-prevention-insights` | `LpInsightsJob`, `app.lossprevention.insights-cron` (`LP_INSIGHTS_CRON`) |
| Read side: summary, hourly, kitchen, staff, tables, pour | `LpInsightsReportService` |
| 7 routes under `/loss-prevention/insights` | `LossPreventionInsightsController` |
| Licence feature `lpInsights` | `LicenseFeatures`, licence server `Entitlements`, `web/src/lib/features.ts` |
| Projection: `payload.tableId` on `sale.completed` | `LpProjectionService`, `payloads.schema.json` |
| Web: Insights tab on `/loss-prevention` (shown when `lpInsights` is licensed and on) | `InsightsTab.tsx`, `insights-api.ts` |

No new permission: reading needs `lp:view`, per-person staff figures `lp:investigate`, recompute `lp:configure`.

## 3. Behaviour worth knowing

- Summaries lag the ledger by 1–6 minutes (60 s settling plus the job interval). The summary route reports
  `pendingDays` and `failedDays`.
- A sale projected before this change has no `tableId`; table turnover covers sales from now on, and sales from
  other senders that send `tableId`.
- Cash accuracy uses rule C08's `amount` as the tolerance, read when the report is asked for, so changing C08 needs no
  recompute. Without an amount the screen shows sessions balanced to zero instead.
- Metrics about people describe recorded activity, and the route says so in `note`. Per person needs
  `lp:investigate`; the default is per role.

## 4. Tests run

- `LpInsightsIT` (6 tests): ingest → dirty → compute → every metric checked against hand-worked figures; hourly,
  kitchen, table and pour breakdowns; per-role vs per-person permission; late event recomputes its day; recompute
  limits; authorization matrix for the 7 routes.
- `LpInsightsKeysTest` (3 tests): which days an event makes dirty.
- Re-run, unchanged and green: `LpLedgerIT`, `LpReconIT`, `LpVisionIT`, `LpVisionOpsIT`, `LpEnginesTest` (fastTest),
  `ModularityTests`, `ArchitectureTests` (test task, filtered).
- Web: `pnpm web:typecheck`, eslint on the changed files, `pnpm web:i18n` (passes; new phrases untranslated).
- Not run: the full build, so the 80 % coverage gate on `lossprevention.application` is unverified. No live data.

## 5. Not done

- A native-speaker check of the Insights translations (ti, lg, sw): written 2026-11-18 by the assistant, not by a
  translator. The rest of the loss-prevention screens are still untranslated.
- Web component tests.
- Cash accuracy per cashier (rule C09 already covers repeated shortfalls per cashier).
- Retention of summary rows: they are derived and small; they are not deleted when ledger retention arrives.

## 6. Differences from the LP6 design (owner approval needed)

| # | Design | Built | Why |
|---|---|---|---|
| 1 (approved 2026-11-18) | Dashboards in the existing analytics module | An Insights tab on the loss-prevention page | The analytics module would have to read loss-prevention tables, which the module boundaries forbid. The figures move later if wanted |
| 2 (approved 2026-11-18) | Table turnover per service period | Per business day | The ledger has no service periods (lunch, dinner) |
| 3 (approved 2026-11-18) | Pour cost and yield | Yield, and the cost of the consumed volume | A count line carries no revenue, so pour cost as a share of revenue cannot be computed from the ledger |
| 4 | Figures per role | Per a user's first role name, alphabetically | A user can hold several roles |
| 5 | Percentiles over a period | The slowest day's 90th percentile | Day percentiles cannot be combined; averages are exact |
| 6 | Gated by `lpInsights` | Routes and screen gated; the job computes for every organization with dirty days | The licence is per installation, and computing is cheap. Days are ready when the feature is switched on |

## 7. Review checks (2026-11-18)

| Check | How it is met |
|---|---|
| Historical accuracy | Kitchen, sales, void/refund/discount, cash and pour figures are computed for any day already in the ledger (V574 marks those days). Table turnover needs the sale's `tableId`, which only sales projected after LP6a carry. The summary's `coverage` says so per area (`historical`), and the screen marks table turnover **Partial** with the share of sales that record their table |
| Recomputation | A day's rows are deleted and rewritten in one transaction (no duplicates on repeats); the ledger stores an event once per source and source event id; the version counter keeps a day dirty if it is marked while computing, so an older result is never taken as current; the job runs under ShedLock and a failed day stays dirty with its error and is retried |
| Permissions | Role-level staff figures with `lp:view`; per person only with `lp:investigate` (403 otherwise) |
| Metric definitions | Numerator, denominator, exclusions and time boundaries per metric in `docs/spec/loss-prevention.md` and in the summary's `definitions` |
| Data completeness | `coverage` per area: days with data out of the days in the period; for table turnover also `sales`, `salesRecordingTable`, `recordedShare`, `firstRecordedDay` and `partial`. The new row metric `sale_table_recorded` counts sales whose payload has the `tableId` field (null when the sale had no table) |

Not tested: two jobs computing the same day after a lock expiry (one would fail on the primary key and retry).
