# ADR-S040 — Profitability layers and GL reconciliation (Cost Management CM-9)

- Status: accepted (CM-9 built 2026-11-02)
- Plan: `docs/roadmap/cost-management-plan.md` (rev 3), G11, AD-7
- Migration: `V233__cm9_profitability.sql`
- Extension: no NestJS counterpart. Service in `costing`; routes under `/costing/profitability`.

## Context

- Six separate estimate reports computed margins their own way. None tied to the GL and none had an unallocated
  bucket.

## Decision

### 1. One fact table, three layers that are never mixed

`ProfitabilityFact(layer, measure, branch, cost centre, customer, product, project, channel, amount, statistical)`,
built per period by `POST /costing/profitability/runs`. A rebuild of the same period supersedes the previous run.

| Layer | Source |
|---|---|
| `actual` | posted GL lines of the period on P&L accounts (classification revenue / expense) |
| `standard` | posted sales-invoice quantities × standard price (product sales price) and × standard cost (active `ProductStandardCost`, else cost price) |
| `estimate` | approved project budgets (revenue and cost per project) |

Every read takes **exactly one layer**: `GET /runs/:id/facts?layer=` is required and must be `actual`, `standard` or
`estimate`. No API returns or sums layers together.

### 2. ACTUAL classification

| Account report section | Measure |
|---|---|
| revenue, contra revenue | revenue |
| cost of sales, code 59xx | variances |
| cost of sales | cogs |
| operating expense, code 6460 | distribution |
| operating expense | overhead |
| other income, other expense | **out of scope** (listed per account) |

- **Segment** = customer, product or project, read from the line dimensions (`customerId`, `productId`, `projectId`).
  The partner counts as the customer on revenue and cost-of-sales lines only. Branch, cost centre and channel are
  attributes that ride along.
- A P&L line with no segment is **unallocated** (listed per account).

### 3. Contribution margins

- CM1 = revenue − cost of sales
- CM2 = CM1 − variances − distribution
- CM3 = CM2 − overhead

Grouped by customer, product, project, branch, cost centre or channel.

### 4. Reconciliation (exact)

```
Σ ACTUAL segment facts + unallocated + out of scope = GL net income of the period
```

- The run stores each term and the `difference`, which must be 0.
- `GET /runs/:id/reconciliation` lists the unallocated and out-of-scope accounts.

### 5. Statistical enrichment

- Posted STATISTICAL allocation runs (CM-3) of the period become `statistical_overhead` facts per receiving cost
  centre, with `statistical = true`.
- They are shown apart, never enter CM3 and are excluded from the reconciliation (`statisticalExcluded`).

## Permissions

`profitability:read`, `profitability:manage`.

## Deferred

- A `layer=` parameter on the six existing estimate reports (their routes and defaults are unchanged).
- Location and channel dimensions are read only when a posting carries them; no posting path writes `channel` yet.
- biexport / analytics feeds of the facts.

## Tests

- `ProfitabilityReconciliationIT` (2):
  - Clean books: segments 220 + unallocated −70 + out of scope −20 = GL 130; CM1 / CM2 / CM3 by customer, product
    and project; ESTIMATE from an approved budget; layer required and never mixed; rebuild supersedes; tenant
    isolation; authorization matrix (5).
  - Demo books over 30 days reconcile to 0.
