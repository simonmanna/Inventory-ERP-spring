# Integrated Cost Management: Revised Design and Phased Plan (rev 3)

> **Rev 3 status.** The architecture and roadmap are approved in principle. Rev 3 changes only the sections the owner's
> eight clarifications affect:
>
> - R3-1: landed cost on STANDARD items
> - R3-2: over-absorption
> - R3-3: quarry provisions and area dimensions
> - R3-4: onerous contracts
> - R3-5: retentions
> - R3-6: allocation-engine exceptions
> - R3-7: AVCO lineage
> - R3-8: the three-layer cost model
>
> Everything else is unchanged. **No code and no migrations until the owner reviews rev 3.** After that, one
> independently tested PR at a time, with owner approval before each next phase.

## Context

The owner approved the CM-0…CM-10 roadmap in principle: keep the existing engines and add shared cost primitives.
Rev 2 adds ten owner requirements:

1. IFRS compliance is enforced in code, not left as free configuration.
2. The material ledger keeps lineage and never becomes a competing valuation engine.
3. Allocations can be financial or statistical.
4. Fleet covers owned and third-party carriers, with full cost pools and empty return trips.
5. Projects become full commercial contracts.
6. Branch and location dimensions are mandatory; same-entity branch transfers are separate from intercompany.
7. Actual, standard and estimated profitability stay separate and reconcile to the GL, including unallocated costs.
8. A future non-posting cost simulation engine.
9. Accounting and period-close regression tests grow phase by phase.
10. Effort estimates are realistic and integrity is never traded for speed.

**Process rule:** no code until this revised design and dependency map are reviewed. Then implement **one
independently testable phase at a time**. Each phase is one PR. It closes only when its exit gate passes, and the
owner reviews it before the next phase starts.

**Owner decisions kept from rev 1:**

- Priority after the foundation: quarry, crushing and blocks.
- Full Project entity.
- The legacy `/procurement/landed-costs` route stays, but delegates to the voucher engine.

**Numbering:**

- ADRs start at **ADR-S031**.
- Migrations start at **V223**.
- Commits use `feat(costing): CM-n …`.

---

## Part A: Current-state assessment (summary of the three audits)

Classification:

- **1** = implemented and tested
- **2** = incomplete
- **3** = not integrated (analytic only, or no GL/UI)
- **4** = designed only
- **5** = missing

| Area | Class | Key evidence / gap |
|---|---|---|
| Landed cost | 1 voucher / 2 legacy | `LandedCostVoucherService` with `LandedCostVoucherIT` ×8. Legacy `LandedCostService` has no reversal. A STANDARD item's landed cost goes to COGS (`LandedCostLayers:99`). No accrual or true-up. |
| Valuation (perpetual) | 1 | `StockPostingService` → `InventoryPostingRuleService`, `InventoryValuationService.glTieOut`. |
| Cost lineage | 2 | FIFO layers live in `InventoryBatch`, but there is no issue→layer consumption record. Cost-only changes write no `InventoryLedger` row. |
| Std / FIFO / AVCO / SPECIFIC | 1 / 1 / 1 / 2 | `CostResolver`, `StandardCostService`, `InventoryCostPolicyService`. The SPECIFIC branch is unreachable. |
| NRV write-down (IAS 2.28) | 5 | none |
| Manufacturing WIP / process / joint / yield | 1 | `ProductionService`, `StageExecutionService`, `ProcessCostingService`, `JointCostAllocator`, `StageYieldCalculator` |
| Variances | 1 / 2 / 3 | Three engines that disagree: `CostingService.recordVariances` (posts to GL), the stage engine (one lump to 5950), and `VarianceCalculator` (read-only). |
| Absorption | 1 absorb / 5 settle | `CostAbsorptionService`. Nothing applies the IAS 2.13 normal-capacity rule. |
| Cost centres | 1 master + rules / 2 allocation | `CostAllocationService` is single-step with static weights, and its `driver` field is dead. |
| ABC (V222) | 1 analysis / 3 integration | No GL posting. Cost-centre filter is exact-match only. COGS comes from `costPrice`. |
| Material ledger (V222) | 1 standalone / 2 integrity | Posts the 5965 difference without a ledger row or lineage. Single stock account. |
| Quarry / crushing / precast | 1 (link 2) | `QuarryCostCalculator`, `CrushingRunService`, `PrecastReportService`. Quarry feeds crushing only through stock. No IFRIC 20. No IAS 37 rehabilitation. |
| Fleet | 1 analytic / 3 GL | `FleetCostCalculator`, `FleetFreightBillService`. No cost per m³. No empty-leg policy. Delivery cost never reaches margin. |
| Branch | 2 | `costCenterId` is everywhere. Branch/location dimension is not enforced. No per-branch accounts. Intercompany (PR-8) mirrors bills only. |
| Projects | 5 | Free-text `projectId` (V152, V154). No entity. |
| Profitability | 2 / 3 | Six separate estimate reports. They don't tie to the GL and have no unallocated bucket. |
| Settings | 2 | Scattered. Two services write the default costing method. Hard-coded fallback accounts. |
| Period close | 1 lock / 2 checklist | `PeriodCloseChecklistService` has no costing gates. No cost adjustment run. |

## Part B: Gaps → phase

| Gap | Phase |
|---|---|
| G1 ledger/lineage integrity; G2 STANDARD landed cost; G14 posting dates; G17 material ledger dims; NRV write-down | CM-1 |
| G12 settings and accounts; policy governance; branch/location dimension enforcement | CM-2 |
| G4 allocation duplication; financial + statistical allocation | CM-3 |
| G5 variance/standard unification; G6 IAS 2.13 absorption settlement; G7 rates | CM-4 |
| G8 quarry→crusher link; IFRIC 20; IAS 37 rehabilitation; per-tonne/per-unit KPIs | CM-5 |
| G3 legacy landed cost; G15 bill tax / FX; landed-cost accrual | CM-6 |
| G9 fleet/distribution; G16 branch transfers vs intercompany | CM-7 |
| G10 projects (core) | CM-8a |
| Project commercial: IFRS 15, retentions, claims, advances, subcontractors, variations, IAS 37 onerous contracts | CM-8b |
| G11 profitability (actual / standard / estimated) | CM-9 |
| G13 close cockpit; cost adjustment run | CM-10 |
| What-if simulation | CM-11 (future) |

---

## Part C: Target architecture (revised)

### C0. Three-layer cost model (R3-8)

The system distinguishes three layers. They stay separate, are each traceable, and reconcile through one bridge.
**No operational expense is assumed to be capitalisable.**

| Layer | What it holds | Owner modules | Effect on the GL |
|---|---|---|---|
| **L1 Operational cost capture** | Quantities and operational facts: fuel litres, meter/machine hours, crew time, timesheets, trip km (loaded/empty), weighbridge tonnes, blasts, workshop jobs, curing days. Valued at *rates* only for management information | fleet, quarry, crushing, precast, hr, manufacturing shop floor | None by itself. It is a driver source for L2 and for allocations |
| **L2 Inventory / product costing** | Decides which costs are **eligible** for inventory (IAS 2 gate, AD-10). Builds unit costs, cost layers, WIP, FG and lineage. Absorbs eligible costs through rates/allocations, and settles rate vs actual | inventory (valuation authority), manufacturing, procurement (landed cost), quarry/crushing posting, costing (settlement) | Moves *eligible* amounts from period expense into inventory. Typical entry: Dr stock/WIP, Cr absorbed or contra account. Never creates cost that has no L3 source |
| **L3 Financial accounting** | The *actual* amounts, by nature: bills, payroll, depreciation, fuel purchases, provisions, revenue. Source of truth | accounting, invoicing, hr, fixedasset, procurement | Journals from source documents |

**Cost-flow bridge** (built up gradually: production in CM-4, quarry in CM-5, fleet in CM-7, complete in CM-10).
Per period and per cost element:

```
L3 actual cost by nature
  = capitalised into inventory (absorbed/allocated, eligible only)
  + expensed directly (ineligible: selling, distribution to customer, admin, abnormal waste, idle capacity)
  + rate-vs-actual settlement (under/over; treatment per AD-11)
  + unallocated (explicit bucket, must be explained at close)
```

Each line drills down: L1 record → absorption/allocation line → journal line → L3 source document.

**Rule:** a rate × driver amount from L1 is an **estimate**. Only L3 amounts, passed through the AD-10 eligibility
gate, can end up in inventory. The difference is always settled (AD-11) and never left in stock unexplained.

### C1. Layers and ownership

```
┌──────────────────────── costing (cross-cutting owner) ─────────────────────────────────────┐
│ Policy: AccountingPolicyService (governed elections)  CostingSettingsService (façade)        │
│ Allocation: AllocationCalculator (pure) · DriverProviders · CostCycleService                  │
│             postingMode = FINANCIAL (GL) | STATISTICAL (memo, no GL)                          │
│ Actual costing: MaterialLedgerService (reads lineage, posts price differences only)           │
│ Period: AbsorptionSettlement · NrvReview · CostAdjustmentRun · CostCloseService               │
│ Analytics: ProfitabilityFact (ACTUAL | STANDARD | ESTIMATE layers) · CostKpiService            │
│ Future: SimulationService (sandbox, reuses pure engines, never writes ledgers)                │
└─────▲──────────────▲──────────────▲──────────────▲──────────────▲──────────────▲────────────┘
  inventory        manufacturing   procurement     quarry/crush/    fleet          projects (new)
  (ONLY valuation  (orders, stages, (landed cost)  precast         (owned+3rd     (contracts,
   authority +     process, joint)                 (IFRIC20/IAS37)  party)         IFRS 15)
   cost lineage)
                         accounting: PostingService · JournalLine dims (branch, location, costCenter, project) · FiscalPeriodControl
```

### C2. Architecture decisions (each becomes an ADR section)

**AD-1: A single valuation authority.**

- Only the `inventory` module may change stock quantity or cost: `StockItem`, `InventoryBatch`,
  `InventorySerial`, `InventoryLedger`, plus the new lineage tables.
- Every other module (landed cost, standard activation, material ledger, revaluation, NRV, cost adjustment) calls
  `InventoryValuationApi.applyValueAdjustment(...)`.
- ArchUnit rule: no class outside `inventory..` writes those tables.

**AD-2: The cost lineage ledger.** These tables are append-only.

- `InventoryCostLayer`: one row per receipt or production output. Holds origin document, quantity, original unit
  cost, location, branch and lot.
- `CostLayerConsumption`: one row per issue. Records which layer was consumed, quantity, cost, the consuming document
  and its target (COGS, WIP order, project, transfer).
- `CostValueAdjustment`: holds layer, adjustment type, amount, on-hand share, consumed share, source document and
  journal entry. Adjustment types: `landed_cost | revaluation | nrv | standard_change | ml_price_diff |
  cost_adjustment | policy_migration`.

AVCO keeps its running average for issues, and its lineage follows AD-2a. FIFO consumption is exact: one
consumption row per layer touched. SPECIFIC: the serial is the layer. STANDARD follows AD-2b. The original layer
cost is **never overwritten**; the current cost equals the original plus the sum of the adjustments.

**AD-2a: AVCO lineage and distribution of later adjustments (R3-7).**

*Model.* AVCO treats each (product, variant, location) pool as a homogeneous mix, so every issue depletes every
layer still in the pool **pro rata**. Instead of writing one row per (issue × layer), the pool keeps an
append-only event chain, `AvcoPoolEvent`:

- `seq`, type (receipt / issue / transfer-in / transfer-out / adjustment / reset)
- `qtyBefore`, `qty`
- `S` = cumulative survival factor

The factor works as follows:

- On a receipt: `S` is unchanged.
- On an issue of `q` from a pool of `Q`: `S_i = S_{i-1} × (1 − q/Q)`.
- When the pool reaches 0 (or goes negative): an *epoch reset*. Layers from earlier epochs are fully consumed.
- Each receipt layer stores `S_at_receipt` and its `epoch`.
- Each issue writes **one** `CostLayerConsumption` row against the *pool event*, with qty, cost, target and
  `S_before`/`S_after`.

*Distribution of a later adjustment `A` to layer X* (landed cost, revaluation, price difference), received at
survival `S_k`:

| Destination | Share |
|---|---|
| Each issue `i` after X in the same epoch | `A × (S_{i-1} − S_i) / S_k` → that issue's target (COGS, WIP order, project WBS, transfer) |
| Still on hand | `A × S_now / S_k` → added to the pool value (the running average rises by `share / qtyOnHand`) |
| Pool reset to 0 since X | No on-hand share; everything goes to the issues |
| Negative-stock epoch | Shares go to COGS, matching today's `CostResolver:128` rule |

*No double counting:*

1. The shares telescope, so Σ issue shares + on-hand share = `A` exactly. Rounding uses `LARGEST_REMAINDER`, and the
   remainder goes to on-hand (or to the last issue when on-hand is 0).
2. Every distribution writes `CostValueAdjustmentLine(adjustmentId, consumptionId | poolOnHand, amount)` with a
   unique key `(adjustmentId, consumptionId)`. Re-running is a no-op; reversing writes the exact negatives.
3. An adjustment distributes **only its own amount**. Earlier adjustments are already in the layer's adjusted cost
   and in the issue costs, and are never redistributed.
4. Transfer-out targets pass the share to the destination pool as a new adjustment on the transfer-in layer, which
   recurses with the same rule. Depth is capped at 5; any remainder beyond the cap goes to the destination's COGS and
   is flagged for the CM-10 run.
5. WIP targets: if the order is open, the share goes into order cost (Dr WIP). If the order is completed, the share
   follows the output layer as a new adjustment (FG on hand vs sold), using the same recursion and the same depth
   cap.

*Back-compat:* today's `LandedCostLayers` on-hand/consumed split for AVCO differs from this. Both give the same
total, but the split differs. The ADR documents the change, and a golden test shows the new split per scenario. The
switch is a CM-1 deliverable that needs owner approval.

**AD-2b: Price differences on STANDARD items (R3-1).**

STANDARD stock is carried at standard *plus a price-difference component held per layer*. IAS 2.21 allows standard
cost only if it approximates actual, so price differences relating to stock still held cannot be expensed.

1. A landed charge (or any later actual-cost difference) on a STANDARD receipt layer X creates a
   `CostValueAdjustment(type=std_price_difference)`. It is distributed through **lineage**, not straight to PPV:

   | Where X's quantity is now | Posting |
   |---|---|
   | Still on hand (raw material) | Dr **inventory price difference** (balance-sheet adjunct account, mapped per stock account, `valuationComponent='price_difference'` on the value-only ledger row) / Cr clearing |
   | Consumed into an **open** production order / process period | Dr **WIP price difference** for that order. It joins the order's actual cost and drops out as material price variance at completion (CM-4 variance engine), which is then split as in the next rows |
   | Consumed into a **completed** order whose output is on hand | Dr **FG price difference** on the output layer (AD-2a-style recursion over the output's issues) |
   | Output or raw material **sold, scrapped or expensed** | Dr COGS (price variance sub-account), or the scrap/abnormal account if the consumption target was abnormal loss |
   | Consumed into a project | Dr project WIP/cost on the WBS |

2. The standard unit cost **does not change**. The carrying amount = standard + price-difference adjunct. The
   valuation report and `glTieOut` include the adjunct accounts.
3. When the item is later issued, the adjunct is released in proportion to the quantity issued (the AD-2a mechanism
   applied to the adjunct balance per layer), following the issue target.
4. Tolerance check: if the price difference on a layer exceeds the elected tolerance (governed, default 5 %) of the
   standard, it raises a *standard review* flag (IAS 2.21). The CM-10 close gate requires a standard revision or a
   documented approval.
5. Multi-level roll-up beyond one production step is done by the CM-10 material ledger (AD-3), reading the same
   lineage. CM-1 handles one step at posting time.
6. PPV on the *purchase price* at receipt (`StandardCostService.postReceipt`, 5905) keeps today's behaviour for
   back-compat. An **election** (AD-4, prospective) moves PPV onto the same lineage split. Default for new orgs: on.
   Existing orgs keep their current behaviour until they elect.

**AD-3: The material ledger is a consumer, not a valuer.**

- It reads lineage for STANDARD items, computes the periodic actual price difference and rolls it along consumption
  chains, single- or multi-level (raw material → WIP → finished goods → COGS).
- It posts *only* the differences, through AD-1, as `ml_price_diff` adjustments.
- It never sets a unit cost directly and never runs for AVCO or FIFO items. Those use the cost adjustment run
  (CM-10) instead.

**AD-4: Policy governance instead of free configuration.**

- Accounting treatments are **elections within what each standard permits**. They are stored in `AccountingPolicy`
  with an effective date, maker-checker approval, a mandatory IAS 8 justification text and an audit record.
- Changes apply prospectively. Retrospective changes are only allowed through a controlled restatement, which is
  out of scope.
- Choices the standards forbid cannot be expressed in the system, and the validation rules enforce this (see C3).
- Operational settings, such as default allocation basis or UI toggles, stay freely configurable.

**AD-5: Financial vs statistical allocation.**

- Each allocation cycle or segment has a `postingMode`:
  - `FINANCIAL` creates `AllocationDocument` + `AllocationLine` + a journal entry, is reversible, and respects
    period locks.
  - `STATISTICAL` writes `AllocationLine` with `journalEntryId = null`. Reports can consume it, but it never touches
    the GL and is excluded from GL reconciliation by construction.
- Statistical keys (m², headcount) are separate from statistical allocations.

**AD-6: Branch and location as mandatory dimensions.**

- `JournalLine.branchId` is required on every costing-originated line. `locationId` is required on stock lines.
- This is enforced through the V217 dimension rules, using `CostCenterRulesService` and the dimension framework.
- The GL account structure is configurable:
  - a shared account plus dimension (default), or
  - per-branch or per-location accounts, set through `InventoryPostingRule.branchId` / `locationId`.
- **A same-entity branch transfer** is an internal stock transfer:
  - no revenue, no AR/AP;
  - optional in-transit account;
  - optional inter-branch clearing (due-to/due-from per branch), so each branch's trial balance can balance;
  - cost moves at carrying amount plus capitalisable freight (IAS 2.10).
- **Intercompany** means a different legal entity. It goes through the intercompany module (PR-8): sale and purchase
  documents, a transfer price, and unrealised-profit elimination on consolidation. The system rejects intercompany
  documents between branches of the same entity, and branch transfers across entities.

**AD-7: Three separate profitability layers.**

- `ProfitabilityFact.layer ∈ {ACTUAL, STANDARD, ESTIMATE}`. Layers are never summed or mixed in one figure.
  - **ACTUAL** = booked GL only.
  - **STANDARD** = quantity × standard cost/price.
  - **ESTIMATE** = budget, plans and simulation.
- The ACTUAL reconciliation is exact and must equal the GL P&L:
  Σ segment facts + **unallocated** costs (P&L balances not assigned to any segment) + **out-of-scope** P&L accounts
  (listed explicitly).
- Statistical allocations may enrich ACTUAL views, but they are flagged and excluded from the GL reconciliation.

**AD-8: Pure engines, ready for simulation.**

- These take a `CostContext` (rates, prices, capacities, FX) and have no side effects: `AllocationCalculator`,
  `VarianceCalculator`, `JointCostAllocator`, the roll-up, `ProcessCostCalculator`, `FleetCostCalculator`,
  `QuarryCostCalculator` and the KPI service.
- Posting services wrap them. CM-11 can then run what-if scenarios by supplying a different context with no
  database writes.

**AD-9: Traceability contract.**

- Every costing journal line carries `sourceType`/`sourceId`, resolvable to a document.
- Every allocation or adjustment line points to its source transaction and its journal entry (or none, if
  statistical).
- A generic traceability test checks this across all scenario ITs.

**AD-10: Cost eligibility gate (R3-8).**

- Every cost element is classified by `CostEligibility`. The classification is governed (AD-4), held per GL account
  / cost component / cost pool, and can be narrowed by cost centre:

  | Class | Meaning |
  |---|---|
  | `production_capitalisable` | Normal production cost |
  | `production_abnormal` | Abnormal waste or idle capacity → always expensed |
  | `acquisition_capitalisable` | Duty, freight-in, handling |
  | `transfer_capitalisable` | Bringing goods to the location of sale (IAS 2.10), e.g. inter-branch freight |
  | `distribution` | Delivery to the customer: selling cost, expensed |
  | `selling` | Expensed |
  | `admin` | Expensed |
  | `storage` | Expensed, unless the storage is necessary in the production process |
  | `financing` | Expensed |

- Absorption, allocation into inventory and landed-cost capitalisation are **rejected** for any source not
  classified as capitalisable.
- Every capitalising line records its eligibility class and the L3 source.
- Typical defaults:
  - quarry haulage from face to crusher → `production_capitalisable`
  - delivery truck to a customer site → `distribution`
  - curing-yard handling → `production_capitalisable`
  - finished-goods yard storage → `storage`

**AD-11: Settlement of absorbed vs actual (R3-2).** Defined in CM-4. The treatment depends on the *cause*, never on a
blanket pro-rata.

### C3. Compliance guardrails, enforced in code

**IAS 2 Inventories**

| Rule | Enforcement |
|---|---|
| Cost = purchase + conversion + other costs to bring to present location/condition (2.10–2.11) | Each `CostComponent.kind` has an allowed set of treatments. Duty, freight-in, insurance and handling are capitalised. Recoverable taxes go to input tax. |
| Excluded costs (2.16): abnormal waste, storage (unless needed in production), admin, selling | Validation rejects `capitalize` for kinds `demurrage`, `storage`, `admin` and `selling`. Abnormal loss is always expensed (`StageYieldCalculator`). |
| Fixed overhead absorbed on **normal capacity** (2.13) | `WorkCenterCostRate` requires a validated `normalCapacity` (CM-4). Under/over-absorption is decomposed by cause and treated per the AD-11 table: volume, spending, rate error, timing, misposting. Low-volume under-absorption is always expensed. Favourable differences reduce **only** the inventory produced in the period, traced through lineage. No blanket pro-rata. |
| Only eligible costs capitalised (2.10–2.16) | AD-10 eligibility gate on every capitalising line |
| Cost formulas: FIFO or weighted average; specific identification for non-interchangeable items; same formula for items of similar nature (2.23–2.25) | No LIFO enum is accepted. The method is set per category through `InventoryCostPolicy`. Changes go through AD-4 governance with an IAS 8 justification. Serial items must use SPECIFIC. |
| Standard cost allowed only if it approximates actual (2.21) | Price differences go through lineage to on-hand, WIP, FG or sold (AD-2b), not straight to P&L. A layer above tolerance raises a standard-review flag. Each standard version has `reviewDueDate`. The CM-10 gate blocks the close until flags are resolved. |
| NRV write-down and reversal (2.28–2.33) | Periodic NRV review via `NrvReviewService`. The write-down posts to an inventory allowance. Reversal is capped at the original write-down. |
| Joint / by-products (2.14) | Joint products use a rational, consistent basis. By-products use NRV carve-out (`JointCostAllocator`). The method is locked per process through AD-4. |

**IFRIC 20 Stripping costs (surface mining, production phase)**

| Rule | Enforcement |
|---|---|
| Stripping benefit realised as inventory → IAS 2 | Default: the cost goes into extraction cost per tonne. |
| Stripping asset recognised only if (a) future benefit is probable, (b) the ore-body component is identifiable, (c) cost is measurable | `QuarryStrippingAsset` requires an identified component (bench/zone), an expected tonnage and an approved assessment. The system rejects it otherwise. |
| Cost split by a relevant production measure (ratio) when inventory and asset are mixed | Allocation basis = actual vs expected waste-to-ore ratio for the component. |
| Depreciated by units of production over the identified component | `StrippingAmortisation` per tonne extracted from that component. |

**IAS 37 Provisions**

| Rule | Enforcement |
|---|---|
| Four distinct rehabilitation movements (R3-3), each its own document type, provision layer and GL route | The four types are listed below this table; the CM-5 rehabilitation movement matrix gives the full detail |
| Utilisation | Actual rehabilitation spend: Dr provision / Cr AP or cash, tracked per area |
| Onerous contracts (R3-4) | A full IAS 37.66–69 / 68A unavoidable-cost assessment, after impairing dedicated assets first. **EAC > revenue alone never triggers a provision.** See the CM-8b onerous-contract assessment |

The four rehabilitation movements:

1. **Initial** (site establishment, development disturbance) → Dr rehabilitation asset (IAS 16.16c) / Cr provision
   at present value.
2. **Unwinding** → finance cost. Never capitalised into inventory (IAS 23 excludes repetitively produced
   inventories).
3. **Production disturbance** (IAS 16.18 → IAS 2) → Dr extraction cost (inventory) / Cr provision, measured per area
   from the disturbance actually caused.
4. **IFRIC 1 remeasurement** → adjusts the asset. Any excess decrease goes to P&L; an increase triggers an IAS 36
   impairment indicator check. The part that relates to production-disturbance layers goes to P&L.

**IFRS 15 Revenue (projects / contracts)**

| Rule | Enforcement |
|---|---|
| Over-time recognition only if criteria 35(a/b/c) are met | Each project records a criteria assessment. Without it, recognition is point in time and cannot be overridden. |
| Input method: cost-to-cost, excluding costs that don't depict progress (uninstalled materials, inefficiencies) | Cost lines flagged `excludeFromProgress`. Uninstalled materials stay in project inventory. |
| Contract modifications (variations) | Approved variation → modification. It is accounted for as a cumulative catch-up or a separate contract according to the 18–21 decision tree, captured on the variation. Unapproved/pending variations are excluded from the transaction price, unless they qualify as variable consideration under the constraint. |
| Contract asset / liability, advances | Unbilled revenue → contract asset. Billing in excess → contract liability. Customer advances → contract liability, recovered through claims. Presented net per contract. A significant-financing-component check exists: retention for protection is not financing (IFRS 15.62(c)); advances follow the ≤ 12-month expedient or are assessed |
| Retentions (R3-5): conditional vs unconditional (IFRS 15.105–108, IFRS 9) | Conditional retention → **contract asset (retention)**. It becomes a **receivable** only when the right is unconditional (passage of time only). Expected credit loss (IFRS 9) applies to both. Subcontractor retention → **financial liability** (retention payable), not deferred cost. See the CM-8b retention matrix |

---

## Part D: Dependency map (target, revised)

```
                     CM-0 Stabilise
                          │
                     CM-1 Valuation integrity + cost lineage + NRV   (AD-1/2/9)
                          │
                     CM-2 Policy governance + settings + branch/location dims (AD-4/6)
                          │
                     CM-3 Allocation engine financial+statistical (AD-5/8)
          ┌───────────────┼───────────────┬────────────────┐
     CM-4 Mfg unification │          CM-6 Landed cost    CM-8a Project core
          │               │               │                │
     CM-5 Quarry/blocks   CM-7 Fleet & distribution + branch transfers
     (IFRIC 20/IAS 37)    │                                CM-8b Contracts (IFRS 15/IAS 37)
          └───────────────┴───────┬───────────────────────┘
                     CM-9 Profitability (ACTUAL/STANDARD/ESTIMATE)  (AD-7)
                                  │
                     CM-10 Close cockpit + cost adjustment run + material ledger rework (AD-3)
                                  │
                     CM-11 Cost simulation (future, non-posting)
```

Hard dependencies:

- CM-1 comes before everything that adjusts value: CM-4, CM-5, CM-6, CM-10.
- CM-2 (dimensions) comes before any new posting phase.
- CM-3 comes before CM-4 (settlement), CM-5 (rehabilitation and stripping allocation), CM-7 and CM-8a (overhead).
- CM-7 needs CM-2's branch rules.
- CM-9 needs CM-7 and CM-8a.
- CM-8b needs CM-8a.
- CM-10 needs everything it gates.
- **Rev 3 additions:**
  - AD-2a/2b lineage (CM-1) comes before AD-11 settlement (CM-4), because favourable differences reduce period
    production *through lineage*.
  - AD-10 eligibility classification (CM-2) comes before every capitalising phase: CM-4, CM-5, CM-6 and CM-7.
  - Quarry area dimensions (CM-5) are added to the CM-2 dimension framework, as a CM-5 migration extending it.
  - The CM-3 exception register is reviewed again in CM-5 (quarry), CM-6 (landed cost) and CM-7 (fleet).
  - The cost-flow bridge (C0) starts in CM-4 and is completed in CM-10.
  - CM-8b's onerous-contract step needs CM-1 NRV (project inventory) and the fixed-asset impairment hook.

Phases that can run in parallel (only if capacity allows; default is sequential): after CM-3, the lanes
{CM-4 → CM-5}, {CM-6} and {CM-8a → CM-8b} are independent.

Recommended sequential order:
CM-0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8a → 8b → 9 → 10 → (11)

---

## Part E: Phases (scope, migrations, APIs, ADR, exit gate, realistic effort)

**Assumptions behind the effort figures:**

- One developer working with AI assistance. Estimates are in calendar weeks.
- Each estimate includes design review, implementation, unit tests, ITs, the regression suite additions, web
  screens, docs and the owner review cycle.
- A 20 % contingency is included.
- **A phase's scope may be trimmed, but its tests and accounting integrity may not.**

### CM-0: Stabilise PR-13 · 1–2 wk · ADR-S030 amendment · V223

- Commit the `costing` module (currently uncommitted).
- Move `landed-costing/*.png` to `docs/legacy/reference-images/`.
- Add a foreign key on `AbcActivity.costCenterId`.
- Make the ABC cost-centre filter roll up the hierarchy.
- Base ABC COGS on booked COGS lines.
- Mark the material ledger as "STANDARD only, pending CM-10 rework" in the spec.
- **Start the regression suite:**
  - `AccountingRegressionSuite`: a JUnit suite tag `accounting-regression`, plus a Gradle task
    `./gradlew accountingRegression`.
  - Seed it with existing ITs: `LandedCostVoucherIT`, `StandardProcessCostingIT`, `MultiStageProductionIT`,
    `QuarryIT`, `CrushingIT`, `PrecastManufacturingIT`, `FleetCostingIT`, `CostCenterAccountingIT`,
    `AdvancedCostingIT`.
- **Exit:** the suite is green and runs in CI.

### CM-1: Valuation integrity, cost lineage, NRV · 8–10 wk · ADR-S031 · V224–V225

- `InventoryValuationApi` (AD-1) plus an ArchUnit rule.
- Lineage tables `InventoryCostLayer`, `CostLayerConsumption`, `CostValueAdjustment`, written by `StockService` and
  `StockPostingService` on every receipt, issue, transfer and production movement.
- Backfill migration from `InventoryLedger` and `InventoryBatch` (best effort). Pre-backfill history is marked
  `lineage='reconstructed'`.
- Value-only `InventoryLedger` rows for these changes, so `glTieOut` `bySource` reconciles to 0:
  - landed cost (`LandedCostLayers.apply`)
  - standard activation
  - policy migration
  - material ledger post/reverse
- AVCO pool event chain and survival-factor lineage (AD-2a). Adds tables `AvcoPoolEvent` and
  `CostValueAdjustmentLine`, and an epoch-reset rule.
- STANDARD item price differences, including landed charges, split through lineage (AD-2b):
  - on hand → inventory price-difference adjunct
  - open WIP → WIP price difference
  - completed and on hand → FG adjunct
  - sold/scrapped → COGS or abnormal
  - project → WBS

  Plus the tolerance flag.
- Posting dates use the effective or document date (`InventoryCostPolicyService:427`, `StockService:990`).
- Transfers always post when stock accounts differ.
- Fix the `CostResolver.SPECIFIC` path.
- **New documents:**
  - Inventory revaluation (`/inventory/revaluations`): draft → approve → post → reverse.
  - NRV review (`/inventory/nrv-reviews`): proposes write-downs from selling price minus cost to complete and sell;
    posts to an allowance; reversals are capped.
- APIs: `GET /inventory/cost-layers?productId&locationId`, `GET /inventory/cost-layers/:id/trace` (receipt →
  adjustments → consumptions → targets).
- **Exit:**
  - The tie-out is 0 after every existing regression scenario, with the adjunct accounts included.
  - The lineage trace test passes for AVCO, FIFO, STANDARD and SPECIFIC.
  - Reversal and idempotency tests pass for revaluation and NRV.
  - **AVCO distribution tests (R3-7):**
    - receipts, issues and a late adjustment → Σ shares = A exactly
    - a pool reset sends no on-hand share
    - negative epoch → COGS
    - a transfer recursion respects the depth cap
    - re-running the adjustment is a no-op
    - reversal gives exact negatives
    - a second adjustment does not redistribute the first
  - **STANDARD landed tests (R3-1):** one receipt split across on hand, an open WIP order, a completed order with FG
    on hand, FG sold and a scrap issue. The test checks each destination account and the lineage line. The standard
    unit cost is unchanged. A layer above tolerance raises the flag.
  - Golden test documenting the old vs new AVCO on-hand/consumed split. The owner signs off the change.
- **Effort (revised): 8–10 wk.**

### CM-2: Policy governance, unified settings, branch/location dimensions · 4–5 wk · ADR-S032 · V226

- `AccountingPolicy` table (election, value, effective date, status draft/approved, approver, IAS 8 justification),
  plus `AccountingPolicyService` (maker-checker, prospective only, audited).
- Elections exposed (permitted choices only):
  - cost formula per category (FIFO or weighted average)
  - joint-cost method per process
  - stripping asset policy per quarry (the IFRIC 20 assessment is required to enable it)
  - project recognition per contract, only if the IFRS 15 criteria are met
  - variance posting granularity (split or summary — presentation only)
- `CostingSettingsService` façade plus `/settings/costing` page with sections:
  - Valuation (read-only view of elections with links)
  - Landed costs (enable, clearing account, default basis, accrual flag, components editor moved here)
  - Accounts (`AccountMapping` keys, seeded)
  - Production
  - Cost centres and dimensions
  - Period close
- Remove the duplicate writer: `CompanySettingsService` delegates to `SettingRegistry`.
- V226:
  - seed fallback and new accounts and their mappings for every org (2155, 2169, 5350, 5905–5965, NRV allowance,
    landed-cost variance, revaluation, under/over, inter-branch clearing, in-transit)
  - add `JournalLine.branchId`/`locationId` if missing, with a backfill from source documents
  - add `InventoryPostingRule.branchId`/`locationId`
- Dimension enforcement: costing-originated postings without a branch are rejected, as are stock postings without a
  location (rule driven, with a grace "warn" mode for one release).
- Permissions: `costing_settings:manage`, `accounting_policy:approve`.
- **AD-10 eligibility (R3-8):**
  - `CostEligibility` classification per GL account, cost component and cost pool, governed.
  - Seed defaults: expense accounts by nature; the landed-cost component kinds (constraint table from C3).
  - Validator API used by every capitalising service from CM-4 on.
  - Settings section "Cost eligibility" with a review list of unclassified expense accounts.
- **Exit addition:**
  - Unclassified accounts are rejected for capitalisation.
  - The classification change is governed and prospective.
  - The seed covers all mapped expense accounts.
- **Exit:**
  - Policy change tests: a forbidden choice is rejected, maker ≠ checker, applies prospectively.
  - Every regression scenario's journal lines carry branch and location.
  - Settings round-trip tests.

### CM-3: Allocation engine, financial and statistical · 6–7 wk · ADR-S033 · V227

- `AllocationCalculator` (pure; rounding `LAST | LARGEST_REMAINDER | MAIN_RECEIVER`; exact sums), with property
  tests.
- `DriverProvider` for each basis: quantity, weight, volume, purchase/sales value, labour hours, machine hours,
  operating hours, km, tonnage, tonne-km, m³, equal, fixed %, manual, statistical key.
- Tables:
  - `AllocationCycle`, `AllocationSegment`: postingMode, sender, receiver type (cost centre, project, product,
    branch, work centre, customer), basis, rounding
  - `AllocationDocument`, `AllocationLine`: sourceType/Id, basis value, share, amount, journal entry, reversedBy
  - `StatisticalKey`, `StatisticalKeyValue`
- `CostCycleService`:
  - preview, post, reverse
  - sequential step-down; optional iterative reciprocal (fixed-point, capped)
  - idempotent per (cycle, period)
  - period-lock aware
  - FINANCIAL posts via `PostingService` with dimensions; STATISTICAL is memo-only
- `CostAllocationService` keeps its routes and is reimplemented as a single-segment FINANCIAL cycle (its `driver`
  label maps to a basis).
- **Allocation-engine exception register (R3-6)**, kept in ADR-S033 and in `docs/spec/costing.md`.
  - Each entry records: engine, owning module, why it stays independent, which tests protect it, migration decision,
    and the phase where it is reviewed again.
  - An engine moves onto `AllocationCalculator` only behind a golden test proving identical output, and every
    exception keeps a regression test.

| Engine | Owner | Independent because | Protecting tests | Decision |
|---|---|---|---|---|
| `LandedCostVoucherService` basis split | procurement | Remainder-on-last rounding per receipt line; manual per-line amounts; parity with `LandedCostVoucherIT` | `LandedCostVoucherIT` + new golden | **Migrate** the basis computation in CM-3 if the golden test is identical. Otherwise keep it and review again in CM-6 |
| Legacy `LandedCostService` | procurement | none | `ProcurementIT.landedCost*` + contract-tests | **Retire** in CM-6 by delegating to the voucher |
| `JointCostAllocator` | manufacturing | Carve-out pre-step (NRV / zero / carve %) is accounting policy, not a basis; already exact with largest remainder | `JointCostAllocatorTest`, `MultiStageProductionIT` | **Keep** as a policy layer. Delegate only its final rounding to the calculator (`LARGEST_REMAINDER`) if the golden test is identical. Review in CM-5 |
| `CostAbsorptionService` / `CostAbsorptionCalculator` | manufacturing | Rate × driver *absorption* (L1→L2), not the allocation of a pool | `CostAbsorptionCalculatorTest`, `ManufacturingCostingIT` | **Permanently independent.** Its settlement uses the engine (CM-4) |
| `FleetWorkOrderCostService` (hours × rate) | fleet | Absorption-type, same as above | `FleetCostingIT` | **Permanently independent** |
| `FleetCostCalculator.allocate` | fleet | Trip-cost basis setting `fleet.tripCostBasis` | `FleetCostingPoliciesTest`, `FleetCostingIT` | **Migrate in CM-7** (adds tonne-km, m³, empty-leg policy) |
| `QuarryCostCalculator` | quarry | Net/gross-tonne basis; feeds posting | `QuarryCalculatorsTest`, `QuarryIT` | **Migrate the allocation part in CM-5.** The per-tonne KPI moves to `CostKpiService` |
| `HrPayrollCostAllocator` | hr | Must match payslip cents per employee; statutory | HR payroll ITs | **Permanently independent.** Its output is an L3 *source* for cycles |
| `CostAllocationService` | accounting | none | `CostCenterAccountingIT` | **Reimplement** as a single-segment cycle in CM-3; routes unchanged |
| `AbcCalculator` | costing | Analytic by nature | `AbcCalculatorTest`, `AdvancedCostingIT` | **Migrate to STATISTICAL mode** in CM-3 (its results become statistical allocation documents) |
| `ProcessCostCalculator` (equivalent units) | manufacturing | A valuation method, not an allocation | `ProcessCostCalculatorTest` | **Permanently independent** |

- **Exit addition:**
  - The register is complete and approved.
  - Every "migrate" row has a passing golden test; every "keep" row has its tests tagged `accounting-regression`.
  - An ArchUnit check stops *new* allocation code outside `costing.allocation`, unless it is listed in the register.
- Routes: `/costing/allocation-cycles`, `/costing/allocation-cycles/:id/runs` (preview, post),
  `/costing/allocation-documents/:id/reverse` (Idempotency-Key), `/costing/statistical-keys`.
- **Exit:**
  - A 3-step cycle test, both financial and statistical.
  - Statistical runs create 0 journal lines.
  - Reversal nets to zero.
  - The traceability test passes.
  - Golden tests pass.

### CM-4: Manufacturing unification and IAS 2.13 absorption · 8–10 wk · ADR-S034 · V228

- `ProductStandardCost` is authoritative. `freezeStandard` proposes a draft from the BOM roll-up.
- One variance engine: `VarianceCalculator` produces a component split (material price and usage, labour rate and
  efficiency, overhead spending and volume, yield, mix).
- Persisting and posting variances moves to `StandardCostService.postProductionVariances`, used by both single-leg
  orders and the stage engine (replacing the 5950 lump at `StageExecutionService:873`).
- Read reports prefer persisted rows.
- Single-leg by-products use `JointCostAllocator`.
- `WorkCenterCostRate` becomes the single rate store, with `normalCapacity`, validity and a source of
  `manual | fleet_tco | payroll_actual`.
- `RateResolver` falls back to the crushing-plant and quarry-equipment fields so existing data keeps working.
- **Normal-capacity validation** (precondition for settlement):
  - `normalCapacity` per work centre (and per plant for process lines) must be approved under AD-4.
  - It must state its basis (hours/tonnes/units) and derivation: average of the last N periods, adjusted for planned
    maintenance, or a design capacity × expected-efficiency factor until history exists.
  - It must be reviewed at least yearly.
  - A missing or stale normal capacity blocks settlement for that work centre.
- **`AbsorptionSettlementService` (AD-11; R3-2):** decompose first, then treat each component.

  Per period, work centre and cost pool:
  - **Actual** = L3 actual through the AD-10 gate
  - **Absorbed** = Σ rate × actual driver
  - **Budget** = budgeted fixed overhead for the period
  - Fixed overhead:
    - **Spending** = actual − budget
    - **Volume** = budget − absorbed = (normal − actual activity) × fixed rate
    - **Rate/estimate error** = flagged where the rate ≠ budget ÷ normal capacity
  - Variable overhead: **spending** (actual vs rate × actual driver)
  - Reconciling items detected first:
    - **timing:** open overhead accruals, unposted bills on overhead cost centres, orders not yet absorbed
    - **misposting:** absorption on the wrong work centre or order

  | Cause | Under-absorbed (actual > absorbed) | Over-absorbed (absorbed > actual) |
  |---|---|---|
  | Timing / cut-off | **Blocks settlement** until accruals and absorption are complete | **Blocks settlement** |
  | Misposting | Correcting entry (reversal + re-absorption), not settlement | Same |
  | Rate / normal-capacity error | Correct the rate (governed, prospective). Current period: treat like a spending variance | Same as favourable spending; correct the rate |
  | Fixed **volume**: activity below normal | **Expense** (IAS 2.13 unallocated overhead). Never capitalised | — |
  | Fixed **volume**: abnormally **high** activity | — | Reduce the fixed overhead per unit: credit **only the units produced in the period**, by lineage of those output layers (WIP still open, FG on hand, sold → COGS) |
  | Fixed or variable **spending**, favourable | — | Inventory would be above cost → credit period production through lineage (as above) |
  | Fixed or variable **spending**, unfavourable, within tolerance and normal | Allocate to period production through lineage (actual normal conversion cost, IAS 2.12) | — |
  | Unfavourable spending above tolerance or abnormal (breakdown, waste) | Reviewer classifies (governed). Abnormal → **expense**; normal → lineage allocation with justification | — |
  | Below the materiality threshold (governed) | COGS | COGS |

  - Each settlement is a FINANCIAL `AllocationDocument`: component, cause, basis, lineage targets, reviewer and
    justification. It is reversible.
- FG reconciliation report.
- **Cost-flow bridge, production part (C0):** actual by nature → absorbed → settled by cause → expensed or unallocated.
- **Exit addition (R3-2):**
  - Decomposition test: spending + volume + rate error + timing = total.
  - Timing items block settlement.
  - Missing or stale normal capacity blocks.
  - Low-volume under-absorption is expensed.
  - High-volume over-absorption credits only the period's output layers (an older FG layer is untouched).
  - Favourable spending follows lineage.
  - Abnormal classification is expensed.
  - Below-threshold amounts go to COGS.
  - Reversal nets to zero.
  - The production bridge reconciles to L3.
- **Effort (revised): 8–10 wk.**
- **Exit:**
  - Variance split reconciles to the total.
  - The stage and single-leg engines give identical variances for the same data.
  - Settlement tests cover under, over and reversal.
  - WIP, FG and the GL tie out to 0.

### CM-5: Quarry, crushing and blocks; IFRIC 20; IAS 37 · 8–10 wk · ADR-S035 · V229

- Quarry → crusher feed: a listener on `quarry.extraction.posted` with a lineage link. Weighbridge tickets are
  linked to the crushing feed and to despatch.
- `CostKpiService` (pure plus readers), one definition each for:
  - extraction cost per tonne (drill, blast, load, haul, royalty, stripping amortisation, rehabilitation increment)
  - crushing cost per tonne per plant and stage
  - cost per aggregate size (joint method elected per process)
  - block cost per unit and per pallet (cement, aggregates, admixture, energy, labour, curing-yard holding, breakage)
  - yield, scrap and wastage per plant, product and shift

  Existing quarry, crushing and precast reports switch to it, with old numbers kept under `basis=legacy` for one
  release.
- IFRIC 20:
  - `QuarryStrippingAssessment` → `QuarryStrippingAsset` (per identified component)
  - stripping ratio split between inventory and the asset
  - `StrippingAmortisation` by units of production
- **Quarry area dimensions (R3-3)**, needed by extraction, stripping, provisions and KPIs:
  - `QuarryArea` hierarchy: **site → pit → bench → zone/block**, plus `oreBodyComponentId` (IFRIC 20 component).
    Each area has a status (planned / development / production / exhausted / rehabilitating / rehabilitated),
    disturbed area m², reserves (t) and validity.
  - `quarryAreaId` becomes a dimension on `JournalLine`, extraction loads, blasts, haulage, `InventoryCostLayer`
    (ROM stockpile layers) and the provision tables, via the CM-2 framework.
  - Required on all quarry postings.
- **IAS 37 rehabilitation, four distinct movements (R3-3):**

  | Movement | Trigger | Measurement | Posting | Lineage / dimension |
  |---|---|---|---|---|
  | **Initial recognition** | Disturbance from establishment or development (access roads, plant pad, initial overburden) once a present obligation exists | PV of best estimate, at a pre-tax rate reflecting current market time value and specific risks | Dr **rehabilitation asset** (PP&E, IAS 16.16c) / Cr provision. The asset is depreciated by units of production over the area's reserves (fixed-asset module) | Provision layer `initial`, per area |
  | **Discount unwinding** | Each period | Opening provision (all layers) × current rate × time | Dr **finance cost** / Cr provision. **Never inventory** (IAS 23.4(b)) and never a remeasurement | Per layer and area; journal `sourceType=rehab_unwind` |
  | **Current-period production disturbance** | Extraction during the period disturbs new ground or deepens the void (IAS 16.18 → IAS 2) | PV of the *incremental* obligation, from area disturbed (m²) or tonnes extracted × the zone's current rehabilitation unit estimate | Dr **extraction cost** (L2, `production_capitalisable`, into ROM layers for the period) / Cr provision | Provision layer `production`, linked to the period's extraction layers |
  | **IFRIC 1 remeasurement** | Change in estimated cash flows, timing or discount rate | Provision recalculated per layer at the current estimate and rate | `initial`-layer change: ± the rehabilitation asset. A decrease above the asset's carrying amount → P&L. An increase → IAS 36 impairment-indicator task. `production`-layer change → **P&L** (the inventory is already produced or sold; it is not re-costed) | New layer `remeasurement`, linked to the layer changed |
  | **Utilisation** | Rehabilitation work performed | Actual spend | Dr provision / Cr AP or cash. Any over/under spend vs the provision is assessed through the next remeasurement | Per area |

  - Tables: `QuarryRehabObligation` (per area), `QuarryRehabProvisionLayer` (origin `initial | production |
    remeasurement`, amount, rate, schedule) and `QuarryRehabMovement` (type, amount, journal, source). They are
    reversible per movement and governed (estimates approved under AD-4).
  - IFRIC 20 stripping uses the same area/component dimension, so stripping-asset amortisation and the production
    disturbance are both traceable per bench and zone.
- **Exit addition (R3-3):**
  - One test per movement type, with the exact GL route.
  - Unwinding never hits inventory.
  - A production-layer remeasurement goes to P&L, not inventory.
  - A decrease beyond the asset → P&L.
  - An increase creates an impairment-indicator task.
  - Every quarry journal line carries `quarryAreaId`.
  - The per-area provision roll-forward (opening + initial + production + unwind + remeasure − utilise = closing)
    ties to the GL.
- **Effort (revised): 8–10 wk.**
- Add `CuringHoldTest`.
- **Exit:**
  - The quarry→sale scenario (see Part F).
  - Rejection tests: a stripping asset without an assessment, a capitalised abnormal loss.
  - Provision unwinding schedule test.

### CM-6: Landed cost completion · 4–5 wk · ADR-S036 · V230

- Legacy route delegation (contract parity scenarios stay identical); reversal now works.
- Accrual at receipt: estimated % or amount per vendor, category or Incoterm (Dr stock / Cr landed-cost accrual).
  The actual bill clears it, and the difference goes on-hand vs consumed through `CostValueAdjustment`.
- `Product.isLandedCost`, and "create voucher from bill".
- FX true-up to realised FX. Capitalising the FX difference is **not** an option: it is not an IAS 2 cost.
- Generated bills carry tax lines.
- Optional duty-proposal helper by HS code.
- Component kind constraints from C3 are enforced on voucher lines.
- **Exit:**
  - The import scenario.
  - Accrual and clearing accounts reach 0.
  - Reversal at each stage.
  - Duplicate-invoice and idempotency tests.

### CM-7: Fleet and distribution; branch transfers vs intercompany · 6–8 wk · ADR-S037 · V231

**Owned fleet**

- Vehicle cost pools per vehicle cost centre:
  - fuel (fuel module)
  - maintenance and tyres (workshop)
  - depreciation (fixed-asset module)
  - driver cost (payroll actuals or rate)
  - insurance, licences and tolls
- Rates per km, hour, trip, tonne, m³ and tonne-km (add `perM3` and `perTonneKm`).

**Third-party carriers**

- `CarrierRateCard`: per tonne, m³, km, trip or zone, with minimum charges.
- Carrier freight bills (extend `FleetFreightBillService`) are matched against deliveries. The expected-vs-billed
  variance is reported.

**Empty return trips**

- A trip leg records loaded vs empty km.
- Elected empty-leg policy:
  - empty km cost is absorbed by the loaded leg (default), or
  - pooled per route/zone

  Applied before cost per tonne-km.

**Delivery and branch transfers**

- Delivery cost is allocated per delivery via a CM-3 FINANCIAL allocation, from fleet or carrier cost to
  distribution cost, with customer, product, branch and project dimensions.
- Eligibility (AD-10), with the fleet part of the cost-flow bridge:
  - customer delivery → `distribution` (expensed, never inventory)
  - quarry face → crusher haulage → `production_capitalisable` (via absorption)
  - inter-branch transfer freight → `transfer_capitalisable`
- Same-entity branch transfer (AD-6): in-transit, optional inter-branch clearing, capitalisable transfer freight via
  `CostValueAdjustment`, and optional per-branch accounts.
- Guard: intercompany vs branch mismatch is rejected.
- Intercompany stock sales (depends on PR-8): transfer price, and an unrealised-profit report for elimination.

**Exit**

- The distribution scenario, covering owned and third-party carriers and empty legs.
- Branch trial balance balances when clearing is on.
- Mismatch rejection tests.

### CM-8a: Project core and cost capture · 6–8 wk · ADR-S038 · V232

- `Project`: customer, contract, branch, cost centre, status.
- `ProjectSite` (multiple sites per project, each with an address and a delivery location).
- `ProjectWbsElement`: hierarchical, with billable/cost flags.
- `ProjectBudget` / `ProjectBudgetLine` by cost element and WBS, versioned (original, revised).
- `projectId` and `wbsId` become dimensions on `JournalLine`, `InventoryLedger`, consumptions, timesheets and fleet
  trips.
- The free-text `projectId` columns (V152, V154) become foreign keys, after a cleanup that maps unmatched values to a
  placeholder project flagged for review.
- Cost capture paths:
  - stock issue to WBS
  - delivery to a site
  - purchase/subcontract bills
  - timesheets × rate
  - equipment hours
  - fleet trips
  - overhead via cycles
- Commitments: PR/PO.
- Forecast cost to complete: `ProjectForecast` (ETC per WBS element and cost element, EAC = actual + committed + ETC
  remaining).
- Reports: budget vs committed vs actual vs EAC.
- **Exit:** cost capture traceability per path; commitment tests; tenant isolation.

### CM-8b: Contract commercials (IFRS 15 / IAS 37) · 8–10 wk · ADR-S039 · V233

- `ContractVariation` (change order):
  - status pending → approved → rejected
  - value and cost impact
  - IFRS 15 modification classification
  - an approved variation updates the budget and the transaction price
- Progress claims / valuations (`ProgressClaim`): measured work, cumulative certified, previous certified, this
  claim, retention deducted, advance recovery, tax. A certified claim raises an invoice.
- **Retentions (R3-5):** a `RetentionLedger` covers both sides:
  - side: `customer | subcontractor`
  - contract or subcontract, claim
  - amount, release conditions (practical completion certificate / end of defects period / date only)
  - status

  **Customer side** (IFRS 15.105–108, IFRS 9):

  | Situation | Classification | Posting |
  |---|---|---|
  | Claim certified; retention withheld, release **conditional** on performance (completion, defects remedied) | **Contract asset: retention** (conditional right) | Revenue is recognised by progress. On billing: Dr AR (net billed) + Dr contract asset–retention / Cr contract asset–unbilled (or revenue) |
  | Condition met (completion certificate, defects period ended without claims) | Becomes an **unconditional receivable** | Dr AR–retention / Cr contract asset–retention |
  | Release depends **only on the passage of time** from the start (contract term) | **Receivable** at billing | Dr AR–retention. No significant financing component, because the retention is protection (15.62(c)) |
  | Defects claim / customer deduction | Reduces the contract asset (variable consideration) or is an impairment | Per the CM-8b assessment |

  - IFRS 9 expected credit loss applies to retention contract assets and receivables.
  - Classification is current or non-current by expected release date.

  **Subcontractor side:**
  - Certified work is **recognised in full as project cost** when performed (Dr project WIP/cost).
  - The withheld retention is a **financial liability, retention payable**: not deferred cost and not a contract
    asset mirror.
  - Entry: Dr cost / Cr AP (net) / Cr retention payable.
  - Back-charges and defects deductions reduce the payable (Dr retention payable / Cr project cost or recovery).
  - On the release condition: Dr retention payable / Cr AP.
  - Classified current or non-current by release date.
  - **Exit addition:**
    - Conditional → contract asset; unconditional → receivable.
    - A time-only retention becomes a receivable at billing.
    - The release reclass is correct.
    - Subcontractor cost is booked at the gross amount and the retention payable is held, then released.
    - Back-charges reduce the payable.
    - The retention roll-forward per side ties to the GL.
- Customer advances: mobilisation advance as a contract liability, recovered at a % per claim.
- Subcontractors: `Subcontract` (scope, WBS, value, retention %, back-charges), subcontractor valuations/bills.
- Revenue recognition run (period-end, per contract):
  - criteria gate
  - cost-to-cost progress excluding flagged costs
  - contract asset/liability posting
  - cumulative catch-up on modifications
- **Onerous contract assessment (R3-4; IAS 37.66–69 incl. the 68A amendment).** `OnerousContractAssessment` runs
  per contract per period. It is approved under AD-4 and reversible.

  1. **Trigger:** an indicator, not a rule:
     - forecast margin < 0
     - EAC revised up by more than the tolerance
     - a dispute or penalty notice
     - management flag

     EAC > revenue alone **does not** create a provision. It only opens the assessment.
  2. **Expected economic benefits:** remaining transaction price, i.e. approved variations plus variable
     consideration after the IFRS 15 constraint. Claims are counted only if highly probable. Revenue already
     recognised is excluded.
  3. **Cost of fulfilling (68A)**, for the *remaining* obligation only:
     - **incremental costs:** materials, direct labour, subcontract
     - **plus an allocation of other costs directly related to fulfilling contracts:** depreciation of equipment used,
       site supervision, contract-specific insurance
     - excluded: general and admin costs not explicitly chargeable to the customer
     - each ETC line is tagged `incremental | directly_related | excluded`
  4. **Cost of exiting:** termination penalties or compensation under the contract, plus legal/exit costs.
     Entered with evidence.
  5. **Unavoidable cost = min(cost of fulfilling, cost of exiting).**
  6. **Impairment first (IAS 37.69):**
     - write down contract-dedicated assets: equipment (IAS 36 via the fixed-asset impairment hook), capitalised
       contract costs (IFRS 15.101), project inventory and uninstalled materials (CM-1 NRV)
     - the provision covers only the remaining shortfall
  7. **Provision = max(0, unavoidable cost − expected benefits − losses already recognised by impairment).**
     Discounted if the time value is material.
     - Dr onerous contract expense / Cr provision.
     - Each period: utilised as the loss-making costs are incurred, remeasured, or released when no longer onerous.
  8. **No double counting with revenue recognition:** the assessment uses the remaining obligation only. Cumulative
     cost-to-cost catch-up already reflects past performance.
  9. **Exit addition (R3-4):**
     - EAC > revenue with a cheaper exit penalty → the provision equals the exit-based amount.
     - Excluded G&A does not create a provision.
     - Dedicated equipment is impaired before the provision.
     - The provision is utilised and then released.
     - Approval is required.
     - The assessment trail links each ETC line and penalty evidence.
- **Effort (revised): 8–10 wk.**
- **Exit:**
  - The project scenario (see Part F).
  - Claim → invoice traceability.
  - Retention and advance balances tie to the GL.
  - An onerous provision is recognised and released.

### CM-9: Profitability (ACTUAL / STANDARD / ESTIMATE) · 5–6 wk · ADR-S040 · V234

- `ProfitabilityFact` (layer, period, branch, location, customer, product, project, channel, measure).
  - **ACTUAL** contribution margins:
    - CM1 = revenue − booked COGS
    - CM2 = CM1 − variances − distribution
    - CM3 = CM2 − allocated overhead
  - **STANDARD** and **ESTIMATE** layers are kept separate.
- `ProfitabilityReconciliation`:
  - Σ ACTUAL + **unallocated** + **out-of-scope accounts** = GL P&L, with each account listed
  - the difference must be 0
  - statistical enrichments are flagged and excluded
- Existing reports keep their routes and add `layer=actual|standard|estimate`. The default preserves current
  behaviour: `estimate` where they estimate today.
- biexport/analytics gain the facts, `AllocationDocument` and lineage.
- **Exit:**
  - The reconciliation test equals 0 for every scenario.
  - Layer separation test: no API returns mixed layers.

### CM-10: Close cockpit, cost adjustment run, material ledger rework · 6–8 wk · ADR-S041 · V235

- `CostCloseService`. Ordered gates that `PeriodCloseChecklistService` must pass:
  1. no open landed-cost vouchers or accruals unexplained
  2. absorption done and settled
  3. allocation cycles posted
  4. NRV review done
  5. stripping amortisation and provision unwinding posted
  6. project revenue recognition run
  7. material ledger (STANDARD) and cost adjustment run (AVCO/FIFO)
  8. profitability rebuilt
  9. all reconciliations at 0 or explained
- Reconciliation dashboard: inventory, WIP, FG, NRV allowance, landed-cost clearing and accrual, GRNI, fleet accrual
  2169, project WIP, contract asset/liability, retentions, provisions — each subledger vs GL with source drill-down.
- Cost adjustment run (AVCO/FIFO): late costs flow through `CostLayerConsumption` (consumed → WIP → FG → COGS),
  posted into the current open period. Prior-period posting happens only if the period is open (governed). Reversible.
- Material ledger rework per AD-3: reads lineage, posts `ml_price_diff` through `InventoryValuationApi`, multi-level
  rollup. It uses the same AD-2b destination rules, so the CM-1 one-step split and the CM-10 multi-level roll-up never
  overlap. A unique key on `(adjustmentId, consumptionId)` guards against double counting.
- **Complete cost-flow bridge (C0):** L3 by nature = capitalised + expensed + settled + unallocated, for every cost
  element. It is a close gate: unallocated must be 0 or explained.
- Costing period lock plus reopen with `costing_close:reopen`, audited.
- **Exit:**
  - The close regression test passes: every gate blocks in turn.
  - The late-cost cascade scenario.
  - Reopen requires permission.

### CM-11: Cost simulation, future and non-posting · 5–7 wk · ADR-S042 · V236

- `CostScenario` (versioned): fuel price, material prices, FX, energy tariff, capacity/shift pattern, selling prices,
  volume mix.
- `SimulationService` runs the pure engines (AD-8) with the scenario's `CostContext`: roll-up, absorption, joint
  allocation, fleet, KPI and profitability. Results go to `SimulationResult` only.
- ArchUnit plus a test assert that simulation writes no ledgers or journals.
- Outputs: cost per tonne/unit, margin per product/customer, and break-even, compared with the ACTUAL and STANDARD
  layers.

**Total effort (rev 3): about 85–110 weeks sequentially** (CM-0…CM-10 ≈ 78–98 wk, plus CM-11).

Rev 3 increased the estimates for:

- CM-1 to 8–10 weeks (AVCO lineage and STANDARD split)
- CM-4 to 8–10 weeks (settlement decomposition)
- CM-5 to 8–10 weeks (area dimensions and four provision movements)
- CM-8b to 8–10 weeks (onerous assessment and retention matrix)

Three parallel lanes after CM-3 could shorten the calendar by about 30 %, but only with added capacity and without
merging phases.

---

## Part F: Testing strategy and acceptance criteria (revised)

### F1. Mandatory per-phase test matrix (an exit gate; a phase cannot close without it)

| Test type | Requirement |
|---|---|
| Unit / property | Pure calculators: sums exact, deterministic, rounding policy |
| Integration (Testcontainers, `ApiTestSupport`) | Every new route |
| Authorization | A 403 without the permission, per route |
| Tenant isolation | Org A cannot read or modify org B's documents, allocations, layers or facts |
| Idempotency | Posting/money routes reject a missing `Idempotency-Key`. A replay returns the same result and creates no duplicate journal entry or adjustment |
| Reversal | Every posting document reverses to a net-zero GL, restores lineage/adjustment state, and cannot be reversed twice |
| Traceability | The generic `TraceabilityAssertions.assertAllCostingLinesTraceable(period)`: every costing journal line → source document; every allocation or adjustment → source + journal entry (or none if statistical) |
| Reconciliation | `glTieOut` = 0, WIP/FG = 0, clearing/accrual accounts as expected, profitability reconciliation = 0 (from CM-9) |
| Period control | Posting into a closed period is rejected. Reopen is audited |
| Compliance rejection | Every C3 forbidden treatment has a test proving it is rejected |
| Eligibility (R3-8) | Every capitalising line has an eligible class and an L3 source. Ineligible sources are rejected. The cost-flow bridge for the phase's scope reconciles, with unallocated = 0 or explained |
| Lineage distribution (R3-1/R3-7) | Adjustment shares sum exactly. Re-runs are no-ops. Reversals give exact negatives. No adjustment is redistributed twice |
| Regression | The phase's scenario is added to `accountingRegression`, and the suite is green across all earlier phases |

### F2. The accounting regression suite, grown incrementally

- **Golden journal snapshots:** each scenario asserts the exact journal lines (account, debit/credit, dimensions,
  source). Snapshots are reviewed in the PR when they change intentionally.
- **Period-close regression** (from CM-10): closes month 1, posts month 2, attempts back-postings, reopens.
- Runs as `./gradlew accountingRegression`, required in CI before merge.

### F3. Scenario ITs (added in the phase shown)

| Scenario | Phase | Key assertions |
|---|---|---|
| `LineageTraceScenarioIT` | CM-1 | Receipt → landed cost → issue → sale trace for every costing method; tie-out 0; NRV write-down and capped reversal |
| `PolicyGovernanceIT` | CM-2 | LIFO, capitalised selling cost and unapproved changes are rejected; prospective effect |
| `AllocationCycleIT` | CM-3 | 3-step financial plus statistical; statistical = 0 journal lines; reciprocal; reversal; idempotent |
| `BlockProductionScenarioIT` | CM-4/5 | Cost per block breakdown; split variances posted; under-absorption expensed, over-absorption pro-rata; abnormal breakage expensed |
| `QuarryToSaleScenarioIT` | CM-5/7 | Extraction (stripping asset + amortisation, rehab increment) → crushing joint sizes → sale with owned truck plus empty return → cost per tonne / tonne-km; provision unwinding |
| `ImportLandedScenarioIT` | CM-6 | USD PO, 2 GRNs, accrual, 3 suppliers with FX, 40 % sold before the voucher; true-up; STANDARD → PPV; reversal at each stage |
| `DistributionBranchScenarioIT` | CM-7 | Branch transfer with freight capitalised and inter-branch clearing; third-party carrier variance; intercompany mismatch rejected |
| `ProjectCostScenarioIT` | CM-8a | 2 sites, WBS, issues, deliveries, timesheets, equipment, subcontract; budget vs EAC |
| `ContractRevenueScenarioIT` | CM-8b | Advance, 3 progress claims with retention, an approved and a pending variation, cost-to-cost revenue, onerous provision, retention release |
| `ProfitabilityReconciliationIT` | CM-9 | ACTUAL + unallocated + out-of-scope = GL P&L; layers never mixed |
| `CostCloseIT` / `CostAdjustmentRunIT` | CM-10 | Gates block in order; late cost cascades to COGS in the open period; reopen permission |
| `SimulationIsolationIT` | CM-11 | Zero writes to ledgers or journals; deterministic results |
| `AvcoLineageDistributionIT` (R3-7) | CM-1 | Survival-factor shares exact; pool reset; negative epoch; transfer recursion cap; idempotent; reversal; no double distribution |
| `StandardLandedSplitIT` (R3-1) | CM-1 | One standard receipt split to on-hand adjunct, open-WIP, FG adjunct, COGS and scrap; standard unchanged; tolerance flag |
| `AbsorptionSettlementCausesIT` (R3-2) | CM-4 | Decomposition; timing block; stale normal capacity block; volume under → expense; volume over → period output only; spending by lineage; abnormal → expense; materiality |
| `QuarryRehabMovementsIT` (R3-3) | CM-5 | Initial / unwind / production / remeasurement / utilisation routes; area dimension required; roll-forward = GL |
| `OnerousContractIT` (R3-4) | CM-8b | Indicator-only trigger; min(fulfil, exit); excluded G&A; impairment first; provision lifecycle |
| `RetentionClassificationIT` (R3-5) | CM-8b | Conditional asset → receivable; time-only receivable; subcontractor payable held, released, back-charged; roll-forwards = GL |
| `AllocationExceptionRegisterTest` (R3-6) | CM-3 | ArchUnit: allocation code outside `costing.allocation` only if registered; golden tests for "migrate" rows |
| `CostFlowBridgeIT` (R3-8) | CM-4 → CM-10 | L3 by nature = capitalised + expensed + settled + unallocated per element; quarry, fleet, production |

---

## Part G: ADR register (written at the start of each phase, reviewed before code)

| ADR | Title | Records |
|---|---|---|
| S030 amend | Advanced costing remainder | Material ledger restricted pending AD-3; ABC fixes |
| S031 | Inventory valuation authority and cost lineage | AD-1, AD-2, **AD-2a (AVCO survival-factor lineage, no double counting)**, **AD-2b (STANDARD price-difference split)**, AD-9; NRV (IAS 2.28–33); value-only ledger rows; back-compat golden for the AVCO split; **C0 three-layer model** |
| S032 | Accounting policy governance, costing settings, branch/location dimensions | AD-4, AD-6 (dimension part), **AD-10 eligibility gate**; C3 guardrail list; single source of truth map |
| S033 | Allocation engine: financial and statistical | AD-5, AD-8; driver catalogue; rounding; golden-test migration rule; **exception register** (owner, reason, tests, decision, review phase) |
| S034 | Manufacturing cost unification and IAS 2.13 absorption | One standard, one variance engine; **normal-capacity validation; AD-11 cause-based settlement matrix**; production cost-flow bridge |
| S035 | Quarry, crushing and block costing; IFRIC 20; IAS 37 rehabilitation | KPI definitions; stripping assessment; **QuarryArea dimensions (site/pit/bench/zone/component)**; **four provision movements + utilisation, layer model, roll-forward** |
| S036 | Landed cost completion | Legacy delegation; accrual and true-up; FX not capitalised |
| S037 | Fleet and distribution costing; branch transfers vs intercompany | Owned/third-party; empty legs; AD-6 transfer part |
| S038 | Project core and job costing | Entities, dimensions, forecast cost to complete |
| S039 | Contract commercials and IFRS 15 / IAS 37 | Variations, claims, advances, subcontracts, recognition; **retention matrix (conditional contract asset vs unconditional receivable; subcontractor retention payable)**; **onerous assessment (68A fulfilment costs, exit penalties, impairment first, indicator trigger)** |
| S040 | Profitability layers and GL reconciliation | AD-7 |
| S041 | Cost close, cost adjustment run, material ledger rework | AD-3; close gate order; **complete cost-flow bridge as a gate**; ML ↔ AD-2b non-overlap |
| S042 | Cost simulation | Sandbox and pure-engine contract |

Each ADR contains:

- context, decision, standards cited, alternatives rejected
- migrations
- API changes and back-compat notes
- test and exit criteria copied from Part F

---

## Part H: Back-compat and non-regression rules

- No existing route, JSON field or posting changes meaning. New behaviour is opt-in via elections or settings, or
  sits on new routes.
- Report changes add a `basis`/`layer` parameter whose default is the current behaviour.
- `reference/`, V1 and V2 are never edited. Schema changes go in new `V2xx` migrations only.
- Engines move onto shared primitives only behind golden tests. If the output would differ, the engine is left alone
  and the reason recorded in the ADR.
- The `docs/spec/costing.md` deviations list and `docs/PORT_STATUS.md` are updated in each phase PR.

## Part I: Verification per phase

1. `cd backend && ./gradlew compileJava compileTestJava -q`, then `./gradlew fastTest --tests '*<Phase>*'`.
2. `./gradlew accountingRegression`, which must be green for every earlier phase as well.
3. `./gradlew build` (Docker running): JaCoCo ≥ 0.80 on touched `application` packages; `ModularityTests` and
   `ArchitectureTests` clean.
4. Check reconciliation endpoints manually on the demo seed: tie-out 0, plus the dashboard from CM-10.
5. Web: `pnpm web:dev`, walk through the phase's screens with zero console errors.
6. Owner review of the ADR, test evidence and diff. The next phase starts only after approval.

## Critical files (representative)

| Area | Files |
|---|---|
| Valuation | `inventory/application/{StockService,StockPostingService,CostResolver,InventoryCostPolicyService,StandardCostService}.java`, `accounting/application/InventoryValuationService.java` |
| Landed cost | `procurement/application/{LandedCostVoucherService,LandedCostLayers,LandedCostService,CostComponentService}.java` |
| Manufacturing | `manufacturing/application/{StageExecutionService,CostingService,CostAbsorptionService,VarianceCalculator,JointCostAllocator,ManufacturingCostingService,CrushingRunService,PrecastReportService}.java` |
| Quarry | `quarry/application/{QuarryCostCalculator,QuarryPostingService,QuarryReportService}.java` |
| Fleet | `fleet/application/{FleetCostCalculator,FleetCostingService,FleetReportService,FleetFreightBillService}.java` |
| Cost centres, close, settings | `accounting/application/{CostAllocationService,CostCenterRulesService,PeriodCloseChecklistService,FiscalPeriodControl}.java`, `kernel/settings/{SettingRegistry,SettingsService,CompanySettingsService}.java` |
| Costing module | `costing/**`: new subpackages `policy`, `settings`, `allocation`, `kpi`, `profitability`, `close`, `simulation` |
| New modules | `projects/**` |
| Tests | `backend/src/test/java/com/inventoryerp/costing/scenario/*`, `TraceabilityAssertions`, `accountingRegression` Gradle task |
| Web | `web/src/pages/costing/*`, `web/src/pages/settings/CostingSettingsPage.tsx`, `web/src/pages/projects/*` |

---

## Build status

| Phase | Commit | ADR | Notes / deviations |
|---|---|---|---|
| CM-0 | `9a28b19` | S030 amendment | done |
| CM-1 | `c7be42c` | S031 | Lineage derived from the stock ledger instead of separate layer / consumption tables. The AVCO landed-cost split switch awaits owner approval. FG adjunct and multi-level roll-up moved to CM-10 |
| CM-2 | `27388fc` | S032 | Accounts created on demand (`/costing/settings/accounts/ensure`), not by a per-org migration. Location-level accounts move to CM-7 |
| CM-3 | `6ccea69` | S033 | `CostAllocationService` reimplementation deferred to CM-4. Hour drivers measured through statistical keys until work centres carry a cost centre |
| CM-4 | see git log | S034 | Settlement lives in `costing` (reads manufacturing). Open orders block settlement (WIP target → CM-10). `CostAllocationService` reimplementation moved to CM-10. Migration V227 (numbering shifted by one from the plan) |
| CM-5 | see git log | S035 | Areas extend `QuarryZone` (no new area table). Services in `costing` (module dependency direction). Rehab-asset depreciation → fixed-asset integration; crushing / block KPIs → CM-9. V228 |
