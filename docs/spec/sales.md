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
- Expiry job (ShedLock, daily) moves past-`validUntil` quotes to `expired`.
- Submit checks: discount bands, margin floor, amount bands (S2), credit (S3) → approval when a rule triggers.
- Convert → `SalesOrder` (or proforma through `DocumentBuilderService`).

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
None yet (no parity routes involved).
