# ADR-S022 — One customer credit management and one sales pricing engine

Status: **accepted** (owner decision, 2026-10-31). Implemented in V211–V212 and the code listed below.

## 1. Context — audit (2026-10-31)

### Credit

| | Sales O2C credit module (ADR-S011 S3, `credit`) | SALES-1 credit control (`invoicing.CreditControlService`) |
|---|---|---|
| Limit | `CreditLimit` rows (customer × branch × currency, validity), else `Partner.creditLimit` | `Partner.creditLimit` only |
| Hold | `Partner.creditHold` | `Partner.creditHold` (same column) |
| Risk | `CreditLimit.riskCategory`, `Partner.creditRiskCategory` | — |
| Exposure | contributors: sales orders (undelivered, delivered-not-invoiced), invoicing (receivables, draft invoices, credit notes, on-account, deposits), POS credit invoices | own SQL: invoice receivables, POS invoices, unbilled `Order`s, manual fleet delivery orders |
| Missing buckets | `Order` (orders / POS back office), manual fleet delivery orders | sales orders, deposits, credit notes, draft invoices |
| Check | `check(checkpoint …)` records an immutable `CreditDecision` (pass / hold / blocked) | `assertCanSell` throws; no record |
| Overrides | per document (`credit:override`, decision `override`) | per customer grant `PartnerCreditOverride` (amount / covers hold, expiry, SoD) |
| Concurrency | none | `Partner` row lock `FOR NO KEY UPDATE` |
| Callers | sales quote / order / amendment, fleet trip dispatch | orders save, invoice post, manual delivery order, dispatch hold check |

Two limits for one customer could disagree; exposure differed by channel; a POS sale ignored open sales orders.

### Pricing

| | S2 `SalesPricingEngine` (sales) | SALES-3 `PriceListService` (core) | CRM `CrmPricingService` |
|---|---|---|---|
| Tables | `Pricelist`, `PricelistItem`, `MarginFloor`, `SalesAgreement*` | `PriceList`, `PriceListItem`, `PriceListAssignment` | `CrmPriceRule` |
| Used by | sales quotations, sales orders, simulator | POS orders (`OrdersService`), invoices, manual delivery orders, CRM | CRM quote helper (delegates to the S2 engine) |
| Precedence | agreement → customer rules → CRM rules → customer / category / branch / org pricelist → standing discount → list | customer list → category list → default list | — |

Snapshots: every document line already stores `unitPrice`, `discountPercent`, `taxId` (so price-list edits never change
history), but nothing records **how** a price was reached.

Sourced delivery orders (`DeliverySourcePort`) already copy the source line price; only manual delivery orders price
themselves. POS `pos.application.PricingPolicy` is not a price resolver: it is the cashier discount authority (limits,
reasons, manager PIN) and stays in POS.

## 2. Decision

1. **Customer credit management = the `credit` module.** `CreditService` is the only place that resolves limits,
   measures exposure, decides, overrides and reserves. `CreditControlService` (SALES-1 routes) is a thin adapter that
   keeps its routes, JSON and error messages and delegates every rule to `CreditService`.
2. **Sales pricing = `SalesPricingEngine`.** It is the only resolver. `PriceListService.resolve` (used by POS orders,
   invoices, delivery orders, CRM) delegates to it through the core port `PricingEngine`; SALES-3 named price lists
   become one tier (`price_list`) of the engine.

## 3. Credit design

- **Customer credit profile** (authoritative): `Partner.creditLimit`, `Partner.creditHold`,
  `Partner.creditRiskCategory`. A `CreditLimit` row without branch and currency is no longer read: V211 copies it into
  the profile, and saving one through `/credit/limits` writes the profile (the row is kept in step for the existing
  screen). Branch or currency `CreditLimit` rows are **sub-limits**: they can only lower the available credit for
  documents of that branch / currency, never raise it.
- **Exposure** (one formula, `CreditService.exposure`): sum of the contributors —
  sales (undelivered, delivered-not-invoiced), orders (unbilled credit `Order`s not covered by an invoiced delivery),
  fleet (manual delivery orders not invoiced), invoicing (receivables, draft invoices not from a sales order, credit
  notes and on-account receipts as credits, deposits), POS (unsettled credit invoices). Each commercial event is counted
  in exactly one bucket at a time: an order moves from *undelivered* to *delivered-not-invoiced* to *receivables*; an
  `Order` billed through a delivery order leaves the orders bucket when the goods invoice exists.
- **Check points** (policy `checkpoints`, configurable): `quote_submit`, `order_confirm` (always on), `amendment`,
  `trip_dispatch`, `pos_credit_sale` (orders save), `delivery_order` (manual delivery order), `invoice_post`. The
  overdue rule applies only to `overdueCheckpoints` (default: the sales ones), so POS / invoice behaviour stays as
  before unless the owner turns it on.
- **Reservation**: `check` locks the customer row (`FOR NO KEY UPDATE`) and, for a passing decision, keeps the lock until
  the caller's transaction commits. The document that consumes credit is written in that transaction, so a second
  concurrent check waits and then sees it. No separate reservation table is needed while every checkpoint writes its
  document in the same transaction; if one ever does not, add `CreditReservation` (documented, not built).
- **Overrides** (both kinds, both audited): per document (`credit:override`, decision `override` linked to the held
  decision) and per customer grant (`PartnerCreditOverride`: extra headroom and / or hold waiver until expiry, approval
  and separation of duties through the controlled-action engine; never usable by the user who granted it).
- **Decisions**: every evaluated check writes an immutable `CreditDecision`. Customers with no limit and no hold pass
  without a decision row (no credit risk, no write on every POS ticket).

## 4. Pricing design

- **Tiers** (`PricingPolicy.precedence`, per organization, deterministic): `agreement`, `customer_rule`, `crm_rule`,
  `customer_pricelist`, `category_pricelist`, `price_list`, `branch_default`, `org_default`, `standing_discount`, then the
  catalogue `list` price (always last). Inside a tier ties break by priority, specificity, minimum quantity, creation
  time and id.
- **Breakdown** on every result: base (list) price, unit price, discount % and amount, net, tax rate, tax amount, final
  unit price and line total, plus the source tier and rule.
- **Channels**: sales quotations / orders call the engine directly; POS orders, invoices and manual delivery orders call
  `PriceListService.resolve` → engine. A line that arrives with its own price (POS terminal, offline Android) keeps it:
  the engine prices only lines without a price. A `list` result maps to "no list price" so each channel keeps its own
  catalogue fallback.
- **Delivery orders**: a sourced delivery order copies the source line price (unchanged); a manual delivery order is
  priced once, at creation, and its stored price is what invoicing uses.
- **Snapshots**: `PriceSnapshot` (V212) is insert-only — a trigger rejects `UPDATE` and `DELETE`. One row per line is
  written when a transaction is committed commercially: sales order confirmation, invoice posting, POS order credit
  billing, manual delivery order creation. It holds the agreed line values and the engine breakdown at that moment
  (`manualPrice` when the agreed price differs from the engine price).

## 5. Migration plan

| Step | What | Reversible |
|---|---|---|
| 1 | V211: copy org-wide `CreditLimit` amounts into `Partner.creditLimit` (higher one wins when both are set and differ; every difference logged in `CreditMigrationLog`) | yes: log keeps the old values |
| 2 | V211: add `overdueCheckpoints` and the new check points to `CreditPolicy` (existing rows get them) | drop column |
| 3 | V212: `PricingPolicy`, `PriceSnapshot` (+ immutability trigger) | drop tables |
| 4 | Code: adapters delegate; no route, JSON or message changes | revert commit |
| 5 | Later (owner): move SALES-3 lists into `Pricelist` tables and retire `PriceList*`; drop org-wide `CreditLimit` rows | — |

No table is deleted. `Partner.creditLimit` stays the column every existing screen reads.

## 6. Reconciliation

`GET /credit/reconciliation` (`credit:read`) lists, per customer with a limit or hold: profile limit, the old
SALES-1 exposure formula, the central exposure, the difference by bucket, and the V211 log entry. Run it before and after
cut-over; differences must be explained by buckets the old formula missed.

## 7. Tests

`CentralCreditIT` (one limit for every channel; no double counting across order → delivery → invoice; reservation under
concurrency; overrides audited; sub-limits only lower), `CentralPricingIT` (one price for every channel; precedence
configurable and deterministic; breakdown; delivery orders keep the source price; snapshots immutable; price-list edits
never change confirmed documents), plus every existing credit, pricing, orders, invoicing and fleet test unchanged.
