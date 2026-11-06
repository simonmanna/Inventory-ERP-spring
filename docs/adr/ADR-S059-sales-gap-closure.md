# ADR-S059 — Sales gap closure (SG-1..SG-12)

- Status: accepted by owner instruction 2026-11-06 ("SG-1 to SG-12 in one go"); the decisions below were taken without a
  separate design review and are listed for that review.
- Spec: [`docs/spec/sales.md`](../spec/sales.md), section "SG-1 to SG-12".
- Extension: no NestJS counterpart. New routes and tables (V401-V409). Existing behaviour stays unless a setting turns the
  new behaviour on, except where listed under "Behaviour that changed".

## Decisions

1. **Cost guard in one place.** `SalesCostGuard` strips cost and margin from Sales JSON and the CRM quotation facade. Report
   exports strip them in `SalesReportingService`. Profitability reports and `/analytics/profitability` answer 403 without
   `sales:cost:read`.
2. **Null-branch rule.** All orders and quotes on the demo data had a null branch. A restricted user sees a branchless
   document only when they own or created it. New documents get a branch from the user's scope.
3. **Merge is non-destructive.** Posted journals and payments are immutable, so `mergedIntoPartnerId` points the old record at
   the survivor. KPIs and Sales statements roll up; the core AR statement and credit exposure stay per record.
4. **The two price list systems are not merged.** `Pricelist` and `PriceList` stay. The engine already reads both as tiers, POS
   depends on `PriceList`, and a data migration needs a decision on production data. `/sales/pricing/grid` and
   `/sales/pricing/lists` show them together. Promotions reach sales documents only, never POS, invoice or delivery quotes.
5. **Delivery without Fleet reuses Fleet's tables and services.** A new carrier `manual` holds a typed plate and driver. Only
   Fleet's controllers and two beans are flag-gated, so `/sales/deliveries` works with the module off.
6. **Milestones bill as down-payment invoices**, so deposits and final settlement stay on one accounting path.
7. **One revenue logic.** `SalesRevenueQuery.invoiced` reads the report-center fact. Commissions, targets, analytics and reports
   therefore use base currency and actual cost.
8. **Write-off does not reverse tax.** Recovering output tax on bad debts depends on local tax law and stays a separate entry.

## Behaviour that changed

- Roles with `sales:report:read` or `sales:pricing:manage` keep cost; other sales roles lose it.
- Commission margin and analytics now use actual cost where the ledger has it, not the current product cost.
- A confirmed order needs a reason to cancel, and cancel is refused with open deposits, invoices or change requests.
- Stock policy defaults to `warn`: confirming still works and returns `stockWarnings`.
- Rule tier `promotion` joins the default precedence. An organization with a saved precedence must add it on the Precedence tab.
- New organizations default new commission rules to collected sales; existing organizations stay on invoiced.

## Consequences

- Restricted users get 403 on organization-wide analytics, the summary and backorders rather than a partial figure.
- Per-branch numbering covers sales orders only. Invoice numbers stay on the document catalog.
