# ADR-S075 — Contractor billing, committed cost, plant and overhead, site pricing

- Status: accepted (built 2026-11-10 on branch `phase/acc-contract`)
- Builds on: [ADR-S038](ADR-S038-project-core.md) (job costing), [ADR-S039](ADR-S039-contract-commercials.md)
  (contract commercials), the fleet dispatch / costing work (spec `docs/spec/fleet.md` 1.3, 1.9) and the central pricing
  engine ([ADR-S022](ADR-S022-central-credit-and-pricing.md)).
- Migrations: `V507__contract_sov_commitments.sql`, `V508__plant_rates_overhead.sql`,
  `V509__customer_site_projects_pricing.sql` (their header comments still say ADR-S072, the number this ADR had before
  it was renumbered at merge; the files are not edited, so applied checksums stay valid).
- Extension: no NestJS counterpart. Modules `projects` (new `ContractorController`, 25 routes), `procurement`,
  `fleet`, `sales`, `invoicing` (additive). Spec [docs/spec/projects.md](../spec/projects.md).

## Context

Construction contractors bill against a **schedule of values** and certify interim payments on AIA G702 / G703-style
applications; claims were lump-sum only (`cumulativeCertified`). "Committed" in the cost report counted purchasing
only, with no WBS split, and ignored the unvalued balance of subcontracts — which have no variations of their own.
Plant was charged at a free-typed hourly rate with no credit to a recovery account; fleet trips and workshop work
orders could not reach a job; overhead was never absorbed. On the supplies side, delivery sites existed in fleet but
were not tied to a project and could not carry their own prices.

## Decision

1. **Schedule of values.** `ContractSovLine` per project (code, WBS, scheduled value, variation value, retention %).
   A client variation can name a line (`sovLineId`, raises its variation value on approval) or a new line
   (`sovCode` + `sovDescription`, inserted on approval). A claim takes either `cumulativeCertified` (unchanged) or
   `lines[]`; line claims store `ProgressClaimLine` rows with the G703 columns (C, D, E, F, G, %, H, retention) and
   roll up into the claim's existing totals, so certification, invoicing, retention and revenue runs are untouched.
   Retention per line = movement this claim × line % (else the claim %). `GET …/payment-application` returns the G702
   summary + G703 lines; the PDF (OpenPDF, A4 landscape) is rendered from the same map.
2. **Committed cost.** Committed = open `Commitment` rows of the project (WBS via `PurchaseRequestLine.wbsId`, also
   reached from PO lines through `requestLineId`) + for each active subcontract (value + approved
   `SubcontractVariation` − latest cumulative valuation, floor 0). Subcontract variations follow maker ≠ checker;
   valuations are capped at the adjusted value. The cost report adds a per-WBS block and split totals; EAC uses the new
   committed figure.
3. **Plant rates.** `ProjectPlantRate` (vehicle / asset / free code; per hour, day or km; validity window; cost
   centre). Equipment cost with `plantId` uses the rate in force; a manual rate needs the new permission
   `project:rate_override`. Postings: Dr 1460 WIP (equipment) / Cr **5392 Plant Recovery to Projects** (created on
   first use, `operating_expense`) carrying the vehicle's cost centre, so fleet profitability sees the recovery.
4. **Fleet to job.** Trips and work orders carry `projectId` / `wbsId` (tag routes in fleet; a trip also inherits the
   project of its delivery order / customer site). An own-carrier delivered trip is charged at the vehicle's plant
   rate, else at the fleet costing monthly rates (`FleetCostCalculator.allocate`); a completed work order at its posted
   line amounts. `ProjectCostLine.sourceType = project_fleet`, `sourceLineId` = trip / work order, unique while posted.
5. **Overhead absorption.** `ProjectOverheadRule` (project or org-wide; `direct_cost_pct` optionally per cost element,
   or `labour_hour`). `ProjectOverheadRun` is unique per project and posted period; a repeat returns the posted run.
   Dr 1460 WIP (overhead) per WBS / Cr **5350 Overhead Absorbed**; reversal reverses the entry and the cost lines and
   frees the period.
6. **Supplies side.** `FleetCustomerSite.projectId`, `FleetDeliveryOrder.projectId` (copied from the site),
   `Pricelist.customerSiteId`. Pricing tier `site_pricelist` after `promotion`; site lists are never defaults and only
   price their site. Invoices from delivery orders / site-tagged sales orders record `customerSiteId` and `projectId`.

## Consequences

- Existing routes keep their contracts; new request fields are optional. New routes live in `ContractorController` so
  the matrix counts of `ProjectController` (27) and `ContractController` (32) are unchanged; the project family is 141
  routes.
- Retention on line claims is no longer a single % of `thisClaim`; the claim row still holds the summed retention.
- Accounts 5392 and 5350 are created by code on first use (like other `ensureByCode` accounts); organisations with a
  custom chart can remap them by code.
- Not built: retention per line release, SOV import from a BoQ spreadsheet, overhead on a scheduled job (runs are
  manual or `POST /projects/overhead-runs` for all projects), web screens for SOV maintenance, plant rates and
  overhead rules (API only; the web shows committed by WBS and the payment application PDF).
