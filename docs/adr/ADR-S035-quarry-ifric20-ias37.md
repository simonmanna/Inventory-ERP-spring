# ADR-S035 — Quarry costing: area dimension, IFRIC 20 stripping, IAS 37 rehabilitation (Cost Management CM-5)

- Status: accepted (CM-5 built 2026-11-01)
- Plan: `docs/roadmap/cost-management-plan.md` (rev 3), R3-3, C3 (IFRIC 20, IAS 37)
- Migration: `V228__cm5_quarry_ifric20_ias37.sql`
- Extension: no NestJS counterpart.

## Context

- Quarry extraction capitalised its cost into ROM stock, but there was:
  - no IFRIC 20 treatment of production-phase stripping
  - no IAS 37 rehabilitation provision
  - no cost-per-tonne that included either of them
- Quarry areas existed only as an operational zone / bench / face list, with no pit level, lifecycle or reserves.

## Decision

### 1. Area dimension (R3-3)

- The existing `QuarryZone` hierarchy becomes the area dimension, rather than adding a second `QuarryArea` table:
  **site → pit → zone → bench → face**. A zone is the top level or sits in a pit; a pit has no parent.
- Each area gains:
  - `areaStatus`: planned, development, production, exhausted, rehabilitating or rehabilitated
  - `disturbedAreaM2`, increased by production-disturbance movements
  - `reservesTonnes`
  - `oreBodyComponent` (the IFRIC 20 component)
- `quarryAreaId` and `quarrySiteId` are journal-line `dimensions` on every stripping and rehabilitation line. Those
  lines also carry the site's cost centre and branch.
- Area-based work aggregates the area **and every area below it**. For example, extraction posted on a bench counts
  for its zone.

### 2. IFRIC 20 stripping-activity asset — `QuarryCostingService`

**Assessment.** IFRIC 20.9 is enforced:

- Benefit probable, component identified and cost measurable must all be true. Otherwise the asset is refused.
- The asset records the expected ore and waste tonnes of the component and an assessment text of at least 20
  characters.
- It is approved by someone other than the proposer.

**Election.** `stripping_cost = stripping_asset` can now be proposed for a quarry once an approved assessment
exists for that site, or at organisation scope once any exists. Capitalisation requires the election to be in force
for the quarry.

**Capitalise** (period, stripping cost, waste tonnes, the expense account it was booked to):

1. Ore produced = net tonnes of posted extraction logs in the component in the period.
2. Waste above `ore × expected ratio` builds the asset: Dr **1660** / Cr the expense account.
3. The rest is reported as the inventory share and stays production cost.
4. If no waste exceeds the ratio, the capitalisation is refused (IFRIC 20.8).

**Amortise** (units of production): `carrying × ore in period / remaining expected ore`, capped at the carrying
amount. It goes into the **period's ROM layers through lineage** (on hand → 1396 adjunct, sold → cost of sales),
Cr **1661**.

**Reversal.** Only the latest movement can be reversed. An amortisation is not reversed once its share has been
released.

### 3. IAS 37 rehabilitation

`QuarryRehabObligation` is per area. It holds a pre-tax discount rate and an optional estimate per m².

There are three provision layer origins:

- `initial`
- `production`
- `remeasurement`, linked to the layer it changes

Each movement has its own document row and GL route:

| Movement | Posting |
|---|---|
| Initial | Dr 1665 Rehabilitation Asset (IAS 16.16c) / Cr **2690 Rehabilitation Provision** |
| Unwind (`from`–`date`) | `balance × rate × days / 365`. Dr **7150 finance cost** / Cr 2690. **Never inventory** (IAS 23) |
| Production disturbance (amount, or m² × estimate) | Dr the period's ROM layers of the area through lineage / Cr 2690. Adds the m² to the area |
| Remeasurement of an `initial` layer (IFRIC 1) | Increase: Dr 1665, and the movement is flagged as an **IAS 36 impairment indicator**. Decrease: Cr 1665 up to the asset carried, the excess to **5368** (P&L) |
| Remeasurement of a `production` layer | P&L **5368**. Inventory already produced is not re-costed |
| Utilisation | Dr 2690 / Cr cash, bank or payables. Refused above the balance |

Integrity:

- Only the latest movement can be reversed.
- A production movement cannot be reversed once its share has been released.
- `GET /quarry/rehab-roll-forward` gives, per obligation: opening + initial + production + unwind ± remeasurement −
  utilisation = closing. It compares the closing balance with the provision account's lines in the movements' own
  journals and reversals, and the difference must be 0.

### 4. Cost per tonne — one definition

`GET /quarry/cost-kpis`, per area, counts:

- the extraction log components: fuel, labour, equipment, blasting, royalty, other
- stripping amortisation
- the rehabilitation production increment

These are divided by net tonnes.

### 5. Shared lineage capitalisation

`LineageCapitalisation` (costing) splits a later cost over specific receipt layers by value. Each share then follows
its layer's lineage. The component records the value-only rows and adjustments and reverses them. Stripping
amortisation and rehabilitation production use it, and CM-6 / CM-7 reuse it. The CM-4 settlement keeps its own
equivalent copy, which is tested and committed.

### 6. Allocation-engine exception register review (CM-5)

| Engine | Decision |
|---|---|
| `QuarryCostCalculator` | **Keep.** Its net / gross-tonne split of one log's own cost between ROM and losses is a capitalisation rule, not a pool allocation. The per-tonne KPI moved to `/quarry/cost-kpis` |
| `JointCostAllocator` | **Keep.** Its carve-out and rounding remain the policy layer. Delegating the rounding needs a golden test on crushing data; review again in CM-10 |

### 7. Quarry → crusher link

The ROM receipt row is a lineage layer. Crushing consumption is an outbound row of the same pool, so
`GET /inventory/cost-layers/:id/trace` already follows extraction → crushing feed → crushed products. No separate
listener is added.

## Routes

| Verb | Path | Permission |
|---|---|---|
| GET / PUT | `/quarry/areas`, `/quarry/areas/:zoneId` | `quarry_site:read` / `quarry_site:update` |
| GET | `/quarry/stripping-assets`, `/:id` | `quarry_extraction:read` |
| POST | `/quarry/stripping-assets` | `quarry:settings` |
| POST | `/quarry/stripping-assets/:id/approve` | `accounting_policy:approve` |
| POST | `/quarry/stripping-assets/:id/capitalise`, `/amortise`, `/quarry/stripping-movements/:id/reverse` (Idempotency-Key) | `quarry_extraction:post` |
| GET | `/quarry/rehab-obligations`, `/:id` | `quarry_extraction:read` |
| POST | `/quarry/rehab-obligations` | `quarry:settings` |
| POST | `/quarry/rehab-obligations/:id/movements`, `/quarry/rehab-movements/:id/reverse` (Idempotency-Key) | `quarry_extraction:post` |
| GET | `/quarry/rehab-roll-forward?from&to&siteId`, `/quarry/cost-kpis?from&to&siteId` | `quarry:report` |

## Deferred, with the reason

| Item | Deferred to | Reason |
|---|---|---|
| Depreciation of the rehabilitation asset by units of production over reserves | Fixed-asset integration (CM-10) | The asset is carried at cost here; depreciation belongs to the fixed-asset register |
| Requiring an area on **every** extraction log | Owner decision | Existing logs and clients post without a zone. Stripping and rehabilitation always carry the area |
| Crushing cost per tonne per plant / stage and block cost per unit in the same KPI service | CM-9 profitability | They need the profitability fact model to avoid a third definition |
| `RateResolver` fallback to crushing / quarry rate fields | CM-9 | Same reason |
| Discounting the initial estimate inside the system | — | The user enters the present value, as IAS 37.45 requires; the rate drives the unwinding |

## Tests

`QuarryRehabMovementsIT` (3):

- **rehabilitation:** initial 1000 (1665 / 2690), production 50 m² × 2 into the ROM adjunct, unwind 0.30 to 7150 with
  no inventory line, remeasurement −1050 (1000 off the asset, 50 to P&L), production-layer +40 to P&L, +20 flagged
  for impairment, over-utilisation refused, utilisation 30; every line carries the area; roll-forward 80.30 ties to
  the GL; out-of-order reversal refused; reversal keeps the tie; tenant isolation
- **IFRIC 20:** missing criterion refused, maker-checker, no election refused, 500 cost / 400 t waste / 100 t ore at
  2:1 → asset 250, UoP amortisation 25 into the ROM adjunct, KPI 1.25 per tonne, out-of-order reversal refused,
  amortisation reversal
- **areas:** pit level validation, lifecycle validation, tenant isolation, authorization matrix (16 routes)

Added to `accountingRegression`.
