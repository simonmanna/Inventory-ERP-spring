# ADR-S039 — Contract commercials: IFRS 15 and IAS 37 (Cost Management CM-8b)

- Status: accepted (CM-8b built 2026-11-01)
- Plan: `docs/roadmap/cost-management-plan.md` (rev 3), C3 IFRS 15 / IAS 37, R3-4, R3-5
- Migration: `V232__cm8b_contract_commercials.sql`
- Extension: no NestJS counterpart. Module `com.inventoryerp.projects`; routes under `/projects`.

## Decision

### 1. Recognition method (IFRS 15.35)

- `ContractCriteriaAssessment` records criteria (a), (b), (c) with evidence. A second person approves it
  (`contract:approve`, never the assessor).
- The governed election `project_revenue_recognition` (ADR-S032) now accepts `over_time_cost_to_cost` **only** at
  `contract` scope and only when an approved assessment meets at least one criterion. At organisation scope, or
  without the assessment, it stays refused. Without an over-time election the contract is point in time; no
  override exists.

### 2. Variations (contract modifications)

- `pending → approved | rejected`; maker ≠ checker.
- Modification type recorded on the variation: `cumulative_catch_up` (default), `prospective`, `separate_contract`.
- Transaction price = contract value + approved variations, except `separate_contract` (accounted for as its own
  contract). **Pending variations never enter the price**: a claim beyond the price is refused.
- An approved variation with a cost impact creates and approves a revised budget version (CM-8a).

### 3. Customer advances

- Invoiced to the customer, crediting **2480 Customer Advances – Contract Liability**.
- Recovered at the advance's % of each claim, never beyond what is outstanding.

### 4. Progress claims

Claim = cumulative certified − previously certified. Deductions: retention %, advance recovery. Certifying:

| Item | Posting |
|---|---|
| Net amount | posted sales invoice, Dr AR / Cr **2470 Contract Billings** (optional tax code) |
| Retention, conditional (completion certificate / end of defects period) | Dr **1475 Contract Asset – Retention** / Cr 2470 |
| Retention, passage of time only | a second invoice due at the release date (unconditional receivable; no financing component, IFRS 15.62(c)) |
| Advance recovery | Dr 2480 / Cr 2470 |

One draft claim per contract at a time; a certified claim cannot be certified again.

### 5. Retentions (R3-5) — `RetentionLedger`, both sides

| Side | Held as | Release |
|---|---|---|
| Customer, conditional | contract asset (1475) | needs the certificate reference; invoice crediting 1475 → receivable |
| Customer, time only | receivable (invoiced at certification) | after the release date; status only |
| Subcontractor | financial liability **2485 Retention Payable** | needs the certificate reference; vendor bill debiting 2485 for what is still held |

**Back-charges** on a held subcontractor retention: Dr 2485 / Cr 1460 project WIP (the project cost falls), capped at
what is still held.

### 6. Subcontracts and valuations

- `Subcontract` (supplier, WBS, value, retention %, release condition).
- A valuation books certified work **in full as project cost**: Dr 1460 (gross) / Cr 2485 (retention) / Cr **2486
  Subcontract Valuation Clearing** (net). A posted vendor bill for the net clears 2486.
- The gross also lands in the project cost sub-ledger (`project_subcontract`).
- A valuation cannot exceed the subcontract value.

### 7. Revenue recognition run (per contract and period end)

| Method | Progress | Cumulative revenue | Cumulative cost released |
|---|---|---|---|
| `over_time_cost_to_cost` | cost to date excluding `excludeFromProgress` ÷ (that + cost to complete) | price × progress | cost to date (progress cost) |
| `point_in_time` | 0, or 1 once the project is `completed` / `closed` | 0 or price | 0 or all cost |

- Cumulative catch-up against the previous posted run: Dr **1470 Contract Asset – Unbilled** / Cr **4610 Contract
  Revenue**, and Dr **5610 Contract Cost of Revenue** / Cr 1460. Negative deltas reverse the sides.
- One posted run per period end; periods only move forward; only the latest run can be reversed.
- Contract position (net per contract): revenue recognised − gross billed → **contract asset** if positive, else
  **contract liability**. Retentions, advances and the provision are shown beside it.

### 8. Onerous contracts (R3-4; IAS 37.66–69, 68A)

1. An indicator is required (`negative_margin, eac_increase, dispute, penalty_notice, management_flag`). EAC above
   revenue does not by itself create a provision.
2. Expected benefits = remaining transaction price (price − revenue recognised) + highly probable claims.
3. Cost of fulfilling = cost to complete tagged `incremental` + `directly_related`; `excluded` (G&A not chargeable)
   never counts.
4. Least net cost of exiting: the exit route costs the penalty and forgoes the benefits, so the exit is chosen when
   penalty < fulfilling − benefits. A cost of exiting needs evidence.
5. Impairment first: losses already recognised on dedicated assets (with a reference) reduce the provision.
6. Provision = max(0, unavoidable − benefits − impairment) — the exit-based amount (the penalty) when the exit is
   cheaper.
7. `draft → approved` (second person) `→ posted`. Posting books the **movement** against the provision carried:
   Dr **6990 Onerous Contract Expense** / Cr **2490 Onerous Contract Provision**, or the reverse to utilise or release.

**Clarification of the plan's formula.** The plan writes `min(fulfil, exit)` against benefits. Taken literally, that
would subtract benefits the entity forgoes by exiting. The implementation compares the net cost of fulfilling with
the penalty (IAS 37.68, "least net cost"). This gives the plan's exit test: the provision equals the exit-based
amount.

### 9. Tie-outs

`GET /projects/:id/contract-position` includes an organisation-wide sub-ledger vs GL tie-out for 1475, 2485, 2480 and
2490. Each difference must be 0. The CM-8a WIP tie-out now subtracts the cost released by revenue runs.

## Accounts

| Code | Name | Category | Mapping key |
|---|---|---|---|
| 1470 | Contract Asset - Unbilled Revenue | current_asset | `contract_asset_unbilled` |
| 1475 | Contract Asset - Retention | current_asset | `contract_asset_retention` |
| 2470 | Contract Billings | current_liability | `contract_billings` |
| 2480 | Customer Advances - Contract Liability | current_liability | `contract_advances` |
| 2485 | Retention Payable | current_liability | `retention_payable` |
| 2486 | Subcontract Valuation Clearing | current_liability | `subcontract_clearing` |
| 2490 | Onerous Contract Provision | current_liability | `onerous_provision` |
| 4610 | Contract Revenue | revenue | `contract_revenue` |
| 5610 | Contract Cost of Revenue | cost_of_goods_sold | `contract_cost_of_revenue` |
| 6990 | Onerous Contract Expense | operating_expense | `onerous_expense` |

## Permissions

`contract:read`, `contract:manage`, `contract:post`, `contract:approve`.

## Deferred

- IFRS 9 expected credit loss on retention assets and receivables (needs the credit-loss model in invoicing).
- Current / non-current split of retentions by expected release date (data is captured; presentation not built).
- Discounting a material onerous provision; the fixed-asset impairment hook (the impairment amount and its reference
  are entered).
- Significant-financing-component assessment of advances beyond the ≤ 12-month expedient.
- Separate-contract variations do not open a new project automatically.
- Web screens (APIs only in this phase).

## Tests

- `ContractCalculatorTest` (3): progress, claim deductions, onerous (fulfilment, cheaper and dearer exit,
  impairment first, claims).
- `ContractRevenueScenarioIT` (2):
  - The full contract: criteria gating the election, approved vs pending variation, advance, two claims with
    conditional and time-only retention, cost-to-cost revenue and contract liability, subcontract valuation,
    back-charge, release on evidence, customer retention release, onerous provision recognised then released,
    run reversal, WIP and GL tie-outs, tenant isolation, authorization matrix (27 routes).
  - Point in time recognising everything only on completion.
