# Sales report center (ADR-S057)

Sales → Reporting. 142 reports in 12 categories. The web shows them on 10 pages, and each page lists its reports as
tabs. Every report has a Table / Chart / Both toggle, KPI tiles, filters, drill-down, export (Excel, CSV, PDF) and
print.

## Routes (all `GET`, `sales:report:read`)

| Route | Purpose |
|---|---|
| `/sales/report-center/catalog` | Categories and the reports the caller may run |
| `/sales/report-center/dashboard?from&to&branchId` | Executive overview: 10 KPIs, 12-month trend, top 5s, branch / category / channel |
| `/sales/report-center/lookups` | Filter options (branches, salespeople, customers, products, categories, customer types, channels) |
| `/sales/report-center/run/{key}?…` | Runs one report |
| `/sales/report-center/export/{key}?format=xlsx\|csv&…` | Server export (PDF and print are rendered in the browser) |

Parameters: `from`, `to` (default: year to date), `asOf`, `branchId` (comma list), `salespersonId`, `partnerId`,
`productId`, `categoryId`, `customerCategoryId`, `channel` (`sales_order`, `branch_cash`, `pos`, `direct`), `status`,
`groupBy` (`day`…`year`), `dimension`, `compare` (`previous_period`, `previous_year`), `measure`, `threshold`, `days`,
`limit`. A report ignores the filters it does not list. An unknown parameter returns 400.

## Web pages

| Page (`/sales/reports/…`) | Categories |
|---|---|
| *(hub)* | Dashboard and a searchable catalogue |
| `performance` | Sales Performance |
| `customers` | Customer Sales |
| `products` | Product & Item Sales |
| `orders` | Sales Orders + Delivery & Fulfilment |
| `quotations` | Quotations & Pipeline |
| `billing` | Invoices & Billing + Collections & Payments |
| `discounts` | Discounts & Returns |
| `team` | Salesperson & Team |
| `profitability` | Profitability |
| `forecasting` | Forecasting & Planning |

The selected report and its filters are kept in the URL (`?r=<key>&period=…`).

## Definitions

- **Net sales** = invoice line subtotals (net of discount and tax) − credit-note line subtotals.
- **Gross sales** = net sales + discounts + returns.
- **Gross profit** = net sales − actual cost. Actual cost is the inventory issue cost of the invoice, the cash sale or
  the delivery trips. When no issue exists, the product's cost price is used.
- **Quotation conversion rate** = converted ÷ eligible quotations × 100. Drafts and cancelled quotations are not
  eligible.
- **Aging buckets** are measured from the due date: current, 1–30, 31–60, 61–90 and over 90 days.
- **On time** means delivered on or before the order's commitment date. If the order has no commitment date, the
  delivery order's requested date is used, then the order's requested date.

## Data used for the catalogue requests that the schema does not support

- **Sales budget**: the monthly sales targets. Company-level targets are used. If there are none, the salesperson
  targets are summed.
- **Delivery by route**: grouped by delivery site.
- **Sales activity**: CRM activities (calls, meetings, emails, tasks).
- **Customer lifetime value / retention**: retention by salesperson, measured period over period.
- **Discount approval**: orders and quotations that carry approval reasons.
