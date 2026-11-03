# Cost accounting to production — roadmap PC-0 … PC-9

- Status: **approved in principle** (owner, 2026-11-03), subject to phase gates. **PC-0 exit met 2026-11-03** ([record](../audits/pc0-accounting-baseline.md)). Decisions: see below.
- Builds on:
  - [`cost-management-plan.md`](cost-management-plan.md): CM-0 … CM-11, built; APIs only for CM-8 … CM-11.
  - [`landed-cost-plan.md`](landed-cost-plan.md): LC-0 … LC-7, LC-0 in progress.
- ADRs: S030–S042 (cost management), S053 (landed cost). One new ADR per phase, written before code.

## Context

The costing engine is built feature by feature. It is **not yet production ready**. Four things are missing:

1. **Proof.** Not every posting path has a debit/credit trace, nor a test that checks classification after every step.
   LC-0 does this for landed cost only.
2. **Correctness gaps.** AVCO, lot and transfer lineage. Returns, closed orders and FX. The LC-0 findings (G1–G4,
   F2–F9).
3. **Deferred items.** Every CM ADR lists some (collected in the table below).
4. **Operations.** Web screens for CM-8 … CM-11, performance at volume, cut-over, monitoring, a parallel run and
   sign-off.

This roadmap orders all of it into ten phases. **PC-0 … PC-3 are gates.** Nothing after them starts until their
financial acceptance tests pass on a fresh database.

## Owner decisions (2026-11-03)

**D-PC1, G2/G3 shared currency handling: APPROVED (PC-0).**

Rate precedence:
1. An explicit, validated document rate.
2. An authorised workflow rate, if supported.
3. The configured exchange-rate table.
4. Otherwise **reject** the posting.

Rules:
- A table rate never silently overrides an explicit document rate.
- The rate is resolved **before** posting, and its rate, date, source and currency pair are snapshotted.
- A failed resolution leaves no partial postings.
- Regression tests cover the affected modules.

**D-PC2, G1 realised FX on payments: APPROVED (PC-0).**
- Built in the shared payment and settlement engine.
- Covers:
  - Partial and full settlements.
  - Different payment rates.
  - Supplier and customer documents.
  - Credit notes, cancellations and reversals.
- Posts to the designated FX gain and FX loss accounts only.
- Never reaches inventory, landed cost, production cost or COGS.

**D-PC3, separate tax lines on generated supplier bills: APPROVED IN PRINCIPLE, implementation subject to impact
analysis.**
- The analysis comes first: [`docs/audits/landed-cost-bill-tax-impact.md`](../audits/landed-cost-bill-tax-impact.md).
  It covers:
  - Where input tax comes from today.
  - What the VAT return depends on.
  - The proposed bill tax records.
  - How historical transactions are treated.
  - The migration strategy.
- One authoritative source for input tax on the VAT return, with no double recognition.
- Historical postings and VAT reports are preserved.
- Before/after VAT regression tests.
- **No migration until the owner reviews the analysis and its acceptance tests.**

**2026-11-03, PC-0 closure and PC-1 start (owner):**
- PC-0 is accepted as closed on `a2cc07f9`.
- The pre-existing failures `BackupJobsIT` / `FleetIncidentsIT` stay visible and are not suppressed.
- The BooksGuard limitation and the unguarded return-to-supplier path stay documented.
- **V1 approved:** NRV allowance release is classified per ADR-S031 (cost of sales), with exact tests for partial and
  full sales, recovery, subsequent sales and reversals. Posted history is preserved. Done within PC-1.
- **PC-1 approved to start**, plan first: [`docs/phases/PC-1-valuation-correctness.md`](../phases/PC-1-valuation-correctness.md).
  Valuation correctness across AVCO / FIFO / lot / serial / STANDARD, transfers, returns, negative stock,
  WIP → finished goods and late landed costs. Plus the return-to-supplier guard, independent AVCO golden tests, and
  conservation, lineage-identity and reconciliation checks.
- PC-2 does not start until the PC-1 exit criteria are demonstrated.
- D-PC3 stays on hold.

**Execution boundaries:**
- LC-0 is completed and reviewed first.
- PC-0 captures the accounting baseline before any shared posting change.
- Phases run strictly in order. PC-1 starts only when the PC-0 exit criteria are met and documented.

## Standing rules

1. Phases run strictly in order. Each one gets a phase doc `docs/phases/PC-n-*.md`, then the build, then a done-report.
2. No acceptance test is weakened to make a phase pass. A phase is never closed on compilation or unit tests alone.
3. Changes to shared or legacy (Nest-contract) behaviour need owner approval and are listed under Deviations.
4. Reuse the existing engines; never build a second one: `CostLineageService`, `AvcoLineageCalculator`,
   `LineageCapitalisation`, `AllocationCalculator`, `CostAdjustmentService`, `ApprovalLimitService`, the `gl-tieout` and
   the close cockpit.
5. Every financial IT checks after every step:
   - The entries balance.
   - The sub-ledger ties out to the GL.
   - Classification per account.
   - Assertions are deltas or scoped to the test's own fixtures.
6. `./gradlew accountingRegression` runs on a **fresh** test database at every phase exit.

## Open items → phase

| Source | Item | Phase |
|---|---|---|
| PC-0 V1 | NRV allowance release posts to NRV expense, not cost of sales as ADR-S031 states (P&L total equal) | Approved; fixed in PC-1 WP1 (ADR-S055) |
| LC-0 G1 | No realised FX on payments, system-wide | PC-0 (owner decision) |
| LC-0 G2 / G3 | `CurrencyService.getRate` throws inside a transaction, so a foreign-currency voucher rolls back; a table rate overrides the document's own rate | PC-0 (owner approval) |
| LC-0 F2, F5–F7 | AVCO / lot landed cost ignores lineage: transfers, WIP, returns, closed orders | PC-1 |
| CM-1 (S031) | AVCO landed split switch; finished-goods adjunct; multi-level roll-up | PC-1 |
| CM-10 (S041) | Material-ledger rework (AD-3) | PC-1 |
| CM-10 (S041) | Cost-flow bridge gate | PC-3 |
| CM-4 / CM-10 | `CostAllocationService` reimplementation; settlement blocked by open orders (WIP target) | PC-4 |
| CM-5 (S035) | Rehab asset depreciation through fixed assets; crushing / block KPIs | PC-4 |
| CM-6 (S036) | Separate tax lines on generated bills | PC-5 (owner decision) |
| CM-7 (S037) | In-transit stage; per-location accounts | PC-5 |
| CM-8a (S038) | Commitments per work breakdown structure; project receiver in allocation cycles | PC-5 |
| CM-8b (S039) | IFRS 9 expected credit loss on retentions; retention current/non-current split; provision discounting | PC-5 |
| CM-9 (S040) | Actual/standard/estimate layer on legacy estimate reports; BI export feeds | PC-7 |
| CM-11 (S042) | BOM / absorption / fleet / quarry roll-ups in simulation | PC-7 |
| CM-8 … CM-11 | No web screens | PC-6 |
| DMS / HR / EX | Untranslated i18n keys (costing screens) | PC-6 |
| LC-1 … LC-7 | Landed-cost roadmap | PC-1 … PC-5 (below) |

---

## PC-0 — Baseline and proof (gate)

**Goal:** every costing posting path is traced and tested. Shared FX defects are fixed.

1. **Finish LC-0** (landed cost audit, V290 tax recoverability, G4 fix).
2. **Fix G2 / G3** after owner approval:
   - `CurrencyService.getRate` with `noRollbackFor = ApiException.class`, or a non-throwing `findRate`.
   - `PostingService.rate` uses the document's own rate when it has one; the table rate only as a fallback.
   - Turn on `LandedCostAuditIT` case 6.
3. **G1 (owner decision):** realised FX gain/loss on settling foreign-currency bills and invoices. It is AP/AR code,
   but costing depends on it: FX must never reach stock.
4. **Cost-flow GL trace**, extending `docs/audits/landed-cost-gl-trace.md` into `docs/audits/costing-gl-trace.md`. One
   table per event, naming account, code line, stock-ledger row and lineage target. Events:
   - Receipt, issue, sale, return.
   - Transfer (immediate and through transit).
   - Production issue, output and scrap.
   - Absorption settlement.
   - Revaluation, NRV write-down and reversal.
   - Standard-cost change.
   - Price-difference release.
   - Cost adjustment run.
   - Quarry stripping and rehabilitation.
   - Fleet and distribution cost.
   - Project capture and settlement.
   - Landed cost (already done in LC-0).
5. **`CostingAuditIT`**: one scenario per event above, with the after-step checks (rule 5). Cases the engine can't yet
   satisfy stay `@Disabled("PC-n")`.

**Exit:** trace complete; `CostingAuditIT` and `accountingRegression` green; G1–G3 resolved or formally deferred by
the owner.

## PC-1 — Valuation correctness (gate)

**Goal:** every cost follows the goods. This is LC-1, widened to the whole engine.

- **Transfer lineage:** `InventoryTransferLineage` (many-to-many, filled by the transfer posting, backfilled exactly
  from `ledgerCode` pairs). Landed cost, revaluation and every other late cost follow it, multi-hop, with cycle
  detection and `LineageException`.
- **AVCO, lot and serial through lineage.** Golden test `AvcoLandedMathTest` checks:
  - `min(q, P)` cap.
  - Settling negative stock never redistributes the earlier charge.
  - Σ shares = charge exactly.
- **New lineage targets:** `return` (with the return policy), and closed-order consumption cascading onto the output
  layers (CM-10 path).
- **Finished-goods adjunct and multi-level roll-up:** a late cost reaches FG, and FG sold → COGS, exactly once.
- **Material-ledger rework (AD-3):** actual-cost periodic settlement over the same lineage.
- **Credit vouchers** (supplier credit notes) through the same engine.

**Exit:**
- Multi-hop, partial and multi-product transfers.
- Destination sale and destination production.
- Scrap; negative stock.
- WIP → FG → sale.
- Returns under each policy.
- Credit notes.
- All green, with correct classification and location.

## PC-2 — Financial controls (gate)

**Goal:** no costing document can post unreviewed, twice, or above authority.

- **Maker-checker and approval limits on every costing document:**
  - Landed cost vouchers.
  - Cost adjustment runs.
  - Absorption settlements.
  - Revaluations, NRV write-downs, standard-cost changes.
  - Price-difference releases.
  - Quarry provisions.
  - Project settlements.

  Each uses `ApprovalLimitService` with its own `entityType`.
- **Approval snapshots:**
  - `approvedHash` covers every input that determines money.
  - The disposition is recomputed at posting. The OR tolerance applies per target, both percentage and amount.
  - New action `reopen`.
- **Duplicate supplier invoices:** normalised reference, two partial unique indexes, audited override.
- **Allocation validation:** weight, volume and UoM required where used, stored on the allocation row. Σ allocated =
  charge exactly.
- **Period lock and idempotency audit** across costing routes. Every money route rejects a missing `Idempotency-Key`
  and replays correctly.

**Exit:** authorization matrix, concurrency (double post, duplicate invoice) and lock-period tests green for every
document type.

## PC-3 — Reconciliation and close (gate)

**Goal:** the books prove themselves at every period end.

- **Reconciliation endpoint and UI:** sub-ledger vs GL vs difference, with drill-down, for:
  - Stock.
  - 1396 adjunct (filtered by source type).
  - WIP (per production order and project).
  - GRNI.
  - 2155 landed clearing.
  - 2156 landed accrual.
  - Accrued expenses.
  - COGS from late costs.
- **Classification and location checks.** A line in the wrong account or warehouse is listed even when totals balance.
- **Cost-flow bridge (deferred from CM-10):** opening stock + purchases + landed + conversion − COGS − scrap ± revaluation
  = closing stock, per period, product family and location.
- **Trace endpoint**, tenant-safe, purchase and production chains:
  - Invoice → charge → receipt → movement (incl. transfer hops) → adjustment → journal line.
  - Issue → order → WIP → FG → sale → COGS.
- **Close cockpit:** the reconciliation, bridge and exception gates become mandatory before lock.

**Exit:** `LandedCostEndToEndIT` and a new `CostingEndToEndIT` cover the whole chain:

> import → transfer → production → late charge → completion → FG transfer → sale → accrual clear → credit note →
> reversal → re-post

Zero differences, zero mismatches.

## PC-4 — Manufacturing and quarry completion

- **`CostAllocationService` reimplementation** on `AllocationCalculator`. Project receivers in allocation cycles.
- **Settlement with open orders:** WIP target. IAS 2.13 normal-capacity absorption; unabsorbed overhead expensed.
- **By-products and co-products:** by-product credit; co-product split by physical or relative-sales-value basis
  (configurable).
- **Quarry:** IFRIC 20 stripping asset, IAS 37 rehabilitation provision, and the rehab asset depreciated through fixed
  assets.
- **KPIs:** cost per tonne, m³ or block from **actual good output** and yield, never from input quantity.

**Exit:** crushing, block and absorption scenario ITs tie out. KPI figures match hand-computed cases.

## PC-5 — Distribution, landed cost and projects completion

- **Landed cost LC-4 … LC-7:**
  - Accrual rules UI; percentage, per-unit and per-kg rates.
  - Partial clearing; FX kept apart from estimate variance.
  - Voucher UX.
  - Variance, valuation and cost-centre reports.
  - Incoterm matrix; versioned customs tariff engine.
- **Distribution (CM-7):** in-transit stage valuation; per-location stock accounts; delivery cost to segment.
- **Projects (CM-8a/8b):**
  - Commitments per work breakdown structure.
  - IFRS 9 expected credit loss on contract assets and retentions.
  - Retention current/non-current split.
  - IAS 37 provision discounting with unwinding.
- **Owner decision:** separate tax lines on generated supplier bills (moves where the VAT return reads input tax).

**Exit:** scenario ITs per area green; accrual FX test (USD accrual, EUR actual, a third payment rate) green.

## PC-6 — Web UI for everything API-only

- Screens for:
  - Projects and contracts (CM-8a/8b).
  - Profitability (CM-9).
  - Close cockpit and cost adjustment run (CM-10).
  - Simulation (CM-11).
  - Reconciliation and trace views (PC-3).
  - Exceptions workspace: lineage, historic tax review, duplicate overrides.
  - Landed-cost stepper and accrual monitor (LC-5).
- Navigation: Cost Management grouped into Configuration / Operations / Analysis and reconciliation.
- Translations of the costing i18n keys; responsive below 768px.

**Exit:** every costing route has a screen. Browser walk-through with zero console errors; screenshots in the
done-report.

## PC-7 — Reporting and analytics

- Actual/standard/estimate layer on the legacy estimate reports and BI export feeds (CM-9 deferred).
- Variance analysis: price, usage, efficiency, volume, mix, yield.
- Valuation impact by warehouse, product and method.
- Margin before and after landed cost:
  - Same units and period on both sides.
  - Purchase, landed, conversion and distribution cost in separate columns.
  - Selling and admin costs shown apart.
- Simulation roll-ups for BOM, absorption, fleet and quarry engines (CM-11 deferred).
- CSV and BI export for every report.

**Exit:** every report reconciles to the GL for a closed period.

## PC-8 — Non-functional readiness

- **Performance at volume:**
  - Lineage walks over 10M+ ledger rows (indexes, pagination of events, memoised pools).
  - Release and adjustment runs batched under ShedLock, resumable.
  - Load test of the month-end close on a seeded large org.
- **Cut-over:**
  - Opening cost layers per location and lot.
  - Transfer-lineage backfill.
  - Historic tax review.
  - Opening WIP and provisions.
  - A rehearsed migration script, with a rollback.
- **Monitoring:** Micrometer metrics, plus alerts on any reconciliation difference, lineage exception, stuck
  idempotency operation or failed scheduled run.
- **Security:** RLS on every new table (`TenantRowSecurityIT`); permission matrix; no secrets in logs.
- **Backup and restore drill** of the costing tables; runbooks in `docs/OPERATIONS.md`.

**Exit:** load-test targets met (agreed in the phase doc); restore drill passed; runbooks reviewed.

## PC-9 — Pilot and go-live

- UAT on client-shaped data: MENA demo orgs first, then the pilot client.
- **Parallel run:** one full monthly close on the new system next to the current books, with every difference explained.
- Sign-off by the client's accountant or auditor: valuation policy, close checklist, reconciliation pack.
- Hypercare for 2 closes: daily reconciliation report, issue log, fix-forward releases.

**Exit:** parallel close matches; written sign-off; release tag cut after a green full build.

---

## Phase acceptance (applies to every phase)

- `cd backend && ./gradlew build` green, with JaCoCo ≥ 0.80 on each touched `application` package.
- `accountingRegression` green on a fresh test database.
- The phase's financial ITs green after every step (rule 5).
- Web: `pnpm --filter web typecheck` clean; browser walk-through for any UI phase.
- Docs: phase doc, done-report, ADR, `docs/PORT_STATUS.md`, and Deviations where relevant.
