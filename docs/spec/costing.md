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
