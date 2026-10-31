# Sales O2C — phase plan (S0 → S10)

Design: [ADR-S011](../adr/ADR-S011-sales-o2c.md) · module spec [`sales.md`](sales.md) · postings
[`sales-postings.md`](sales-postings.md) · credit [`credit.md`](credit.md) · product map
[`PRODUCT_ARCHITECTURE.md`](../PRODUCT_ARCHITECTURE.md).

Owner instruction (2026-10-30): build S0, then continue S1, S2, … in order; report after each phase ("phase Sn is
done"). Each phase lives on its own branch `phase/S<n>-sales-<area>` and is merged to `main` only after its gates pass.

Status legend: `todo` · `in-progress` · `done` · `blocked`.

| Phase | Name | Release | Status | Depends on |
|---|---|---|---|---|
| Step 0 | Design docs | — | done (5c42014) | — |
| S0 | Foundation: module, quotation migration, master-data fields, settings, ledger harness | R1 | done | Step 0 |
| S1 | Quotations API in sales + Sales Orders core + amendments + R1 reports + web | R1 | done | S0 |
| S2 | Pricelists, pricing policy, margin floors, commercial approvals | R1 | done | S1 |
| S3 | Credit module (limits, exposure, decisions, holds) | R2 | done | S1 |
| S4 | ATP, reservations, backorders, supply pegs | R2 | done | S1 |
| S5 | Fulfilment orchestration (fleet DO, weighbridge, collect, quantity read model, policies) | R2 | done (partial, see deviations) | S4, fleet 1.4b |
| S6 | Billing, deposits, down payments, payment gateway SPI + mobile money | R2 | done (defaults pending accountant) | S5, accountant answers |
| S7 | Returns / RMA, claims, quality certificates | R2 | done | S6 |
| S8 | Collections: dunning, promise-to-pay, statement runs | R3 | done | S3, S6 |
| S9 | Agreements, rebates, commissions, targets | R3 | done | S2, S6 |
| S10 | Analytics, dashboard, portal, AI assist | R3 | done | all |

Gates for every phase (from ADR-S011 / plan): `./gradlew build` green (Testcontainers) incl. `ModularityTests` and
`ArchitectureTests`; permission (403) and tenant tests for new routes; idempotency on money routes; reconciliation
(`SalesLedgerAssertions`) where GL is touched; backward-compatibility checklist (§ "Compatibility"); docs updated
(`sales.md`, `BUILD_LOG.md`, `VERIFY_CHECKLIST.md`); unbuilt integrations hidden from users.

---

## S0 — Foundation

**Goal.** Create the `sales` module and move quotations out of CRM without changing any existing route contract.

**Deliverables**
1. Migration `V120__sales_foundation.sql`
   - `SalesSettings` (per organization): quote validity days, discount / amount approval thresholds, credit check
     flag, default terms, auto-win on conversion (copied from `CrmSettings`), plus new fields: flows enabled, five
     fulfilment / recognition policy defaults (stored only), reservation trigger.
   - `SalesQuotation`, `SalesQuotationLine` — same columns as `CrmQuotation*` plus `branchId`, `paymentTermId`,
     `pricelistId`, `siteId`, `teamId`; rows copied from CRM **with the same ids, numbers and statuses**.
   - `SalesQuotationRevision` — immutable snapshots (header + lines JSON) written on every `revise`; one baseline
     snapshot per migrated quotation that already had `revision > 0` is not possible (history was overwritten in
     CRM) — recorded as a known gap.
   - `Partner` + `pricelistId`, `creditRiskCategory`, `creditReviewDate`, `creditPeriodDays`, `salesTeamId`,
     `salespersonId`; `Product` + `invoicePolicy` (`ordered` | `delivered`), `billingBasis` (`ordered` | `weighed`
     | `accepted`).
   - Write-block trigger on `CrmQuotation` / `CrmQuotationLine` (raises unless `app.allow_crm_quote_write = on`).
   - Permissions `sales:*` and grants (Administrator: all; roles with `crm:quotation:*` get the matching
     `sales:quotation:*`).
   - Row-level security policies like every org-scoped table.
2. Module `com.inventoryerp.sales` (`package-info` with `@ApplicationModule`, `application` named interface).
   - `SalesQuotationService` — the quotation lifecycle ported from `CrmQuotationService` onto the sales tables, plus
     revision snapshots.
   - Ports owned by sales, implemented in crm (no sales → crm dependency): `QuotationOpportunityPort` (deal
     visibility / currency / owner, mark won), `QuotationPricingPort` (price resolution until S2 replaces it).
   - `SalesSettingsService`.
3. CRM becomes a client: `CrmQuotationService` delegates every call to `SalesQuotationService` (same JSON);
   `CrmApprovalSubscriber` handles both `crm_quotation` (legacy requests) and `sales_quotation`.
4. Test harness `SalesLedgerAssertions` (trial balance, AR subledger = control, stock ledger = valuation).
5. Runbook `docs/runbooks/sales-quote-migration.md`: verification queries, rollback.

**Tests.** `SalesFoundationIT`: migrated quotation readable through `/crm/quotations` with same number; new quote via
CRM route lands in `SalesQuotation`; revise writes a revision snapshot; legacy approval entity still applies; direct
write to `CrmQuotation` is rejected; settings defaults; tenant isolation. Existing `CrmSuiteIT` unchanged and green.

**Done when** gates pass and `/crm/quotations*` behaves identically.

## S1 — Quotations + Sales Orders core
- Routes `/sales/quotations*` (list, CRUD, submit / approve / reject / send / accept / decline / cancel / revise /
  convert, revisions); convert target `sales_order` (default for new UI), `order` (legacy POS order), `proforma`.
- Tables `SalesOrder`, `SalesOrderLine`, `SalesOrderRevision`, `SalesOrderChangeRequest`; numbering `SO-YYYY-nnnnn`.
- `SalesOrderService`: create / update draft, confirm (stock + credit messages informational until S3/S4), hold /
  release, cancel, close short (approval), duplicate; amendments via change request → preview → approval → apply.
- Workflow registration, audit, events `sales.order.*`, permissions `sales:order:*`.
- R1 reports: quotations by status / salesperson / expiry, pending approvals, open orders, conversion.
- Web: `pages/sales/` Quotations (moved), Quotation editor, Orders list, Order form with status ribbon and smart
  buttons, Change requests, Sales dashboard tiles; nav group "Sales".
- Tests: lifecycle, amendment constraints (qty ≥ billable, invoiced history untouched), 403 matrix, tenant isolation.

## S2 — Pricing and commercial approvals
- Tables `Pricelist`, `PricelistItem`, `MarginFloor`; `CrmPriceRule` rows migrated into "Migrated rules" pricelist.
- `PricingPolicy.resolve` (agreement → customer item → customer pricelist → category pricelist → branch default →
  list price) with unit, quantity breaks, site zone, branch, dates; snapshot on lines; override with reason.
- `/crm/pricing/*` delegates; `QuotationPricingPort` implementation moves to sales.
- Approval rules via kernel approvals: discount bands, margin floor, amount bands (entity types `sales_quotation`,
  `sales_order`, `sales_order_change`).
- Web: Pricelists, pricelist grid, price simulator, margin floors.

## S3 — Credit
- Module `credit`: `CreditLimit`, `CreditPolicy`, `CreditDecision`, holds; contributor SPI implemented by sales,
  invoicing, pos, fleet.
- Transition tests E1–E14 + worked example + conservation property test (`credit.md`).
- Check points: quote submit, SO confirm, amendment, trip dispatch, POS credit sale.
- Web: credit panel on partner and SO, review queue, held orders.

## S4 — ATP and reservations
- `AtpService` (time-phased, location-aware), supply providers (purchase, transfer; production port hidden).
- Reservations at confirm inside the stock row lock; priority, expiry, reallocation; `SupplyPeg` for future supply.
- Concurrency IT (two confirms, no oversell). Backorder report, reservations screen.

## S5 — Fulfilment
- `FulfilmentOrchestrator` → fleet `fromSalesOrder` (new source), several DOs per order; collect at yard.
- Quantity read model with inbox, version checks, dead letter, rebuild, nightly reconciliation.
- Five policies executed (transit location, ownership transfer issue) after accountant review.
- Needs fleet 1.4b (ticket ↔ trip).

## S6 — Billing
- `SalesBillingService` (regular / down payment / batch), `DepositApplication`, deposit receipts, refunds.
- `PaymentGateway` SPI in invoicing; MTN MoMo, Airtel, mock.
- Deposit scenarios D1–D10 as ITs; reconciliation harness.
- Accountant answers (VAT tax point, application rule) required; built as validated settings.

## S7 — Returns
- `SalesReturn` RMA lifecycle, dispositions (restock / quarantine / scrap), credit note / replacement / refund;
  discrepancy → claim; certificates.

## S8 — Collections
- Dunning levels, daily run, promise-to-pay, statement runs, collections worklist; auto credit hold.

## S9 — Agreements, rebates, commissions, targets
- `SalesAgreement` + releases, `RebateProgram` accruals and settlement, `SalesCommissionRule` / statements → payroll,
  `SalesTarget`. Licence key `salesAgreements`.

## S10 — Analytics, portal, AI
- Dashboard, analysis views, margin and customer profitability, forecast; customer portal (licence
  `customerPortal`); AI advisories (read-only).

## Compatibility checklist (run every phase)
- `CrmSuiteIT`, `CrmIT` green; `/crm/quotations*`, `/crm/pricing*` JSON unchanged.
- `OrdersIT` / POS ITs green; `/orders` unchanged.
- Fleet `FleetDispatchIT` green (legacy `from-sales-order`).
- No change under `manufacturing/**`, `pos/**` billing path, `android/`.

## S1 — as built
- `V121__sales_orders.sql`: `SalesOrder`, `SalesOrderLine` (ordered / closed-short + projection columns reserved,
  delivered, billable, invoiced, credited, returned), `SalesOrderRevision`, `SalesOrderChangeRequest`.
- Services: `SalesOrderService` (draft CRUD, confirm with rate lock + customer credit-hold → on hold, hold / release,
  cancel only without deliveries / invoices / live DOs, close short to `max(delivered, billable, net invoiced)`,
  duplicate, derived delivery / invoice status, `in_fulfilment` / `done`), `SalesOrderChangeService` (request →
  impact → approve / reject / withdraw; stale-revision guard; quantity floor; price / product change on a moved line
  splits it; immutable revision + diff), `SalesReportService` (R1 summary), `SalesLinePricing`.
- Quotation convert target `sales_order` (default on `/sales/quotations/{id}/convert` and in the editor).
- Routes: `/sales/quotations*` (14), `/sales/orders*` (14), `/sales/change-requests/*` (4), `/sales/reports/summary`,
  `/sales/settings` (GET / PUT). Events `sales.order.*` (payload `v: 1`).
- Web: Sales nav group, Sales dashboard, Sales Orders list (tabs: drafts / open / to deliver / on hold / done),
  order form with status ribbon, smart buttons, change-request dialog + approval panel, revisions, close short.
- Tests: `SalesOrderIT` (8) incl. authorization matrix of all `/sales` routes and tenant isolation.
- Deviation: order state machine lives in `SalesOrderService` (like quotations), not in the kernel `WorkflowRegistry`
  (that registry drives JPA document statuses); `branchId` optional until branch-scoped access lands.

## S2 — as built
- `V122__sales_pricing.sql`: `Pricelist`, `PricelistItem` (product / category / all × customer / customer category ×
  min qty × unit × delivery zone × branch × dates; fixed / % discount / % surcharge / cost + %; priority),
  `MarginFloor`, `PartnerCategory.pricelistId`, order thresholds in `SalesSettings`.
- `SalesPricingEngine` (the single pricing policy, `@Primary` `QuotationPricingPort`): customer-bound rules → CRM
  price rules → customer pricelist → category pricelist → branch / organization default → standing discount → list;
  unit-aware, base-pricelist chaining (depth 3), zone from the delivery site; margin floor → floor price + margin %.
  `breaches()` = extra discount beyond the authorised price and prices below the floor.
- Quotation submit adds margin-floor reasons; sales-order confirm goes to `pending_approval` on discount / amount /
  margin breaches (kernel approval engine entity `sales_order`, approve / reject routes, subscriber), else confirms.
- `/crm/pricing/resolve` answers from the engine (same fields + floor / margin); CRM rule CRUD unchanged.
- Routes: `/sales/pricelists*` (8), `/sales/margin-floors*` (4), `/sales/pricing/resolve`, `/sales/orders/{id}/approve`
  and `/reject`.
- Web: Pricelists (list, simulator), pricelist editor (rules, customer / category assignment), Margin floors; order
  form shows approval reasons and Approve / Reject.
- Tests: `SalesPricingIT` (4).
- Deviation: CRM price rules are not migrated into pricelists; they stay editable under CRM → Price Rules and are the
  engine's second tier (avoids a translation layer; one engine decides all prices).

## S3 — as built
- `V123__credit.sql`: `CreditLimit` (customer × branch × currency, risk category, review date, validity),
  `CreditPolicy` (overdue days / action none-approval-hard, deposits reduce exposure, group limits, check points,
  auto-hold), immutable `CreditDecision` (trigger), `Partner.parentId` (customer groups), `SalesOrder.holdKind`,
  permissions `credit:read|manage|override`.
- Module `com.inventoryerp.credit` (depends on no business module): `CreditService` (exposure, check, override,
  limits, policy, hard hold, review queue, decisions, auto-hold), `CreditAutoHoldJob` (02:15 daily, ShedLock),
  SPI `CreditExposureContributor` implemented by sales (undelivered, delivered-not-invoiced), invoicing (receivables +
  overdue, draft non-SO invoices, unapplied credit notes / on-account payments) and pos (credit sales).
- Check points: quotation submit (reasons), order confirm (pass / credit hold / customer block), amendment value
  increase, fleet trip dispatch (blocked → refused; hold → refused unless `credit:override`, which records an override).
  Credit holds on orders are released only with `credit:override` (override decision linked to the hold decision).
- Web: Sales → Credit Control (review queue, limits, decisions, policy); credit panel on the order form.
- Tests: `CreditIT` (7) incl. worked example (buckets through confirm → deliver → invoice → pay → close short, AR
  tie-out), group limits, overdue policy, immutability, authorization matrix.
- Deviations: `undeliveredBasis = net` is stored but exposure is always gross (VAT-inclusive) for now; the POS
  checkout keeps its own `CreditStatus` check (frozen POS path) and only contributes its exposure; deposits join S6.

## S4 — as built
- `V124__sales_atp.sql`: `SalesOrder.priority`, `SalesOrderLine.reservedLocationId` / `promiseAtRisk`,
  `SalesSupplyPeg` (soft pegs of unreserved demand to expected supply).
- `SalesAtpService`: ATP per product (per yard on hand / reserved / free, free purchase supply after other lines'
  pegs, earliest full-fill date, best / full-fill location, split proposal; production reported "not connected");
  firm reservations on confirm (`StockReservation` sourceType `sales_order_line`) inside the `StockItem` row lock;
  backorder = unreservable remainder pegged to purchase supply by date; promise-at-risk flag; release on cancel /
  done; re-plan on close short, amendment and manual "re-plan"; `SalesBackorderJob` every 15 min by priority.
- SPI `SupplyProvider` (`PurchaseSupplyProvider` now; a production provider from the manufacturing integration).
- Routes: `POST /sales/atp`, `GET /sales/orders/{id}/atp`, `GET /sales/orders/{id}/reservations`,
  `POST /sales/orders/{id}/reserve`, `GET /sales/reports/backorders`.
- Web: Availability panel on the order (reserved / free / short / full-by date / pegs / re-plan), Backorders page.
- Tests: `SalesAtpIT` (4) incl. two concurrent confirmations against 150 → exactly 150 reserved.
- Deviations: purchase orders carry no receiving location, so purchase supply is organization-wide (not per yard);
  stock transfers in transit are not yet counted as supply; variants are not reserved (aggregates / blocks have none).

## S5 — as built
- `V125__sales_fulfilment.sql`: line projections `qtyLoaded`, `qtyDispatched` (net of returns, also `qtyDelivered`),
  `qtyReceived` (POD), `qtyAccepted` (fleet billable after approved discrepancies); immutable
  `SalesFulfilmentDecision`; inbox `SalesProcessedEvent`; `SalesReconciliation` log.
- Fleet owns the new port `DeliverySourcePort`; sales implements it (`SalesDeliverySource`) — fleet never depends on
  sales. `FleetDeliveryOrderService.fromSalesOrder` serves new B2B orders through the port (legacy back-office
  orders unchanged); fleet refuses to dispatch a trip of a held / cancelled sales order.
- `SalesFulfilmentService`: create the delivery order for the open quantities; `rebuild` recomputes projections
  absolutely from fleet records (duplicates / out-of-order events harmless), billable by product basis (weighed /
  accepted / ordered) with a decision per change, manual override (`sales:billing:override`, reason, sticky, never
  below net invoiced), refreshes status and reservations. `SalesFulfilmentSubscriber` rebuilds on
  `fleet.trip.*` and `fleet.delivery.discrepancy_decided`; `SalesReconcileJob` 03:10 nightly records drift.
- Policies: only the default (issue at dispatch, revenue at invoice) is executable; other combinations are refused
  until the accountant approves the transit postings.
- Routes: `POST /sales/orders/{id}/delivery-orders`, `GET /{id}/fulfilment`, `POST /{id}/rebuild`,
  `POST /{id}/lines/{lineId}/billable`.
- Web: Fulfilment panel (create delivery order, deliveries, ordered / dispatched / received / accepted / billable /
  invoiced, billable override dialog).
- Tests: `SalesFulfilmentIT` (2): SO → DO → hold blocks dispatch → dispatch shrinks reservation → POD shortage →
  approved → billable 29.5 → manual 30 → second trip via the event path → full delivery; reconciliation repair.
- Deviations: one live delivery order per sales order (fleet's unique source index; close it to raise the next);
  collect-at-yard through the weighbridge waits for fleet 1.4b (ticket ↔ trip); transit / ownership policies
  deferred (accountant).

## S6 — as built
- `V126__sales_billing_deposits.sql`: `CustomerDeposit`, `DepositApplication`, `SalesInvoiceLink`,
  `MobileMoneyRequest`, settings `depositApplication` (manual | oldest_first) and `depositTaxPoint` (delivery |
  receipt), permissions `sales:invoice:create`, `sales:deposit:record|refund`.
- Invoicing (owner of payments): `CustomerDepositService` — receipt P2 (Dr tender / Cr Customer Deposits 2350,
  mapping `customer_deposit`), down-payment registration P3 on `invoice.posted`, application P9 through
  `PaymentService.createDepositApplication` (a deposit-backed receipt allocated like cash; only callable internally;
  not voidable from `/payments`), refund P10, void; `PaymentGateway` SPI with `mock`, `mtn_momo`, `airtel_money`
  (unconfigured until credentials) and `MobileMoneyService` (deposit / invoice / on-account, booked once on success);
  credit exposure now subtracts unapplied deposits.
- Sales: `SalesBillingService` — invoiceable quantity per line (order override, else product `invoicePolicy`;
  ordered → ordered − closed short, delivered → billable; minus quantities already on non-cancelled invoices),
  regular invoice (never re-issues stock: `stockIssuedBy`; fleet DOs marked goods-invoiced), down-payment invoice
  (line on the deposit account, VAT only with tax point "receipt"), batch per customer, gross-invoiced projection on
  `invoice.posted` / `invoice.cancelled`, oldest-first auto application.
- Routes: `GET /sales/orders/{id}/billing`, `POST /{id}/invoices`, `/{id}/deposits`, `/{id}/apply-deposits`,
  `POST /sales/invoices/batch`; `/customer-deposits` (list, get, receive, apply, refund, void);
  `/payments/mobile-money` (providers, initiate, get, refresh).
- Web: Billing panel on the order (to invoice, create invoice, down-payment invoice, record deposit incl. mobile-money
  request, apply deposit to an invoice, invoices and deposits list).
- Tests: `SalesBillingIT` (4): D1 / D4 / D6, D5 partial + auto apply, D2 down payment, mobile money; AR and deposit
  subledgers tie out to the ledger; trial balance zero.
- Pending accountant (defaults shipped, configurable): VAT tax point of advances, application rule, freight on
  sales-order invoices (still invoiced from the fleet delivery order as a charges-only invoice).

## S7 — as built
- `V127__sales_returns.sql`: `SalesReturn` (RMA-, reason, physical return or claim, outcome, links to credit note /
  refund / replacement / complaint / discrepancy), `SalesReturnLine` (claimed, received, disposition, credited),
  `CustomerComplaint` (CMP-), `SalesQualityCertificate`, `Product.qualitySpec`, permissions
  `sales:return:read|write|approve`, `sales:complaint:read|write`; events `sales.return.received|closed`.
- `SalesReturnService`: request (quantity ≤ delivered − already claimed) → approve → receive into a location (inventory
  return receipt: Dr inventory / Cr COGS) → inspect per line (restock / quarantine transfer / scrap issued as waste)
  → close: credit note against the order invoice for the invoiced part (gross invoiced untouched, credited grows,
  via `SalesInvoiceLink` kind credit_note), not-yet-invoiced part lowers billable by a recorded decision; outcomes
  credit_note / refund (credit note + customer refund payment) / replacement (draft order at no charge) / none.
  Claims without goods skip receive / inspect. Returned quantity projection on order lines.
- `SalesComplaintService`: complaints (manual; automatic from approved fleet discrepancies on sales-order deliveries,
  idempotent), link to a return (resolved when the return closes), quality certificates with results JSON and file.
- Routes: `/sales/returns*` (8), `/sales/complaints*` (3), `/sales/quality-certificates` (2).
- Web: Returns list + detail (approve, reject, receive, inspect with dispositions, close with outcome), "Return /
  claim" from the order, Complaints page.
- Tests: `SalesReturnIT` (4).
- Note: when the invoice is already fully paid, the credit note leaves a customer credit that the existing invoicing
  model does not keep as an open credit-note residual (pre-existing behaviour); use the refund outcome then.

## S8 — as built
- `V128__sales_collections.sql`: `DunningLevel`, `DunningRun`, `DunningEntry`, `PromiseToPay`, settings
  `dunningAutoRun` / `monthlyStatements`, permissions `sales:collections:read|manage`.
- `SalesCollectionsService`: levels (sequence, from days overdue, channel e-mail with the statement PDF / letter /
  record only, template with {{customer}} {{amount}} {{days}}, late fee, credit hold); run = per customer the oldest
  overdue invoice picks the highest reached level, skipped when an open promise covers today or that level was already
  sent since the oldest current overdue invoice fell due (a settled customer restarts at level 1); fee → draft
  invoice on "Late Payment Fees" (4290, mapping `dunning_fee_income`); hold → credit module. Promises settle kept /
  broken from receipts since the promise. Worklist by aging bucket (current, 1–30, 31–60, 61–90, 90+) with last level
  and open promise. Statement run e-mails AR statements to customers with an open balance.
- `SalesCollectionsJob`: 06:00 daily promises + scheduled dunning; 07:00 on the 1st monthly statements.
- Routes: `/sales/collections/*` (13). Web: Collections page (worklist, runs, levels, promises).
- Tests: `SalesCollectionsIT` (3).
- Deviation: WhatsApp / SMS reminders are not sent yet (channel options e-mail / letter / record only); the
  communication module can be wired later.

## S9 — as built
- `V129__sales_agreements_rebates_commissions.sql`: `SalesAgreement` (+`Line`), `SalesOrder.agreementId`,
  `RebateProgram`, `RebateAccrual`, `SalesCommissionRule`, `CommissionStatement`, `SalesTarget`; permissions
  `sales:agreement:read|manage`, `sales:rebate:manage`, `sales:commission:read|manage`, `sales:target:manage`.
  Licence key `salesAgreements` (install `LicenseFeatures` + licence server `Entitlements.KNOWN_FEATURES`) gates the
  agreement and rebate routes (402 without it); commissions and targets are base sales.
- `SalesAgreementService`: blanket / contract agreement per customer — product lines with contract quantity, agreed
  price (in the line's unit), discount, minimum release; draft → active → expired (daily job) | closed, draft / active
  → cancelled (refused once a release is confirmed). Active agreements accept only end date, sites, notes and the
  over-release flag. Sales orders carry `agreementId` (validated: active, same customer, valid today; its payment
  term is the default); the pricing engine's new tier 0 prices their lines from the agreement (source `agreement`,
  excluded from discount / margin breaches). Released = confirmed / in-fulfilment / done order lines net of closed
  short, in base units; confirm adds approval reasons for a release beyond the balance (unless over-release is
  allowed) or below the minimum release. Agreement detail shows released / balance / % and the releases; the order
  shows its agreement (relations).
- `SalesRevenueQuery`: product lines of posted invoices (+) and credit notes (−) with salesperson attribution
  (invoice → its sales order owner → the reversed invoice's order owner → customer salesperson) and order team; the
  `paid` basis takes invoice lines pro rata to the allocations of the period.
- `SalesRebateService`: programmes scoped by customer / customer category × product / product category, month /
  quarter / year, basis quantity or value, tiers (per unit or % of value; the highest tier reached applies to the whole
  period). Accrual run recomputes each customer's period amount and posts only the difference (P15: Dr 4190 Sales
  Rebates `sales_rebates` / Cr 2360 Rebate Accrual `rebate_accrual`; a drop reverses). Settlement (period ended,
  idempotent route) issues and posts a credit note whose line is booked to the accrual account (P15a: Dr accrual / Cr
  AR) against the customer's oldest open invoice; a settled accrual is final.
- `SalesCommissionService`: rules by salesperson / team / product category, basis invoiced or paid, method % of
  revenue, % of margin (revenue − product cost) or per unit, priority then specificity; credit notes claw back in the
  month issued. Monthly statement per salesperson (draft, regenerated until approved); approval (not by the
  salesperson) queues a taxable HR payroll earning (source `sales_commission`, Dr 6150 Sales Commissions
  `sales_commission_expense`) for the employee linked to the user; a non-positive total is approved without payroll.
  Statements are visible to their salesperson without `sales:commission:manage`. Targets per month × salesperson /
  team / branch / product category with actual net revenue and achievement %.
- `SalesAgreementJob`: 05:30 daily — expire agreements, accrue rebates as of yesterday.
- Routes: `/sales/agreements*` (7), `/sales/rebates/*` (6), `/sales/commissions/*` (9), `/sales/targets*` (5).
  Web: Agreements & rebates page, Commissions & targets page, agreement select on the sales order.
- Tests: `SalesAgreementIT` (3) — tier-0 price, balance, over-release approval, wrong customer, rebate delta postings
  / reversal / settlement / finality with AR and trial-balance tie-out, commission clawback + payroll earning, targets.
- Deviations: amounts are in document currency (no FX conversion); commission margin uses the product's current cost,
  not the issue cost; agreement delivery schedules and sites are stored but not enforced; rebate settlement credit
  notes carry no VAT (accountant question Q-S9-1).

## S10 — as built
- `V130__sales_customer_portal.sql`: `CustomerPortalAccess` (token SHA-256, expiry, revoke, last used; no row policy —
  it is looked up by token before the organization is known), permission `sales:portal:manage`. Licence key
  `customerPortal` (install + licence server) gates portal administration and the portal itself.
- `SalesAnalyticsService` (`/sales/analytics/*`, `sales:report:read`): dashboard (revenue this vs last month,
  quotations pending, orders to deliver / invoice, overdue receivables, 12-month revenue + margin chart, top
  customers); analysis by product, product category, customer, customer category, branch, salesperson or month
  (revenue, qty, cost, margin, margin %); customer profitability = revenue − cost − transporter freight of the
  customer's sales-order trips − rebates accrued − commissions; forecast per month = max(run rate of the last three
  months, open order value by commitment date + open quotations × 90-day conversion rate + agreement balances spread
  to their end date), components shown separately.
- `SalesAdvisorService` (`/sales/advisor`): read-only, explainable rules — churn risk (last 90 days < half of the 90
  before), sold below cost (30 days), discount outliers (> mean + 2σ of the product's 180-day history), quotations
  expiring in 7 days, 30+ days overdue customers with undelivered orders, agreements ending within 30 days with
  > 20 % unreleased. It never posts, approves or changes data.
- `SalesPortalService`: staff issue (`POST /sales/portal/access`, optional e-mail, 1–365 days) and revoke links; the
  public `/sales-portal/*` routes (`@Public`, header `X-Portal-Token`, throttled) run in the token's organization scoped to
  its customer: me (open / overdue balance), quotations (list, detail without internal fields, accept, decline),
  orders with lines and delivery trips, invoices / credit notes, statement PDF, mobile-money payment of an open
  invoice (a repeat within 10 minutes returns the running request), payment status, complaints.
  The `mock` gateway is never offered or accepted on the portal unless `app.portal.allow-test-payments=true`
  (tests only).
- Routes: `/sales/analytics/*` (4), `/sales/advisor`, `/sales/portal/access*` (3), `/portal/*` (12).
  Web: Sales analytics page (overview + advisor, analysis, profitability, forecast), Customer portal links page,
  public `/sales-portal` page (token kept in session storage, removed from the address bar).
- Tests: `SalesInsightsIT` (3) — analysis / profitability / forecast numbers, advisor rules, portal scope (other
  customer's quotation 404), accept, payment booked, PDF, complaint, revoke → 401. Browser-checked locally:
  analytics tabs, portal link, portal page.

## Post-S10 — stakeholder presentation (as built)
- `SalesPresentationService` (`GET /sales/analytics/presentation?from&to`, `sales:report:read`, default the last 7
  days, max 366 days, `from` after `to` → 400): one read-only snapshot of the period against the previous period of
  equal length — KPIs (net invoiced revenue / margin, invoices, active and new customers, quotations issued and
  converted, orders confirmed, orders cancelled + quotations rejected/expired, cash collected), revenue by product
  category with top products (volume in the product's sales unit, revenue, share), trend (six equal periods up to 45
  days, else calendar months; top four categories stacked), top customers, salespeople, CRM pipeline (open / weighted,
  won, lost, by stage, lost reasons, top open), lost business, receivables ageing, completed / upcoming CRM
  activities, the 3-month forecast and the advisor's advisories.
- Web: Sales → Generate presentation (`/sales/presentation`). Date presets, four themes, cover fields and free-text
  highlights / marketing update / next actions (kept in local storage), slide toggles; the PPTX (16:9, native
  editable charts, speaker notes) is built in the browser with `pptxgenjs` — the server only serves data.
- Tests: `SalesInsightsIT.presentation` — period / previous period, revenue vs previous, 6 trend buckets, product in
  its category, monthly granularity for a year, 400 on reversed and over-long ranges.
- Deviations: the "AI" assist is deterministic rules, not a language model, and does not draft quotations; costs
  are current product cost; own-fleet running cost is not in profitability until fleet trip costing (1.9); the
  forecast is not yet fed to MRP (manufacturing integration); the portal has no e-signature (acceptance is recorded
  with the token's audit trail) and no per-contact users — one link per customer contact e-mail.

## Known deviations from the approved plan (owner to confirm)
- S0: sales teams stay in CRM (`CrmTeam`) — pipeline code depends on them heavily; sales stores `teamId` pointing to
  `CrmTeam`. Revisit in S9 (targets).
- S0: rollback is a runbook (copy rows back + drop trigger + revert release) instead of a runtime source flag; a
  dual code path would keep two quotation engines alive, which ADR-S011 §4 forbids.
