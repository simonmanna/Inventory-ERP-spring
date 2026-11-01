# ADR-S032 — Accounting policy governance, Costing & Valuation settings, cost eligibility, branch dimension (CM-2)

- Status: accepted (CM-2 built 2026-11-01)
- Plan: `docs/roadmap/cost-management-plan.md` (rev 3), AD-4, AD-6 (dimension part), AD-10, C3
- Migration: `V225__cm2_accounting_policy.sql`
- Extension: no NestJS counterpart.

## Context

Costing configuration was scattered across five places:

- setting-registry keys
- the dated inventory costing policy
- account mappings
- landed-cost components, edited on the reports page
- module settings

Accounting treatments could be changed freely and with no record of why. Nothing stopped an operational expense from
being capitalised. The owner asked for:

- a single source of truth
- treatments limited to what the standards permit, with governance on any change
- a branch dimension that can be traced on costing postings

## Decision

### 1. Governed accounting policy elections (AD-4) — `AccountingPolicyService` (accounting)

`AccountingPolicy` rows hold: election, scope, value, `effectiveFrom`, status `proposed → approved | rejected`, an
IAS 8 justification, proposer and approver.

**Catalogue.** Allowed values only.

| Election | Standard | Scope | Values |
|---|---|---|---|
| `variance_posting` | IAS 1 | org | split, summary |
| `price_difference_tolerance_pct` | IAS 2.21 | org | 0–50 |
| `materiality_threshold` | IAS 8 | org | ≥ 0 |
| `ppv_lineage_split` | IAS 2.21 | org | off (default for existing orgs), on |
| `joint_cost_method` | IAS 2.14 | org / process | quantity, weight, market_value, fixed_pct |
| `byproduct_valuation` | IAS 2.14 | org / process | nrv, zero |
| `stripping_cost` | IFRIC 20 | org / quarry | inventory, stripping_asset (refused until the CM-5 assessment exists) |
| `project_revenue_recognition` | IFRS 15.35 | org / contract | point_in_time, over_time_cost_to_cost (refused until the CM-8b criteria assessment) |
| `cost_eligibility` | IAS 2.10–2.16 | account / cost_component / cost_pool | the AD-10 classes |

**Forbidden choices cannot be expressed:**

- `cost_formula` / LIFO is refused (IAS 2.25). The cost formula stays on the dated inventory costing policy page.
- An effective date in the past is refused: changes apply prospectively.
- A justification under 10 characters is refused.
- Approving your own proposal returns 403.

**Resolution.** The value in force is the scope's latest approved election effective today, else the organisation's,
else the catalogue default.

### 2. Cost eligibility (AD-10)

The classes are:

- `production_capitalisable`
- `production_abnormal`
- `acquisition_capitalisable`
- `transfer_capitalisable`
- `distribution`
- `selling`
- `admin`
- `storage`
- `financing`

Only the three `*_capitalisable` classes may be capitalised.

`assertCapitalisable(subject, …, requireClassified)`:

- **Landed-cost components** (`requireClassified = false`, for back-compat): a component explicitly classified as
  not capitalisable cannot be set to, or used with, treatment `capitalize`.
- **Absorption and allocation into inventory** (CM-4 onward, `requireClassified = true`): unclassified subjects are
  refused too. Nothing is assumed capitalisable.

System defaults, idempotent and system-approved, for the seeded components:

| Component | Class |
|---|---|
| duty, freight, insurance, handling | acquisition capitalisable |
| DEMURRAGE | abnormal |
| SELLING | selling |
| ADMIN | admin |

There is a review list of unclassified expense and cost-of-sales accounts.

### 3. Costing & Valuation settings — `CostingSettingsService` (costing): a façade, not a store

Every value is read from and written to the place that already owns it:

| Setting | Owner |
|---|---|
| Default costing method, negative stock | setting registry |
| `costing.landedCost.enabled`, `costing.landedCost.defaultAllocationMethod`, `costing.dimensionEnforcement` | setting registry (new keys) |
| Cost formula per category | inventory costing policy (shown read-only, changed on its page) |
| Costing accounts | account mappings. The Cost Management keys are set with `force`, because they are not in the Nest registry |
| Landed-cost components and their eligibility | procurement (components) and accounting (eligibility) |
| Elections | accounting policy |

Other points:

- `POST /costing/settings/accounts/ensure` creates the fallback accounts (1396, 1397, 5975, 5968) and their
  mappings. They are created on demand, not by a per-org migration, because charts of accounts differ.
- Switching landed costs off refuses new vouchers with "Landed costs are switched off in Costing & Valuation settings".
- "Duplicate writer" finding: `CompanySettingsService` already wrote through `SettingsService` to the same registry
  key. There is a single store; no change was needed.

### 4. Branch dimension (AD-6, dimension part) — `CostingDimensionGuard` (accounting)

The guard applies to postings whose source type is a costing source:

- landed cost
- revaluation and NRV
- release
- material ledger
- standard cost
- cost policy
- production variance
- cost allocation

| Mode | Behaviour |
|---|---|
| `off` | No check |
| `warn` (default) | Postings without a branch appear in `GET /costing/dimension-compliance` |
| `enforce` | A costing posting with a line that has no branch is refused |

Ordinary sales and cash postings are never affected. Location-level GL accounts and same-entity branch transfers
come with CM-7.

## Routes

| Verb | Path | Permission |
|---|---|---|
| GET / PUT | `/costing/settings` | `costing_settings:read` / `costing_settings:manage` |
| POST | `/costing/settings/accounts/ensure` | `costing_settings:manage` |
| GET | `/costing/dimension-compliance?from&to` | `costing_settings:read` |
| GET | `/costing/policies/catalogue`, `/costing/policies`, `/costing/policies/:id`, `/costing/eligibility` | `accounting_policy:read` |
| POST | `/costing/policies` | `accounting_policy:manage` |
| POST | `/costing/policies/:id/approve`, `/reject` | `accounting_policy:approve` |

Web: `/costing/settings`, with tabs Settings, Accounting policies, and Revaluation & NRV (the CM-1 documents).

## Deferred, with the reason

| Item | Deferred to | Reason |
|---|---|---|
| Location-specific GL accounts (`InventoryPostingRule.branchId/locationId`) | CM-7 | Needed together with same-entity branch transfers |
| Defaulting the branch from the stock location on costing postings | CM-7 | Until then use `warn` mode to measure the gap |
| A migration that seeds accounts for every org | — | Replaced by `accounts/ensure` because charts differ per organisation |

## Tests

`CostingSettingsIT` (5):

- façade round-trip (the registry sees the same value), and landed costs switched off blocks vouchers
- governance: LIFO, stripping asset, over-time revenue, short justification, past date, out-of-range value; maker ≠
  checker; in force; tenant isolation
- eligibility: a storage-classified component cannot be capitalised
- dimensions: enforce refuses, warn lists the posting
- authorization matrix
