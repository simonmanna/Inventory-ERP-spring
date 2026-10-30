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
| S1 | Quotations API in sales + Sales Orders core + amendments + R1 reports + web | R1 | todo | S0 |
| S2 | Pricelists, pricing policy, margin floors, commercial approvals | R1 | todo | S1 |
| S3 | Credit module (limits, exposure, decisions, holds) | R2 | todo | S1 |
| S4 | ATP, reservations, backorders, supply pegs | R2 | todo | S1 |
| S5 | Fulfilment orchestration (fleet DO, weighbridge, collect, quantity read model, policies) | R2 | todo | S4, fleet 1.4b |
| S6 | Billing, deposits, down payments, payment gateway SPI + mobile money | R2 | todo | S5, accountant answers |
| S7 | Returns / RMA, claims, quality certificates | R2 | todo | S6 |
| S8 | Collections: dunning, promise-to-pay, statement runs | R3 | todo | S3, S6 |
| S9 | Agreements, rebates, commissions, targets | R3 | todo | S2, S6 |
| S10 | Analytics, dashboard, portal, AI assist | R3 | todo | all |

Gates for every phase (from ADR-S011 / plan): `./gradlew build` green (Testcontainers) incl. `ModularityTests` and
`ArchitectureTests`; permission (403) and tenant tests for new routes; idempotency on money routes; reconciliation
(`SalesLedgerAssertions`) where GL is touched; backward-compatibility checklist (§ "Compatibility"); docs updated
(`sales.md`, `BUILD_LOG.md`, `VERIFY_CHECKLIST.md`); unbuilt integrations hidden from users.

---

## S0 — Foundation

**Goal.** Create the `sales` module and move quotations out of CRM without changing any existing route contract.

**Deliverables**
1. Migration `V24__sales_foundation.sql`
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
- `SalesAgreement` + releases, `RebateProgram` accruals and settlement, `CommissionRule` / statements → payroll,
  `SalesTarget`. Licence key `salesAgreements`.

## S10 — Analytics, portal, AI
- Dashboard, analysis views, margin and customer profitability, forecast; customer portal (licence
  `customerPortal`); AI advisories (read-only).

## Compatibility checklist (run every phase)
- `CrmSuiteIT`, `CrmIT` green; `/crm/quotations*`, `/crm/pricing*` JSON unchanged.
- `OrdersIT` / POS ITs green; `/orders` unchanged.
- Fleet `FleetDispatchIT` green (legacy `from-sales-order`).
- No change under `manufacturing/**`, `pos/**` billing path, `android/`.

## Known deviations from the approved plan (owner to confirm)
- S0: sales teams stay in CRM (`CrmTeam`) — pipeline code depends on them heavily; sales stores `teamId` pointing to
  `CrmTeam`. Revisit in S9 (targets).
- S0: rollback is a runbook (copy rows back + drop trigger + revert release) instead of a runtime source flag; a
  dual code path would keep two quotation engines alive, which ADR-S011 §4 forbids.
