# Manufacturing WIP reconciliation

- Status: **Built (IPR Phase 2, stream C, branch `fix/ipr-2`)** as the report-only integrity check `wip_tieout`
  (`manufacturing/application/WipTieOutService.java`, SPI `inventory/application/WipTieOutProvider.java`); the
  `GET /manufacturing/reports/wip-reconciliation` report uses the same basis (WIP-6). Promotion to a value check
  (holding the period close) is an owner decision. Changes to §5.3 from Phase 2 are marked "(Phase 2)".
- Requirement: IPR plan rev 3, R4 (WIP valuation rules) and GL-11. The plan's basis table was "to be confirmed in
  Phase 0". This spec replaces it with what the code actually posts; §4 lists where the plan was wrong.
- Method: static trace of `origin/main` 0e670f12. Every row below was read in code. Paths are relative to
  `backend/src/main/java/com/inventoryerp/`.

## 1. The WIP account

| Topic | Code fact | Evidence |
|---|---|---|
| How many | One WIP account per organisation: `AccountMapping` key `wip`, default `1420` Work In Progress. Not per product or stage | `resources/contracts/coa-template.json`; `manufacturing/application/ManufacturingDemoSeedContributor.java:84` |
| Project WIP | Separate account `1460` (`project_wip`); not part of this check | `projects/application/ProjectCostService.java:112-114` |
| Resolution path 1 | `StockPostingService.wipAccount()` → `wipOr(stock_valuation)`: **silently falls back to the stock valuation account** when `wip` is not mapped | `inventory/application/StockPostingService.java:236-239`, `:836-842` |
| Resolution path 2 | `determination.mapped("wip")`: throws when not mapped. Used by scrap, scrap reversal, overhead absorption and its reversal, cost adjustment run, lineage | `StockPostingService.java:683-738`; `costing/application/CostAdjustmentService.java:129`; `inventory/application/CostLineageService.java:110` |
| Resolution path 3 | Posting rules `PRODUCTION_CONSUME` (Dr `wip` / Cr `stock_valuation`) and `PRODUCTION_OUTPUT` (Dr `stock_valuation` / Cr `wip`); product rule, then category rule, then org default | `kernel/common/InventoryPostingRuleDefaults.java:49-52`; `StockPostingService.java:609`, `:639`, `:669` |

Consequence for the check: the set of "WIP accounts" is **the mapped `wip` account plus every account a
`PRODUCTION_CONSUME` / `PRODUCTION_OUTPUT` rule of the organisation resolves to** (finding WIP-4).

## 2. What posts to WIP, element by element

Every manufacturing entry goes to journal `INV`. "now" = `Instant.now()`. "code" = `ProductionOrder."orderCode"`
(the journals use the **code**, not the id). "pid" = `ProductionStagePosting.id`.

### 2.1 Single-stage production order (`manufacturing/application/ProductionService.java`, `SOURCE = "production_order"`, :85)

| Element | Dr | Cr | Amount (basis) | sourceType / sourceId | Date | Evidence |
|---|---|---|---|---|---|---|
| Material consume (start) | WIP (rule `PRODUCTION_CONSUME`) — **COGS when the rule is missing** (WIP-1) | Stock | **Actual** engine issue value (`Issued.totalValue`, = ledger `totalValue` of the issue rows); summed into `materialCost` | `production_order` / code; key `inventory:issue:<org>:<ledgerCode>` | start date (`.date(date)`) | `ProductionService.java:468-475`; `inventory/application/StockService.java:865-871`; `StockPostingService.java:117-130` |
| Labour, machine, subcontract | – (no journal at the time) | – | Added to `overheadCost` (minutes × resource rate, or qty × subcontract price); refused once the order is posted | – | – | `manufacturing/application/WorkOrderService.java:203-205`, `:216-227`, `:249-254` |
| Overhead absorption, rated part | WIP (`wipAccount()`) | each work-centre rate's `creditAccountId` | Σ `ProductionCostAbsorption` (no stage) with a credit account, capped at `overheadCost` | `production_order` / code | completion date | `ProductionService.java:1054-1081` |
| Overhead absorption, remainder | WIP (`mapped("wip")`) | `overhead_absorbed` | `overheadCost` − rated part. `overheadCost` = BOM overhead + work-order labour/machine/subcontract + `CostAbsorptionService` | `production_order` / code | completion date | `ProductionService.java:1083`; `StockPostingService.java:712-722` |
| Zero yield | `stock_adjustment_expense` | WIP | `materialCost + overheadCost` (after absorbing the overhead) | `production_order` / code | completion date | `ProductionService.java:549-556` |
| By-product | Stock (rule `PRODUCTION_OUTPUT`) | WIP | **Allocated actual**: `pool × costAllocationPct / 100` of the *remaining* pool, taken first | `production_order` / code | completion date | `ProductionService.java:590-610`, `:1190-1199` |
| Abnormal rework loss | `stock_adjustment_expense` | WIP | pool − cost the reworked units already carried | `production_order` / code | completion date | `ProductionService.java:619-632` |
| Normal scrap | – | – | No posting: absorbed by dividing the pool by the **actual** produced quantity | – | – | `ProductionService.java:634-655` |
| Main output, AVCO / FIFO | Stock (rule) | WIP | **Actual**: the remaining pool | `production_order` / code | completion date | `ProductionService.java:644-659`, `:1197-1199` |
| Main output, STANDARD | Stock (rule) | WIP | **Standard** × produced qty (6 dp) | `production_order` / code | completion date | `ProductionService.java:644-659` |
| Standard variances | variance accounts 5910–5950 (by kind; remainder to yield) | WIP (`wipAccount()`) (or the reverse) | remaining pool − standard value | **`production_variance`** / code; key `inventory:production_variance:<org>:<orderId>` | completion date | `ProductionService.java:690-697`; `inventory/application/StandardCostService.java:228-271` |
| QC fail units | waste rule | Stock (quarantine) | issue value | `production_qc` / code | now | `ProductionService.java:764-768` (no WIP) |
| Cancel (in progress) | Stock (each material's `PRODUCTION_OUTPUT` rule) | WIP | each material line's consumed `totalCost` | `production_order_cancel` / code | now | `ProductionService.java:843-849`, `:1218-1247` |
| Reverse: finished goods | WIP (fgValue) | Stock (value the engine issued) ± difference to adjustment income/expense | fgValue = main output `totalCost` (fallback `outputUnitCost × qty`) | `production_reversal` / code | now | `ProductionService.java:925-941`; `StockPostingService.java:627-660` |
| Reverse: by-products | WIP | Stock | by-product `totalCost` | `production_reversal` / code | now | `ProductionService.java:943-951` |
| Reverse: materials | Stock | WIP | each line's `totalCost` | `production_reversal` / code | now | `ProductionService.java:953` |
| Reverse: overhead | `overhead_absorbed` (remainder); rated JE reversed | WIP | `overheadCost` | remainder `production_reversal` / code; rated part `reversal` (posting.reverse of the original) | now | `ProductionService.java:958-959`, `:1087-1104` |
| Reverse: rework loss | WIP | `stock_adjustment_expense` | `reworkLoss` | `production_reversal` / code | now | `ProductionService.java:961-966` |
| Reverse: variances | mirror | mirror | original | `reversal` / original JE id (`reversalOfId`) | now | `ProductionService.java:968-972`; `StandardCostService.java:303-311` |
| Reverse zero yield | materials Cr WIP; written-off amount Dr WIP; overhead mirror | | `totalCost`, absorbed = `totalCost − materialCost` | `production_reversal` / code | now | `ProductionService.java:1112-1120` |

Arithmetic check, STANDARD main output with a by-product (M material, O overhead, B by-product, R rework, S
standard value): WIP after completion = M + O − B − R − S − (M + O − B − R − S) = 0. After reversal:
+S + B − M − O + R + (M + O − B − R − S) = 0. The legs are complete.

### 2.2 Multi-stage order (`manufacturing/application/StageExecutionService.java`, `SOURCE = "production_stage"`, :93)

Crushing runs (`CrushingRunService.post`, `:583`, `:608`, `:630`; reverse `:706`), precast production
(`PrecastProductionService`, `:137`, `:244`, `:525`) and processing jobs post through this engine. **Crushing runs go
through WIP.**

| Element | Dr | Cr | Amount (basis) | sourceType / sourceId | Evidence |
|---|---|---|---|---|---|
| Material issue (`skipGl` + explicit entry) | WIP (`postProductionConsume`; rule, fallback **WIP**) | Stock | **Actual** `Issued.totalValue` | `production_stage` / pid | `:1233-1247` |
| Intermediate issue | WIP | Stock | this order's own lots at their carried cost | `production_stage` / pid | `:1249-1301` |
| Conversion with a credit account | WIP (`wipAccount()`) | the given account | entered amount | `production_stage` / pid | `:584-597` |
| Conversion without a credit account | WIP | `overhead_absorbed` | entered amount | `production_stage` / pid | `:599` |
| Pending labour at output | WIP | `overhead_absorbed` | Σ work-order components − already absorbed | `production_stage` / pid | `:1430-1449` |
| Pending BOM overhead (last stage) | WIP | `overhead_absorbed` | BOM overhead − absorbed | `production_stage` / pid | `:1450-1460` |
| Abnormal scrap | `stock_adjustment_expense` | WIP | `StageYieldCalculator` abnormal value | `production_stage` / pid | `:446-450` |
| Residue / unstocked output | `stock_adjustment_expense` | WIP | allocated value | `production_stage` / pid | `:534-538`, `:554-558` |
| Output (main, co-, by-product), not standard | Stock (rule) | WIP | **Allocated actual** (`JointCostAllocator`, shares sum to the good value) | `production_stage` / pid | `:1403-1419` |
| Output, STANDARD | Stock at **standard** | WIP | standard × qty; plus `postStageVariance`: Dr yield 5950 / Cr WIP for (allocated − standard), or the reverse | `production_stage` / pid | `:1405-1418`; `StandardCostService.java:279-298` |
| Pass-through stage | – | – | sub-ledger only (cost moves to the next stage's `transferredInCost`) | – | `:515-520` |
| QC scrap | waste rule | Stock | issue value (no WIP) | `production_stage` / new pid | `:853-856` |
| Reverse one posting | mirror of every JE | | original amounts; variance JEs reversed first, then `StockReversalService.reverseLedgerSource` mirrors every JE with `production_stage` and sourceId in (pid, ledger codes) | `production_stage_reversal` / pid, key `inventory:reversal:<org>:<jeId>`; or `reversal` / JE id | `:1058-1110` |

Stage sub-ledger: `StageExecutionService.wip(stage) = materialCost + transferredInCost + conversionCost − outputCost −
scrapCost` (`:1707-1710`). `outputCost` is the allocated (actual) value; the GL credits standard + variance =
the same actual value, so the bases agree.

### 2.3 Process costing (`manufacturing/application/ProcessCostingService.java`)

sourceId = `"<processCode>:<periodId>"`. Date = period end 12:00 UTC, or now if earlier (`:543-548`).

| Element | Dr | Cr | Basis | sourceType (key) | Evidence |
|---|---|---|---|---|---|
| Material issue (`skipGl`) | WIP | Stock | actual issue value (6 dp) | `process_costing` | `:261-276` |
| Added cost with a credit account | WIP (`wipAccount()`) | given account | amount | `process_costing` | `:296-322` |
| Added cost, record only | – | – | must cite an existing JE that put it in WIP | – | `:303-345` |
| Abnormal loss | 5970 `abnormal_process_loss` | WIP | calculator | `process_costing` (key `manufacturing:process_abnormal:<org>:<periodId>`) | `:387-397` |
| Transfer to the next process | – | – | **no journal**: WIP stays in the same account, moves between process sub-ledgers | – | `:400-417` |
| Last-process output | Stock | WIP | standard × units, else completed cost | `process_costing` | `:420-433` |
| Variance | variance accounts | WIP | completed − standard value | **`production_variance` / processCode** (key `inventory:production_variance:<org>:<periodId>`) | `:434-440`; `StandardCostService.java:263-264` |

Closing WIP is a residual (`ProcessCostCalculator`), carried into the next period's opening fields. There is no
reversal or reopen path.

### 2.4 Late costs that land in WIP (lineage)

| Element | Dr | Cr | sourceType / link to the order | Evidence |
|---|---|---|---|---|
| Landed cost, landed-cost voucher, accrual true-up whose consumed share went into an **open** order | WIP | the charge account | the landed-cost document's own sourceType; order link = `CostValueAdjustmentLine` (`target = 'wip'`, `targetRef` = order id or code, `amount − clearedAmount`) | `StockPostingService.java:406-409`, `:472-473`; `CostLineageService.java:101-113`, `:256-262` |
| Cost adjustment run (moves those shares of completed/closed orders to their outputs) | output targets (stock adjunct, COGS, WIP of a later order, scrap) | WIP | `cost_adjustment_run` / run id; key `costing:cost_adjustment:<org>:<id>`; WIP line `dimensions.productionOrderCode` | `CostAdjustmentService.java:81-97`, `:118-172` |

### 2.5 Paths that do **not** touch WIP (confirmed)

| Path | Posting | Evidence |
|---|---|---|
| Quarry extraction | Dr product stock account (+ Dr 5363 losses) / Cr 2177 royalty payable + Cr 5361 extraction cost absorbed. sourceType `quarry_extraction_log`, key `quarry:extraction:<id>`, date = extraction day. The stock receipt (`production_output`, `quarry_extraction`) posts no GL itself | `quarry/application/QuarryPostingService.java:81-117`; `quarry/application/QuarryExtractionService.java:311-320` |
| Quarry stockpile consume | `production_consume` with `skipGl`; no caller | `quarry/application/QuarryStockpileService.java:108-121` |
| Crushing stockpile survey | `StockService.adjust` (adjustment gain/loss); reversal `crushing_survey_reversal` | `manufacturing/application/CrushingSurveyService.java:218`, `:257`, `:322-338` |
| QC fail / QC scrap | waste rule from quarantine | §2.1, §2.2 |

## 3. Valuation basis, summary

| Element | Basis as posted |
|---|---|
| Material issues | Actual engine issue cost (ledger `totalValue`) |
| Labour, machine, subcontract, overhead | Absorbed amount. Single-stage: **only at completion** (zero before). Multi-stage: when entered, and pending amounts at output |
| By-products | Single-stage: allocated share of the actual pool, **also for STANDARD by-products** (WIP-2). Multi-stage: standard + variance |
| Main output | AVCO/FIFO: actual remaining pool. STANDARD: standard, residual to variance accounts (WIP side) |
| Normal scrap | Absorbed into output cost |
| Abnormal loss, zero yield, abnormal rework | Cr WIP, Dr loss account |
| Late landed costs | Into WIP while the order is open; out by the cost adjustment run after completion |
| Reversal | Every leg mirrored, at reversal time (now) |

## 4. Corrections to the plan's basis table

| Plan said | Code does | Effect on the check |
|---|---|---|
| Overhead, labour, machine, subcontract are absorbed at completion | True for single-stage. Multi-stage absorbs conversion when entered (`:584-599`) and pending labour/overhead at each output | Open multi-stage orders carry conversion in WIP; open single-stage orders carry **only materials** |
| By-products: STANDARD at standard plus variance | Single-stage: no. Multi-stage: yes | WIP still nets to zero; the gap is stock GL vs stock ledger (WIP-2) |
| Partial / multi-stage: Σ consumed + absorbed − Σ outputs | Holds per stage via `StageExecutionService.wip` | Use the stage formula |
| Quarry extraction posts directly to stock | Confirmed | The check asserts no WIP lines |
| (not in plan) | Late landed-cost shares sit in WIP until a cost adjustment run | A completed order may show a WIP balance equal to its pending shares |
| (not in plan) | Process transfers have no journal | Process costing is checked across all processes of the org, not per process |

## 5. The WIP reconciliation check

Report-only integrity kind `integrity_wip_tieout` (Phase 2, T25). Not in `INTEGRITY_VALUE_KINDS` until the owner
promotes it.

### 5.1 Scope of "WIP lines"

- Accounts: the mapped `wip` account ∪ accounts that active `PRODUCTION_CONSUME` / `PRODUCTION_OUTPUT` rules of the
  org debit or credit on the WIP side (WIP-4). If `wip` is unmapped, report `wip_unmapped` and stop (WIP-5).
- Journal entries with `status IN ('posted', 'reversed')`. Drafts are excluded.
- Amounts in base currency: `baseDebit − baseCredit`.

### 5.2 Attribution of a WIP line to a cost object

| Cost object | Journal entries |
|---|---|
| Single-stage order X | `sourceType IN ('production_order', 'production_order_cancel', 'production_reversal', 'production_variance') AND sourceId = X.orderCode`, plus `sourceType = 'reversal' AND reversalOfId` in those entries |
| Multi-stage order X (incl. crushing run, precast, processing job) | `sourceType IN ('production_stage', 'production_stage_reversal') AND sourceId IN (SELECT id FROM "ProductionStagePosting" WHERE "orderId" = X.id)`, plus `reversal` of those |
| Late costs of order X | WIP lines of entries linked through `CostValueAdjustmentLine` / `CostValueAdjustmentRelease` (`target = 'wip'`, `targetRef IN (X.id, X.orderCode)`), and `cost_adjustment_run` WIP lines with `dimensions->>'productionOrderCode' = X.orderCode` |
| Process costing (org total) | `sourceType = 'process_costing'`, plus variance entries with `postingKey LIKE 'inventory:production_variance:<org>:%'` whose period is a `ProcessCostPeriod` (WIP-7) |
| Unattributed | every other WIP line. Expected empty |

### 5.3 Expected balance (sub-ledger, same basis as the journals)

| Cost object state | Expected GL WIP | Tolerance |
|---|---|---|
| Single-stage `draft` / `confirmed` | 0 | 0.01 |
| Single-stage `in_progress` | `materialCost` (overhead is not yet in WIP) + Σ partial `overheadAbsorbed` − Σ partial `wipCredit` (Phase 2, X-9) + open late-cost shares | 0.01 |
| Single-stage `qc_hold`, `completed`, `closed` | 0 + open late-cost shares not yet run | 0.01 |
| Single-stage `cancelled` (before or after start), reversed | 0 | 0.01 |
| Multi-stage order, any open state | Σ over its stages of `StageExecutionService.wip(stage)` + open late-cost shares | 0.01 |
| Multi-stage order completed / reversed / cancelled | 0 + open late-cost shares (completed only) | 0.01 |
| Process costing (all processes) | Σ open periods (`opening*Cost` + Σ `ProcessCostEntry.amount`) + for processes with no open period, the last closed period's `closingWipCost` | 0.01 per process |
| Quarry extraction | **no WIP line** with sourceType `quarry_extraction*` | exact |
| Unattributed | 0 | exact |
| Org total | GL WIP balance = Σ all rows above | 0.01 × number of objects, max 1.00 |

Open late-cost share = `CostValueAdjustmentLine.amount − clearedAmount` with `target = 'wip'`, adjustment not
reversed, and no posted `CostAdjustmentRunLine` (the same predicate as `CostAdjustmentService.pending`,
`:81-97`, but for every order status). (Phase 2) The release rows (`CostValueAdjustmentRelease`, target `wip`) count
the same way. After the WIP-3 fix the cost adjustment run also takes the shares of `cancelled` (cancelled or
reversed) orders, so an open share of such an order is a reconciling item until the next run, not a finding. The
built check reports late-cost shares and every WIP line it cannot attribute as one object, `late_cost_and_other`
(expected = Σ open shares), because a lineage share's `targetRef` is not reliably the order (open point below).

### 5.4 SQL sketch (single-stage orders)

```sql
WITH wip_accounts AS (
  SELECT "accountId" AS id FROM "AccountMapping"
  WHERE "organizationId" = :org AND key = 'wip' AND "deletedAt" IS NULL
  -- UNION the WIP-side accounts of active PRODUCTION_CONSUME / PRODUCTION_OUTPUT rules (WIP-4)
),
order_je AS (
  SELECT o.id AS order_id, je.id AS je_id
  FROM "ProductionOrder" o
  JOIN "JournalEntry" je ON je."organizationId" = o."organizationId"
   AND je."sourceType" IN ('production_order','production_order_cancel','production_reversal','production_variance')
   AND je."sourceId" = o."orderCode"
  WHERE o."organizationId" = :org
    AND NOT EXISTS (SELECT 1 FROM "ProductionOrderStage" s WHERE s."orderId" = o.id)
    AND je.status IN ('posted','reversed')
),
order_je_all AS (
  SELECT order_id, je_id FROM order_je
  UNION
  SELECT oj.order_id, r.id FROM order_je oj
  JOIN "JournalEntry" r ON r."reversalOfId" = oj.je_id AND r."sourceType" = 'reversal'
   AND r.status IN ('posted','reversed')
),
gl AS (
  SELECT oj.order_id, SUM(jl."baseDebit" - jl."baseCredit") AS gl_wip
  FROM order_je_all oj JOIN "JournalLine" jl ON jl."journalEntryId" = oj.je_id
  WHERE jl."accountId" IN (SELECT id FROM wip_accounts)
  GROUP BY oj.order_id
),
late AS (
  SELECT o.id AS order_id, SUM(x.amount - x."clearedAmount") AS open_share
  FROM "CostValueAdjustmentLine" x
  JOIN "CostValueAdjustment" a ON a.id = x."adjustmentId" AND a."reversedAt" IS NULL
  JOIN "ProductionOrder" o ON o."organizationId" = x."organizationId"
   AND (o.id = x."targetRef" OR o."orderCode" = x."targetRef")
  WHERE x."organizationId" = :org AND x.target = 'wip'
    AND NOT EXISTS (SELECT 1 FROM "CostAdjustmentRunLine" r WHERE r."adjustmentLineId" = x.id AND r.status = 'posted')
  GROUP BY o.id     -- plus CostValueAdjustmentRelease the same way
)
SELECT o."orderCode", o.status,
       COALESCE(g.gl_wip, 0) AS gl_wip,
       CASE WHEN o.status = 'in_progress' THEN o."materialCost" ELSE 0 END
         + CASE WHEN o.status IN ('in_progress','qc_hold','completed','closed') THEN COALESCE(l.open_share, 0) ELSE 0 END
         AS expected
FROM "ProductionOrder" o
LEFT JOIN gl g ON g.order_id = o.id
LEFT JOIN late l ON l.order_id = o.id
WHERE o."organizationId" = :org
  AND ABS(COALESCE(g.gl_wip, 0) - (CASE WHEN o.status = 'in_progress' THEN o."materialCost" ELSE 0 END
         + CASE WHEN o.status IN ('in_progress','qc_hold','completed','closed') THEN COALESCE(l.open_share, 0) ELSE 0 END)) > 0.01;
```

The late-cost entries themselves carry the landed-cost sourceType; their WIP lines are added to `gl` through the
`CostValueAdjustment` → journal link of each adjustment. The multi-stage query is the same with the stage
attribution of §5.2 and `expected = Σ wip(stage)` for open orders. The org-total line closes the loop and catches
unattributed WIP lines.

## 6. Findings (code bases that contradict each other)

| Id | Severity | Finding | Evidence | Failure scenario |
|---|---|---|---|---|
| WIP-1 (= GL-4) | P1 | Single-stage consume posts through `StockService.issue` → `postIssue`. With no `PRODUCTION_CONSUME` rule it falls back to `fallbackLinesIssue`, which debits **COGS**. `postProductionConsume` (used by stages and process costing) falls back to WIP | `StockPostingService.java:117-120`, `:792-804` (Dr cogs `:801-803`); compare `:609-616` | Rule inactive. Start: Dr COGS 500 / Cr Stock 500. Complete: Dr FG 500 / Cr WIP 500. WIP −500, COGS +500. Cancel instead: same −500 |
| WIP-2 (= GL-2) | P1 | Single-stage STANDARD by-product: the GL takes the by-product at its pool share, the stock ledger at its standard (`StockService.receive` uses the standard for STANDARD products). No variance is posted (the multi-stage path posts one) | `ProductionService.java:597-609`, `:1196-1199`; `StockService.java:311-318`, `:360-363`; compare `StageExecutionService.java:1414-1418` | Pool 1000, by-product 10 % = 100, 40 kg at standard 5. GL Dr Stock 100 / Cr WIP 100; ledger 200. WIP nets to 0 (main output takes 900); stock GL is 100 below the ledger. Reversal issues at 200 against wipValue 100: 100 to adjustment expense, stock gap remains |
| WIP-3 | P1 | Late-cost WIP shares of a reversed or cancelled order stay in WIP for ever. `CostAdjustmentService.pending` only picks completed/closed orders; no reversal or cancel code touches `CostValueAdjustment*` | `CostAdjustmentService.java:88`, `:93`; `ProductionService.java:843-849`, `:920-972` | Landed cost 30 arrives while the order is in progress (Dr WIP 30). Order completes and is reversed before any run: 30 stranded in WIP |
| WIP-4 | P2 | WIP can split across accounts: consume/output/reversal follow product/category rules, while overhead, scrap and variances always use the mapped `wip` | `StockPostingService.java:609`, `:669` vs `:683-738` | Category rule debits 1421 for raw materials. Start: 1421 +500; complete: 1420 −500. Each account shows a residual; the total is 0 |
| WIP-5 | P2 | Missing `wip` mapping: consume/output fall back to stock valuation (no WIP at all) and `wipAccount()` returns stock valuation, but overhead and scrap legs throw | `StockPostingService.java:236-239`, `:836-842`; `accounting/application/AccountResolverService.java:86-92` | No `wip` mapping: an order with overhead or zero yield cannot complete; one without posts Dr SV / Cr SV |
| WIP-6 | P2 | The existing WIP controls give false drift. `ProductionReportService.wipReconciliation` expects `materialCost + overheadCost` for `in_progress` **and `qc_hold`** single-stage orders, but overhead reaches WIP only at completion and a `qc_hold` order has already cleared WIP. `StageWipReportService` gets single-stage orders right (material only, `in_progress` only, `:69-73`) and uses the stage formula for staged orders. Neither report counts late-cost shares, filters journal status, or uses base amounts | `manufacturing/application/ProductionReportService.java:50-85` (`:61-63`, `:74-75`); `StageWipReportService.java:43-46` | In-progress order (material 500, overhead 50) + qc_hold order (total 300): expected 850, GL 500, drift −350 shown |
| WIP-7 | P2 | Process-costing variance JEs carry sourceId = process code (same for every period), not `code:periodId`; a `process_costing` filter misses their WIP leg | `ProcessCostingService.java:435`; `StandardCostService.java:263` | Select by `postingKey` instead (§5.2) |
| WIP-8 | P2 | Legacy reversal rounding: an output row without `totalCost` is reversed at `outputUnitCost` (6 dp) × qty | `ProductionService.java:927-928` | Pool 1,000,000, qty 300,000: 3.333333 × 300,000 = 999,999.90, WIP residual 0.10. New orders store `totalCost`, so only old rows |

Open points (not determined in the trace): whether every writer of `CostValueAdjustmentLine.targetRef`
(`LineageFollower`) stores the order id or code for stage orders; multi-stage work-order labour completed after all
stages closed is recorded as a cost component but never posted (no WIP imbalance, cost never absorbed).

## 7. Tests (Phase 2, T25)

`ManufacturingLifecycleIT` (built in Phase 2 on an isolated organisation, `InventoryAuditSupport`, with the
MfgTestSupport fixture patterns; DEMO's shared GL cannot be asserted green) runs each R4 scenario and asserts after every step: integrity green, GL
tie-out green, and this check green with the expected balance of §5.3. Regression ITs for WIP-1, WIP-2 and WIP-3
must be red on `origin/main` first.
