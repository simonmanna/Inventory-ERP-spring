# PC-0 — Accounting baseline and exit record

- Roadmap: [`costing-production-ready-plan.md`](../roadmap/costing-production-ready-plan.md), PC-0
- ADRs: [S053](../adr/ADR-S053-landed-cost-erp-grade.md) (LC-0),
  [S054](../adr/ADR-S054-shared-fx-resolution-realised-fx.md) (shared FX)

## 1. Baseline captured before shared posting changes (2026-11-03)

- **Code:** clean `main` at `482f11a9`, in a separate worktree with no PC-0 changes.
- **Database:** fresh Testcontainers database (`test` task, not the reused `accountingRegression` container).
- **Suites:**
  - The full `accountingRegression` list: costing, valuation, landed cost, manufacturing, quarry, fleet, projects,
    profitability, close, reports.
  - Plus every class in `com.inventoryerp.invoicing.*`, `accounting.*`, `procurement.*` and `costing.*`.

| Result | Count |
|---|---|
| Tests run | 380 |
| Failed | 2 |
| Failures | `BackupJobsIT` (config backup without encryption password, line 95); `FleetIncidentsIT` (fine → company credit, `TypeMismatchDataAccessException` at line 296) |

Both failures are **pre-existing on `main`** and outside costing. Another session has uncommitted edits to
`FleetIncidentsIT` in the main checkout. They are not caused or masked by PC-0 and stay listed until their owners fix
them.

## 2. Shared posting changes made after the baseline

| Change | Owner decision | Proof |
|---|---|---|
| Rate precedence: explicit document rate → rate table → refuse; `findRate` non-throwing; `JournalEntryFxRate` snapshot (V291) | D-PC1 | `SharedFxIT`: explicitRateWins, tableRateWhenNoneStated, refusedWithoutRate; `LandedCostAuditIT` case 6 |
| Realised FX on payments (carrying vs settlement rate, partial and full, supplier and customer, void) and on credit notes | D-PC2 | `SharedFxIT`: supplierPartialAndFullSettlement, customerSettlement, creditNoteSettlement, currencyMismatchRefused; `LandedCostAuditIT` case 6b |
| Separate tax lines on generated supplier bills | D-PC3 (impact analysis only) | [`landed-cost-bill-tax-impact.md`](landed-cost-bill-tax-impact.md), for owner review; **not implemented** |

## 3. PC-0 exit criteria

| Criterion | Status |
|---|---|
| LC-0 complete: implementation map, GL trace, `LandedCostAuditIT`, tax recoverability (V290), G4 fix | ✅ |
| G1–G3 resolved | ✅ fixed (ADR-S054) |
| Baseline captured before the shared changes | ✅ §1 |
| Regression after the change: full `./gradlew build` | see §4 |
| Cost-flow GL trace for every costing posting path | ✅ [`costing-gl-trace.md`](costing-gl-trace.md): every row exact or guarded except return to supplier (LC-1) |
| `CostingAuditIT` (exact classification) | ✅ 13 scenarios: sale, waste, customer return, transfer, count gain/loss, production, absorption, revaluation up/down, NRV write-down, recovery, sale, standard activation; 1 `@Disabled` (V1) |
| Uniform books check on every accounting-regression suite | ✅ `BooksGuard` on 24 suites (entries balance, tie-out unchanged), all green |

**PC-0 exit criteria are met.** Open owner decisions do not block PC-1:
- **V1:** NRV allowance release classification.
- **D-PC3:** bill tax lines.

## 4. Regression after the change

**PC-0 close build (2026-11-03):** 1,679 tests, the same 2 pre-existing failures, 4 skipped (2 LC-1 landed cases,
V1 release, one existing skip). Formatting and coverage are as below.

**LC-0 / PC-0 FX build:**

Full `./gradlew build` of the combined LC-0 + PC-0 branch, fresh database (2026-11-03):

| Result | Value |
|---|---|
| Tests run | 1,666 (3 skipped: `LandedCostAuditIT` LC-1 cases and one existing skip) |
| Failed | 2. **The same two as the baseline** (`BackupJobsIT`, `FleetIncidentsIT`), no new failure |
| `spotlessCheck` | green |
| JaCoCo ≥ 0.80 per `application` package | green for every package this work touches (`procurement`, `accounting`, `invoicing`, `costing`). Fails on `hr.application` (0.79) and `backup.application` (0.78): untouched packages, whose coverage drops with the failing `BackupJobsIT` / HR suites already on `main` |
| Web `tsc --noEmit` | 0 errors |
