# Roadmap — from parity port to world-class ERP

Status: **approved for autonomous execution** (2026-10-28). Execution rules, decision authority and the
resume protocol are in [`AUTONOMOUS_BUILD.md`](AUTONOMOUS_BUILD.md); live progress is in `BUILD_LOG.md`.
Each item that changes the wire contract or adds a framework still gets its own ADR in `docs/adr/`.

## How to read this

- **Tiers** are ordered by business value: Tier 0 finishes what exists, Tier 1 makes the product sellable to a
  general business, Tier 2 reaches Odoo Enterprise / Dynamics 365 Business Central parity, Tier 3 targets
  SAP S/4HANA / D365 Finance & Operations territory.
- **Effort** is in engineer-weeks (EW) for one experienced engineer working with AI agents, including tests,
  parity/authorization tests, web screens and docs. Sizes: **S** ≤ 2 EW · **M** 3–6 EW · **L** 7–12 EW ·
  **XL** > 12 EW. Treat them as ±50 % planning estimates, not commitments.
- **Priority** within a tier: P1 (do first) → P3.
- Everything beyond Nest parity is an *extension* (like ADR-S005) and must not alter existing routes.

## Baseline (where we are)

| Area | State |
|---|---|
| Routes ported | 965 / 1,118 ticked (+20 ADR-S005 extensions); rental + repair code merged but unverified |
| Strong | GL + posting engine, periods, multi-currency + FX revaluation, bank reconciliation, cash sessions, AVCO/FIFO/standard costing, batches/serials/expiry, reservations/ATP, transfers, counts, procurement with GRNI + landed cost + 3-way match, POS (restaurant + offline Android), HR/payroll, fixed assets, approvals, audit, multi-tenant, licensing |
| Basic | Tax (codes + summary report), sales cycle, analytics (fixed reports), cost dimensions (cost center + branch only) |
| Absent | Localization, e-invoicing, bank feeds, WMS, MRP, intercompany, subscriptions, customization platform, BI, portals, AI |

## Summary

| Tier | Goal | Items | Effort |
|---|---|---|---|
| 0 | Finish the parity port | 4 | ~16 EW |
| 1 | Sellable to a general SMB | 8 | ~47 EW (one e-invoicing standard) |
| 2 | Odoo Enterprise / BC parity | 10 (+2 small) | ~85 EW |
| 3 | SAP / D365 F&O class | 7 | ~70 EW + ongoing ecosystem |
| 4 | Vehicle / fleet management module | 1 module | ~10 EW |

Rough total: **~227 EW** (≈ 4 engineer-years). With a team of 4: Tier 0–1 ≈ 3.5 months, Tier 2 ≈ +5 months.

---

## Tier 0 — Finish the port (prerequisite)

Follow `AGENTS.md` §6; parity rules apply in full.

| # | Item | Pri | Effort | Status (2026-10-28) | Notes |
|---|---|---|---|---|---|
| 0.1 | `manufacturing` (42 routes) | P1 | M (5 EW) | **In progress** — 42/42 ticked, ADR-S006 package A done; package B (time-phased MRP, `MrpPlanningService`) committed WIP, unverified | BOMs, production orders, work centres, routings, QC; needed before MRP (2.3) |
| 0.2 | `repair` (64 routes) | P2 | M (5 EW) | **WIP merged, unverified** — routes/services/tests written, build not run, routes not ticked | Quotations, jobs, warranties, service contracts |
| 0.3 | `rental` (47 routes) | P2 | M (4 EW) | **WIP merged** — services + controller, tests pending, routes not ticked | Reservations, agreements, deposits, damages |
| 0.4 | `communication` (22 routes) | P3 | S (2 EW) | **Done** — 22/22 ticked, specs + IT + parity scenario | Channels, conversations, templates, WhatsApp; secrets handling per `SECURITY.md` |

**Exit:** `PORT_STATUS.md` 1,118 / 1,118, parity harness green, Nest retired.

## Tier 1 — Sellable to a general business

| # | Item | Pri | Effort | Scope |
|---|---|---|---|---|
| 1.1 | Localization framework | P1 | L (10 EW) | Per-country package: CoA template, tax codes, fiscal positions, report layouts. Plugin-style `localization/<cc>` module; ship 2–3 target countries first |
| 1.2 | Tax returns | P1 | M (5 EW) | Configurable tax-report lines (boxes) mapped to tax tags; VAT/GST return, withholding tax, reverse charge, cash-basis VAT |
| 1.3 | E-invoicing | P1 | L (8 EW per standard family) | Pluggable provider SPI; start with target market (ZATCA, Peppol BIS 3, GST IRN, CFDI or SDI). Signing keys per `SECURITY.md` |
| 1.4 | Bank feeds & payment runs | P1 | M (6 EW) | CAMT.053 / MT940 / OFX import, auto-match rules, open-banking connector SPI, outgoing payment batches (SEPA pain.001 / local formats) |
| 1.5 | Complete sales cycle | P1 | M (6 EW) | Pricelists + price rules, discount policies, partial delivery / backorders, sales returns (RMA), customer credit limits + hold, drop-ship |
| 1.6 | Dunning & collections | P2 | S (2 EW) | Reminder levels, letters/e-mail, interest/fees, promise-to-pay |
| 1.7 | Year-end close | P2 | S (2 EW) | Retained-earnings rollover, closing entries, period-close checklist with blockers |
| 1.8 | Landed costing & cost centres | P1 | M (7 EW) | Client requirement (Tally-style). Full landed-cost vouchers across receipts/suppliers with quantity/value/weight/volume/manual allocation, late-cost revaluation, and hierarchical cost centres on every transaction line with allocation rules and reports. Detailed scope: §1.8 below |

**Exit:** a non-hospitality SMB in one target country can run quote-to-cash, procure-to-pay, statutory tax

### 1.8 Landed costing & cost centres (client requirement)

Two related but distinct dimensions — build both, integrated:

- **Landed cost (item costing)** answers *"what did this product really cost?"* — customs, freight, transport,
  insurance, clearing, handling are capitalised onto the goods (IAS 2).
- **Cost centres** answer *"which department / branch / project / warehouse incurred the expense?"* — management
  reporting dimension on GL lines.

One document may carry both: a freight bill is allocated to products (inventory valuation) **and** tagged to an
import project cost centre (management reporting).

**Already on `main` (extend, never break):** `procurement` `LandedCostService` / `LandedCostController` — one
landed cost per *posted* goods receipt, methods `value | quantity | equal`, single credit account, layer re-costing
(capitalised on on-hand qty, expensed on already-consumed qty), allocation rows, GL posting, period-lock check.
`accounting` `CostCenter` (code, name, type, active) with `costCenterId` + `branchId` on `JournalEntry` and
`JournalLine`. Existing routes and JSON stay identical; everything below is new routes/fields (ADR first).

Worked example to use as the acceptance test: 100 laptops × UGX 2,000,000 = 200,000,000; customs 30,000,000,
freight 5,000,000, clearing 2,000,000, insurance 1,000,000 → landed 238,000,000 → **UGX 2,380,000 / unit**.
Sale at 2,800,000 → gross profit **420,000** (not 800,000). Mixed shipment: transport 3,000,000 by value with
cement 60 % / steel 40 % → 1,800,000 / 1,200,000; original expense and per-product split both retained.

**Landed cost scope**

| Area | Requirement |
|---|---|
| Landed Cost Voucher | One document linked to one **or more** goods receipts and/or purchase invoices (a shipment); draft → approved → posted → reversed; sequence numbers; attachments (source invoices) |
| Charge lines | Multiple lines per voucher: kind (customs duty, freight, transport, insurance, clearing, loading/handling, other — configurable charge types with default GL account), amount, currency + FX rate, tax code, **capitalisable flag** |
| Multiple suppliers | Each charge line may have its own supplier (goods vendor ≠ freight forwarder ≠ clearing agent ≠ customs authority); posting credits each supplier's AP (or a clearing account) and can generate/link the vendor bill |
| Allocation methods | `quantity`, `value`, `weight`, `volume`, `manual` (user enters amount or % per line; must sum to charge total), plus existing `equal`. Method **per charge line**, not only per voucher. Weight/volume from product master (new `weight`, `volume` + UoM fields) or overridden on the receipt line. Rounding remainder to last line (existing rule) |
| Preview | Allocation preview route + UI before posting: per product, per charge — basis, share %, allocated amount, unit cost before/after |
| Partial shipments | Voucher spans several receipts of one PO/shipment; goods arriving in multiple deliveries; allocation across all selected receipt lines |
| Late costs | Charges arriving after goods receipt (and after partial sale): capitalise on remaining on-hand, expense the consumed share to COGS / variance (existing split, extended to multi-receipt); estimated vs actual accrual with true-up when the real invoice arrives |
| Valuation integration | Respect org valuation method: AVCO re-averages, FIFO bumps the specific layers, standard cost posts to purchase price variance. Batch/serial-level cost where tracked |
| Accounting | Balanced JE through the posting engine + account mappings: Dr Inventory (capitalised) / Dr COGS or variance (consumed) / Dr recoverable input tax (never capitalised) / Cr supplier AP or landed-cost clearing. No double counting: a charge booked as an expense bill and then landed must reclass, not re-post. Reversal = reversing JE + layer restore |
| IAS 2 rules | Only direct acquisition costs capitalise; selling, most admin costs and recoverable taxes do not. Charge-type flag drives this; non-capitalisable lines post to expense with cost centre |
| Audit trail | Keep original charge amounts, basis values, shares, before/after unit costs, approver, timestamps, source documents; immutable once posted |
| Reports | Landed cost by shipment, by item, by supplier, by branch; landed vs purchase price per item; product gross margin using landed cost |

**Cost centre scope**

| Area | Requirement |
|---|---|
| Master | Hierarchical cost centres (parent/child, categories e.g. department, branch, project, warehouse, vehicle); active dates; default per user, branch, warehouse, employee, vehicle |
| Tagging | Cost centre on every expense/revenue line of: journal entries, vendor bills, expenses, sales invoices, payroll, landed-cost charge lines, stock issues/adjustments, fixed-asset depreciation, POS (per branch), fleet (Tier 4) |
| Split / allocation | One line split across several cost centres by % or amount (Tally "cost allocation"); allocation rules (e.g. rent by floor area) run as periodic allocation journals |
| Rules | Per-account policy: cost centre required / optional / forbidden; validation errors on posting |
| Budgets | Budget vs actual per cost centre (extend `BudgetService`) |
| Reports | Cost centre P&L, expense breakdown, cost centre × account matrix, roll-up by hierarchy, drill-down to source documents, period comparison, export |
| Relation to 2.4 | Cost centre is the first concrete analytic dimension; design the storage so 2.4 generic dimensions generalise it without a data migration |

Deliverables: ADR (data model + posting rules), `docs/spec/procurement.md` + `docs/spec/accounting.md` updates,
Flyway migrations, permissions (`procurement:landed_cost:*`, `accounting:cost_center:*` — follow existing naming),
web screens (voucher editor with allocation preview, cost-centre tree, reports), seed data with the laptop and
cement/steel examples, ITs asserting the figures above and GL balance, authorization + tenant + idempotency tests.
filing and bank reconciliation without spreadsheets.

## Tier 2 — Odoo Enterprise / Business Central parity

| # | Item | Pri | Effort | Scope |
|---|---|---|---|---|
| 2.1 | Warehouse management (WMS) | P1 | L (12 EW) | Bins/sub-locations, putaway rules, pick/pack/ship (1–3 step), waves, packages, ABC cycle counts |
| 2.2 | Barcode / mobile warehouse app | P1 | M (6 EW) | Android module reusing the POS sync stack; receive, pick, count, transfer |
| 2.3 | Replenishment & MRP | P1 | L (10 EW) | Min/max + reorder rules auto-creating RFQs/production; MRP run, capacity planning, subcontracting, by-products, scrap. Depends on 0.1 |
| 2.4 | Analytic dimensions | P1 | M (4 EW) | Generic multi-dimension tags on journal/document lines (project, department, region…), dimension-filtered reports |
| 2.5 | Customization platform | P1 | XL (16 EW) | Custom fields/objects (JSONB + metadata), print-template designer, workflow/automation rules, public API with OAuth clients + scopes. Needs ADR |
| 2.6 | BI & dashboards | P2 | M (6 EW) | Pivot/graph views over reporting views, dashboard builder, scheduled report delivery; optional read-replica |
| 2.7 | Intercompany & multi-company | P2 | L (8 EW) | Multi-company users, mirrored IC transactions, eliminations in consolidation (extends ADR-S005 #6) |
| 2.8 | Subscriptions & deferrals | P2 | M (6 EW) | Plans, recurring billing, proration, deferred revenue/expense schedules |
| 2.9 | Projects & service billing | P2 | M (6 EW) | Projects over `task`, timesheet billing, milestones, WIP accounting |
| 2.10 | Portals & payment gateways | P3 | M (6 EW) + S per gateway | Customer/vendor portal (quotes, invoices, pay online), gateway SPI (Stripe, Adyen, local) |

Also in Tier 2 (small, schedule opportunistically): full UI i18n (S), e-commerce connector to Shopify/WooCommerce (M).

**Exit:** feature checklist parity with Odoo Enterprise for finance, inventory, MRP, sales, purchase.

## Tier 3 — SAP / D365 Finance & Operations class

| # | Item | Pri | Effort | Scope |
|---|---|---|---|---|
| 3.1 | Advanced costing | P1 | L (10 EW) | Actual costing / material ledger, standard-cost variance analysis (price, usage, efficiency), activity-based costing |
| 3.2 | IFRS 15 / IFRS 16 | P1 | L (10 EW) | Revenue recognition (performance obligations, contract assets), lease accounting (ROU asset, liability schedules) |
| 3.3 | Scale & data lifecycle | P1 | XL (16 EW) | Load tests at 10M+ journal lines, partitioning, read replicas, archiving/purge policy, async report generation |
| 3.4 | Governance, risk & compliance | P2 | L (8 EW) | Segregation-of-duties conflict matrix on roles, control tests, evidence export (SOX/SOC 2), field-level change history |
| 3.5 | Treasury | P2 | M (6 EW) | Cash forecasting from AR/AP/PO/SO, liquidity planning, FX exposure |
| 3.6 | AI features | P2 | XL (16 EW) | Vendor-bill OCR → draft bill, bank-match suggestions, anomaly detection on postings, demand forecasting, natural-language report assistant |
| 3.7 | Ecosystem | P3 | XL (ongoing) | EDI (X12/EDIFACT), carrier integrations, marketplace connectors, extension/app packaging + partner program |

## Tier 4 — Vehicle / fleet management (`fleet` module)

New module `com.inventoryerp.fleet`, feature flag `app.modules.fleet`, ADR-S0xx before code. Effort ~10 EW (L).

| # | Area | Scope |
|---|---|---|
| 4.1 | Vehicle registry | Vehicles (VIN, plate, make/model/year, type, fuel type, capacity, status), documents, photos; optional link to `fixedasset` asset for depreciation |
| 4.2 | Drivers & assignments | Driver profile linked to `hr` employee, licence class + expiry, vehicle ↔ driver assignment history, check-out / check-in with odometer + condition |
| 4.3 | Odometer, trips & logbook | Odometer readings (monotonic validation), trips (purpose, route, start/end km, cost center), private vs business use |
| 4.4 | Fuel & energy | Fuel / charging logs, fuel cards, efficiency (L/100 km, km/kWh), anomaly flags; expense posting to GL |
| 4.5 | Maintenance | Service schedules by km / time / engine hours, preventive work orders (reuse `repair` where possible), parts from `inventory`, vendor service bills via `procurement` |
| 4.6 | Inspections & compliance | Checklist-based inspections (pre-trip/DVIR), defects → work orders; registration, insurance, permits, road tax with expiry alerts |
| 4.7 | Incidents & fines | Accidents, damage, insurance claims, traffic fines charged to driver or company |
| 4.8 | Tyres & components | Tyre positions, rotation, tread depth, lifecycle |
| 4.9 | Telematics SPI | Pluggable GPS / OBD provider interface (positions, odometer sync, alerts); mock provider for tests |
| 4.10 | Costs & reports | Cost per vehicle / km, total cost of ownership, utilisation, upcoming renewals/services dashboard, fuel report; GL postings through the posting engine with cost-center dimension |

Permissions `fleet:*`, web screens, authorization + tenant tests, seed data, `docs/spec/fleet.md`.

## Cross-cutting (every tier)

- Each item: spec in `docs/spec/`, ADR when it adds routes/frameworks, integration tests on Testcontainers,
  authorization + tenant-isolation tests, ≥ 80 % JaCoCo on `application`, `PORT_STATUS.md`-style tracking.
- Money routes keep idempotency; new GL postings go through the existing posting engine + account mappings.
- Keep the vertical moat (POS + offline sync + GL tie-out) ahead: hospitality/retail features should not
  regress while horizontal breadth grows.

## Suggested sequence

1. Tier 0 (all) → retire Nest.
2. 1.8 (client requirement), then 1.1–1.4 for the first target country (localization, tax returns, e-invoicing, bank feeds).
3. 1.5–1.7, then 2.4 (dimensions) and 2.1–2.3 (WMS, barcode, MRP).
4. 2.5 customization platform before onboarding partners; 2.6–2.10 by customer demand.
5. Tier 3 only after paying customers outgrow Tier 2.

## Open decisions for the owner

- Target countries for localization and e-invoicing (drives 1.1–1.3 scope).
- Vertical focus (hospitality/retail) vs horizontal SMB ERP (drives Tier 2 order).
- Build vs integrate for BI (2.6), e-commerce and payment gateways.
