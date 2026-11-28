# IPR Phase 4: go-live readiness and the promoted P2s (stream F evidence)

Branch `fix/ipr-4`, based on `fix/ipr-p15` at `adfef4a3` (Phase 0 + Phase 3 merged). Test DB: owner-split Postgres 16
`erp-itdb-ipr4` on port 5704 (Flyway as `owner`, the app as `erp` under RLS).

- **Migrations:** none. V708–V709 were assigned and are not needed (recorded in `docs/adr/migration-registry.md`).
- **ADR:** ADR-S093 (tenant-bound connections, S-6).
- **Engine core:** not edited. `StockService`, `StockMove`, `StockReversalService`, `StockPostingService`,
  `CostResolver`, `LayerCostWriter` and `InventoryLedger` are untouched. Stream A, B and C files are untouched.
  Requests for S13 and GL-7 are below.

## How the red runs were made

1. The regression ITs were written first, against the intended behaviour.
2. One script under the Gradle lock compiled the branch with the fixes, then restored the five fixed main files to
   their base versions (`git checkout`; the new `TenantBindingDataSource.java` removed) and ran the three new ITs. It
   then put the fixes back and ran the same ITs plus the affected classes.

Red run (base code, the new ITs only):

| Class | Tests | Passed | Failed | Failing assertion |
|---|---|---|---|---|
| `MaterialLedgerRaceIT` | 1 | 0 | 1 | `[one post wins, the other is refused]` (both posts answered 200) |
| `TenantRlsBackstopIT` | 3 | 1 | 2 | `tenantReadOutsideTransactionIsRestricted` `[another tenant, no transaction]`; `bindingNeverLeaksAcrossThePool`. `noTenantIsTheSystemMode` passes on base by design (it pins the remaining gap) |
| `RentalStockLifecycleIT` | 3 | 1 | 2 | `cleanReturnLeavesRentOut` `[the counter return takes the unit out of RENT-OUT]`. `damagedReturn` passes on base (the old OUT → DAMAGED path ends in the same balances). `partialReturns` failed on setup (it used a pooled product, which can never be rented); it was rewritten with unit lines after the red run and was **not** re-run red |

The green run of the same script then failed only `MaterialLedgerRaceIT` on `valuation_vs_gl_by_product` after the
post: a pre-existing defect (see "New defects found"), now pinned in the test at exactly one delta.

## Evidence table

| Finding | Root cause (file:line, base `adfef4a3`) | Fix commit | Regression IT (red → green) | Workflow IT | Integrity / GL asserted | Lock levels | Deviations |
|---|---|---|---|---|---|---|---|
| M-7 (P1, P15-3) rental RENT-OUT never decremented on a clean return | `RentalReturnService.java:107-160` (`createReturn` moves no stock), `:206-209` (clean inspection moves RENT-CLEAN → RENT-STOCK although nothing left RENT-OUT) | `5e883049` | `RentalStockLifecycleIT.cleanReturnLeavesRentOut`, `.damagedReturn`, `.partialReturns` | the same class: out → return → clean → stock; damaged; partial return of one line; a missing unit stays in RENT-OUT (even inspected with a damage) and is found later; over-return refused; second inspection refused. `RentalIT.lifecycle` updated (RENT-OUT 0 after inspection) | yes, `assertIntegrityGreen` + `assertGlTieOut` after every step | return: **L1** `RentalAgreement FOR UPDATE` → cap read → engine transfer **L3 → L4** (OUT, CLEAN in location-id order) **→ L5 → L6 → L7**. Inspect: **L1** `RentalReturn FOR UPDATE` → per line engine transfer L3–L7 → `RentalUnit` status update (order unchanged: the unit row is written after the engine, as before) | `docs/spec/rental.md` Deviations (IPR M-7); new 400s `Cannot return <n> on agreement line <id>: only <m> still out`, `Return line <id> has already been inspected` |
| M-23 material-ledger run double post | `MaterialLedgerService.java:229-231` (plain status read), `:272` (UPDATE without `status = 'draft'`); same pattern in `reverse` (:281, :302) and `delete` (:139) | `76e44b2a` | `MaterialLedgerRaceIT.concurrentPostAndReverseRunOnce` (two posts with different keys, then two reversals, through the `race` helper; a 40P01 fails the test) | the same test (receipt at 13 against standard 10, consumption, run, post, reverse) and `AdvancedCostingIT.materialLedger` | yes, after the post and after the reversal | **L1** `MaterialLedgerRun FOR UPDATE` → compute (reads) → **L7** journal → value rows (ledger inserts; no L3/L4: LK-15, P3, unchanged) | none (existing refusal strings); `docs/spec/costing.md` note |
| S-6 (P2, promoted for every org) RLS lets everything through outside a tenant transaction | `TenantAwareTransactionManager.java:27-44` sets `app.org_id` only in `doBegin`; the V214 policy passes every row while it is empty | `6941cee7` | `TenantRlsBackstopIT.tenantReadOutsideTransactionIsRestricted`, `.bindingNeverLeaksAcrossThePool` | `.noTenantIsTheSystemMode` (pins the remaining gap: jobs, `InstallWide`, `unscoped` span orgs), `TenantRowSecurityIT`, and the wide regression set below (schedulers, monitors, intercompany, portals, licensing, POS, sync) | n/a (no stock); the inventory ITs of the wide set assert integrity / GL themselves | no locks (one `set_config` per connection checkout) | `docs/spec/kernel.md` (IPR Phase 4, S-6); ADR-S093. **Owner sign-off:** the no-tenant system mode stays unrestricted |
| T33 integrity page and go-live checklist | absence (part of S-5) | `1cc16b76`, `72d69f95` | web `integrity.test.ts` (7 tests: permission gating, Posting Monitor links, summaries, rows, features, blockers, cache classes) | n/a | n/a | reads only | `docs/spec/inventory.md` IPR Phase 4 (additive `fixedP2s`) |
| R6 map | `InventoryFeatureUsageService.P2_FEATURES` had no fixed state (a fixed P2 had to be deleted) | `320ee96c` | `InventoryFeatureRiskMapDocTest.docMatchesCode`; `InventoryIntegrityP0IT.featureUsage` now derives the expected promoted list from the map | `InventoryIntegrityP0IT` | `feature_risk` follows the map | n/a | none |

## T33: the web page

- **Route:** `/inventory/integrity` (`inventory_valuation:read`). Nav: Inventory → Reporting → Integrity &
  Reconciliation.
- **Sections:**
  - go-live blockers (failing checks plus promoted P2s);
  - integrity checks and report-only checks, failing first, each expandable to its rows, with a **Findings** link to
    `/inventory/posting-monitor?kind=integrity_<check>` (shown with `pos:reports`);
  - GL tie-out (shown with `report:accounting`), with a link to the `gl_tieout_variance` findings and to the full
    tie-out page;
  - feature usage: open P2s on features in use, P2s fixed in the programme (`fixedP2s`), and every feature with its
    count (features that promote a P2 highlighted);
  - a location filter (the integrity report's `locationId`) and "Run again".
- **Posting Monitor:** reads `?kind=` to preselect a finding kind.
- **Query keys:** `inventory-integrity` and `inventory-feature-usage` are classified `cache-strict` (24 h, dropped on a
  permission change); `inventory-gl-tieout` was already classified.
- **Fixed on the way:** `OfflineDiscrepanciesPage` linked to `/pos/posting-monitor`, a route that does not exist; it
  now links to `/inventory/posting-monitor`.
- **No new backend route**, so no new authorization test. The three routes keep their permission tests
  (`InventoryIntegrityP0IT.featureUsage` 403/401, `InventoryIntegrityIT`, the accounting tie-out tests).

## T33: go-live checklist

`docs/OPERATIONS.md` §8c covers:
- costing method;
- negative stock;
- serial and expiry policies;
- SoD (new-org defaults; existing orgs are an owner decision);
- thresholds (`inventory.transitLossApprovalThreshold`, approvals, the ADR-S088 time limits);
- tolerances;
- opening stock (the wave-3 opening-stock import);
- the first integrity run;
- alert recipients;
- the feature-usage re-check as a **mandatory release step**;
- the day-1 check.

## R6: the feature-risk map

- `P2Rule` has `fixedBy` (an evidence file). A fixed P2 is never promoted; the report lists it under `fixedP2s`.
- Marked fixed on this branch, by the evidence files present:
  - **S14** and **S-8**: `docs/audits/ipr-evidence/3-security-controls.md` (stream D; S-8 could not be shown red
    there);
  - **S-6**: this file (with the documented system-mode gap).
- Everything else stays `open`. X-3 is still open (stream D filed it as an engine request).
- **For the integrator:** tick a P2 by appending `.fixed(<evidence file>)` in `P2_FEATURES` and setting Status and
  Evidence in the doc table. `InventoryFeatureRiskMapDocTest` (plain JUnit) checks that the two agree and that the
  file exists. Steps: `docs/spec/inventory-feature-risk-map.md`, "Changing the map".

## S-6: what passes, before and after

ADR-S093 has the matrix. In short, a tenant's statement outside a transaction saw every organisation's rows; now it
sees only its own. Work with no tenant (jobs, login, bootstrap, `InstallWide`, `TenantContext.unscoped`) stays
unrestricted, as before.

A default-deny policy was not safe within this stream:
- about 57 scheduler classes, plus every public and login path, would need an explicit system marker;
- a miss fails silently;
- the policy of about 400 tables would need rewriting.

The detector is `TenantRlsBackstopIT`, with `TenantRowSecurityIT` and `DatabaseRoleValidator`.

## Not fixed here: requests for the integrator

### S13 (GL granularity): stream C file `StockPostingService`

- **`StockPostingService.java:530-552` `postAdjustment`.** Every adjustment reason resolves `ADJUSTMENT_GAIN` /
  `ADJUSTMENT_LOSS`, so theft, damage and found stock land on one account.
  - **Proposed:** pass the adjustment reason (`StockAdjustment.reason` or the count's reason code). Resolve
    `resolveLines("ADJUSTMENT_LOSS_" + REASON)` (or `_GAIN_`) first. Then fall back to today's `ADJUSTMENT_LOSS` /
    `ADJUSTMENT_GAIN`, then to the account mappings.
  - Posting rules per reason are configuration, so nothing changes until an organisation adds them.
- **`StockPostingService.java:792-799` `fallbackLinesIssue`.** `WASTE` and `EXPIRY_WRITE_OFF` fall back to
  `stock_adjustment_expense`.
  - **Proposed:** new optional account mappings `stock_waste_expense` and `stock_expiry_expense`, used when mapped;
    otherwise `stock_adjustment_expense` (the default is unchanged).
- **`StockPostingService.java:563-579` `postTransfer`.** Without `STOCK_TRANSFER_OUT` / `IN` rules no journal is
  written, so goods in transit are not separated in the GL.
  - **Proposed:** when the destination is a `transit` location and an account mapping `goods_in_transit` exists, post
    Dr goods in transit / Cr stock valuation on dispatch, and the reverse on receipt. The integrity `transit` check
    already reconciles the quantities. Off unless the mapping is set.
- **Callers that would pass the reason:** `StockDocService` (adjustment approve) and `InventoryCountService` (submit).
  The change is in the `postAdjustment` signature, so it belongs with stream C.

### GL-7 (no AVCO residue sweep on moves that skip the GL): engine core `StockService`

- **`StockService.java:873`:** `if (residueAt != null && !dto.skipGlPosting)`.
  - Callers that post their own journal set `skipGlPosting`: debit-note RTV, supplier-claim settlement, stage and
    process consume, fleet fuel and tyres, project issue, QC.
  - They never sweep. An AVCO quant taken to zero keeps a residue below 1.00, `valuation_vs_subledger` (0.01) fails,
    and it holds the close.
- **Proposed:** drop `&& !dto.skipGlPosting` from that condition.
  - `sweepAvcoResidue` (:1716-1737) writes its own value-only ledger row and its own journal
    (`postNegativeStockCostCorrection`, key `...:residue:<code>`), independent of the caller's journal. The caller's
    posting is not affected.
  - Lock levels are unchanged (inside the issue's L3–L5).
- **Regression IT:** receive an AVCO product at costs that leave a 1/3 residue, issue the quant to zero through a
  `skipGlPosting` caller, then assert the residue row and `valuation_vs_subledger` green.

## New defects found

- `web/src/pages/warehouse/OfflineDiscrepanciesPage.tsx:44` linked to `/pos/posting-monitor` (no such route).
  Fixed in this branch.
- `RentalReturnService.inspect` writes `RentalUnit` after the engine transfers: a business row after L3–L7. It cannot
  deadlock today, because no other path locks `RentalUnit` before stock, but it is outside the documented order. Left
  as is.
- Found while writing M-7: a **missing** return line inspected **with a damage** moved RENT-OUT → RENT-DAMAGED
  (`RentalReturnService.java` base :206-212, the damaged branch had no missing guard). With the counter return now
  moving found units, a later "found" return would decrement RENT-OUT a second time. Fixed in the M-7 commit: a missing
  line moves no stock at inspection (the damage row and charge are still recorded); `partialReturns` covers it.
- **Material-ledger post breaks `valuation_vs_gl_by_product` (pre-existing, not caused by M-23).**
  `MaterialLedgerService.java:268` (base :255) debits `stock_valuation`, stamped with the product, while
  `valueRows` (:334-365) writes the matching ledger rows as value-only `valuationComponent = 'price_difference'`, and
  `InventoryIntegrityService.java:504,508,534` keeps only `valuationComponent IS NULL` rows on the subledger side. After
  any run is posted, the product shows a gap of the run delta (here −45) and the check holds the close until the run
  is reversed. The organisation-level `valuation_vs_gl` stays green. Either the run should credit/debit the
  `inventory_price_difference` adjunct (as landed cost does for standard products, ValuationIntegrityIT) or the check
  should count `price_difference` rows posted to the stock account. Owner/stream C decision; not changed here.
  `MaterialLedgerRaceIT` pins the gap at exactly one delta (also a double-post signal).
- `MaterialLedgerService.valueRows` writes value rows with no L3/L4 (LK-15, P3, known).

## Test runs

Final run on a freshly reset `erp-itdb-ipr4` (`docker rm -f -v`, new container), on the committed branch, one
`fastTest --continue` with all classes below (19 min 33 s, BUILD SUCCESSFUL): **36 classes, 259 tests, 259 passed, 0
failed.** Known environment-only failures (`InventoryHardeningIT.ledgerGuardBypass`, `InventoryAuditCrashBoundaryIT`)
were not in the set.

| Class | Tests | Passed | Failed |
|---|---|---|---|
| `ApplicationIT` | 4 | 4 | 0 |
| `AuthorizationMatrixIT` | 2 | 2 | 0 |
| `GetRouteSweepIT` | 34 | 34 | 0 |
| `accounting.PeriodCloseChecklistIT` | 7 | 7 | 0 |
| `accounting.PeriodControlsIT` | 7 | 7 | 0 |
| `accounting.verification.Acc0TenantIsolationIT` | 3 | 3 | 0 |
| `alerts.AlertsIT` | 7 | 7 | 0 |
| `backup.application.BackupJobsIT` | 5 | 5 | 0 |
| `costing.AdvancedCostingIT` | 5 | 5 | 0 |
| `costing.MaterialLedgerRaceIT` | 1 | 1 | 0 |
| `costing.ValuationIntegrityIT` | 5 | 5 | 0 |
| `fleet.FleetRemindersPayrollIT` | 3 | 3 | 0 |
| `intercompany.IntercompanyIT` | 7 | 7 | 0 |
| `inventory.InventoryFeatureRiskMapDocTest` | 1 | 1 | 0 |
| `inventory.InventoryIT` | 21 | 21 | 0 |
| `inventory.InventoryIntegrityAlertsIT` | 3 | 3 | 0 |
| `inventory.InventoryIntegrityIT` | 12 | 12 | 0 |
| `inventory.InventoryIntegrityP0IT` | 16 | 16 | 0 |
| `inventory.InventoryLedgerImmutabilityTest` | 2 | 2 | 0 |
| `kernel.OutboxDeadLetterIT` | 2 | 2 | 0 |
| `kernel.TenantRlsBackstopIT` | 3 | 3 | 0 |
| `kernel.TenantRowSecurityIT` | 3 | 3 | 0 |
| `kernel.licensing.LicensingIT` | 24 | 24 | 0 |
| `notificationrouting.NotificationRoutingIT` | 6 | 6 | 0 |
| `portal.PortalIT` | 9 | 9 | 0 |
| `pos.PosStockPostingIT` | 10 | 10 | 0 |
| `pos.PosStockPostingRecoveryIT` | 2 | 2 | 0 |
| `pos.PostingMonitorAbandonIT` | 1 | 1 | 0 |
| `projects.ProjectTenantIsolationIT` | 2 | 2 | 0 |
| `qualitycontrol.QualityControlAlertsIT` | 6 | 6 | 0 |
| `rental.RentalIT` | 10 | 10 | 0 |
| `rental.RentalLifecycleIT` | 8 | 8 | 0 |
| `rental.RentalStockLifecycleIT` | 3 | 3 | 0 |
| `sync.SyncBranchRelayIT` | 6 | 6 | 0 |
| `sync.SyncIT` | 10 | 10 | 0 |
| `sync.SyncInventoryIT` | 9 | 9 | 0 |

- `ArchitectureTests` 5/5 and `ModularityTests` 2/2 passed (one run, at the end).
- Web: `pnpm --filter @erp/web test` 25 files, 258 tests passed (includes `integrity.test.ts`, 7, and the
  cache-policy test); `tsc` typecheck clean.
- The full Gradle build was **not** run (owner rule).
