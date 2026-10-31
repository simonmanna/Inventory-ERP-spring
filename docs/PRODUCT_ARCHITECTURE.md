# ERP Product Architecture & Capability Roadmap

Status: draft (2026-10-30) — owner review pending. Living document: update it in the same PR as any module, ADR or
roadmap change.

Positioning: **a modular enterprise ERP with industry-specific operations** — one platform for finance, sales,
supply chain, manufacturing, logistics, people and analytics, configurable per business, with specialised extensions
for quarrying, construction materials, trading, distribution and retail / hospitality.

Related: [`ROADMAP.md`](ROADMAP.md) (tiers and effort), [`AUTONOMOUS_BUILD_CONSTRUCTION.md`](AUTONOMOUS_BUILD_CONSTRUCTION.md)
(Track C), [`BUILD_LOG.md`](BUILD_LOG.md) (live status), [`ARCHITECTURE.md`](ARCHITECTURE.md) (code layering),
[`requirements/construction-materials-erp.md`](requirements/construction-materials-erp.md), ADRs in [`adr/`](adr/).

## 1. Product principles

1. **One source of truth.** Every fact has exactly one owning module (§4). Others read through its API or events.
2. **Integrated processes.** A business transaction flows across modules without re-entry.
3. **Configurable, not hardcoded.** Pricing, approvals, costing, tax, workflows and timing policies are
   configuration — validated, permissioned, effective-dated and audited. Only real business variants become settings.
4. **Auditable and controlled.** Permission, audit trail and a reversal path on every important action; posted
   records are never edited.
5. **Extensible.** New industries, branches, organizations and integrations plug in without rewriting the core.

## 2. Three layers

```
┌───────────────────────────────────────────────────────────────────────────────────────┐
│ Layer 3 — Industry extensions                                                         │
│ quarry & extraction · crushing & screening · block / precast · weighbridge &          │
│ bulk materials · grading & quality · yield & wastage · hospitality POS · beverage     │
├───────────────────────────────────────────────────────────────────────────────────────┤
│ Layer 2 — Generic ERP business modules                                                │
│ accounting · invoicing/AR · expenses/AP · sales · credit · crm · inventory ·          │
│ procurement · manufacturing · fleet & logistics · hr & payroll · fixed assets ·       │
│ maintenance · quality · projects/tasks · rental · repair · pos · documents · BI       │
├───────────────────────────────────────────────────────────────────────────────────────┤
│ Layer 1 — Platform kernel (`kernel`, `shared`, `core`)                                │
│ tenancy · identity & permissions · workflow & approvals · audit · numbering ·         │
│ events (outbox) · notifications & communication · files/DMS · licensing & features ·  │
│ settings · jobs · idempotency · reporting/PDF · sync (offline clients)                │
└───────────────────────────────────────────────────────────────────────────────────────┘
Data & infrastructure: PostgreSQL 16 · Flyway · Spring Boot 4 · Modulith boundaries · Micrometer ·
backups · monitoring · (later) read replica / BI · AI services
```

Rules: Layer 3 depends on Layer 2 and 1, never the reverse (Layer 2 exposes ports — e.g. sales `SupplyProvider` —
that Layer 3 implements). Industry features are licensed add-ons (`kernel/licensing/LicenseFeatures`).

## 3. Organization, company and branch model

- **Organization = tenant = legal entity (company).** Own chart of accounts, sequences, partners, settings.
- **Branch / location** inside an organization: branches, quarries, plants, yards, warehouses (`InventoryLocation`
  types). Branch-scoped access is a data filter enforced in services (Track C Phase 4 "Branches").
- **Multi-company / intercompany** (ROADMAP 2.7) = several organizations under one group: multi-org users, mirrored
  intercompany documents (sale in A ↔ purchase in B), eliminations in consolidation. Rules until built: no
  organization fulfils from another's stock or invoices another's customer without an intercompany document; credit
  limits and pricing are per organization.

## 4. Modules — responsibilities, owned data, integration, maturity

Maturity: **none / basic / standard / enterprise** (assessed 2026-10-30 from docs and build log; Track C Phase 3
`GAP_ANALYSIS.md` verifies it against code).

| Module | Owns (source of truth) | Publishes / exposes | Consumes | Maturity | Next step |
|---|---|---|---|---|---|
| kernel | tenancy, users, roles, approvals, workflow, audit, sequences, outbox, files, licensing, settings, jobs | approval / workflow / audit APIs, `EventBus` | — | standard | approval delegation, event payload versioning, inbox helper |
| core | partners, contacts, addresses, products, units (ADR-S003), taxes, payment terms, branches | `PartnerService`, `UomConversionService`, `TaxService` | — | standard | density / truckload units (Track C P4), branch access filter |
| accounting | GL, journals, periods, posting engine, account mapping, FX revaluation, bank rec, cost centres (ADR-S005/S007/S008) | `PostingService`, `AccountMappingService` | posting requests | standard → enterprise | year-end close (1.7), budgets, cash-flow, analytic dimensions (2.4) |
| invoicing / AR | sales invoices, credit notes, payments, allocations, statements, (new) deposits, payment gateways | `invoice.*`, `payment.*`, `DocumentBuilderService` | sales billing requests | standard | deposits, `PaymentGateway` SPI + mobile money, dunning (S6–S8) |
| expenses / AP | vendor bills, expenses | `bill.*` | procurement, fleet costs | standard | — |
| **sales** (ADR-S011) | quotations, sales orders, amendments, pricelists, agreements, teams, targets, commissions, rebates, RMAs, dunning | `sales.*`, `SalesOrderQuery`, ports `SupplyProvider`, `MakeToOrderPort` | fleet, weighbridge, invoicing, inventory events | none (design) | S0 → S10 per [`spec/sales.md`](spec/sales.md) |
| **credit** (ADR-S011) | limits, exposure, decisions, holds | `credit.*`, `CreditExposureService` | contributor SPI | none (design) | S3 per [`spec/credit.md`](spec/credit.md) |
| crm | leads, opportunities, activities, pipeline config (ADR-S010) | `crm.*` | sales quotations (link) | standard | complaints / service cases, segmentation; quotations move to sales |
| inventory | stock, locations, movements, valuation (AVCO/FIFO/standard), batches, serials, reservations, counts, transfers | `StockService`, `StockReservationService`, `stock.*` | sales / production / procurement requests | standard | stock status (quarantine / damaged), bins / WMS (2.1), branch transfers with approval |
| procurement | requisitions, RFQs, POs, GRNs, landed cost, 3-way match | `po.*`, open supply | MRP, sales shortages | standard | requisition approval, supplier comparison & performance, explosives control (Track C P4) |
| manufacturing (ADR-S006) | BOMs, routings, work centres, production orders, MRP, QC hold | `production.*`, `ProductionRequestService` | sales demand (later), inventory | basic → standard (other session building) | multi-stage WIP, quarry, crushing, precast (Track C 2.x) |
| fleet & logistics (ADR-S009) | vehicles, drivers, delivery orders, trips, POD, discrepancies, fuel, maintenance, tyres, telematics, weighbridge | `fleet.*`, `weighbridge.*` | sales orders, invoices | standard | 1.4b weighbridge ↔ trip, 1.9 costing; DO core unlicensed (ADR-S011) |
| hr & payroll | employees, attendance, payroll, allowances, commissions pay items | `hr.*` | fleet allowances, sales commissions (S9) | standard | shifts / rosters, certifications (Track C P4) |
| fixed assets | assets, depreciation, disposal | `asset.*` | procurement | standard | link to maintenance equipment |
| maintenance (CMMS) | equipment, PM schedules, work orders (fleet engine reused for plant) | | inventory parts | basic (fleet only) | fixed-plant CMMS (Track C P4) |
| quality | inspections, test certificates, non-conformance | | production, sales returns | none / basic (QC hold only) | Track C 2.6 |
| pos | counter sales, sessions, tabs, restaurant flow, offline Android sync | `order.*`, `pos.*` | credit (S3) | enterprise (vertical moat) | none for sales; keep untouched |
| documents (DMS) | templates, relations, print log | `DocumentRelationsService` | all | standard | e-signature (portal) |
| communication | channels, templates, rules, WhatsApp / Telegram | `MessageDispatchService` | dunning, alerts | standard | — |
| rental, repair, task, beverage, backup | as ported | | | standard | — |
| BI & analytics | reporting views, dashboards, KPIs | | all | basic (fixed reports) | 2.6 / Track C P5 KPI dashboards |
| portals | customer / supplier self-service | | sales, invoicing, fleet | none | 2.10 / S10 |
| AI | advisory models (forecast, anomalies, assistants) | | read-only | none | 3.6 / S10 (advise only, never post or approve) |

## 5. Cross-module process maps

### 5.1 Order-to-cash (Stage 2 — detailed in [`spec/sales.md`](spec/sales.md))
`Opportunity (crm) → Quotation (sales) → credit check (credit) → Sales order (sales) → ATP + reservation (inventory,
procurement, manufacturing later) → Delivery order / trips / weighing (fleet) → stock issue (inventory) → billable
decision (sales) → invoice (invoicing) → deposit application / payment (invoicing) → GL (accounting) → returns /
credit notes → collections → analytics`

### 5.2 Sales ↔ production coordination (manufacturing-integration target)
Blocks (make-to-stock / make-to-order):
1. Finished-goods stock by yard and curing status (cured vs curing release date).
2. Shortage vs order.
3. Capacity of block machines / work centres by day.
4. Raw materials: cement, sand, aggregate, admixtures (ATP of inputs).
5. Earliest completion date = max(capacity slot, material availability) + curing time.
6. Promise date to the customer (sales ATP via `ProductionSupplyProvider`).
7. Create or propose a production request (`MakeToOrderPort` → `ProductionRequestService.raiseRequest`).
8. Track actual production and curing against the sales commitment; re-promise on slippage.

Aggregates:
1. Stock by grade (20 mm, 10 mm, dust, sand) at quarry / yard.
2. Quarry output plan (blasting, extraction rate) and crusher / screen capacity and yield split by grade.
3. ATP per grade = stock + planned crushing output × yield − commitments.
4. Shortage → schedule crushing shift or buy-in (procurement).

### 5.3 Procure-to-pay, record-to-report, hire-to-retire
Existing (procurement → GRN → landed cost → bill → payment; posting engine → periods → statements; hr → payroll →
GL). Gaps listed in Track C Phase 4.

## 6. Capability stages and release gates

| Stage | Capability | Status | Detailed plan |
|---|---|---|---|
| 1 | Finance & accounting foundation | largely built | ROADMAP 1.1–1.8, ADR-S005/S007/S008 |
| 2 | **Sales O2C** | Step 0 design | ADR-S011, `spec/sales.md`, `spec/credit.md`, `spec/sales-postings.md` |
| 3 | Inventory & procurement depth | built core; gaps in Track C P4 | ROADMAP 2.1–2.2 |
| 4 | Manufacturing & costing (quarry, crushing, precast) | in progress (other session) | ADR-S006, Track C 2.x |
| 5 | Fleet, weighbridge & logistics | mostly built (1.4b, 1.9 open) | ADR-S009 |
| 6 | CRM & customer management | built (ADR-S010); service cases pending | `spec/crm.md` |
| 7 | HR, payroll & assets | built core; shifts / certifications pending | Track C P4 |
| 8 | Quality & maintenance | basic | Track C 2.6, P4 CMMS |
| 9 | Business intelligence | basic | ROADMAP 2.6, Track C P5 |
| 10 | Portals & AI | none | ROADMAP 2.10, 3.6; sales S10 |

### Release gates (every stage and every phase inside it)
1. Build green (`./gradlew build`), ModularityTests / ArchitectureTests, JaCoCo ≥ 80 % on `application`.
2. Migration safety: fresh migrate + migrate a copy of demo / VPS data; verification queries; rollback note.
3. Accounting reconciliation: trial balance, subledgers = control accounts, stock ledger = inventory GL.
4. Security: permission matrix, tenant + branch isolation, idempotency replay on money routes.
5. Concurrency: no oversell / double post under parallel requests.
6. UAT rows in `VERIFY_CHECKLIST.md` clicked through by the owner.
7. Performance smoke (500-user target: p95 < 300 ms for order entry, ATP, credit check).
8. Operations: metrics, alerts, runbook, backup / restore drill.
9. Unbuilt integrations stay unavailable to users — never shown as complete.

## 7. Enterprise non-functional workstream (continuous)

| Concern | Target |
|---|---|
| Multi-company | §3; intercompany in ROADMAP 2.7 |
| Multi-currency | document currency + rate locked at confirm / post; revaluation (exists) |
| Access | RBAC permission strings + attribute rules (own / team / branch) |
| Audit & delegation | audit on every state change; approval delegation |
| Retention & DMS | documents, signed acceptances, certificates via files / DMS; retention policy (3.3) |
| Integration | OpenAPI, versioned event payloads, inbox / outbox, webhooks (2.5) |
| Performance & scale | load tests, partitioning, archiving (3.3) |
| Operations | Micrometer / Prometheus, alerting, backups, DR drills (`OPERATIONS.md`) |
| Security | `SECURITY.md`; SoD conflict matrix (3.4) |

## 8. Long-term capability backlog (not scheduled)

| Capability | Why | Stage |
|---|---|---|
| Customer-specific product catalogues | contractors allowed only certain grades | 2+ |
| Sales territories | regional assignment of customers / opportunities | 2+ / 6 |
| Product allocation | reserve scarce output for strategic contracts | 2 (after ATP) + 4 |
| Delivery scheduling | coordinate many deliveries against site requirements | 2 + 5 |
| Customer service cases | complaints, quality issues, service requests → RMA | 6 |
| Customer profitability | contribution after discounts, freight, rebates, cost | 9 |
| Sales forecasting → MRP | demand feed for procurement / production | 2 S10 + 4 |
| Electronic acceptance / signature | signed quotes, POD, contracts | 10 |
| Regulatory / tax integrations (e-invoicing, e.g. URA EFRIS) | statutory compliance | ROADMAP 1.3 |

## 9. Governance

- New module or cross-module contract → ADR (`docs/adr/ADR-Sxxx`).
- Each module chapter lives in `docs/spec/<module>.md`; this document links them and holds ownership + maturity.
- Parallel sessions claim items in `BUILD_LOG.md` before starting.
- Phases start only on explicit owner go-ahead after inspecting the previous phase (ADR-S011 §13).
