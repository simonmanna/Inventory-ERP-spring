# Roadmap — from parity port to world-class ERP

Status: **draft for owner review** (2026-10-27). Nothing here is approved; each item that changes the wire
contract or adds a framework needs its own ADR in `docs/adr/` (see `AGENTS.md` §1, §3).

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
| Routes ported | 943 / 1,118 (+20 ADR-S005 extensions) |
| Strong | GL + posting engine, periods, multi-currency + FX revaluation, bank reconciliation, cash sessions, AVCO/FIFO/standard costing, batches/serials/expiry, reservations/ATP, transfers, counts, procurement with GRNI + landed cost + 3-way match, POS (restaurant + offline Android), HR/payroll, fixed assets, approvals, audit, multi-tenant, licensing |
| Basic | Tax (codes + summary report), sales cycle, analytics (fixed reports), cost dimensions (cost center + branch only) |
| Absent | Localization, e-invoicing, bank feeds, WMS, MRP, intercompany, subscriptions, customization platform, BI, portals, AI |

## Summary

| Tier | Goal | Items | Effort |
|---|---|---|---|
| 0 | Finish the parity port | 4 | ~16 EW |
| 1 | Sellable to a general SMB | 7 | ~40 EW (one e-invoicing standard) |
| 2 | Odoo Enterprise / BC parity | 10 (+2 small) | ~85 EW |
| 3 | SAP / D365 F&O class | 7 | ~70 EW + ongoing ecosystem |

Rough total: **~210 EW** (≈ 4 engineer-years). With a team of 4: Tier 0–1 ≈ 3.5 months, Tier 2 ≈ +5 months.

---

## Tier 0 — Finish the port (prerequisite)

Follow `AGENTS.md` §6; parity rules apply in full.

| # | Item | Pri | Effort | Notes |
|---|---|---|---|---|
| 0.1 | `manufacturing` (42 routes) | P1 | M (5 EW) | BOMs, production orders, work centres, routings, QC; needed before MRP (2.3) |
| 0.2 | `repair` (64 routes) | P2 | M (5 EW) | Largest remaining surface; quotations, jobs, warranties, service contracts |
| 0.3 | `rental` (47 routes) | P2 | M (4 EW) | Reservations, agreements, deposits, damages |
| 0.4 | `communication` (22 routes) | P3 | S (2 EW) | Channels, conversations, templates, WhatsApp; secrets handling per `SECURITY.md` |

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

**Exit:** a non-hospitality SMB in one target country can run quote-to-cash, procure-to-pay, statutory tax
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

## Cross-cutting (every tier)

- Each item: spec in `docs/spec/`, ADR when it adds routes/frameworks, integration tests on Testcontainers,
  authorization + tenant-isolation tests, ≥ 80 % JaCoCo on `application`, `PORT_STATUS.md`-style tracking.
- Money routes keep idempotency; new GL postings go through the existing posting engine + account mappings.
- Keep the vertical moat (POS + offline sync + GL tie-out) ahead: hospitality/retail features should not
  regress while horizontal breadth grows.

## Suggested sequence

1. Tier 0 (all) → retire Nest.
2. 1.1–1.4 for the first target country (localization, tax returns, e-invoicing, bank feeds).
3. 1.5–1.7, then 2.4 (dimensions) and 2.1–2.3 (WMS, barcode, MRP).
4. 2.5 customization platform before onboarding partners; 2.6–2.10 by customer demand.
5. Tier 3 only after paying customers outgrow Tier 2.

## Open decisions for the owner

- Target countries for localization and e-invoicing (drives 1.1–1.3 scope).
- Vertical focus (hospitality/retail) vs horizontal SMB ERP (drives Tier 2 order).
- Build vs integrate for BI (2.6), e-commerce and payment gateways.
