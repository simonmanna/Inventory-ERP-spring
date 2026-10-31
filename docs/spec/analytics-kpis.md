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
| `/analytics/blocks` | `analytics:blocks` | `manufacturing` | `precast-orders` |
| `/analytics/inventory` | `analytics:inventory` | none | `below-minimum`, `count-variances`, `stockpile-surveys` |
| `/analytics/procurement` | `analytics:procurement` | none | `purchase-orders` |
| `/analytics/sales` | `analytics:sales` | none | `invoices`, `receivables` |
| `/analytics/fleet` | `analytics:fleet` | `fleet` | `deliveries`, `fuel-logs` |
| `/analytics/maintenance` | `analytics:maintenance` | none | `work-orders`, `downtime` |
| `/analytics/finance` | `analytics:finance` | none | `receivables`, `payables` |
| `/analytics/branches` | `analytics:branch` | none | `invoices` |

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

## Block manufacturing

Source: `PrecastReportService.costPerBlock` and `breakage`, with a branch list added for analytics. An order counts in
the period it was created, as in the precast reports, not the period it finished; the screen says so. The report reads at
most 500 orders per period, and the screen warns when it reaches that limit.

| KPI | Formula |
|---|---|
| Blocks produced | Σ good blocks posted at the cubing stage of those orders |
| Blocks pressed | Σ good off the press + broken at the press |
| Rejection rate | (broken at press + broken at cubing + second grade) ÷ blocks pressed × 100 |
| Second grade | Σ by-product quantity |
| Finished goods value | Σ value of the main output at cubing |
| Cost per acceptable block | finished goods value ÷ blocks produced |

## Inventory

Stock figures are as of now; only the discrepancies follow the period. Value, availability and the per-location table
are plain SQL over `StockItem`, because `InventoryReportsService.valuation` takes one location. Aging and slow / dead
stock come from `stockHealth`, ledger drift from `getStockReconciliation`. Those two run once per location for a narrowed
view, at most 25 locations, and the screen warns when that limit or the report's 5,000-row cap cut the figure short.

| KPI | Formula |
|---|---|
| Stock value | Σ `quantity × average cost` (cost price when the average is 0), as the valuation report values stock |
| Stock availability | active stock-tracked products with on hand > 0 ÷ active stock-tracked products × 100 |
| Below minimum | products with 0 < on hand ≤ `minQuantity` |
| Aged over 90 days | value of the stock layers older than 90 days (FIFO layers from the ledger, `stockHealth`) |
| Slow / dead stock | `stockHealth` value with no consumption for 90 / 180 days |
| Stock discrepancies | drifted cells (cached vs ledger vs batches) + non-zero count lines of sessions submitted in the period + posted stockpile surveys with a variance in the period |
| Discrepancy value | Σ `abs(variance) × cost price` of those count lines + Σ `abs(varianceValue)` of those surveys |

Availability's denominator is every tracked product, even in a branch-limited view, so a branch that does not stock a
product counts it as unavailable. Ledger drift has no value because the reconciliation reports quantities only.

## Procurement

`partnerId` is the supplier. Spend and prices are plain SQL over `PurchaseOrderLine`, on orders that are not draft or
cancelled with `orderDate` in the period. Supplier performance is `SupplierPerformanceService.report` with a branch list
added for analytics.

| KPI | Formula |
|---|---|
| Procurement spend | Σ line `subtotal × exchangeRate` (net of tax) |
| Purchase orders | distinct orders with a matching line |
| Purchase price trend | per month, Σ `unitPrice × quantity × exchangeRate` ÷ Σ `quantity`, for the selected product or the top 5 by spend |
| Purchase price change | (last month's price − first month's price) ÷ first month's price × 100 |
| Active suppliers | suppliers on the scorecard for the period |
| Supplier score | spend-weighted mean of the scorecard score (on-time 40, quality 30, fill rate 20, price 10) |
| Supplier on-time | receipt-weighted mean of the scorecard on-time rate |

The scorecard's spend includes tax, so it does not equal Procurement spend; the screen says so. The scorecard ignores
the product filter. In a branch-limited view it has no quality measure, because inspection lots carry no branch. Prices
are not converted between units of measure, so a product bought in several units needs reading with care.

## Sales

Revenue and volume come from `SalesAnalysisService`, gross margin from `ManufacturingCostingService.margin` with branch,
product and customer limits added for analytics, debt from plain SQL over the same open documents as the AR aging report.

| KPI | Formula |
|---|---|
| Revenue | Σ line subtotal (net of tax and discount) of posted and paid sales invoices, ERP and POS. Credit notes are not deducted |
| Sales volume | Σ line quantity of those lines. Units are mixed unless a product is selected |
| Gross margin | Σ subtotal − Σ quantity × unit cost |
| Unit cost | the product's production cost per unit in the period, else its average stock cost, else its cost price. The table shows which |
| Gross margin % | gross margin ÷ margin revenue × 100 |
| Customer debt | Σ `amountResidual` of posted and paid sales documents with a balance, as of now |
| Overdue debt | customer debt whose due date (issue date if none) has passed |

Margin covers lines with a product, so margin revenue can be below Revenue; the screen says so. Debt ignores the period.
Production cost and average stock cost are organization-wide, also in a branch-limited view.

## Fleet and delivery

Trips come from `FleetReportService.deliveryKpis`, one set of the same trip facts as the `deliveries` and `transportCost`
reports with a branch list and product added for analytics. A trip counts on its delivery (POD) time when delivered or
closed. Fuel comes from `FleetReportService.fuel`, once per branch in a branch-limited view.

| KPI | Formula |
|---|---|
| Deliveries completed | delivered or closed trips in the period |
| On-time deliveries | trips delivered by the requested day and window end ÷ deliveries completed × 100 |
| Tonnes delivered | Σ delivered tonnes, else loaded, else planned |
| Fuel cost | Σ fuel log cost of posted logs: station `amount`, tank `stockValue` |
| Transport cost | Σ trip cost: own fleet allocated by the org's `fleet.tripCostBasis` plus driver allowances, transporter freight |
| Transport cost per tonne | transport cost ÷ tonnes delivered |

Fuel belongs to vehicles, not trips, so the customer, delivery site and product filters do not reach it and the screen
says so. A product filter keeps the trips carrying the product, and each KPI then covers the whole trip.

## Maintenance

Reliability comes from `FleetDowntimeService.reliability`, once per branch in a branch-limited view; plant hours from
`CrushingReportService.plantHours`; spend is plain SQL over completed and closed `FleetWorkOrder` rows; OEE from
`ShopFloorReportService.oeeReport` for an unlimited view of at most 93 days, since work centres carry no branch.

| KPI | Formula |
|---|---|
| Equipment downtime | Σ fleet downtime hours in the period (overlaps count once per unit) |
| Plant downtime | Σ crushing run `downtimeHours` |
| Fleet availability | Σ (period hours − downtime) ÷ Σ period hours × 100 |
| Maintenance expenditure | Σ work order `totalCost` with `completedAt` in the period (parts, labour, external) |
| Breakdown frequency | unplanned downtime intervals starting in the period |
| Breakdowns per unit | breakdown frequency ÷ units |
| MTBF | Σ uptime hours ÷ breakdowns |
| MTTR | Σ unplanned hours ÷ breakdowns |
| OEE | availability × performance × quality from summed minutes of approved shift reports |

## Finance

P&L figures come from `ConsolidatedReportService.consolidated`, cash flow from `FinancialReportsService.cashFlow`,
receivables and payables from plain SQL over the same open documents as the aging reports. Every ledger figure is in base
currency. Journal lines without a branch sit in Unassigned and leave a branch-limited view.

| KPI | Formula |
|---|---|
| Net revenue | revenue accounts − contra revenue accounts |
| Gross profit | net revenue − cost of goods sold accounts |
| Operating expenses | operating expense accounts |
| Net profit | income accounts − expense accounts |
| Profit margin | net profit ÷ income × 100 |
| Net cash flow | operating + investing + financing, direct method. Unlimited view only |
| Closing cash | opening cash + net cash flow. Unlimited view only |
| Receivables | as Customer debt |
| Payables | Σ `amountResidual` of posted and paid vendor bills with a balance, as of now |

Cash has no reliable branch, so a branch-limited view omits both cash KPIs and the screen says so, rather than showing
organization-wide cash to a restricted user. The monthly chart covers the last 12 months of the period at most.

## Branch performance

Source: `BranchPerformanceService.comparison`, which also applies the data scope, passing the end of the last day. Tiles
show the consolidated total, or the selected branch.

| KPI | Formula |
|---|---|
| Revenue | income accounts per `JournalLine.branchId` |
| Expenses | expense accounts per `JournalLine.branchId` |
| Stock position | Σ `quantity × runningAverageCost` per location's branch, as of now |
| Profitability | revenue − expenses; margin = profit ÷ revenue × 100 |
| Open receivables | Σ `amountResidual` of the branch's open sales documents, as of now |
| Deliveries, tonnes, produced | delivered trips, delivered tonnes and produced quantity in the period |

Stock position here uses `runningAverageCost` alone, as the branch report does, so it can differ from Stock value on the
inventory dashboard when an average is zero.
