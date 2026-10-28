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
| 1.8 | Landed costing & cost centres — **built 2026-10-28 (ADR-S007)** | P1 | M (7 EW) | Client requirement (Tally-style). Configurable costing policy (AVCO or FIFO, effective-dated, controlled migration); landed-cost vouchers across shipments/receipts/suppliers with quantity/value/weight/volume/manual allocation and late-cost revaluation; hierarchical cost centres as a first-class GL dimension with allocations, budgets and reports. Detailed scope: §1.8 below |

**Exit:** a non-hospitality SMB in one target country can run quote-to-cash, procure-to-pay, statutory tax

### 1.8 Landed costing & cost centres (client requirement)

> **Status 2026-10-28:** implemented per ADR-S007 (`V12`), see `docs/spec/{procurement,accounting,inventory}.md`. Remaining follow-ups: estimated-cost accrual with true-up, supplier credit note on a landed charge (today: reverse + repost), manager-threshold approval routing.

Target client: trading / distribution (imported electronics, construction materials — cement, steel, aggregate),
multi-branch, multi-warehouse, UGX base currency with foreign-currency imports. Build it as a **reusable ERP
capability** on a shared costing + GL foundation — never a special case inside the purchase screen.

#### 1.8.0 Architecture and principles

```
Purchasing & receiving (PO · GRN · vendor bill)        Cost centres (dimensions · allocations · budgets)
            │                                                         │
      Landed cost (vouchers · charges · allocation · late adjustments)│
            │                                                         │
   Shared inventory costing engine (AVCO | FIFO policy · layers · movements · COGS)
            │                                                         │
   General ledger + subledgers (posting engine · AP · inventory control · audit)
            │
   Financial & management reporting (P&L · balance sheet · product margin · cost-centre P&L · reconciliations)
```

Two questions, kept **connected but logically separate**:

- **Landed cost / item costing** — *what did this product cost?* (customs, freight, clearing, insurance capitalised per IAS 2).
- **Cost centre** — *which unit incurred / earned it?* (analytic attribute on GL lines, never a second expense or extra JE).

A freight bill can increase inventory value, be attributed to an import-project cost centre, and be payable to a
separate transport supplier — the system records all three facts **without recording the expense twice**.

Principles (each becomes a test): every posting balances · every valuation change traces to its source document ·
cost-centre attribution never mixes with inventory cost allocation · posted documents corrected only by reversal or
controlled adjustment · company / branch / tenant boundaries preserved · **inventory subledger reconciles to the
inventory control account**.

**Already on `main` (extend, never break existing routes/JSON):** `procurement` `LandedCostService` — one voucher per
*posted* GRN, methods `value | quantity | equal`, one credit account, layer re-costing (capitalised on on-hand,
expensed on consumed), allocation rows, GL posting, period lock. `core` `Product.costingMethod`
(`AVCO | FIFO | STANDARD | SPECIFIC`, default from org setting `inventory.defaultCostingMethod`, freely editable today).
`accounting` `CostCenter` (flat: code, name, type, active) with `costCenterId` + `branchId` on `JournalEntry` /
`JournalLine`; GRNI; perpetual inventory. New behaviour = new routes / new fields, ADR first.

#### 1.8.1 Configurable costing policy (AVCO or FIFO)

The valuation method is an **approved accounting policy**, not a per-user product toggle.

- New `InventoryCostPolicy` (org; scope = organisation default, optionally per product category / inventory class;
  `method` = `AVCO | FIFO` (+ `SPECIFIC` for serialised items, `STANDARD` kept for manufacturing); `effectiveFrom`;
  `status` draft → approved → active → superseded; `approvedBy/At`; `version`).
- Org admin chooses the method in **Settings → Inventory → Costing policy**; only the approved method is enabled for
  new products. Both AVCO and FIFO are fully implemented behind one `CostingStrategy` interface
  (`receive`, `issue`, `revalue(landedAdjustment)`, `valuation(asOf)`) selected by the active policy.
- `Product.costingMethod` stays on the wire (contract) but is **derived from the active policy** for the product's
  scope; direct edits that disagree with the policy are rejected once the product has posted movements.
- **No free switching after postings.** Changing method = new policy version with a future `effectiveFrom`, approved by
  a user holding the policy-approve permission, executed by a controlled **method-migration job**: closes the period
  boundary, snapshots valuation, re-layers stock (AVCO → FIFO: one opening layer per item/warehouse at current average;
  FIFO → AVCO: collapse layers to weighted average), posts any rounding difference to a revaluation account, and
  produces a reconciliation report. Old movements keep the method version they were costed with.
- Every cost movement stores `policyVersionId` + method so historical figures can always be explained.
- Defaults: AVCO recommended for bulk interchangeable goods (cement, aggregate); FIFO where receipt-layer traceability
  matters (imported electronics); SPECIFIC for serialised items. Lower-of-cost-and-NRV write-down report + posting
  (IAS 2) for both methods.

#### 1.8.2 Landed cost workflow

Dedicated **Landed Cost Voucher (LCV)** — users never edit purchase prices to include freight or customs.

1. **Select goods** — link one or more GRNs, purchase invoices or an import **shipment** (new grouping: reference,
   origin, vessel/BL/AWB, ETA, currency). Capture supplier, item, qty, warehouse, currency, receipt date.
2. **Enter charge lines** — each line: cost component, own **supplier** + **supplier invoice reference**, amount,
   currency + rate + rate date, tax code/treatment, capitalisation eligibility (defaulted from component master).
3. **Choose allocation basis per line** — `value | quantity | weight | volume | manual %/amount` (+ existing `equal`);
   a line may apply to **selected receipt lines only**. Weight/volume from product master (new fields + UoM) or
   overridden per receipt line.
4. **Preview** — per item × charge: basis, share %, allocated amount, original cost, new unit cost, rounding
   difference, resulting inventory value, split on-hand vs already consumed (COGS).
5. **Approve and post** — separate prepare / review / approve / post / reverse permissions. Checks: period open,
   duplicate supplier invoice (supplier + reference unique), allocation total = eligible charge exactly, receipts still
   posted, account mappings complete. Posting writes cost movements + GL atomically.
6. **Reconcile and report** — match actuals to estimates, clear differences, shipment profitability, valuation.

**Cost component master** (configurable, effective-dated default treatment + GL account):

| Component | Default treatment |
|---|---|
| Import duty, non-recoverable | Capitalise if directly attributable |
| Freight to bring goods to intended location | Capitalise |
| Clearing / handling / loading | Capitalise when directly attributable |
| Insurance in transit | Capitalise |
| Recoverable VAT / input tax | Input tax receivable — **never** inventory |
| Selling & distribution after acquisition | Expense (with cost centre) |
| General administration | Expense (with cost centre) |
| Abnormal waste, penalties, demurrage, avoidable costs | Review; default expense |

**Estimates and late costs** — estimated charges may be accrued at receipt (Dr inventory / Cr landed-cost accrual);
the actual invoice later clears the accrual and the difference revalues stock. Charges arriving after part of the
stock was sold, transferred, returned or consumed are split by the **costing engine** (never by adding to product
master cost):

- **AVCO**: capitalise the on-hand share into the average; consumed share → COGS (or variance account per policy).
  Location-aware when the average is kept per warehouse.
- **FIFO**: bump the specific receipt layers; for the consumed part of each layer, trace the issues that consumed it
  and post the COGS adjustment in the current open period with source links.
- Transfers carry the adjusted cost to the destination layer / average.

#### 1.8.3 Posting rules (effective-dated account mappings; posting engine only)

Perpetual inventory. Worked figures (UGX) are acceptance tests:

| Event | Dr | Cr |
|---|---|---|
| A. GRN 100 laptops × 2,000,000 | Inventory control 200,000,000 | GRNI 200,000,000 |
| A. Vendor bill matched | GRNI 200,000,000 | AP goods supplier 200,000,000 |
| B. Customs 30M, freight 5M, clearing 2M, insurance 1M, all on hand | Inventory control 38,000,000 | AP per service supplier (4 bills) 38,000,000 |
| C. Same, 50 of 100 already sold | Inventory 19,000,000 · COGS 19,000,000 | AP 38,000,000 |
| D. Branch rent 2,000,000 | Rent expense 2,000,000 *(branch Kampala, dept Admin, CC Kampala Admin)* | AP 2,000,000 |
| E. Pay service suppliers | AP 38,000,000 | Bank 38,000,000 |

Result B: inventory 238,000,000 → **2,380,000 / laptop**; sale at 2,800,000 → gross profit **420,000** (not 800,000).
Mixed shipment: transport 3,000,000 by value, cement 60 % / steel 40 % → **1,800,000 / 1,200,000**, original charge kept.

Rules: no freight/customs expense debit when capitalised. Optional two-stage mode via **landed-cost clearing account**
(Dr clearing / Cr AP on bill; Dr inventory / Cr clearing on LCV) — one mode per org, never both for one charge;
clearing must reconcile to zero or to listed open items. Invoice-before-receipt uses purchase accrual / receipt
clearing, never duplicate inventory. Recoverable VAT → input tax. Foreign currency: transaction + base amount, rate,
rate date; capitalise at the charge's booking rate, later FX differences to FX gain/loss (not inventory).
Withholding tax, supplier credit notes, returns and purchase discounts per approved policy (credit note on a landed
charge = negative LCV adjustment).

#### 1.8.4 Cost centre accounting

First-class accounting dimension, not a field on expense vouchers. Keep separate concepts: **branch** (legal/operational
location), **warehouse** (stock location), **cost centre** (responsible unit), **project / shipment** (temporary
grouping), **department** (classification mapping to one or more cost centres). Do not duplicate the org hierarchy.

Example hierarchy: Company → Kampala Branch → {Sales, Warehouse Operations, Import} · Mbarara Branch → {Sales,
Warehouse Operations}.

| Feature | Behaviour |
|---|---|
| Master | Code, name, parent, branch, category, status, effective from/to |
| Defaults | Inherit from branch, department, employee, supplier, warehouse, document — configurable precedence |
| Allocation | Split a line across centres by fixed %, amount or driver (floor area, headcount, sales); periodic allocation journals; e.g. 10,000,000 shared office cost → Sales 60 % / Admin 40 % |
| Budgets | Period × account × cost centre (extend `BudgetService`), budget vs actual |
| Approvals | Route transactions above thresholds to the centre's manager (existing approvals engine) |
| Restrictions | Per-account rule required / optional / forbidden; inactive or out-of-date centres rejected on new postings |
| Coverage | JE, vendor bills, expenses, sales invoices, payroll, LCV charge lines, stock issues/adjustments, depreciation, POS (by branch), fleet (Tier 4) |
| Reports | Cost-centre P&L, expense by centre, centre × account matrix, hierarchy roll-up, budget vs actual, department profitability, drill-down to source |
| Audit | Original transaction + allocation history preserved; allocations sum exactly to source amount |

Relation to 2.4: cost centre is the first concrete dimension; store it so 2.4 generic dimensions generalise it without
a data migration.

#### 1.8.5 Data model (Flyway V{n}; quoted camelCase table names like the existing schema)

| Table | Purpose |
|---|---|
| `InventoryCostPolicy` | Org, scope (org / category), method, effectiveFrom, status, version, approvals |
| `CostComponent` | Charge type master: code, default treatment, capitalisable, GL account, tax treatment, effective dates |
| `Shipment` (+ `ShipmentReceipt`) | Import shipment grouping receipts / invoices |
| `LandedCost` (extend) | Voucher: shipment, currency, rate, status workflow, totals, approvals |
| `LandedCostReceipt` | Voucher ↔ GRN / purchase invoice bridge |
| `LandedCostCharge` (extend) | Component, supplier, supplier invoice ref (unique per supplier), currency/rate/date, tax, capitalisable, method, estimate/actual |
| `LandedCostAllocation` (extend) | Charge line × receipt line: basis, share, allocated, capitalised, to-COGS, rounding, unit before/after |
| `InventoryCostLayer` | Item, warehouse, batch, source receipt, original/remaining qty, unit cost (FIFO; AVCO keeps one rolling record) |
| `InventoryCostMovement` | Source doc type/id, item, warehouse, qty, value, movement type, posting date, policyVersionId, JE link |
| `CostCenter` (extend) | parentId, branchId, category, effectiveFrom/To |
| `CostAllocationRule` / `CostAllocation` | Driver rules; source journal line → destination centre, basis, % / amount |
| `AccountDimensionRule` | Account × dimension: required / optional / forbidden |
| Posting mappings | Effective-dated rules for GRNI, inventory, landed-cost clearing/accrual, AP, input tax, COGS, revaluation, CC defaults |

Reuse existing cost-layer / stock-valuation tables where they already cover a row above — extend, do not duplicate.
Schema rules: `numeric` for all money/qty (no float); transaction + base currency, rate, rate date; unique constraints
on source document to block double posting; `organizationId` on every FK path and query; immutable posted lines; store
posted values + method version; source reference on every inventory and GL movement. **No generic `expenseAmount`
field that mutates product cost.**

#### 1.8.6 Engines, controls and concurrency

- **Allocation engine** — pure, deterministic calculator (unit-tested): all methods, selected-lines subset, exact
  totals, remainder to last line, auditable breakdown.
- **Valuation engine** — `CostingStrategy` AVCO + FIFO (+ SPECIFIC), late-adjustment revaluation, NRV.
- **Posting service** — cost movements + JE in one transaction; `Idempotency-Key` required; source-document uniqueness;
  `@Version` + `SELECT … FOR UPDATE` on voucher and layers/averages; two users cannot post one voucher; allocation
  cannot exceed eligible charge; closed period untouched without authorised reopen; reversal restores layers and posts
  a reversing JE.
- **Permissions** — prepare / review / approve / post / reverse / close, policy approve, cost-centre manage, branch
  restrictions (follow existing `procurement:*` / `accounting:*` / `inventory:*` naming; add to `packages/shared`).

#### 1.8.7 Reports and reconciliation

Landed cost by shipment (purchase value, each component, total, cost per item) · by item / supplier / branch · product
profitability (revenue, COGS incl. landed cost, GP, margin) · cost-centre P&L + budget vs actual · inventory valuation
by item and warehouse · **inventory subledger ↔ GL inventory control reconciliation** · open landed-cost clearing /
accruals · method-migration reconciliation.

#### 1.8.8 Acceptance tests (item not done until all pass)

Allocation equals source charge exactly incl. rounding · recoverable VAT excluded from cost · GRN + vendor bill do not
duplicate inventory · multi-product, multi-freight-supplier shipment posts correctly · partial sales before late
invoice → correct inventory/COGS split **under AVCO and under FIFO** (same scenarios run with each policy) · repeated
post requests do not duplicate JEs or movements · cost-centre allocations sum to source · branch and company reports
reconcile · subledger = inventory control account · reversals, period locks, permissions, audit trail · concurrent
posting and concurrent stock movements cannot corrupt valuation · method switch only via approved policy + migration
job, totals preserved · foreign currency, supplier credit notes, returns, purchase discounts per policy · authorization
(403) and tenant isolation per route · laptop and cement/steel figures above.

#### 1.8.9 Delivery plan (~7 EW)

| Phase | Effort | Deliverable |
|---|---|---|
| 1. Policy & discovery | 0.5 EW | ADR (costing policy, posting rules, data model); defaults recorded as Accepted (autonomous) |
| 2. Data model & config | 1 EW | Migrations, cost policy, cost components, cost-centre hierarchy, dimension rules, posting mappings, settings UI |
| 3. Landed-cost workflow | 1.5 EW | Shipments, multi-receipt / multi-supplier voucher, allocation preview, approvals, posting |
| 4. Costing integration | 1.5 EW | AVCO + FIFO strategies, late adjustments, method-migration job, NRV |
| 5. Cost-centre accounting | 1 EW | Tagging across modules, splits, allocation rules, budgets, reports |
| 6. Tests & reconciliation | 1.5 EW | §1.8.8 suite, reconciliation reports, seed data with both examples, web screens |

Client accountant still to confirm — do **not** block; ship the configurable default and log it in `BUILD_LOG.md`:
(1) valuation method per inventory class (default AVCO org-wide, FIFO selectable); (2) which costs capitalise, which
taxes are recoverable, post-sale cost treatment (defaults = component table above); (3) cost-centre hierarchy,
allocation rules and required reports (seed the Kampala / Mbarara example).

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
