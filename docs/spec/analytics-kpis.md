# Analytics: management dashboards and KPI formulas

Track C Phase 5, requirements §10 and §2.7. Module `com.inventoryerp.analytics`, design in
[ADR-S020](../adr/ADR-S020-analytics-dashboards.md). Read only: no table of its own, no events, nothing depends on it.

**Status: built, nothing compiled or run.** Every figure below still needs checking against real data
(`docs/VERIFY_CHECKLIST.md`).

## Rules common to every KPI

- **Period.** `from` and `to` are ISO business dates; the default is the month to date in `APP_TIMEZONE`. `from` after
  `to`, or a period of two years or more, is a 400.
- **Missing denominator.** A ratio with a zero or negative denominator is null and shows as a dash, never as 0.
- **Rounding.** `BigDecimal`, `HALF_UP`. Percentages have 2 decimals and rates 4, unless the source report rounds first.
- **Tenancy.** Every query filters on `organizationId`.
- **Data scope.** A user with `UserDataScope` grants sees their branches only; `branchId` must be one of them (403) and
  must exist (400). A row with no branch is outside a branch-limited view. Sites follow `QuarrySite.branchId`, plants
  follow their site, block orders and stock follow `InventoryLocation.branchId`, vehicles follow
  `FleetVehicle.branchId`, trips follow the delivery order's branch and then its location's branch.
- **Filters a dashboard cannot apply.** The response lists `supportedFilters` and `ignoredFilters`; the screen disables
  the first kind and names the second. A filter is never silently dropped.
- **Sites.** `siteId` is a quarry site, `deliverySiteId` a customer delivery site. `partnerId` is the customer, and on
  the procurement dashboard the supplier.

## Filters per dashboard

| Dashboard | Date | Branch | Site | Delivery site | Product | Customer / supplier |
|---|---|---|---|---|---|---|
| Quarry | yes | yes | yes | no | material's product | no |
| Aggregates | yes | yes | yes | no | feed or output product | no |
| Blocks | yes | yes | no | no | output product | no |
| Inventory | discrepancies only | yes | stockpile locations | no | yes | no |
| Procurement | yes | yes | no | no | spend and price trend | supplier |
| Sales | yes | yes | no | no | yes | customer |
| Fleet and delivery | yes | yes | no | yes | trips carrying it | customer |
| Maintenance | yes | yes | no | no | no | no |
| Finance | yes | yes | no | no | no | no |
| Branch performance | yes | yes | no | no | no | no |

## Routes

Every route is `GET`. A dashboard is `/analytics/<path>`; its drill-down is `/analytics/<path>/documents?kpi=<key>` with
the same filters plus `page` and `pageSize` (default 25, at most 200). Both need the dashboard's permission.

| Path | Permission | Module gate | Drill-down keys |
|---|---|---|---|
| `/analytics/filter-options` | `analytics:view` | none | none |
| `/analytics/quarry` | `analytics:quarry` | `quarry` | `extraction-logs` |
| `/analytics/aggregates` | `analytics:aggregates` | `manufacturing` | `crushing-runs` |

Administrators receive every analytics permission through V100. Other roles get them in Staff, Roles & Permissions.

## Quarry operations

Source: `QuarryReportService` over posted, non-deleted extraction logs, with a site list and product added for analytics.

| KPI | Formula |
|---|---|
| Extraction volume | Σ `netTonnes` of posted logs with `extractedOn` in the period |
| Extraction cost | Σ `totalCost` of those logs (fuel, labour, equipment, blasting, royalty, other) |
| Extraction cost per tonne | extraction cost ÷ extraction volume |
| Extraction loss | Σ `lossTonnes` ÷ Σ `grossTonnes` × 100 |
| Target tonnes | Σ `targetTonnes` of plans that are not drafts and overlap the period |
| Target achievement | Σ plan actual tonnes ÷ target tonnes × 100 |

A plan's actual tonnes cover the plan's whole period, not the dashboard period, so a plan that overlaps the period
counts in full. The screen says so. The product filter keeps logs and plans whose material is stocked as that product,
so a plan with no material drops out.

## Aggregate processing

Source: `CrushingReportService` over posted, non-deleted crushing runs, with a plant list and product added for analytics.
Recirculated oversize is not feed or output here: runs store it apart.

| KPI | Formula |
|---|---|
| Feed volume | Σ `feedTonnes` of posted runs with `runDate` in the period |
| Production volume | Σ `outputTonnes` of those runs |
| Input-to-output yield | production volume ÷ feed volume × 100 |
| Material loss | Σ `lossTonnes` of those runs |
| Material loss % | material loss ÷ feed volume × 100 |
| Cost per tonne | Σ `totalCost` of the runs ÷ production volume |

The product filter keeps runs that fed or produced the product, and each KPI then covers the whole run, so the
per-product table is the place to read one product's tonnes.
