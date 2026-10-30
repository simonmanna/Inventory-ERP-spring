# ADR-S011: Sales / Order-to-Cash module — quotations, sales orders, pricing, credit, fulfilment orchestration

- Status: Proposed (2026-10-30) — owner approval pending
- Spec: [`docs/spec/sales.md`](../spec/sales.md), postings [`docs/spec/sales-postings.md`](../spec/sales-postings.md),
  credit [`docs/spec/credit.md`](../spec/credit.md)
- Product context: [`docs/PRODUCT_ARCHITECTURE.md`](../PRODUCT_ARCHITECTURE.md) (Stage 2 — Sales O2C)
- Requirements: `docs/requirements/construction-materials-erp.md` §2.4, §2.6; `docs/AUTONOMOUS_BUILD_CONSTRUCTION.md`
  Phase 4 "Sales"; `docs/ROADMAP.md` 1.5, 1.6
- Supersedes in part: ADR-S010 §5 (quotations) and §6 (customer pricing) — both move from `crm` to `sales`

## Context

The B2B sales cycle today is spread over documents built for other purposes:

| Need | Today | Problem |
|---|---|---|
| Quotation | `CrmQuotation` (ADR-S010, `crm`, licensed) | quoting requires the CRM licence; converts into a weak document |
| Sales order | POS `Order` / `OrderItem` through `orders/OrdersService` | restaurant-shaped (table, waiter, kitchen); no unit snapshot, site, approval, delivered / invoiced quantities; billing path is frozen POS code |
| Pricing | `CrmPriceRule` | no named pricelists, no unit / site / branch scope; not used by orders or invoices |
| Credit | `Partner.creditLimit` / `creditHold`, enforced only in POS (`CreditStatus`) and CRM quote submit | no exposure that includes undelivered orders or deposits |
| Fulfilment | fleet `FleetDeliveryOrder` (ADR-S009) reads the POS `Order` | delivered / weighed / accepted quantities never flow back to the order |
| Advances | `Payment.unallocatedAmount` | no deposit liability, no down-payment invoice |

The construction-materials client sells bulk aggregates, sand and blocks to contractors on credit, in many truck
trips per order, measured on a weighbridge, sometimes against advances and long-running contracts. That needs an
enterprise order-to-cash design (Odoo user experience, SAP / D365 controls), built as a generic module with industry
extensions.

## Decision

1. **New module `com.inventoryerp.sales`** owns *commercial commitments*: quotations, sales orders, amendments,
   pricelists, agreements, sales teams, targets, commissions, rebates, returns authorisations and dunning. POS `Order`
   stays for counter / walk-in / restaurant sales and is not changed.
2. **Quotations move from `crm` to `sales`.** Rows are copied with their ids; `/crm/quotations*` and `/crm/pricing*`
   keep their contract and delegate to `sales`. CRM keeps leads, opportunities and activities and links an
   opportunity to quotations. Quoting does not require the CRM licence. CRM tables stay (write-blocked) for one
   release, then a later migration drops them after owner verification. Rollback = re-enable CRM writes and flip
   `sales.quotations.source=crm`.
3. **Credit management is its own module `com.inventoryerp.credit`** (limits, exposure, decisions, holds). Sales, POS,
   invoicing and fleet contribute exposure components through a `CreditExposureContributor` SPI implemented in their
   own modules; `credit` depends on none of them.
4. **Domain ownership** (one authoritative owner per fact):

   | Owner | Facts |
   |---|---|
   | sales | ordered / cancelled quantity, prices, commitments, amendments, billable decisions |
   | inventory | stock, reservations, movements, valuation |
   | fleet | delivery orders, trips, loaded / received / accepted quantity, POD, discrepancies |
   | fleet (weighbridge) | weighed quantities |
   | invoicing | invoices, credit notes, payments, allocations, deposits, receivables |
   | accounting | general ledger |
   | credit | limits, exposure snapshots, credit decisions |
   | crm | leads, opportunities, activities |

   Sales orchestrates; it never writes stock, GL, invoices or delivery records directly — it calls the owning
   module's application API or reacts to its events. No second delivery, payment or pricing-history engine.
5. **Quantities are never collapsed.** Ordered, cancelled, reserved, loaded, weighed, received, accepted, billable,
   gross invoiced, credited, net invoiced and returned are distinct. Sales keeps a rebuildable read model
   (`SalesOrderLineQty`) fed by events with an inbox (idempotent, version-checked, dead-lettered, reconciled nightly);
   the evidence stays in the owning modules plus immutable `SalesFulfilmentDecision` records for billable quantity.
6. **Five independent policies** — physical movement, ownership transfer, COGS recognition, revenue recognition,
   billing — effective-dated per organization / branch, overridable per order with permission. No single "stock
   issue timing" switch.
7. **Confirmed sales orders are never edited in place.** Changes go through `SalesOrderChangeRequest` → impact
   preview → approval (when rules trigger) → immutable `SalesOrderRevision`.
8. **Deposits are a liability** (`customer_deposit`) applied through explicit `DepositApplication` records;
   down-payment invoices are separate tax documents. Accountant sign-off before billing (S6) is built.
9. **One delivery engine.** Fleet `FleetDeliveryOrder` is the only delivery document. Its core (delivery order,
   collect / direct issue, delivery note, POD) becomes available without the `fleet` licence; trips, dispatch board
   and weighbridge stay licensed.
10. **Company model: Organization = company (legal entity).** Existing tenancy gives per-organization sequences,
    chart of accounts and partners. No `companyId` column. `branchId` is mandatory on quotations and sales orders.
    Credit limits are per organization × optional branch × currency. Intercompany = cross-organization documents,
    designed in `PRODUCT_ARCHITECTURE.md` before any build; one organization never fulfils from another's stock
    without an intercompany document.
11. **Manufacturing is integrated later through ports owned by sales** (`SupplyProvider`, `MakeToOrderPort`). Until
    an adapter exists the capability is not shown to users; ATP separates "available now" from future supply and
    never derives a promise date from production.
12. **Licensing.** Sales core is unlicensed base functionality (like invoicing). New keys `salesAgreements`
    (agreements, rebates) and `customerPortal` are added to `kernel/licensing/LicenseFeatures` and the licence
    server's `Entitlements.KNOWN_FEATURES` in the phase that introduces them.
13. **Delivery in phases with owner gates** (see spec): Step 0 docs → S0 foundation → S1 quotations + orders → S2
    pricing → S3 credit → S4 ATP / reservations → S5 fulfilment → S6 billing → S7 returns → S8 collections → S9
    agreements / rebates / commissions → S10 analytics / portal / AI. Each phase starts only on explicit owner
    go-ahead after inspecting the previous one.

## Consequences

- Parity routes `/orders`, `/crm/quotations*`, `/crm/pricing*`, `/invoices*` keep path, verb and JSON.
  `/orders` remains the POS / back-office path; the web app stops offering it for B2B once `/sales/orders` exists.
- `FleetDeliveryOrderService.fromSalesOrder` reads the new `SalesOrder` through a sales query API; the legacy
  POS-order path stays for existing rows.
- New Flyway migrations (V9xx placeholders, renumbered at merge). No edit to V1 / V2.
- New permissions `sales:*` and `credit:*` in `packages/shared/src/permissions.ts`; new events `sales.*`, `credit.*`
  in `packages/shared/src/events.ts` with a payload version field.
- Account mapping keys added (no hardcoded account numbers): `customer_deposit`, `goods_in_transit_customer`,
  `unbilled_receivable`, `freight_income`, `freight_expense`, `sales_returns`, `sales_rebates`, `rebate_accrual`,
  `inventory_quarantine`, `stock_writeoff`. Existing fleet use of account 4250 moves to `freight_income`.
- Risk: more modules and events → mitigated by Modulith / ArchUnit boundary tests, inbox pattern and nightly
  reconciliation.

## Alternatives rejected

- **Extend POS `Order` / `OrderItem`.** Mixes restaurant and B2B fields, touches the frozen POS billing path and the
  Android sync contract.
- **Keep quotations in CRM.** Forces a CRM licence to quote; CRM would own a commercial document.
- **Credit service in `core` or `sales`.** `core` should stay generic; inside `sales`, POS and collections would
  depend on sales.
- **Single stock-issue timing setting.** Conflates physical movement, ownership, COGS and revenue recognition.
