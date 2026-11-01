# ADR-S041 — Cost close, cost adjustment run, costing period lock (Cost Management CM-10)

- Status: accepted (CM-10 built 2026-11-02); the material-ledger rework (AD-3) is deferred, see below
- Plan: `docs/roadmap/cost-management-plan.md` (rev 3), G13, AD-2a rule 5, AD-3
- Migration: `V234__cm10_cost_close.sql`
- Extension: no NestJS counterpart. Services in `costing`; routes under `/costing/close` and
  `/costing/cost-adjustment-runs`.

## Decision

### 1. Ordered close gates — `GET /costing/close/gates?from&to`

Each gate is `pass`, `fail`, or `n/a` (nothing of that kind exists for the organisation). The first failing gate is
returned as `blocking`.

| # | Gate | Passes when |
|---|---|---|
| 1 | Landed costs complete | no draft landed-cost document dated up to the period end |
| 2 | Absorption settled | a posted absorption settlement overlaps the period (only where work-centre capacities exist) |
| 3 | Allocation cycles posted | every active cycle has a posted run reaching the period end |
| 4 | NRV review done | a posted NRV review dated in the period (only where stock is on hand) |
| 5 | Stripping amortisation and provision unwinding posted | unwinding in the period for open rehabilitation obligations; amortisation for approved stripping assets |
| 6 | Project revenue recognition run | every active / completed contract (with a customer and cost) has a posted run up to the period end |
| 7 | Cost adjustment run done | no late WIP share is waiting (§3) |
| 8 | Profitability rebuilt and reconciled | a current CM-9 build of exactly this period with difference 0 |
| 9 | Reconciliations at zero | inventory sub-ledger vs GL within 0.01; inter-branch clearing 1299 nets to 0; every project's WIP vs its cost sub-ledger; retentions, advances and onerous provision vs GL |

`GET /costing/close/dashboard?asOf` shows the reconciliation rows on their own.

### 2. Costing period lock

- `POST /costing/close/locks` locks the period only when no gate fails (409 names the blocking gate). Locks cannot
  overlap. The gate results are stored with the lock.
- While locked, **every costing posting dated inside the period is refused**: the guard in `PostingService` covers all
  costing source types, including the CM-8 project and contract sources and the cost adjustment run.
- `POST /costing/close/locks/:id/reopen` needs `costing_close:reopen` and a reason of at least 10 characters, which is
  audited.
- The fiscal-period close checklist gains an **advisory** item, `costing_close`: costing entries of the period outside
  a locked costing period. It is advisory so existing fiscal closes are not blocked retroactively. The costing lock
  is the hard control.

### 3. Cost adjustment run (AVCO / FIFO late costs)

- A later adjustment distributed through lineage while the consuming production order was open puts its share in WIP
  (`CostValueAdjustmentLine.target = 'wip'`). Once the order completes, that amount is stranded: the output was
  costed without it.
- The run takes every such share of a completed or closed order and spreads it over the order's output receipt layers
  (`InventoryLedger.referenceType = 'production_order'`) through their lineage (`LineageCapitalisation`):

  | Where the output is | Posting |
  |---|---|
  | on hand | 1396 adjunct, with a value-only stock-ledger row |
  | sold | cost of sales |
  | consumed again (open order) | WIP |
  | scrapped | scrap |
  | no output receipt | cost of sales |

  The run credits WIP. The result is one journal entry dated in the open period (the period end, or today if earlier).
- Each share cascades once (unique index on `CostAdjustmentRunLine.adjustmentLineId` among posted lines; AD-2a "no
  double counting").
- A run reverses while nothing it capitalised has been released; reversed shares become pending again.
- Depth: one production step per run; a share that lands in WIP of a further open order is picked up by a later run
  once that order completes.

## Permissions

`costing_close:read`, `costing_close:manage`, `costing_close:reopen`.

## Deferred

- **Material-ledger rework (AD-3)**: reading lineage for STANDARD items, multi-level `ml_price_diff` roll-up through
  `InventoryValuationApi`. The existing material-ledger run stays "STANDARD only" (ADR-S030 amendment).
- The complete C0 cost-flow bridge as a gate (actual by nature = capitalised + expensed + settled + unallocated per
  cost element). Gate 8 (profitability reconciliation) and gate 9 cover the GL-level completeness for now.
- Prior-period posting of late costs into an open earlier period (governed); runs always post in the open period.
- Close-cockpit web screen.

## Tests

- `CostCloseIT` (2):
  - On clean books, gates block in order: allocation (3) → revenue run (6) → profitability (8). Then ready; lock;
    overlapping lock refused; a costing posting into the locked period refused; reopen refused without
    `costing_close:reopen` and without a reason; reopened; posting works again. Tenant isolation.
  - Cost adjustment run on a completed order with 4 of 10 outputs sold: WIP relieved 100, adjunct 60 on hand, cost of
    sales 40; `Idempotency-Key` required; second run has nothing to adjust; reversal makes the share pending again;
    authorization matrix (10).
