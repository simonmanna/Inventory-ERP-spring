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
