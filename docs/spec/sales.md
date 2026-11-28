# Sales / Order-to-Cash (`com.inventoryerp.sales`)

Design: [ADR-S011](../adr/ADR-S011-sales-o2c.md). Postings: [`sales-postings.md`](sales-postings.md). Credit:
[`credit.md`](credit.md). Product map: [`PRODUCT_ARCHITECTURE.md`](../PRODUCT_ARCHITECTURE.md).

Status: **design (Step 0)**. Nothing below is built yet. This is a non-Nest extension: no parity routes are added;
existing parity routes keep their contract (see "Backward compatibility").

## 1. Scope and ownership

Sales owns commercial commitments and orchestrates the rest of order-to-cash.

| Capability | Owner | Sales does |
|---|---|---|
| Leads, opportunities, activities | crm | links opportunity ↔ quotation |
| Quotations, orders, amendments, pricelists, agreements, teams, targets, commissions, rebates, RMAs, dunning | **sales** | owns |
| Credit limits, exposure, holds | credit | contributes exposure, asks for decisions |
| Stock, reservations, movements, valuation | inventory | requests reserve / release via API |
| Delivery orders, trips, POD, discrepancies, weighing | fleet | creates DOs via API, consumes events |
| Invoices, credit notes, payments, deposits, allocations | invoicing | requests documents via API, consumes events |
| GL | accounting | never posts directly |
| Production supply, make-to-order | manufacturing (later) | ports only (`SupplyProvider`, `MakeToOrderPort`) |

## 2. Supported flows

| Flow | Path | Phase |
|---|---|---|
| Wholesale | Quote → SO → DO → Invoice → Payment | S1, S5, S6 |
| Direct order | SO (no quote) → DO → Invoice | S1 |
| Advance | Quote → Deposit → SO → DO → Final invoice (deposit applied) | S6 |
| Contract | Agreement → release SO → DO → Invoice | S9 |
| Collect at yard | SO → weighbridge (no trip) → Invoice | S5 |
| Make-to-order | SO → production request → DO → Invoice | after manufacturing integration |
| Return | RMA → inspection → credit note / replacement SO / refund | S7 |
| Walk-in / counter | POS (unchanged) or direct invoice (existing) | — |
| Branch cash sale | Showroom CSS (sale + stock + payment) → HO review → invoice ([`cash-sales.md`](cash-sales.md), ADR-S056) | CS-1..CS-4 |

`SalesSettings.flows` enables flows per organization; disabling a flow never disables a control (credit, approvals,
idempotency, stock and GL integrity).

## 3. Documents and states

### 3.1 Quotation (`SalesQuotation`, `QT-YYYY-nnnnn`, numbering continues from CRM)
States: `draft → pending_approval → approved → sent → accepted | rejected | expired | cancelled → converted`.
- Header: customer, contact, branchId (mandatory), opportunityId, pricelistId, currency, paymentTermId,
  validUntil, salespersonId, teamId, invoice / ship-to address or `FleetCustomerSite`, notes / terms, templateId.
- Lines: product, unit snapshot (`uomId`, `uomFactor`), qty, unitPrice, priceSource + ruleId, discount %, taxes,
  optional flag.
- Editing a `sent` / `accepted` quotation creates an immutable `SalesQuotationRevision` (full snapshot, `revision++`).
- `rejected` covers two cases told apart by `declinedAt`: an internal approval rejection (`declinedAt` null) is edited
  in place and resubmitted; a customer decline (`declinedAt` set: staff, portal or online link) is not editable or
  resubmittable in place — **Revise** snapshots the declined terms and reopens a new revision (V675).
- Expiry job (ShedLock, daily) moves past-`validUntil` **open offers** (`approved` / `sent`) to `expired` and alerts the
  owner. Opening the list does the same (expire + alert): whichever runs first expires and alerts a quote exactly once.
  Drafts never expire; a draft past its date cannot be submitted ("The expiration date has passed; set a new one before
  submitting"). An expired-but-not-yet-swept offer refuses send / accept / convert / email.
- Expiry follow-up (V676): `validityDaysLeft` on list rows and detail; list filter `expiringWithin=N` (open offers due
  within N days); the job also reminds the owner once, `quoteExpiryReminderDays` (default 3, 0 = off) before the date
  (`expiryRemindedAt`; a new `validUntil` or a revision re-arms it).
- Conversion to a proforma keeps each line's variant and unit (the unit factor is resolved again, as for orders).
- Submit checks: discount bands, margin floor, amount bands (S2), credit (S3) → approval when a rule triggers.
- Convert → `SalesOrder` (or proforma through `DocumentBuilderService`). The proforma keeps the payment term, delivery
  date / address, currency and rate; the counter order keeps branch and payment term, and its notes carry the delivery
  address and date.

#### Quotations production readiness (2026-11-26, V676)
- **Optional products** (`isOptional` per line): priced and shown (PDF prefix "Optional:"), not counted in the header
  totals. Approval amount and credit checks use the total with every option. Accept takes `optionalLineIds` (staff:
  `POST /{id}/accept`; customer: public accept) — those lines become regular and the totals are recomputed; untaken
  options never reach the order or proforma. A quotation needs at least one non-optional priced line to submit.
- **Stable line ids**: a saved line sent back with its `id` keeps it; unknown ids get a new one.
- **Order discount** (% or amount), **exchange rate**, **contact**, **customer site** and **agreement** are editable
  on the quotation form.
- **Preview pricing**: `POST /sales/quotations/resolve-prices` and `/crm/quotations/resolve-prices` (quotation write)
  price lines exactly as saving does (customer, date, branch, site, currency, agreement, variant, unit, quantity).
  Submit-time pricing memoizes identical resolutions.
- **Email compose**: `POST /{id}/email` takes `to`, `cc` (≤ 10, validated), `subject`, `body`; defaults from the sales
  settings `quoteEmailSubject` / `quoteEmailBody` (placeholders `{number} {customer} {validUntil} {total} {company}
  {link}`, filled by the client). The PDF is rendered in a read-only transaction, the mail sent outside any
  transaction, then the quotation is marked sent; the answer says whether SMTP delivered it.
- **Mark as sent** needs `sales:quotation:send` (CRM family: `crm:quotation:write`) in the UI too, and leaves a done
  timeline activity "Quotation N sent" on the opportunity / customer.
- **CRM decline** passes `lostReasonId` through, as the sales route does.
- **List totals** carry `totals.byCurrency` (`currencyCode`, `count`, `total`); the screen shows one total per currency.
- **Duplicate**: `POST /{id}/duplicate` (201) → a new draft with the same header and lines (rule prices re-resolved).
- **Templates** (`SalesQuotationTemplate`, RLS): `GET/POST /sales/quotation-templates`, `GET/DELETE
  /sales/quotation-templates/{id}`; saved from a quotation (lines, notes, terms), name unique per organization
  (case-insensitive, 409), soft delete. The editor starts a new quotation from `?templateId=`.
- **Revision history**: `GET /crm/quotations/{id}/revisions` (the sales route already existed); the editor compares any
  two revisions (or one with the current) field by field and line by line.
- **After online acceptance** (`quoteOnlineAcceptAction`: `none` | `sales_order` | `proforma`): the acceptance commits
  first; the conversion runs in its own transaction. On success the owner is notified
  (`sales.quote_link.converted`) and the public page shows the order number, or for a proforma the advance to pay with
  the bank / mobile money details. On failure the acceptance stands and the owner is told to convert by hand
  (`sales.quote_link.convert_failed`).

### 3.2 Sales order (`SalesOrder`, `SO-YYYY-nnnnn`)
States: `draft → pending_approval → confirmed → in_fulfilment → done | cancelled`; overlays `on_hold` (credit / manual);
line or order `closed_short` (approval).
Derived: `deliveryStatus` none / partial / full; `invoiceStatus` nothing / to_invoice / partial / invoiced.
- Header: quotationId, agreementId, customer, invoice address, ship-to site, branchId (mandatory), source
  locationId, pricelistId, currency + rate (locked at confirm), paymentTermId, salespersonId, teamId, customer PO
  number, requestedDate, commitmentDate, deliveryMethod (`delivery` | `collect`), incoterm, policy overrides,
  `@Version`.
- Line: product / variant, `uomId` + `uomFactor` snapshot (ADR-S003 `UomConversionService`), unitPrice, priceSource +
  ruleId, discount %, taxes, lineTotal, promisedDate, sourceLocationId, costSnapshot, tolerancePct.
- Transitions registered in `WorkflowRegistry`, one permission per transition, audited.

### 3.3 Amendments (`SalesOrderChangeRequest` → `SalesOrderRevision`)
Draft orders edit freely. From `confirmed` on, every change is a change request:
1. Requested changes: qty, dates, site, product / grade, price, payment terms; reason, requestedBy, effectiveDate.
2. Impact preview: reservations to add / release, open DOs / trips affected, credit exposure delta, invoiced lines
   affected, approval rules triggered.
3. Approval through `ApprovalsService` when a rule triggers.
4. Apply: immutable `SalesOrderRevision` (rev n+1, field-level diff); reservations, DOs and exposure recalculated;
   event `sales.order.amended`.

Constraints: qty never below billable or net invoiced; product / grade change on a partly delivered line = close
short the old line + add a new line; price change applies only to not-yet-invoiced quantity; invoices and delivered
history are never rewritten.

## 4. Quantity model

| Quantity | Authoritative source | Event / trigger |
|---|---|---|
| ordered | sales line (revision) | confirm / amendment |
| cancelled (closed short) | sales line | close-short approval |
| reserved | inventory `StockReservation` (sourceType `sales_order_line`) | reserve / release / consume |
| loaded | fleet `FleetLoadLine` | `fleet.trip.dispatched` |
| weighed dispatch | weighbridge ticket net | `weighbridge.ticket.completed` |
| received | fleet POD | `fleet.trip.delivered` |
| accepted | POD − approved discrepancy | `fleet.delivery.discrepancy_decided` |
| billable | `SalesFulfilmentDecision` (basis, qty, reason, user, time; superseded, never edited) | billing policy / manual decision |
| gross invoiced | invoicing invoice lines, posted, not cancelled | `invoice.posted` / `invoice.cancelled` |
| credited | invoicing credit-note lines linked to the SO line | `credit_note.posted` |
| net invoiced | gross invoiced − credited | derived |
| returned (physical) | inventory return receipt via RMA | `sales.return.received` |
| outstanding billable | billable − net invoiced (+ re-billable after replacement) | derived |

Rules:
- Returns never reduce gross invoiced; credit notes reverse value without editing the invoice or the order.
- Guard at invoice post, checked against source documents (not only the read model):
  `gross invoiced − credited ≤ billable`.
- `Product.billingBasis` default: aggregates / sand = weighed; blocks = accepted pieces; manual override needs
  `sales:billing:override` and writes a decision record.

### 4.1 Read model reliability (`SalesOrderLineQty`)
- Producers use the existing transactional outbox (`EventOutbox` / `OutboxWorker`); every event has a unique id, a
  payload version `v` and the source aggregate version.
- Consumer inbox `SalesProcessedEvent(eventId, sourceType, sourceId, sourceVersion)` is written in the same
  transaction as the projection update; duplicate id → no-op.
- Projections store **absolute values per source document**, not increments. An event with an older or gapped source
  version triggers a recompute of that source document from its owning API.
- Bounded retries with backoff → dead-letter row + alert; admin replay route.
- `rebuild(salesOrderId)` recomputes from source APIs. Nightly ShedLock job compares projection vs rebuild for open
  orders, reports drift and missing events; admin repair route.

## 5. Fulfilment and recognition policies

Five independent policies; per organization / branch; effective-dated; audited; incoterm-driven defaults; order
override needs `sales:policy:override`. Validation rejects unsupported combinations (matrix in
`sales-postings.md` §3).

| Policy | Values | Executed by |
|---|---|---|
| Physical movement | `direct` (gate-out issues to customer) / `via_transit` (gate-out moves stock to seller-owned customer-transit location, `LocationType.transit`) | inventory, triggered by fleet / collect |
| Ownership transfer | `at_dispatch` / `at_delivery` (POD) / `at_acceptance` | inventory issue from transit |
| COGS recognition | `at_ownership_transfer` (default) / `at_revenue_recognition` | accounting rule |
| Revenue recognition | `at_ownership_transfer` (unbilled-receivable accrual if before invoice) / `at_invoice` (allowed only when invoice ≥ transfer) | accounting rule |
| Billing | `ordered` / `delivered` (basis per `billingBasis`) / `milestone` (agreements, S9) | sales |

S0 / S1 only store these fields. Behaviour arrives in S5 / S6 after client delivery terms and the Uganda VAT tax point
are reviewed.

## 6. Pricing (S2)

- `Pricelist`: type (retail / wholesale / contractor / distributor / contract), currency, validity, branch, optional
  base pricelist + formula.
- `PricelistItem`: product / category / all × min qty × unit × site zone × branch × date range; compute fixed /
  discount % / surcharge on base / cost + margin; priority.
- `MarginFloor`: product / category × branch × customer category × effective dates.
- `PricingPolicy.resolve(customer, product, unit, qty, site, branch, date, agreement)` priority: agreement →
  customer-specific item → customer pricelist → customer-category pricelist → branch default → product list price.
  Returns price, source, rule id, floor.
- Documents snapshot price + source + rule id; history is never repriced; authorised manual override records a reason.
- `CrmPriceRule` rows migrate into a "Migrated rules" pricelist; `/crm/pricing/*` delegates.

## 7. Approvals

Kernel `ApprovalsService` workflows, entity types `sales_quotation`, `sales_order`, `sales_order_change`,
`sales_credit_override` (credit module), `sales_close_short`. Default bands (configurable, not code): discount ≤ 5 %
none, 5–10 % sales manager, > 10 % general manager; below margin floor → director; amount bands per organization.
Approval delegation (out of office) is a kernel enhancement in SX.

## 8. Credit (S3)
See [`credit.md`](credit.md). Check points: quotation submit, SO confirm, amendment, DO release / trip dispatch,
POS credit sale.

## 9. ATP, reservations, backorders (S4)

- `AtpService.check(product, qty, unit, locations | all, fromDate, horizon)` → per location × day: on hand, reserved,
  unreserved committed demand, incoming supply by source type, cumulative ATP; split proposal (location × date).
- Supply providers (SPI): purchase (open PO remainder by expected date + receiving location), transfer (in transit,
  counted at destination once dispatched), production (later).
- No double count: reservations are netted from on hand, so demand counts only the unreserved remainder; supply counts
  until receipt, then it is on hand; a peg against future supply nets that supply, not on hand.
- Reservation at confirm when `inventory.reservationMode = order`; inside the `StockItem` row lock, same transaction
  as confirm → concurrent confirms cannot oversell. Release on cancel / close short / amendment / expiry; consume on
  issue. Priority, optional expiry, audited reallocation.
- Future supply = soft peg (`SupplyPeg`), never a firm reservation; PO late / cancelled / short → re-run ATP, flag
  lines, notify salesperson, re-peg by priority.
- Production supply shows "not connected"; make-to-order action hidden until a live adapter exists.

## 10. Fulfilment (S5)

- Confirm → `FulfilmentOrchestrator` → `FleetDeliveryOrderService.fromSalesOrder` (reads `SalesOrderQuery`); several
  DOs per order keyed by schedule.
- Collect at yard: weighbridge ticket `sale_delivery` with `salesOrderId` (foreign key) → issue + delivery note.
- Tolerance ± % per product auto-closes a line; close short needs approval.
- Depends on Track C 1.4b (weighbridge ticket ↔ trip).

## 11. Billing, deposits, payments (S6)
See [`sales-postings.md`](sales-postings.md). Modes: regular (per policy), down payment % / fixed, batch (per
customer / period). Deposits through `DepositApplication`. Mobile money via a generic `PaymentGateway` SPI owned by
invoicing (MTN MoMo, Airtel, mock).

Web: `/sales/deposits` (Customer Deposits) lists deposits by status with an open-balance total. From that page a user can
record an advance with or without a sales order, apply a deposit to a posted invoice (partial or full), refund it, void
an unused receipt, and reverse an application from the detail view (needs `payment:void`). The same list appears as a
"Deposits" tab on the CRM customer page. On the sales-order `BillingPanel`, the deposit rows also offer Refund and
Void. Cash-sale deposits stay with their cash sale and get no refund/void/reverse actions here.
Deposit receipt: `GET /customer-deposits/:id/pdf` (`payment:read`) returns an A5 PDF with the business header
(`Organization.receiptHeader`: name, address, phone, TIN), the customer (code and TIN), the linked sales order, the
method and reference, the amount received, the applied / refunded / available balance, the history (applications,
refunds, reversals struck through), who received it, and a "not a tax invoice" note. A void deposit prints as
"DEPOSIT RECEIPT — VOID". There is a Print button on the deposits list, the deposit detail and the sales-order billing panel.
Deposit receipt header (Sales settings → Deposit receipt, `SalesSettings.depositReceiptLayout`, JSONB, V582): title,
business name (blank = company settings name, not the POS receipt header), detail lines (blank = company settings
address / phone / e-mail / TIN), logo on/off, detail lines on/off, received-by/signature line on/off, footer note
(blank = none; default is the "not a tax invoice" note). `PUT /sales/settings` validates it (unknown keys, booleans,
lengths) and merges partial updates. `POST /sales/settings/deposit-receipt/preview` (`sales:configure`) renders the
unsaved layout on the latest deposit, or on a sample. Invoicing renders the receipt through the `DepositReceiptBranding`
interface, which the sales module implements, so invoicing does not depend on sales.
Customer statement (`StatementService`, AR side): deposits sit on the customer-deposit liability, not on AR, so the AR
lines and closing balance are unchanged. The statement adds a `deposits` section built from the deposit subledger:
`openingBalance`, `lines` (each with `date`, `reference` = deposit number, `description`, `received`, `used` and
running `balance`), `totalReceived`, `totalUsed` and `closingBalance`. A receipt adds to the amount held. An
application to an invoice and a refund each use it. A reversed application gives the amount back on its reversal
date. Void deposits are left out. It also adds `netBalance` = AR closing − deposits held (negative = the customer has
money on account). The statement PDF (sales, AR report, portal, e-mailed) prints the section with "Deposits held"
and "Net amount due after deposits" whenever there is anything to show. A filtered sales statement (branch / currency /
type) omits both fields, because deposits are not split that way.
Foreign-currency deposits (ADR-S080): `currencyCode` / `exchangeRate` on receipt, received into a designated account held
in that currency, never revalued, applied only to invoices in the same currency (realised FX at application and
refund). Statement, credit and report totals are in functional value at each deposit's own rate.
Known gaps: no deposit tender at POS/Android; cash-sale and mobile-money deposits are functional only.

## 12. Returns (S7), collections (S8), agreements / rebates / commissions / targets (S9), analytics / portal / AI (S10)
As in the approved plan; detailed here when each phase is authorised.

## 13. API (planned, S1–S2)

| Path | Notes |
|---|---|
| `/sales/quotations` | list (filters: status, customer, salesperson, branch, expiry), create |
| `/sales/quotations/{id}` | get, update (draft) |
| `/sales/quotations/{id}/{submit,approve,send,accept,reject,cancel,convert,revise}` | transitions |
| `/sales/quotations/{id}/{pdf,email,revisions}` | |
| `/sales/orders` | list, create |
| `/sales/orders/{id}` | get, update (draft only) |
| `/sales/orders/{id}/{confirm,hold,release,cancel,close-short,duplicate}` | transitions |
| `/sales/orders/{id}/change-requests` | create, preview, apply |
| `/sales/orders/{id}/{revisions,relations,quantities,pdf,email}` | smart buttons via `DocumentRelationsService` |
| `/sales/pricelists`, `/sales/pricelists/{id}/items`, `/sales/pricing/resolve`, `/sales/margin-floors` | S2 |
| `/sales/teams`, `/sales/settings` | |
| `/sales/reports/{quotations,orders-open,orders-to-fulfil,conversion,pending-approvals}` | R1 ops reports |

Lists use the standard list envelope; errors via `ApiException`; transitions that move money are `@Idempotent`.

## 14. Permissions (planned)
`sales:quotation:{read,write,approve,send,convert}`, `sales:order:{read,read_all,write,confirm,cancel,close_short,hold_release,amend,amend_approve}`,
`sales:pricing:{read,manage}`, `sales:billing:override`, `sales:policy:override`, `sales:report:read`,
`sales:configure`. Visibility without `read_all`: own, unassigned and led-team records, filtered by the user's
branch scope.

## 15. Events (planned, payload `v: 1`)
`sales.quotation.{submitted,approved,sent,accepted,rejected,expired,converted}`,
`sales.order.{confirmed,held,released,amended,cancelled,closed_short,done}`, `sales.fulfilment.decided`,
`sales.return.received` (S7).

## 16. Backward compatibility (checked every phase)
- `/crm/quotations*`, `/crm/pricing*` — same JSON, delegate to sales.
- `/orders` — unchanged for POS / back office.
- Fleet `from-sales-order` legacy path for POS-order sources.
- POS e2e (`reference/e2e`) and Android sync unaffected.

## 17. Migration of CRM quotations (S0)
- Copy `CrmQuotation`, `CrmQuotationLine`, `CrmTeam` into sales tables **keeping ids**; quotation numbering continues.
- Revisions, approvals (`entityType` alias `crm_quotation` → `sales_quotation` through a mapping, originals untouched),
  attachments (owner-type alias), activities, communications, converted POS order references preserved.
- CRM tables write-blocked by trigger for one release; dropped by a later migration after owner verification.
- Verification queries and rollback in `docs/runbooks/sales-quote-migration.md`.

## Deviations

- **IPR Phase 1a (2026-11-27; owner sign-off required).**
  - **F2: restock only what left stock.** A return line with no recorded stock issue (for example an order-policy
    line invoiced but never delivered) is a financial credit only. Receive and inspect move no stock. A line with an
    issue is capped by what that issue still has out, net of every return already linked to it (credit-note restocks
    included), less what returns not yet received have claimed. Over the cap, `POST /sales/returns` answers 400 with
    the existing "more than returnable" refusal. Before, a return restocked goods that never left, at the current
    cost.
  - **M-27.** `POST /sales/orders/:id/reserve` runs in one transaction. It locks the sales order first, then each
    line's cell, so two concurrent reserves cannot both take the same free stock.
  - **LK-3.** The fulfilment rebuild, `recomputeInvoiced` and return receive/close lock the `SalesOrder` row before
    its `SalesOrderLine` rows, the order every other sales path uses.
  - **F5.** A cash-sale confirm, an invoice auto-issue or collection, and a fleet delivery that is not the sales
    order's own are refused when they would take stock reserved for sales orders ("Only X of P is free in L; Y is
    reserved for sales orders. Release or deliver those reservations first."). POS, stock documents, transfers,
    production and quarry issues are not covered (see the IPR 1a evidence file).

- **R6 accounting policy settings (2026-11-26, V683, owner to approve).** New `SalesSettings` keys, each defaulting to
  the shipped behaviour: `showroomTaxPoint` (`at_confirm` | `at_invoice`), `overOrderExcess` (`bill` | `absorb`),
  `rebateTaxId` (tax or null). `depositTaxPoint = receipt` now also refuses a plain advance on a taxed order at
  `POST /sales/orders/{id}/deposits`, `POST /customer-deposits` (with `salesOrderId`) and the mobile-money deposit
  initiation, pointing to the down-payment invoice. The accountant's choices are recorded in `sales-postings.md`,
  "Sales Accounting Go-Live Sign-off".
No parity routes are involved. Behaviour changes of the 2026-11-26 readiness fixes (R2–R5, owner to approve):

- Money routes of orders, cash sales, deliveries and returns require an Idempotency-Key (400 without one).
- Credit approval of a draft re-runs the full confirm checks in a savepoint; when they fail the order stays a draft and
  the requester is told why. A `pending_approval` order is no longer confirmed by a credit approval. Pending credit
  requests close when the order is deleted or cancelled. Sales events go out after commit only.
- Down payments of an order together may not exceed the order total.
- Close short and approved change requests resync the delivery order; a change that leaves less than is already planned
  on deliveries is refused. A trip may not dispatch more than the order line (plus its tolerance) allows.
- Serial-tracked lines name their serial numbers before dispatch (`POST /sales/deliveries/:id/issue` takes an optional
  `{lines:[{loadLineId, serialNumbers}]}`; column `FleetLoadLine.serialNumbers`, V677). Returns name the serials that
  came back.
- A material delivery edit (lines, date, warehouse) clears its approval; neither the creator nor the last editor may
  approve; only a delivery that has not left can be approved. Approval and blocking QC run from every dispatch route,
  fleet trip routes included (`DispatchGate`).
- The handover may not mark an order with stocked goods, or with deliveries, dispatched/delivered.
- Order status refresh runs under the order lock; an order closed short on every line before anything left becomes
  done. Re-planning reservations after a change respects `reservationTrigger = none`.
- A draft save carrying an older `version` answers 409. Header references (warehouse, site, payment term, team, branch)
  must belong to the organisation (404).
- Business dates (issue, deposit, planned delivery, numbering year) use the organisation's business zone.
- The backorder and reconcile jobs run one transaction per order.

Behaviour changes of the 2026-11-26 R6/R7 round (owner decisions 2026-11-26 for DEL-9 and INV-11; the rest owner to
approve):

- **SO-10** Order approval needs the new permission `sales:order:approve` (V678 grants it to every role holding
  `sales:quotation:approve`). Approve and reject decide the kernel approval request; a multi-step policy keeps the order
  pending until its last step.
- **DEL-9a** Damaged and short quantities at delivery move their issue cost out of cost of goods sold into a
  "Delivery losses" expense account (setting `deliveryLossAccountId`, else 5190 created on first use; switch
  `deliveryLossPosting`, default on). A proof-of-delivery correction posts only the difference
  (`FleetLoadLine.lossValue`, V680). No carrier claims.
- **DEL-9b** Goods the customer rejects (proof of delivery, or a whole-load return for customer_rejected /
  wrong_product / damaged) go into a quarantine location (setting `rejectedQuarantineLocationId`, else the virtual
  location SALES-QUARANTINE; switch `rejectedToQuarantine`, default on), not back into saleable stock. A failed attempt
  (site closed, etc.) still returns to the yard.
- **DEL-8** Issuing a delivery refuses stock that is firmly reserved for another order (400 "Only X of P is free at this
  location; Y is reserved for other orders").
- **DEL-10** What can be returned is the accepted quantity for delivered goods (not rejected, damaged or short).
- **DEL-11** An issued gate pass whose source quantities fell below the pass is flagged `source_quantity_changed` and
  cannot gate out.
- **DEL-12** `GET /sales/deliveries/:id/note` views without counting a print; `?print=true` counts. One live delivery
  order per sales order is enforced under an advisory lock.
- **SO-9** Duplicating an order keeps variant, source location, tolerance, discount amount, dimensions and a future
  promised date. **SO-13** The order list total is in base currency (`total`) with a `byCurrency` breakdown; credit
  checks convert foreign-currency orders to base.
- **INV-12** Dunning works per customer and currency, net of open credit notes and unallocated receipts; fees and holds
  apply once per customer per run; emails go after commit (`DunningEntry.currencyCode`, V679). A document without a
  currency counts as the organisation's base currency, and every entry stores the code (2026-11-27: a base-currency
  entry stored as NULL read as a pre-V679 all-currency reminder and suppressed the same run's other currencies).
- **X-7** `GET /sales/deliveries` pages on the server (`limit`, default 500, max 1000; `offset`; `search`) and returns
  the filter's total in `X-Total-Count`; reconciliation is scoped to open orders and orders done in the last 90 days
  unless `orderId` or `all=true` is given (`limit`/`offset` too). The backorder report shows at most 1000 lines and the
  backorder job takes 500 orders per run. Index V682.
- **X-6** Reads and writes by id in sales, invoicing and fleet services also filter by the organisation.


### IPR review 1 (2026-11-27, awaiting owner approval)

- **Returns of goods not issued by fleet (review 2, N2).** Receiving a sales return finds the order line's stock issue
  on every path: its fleet load lines (also a delivery of the line's invoice), a legacy load line without its code
  (the trip's `fleet_trip` issue rows of the product), and the line's invoice issue (`sales_invoice` rows of the linked
  invoice line). The goods come back against those rows, never more than they still have out. When none exists, the
  line restocks nothing and an open `InventoryException` of kind `sales_return_unmatched` (payload `returnId`,
  `orderLineId`, `receivedQty`) asks staff to check and restock by hand. Like other open stock exceptions it holds a
  period close until resolved.

## Central pricing engine (ADR-S022)

`SalesPricingEngine` prices every channel (sales directly; POS orders, invoices, manual delivery orders and CRM through
`core.PricingEngine` / `PriceListService.resolve`). Tiers in `PricingPolicy.precedence` (default: agreement,
customer_rule, crm_rule, customer_pricelist, category_pricelist, price_list, branch_default, org_default,
standing_discount; catalogue list last). Every result carries `breakdown` (base, discount, net, tax, final, line totals).
`PriceSnapshot` (insert-only) records each line at sales order confirmation, invoice posting, POS billing and manual
delivery order creation. Routes added: `GET|PUT /sales/pricing/policy`. Tests: `CentralPricingIT`, `PriceListIT`.

Site price lists (ADR-S075): a price list with `customerSiteId` (a `FleetCustomerSite` of the customer; never a default
list) prices only quotes / orders / invoices for that delivery site. Tier `site_pricelist` sits after `promotion`; an
org policy saved before it existed gets it inserted ahead of the first tier that is not agreement / promotion. The
site is passed as `siteId` to `POST /sales/pricing/resolve`. Invoices raised from a delivery order or a site-tagged
sales order store `customerSiteId` / `projectId` in `Document.data`.

## Delivery & fulfilment handover (orders and invoices)

Same model as the branch cash sale delivery tab (`docs/spec/cash-sales.md`), stored in `SalesHandover` (V350, one
row per `sourceType` + `sourceId`, created on first update; until then defaults come from the source document).

| Route | Permission |
|---|---|
| `GET/PATCH /sales/orders/:id/handover` | `sales:order:read` / `sales:order:write` |
| `GET/PATCH /sales/invoices/:id/handover` | `invoice:read` / `invoice:update` |

- Method `pickup` (customer collect from a store / warehouse `locationId`) or `delivery` (to site, `address`).
- Status `pending → scheduled → dispatched → delivered`, or `failed`. Dispatched / delivered need the order
  confirmed (or in fulfilment / done) or the invoice posted / paid. Scheduled needs `scheduledDate`; non-pending
  delivery needs `address`, non-pending pickup needs `locationId`. Delivered stamps `deliveredAt` / `deliveredById`
  and then only `receivedBy` and `notes` stay editable. A cancelled source is read-only.
- On a sales order, method / address write through to `SalesOrder.deliveryMethod` (`collect` ↔ `pickup`) and
  `deliveryAddress`. Logistics only: stock still moves through fleet delivery orders / invoice posting.
- Site details (V365, 2026-11-05 owner request): `landmark` (GPS / landmark), `loadingSite`, `deliveryScope`
  (`full|partial`, 400 `deliveryScope must be one of: full, partial`) and `offloadingEquipment` (`yes|no`, 400
  `offloadingEquipment must be one of: yes, no`). All optional; web drops landmark / offloading for customer collect.

## SG-1 to SG-12 (2026-11-06)

Decisions and behaviour changes: [ADR-S059](../adr/ADR-S059-sales-gap-closure.md). Migrations V401-V409.

| Phase | Routes and settings | Tests |
|---|---|---|
| SG-1 access | `sales:cost:read`, `sales:branch:override`; scope on orders, quotes, collections, report center | `SalesAccessIT` |
| SG-2 orders | `stockCheckPolicy`, `orderApprovalAlways`, `notifyOnHold`, `notifyOnShortage`; `POST /sales/orders/{id}/lines/{lineId}/source-location` | `SalesOrderPolicyIT` |
| SG-3 quotes | `/sales/quotations/{id}/pdf`, `/email`; `/sales/lost-reasons`; daily expiry job; TRIP and LOAD units | `SalesQuoteProformaIT` |
| SG-4 proforma | `/sales/proformas`, `/{id}/pdf`, `/{id}/email`; `POST /sales/orders/{id}/proforma` | `SalesQuoteProformaIT` |
| SG-5 customers | `/sales/customers/duplicates`, `/import`, `/export`, `/{id}/profile`, `/suspend`, `/reinstate`, `/merge` | `SalesCustomerIT` |
| SG-9 delivery | `/sales/deliveries`, `/reconciliation`, `/{id}/issue`, `/approve`, `/confirm`, `/photos`, `/note`; `/sales/orders/{id}/milestones`, `/recurring` | `SalesDeliveryIT` |
| SG-6 pricing | `/sales/promotions`, `/sales/price-limits`, `/sales/pricing/grid`, `/lists`, `/history` | `SalesPricingToolsIT` |
| SG-7 returns | `damaged` disposition, `rejectedQty`; `/sales/credit-notes`, `/allocate`, `/refund`, `/pdf`; `/sales/invoices/{id}/write-off` | `SalesReturnFlowIT` |
| SG-8 credit | `overLimitPolicy`, `cashBypassesCredit`; `/sales/credit-requests`; `/sales/customers/{id}/credit-by-branch`; policy `enforcePeriodAndReview` | `SalesCreditPolicyIT` |
| SG-10 statements | `/sales/statements/{id}`, `/outstanding`, `/send-bulk`, `/log/{id}/confirm`, `/disputes` | `SalesStatementIT` |
| SG-11 commissions | rule tiers, splits, product and customer scope; statement adjustments, disputes, paid, carry-forward, PDF | `SalesCommissionTargetIT` |
| SG-12 targets | target `metric`, `periodType`, alerts job; `/sales/analytics/kpis` | `SalesCommissionTargetIT` |

Known limits:

- `Pricelist` and `PriceList` are not merged (ADR-S059 §4).
- A merged customer's balance rolls up in Sales KPIs and Sales statements only.
- Numbering per branch covers sales orders. The organization is the legal entity.
- Agreement on a quotation has an API field and project auto-pick, no picker on the quotation screen.
- Commission `paid` basis costs lines at current product cost; only `invoiced` uses actual cost.
- Customer deposits in reports are still not converted (no rate stored).

## SG-0 stabilization (2026-11-06)

Defects fixed from the Sales gap audit; `SalesStabilizationIT` and `SalesReturnIT` pin them.

- `priceSource` accepts `price_list` on quotation and order lines (V400).
- A return closed with a refund pays only the credit left after the invoice's open balance (`takeOpenCredit`). When the
  credit clears the balance entirely, no payment is made and `refundPaymentId` stays null.
- A sales order's invoice carries the order's currency and rate. Sales reports convert documents, order totals and
  payments to base currency at the stored rate.
- Quotations save `branchId`, `paymentTermId`, `siteId`, `deliveryAddress` and line `uomId`, and price with the header's
  site, branch and currency. Orders price with theirs too.
- New quotations and orders, and confirming a draft order, answer 400 `This customer is not active`.

Known limits, left for later phases:

- Quotation reports stay in document currency. Quotations gained `exchangeRate` and pass it to the order in SG-3.
- Customer deposits in reports are not converted (no rate stored).
- Invoices already billed at rate 1 from foreign orders are not changed; check production before relying on old totals.

## Quotation form layout (2026-11-09, V431)

Odoo-style quotation form (`/sales/quotations/new`, `/sales/quotations/:id`; reference `references/invoice-reference/quotation-ui.png`).

- **Section and note rows.** A quotation line may carry `displayType` = `line_section` or `line_note` with only a
  `description`. These rows keep their place (`sortOrder`), carry no amounts, and are left out of submit
  ("Add at least one line before submitting"), the discount and margin checks, conversion (sales order, counter
  order, proforma), the PDF and print templates. An unknown value is refused with
  `displayType must be one of: line_section, line_note`.
- **Delivery date.** `SalesQuotation.commitmentDate` (optional) becomes the sales order's `commitmentDate` on conversion
  unless the convert request sets one.
- **Lead time.** `SalesQuotationLine.leadTimeDays` (optional, ≥ 0; `leadTimeDays must not be less than 0`).
- **Web.** Header: Customer, Delivery address | Expiration, Quotation date, Payment terms, Delivery date. Tabs:
  *Order Lines* (Description · Quantity · Unit Price · Amount; optional columns Product, Product Variant, Unit of
  Measure, Lead Time, Disc.%, Taxes, Unit Cost, Margin, Margin (%), Tax Excl., Tax Incl., remembered per browser;
  Add a line / section / note, Catalog, drag to reorder, section subtotals; terms below the lines; totals with one
  row per tax) and *Other Info* (salesperson, branch, currency, dates, customer credit, notes). The
  **Tax Excl. / Tax Incl.** toggle sets whether the unit prices on an editable quotation include tax (sent as
  `taxInclusive` on every line) and switches the Amount column; on a locked quotation it only switches the display.

## Product images on quotations (2026-11-12, V483)

Some customers want product pictures on their quotations, others do not, so the feature is a setting.

- **Setting.** `SalesSettings.quoteLineImages` (boolean, default `false`), edited in Sales settings → Quotations and in
  CRM settings → Quotation approval (`PUT /sales/settings` or `PUT /crm/config/settings`; also returned in
  `GET /crm/config/lookups` → `settings`).
- **Detail.** `GET /sales/quotations/{id}` returns `lineImages` (the setting) and, per line, `productImage`: a fresh
  signed URL of the product's image, or `null` (setting off, no product, no image).
- **Web.** While the setting is on, the lines table offers an optional **Image** column (thumbnail, opens full size).
  Each user shows or hides it; the choice is remembered per browser apart from the other optional columns, so it
  starts shown when the setting is turned on.
- **Print and PDF.** *Print* and *Download PDF* add an Image column exactly when the user sees it
  (`GET /sales/quotations/{id}/pdf?images=true|false`). Without `images` (e.g. the emailed PDF) the PDF follows the
  setting. While the setting is off no image prints, whatever `images` says. A picture that is missing, too large
  (> 5 MB) or in a format the PDF library cannot read (e.g. WebP) leaves its cell blank.
- Tests: `SalesQuoteProformaIT.productImages`, `SalesDocumentPdfTest`.

## Quotation layout (2026-11-19, V579, owner request)

Extension (no Nest counterpart). Users choose how quotations print without editing HTML (the HTML designer in
Settings → Print templates stays separate and is not used by quotations).

- **Setting.** `SalesSettings.quoteLayout` (JSONB, default `{}`). `GET /sales/settings` returns it with every key
  filled in (`QuoteLayout.DEFAULT`); `PUT /sales/settings` `{quoteLayout: {...}}` takes a partial object merged onto the
  saved one. Keys: `title` (≤ 60, blank = `QUOTATION`), `companyName` (≤ 120, blank = company settings name),
  `showLogo`, `showCompanyDetails`, `accentColor` (`#RGB` / `#RRGGBB`), `paperSize` (`A4|A5|Letter`), `orientation`
  (`portrait|landscape`), `showUnit`, `showDiscount`, `showTax` (default off), `showSize`, `showSections`,
  `showSalesperson`, `showNotes`, `showTerms`, `showSignature` (default off), `headerText` (≤ 2000), `footerText`
  (≤ 500, every page). Errors (400): `quoteLayout.<key> should not exist`, `quoteLayout.<key> must be a boolean value`,
  `quoteLayout.<key> must be at most <n> characters`, `quoteLayout.accentColor must be a hex colour like #1F2937`,
  `quoteLayout.paperSize must be one of: A4, A5, Letter`, `quoteLayout.orientation must be one of: landscape, portrait`,
  `quoteLayout must be an object`.
- **Company name.** Quotations print the company settings name (`Organization.name`, Settings → Developer company
  settings) unless the layout's `companyName` overrides it. Before, they printed the POS receipt header's business name.
  Contact lines: organization settings `address` / `phone` / `email` / `taxNumber`, falling back to the receipt header;
  logo: the company settings logo.
- **PDF.** `GET /sales/quotations/{id}/pdf` (and the emailed PDF) renders `SalesQuotationPdf` with the layout: logo and
  company on the left, title, number, customer, dates and salesperson on the right; an accent-coloured lines table with
  optional Image / Unit / Disc % / Tax columns; section and note rows; the size under the description; totals; notes,
  terms, signature block; footer text and page number on every page. Proformas keep `SalesDocumentPdf`.
- **Preview.** `POST /sales/settings/quote-layout/preview` `{layout}` (`sales:configure`) returns the PDF inline for an
  unsaved layout, on the latest quotation the user can see, or on sample lines. Nothing is saved.
- **Web.** Sales settings → *Quotation layout* card (options, live preview, own Save). The quotation's *Print* now prints
  the same server PDF through a hidden frame (the browser-built print page is gone), so Print, Download PDF and email
  look the same.
- Tests: `SalesQuotationPdfTest` (defaults, validation, rendering), `SalesQuoteProformaIT.quoteLayout`.

## Delivery & fulfilment management (2026-11-13, V484, owner request)

Post-parity enhancement (not in the Nest reference). One sales order → many deliveries; a delivery is the existing
manual-carrier `FleetLoad` (SG-9), so stock, costing, billing and discrepancies keep their single path. The order's
Delivery & fulfilment tab (`web/src/pages/sales/delivery/`) manages everything inline.

| Route | Permission |
|---|---|
| `GET /sales/orders/:id/delivery-summary` | `sales:delivery:read` |
| `POST /sales/orders/:id/deliveries` (extended) | `sales:delivery:write` |
| `PATCH /sales/deliveries/:id` | `sales:delivery:write` |
| `GET /sales/deliveries/:id/history` | `sales:delivery:read` |
| `POST /sales/deliveries/:id/issue` (was `write`) · `/depart` | `sales:delivery:dispatch` |
| `POST /sales/deliveries/:id/confirm` (was `write`) · `/fail` · `/return` | `sales:delivery:confirm` |
| `POST /sales/deliveries/:id/cancel` | `sales:delivery:cancel` |

V484 grants the three new permissions to every role holding `sales:delivery:write` (no access lost).

- **Per delivery** (FleetLoad columns): `deliveryMethod` (`customer_collect|site_delivery|warehouse_transfer|courier`),
  `scheduledTime` (HH:mm), dispatch warehouse (`locationId`), `destinationLocationId` (required for a transfer),
  `deliveryAddress`, site contact, driver + phone, vehicle + type, `transportCompany` (required for courier),
  `trackingNumber`, `route`, `offloadingEquipment`, `expectedArrivalAt`, `arrivedAt`, `transportCost`, `tripExpenses`,
  `attemptFailed`, `rescheduledFromId`. A plate is optional for customer collect and courier only.
- **Stage** (derived): planned/loading/weighed → `scheduled`; `dispatched`; `in_transit`; delivered/closed →
  `delivered`, or `partially_delivered` when any line delivered less than dispatched; returned → `failed`
  (`attemptFailed`) or `returned`; `cancelled`.
- **Order stage** (summary, independent of each delivery): `delivered` when every line's delivered ≥ ordered (ordered =
  quantity − closed short), else `partially_delivered` if anything delivered, else `dispatched` if anything on the
  road, else `scheduled` if anything planned, else `pending`. `SalesOrder.deliveryStatus` is unchanged.
- **Quantities per line, one bucket each**: scheduled (planned on open trips), dispatched (on the road), delivered
  (accepted at POD), rejected, damaged, shortage (= dispatched − accepted − rejected − damaged), returned (whole-load
  returns / failed attempts). Outstanding = ordered − delivered. To schedule = ordered − scheduled − dispatched −
  delivered − damaged − shortage (rejected and returned goods can go again). Percent delivered is weighted by line
  value (falls back to the line average with no prices).
- **Rules**: over-planning is refused under the delivery-order lock (no override); concurrent first deliveries on one
  order are serialised by a transaction advisory lock; quantities change only while `planned` (`FleetDispatchService
  .replan`); after dispatch only tracking, contact and costs change, after delivery only costs. Stock is issued once
  at dispatch; confirmation only returns rejected goods; fail / return bring the whole load back at issue cost. POD
  quantities cannot exceed what was dispatched.
- **Documents**: per-delivery note PDF (existing), POD photos and signature, and DMS attachments on record type
  `sales_delivery`. History = `AuditLog` rows of the `FleetLoad`.
- **Order plan** (`SalesHandover`): methods `transfer` and `courier` added; new fields `gpsCoordinates`,
  `offloadingSite`, `destinationLocationId`, `courierName`. The sales order tab no longer offers the manual handover
  status buttons (Mark dispatched / delivered without stock movement); the status comes from the deliveries. The
  invoice handover tab is unchanged.
- Tests: `SalesDeliveryManagementIT` (multi-product partials, edit / re-plan / cancel / history, fail and reschedule,
  shortage / damage / rejection, whole-load return, methods, concurrency, permissions).

Invoices and branch cash sales (new and saved) use the same delivery tab layout (`HandoverDeliveryView`): status
stepper with actions, overview tiles, the delivery plan panel, transport & schedule, receipt & notes and line
fulfilment. They keep their single-handover model (`/sales/invoices/:id/handover`, `/sales/cash-sales/:id/delivery`):
no trips and no stock movement from the tab. Cash sales offer customer collect / delivery only (their column CHECK);
invoices offer all four methods. Invoice handover lines now return `uomCode`.

### Delivery columns, delivery note and returns (2026-11-13, V485, owner request)

Post-parity enhancement on the delivery tabs of sales orders, invoices and branch cash sales.

| Route | Permission |
|---|---|
| `GET /sales/invoices/:id/handover/note` (PDF) | `invoice:read` |
| `GET /sales/cash-sales/:id/delivery-note` (PDF) | `sales:cash_sale:read` |

- **Deadline** (V485): `SalesHandover.deadline` and `SalesSubmission.deliveryDeadline` (DATE), the date the goods must
  reach the customer by (the scheduled date is when they are planned to leave). Editable like the other plan fields
  (`deadline` / `deliveryDeadline` on the existing PATCH routes, `<field> must be a valid ISO 8601 date string`); locked
  once delivered. A sales order without one falls back to its commitment date, then its requested date
  (`delivery-summary.deadline`).
- **Line columns** (optional, chosen per user with the sliders button; remembered in browser storage):
  date scheduled, deadline (red when past with quantity outstanding), demand, picked (tick when the whole line is
  picked), issued / dispatched, delivered, invoiced, returned, outstanding, progress. Sources: sales order —
  `delivery-summary.lines[].picked` (loaded, else dispatched, on loading → closed trips), `invoiced` (`qtyInvoiced −
  qtyCredited`), `customerReturned` (`qtyReturned`, received RMAs) + `returned` (whole-load returns),
  `scheduledDate` (earliest planned date of a trip not yet dispatched); invoice handover lines — `pickedQty` (full once
  dispatched / delivered), `invoicedQty` (= quantity), `returnedQty`; cash sale lines — `returnedQty`. The deliveries
  table gains optional Delivery note, Deadline, Returned / rejected and Delivered-on columns.
- **Returns**: sales order — `delivery-summary.returns` (its `SalesReturn`s with lines); invoice — `handover.returns`,
  the credit notes whose `reversedDocumentId` is the invoice; cash sale — `returns`, a void after confirm (all lines
  back) plus credit notes against any of its head-office invoices. Only posted / paid credit notes count towards a
  line's returned quantity (spread by product in line order, capped at the line quantity); drafts are listed with
  `counted: false`.
- **Delivery note**: invoice and cash sale notes are numbered `DN/<document number>` (derived, no sequence) and print
  from `SalesDeliveryNotePdf` (company, delivery address, source document, shipping / scheduled / deadline dates,
  ordered / delivered / returned per product, receipt or signature lines). Sales order deliveries keep the trip note
  (numbered at dispatch); the delivery dialog and the order tab show an on-screen preview and list the notes.
- Tests: `SalesHandoverIT.invoiceColumnsReturnsAndNote`, `SalesHandoverIT.orderDeadlineDefault`,
  `CashSaleIT.deliveryAndFulfilment`, `CashSaleIT.voidSale`.

### Delivery documents (2026-11-14, owner request)

- The delivery details of branch cash sales, sales orders and invoices gain a **Delivery documents** section: the
  printable delivery note(s) — preview, print, PDF (orders list one note per dispatched delivery) — and an upload area
  for delivery documents (signed delivery note, proof of delivery, waybill, photos).
- Uploads use their own DMS record types (built-ins, "sales" workspace), kept apart from the record's other files:
  `cash_sale_delivery` (SalesSubmission; read `sales:cash_sale:read`, write `sales:cash_sale:create`),
  `invoice_delivery` (Document; `invoice:read` / `invoice:update`), `sales_order_delivery` (SalesOrder;
  `sales:order:read` / `sales:delivery:write`). Per-trip files stay on `sales_delivery`.
- `SalesDeliveryNotePdf` prints a letterhead (`SalesLetterhead`): the company logo (Developer settings) and the
  receipt-header business name, address lines, phone and TIN, falling back to the organization name.
- Tests: `SalesHandoverIT.deliveryDocumentRecordTypes`, `CashSaleIT.deliveryAndFulfilment`.

### Weighbridge tickets on Sales deliveries (Phase A1, 2026-11-14, no migration)

Read-only visibility of the Fleet weighbridge. Fleet stays the only place that weighs, voids and reissues tickets;
Sales stores no ticket data. Plan: Phase A (A1 → A4), owner-approved 2026-11-14.

- `GET /sales/deliveries/{id}` adds:
  - `weighbridgeTickets`: every ticket of the truck, oldest first, including the void / reissue chain. Each ticket has
    the number, status, plate, trailer, driver, both weighings (kg, time, source, operator), gross / tare / net,
    `isManual`, `usedStoredTare`, tolerance and variance, the latest exception status, `voidReason`, `replacedById`,
    `reissuedFromId`, the trip-feed status, `feedsTrip`, and a derived `verification`.
  - `weighbridge {required, feedingTicketId}`.
- `verification` is derived; there is no column. Values: `verified` (completed within tolerance),
  `approved_exception` (completed after an approved tolerance exception), `pending_exception`, `in_progress`,
  `cancelled`, `voided`.
- `GET /sales/deliveries` (and the detail) add `weighTicketId`, `weighTicketNumber`, `weighNetKg`,
  `weighTicketStatus`, `weighWithinTolerance` and `weighVerification`, all taken from the ticket that fed the truck.
- New route `GET /sales/deliveries/{id}/weigh-tickets/{ticketId}/pdf` (`sales:delivery:read`):
  - returns the ticket PDF stamped "COPY (view)";
  - does not count as a print (`printCount` unchanged), and is audited as `reprint` with `copy: "view"`;
  - returns 404 for a ticket of another delivery, 400 unless the ticket is completed or void.

  No `weighbridge:*` permission is needed. The Fleet print route still needs `weighbridge:weigh`.
- The delivery note shows "Weighbridge ticket: WBT-… / Gross · Tare · Net kg" (flagged when manual) instead of the
  raw source id.
- Web:
  - a Weighbridge tab and a summary line in the delivery dialog;
  - a ticket column and section on `/sales/deliveries`;
  - the link to the weighing screen shows only with `weighbridge:read`.
- No behaviour change: weighing, dispatch, stock issue and billing are unchanged. The weighed basis still bills at
  dispatch; A4 moves invoiceability to after POD (D1).
- Fix included: registering a ticket from a Sales delivery failed with "Sales order not found". The trip pre-fill put
  the `SalesOrder` id into `salesOrderId`, but the check looked in the POS `"Order"` table. The check now accepts a
  `SalesOrder` or, as before, a POS `Order`.
- Tests: `SalesWeighbridgeIT` and `FleetDeliveryNoteWeighLineTest`.

### Customer weight evidence on the proof of delivery (Phase A2, 2026-11-16, V496)

The customer's own weighing of a delivered truck is **reconciliation evidence only**. It never changes the quarry
ticket, stock, the delivered quantity or any document. It can raise **weight claims** (discrepancy kind `weight`)
that go through the approval engine. Plan: Phase A §7 / §11.

- Routes (`sales:delivery:confirm`):
  - `POST /sales/deliveries/{id}/customer-weight`, Idempotency-Key honoured. Body `{grossKg?, tareKg?, netKg?,
    ticketRef?, weighedAt?, notes?, allocations?: [{loadLineId, quantity, kg?}]}`. Only after the proof of delivery
    (400 before). Returns the delivery plus `customerWeightResult` (outcome, Q, C, V, allowed variance, proposed /
    applied split per line).
  - `POST /sales/deliveries/{id}/customer-weight/evidence` `{dataUrl}`: an image or PDF of the customer's ticket.
  - `POST /sales/deliveries/{id}/confirm` accepts the same object as `customerWeight`. It is recorded after the
    damaged / rejected quantities, so the ceiling already excludes them.
  - Fleet mirror: `POST /fleet/trips/{id}/customer-weight` (`fleet_trip:deliver`, Idempotency-Key required) and
    multipart `POST /fleet/trips/{id}/customer-weight-evidence`.
- `GET /sales/deliveries/{id}` adds `customerWeight` (null before any evidence): the evidence, the frozen comparison,
  `evidenceUrls` and `claims` (`code, loadLineId, quantity, claimKg, status, decidedAt, decisionNotes, withdrawnAt`).
- Comparison, frozen on `FleetProofOfDelivery` when recorded:
  - Q = the net of the ticket that fed the trip (`quarryWeightSource = ticket`), else the trip's manual gross − tare
    (`manual`); no Q → `no_comparison`.
  - C = customer gross − tare (gross must exceed tare; a net that disagrees is refused), or the net alone.
  - V = Q − C. T (allowed variance) = max(Q × pct, kg) of the ticket's station, overridden by the product's own
    weighbridge setting on a single-product truck. On the manual path there is no station: the product's setting on a
    single-product truck, else 0.
- **Tolerance policy:** the tolerance is an **investigation trigger, not a deduction allowance.**
  - |V| ≤ T → `within_tolerance`: no claim.
  - Customer heavier beyond T → `heavier`: flagged only; never an excess claim, never stock.
  - Customer lighter beyond T → `disputed`: a pending claim for the **full** V (not V − T).
- Split of a disputed V over the lines (`WeightAllocation`, pure):
  - E (eligible) = max(0, min(accepted, dispatched − returned − damaged)); cap = E − the line's other pending or
    confirmed weight claims.
  - Shares are proportional to E × kg per unit, capped, with the overflow redistributed over the other lines. They are
    floored to 0.001 of the line unit (load-line columns are NUMERIC(14,3); the plan said 0.000001) and the remainder
    goes by largest remainder, ties by line number.
  - What fits no cap, or sits on a unit without a weight conversion, is `unallocatedVarianceKg`.
  - One weighable line → applied automatically. Several lines, or a line without a weight factor → `awaiting_split`
    with a proposal and **no claim**, until the user sends `allocations`. Allocations are refused when a line exceeds
    its cap, when the total kg exceeds V, or when a line without a weight factor has no `kg`.
- Claims and resubmission:
  - A new submission makes the pending claims match the applied split: it raises, updates (same claim, audited) or
    withdraws. A withdrawn claim is closed as rejected with `withdrawnAt` set and its approval request cancelled; it
    is not a decision and does not block later corrections.
  - Once any weight claim is confirmed or dismissed: 409 "Weight claim DSC-… is already confirmed/dismissed; the
    customer weight can no longer change it".
  - Weight claims never auto-approve, even when the discrepancy workflow is switched off.
  - Deciding a claim uses the discrepancy flow. Since A3 (below) the billable quantity is the accepted bucket:
    confirming a weight claim keeps it out, dismissing returns it. Invoiceability is A4.
- Delivery note (PDF and on-screen preview): "Customer weight N kg (ticket …) · quarry Q kg · variance V kg —
  outcome" under the receipt line.
- Web: an optional "The customer weighed the truck" section in Confirm delivery, and a customer-weight panel on the
  proof-of-delivery tab (comparison, outcome, claims, evidence). The panel's dialog records or corrects the weight
  and confirms a split.
- Migration `V496__pod_customer_weight.sql`: nullable POD columns plus CHECKs, discrepancy kind `weight`, `claimKg`,
  `withdrawnAt`. V490–V495 are taken on other branches, so renumber at merge if needed. The entities caught up on 24
  `FleetLoad` and 2 `FleetLoadLine` columns that were missing from the JPA model (G10).
- Tests: `SalesCustomerWeightIT` (within tolerance on confirm; full-variance claim; update, withdraw and re-raise;
  decided → 409; heavier via the Fleet route; cap and unallocated; multi-product awaiting split, refusals and
  confirmed split; 403; before POD → 400; ticket, stock and delivered quantity unchanged) and `WeightAllocationTest`.

### Quantity buckets, POD correction and line history (Phase A3, 2026-11-16, V497)

Every dispatched quantity of a delivery line sits in exactly one bucket, so that we know what left the quarry, what
the customer accepted and what is still argued about. Plan: Phase A §2, §3, §8, §9, §10. Fleet owns the facts and the
calculation (`LoadLineQuantities`, pure; `LoadLineQuantityService`); Sales only reads them.

- **Invariant** (per line, once the trip is delivered): dispatched D = accepted + returned R + damaged G + confirmed
  shortage + pending dispute; excess X is reported beside it. With Δ = delivered at the POD:
  - eligible E = max(0, min(Δ, D − R − G)); X = max(0, Δ − (D − R − G)); POD shortage S = max(0, D − R − G − Δ).
  - Weight claims sit inside E (pending Wp, confirmed Wc; confirmed first, clamped at E).
  - Shortage claims sit in S: confirmed (capped at S) → confirmed shortage; the rest of S stays **pending dispute**,
    including a *dismissed* shortage claim. A dismissal says "we do not accept the shortfall", not "it arrived", so
    only a POD correction moves it into accepted.
  - accepted = E − Wp − Wc. `billableQty` is kept equal to accepted, written in the same transaction as every change.
- **Claim model.** Claims raised before A3 keep `resolutionModel = 1` and their old meaning (a rejected shortage was
  billed), so historic lines are not reclassified. New claims are model 2. Claim states for the API, via
  `claimDecision`: pending, confirmed, dismissed, reconciled, withdrawn. A bare "rejected" is never shown.
- **Damaged** is its own fact. The POD records it (`damagedQty` on the Fleet POD line; Sales `confirm` now passes it
  through instead of folding it into the shortage) and raises a `damaged` claim. R + G may not exceed D: 400
  "Returned and damaged quantities exceed what was dispatched on trip …".
- **POD correction** `POST /sales/deliveries/{id}/pod-correction` (new permission `sales:delivery:pod_correct`,
  granted by V497 to Administrator and to roles holding `fleet_dispatch:approve_discrepancy`; Idempotency-Key
  honoured):
  - Body `{lines: [{loadLineId, deliveredQty}], reason, evidence: [dataUrl image / PDF]}`. A reason and at least one
    evidence file are required.
  - The person who recorded the POD cannot correct it (403).
  - It can only raise Δ (never above D − R − G), and only by as much as the line's open shortage claims. Those claims
    are reconciled oldest first (`reconciledQty`; fully covered → status `reconciled`, approval request withdrawn).
  - It updates the delivered quantity, the delivery-order line, the order fulfilment and the line history.
- **Line history** `GET /sales/deliveries/{id}/lines/{lineId}/history` (`sales:delivery:read`): Fleet's immutable
  `FleetLoadLineQuantityEvent` rows (dispatched, POD, returned, damaged, excess, POD corrected, customer weight, claim
  opened / changed / confirmed / dismissed / reconciled / withdrawn), with who, when, reason and evidence. Lines from
  before A3 get a reconstruction marked `reconstructed: true`. A4 adds invoice allocations.
- **Reads.** `GET /sales/deliveries` and `/{id}` add `acceptedQty, pendingDisputedQty, confirmedShortageQty,
  excessQty`. The detail also has these per line, plus `claims` (all kinds, with `claimDecision` and `openQty`).
  `GET /sales/orders/{id}/delivery-summary` adds `accepted, pendingDisputed, confirmedShortage, excess` per line.
- **Fleet routes:**
  - `POST /fleet/delivery-discrepancies/{id}/reduce` lowers a pending weight claim, reason required; 0 withdraws it.
  - `GET /fleet/reconciliation/findings` and `POST /fleet/reconciliation/run` (`fleet:report`).
- **Operational reconciliation** (`FleetQuantityReconciliation`; nightly 03:40, ShedLock `fleet-quantity-reconcile`)
  checks every delivered line for:
  - the invariant, negative buckets and the weight ceiling;
  - shortage, damaged and excess not covered by a claim;
  - a missing stock issue or return receipt;
  - history totals that differ from the line.

  It repairs a drifted `billableQty` (a projection) and records each problem as an open `FleetReconciliationFinding`.
  A run that no longer detects a finding resolves it. Tickets, POD rows, stock, journals and invoices are never
  changed.
- **Lock order** (plan §8): sales order → delivery order → trip → claims → order lines. Sales issue / confirm /
  cancel / fail / return and the POD correction now take the sales-order row lock first.
- **M-6 (Phase I2) closed here.** Dispatch issues the base quantity of a non-base-unit line cumulatively per
  delivery-order line: round(issued before + this load) − round(issued before). Two loads of 10.37 t of 50-kg bags
  issue 415 bags, not 2 × 207.
- Late-dispute credits: confirming a shortage or weight claim on a Fleet-owned delivery order keeps the existing
  draft credit note. Sales-order deliveries get theirs from the A4 invoicing owner.
- Web: the proof-of-delivery tab shows a Quantities panel (buckets per line, claims with their decision, a history
  dialog per line) and a "Correct proof of delivery" dialog (lines, reason, evidence files).
- Migration `V497__delivery_quantity_buckets.sql`:
  - claim kind `damaged` and status `reconciled`;
  - claim columns `reconciledQty`, `resolutionModel` (existing rows 1, new rows 2; no UPDATE), `dismissReason`,
    `evidenceFileIds`;
  - tables `FleetLoadLineQuantityEvent` (immutable trigger) and `FleetReconciliationFinding`, with the V214
    tenant-or-no-tenant RLS policy;
  - the permission row.
- Tests:
  - `LoadLineQuantitiesTest`: the reviewer's example; weight claims refused above the ceiling (30 / 30 / 5 / 5);
    partial reconciliation; dismissed shortage stays pending; legacy claims; 5 000 random cases for the invariant
    and non-negativity.
  - `SalesQuantityBucketsIT`: 30 = 25 + 2 + 1 + 1 + 1 through the API and the summary; reduce a weight claim; dismissed
    → partial correction → full correction → reconciled, with 403 / SoD / evidence / ceiling refusals and the history
    kinds; partial correction then confirm; reconciliation repair and resolve.

### Invoiceability, invoice ownership and legacy Fleet invoices (Phase A4, 2026-11-16, V500)

Plan: Phase A §5, §6, §10, §13 and decisions A-1, A-2, A-7. No GL posting rule and no accounting policy changes. A4 is
built on its branch and **waits for the owner's financial review** (below) before it merges.

*Owner review 2026-11-16 — returned for two corrections, both made:* (1) ordered-quantity billing is preserved for the
`ordered` billing basis and the `ordered` invoice policy; (2) the legacy Fleet invoice review is approved through the
kernel approval engine, not a direct check. A4 now has **no deviations**.

**Financial / architectural review (the plan required one before code)**

- *Double invoicing.* Before A4, the goods of a Sales-module order could be invoiced twice: by Sales
  (`SalesInvoiceLink`) and by the order's Fleet delivery order (`FleetDeliveryInvoiceService`, when `goodsInvoiceId`
  was still null, e.g. Fleet invoiced first). Fix: one owner per delivery-order source.
  - A Sales-module order → Sales invoices the goods; its delivery order invoices transport charges only.
  - Any other source → Fleet invoices the goods, through the new immutable ledger `FleetDeliveryInvoiceLine`.
  - **Clarification of the plan:** source type `sales_order` also covers back-office orders of the orders module
    (`/orders`). Those keep the Nest behaviour (Fleet invoices their goods, now through the ledger).
    `DeliveryInvoicing.salesOwned` tells the two apart through `DeliverySourcePort`.
- *Invoiceable quantity.* Invoiceable = billable − live allocations on both paths. Billable by billing basis:

  | Billing basis / invoice policy | Billable (Sales) | Change in A4 |
  |---|---|---|
  | `accepted` basis | A3 accepted bucket: dispatched − returned − damaged − confirmed shortage, **pending disputes excluded** | none |
  | `weighed` basis | the same accepted, undisputed quantity — only after the POD (A-2: the ticket proves what left the quarry) | was ticket net at dispatch |
  | `ordered` basis | dispatched, capped at ordered − cancelled (ordered-quantity billing, owner decision 2026-11-16) | none |
  | `ordered` invoice policy | ordered − cancelled, before delivery (commercial choice) | none |

  - A draft reserves quantity; a cancelled or rejected document releases it; credit notes never restore it.
  - Fleet-owned delivery orders invoice accepted − live ledger allocations (Fleet's accepted excludes pending disputes).
- *Guards.*
  - Service layer: `fleet/application/DeliveryInvoicing` is the only allocator. `allocateFleetGoods` locks the delivery
    order and rechecks the quantity; `assertSalesGoods` locks the Sales order's delivery orders (lock order: sales
    order → delivery orders) and refuses when Fleet holds goods on them.
  - Post time: an `InvoicePostGuard` SPI in invoicing, implemented by Fleet and Sales, runs for drafts issued by a
    delivery. A draft that now bills more than is invoiceable gets 409 "… can be invoiced; cancel this draft and
    invoice the order again".
  - `InvoiceLedgerWritersTest` (ArchUnit plus a source scan) limits who may call the allocator and who may insert into
    `FleetDeliveryInvoiceLine` / `SalesInvoiceLink`.
- *Creation paths audited* (plan §5 table):
  - Sales regular and batch invoicing: guarded; batch skips blocked orders.
  - Down payments: no goods.
  - `FleetDeliveryInvoiceService`: guarded.
  - Cash sales, opening balances, contracts: not delivery-sourced; unchanged.
  - Manual `/invoices`: cannot link to a sales order.
  - `SalesCollectionsService`: write-offs and interest only; no goods.
- *Late customer evidence* (§13): a shortage or weight claim confirmed after invoicing produces a **draft** credit note
  for min(confirmed, gross invoiced − credited − invoiceable). An `ordered`-policy line invoices its ordered quantity, so
  a delivery claim never auto-credits it (returns and credit notes stay the route there).
  - Sales owner: against the latest posted invoice of the line, linked as `credit_note`. The line carries no product,
    so posting never moves stock, and the product category's income account is used, as on the existing Fleet path.
  - Fleet owner: the existing Fleet credit-note path, capped by the ledger.
  - A replayed event credits nothing more, because the live credit link already counts.

**Legacy Fleet goods invoices on Sales orders (§6, A-7)**

- *Detection is inline.* A live goods invoice on an order's delivery order is a historical Fleet invoice when it has
  no `origin = sales` link to the order and no reconciled review. Such an order gets 409 "Sales order {n} has
  historical Fleet invoices awaiting reconciliation" on regular invoicing. `GET /sales/orders/{id}/billing` shows
  `blocked` and `legacyFleetInvoices`.
- *Routes* (all `sales:billing:override`):
  - `GET /sales/legacy-invoice-reviews`: the exception queue, and also the pre-flight report.
  - `GET /sales/orders/{id}/legacy-invoices`: proposed matches (Fleet invoice line → order line, by product through
    the delivery-order lines).
  - `POST /sales/orders/{id}/legacy-invoices/{documentId}/review`: `{matches: [{documentLineId, orderLineId,
    quantity}], notes}`. Each invoice line is matched once. A match above the order line's ordered quantity is refused
    with "record an exception instead". The review becomes `submitted` and raises a **kernel approval-engine request**
    (entity type `sales_legacy_invoice_review`; the default workflow "Legacy Fleet invoice review", step "Sales manager"
    with `sales:billing:override` and distinct approvers, is created when the organisation has none). Re-submitting
    withdraws the pending request and raises a new one. A switched-off workflow refuses the submission with 409 "The
    approval workflow for legacy Fleet invoice reviews is switched off; …" — nothing is approved without a request.
  - `POST /sales/legacy-invoice-reviews/{id}/approve` and `/reject` (Idempotency-Key honoured) decide that request
    through the engine: permission, approval limits, distinct approvers and the self-approval refusal are the engine's
    (the reviewer, as requester, gets 400 "You cannot approve a request you created"). An approved request writes one
    `legacy_fleet` regular link per order line, carrying `legacyReviewId`, in the same transaction, recomputes the
    invoiced quantities and unblocks the order; a rejected one returns the review to `open`. A decision taken in the
    generic approvals inbox (`POST /approvals/{id}/decide`) is applied by `SalesLegacyInvoiceApprovalSubscriber`
    (`approval.decided`, idempotent).
  - `POST /sales/legacy-invoice-reviews/{id}/exception` `{notes}` withdraws a pending request, keeps the order blocked
    and logs a warning; `/reopen` reopens it once the cause is resolved.
- *Pre-flight query* (run read-only before deploying A4):

  ```sql
  SELECT o.number, f.code, d."documentNumber", d.status, dl.description, dl.quantity
    FROM "FleetDeliveryOrder" f JOIN "SalesOrder" o ON o.id = f."sourceId"
    JOIN "Document" d ON d.id = f."goodsInvoiceId" JOIN "DocumentLine" dl ON dl."documentId" = d.id
   WHERE f."sourceType" = 'sales_order' AND d.status::text NOT IN ('cancelled', 'rejected')
     AND NOT EXISTS (SELECT 1 FROM "SalesInvoiceLink" k WHERE k."orderId" = o.id AND k."documentId" = d.id)
   ORDER BY o.number, f.code, dl."lineNumber";
  ```

**Financial reconciliation (§10).** Neither run changes invoices, journals or stock.

- Sales (`POST /sales/billing/reconcile`, `sales:billing:override`, and nightly with the fulfilment run) records a
  `SalesReconciliation` row with kind `financial`. It checks:
  - over-invoiced lines: gross invoiced − credited above billable;
  - credit above gross invoiced;
  - duplicate regular links;
  - unreconciled legacy invoices.
- Fleet (`POST /fleet/reconciliation/run`) adds scope `financial` findings:
  - `over_invoiced`: ledger allocations above accepted;
  - `fleet_goods_on_sales_order`.

**Migration `V500__delivery_invoiceability.sql`:**

- `FleetDeliveryInvoiceLine`: immutable, with unique keys on the document line and on (document, delivery-order line).
- `SalesLegacyInvoiceReview` (status open | submitted | reconciled | exception, `approvalRequestId`).
- `SalesInvoiceLink` gains `documentLineId`, `origin` and `legacyReviewId`.
- The partial unique index (documentId, orderLineId) WHERE kind = 'regular' is created only when no duplicates exist.
  Otherwise a warning is raised and reconciliation reports them.
- `SalesReconciliation.kind`.

**Web:** the order's billing panel shows the block and hides "Create invoice" while blocked.

**Tests:**

- `SalesInvoiceabilityIT` (9):
  - accepted only, with Fleet charges only on a Sales delivery order;
  - weighed basis invoiceable only after the POD;
  - ordered basis keeps ordered-quantity billing (dispatched, before and after a disputed POD);
  - the post guard refuses until the claim is dismissed;
  - late credit after posting, with the over-invoiced finding;
  - legacy block → review → engine request → requester refused (400) → rejected → re-submitted → approved →
    unblocked;
  - legacy review approved from the generic approvals inbox (subscriber);
  - a switched-off workflow refuses the submission;
  - an exception withdraws the request and stays blocked; the routes need the permission.
- `InvoiceLedgerWritersTest`.
- `SalesWeighbridgeIT` now expects billable 0 before the POD.

