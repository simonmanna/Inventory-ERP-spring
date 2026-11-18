# Advanced costing (`com.inventoryerp.costing`)

Extension (no NestJS counterpart), roadmap 3.1 remainder, design: `docs/adr/ADR-S030-advanced-costing-remainder.md`,
migration `V222__advanced_costing.sql`.

## Routes (`/api/v1`)

| Verb | Path | Permission |
|---|---|---|
| GET / POST | `/costing/material-ledger/runs` (`{from, to}`) | `advanced_costing:read` / `advanced_costing:manage` |
| GET / DELETE | `/costing/material-ledger/runs/:id` (delete: draft only) | read / manage |
| POST | `/costing/material-ledger/runs/:id/recompute` | `advanced_costing:manage` |
| POST | `/costing/material-ledger/runs/:id/post`, `/reverse` (`Idempotency-Key` required) | `advanced_costing:post` |
| GET | `/costing/variance-analysis?from&to` | `advanced_costing:read` |
| GET | `/costing/abc/drivers` | `advanced_costing:read` |
| GET / POST | `/costing/abc/activities` | read / manage |
| PATCH / DELETE | `/costing/abc/activities/:id` | `advanced_costing:manage` |
| GET / POST | `/costing/abc/runs` (`{costObject, from, to}`) | read / manage |
| GET | `/costing/abc/runs/:id` | `advanced_costing:read` |

## Messages (verbatim)

`The next run must start on <date>`, `Post or delete the open draft run first`, `A run cannot end in the future`,
`Only a draft run can be recomputed|deleted|posted`, `Only a posted run can be reversed`,
`Only the last posted run can be reversed`, `The run no longer follows the last posted run; recompute it`,
`Define at least one active <object> activity first`, `driver must be one of the following values: …`,
`costObject must be one of the following values: customer, product`, `poolPercent must be greater than 0 and at most 100`.

## Tests

`AdvancedCostingIT` (5), `MaterialLedgerCalculatorTest` (4), `AbcCalculatorTest` (4). Web: `/costing/advanced`.

## Cost Management roadmap

Design: `docs/roadmap/cost-management-plan.md` (rev 3). Phases CM-0 … CM-11; ADR-S031 onward.

- **CM-0** (done): ABC cost-centre roll-up + FK (V223), booked ABC cost of goods, `accountingRegression` suite,
  material ledger restricted to STANDARD items pending the CM-10 rework.
- **CM-1** (done, ADR-S031, V224): value-only stock-ledger rows for every cost-only change, cost lineage over the
  stock ledger (`/inventory/cost-layers/:id/trace|distribution`), STANDARD landed cost split over lineage into the
  price-difference adjunct / WIP / COGS, adjunct release run, inventory revaluation and IAS 2 NRV documents
  (`/inventory/revaluations`). Tests: `AvcoLineageCalculatorTest` (5), `ValuationIntegrityIT` (5).
- **CM-2** (done, ADR-S032, V225): governed accounting policy elections and cost eligibility (`/costing/policies`,
  maker-checker, prospective, forbidden choices refused), Costing & Valuation settings façade (`/costing/settings`,
  web `/costing/settings`), landed-cost on/off, branch dimension guard on costing postings + compliance report.
  Tests: `CostingSettingsIT` (5).
- **CM-3** (done, ADR-S033, V226): `AllocationCalculator` (exact-sum rounding policies, reciprocal solver), allocation
  cycles (sequential / reciprocal; financial = posted, statistical = memo), measured drivers incl. statistical keys
  and fleet tonnage / km / tonne-km, idempotent runs with reversal, allocation-engine exception register
  (ArchUnit). Web `/costing/allocation`. Tests: `AllocationCalculatorTest` (5), `AllocationCycleIT` (5),
  `AllocationExceptionRegisterTest` (1).
- **CM-4** (done, ADR-S034, V227): governed normal capacity per work centre (maker-checker, yearly review), IAS 2.13
  absorption settlement decomposed into volume / spending / rate error and treated by cause (low volume → 5970,
  abnormal → 5972, high volume and favourable spending → period output through lineage, below materiality → COGS),
  timing blockers, production cost-flow bridge, stage variances registered through `StandardCostService`, variance
  component split. Tests: `AbsorptionSettlementCalculatorTest` (4), `AbsorptionSettlementCausesIT` (4).
- **CM-5** (done, ADR-S035, V228): quarry area dimension (site → pit → zone → bench → face, lifecycle, disturbed m²,
  reserves, ore-body component), IFRIC 20 stripping-activity assets (assessment, maker-checker, election, strip-ratio
  split, units-of-production amortisation into ROM lineage), IAS 37 rehabilitation (initial / unwind / production /
  remeasurement / utilisation, roll-forward = GL, impairment indicator), extraction cost per tonne KPI, shared
  `LineageCapitalisation`. Tests: `QuarryRehabMovementsIT` (3).
- **CM-6** (done, ADR-S036, V229): landed-cost accrual at receipt by rule (supplier → category → Incoterm →
  default) through lineage to 2156, cleared in full where it went when the actual voucher posts (or reversed),
  accrual reconciliation; legacy per-receipt landed cost reversible through the voucher engine. Tests:
  `ImportLandedScenarioIT` (2).
- **CM-7** (done, ADR-S037, V230): transporter rate cards per m³ / tonne-km with a minimum charge, empty return
  legs (`loaded_leg` / `pooled` election), owned-fleet delivery cost runs into distribution (6460, customer / load /
  branch dimensions, P&L-neutral against 5385), same-entity branch transfers with inter-branch clearing (1299, each
  branch balances) and capitalised transfer freight through lineage. Tests: `DistributionCostCalculatorTest` (4),
  `DistributionBranchScenarioIT` (2).
- **CM-8a** (done, ADR-S038, V231): projects module — projects, sites, WBS, versioned budgets, cost-to-complete
  forecasts; project cost sub-ledger (1460 Project WIP with project / WBS / site dimensions) captured by stock issue,
  site delivery, timesheets × rate, equipment hours and reclass of posted cost; commitments; budget vs committed vs
  actual vs EAC; WIP tie-out; free-text procurement project references converted to foreign keys. Tests:
  `ProjectCostScenarioIT` (2).
- **CM-8b** (done, ADR-S039, V232): contract commercials — IFRS 15.35 criteria gating the over-time election,
  variations (approved into price and budget, pending excluded), customer advances (2480), progress claims invoiced to
  2470 with conditional retention (1475 contract asset) or time-only retention (receivable), subcontract valuations
  (gross to 1460, 2485 retention payable, back-charges), cost-to-cost / point-in-time revenue runs (1470 / 4610,
  5610), IAS 37 onerous assessments (indicator, 68A fulfilment cost, least net cost of exit, impairment first,
  maker-checker, 2490). Tests: `ContractCalculatorTest` (3), `ContractRevenueScenarioIT` (2).
- **CM-9** (done, ADR-S040, V233): profitability facts in three never-mixed layers (actual from the GL, standard
  from invoiced quantities, estimate from approved project budgets); CM1 / CM2 / CM3; exact ACTUAL reconciliation
  (segments + unallocated + out of scope = GL net income) with per-account listing; statistical allocations flagged
  and excluded. Tests: `ProfitabilityReconciliationIT` (2).
- **CM-10** (done, ADR-S041, V234): cost close cockpit — nine ordered gates (landed cost, absorption,
  allocation, NRV, quarry, revenue runs, cost adjustment, profitability, reconciliations) with the first blocking
  gate; costing period lock refusing costing postings until reopened (`costing_close:reopen`, reason audited);
  reconciliation dashboard; cost adjustment run cascading late WIP cost of completed orders to output layers by
  lineage. Material-ledger rework (AD-3) deferred. Tests: `CostCloseIT` (2).
- **CM-11** (done, ADR-S042, V235): non-posting cost simulation — versioned scenarios, pure `SimulationEngine`
  (unit cost, CM1–CM3, margin, break-even; fixed pool re-spread exactly), results beside ACTUAL and STANDARD of a
  profitability build; ArchUnit + isolation test prove no ledger writes. Tests: `SimulationEngineTest` (3),
  `SimulationIsolationTest`, `SimulationIsolationIT` (1).

## Review 2026-11-17 — accounting and costing fixes

Owner-approved 2026-11-17 ("code & develop the above fixes & enhancements"). Audit and outcome per finding:
[`docs/audits/2026-11-17-accounting-costing-review.md`](../audits/2026-11-17-accounting-costing-review.md).
Migrations `V530`–`V534`. Code markers: `Review 2026-11-17 (ACR-n)`.

### New routes

| Verb | Path | Permission |
|---|---|---|
| GET | `/inventory/cost-explanation?productId&locationId&limit` (limit 1–500, default 100) | `inventory_valuation:read` |
| POST | `/pos/posting-monitor/jobs/:id/abandon` (`{note}`, ≥ 10 characters; failed jobs only) | `pos:override` |

New settings:

- `costing.production.requireMaterialsOnHand` (bool, default `false`).
- `procurement.billWithoutReceipt` (`auto_receive` (default) | `block`).

### Deviations

These deviations come from the review. The period-close items (ACR-1, ACR-17) are in
[accounting](accounting.md#accounting-and-costing-review-deviations-2026-11-17), the cost explanation route in
[inventory](inventory.md#deviations).

| # | Where | Before (Nest / earlier Spring) | Now |
|---|---|---|---|
| ACR-6 | every costing engine's reverse (allocation, cycles, absorption settlement, cost adjustment, material ledger, distribution, quarry, revaluation, landed cost voucher, project cost / overhead / contract / capital project, standard-cost variances) | reversed at `now()` and skipped the CM-10 costing lock | dated per D-B6: the original date while its period is open, else today. A locked costing period moves the reversal to today. Today being locked refuses it with the guard's message. Postings take the shared side of the `costing-close:<org>` advisory lock |
| ACR-7 | `POST /costing/allocation-cycles/:id/runs` | overlapping periods allocated the same cost twice | 409 `This cycle already has a posted run for <from> – <to>; reverse it first` / `This cycle's posted run <no> (<from> – <to>) overlaps <from> – <to>; reverse it first` |
| ACR-7 | reciprocal allocation (`AllocationCalculator`) | fixed-point iteration capped at 100 rounds (inexact for strongly reciprocal centres) | exact solution (Gaussian elimination). A closed loop without an outlet: 400 `The service cost centres allocate all their cost among themselves; reciprocal allocation has no solution` |
| ACR-8 / ACR-14 | cost adjustment runs, landed-cost accrual clearing (V531) | a run reversed after a clearing, or a clearing after a run, credited WIP twice. A clearing always credited cost of sales | cleared amounts are kept per WIP share and releases per target. A clearing takes the released part back from where it went (COGS, an order's WIP, scrap, write-off). Refused while a dependent run or clearing exists: `Part of this amount was cascaded from WIP by cost adjustment run <no>; reverse that run first`, `A landed-cost accrual clearing has since taken part of the cost carried to <target> back out; post a correcting document instead`, `Part of this amount was resolved from a lineage exception; post a correcting document instead` |
| ACR-9 | late costs (absorption settlement, lineage capitalisation) on goods moved to another warehouse | stayed on the source pool's 1396 adjunct | follow the goods: on hand at the destination → that pool's adjunct; consumed there → its target |
| ACR-12 | absorption settlement | the absorption of a cancelled single-step order blocked the work centre's settlement for ever | not counted (it is not in the books). A cancelled multi-stage order's posted stage absorption is settled like any other |
| ACR-15 | quarry stripping amortisation and provision unwinding (CM-5) | could be run twice for one period | 409 `Stripping asset <code> is already amortised for <from> – <to>; reverse that amortisation first` / `The provision <code> is already unwound for <from> – <to>; reverse that unwinding first` |
| ACR-16 | cost close gates 2, 4, 5 | passed when any one settlement / NRV review / quarry posting existed | every work centre with overhead to settle, every item still written down to NRV (re-assessed in the period, IAS 2.33), and every provision / stripping asset with something to post; the gate lists what is missing |
| V532 | `ProfitabilityFact.amount`, `ProfitabilityReconLine.amount` | numeric(20,2): a 0.01 false difference on ledgers with sub-cent postings | numeric(20,6); the run's reconciliation is exact. Fact rows also carry `segmentName` (product, project, branch, cost centre or customer name; falls back to the id) |

Production, POS, purchasing and bill entries are in [manufacturing](manufacturing.md#deviations),
[pos](pos.md#deviations), [procurement](procurement.md#deviations) and [invoicing](invoicing.md#deviations).

### Web

New screens:

- `/inventory/cost-explanation` ("Why this cost?"; `inventoryValuation.read`).
- `/costing/close` (`costingClose.read`).
- `/costing/profitability` (`profitability.read`; includes the simulation).
- `/procurement/grni` (`goodsReceipt.read`).

Additions to existing screens:

- Posting Monitor: abandon a job.
- Cost operations: quarry unwind / amortise for a period.
- Process costing: journal-entry reference.
- Manufacturing order cost: the variance breakdown and reconciliation.
- BOM stage set-up and the default allocation rule: the NRV method.
- Production order: the rework treatment.

Checked with `pnpm web:typecheck`, lint and the i18n check. Not exercised in a browser.
