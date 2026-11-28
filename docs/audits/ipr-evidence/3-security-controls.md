# IPR Phase 3: security and operational controls (stream D evidence)

Branch `fix/ipr-3`, based on `fix/ipr-p15` at `fb91d736`. Test DB: a fresh Postgres 16 on port 5704.

This stream adds no migration: V710–V714 are unused. No ADR was needed. No engine core file was edited
(`StockService`, `StockMove`, `StockReversalService`, `StockPostingService`, `CostResolver`, `LayerCostWriter` and
`InventoryLedger` are untouched).

## How the red runs were made

1. The regression ITs were written against the finished fixes.
2. The `backend/src/main` changes were stashed, and the ITs ran against the base code (`fb91d736`). Every one failed;
   the FAILED lines are quoted below.
3. The fixes were restored, and the ITs ran green.

The web CSV test could not be shown red: the old `csvCell` was not exported.

Red run 1 (`IprBranchScopeCostIT`, `IprStockControlsIT` on base): 3 + 9 tests, 0 ok, 12 failed.
Red run 2 (`PeriodControlsIT`, `FleetRemindersPayrollIT` on base): 7 + 3 tests, 2 + 1 failed.

## Evidence table

| Finding | Root cause (file:line, base `fb91d736`) | Fix commit | Regression IT (red → green) | Workflow IT | Integrity / GL asserted | Lock levels | Deviations |
|---|---|---|---|---|---|---|---|
| S-1 branch-scoped reads | `LocationScope.java:76`: two or more locations and no filter gave 400 "Choose a location…"; `InventoryQueryService.java:138,261,513,555,660,733,824`, `InventoryReportsService`, `InventoryIntegrityService.java:180` all used `resolve` | see commits below | `IprBranchScopeCostIT.twoLocationUserGetsScopedReads`. Red: `[reads refused to a two-location user]`. `branchScopeMatrix`. Red: `GET /inventory/product-stock-levels?search=IPR-SCOPE → {"message":"Choose a location: your access is limited to specific locations",…,"statusCode":400}` | `branchScopeMatrix` (1, 2 and all locations; 403 outside the access), `InventoryBranchScopeIT` | yes (`assertIntegrityGreen`, `assertGlTieOut`) | reads only, no locks | inventory spec S-1 |
| S-2 cost visibility | `InventoryQueryService.java:253` (and item detail, items, register, waste and stock-out reads) returned costs to every `inventory:read` holder | see commits below | `IprBranchScopeCostIT.costVisibilityMatrix`. Red: the stock level row for a user without `inventory_valuation:read` carried `"averageCost":2,"totalValue":26` | same test, also with the permission (values shown) | yes | reads only | inventory spec S-2 |
| S2 (P15-6) SoD defaults for new orgs | `OrganizationsService.java:110`: bootstrap seeded no `SodRule` | see commits below | `IprStockControlsIT.newOrganisationSeedsTheInventoryPairs`. Red: FAILED (stock_adjustment not active) | bootstrap then the `/approval-controls/sod-rules` read; the audit org stays off | n/a (no stock) | one insert into `SodRule` inside bootstrap; no stock locks | kernel spec S2; **owner sign-off** for existing orgs |
| S9 SoD exemption never matched | `SegregationOfDutiesService.java:132,158-159`: `approvedByRequest` looked for `entityType = pairKey`, but requests are `stock_out`, `waste` or `inventory_count_submit` | see commits below | `IprStockControlsIT.engineApprovalByACheckerClearsTheWriteOffPair`. Red: `{"message":"Separation of duties: the person who made this document cannot also clear it (Stock write-off)",…403}` | the maker posts a stock-out after a checker decided the engine request; the maker's own approval stays refused | yes | L1 stock-out claim → engine L3–L7 (unchanged); the alias query is a read | kernel spec S9 |
| S3 transit loss approval | `StockDocService.java:989`: `receiveTransfer` wrote damaged and short goods off with no reason, threshold or approver | see commits below | `IprStockControlsIT.transitLossNeedsReasonAndApproval`. Red: a 200 `partially_received` where 400 "Give a reason…" was expected. `transitLossThroughTheApprovalEngine`. Red: the unknown field `lossReason` was refused (validation array) | named approver; SoD 403 for the receiver; engine workflow `stock_transfer_loss`, consumed once; at or below the threshold no reason needed | yes | L1 transfer row `FOR UPDATE` → loss check (reads cost; approval gate in REQUIRES_NEW, which touches only `ApprovalRequest`) → engine L3 → L4 (both locations in id order) → L5 → L6 → L7. `consumeApproval` runs in the outer transaction after the gate | inventory spec S3; **owner sign-off**: threshold default 0; offline receipts with a loss are held |
| S-4 direct stock self-approval / null approver | `StaffAttribution.java:89-92`: a null `approvedById` dropped `AND id = ?`, so a PIN matched any active user; `DirectStockService` had no SoD check | see commits below | `IprStockControlsIT.missingApproverNeverMatchesAnyUser`. Red: 200 `DSI-00006` posted. `directStockNoSelfApprovalUnderSod`. Red: 200 `DSI-00007` posted | in and out with another approver plus PIN post | yes | refusal happens before any engine call; posting L3–L7 unchanged | inventory spec S-4 |
| C-5 / S-7 / S14 manual reservations only | `StockReservationService.java:122,177`: `reserveForUser` and `consumeForUser` accepted any `sourceType` | see commits below | `IprStockControlsIT.manualReservationsOnly`. Red: 201 for `sourceType: "order"` | manual reserve, then consume; release of a document source stays open | integrity yes | refusal before L5 | inventory spec C-5; **owner decision**: release left open |
| S5 replenishment re-open | `ReplenishmentService.java:47,492-515`: an alert stayed `requisitioned` (LIVE) after its transfer was cancelled, so the rule never fired again | see commits below | `IprStockControlsIT.replenishmentAlertReopensWhenItsTransferIsCancelled`. Red: `[{status=requisitioned, statusReason=null}]` | scan → requisition (transfer) → cancel → scan raises again | integrity yes | alert row `FOR UPDATE` (the existing lock in `check`), then the document status read; no stock locks | inventory spec S5 |
| S-8 CSV negatives | `web/src/lib/export-csv.ts:9`: the formula guard prefixed `-3` with `'` | see commits below | `web/src/lib/export-csv.test.ts` (could not be shown red, see above) | n/a | n/a | n/a | inventory spec S-8 |
| OPS-1 null-user notifications | `FiscalPeriodCoverageService.java:168`, `FleetReminderService.java:175`: `SendInput(org, null, …)` | see commits below | `PeriodControlsIT.createsNextYear` and `alertsInstead`. Red: FAILED (count of addressed notifications 0). `FleetRemindersPayrollIT.reminders`. Red: `Incorrect result size: expected 1, actual 0` | the same tests (nightly job paths) | n/a | no stock locks | kernel spec OPS-1 |
| OPS-2 inactive approvers notified | `ApprovalsNotificationsSubscriber.java:79`: the approver query had no `isActive`/`deletedAt` filter | see commits below | `IprStockControlsIT.inactiveApproverIsNotNotified`. Red: FAILED (the inactive user got a notification) | a stock-out approval request notifies the active checker once | n/a | after-commit listener; no locks | kernel spec OPS-2 |

## Web

- **Stock levels:** API errors show in an alert; the Avg Cost and Total Value columns are hidden without
  `inventory_valuation:read`.
- **Item detail:** an API error shows instead of "Item not found"; hidden costs show as a dash.
- **Register report and waste page:** hidden values show as a dash, never as 0.
- **Transfer receive dialog:** asks for a loss reason, an approver and a PIN when a line has damaged or short goods.
- **Reservations:** Consume is offered only on active manual rows.
- **CSV export:** negative numbers stay numbers.
- **New helper:** `web/src/features/inventory/cost-visibility.ts`, with tests.
- **Query keys:** no new query keys, so the offline cache policy is unchanged.
- **Stock-outs:** there is no separate stock-out list page in the web, so nothing changed there.

## Engine request (not done, engine core is out of scope)

- **X-3 guard:** a manual move (direct stock in/out, stock-out, waste, adjustment, transfer) must not take stock out
  of, or put stock into, a quarantine or rental virtual location. The guard belongs in `StockService`, so it applies
  to every caller.

## Test runs

### Final run on a freshly reset DB

The run used `fastTest` on 59 classes: 436 tests, 435 ok, 1 failed. The one failure is
`InventoryHardeningIT.ledgerGuardBypass` ("The url cannot be null"), which is a known environment-only failure.

- **New ITs:**
  - `IprBranchScopeCostIT` 3/3
  - `IprStockControlsIT` 9/9
- **Changed tests:**
  - `InventoryIT` 21/21
  - `StockReservationOverviewIT` 3/3
  - `InventoryBranchScopeIT` 7/7
  - `InventoryIntegrityIT` 12/12
  - `InventoryEngineHardeningIT` 13/13
  - `InventoryEngineGapsIT` 13/13
  - `StockConcurrencyIT` 6/6
  - `NegativeStockPolicyIT` 8/8
  - `InventoryAuditConservationIT` 13/13
  - `PeriodControlsIT` 7/7
  - `FleetRemindersPayrollIT` 3/3
  - `AdvancedCostingIT` 5/5
  - `CostCloseIT` 3/3
  - `CostExplanationIT` 2/2
  - `CostingSettingsIT` 5/5
  - `ValuationIntegrityIT` 5/5
  - `ValuationGapsIT` 10/10
  - `IntercompanyIT` 7/7
- **R1 guards:**
  - `InventoryLedgerImmutabilityTest` 2/2
  - `InventoryIntegrityP0IT` 16/16 (includes `ledgerGuardEnabled`)
- **Other classes:** all green. They are listed in the stream report.

### Web

- `pnpm web:typecheck`: clean.
- `pnpm --filter @erp/web test`: 24 files, 251 tests, all passed. This includes the new `export-csv.test.ts` and
  `cost-visibility.test.ts`.

### Not run

The full Gradle build did not run (CI runs it). `ArchitectureTests` (5/5) and `ModularityTests` (2/2) ran once, green.

### Fixture change caused by S2

Bootstrapped one-user fixtures now call `ApiTestSupport.inventorySodPairsOff(token)`, because the new SoD defaults
would otherwise block an administrator who clears their own documents.

## Commits (`fix/ipr-3`)

| Commit | Findings |
|---|---|
| `b53aad3b` | S-1 (branch-scoped reads), S-2 (cost visibility on items, detail, stock levels, register) |
| `255ee970` | S2 (P15-6), S9, S-4 |
| `8cdfac09` | S3 (transit-loss approval), S-2 (waste, stock-out and transfer reads) |
| `a618317d` | C-5 / S-7 / S14 |
| `007f3379` | S5 |
| `a262f855` | OPS-1, OPS-2, `IprStockControlsIT` |
| `0d066329` | web (S-1, S-2, S3, C-5, S-8) |
| `a338affa` | docs: spec deviations, migration registry, this file |
| `b4809cd4` | test fixtures for the S2 defaults, manual reservation sources |
