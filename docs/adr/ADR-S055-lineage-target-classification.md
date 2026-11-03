# ADR-S055 — One account per lineage target, for posting and release (PC-1 WP1)

- Status: accepted (owner decision V1, 2026-11-03); built in PC-1 WP1.
- Phase: [`PC-1-valuation-correctness.md`](../phases/PC-1-valuation-correctness.md)
- Amends: [ADR-S031](ADR-S031-valuation-integrity-cost-lineage.md) §3–§5 (classification of consumed shares)
- Findings: V1, V2, V3 ([GL trace](../audits/costing-gl-trace.md))
- Extension: no NestJS counterpart (the lineage engine is Spring-only). No route, request or response changes.

## Context

A late cost or an open adjunct reaches the GL by two paths:

1. **At posting**, `LineageCapitalisation.accountFor` classifies each consumed share. Callers: landed-cost vouchers,
   accruals, the cost-adjustment run, distribution freight and quarry.
2. **Later**, `CostLineageService.release` (`POST /inventory/price-differences/release`) classifies the share of an open
   1396 / 1397 adjunct consumed since its anchor.

Each path had its own switch, and they disagreed:

| Finding | Before | ADR-S031 |
|---|---|---|
| **V1** | Release sent the consumed **NRV allowance** to NRV expense (5975), whatever consumed it | "released to cost of sales as the goods are consumed" |
| **V2** | Release sent the consumed adjunct of a **scrap** move to cost of sales, while posting sent scrap to stock adjustment expense | Scrap is its own target |
| **V3** | A pool at or below zero sent the whole open amount to cost of sales, without a stated policy | — |

V1 kept the P&L total, but split it wrongly: cost of sales was understated and NRV expense overstated, by the
consumed allowance. V2 made the classification of the same late cost depend on when it was recognised.

## Decision

`CostLineageService.consumptionAccount(target)` is the single target → account function. Posting and release both use
it, and only the caller decides what "stays in inventory" means (the adjunct it holds).

| Target | 1396 price difference | 1397 NRV allowance | Late cost at posting |
|---|---|---|---|
| on hand | stays open | stays open | 1396 (STANDARD) / layer (AVCO, FIFO) |
| `transfer` | stays in inventory | stays in inventory | 1396 |
| `wip` (open production order) | WIP | WIP | WIP |
| `scrap` (waste, expiry, internal use, promo) | stock adjustment expense | stock adjustment expense | stock adjustment expense |
| `cogs` (sale, issue, closed order, return to supplier until WP5) | cost of sales | **cost of sales** | cost of sales |
| `negative_stock` / pool at or below zero (V3) | cost of sales | cost of sales | cost of sales |

- **V1:** the consumed allowance is released Dr 1397 / Cr cost of sales. Sale of written-down goods therefore shows
  cost of sales at the written-down carrying amount; the write-down itself stays in NRV expense (5975). The total P&L
  is unchanged.
- **V2:** a scrapped share is stock adjustment expense on both paths.
- **V3 policy:** when the pool holding an open adjunct has no quantity left (or is negative), nothing remains to carry
  it, so the whole open amount is consumed and goes to cost of sales. WP6 adds the negative-stock tests; a different
  policy needs a new owner decision.
- NRV **recovery** remains IAS 2.33-capped: the remeasurement reverses at most the allowance still open, so after a
  partial release only the unreleased part can come back.
- Reversal of an NRV document stays refused once any part was released (ADR-S031 §5).

## History

- Posted journals are never re-posted or rewritten. Releases posted before this change keep their classification
  (5975). The change applies to release runs posted from the WP1 commit on.
- A release run re-anchors every open amount, so nothing released before the change is released again.

## Tests

`CostingAuditIT` (AVCO, exact classification) and `LandedCostAuditIT` (STANDARD adjunct):
- NRV write-down → partial sale → release (allowance → cost of sales) → second sale → release → full sale → release
  (allowance fully released).
- Recovery after a partial release, capped at the open allowance.
- Reversal refused after a release; a new NRV review instead.
- Scrap after a write-down → stock adjustment expense.
- STANDARD adjunct: partial, second and full sale releases to cost of sales; scrap release to stock adjustment expense.
