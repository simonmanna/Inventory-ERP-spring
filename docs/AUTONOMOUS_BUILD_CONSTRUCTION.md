# Autonomous build brief — Construction Materials ERP (Fleet → Advanced Manufacturing → Production-ready)

You are the AI engineer taking over **Inventory-ERP-spring** for a paying client: a **quarrying, aggregate
crushing, concrete-block manufacturing, construction-materials trading and distribution company** (a supplier
to construction companies). Your job is to turn this ERP into a **production-ready system for that client**,
phase by phase, **autonomously**, committing and pushing to `origin/main` after every phase and every module.

The client's full scope is [`docs/requirements/construction-materials-erp.md`](requirements/construction-materials-erp.md).
Read it in full. Every bullet in it must end up either **built and verified**, or **explicitly mapped to an
existing feature that you verified works**, in `docs/requirements/GAP_ANALYSIS.md`.

This brief **takes precedence over the execution order in `docs/AUTONOMOUS_BUILD.md`**. Everything else in
`AUTONOMOUS_BUILD.md` (decision policy §1, per-item loop §4, git protocol §5, production-ready definition §6,
hard rules §9) still applies — read it, and follow it unless this file says otherwise.

The owner has delegated all engineering decisions to you. **Do not stop, pause for approval, or ask
questions** until the last line of `docs/BUILD_LOG.md` reads `STATUS: CONSTRUCTION ERP COMPLETE`.

---

## 0. Absolute constraints (read twice)

1. **Do not change the POS.** The POS is live and frozen. You may *read* it, never modify it:
   - Backend: `backend/src/main/java/com/inventoryerp/pos/**`, POS parts of `sync/**`, POS tests.
   - Web: `web/src/features/pos/**`, `web/src/pages/pos/**`, `web/src/features/tables/**`,
     `web/src/pages/tables/**`, `web/src/features/menu/**`, `web/src/pages/menu/**`, the POS module switch.
   - Android: `android/**` (entirely).
   - Shared code (`kernel`, `inventory`, `accounting`, `invoicing`, `shared`, `packages/shared`) **may** change,
     but only additively and never in a way that changes POS behaviour, routes, JSON, or POS GL postings.
     Every existing POS test (`*Pos*IT`, `PosLakeviewSimIT`, sync tests) must stay green after every commit.
   - If a feature seems to need a POS change, build it outside the POS (new route / new screen) instead, and
     record the decision in an ADR. Never edit POS to make your feature fit.
2. **Never edit** `reference/`, `V1__baseline.sql`, `V2__reference_data.sql`, or an already-applied migration.
   Schema changes = new Flyway `V{n}__<slug>.sql`. The next free number at the time of writing is **V15** —
   always re-check `backend/src/main/resources/db/migration/` before creating one.
3. **Existing routes keep their contract** (path, verb, JSON, status, error messages). New features = new routes.
4. All rules of `AGENTS.md` §8 apply: tenancy filter on every org-scoped query, idempotency on money routes,
   `BigDecimal` + `HALF_UP` (never `double`/`float`) for money and quantities, posting engine for every GL
   effect, records (not entities) from controllers, no logic/repositories in controllers, no secrets in logs.
5. Only push **green** builds. Never force-push, rewrite pushed history, skip hooks, or commit `.env`/keys/dumps.

## 1. Resume protocol (first thing in EVERY session)

Sessions will end (context limits, usage limits). Progress must live in git, not in your memory.

1. `git checkout main && git pull --rebase origin main`.
2. Read in order: `AGENTS.md`, `CLAUDE.md`, **this file**, `docs/requirements/construction-materials-erp.md`,
   `docs/AUTONOMOUS_BUILD.md`, `docs/ROADMAP.md`, `docs/BUILD_LOG.md`, `docs/requirements/GAP_ANALYSIS.md`
   (if it exists).
3. In `docs/BUILD_LOG.md` find the section **`## Track C — Construction materials`** (create it from §9 below on
   the first run). Continue the first phase/item whose status is not `done`.
4. If the working tree has uncommitted changes from a crashed session: inspect, then finish or discard them
   deliberately; note what you did in the log.
5. Keep going phase after phase. Never end a session voluntarily while work remains. When context is getting
   full: commit + push the current **green** state, set the `Next action:` line in `BUILD_LOG.md`, push, and let
   the session end. The next session resumes from the log.

### Coordination with other sessions

`BUILD_LOG.md` shows another session ("B", a MacBook) had **claimed Tier 4 fleet** and some Tier 2 items in
local worktree branches that were **never pushed** (only `main` exists on `origin`; no `fleet` package exists on
`main` as of 2026-10-29). Before Phase 1:

- `git fetch --all --prune` and check `git ls-remote --heads origin` and `backend/src/main/java/com/inventoryerp/`
  for any `fleet` work. If fleet code has appeared on `origin`, **build on it** (do not duplicate); if not, take
  over the claim: change the `4 fleet` row to `in-progress (C)` with a note
  `taken over by Track C on <date>; session B work never pushed`, commit + push the claim, then start.
- Claim every item in `BUILD_LOG.md` (commit + push the claim) before starting it. Rebase often.

## 2. Execution order (strict)

| Phase | What | Push / tag when done |
|---|---|---|
| **0** | State audit + baseline (short) | push log |
| **1** | **Vehicles & Fleet management** — full module for a construction-materials supplier, incl. delivery dispatch and weighbridge | push after each sub-module; tag `fleet-complete` |
| **2** | **Advanced Production / Manufacturing** — multi-stage, any-stage manufacturing incl. quarry extraction, crushing/screening, block manufacturing, quality | push after each sub-module; tag `manufacturing-advanced-complete` |
| **3** | **Requirements verification** — gap analysis of every bullet in the client document; prove each existing capability works | push; tag `requirements-verified` |
| **4** | **Close the gaps** — build every missing capability found in Phase 3 (core ERP, procurement, sales, UoM, branches, maintenance, HR, CRM, controls) | push after each module; tag `gaps-closed` |
| **5** | **Dashboards, KPIs, alerts & integrations** | push; tag `bi-complete` |
| **6** | **Production hardening** — `docs/phases/P8-hardening-release.md` + `AUTONOMOUS_BUILD.md` §6 across the system | push; tag `v2.1.0-rc` |
| **7** | **Demo data, docs, final report** | push; tag `v2.1.0` |

Phase 1 and Phase 2 come first because the client asked for them first. Do **not** start Phase 2 before
Phase 1 is pushed and tagged, and so on. Inside a phase you may reorder sub-items when a dependency demands it
(log why).

## 3. Per-module loop (every sub-module of every phase)

1. **Plan** — write/extend `docs/spec/<module>.md` (purpose, functional areas, route table with permissions,
   data model, state machines, events, scheduled jobs, GL postings, deviations). Write an ADR
   `docs/adr/ADR-S0NN-<slug>.md` (status `Accepted (autonomous, <date>)`) before code for any new module,
   new framework, or design choice with lasting impact. Next free ADR number: check `docs/adr/` (S009 at the
   time of writing).
2. **Schema** — new Flyway migration; quoted camelCase table/column names like the existing schema; `id`
   text UUID, `organizationId` on every org-scoped table + index, `createdAt`/`updatedAt`, `@Version` where rows
   are contended; FK + indexes for every query path. Entities by hand in `<module>/domain`.
3. **Code** — `web` → `application` → `domain`. Records + Bean Validation for DTOs; `ApiException` for errors;
   `@RequirePermissions` on every route; new permission strings added to `packages/shared/src/permissions.ts`,
   then `node tools/contracts2java/generate.mjs`; new domain events added to `packages/shared/src/events.ts`
   the same way; cross-module calls through the other module's `application` API or events (Spring Modulith —
   `ModularityTests` must stay green); stock only through `StockService` / `StockPostingService`;
   GL only through the posting engine with effective-dated account mappings; document numbers through the
   existing numbering service; approvals through the existing approval workflow engine; audit trail through
   the existing audit service; scheduled jobs with ShedLock.
4. **Feature flag** — new modules behind `app.modules.<name>` (`ENABLE_<NAME>` env, add to `application.yml`
   and `.env.example`) and registered in the licence/module registry the same way `manufacturing` and `rental`
   are, so the web Modules screen can switch them on.
5. **Web UI** — screens in `web/src/features/<module>` + `web/src/pages/<module>`, following existing patterns
   (look at `manufacturing`, `rental`, `repair` screens): list with filters + pagination, detail, create/edit
   forms with validation, action buttons per state, empty/loading/error states, permission-aware menu entries,
   i18n keys. **A feature without a usable screen is not done.** Mobile-friendly layouts for driver/operator/
   weighbridge/shop-floor screens (they are used on tablets and phones in the yard).
6. **Tests** — unit tests for calculators/policies/state machines; `@SpringBootTest` ITs per use case (see
   `docs/TESTING.md`; Testcontainers, or `IT_EXTERNAL_DB=true` against a local Postgres when Docker is absent);
   authorization test per route (403 without permission); tenant-isolation test (org A cannot see org B);
   idempotency replay test on every money route; GL balance assertion (debits = credits) for every posting;
   reversal tests; concurrency test where stock/money is contended. JaCoCo ≥ 80 % lines on the module's
   `application` package. Web: `pnpm` lint + typecheck + tests green.
7. **Verify** — `cd backend && ./gradlew build` green; start the stack, exercise the feature end-to-end through
   the web UI with zero console errors (use the browser preview tools if available), including the GL result in
   the accounting screens.
8. **Document** — module spec status section, `docs/ROADMAP.md` status, `docs/requirements/GAP_ANALYSIS.md`
   rows ticked with evidence (route/screen/test names), OpenAPI annotations, `SeedRunner` demo data,
   `.env.example`, release notes in `docs/releases/`.
9. **Commit + push** — Conventional Commits (`feat(fleet): ...`, `feat(manufacturing): ...`, `test(...)`,
   `docs(...)`), each ending with your co-author trailer. Then:
   `git pull --rebase origin main && cd backend && ./gradlew build && cd .. && git push origin main`.
   Push after **every finished sub-module**, not only at phase end.
10. **Log** — update `BUILD_LOG.md` (status, commit SHAs, notes, `Next action:`), commit + push, start the next
    item immediately.

At the end of each phase: run the full build, push, create the annotated tag from §2
(`git tag -a <tag> -m "<phase> complete" && git push origin <tag>`), write a short phase summary into
`BUILD_LOG.md`, push.

---

## Phase 0 — State audit (keep it short: ≤ 1 session)

1. Run the full build; record test count and any failures in `BUILD_LOG.md`. Known flaky: POS trading-date tests
   between 00:00 and 06:00 UTC (recently fixed in `9c82a91` — confirm). Fix any red that is not POS logic.
   If a POS test is red, find the root cause outside POS (shared code you or others changed); do not edit POS.
2. Inventory what already exists that Phases 1–2 will reuse — read their specs and services:
   `fixedasset` (assets, depreciation), `repair` (jobs, work orders, parts), `hr` (employees, shifts, payroll),
   `inventory` (locations, batches, UoM, reservations, counts, transfers, QC quarantine), `procurement`
   (PO, GRN, bills, landed cost), `orders` / `invoicing` (sales orders, invoices, delivery), `expenses`,
   `accounting` (posting engine, cost centres, dimensions), `manufacturing` (see `docs/spec/manufacturing.md`:
   BOMs, routings, work centres, work orders, MRP, scheduling, QC plans, scrap reasons, rework, standard &
   process costing, engineering changes), `rental` (it has a "fleet" report for rental assets — do not confuse
   with the new `fleet` module; reuse ideas, do not couple).
3. Create the Track C section in `BUILD_LOG.md` (§9), claim Phase 1, commit + push.

---

## Phase 1 — Vehicles & Fleet management (`fleet` module)

Client context: they run **tipper trucks, trailers, water bowsers, excavators, wheel loaders, dozers, forklifts,
generators and light vehicles**; deliver aggregates, sand, blocks and cement by the **tonne, m³, piece, bag or
truckload** to **construction sites**; use **own trucks and third-party transporters**; and operate a
**weighbridge** at the quarry/yard. Scope = `ROADMAP.md` Tier 4 (4.1–4.10) **plus** requirements §5, §5.1, §7
(for mobile equipment), adapted to this industry.

New module `com.inventoryerp.fleet`, flag `app.modules.fleet` / `ENABLE_FLEET`, permissions `fleet:*`
(granular: `fleet_vehicle:*`, `fleet_driver:*`, `fleet_trip:*`, `fleet_fuel:*`, `fleet_maintenance:*`,
`fleet_dispatch:*`, `weighbridge:*`, `fleet:report`, `fleet:settings` — your design, documented in the spec).
ADR first (`ADR-S0NN-fleet-and-logistics.md`): module boundaries, which existing modules are reused
(fixedasset, hr, inventory, procurement, orders/invoicing, expenses, repair), GL posting rules, telematics +
weighbridge SPIs.

Build and push each sub-module separately, in this order:

### 1.1 Vehicle & equipment registry
- Vehicle/equipment master: code, registration plate, VIN/chassis, engine no., make/model/year, **class**
  (tipper, trailer, prime mover, water bowser, excavator, wheel loader, dozer, forklift, generator, light
  vehicle, other — configurable categories), ownership (owned / leased / hired / third-party), **payload
  capacity** (tonnes) + **body volume** (m³) + axle config, fuel type, tank size, meter type (**odometer km
  and/or engine hours**), home branch/site, cost centre, status (active, in workshop, breakdown, idle,
  disposed) with status history, photos + documents (via `documents` module), optional link to a `fixedasset`
  asset (depreciation stays in fixedasset; fleet shows it).
- Meter readings (odometer and hour-meter) with monotonic validation and correction workflow (approved
  rollback with audit).
- Compliance documents: registration, insurance, road licence/permit, inspection certificate, operator permits
  — with expiry dates and reminder lead times.

### 1.2 Drivers & operators
- Driver/operator profile linked to an `hr` employee: licence number, classes, expiry, medical/permit expiry,
  qualified vehicle classes/equipment.
- Vehicle ↔ driver assignment history; **check-out / check-in** (odometer/hours, fuel level, condition,
  photos); block assignment when licence/class is invalid or expired.
- Driver allowances and trip expenses (per-trip/per-day allowance rules), approved and either paid via
  `expenses` or exported to `hr` payroll as an earning line.

### 1.3 Trips, delivery planning & dispatch (the core for this client)
- **Delivery orders** generated from confirmed sales orders/invoices (`orders` / `invoicing` application API —
  read their specs to find the sales-order document; do not touch POS sales), or created manually; customer,
  **construction site / delivery address** (customer sites with GPS coords, contact, access notes), product
  lines with ordered qty + UoM, requested date/time window.
- **Dispatch board**: schedule deliveries by date / customer / site; split an order into multiple **loads/trips**
  respecting truck payload (t) and volume (m³); assign truck + driver (+ trailer); own fleet or **third-party
  transporter** (supplier) with agreed freight rate (per trip / per tonne / per km).
- Trip lifecycle: planned → loading → weighed → dispatched → in transit → delivered → closed (and cancelled /
  returned). Start/end odometer, times, route/distance, purpose, cost centre.
- **Delivery note** (PDF via the existing PDF/document service) with dispatched quantity; **proof of delivery**:
  received quantity, receiver name, signature image/photo, timestamp, GPS if supplied.
- Dispatch issues stock from the yard location through `StockService` (correct product, batch, UoM) — exactly
  once per load; returns/rejected loads put stock back. Delivered-vs-ordered **discrepancies and shortages**
  recorded with reason and routed through the approval workflow; approved discrepancies adjust the delivery
  record and, where applicable, raise a credit note request via `invoicing` (do not auto-post silently).
- Delivery charges: optional transport/freight charge lines added to the customer invoice (configurable per
  customer/zone/distance).

### 1.4 Weighbridge
- Weighbridge stations (per site), **ticket workflow** exactly as requirements §5.1: register truck/driver/
  customer/delivery order (or supplier/PO for inbound materials) → first weigh (gross for outbound loaded,
  or tare) → second weigh → **net = gross − tare** → compare with ordered/invoiced quantity using product
  density/UoM conversion → tolerance check (configurable % / absolute) → out-of-tolerance creates an exception
  needing approval (separation of duties: the operator cannot approve own exception) → printable **weighbridge
  ticket** (sequential number, immutable once printed; corrections only via void + reissue with audit) → links
  to the delivery/trip and drives the stock movement quantity.
- Stored tare per vehicle with expiry (re-tare required after N days), and manual-entry flag vs device-captured.
- **Inbound weighing** for purchased materials (sand, cement, fuel deliveries) feeding the GRN quantity in
  `procurement`.
- **Weighbridge device SPI** (`WeighbridgeAdapter`): read stable weight from an indicator over serial/TCP
  (e.g. standard continuous-output ASCII protocols) — ship the interface, a configurable generic ASCII/TCP
  adapter, and a simulator adapter used by tests and the demo. Manual entry allowed only with permission
  `weighbridge:manual_entry` and always flagged on the ticket.

### 1.5 Fuel & energy
- Fuel logs per vehicle/equipment: litres, price, odometer/hours, station or **site fuel tank/bowser**,
  fuel card, receipt photo; efficiency (L/100 km, L/hour) with anomaly flags (outside configurable band,
  odometer not increasing, tank overfill).
- **Site fuel tanks**: fuel bought into an inventory location (via procurement GRN), issued to vehicles and
  equipment as stock issues (costed AVCO/FIFO per the org policy) with dip readings and reconciliation.
- GL: fuel expense posted to the vehicle's cost centre through the posting engine (external purchase = via
  expenses/AP; tank issue = stock issue to fuel expense).

### 1.6 Maintenance (fleet + mobile plant)
- Service schedules by km / engine hours / calendar interval (whichever first), templates per vehicle class.
- Preventive and breakdown **maintenance work orders**: reuse the `repair` module's application API if it fits
  (jobs, parts, labour) — otherwise build fleet work orders that issue spare parts from `inventory`, record
  labour (hr employee + hours + rate) and external workshop bills via `procurement`. Decide in the ADR.
- **Downtime** tracking (breakdown start/end, reason), availability %, MTBF/MTTR.
- Inspections / pre-trip checklists (DVIR): configurable checklist templates; failed items create defects →
  work orders; vehicle blocked from dispatch while a critical defect is open.

### 1.7 Tyres, incidents and fines
- Tyres by serial and position, fitment/rotation/removal, tread depth readings, cost per km.
- Incidents/accidents, damage, insurance claims; traffic fines charged to driver (salary deduction via `hr`) or
  company.

### 1.8 Telematics SPI
- `TelematicsProvider` SPI: positions, odometer/hours sync, ignition events, alerts. Mock provider for tests/
  demo; config placeholders in `.env.example`. Positions feed trip distance and proof-of-delivery location.

### 1.9 Costing, profitability & reports
- Cost per vehicle, per km, per engine hour, **per tonne delivered**, per trip; total cost of ownership
  (depreciation from fixedasset + fuel + maintenance + tyres + insurance + driver allowances + fines).
- **Delivery profitability**: revenue of delivered lines + transport charge − product cost − trip cost (own
  fleet) or freight bill (third party).
- Third-party transporter **freight bills**: generate/match supplier bills in `procurement` from completed trips;
  freight can be capitalised to inventory as landed cost (reuse ADR-S007 landed cost) when it is inbound.
- Reports/dashboards: fleet utilisation, availability/downtime, fuel report, upcoming renewals & services,
  deliveries completed / on-time %, tonnes delivered per truck/driver/customer/site, weighbridge discrepancy
  report, transport cost per tonne. CSV/XLSX/PDF export like existing reports.
- Scheduled jobs (ShedLock): expiry reminders (documents, licences, tare), service-due alerts — via the
  `communication`/notification services.

**Phase 1 exit**: every sub-module above has routes + screens + tests + spec, an end-to-end IT covering
*sales order → delivery order → dispatch → weighbridge ticket → stock issue → proof of delivery → invoice with
transport charge → trip cost → GL balanced*, demo seed (trucks, drivers, sites, a week of deliveries and
weighbridge tickets), build green, pushed, tagged `fleet-complete`.

---

## Phase 2 — Advanced Production / Manufacturing (enhance `manufacturing`)

Goal: an **advanced manufacturing system that can model any number of stages** and every manufacturing task this
client has — quarry extraction, primary/secondary crushing, screening into sized aggregates, concrete block /
paver / kerb manufacturing with curing — while staying generic enough for any discrete, batch or process
manufacturer.

**Start with an audit**: read `docs/spec/manufacturing.md`, ADR-S006, ADR-S008 and the `manufacturing`
services. Existing (do not rebuild — extend): BOMs + revisions + substitutes + engineering changes, routings,
work centres + calendars + closures + parallel slots, resources, work orders + time logs, production
requests/plans/orders (confirm, start, complete, QC, rework, cancel, reverse), soft reservations,
availability, time-phased MRP with planned orders, forward finite scheduling, QC plans + scrap reasons +
quarantine, standard costing with GL variances, process costing, variance + where-used reports, subcontracted
operations. Write an ADR (`ADR-S0NN-advanced-multistage-manufacturing.md`) with the extension design. **All
61 Nest-parity manufacturing routes and the ADR-S006/S008 routes keep their exact contract** — extensions are new
routes/fields only, and existing ITs must stay green.

Build and push each sub-module separately:

### 2.1 Multi-stage production & WIP between stages
- Routing operations become real **stages**: each stage can consume materials, produce an **intermediate
  (semi-finished) product** into a WIP/stage location, and feed the next stage; stage-level output, scrap,
  yield and cost; partial completion and backflush vs manual issue per stage.
- **Multi-level production**: a parent order can auto-create/link child orders for sub-assemblies/intermediates
  (from multi-level BOMs), with pegging and status roll-up.
- **Co-products and by-products** with cost-allocation rules (by quantity, by weight, by market value,
  by fixed %); by-products optionally at net realisable value; **waste/fines** as zero-value or saleable output.
- Stage gates with QC checkpoints (block next stage until passed).
- WIP valuation per stage and a WIP report reconciled to the GL WIP account.

### 2.2 Quarry & extraction management (new sub-area, may be its own module `quarry` if cleaner — decide in ADR)
- Quarries/sites, extraction zones/benches/faces, geology/material type & grade.
- Extraction plans and targets (per site/zone/period); **daily extraction logs** by shift, zone, operator,
  equipment (excavator/loader from `fleet`), loads × truck capacity or weighbridge tonnage; drilling & blasting
  records (holes, explosive quantities — explosives tracked as controlled stock with issue authorisation and
  full audit, compliance fields).
- Haulage from face to crusher (internal trips from `fleet`, with weighbridge or load counts).
- Raw stone received into a **ROM (run-of-mine) stockpile** location as inventory with cost: fuel, labour,
  equipment hours, blasting, royalties/levies allocated per tonne.
- Losses / unusable rock / overburden recorded; extraction vs target, cost per tonne by site and period.

### 2.3 Crushing, screening & aggregate processing
- Plant / crusher-line configuration (primary jaw, secondary cone, VSI, screens) as work centres with capacity
  in t/h and meters (engine hours/kWh).
- **Process production runs by shift**: input tonnes from ROM stockpile → outputs into sized stockpiles
  (e.g. 0–6 mm dust, 6 mm, 10 mm, 14 mm, 20 mm, 40 mm, base course, oversize recirculated) using an
  **expected yield/grading split** (configurable per plant & material) vs **actual** (weighbridge/belt scale/
  stockpile survey) → yield variance and loss.
- Consumption of diesel, electricity (kWh meter readings), wear parts, labour and maintenance per run; cost
  allocated to co-products per the allocation rule; cost per tonne by size.
- **Stockpile management**: stockpiles as inventory locations with periodic survey (volume × density =
  tonnes) and survey adjustments with approval.
- Integrates with process costing (ADR-S008) — extend, don't duplicate.

### 2.4 Concrete block / precast manufacturing
- Product specs: block type, dimensions, strength class (e.g. 7 N/mm²), weight, pallet qty.
- **Recipes / mix designs** (cement, sand, aggregate, water, additives) per batch/mixer cycle with **batch
  scaling** (e.g. 1,000 blocks → required cement bags, sand t, aggregate t), mix-design versioning.
- Production order → material availability check → issue/backflush → moulding → **curing stage** (curing
  location + minimum curing days; stock is not available for sale until curing release) → QC (compressive
  strength test results at 7/28 days, dimensions, breakage count) → release to finished goods or reject/
  downgrade (second-grade product) → pallet labelling (barcode/QR with batch).
- Breakages and rejects by reason; **cost per acceptable block**; labour, energy and machine hours allocation;
  material usage variance vs recipe.

### 2.5 Advanced planning & scheduling
- Production plans from **confirmed sales orders + delivery schedule (fleet) + forecast + stock policy**, with
  order-priority pegging ("make for contractor X's site delivery on date Y").
- **Capacity planning**: load vs capacity per work centre/plant/line (hours and t/h), bottleneck view,
  finite scheduling (existing forward scheduler extended with backward scheduling from due date), Gantt
  schedule screen with drag-to-reschedule (respecting calendars), shift plans.
- Shortfall report, production-vs-plan and plan-adherence.
- MRP considers multi-level, co-products, yields and curing lead time.

### 2.6 Quality management (generic, used by manufacturing, procurement and fleet)
- Specifications per product/grade (parameters with min/max/target and test method: grading/sieve analysis,
  moisture, compressive strength, dimensions, slump, etc.).
- Inspection plans and lots: **incoming** (on GRN — e.g. cement, sand), **in-process** (per stage),
  **final** (before release) — sampling, test results, pass/fail, certificate of analysis PDF.
- Hold/quarantine/release/reject/downgrade decisions with permissions and audit.
- **Nonconformance reports (NCR)** and **CAPA** (root cause, corrective/preventive actions, owners, due dates).
- **Lab SPI** for external laboratory result import (CSV + REST adapter).
- Full **batch traceability**: forward/backward (raw stone source/zone & cement lot → crushed batch/block batch
  → stockpile/pallet → delivery → customer/site), as a report and a screen.
- Quality reports by product, batch, line, supplier.

### 2.7 Shop-floor execution & data capture
- Tablet-friendly shop-floor screens: operator/shift login (existing auth), start/stop stage, record output/
  scrap/downtime with reason codes, meter readings, material issues; supervisor approval of shift reports.
- **Production data capture SPI** (`MachineDataAdapter`) for PLC/counters/belt scales (generic MQTT or HTTP push
  + simulator), feeding output counts and downtime events.
- OEE per line (availability × performance × quality).

### 2.8 Manufacturing costing & analytics
- Actual cost per batch/order/shift/plant: materials + labour + energy + equipment (from fleet cost per hour)
  + overhead absorption (configurable rates per work centre) → WIP → finished goods, through the posting engine.
- Variances (material price/usage, yield, labour efficiency, overhead) per order and summarised; extend
  ADR-S008 standard costing where needed.
- Reports: cost per tonne by size, cost per block, yield % (expected vs actual), material loss, production
  volume vs target, by shift/plant/product/period.

**Phase 2 exit**: end-to-end ITs for (a) *blast → extraction log → haul → ROM stockpile → crushing run with
co-products → sized stockpiles → cost per tonne → GL WIP/FG balanced*; (b) *block order → recipe scaling →
material issue → moulding → curing → strength QC → release + rejects → cost per acceptable block*;
(c) multi-level order with intermediate stage + traceability report. All prior manufacturing ITs green, screens
done, spec + ADR + `GAP_ANALYSIS.md` updated, demo seed (one quarry, one crusher line, one block plant, recipes,
a month of runs), pushed, tagged `manufacturing-advanced-complete`.

---

## Phase 3 — Requirements verification ("make sure all the functionalities are there")

1. Create `docs/requirements/GAP_ANALYSIS.md`: **one row per bullet** of
   `docs/requirements/construction-materials-erp.md` (sections 2.1–10): requirement · status
   (`present-verified` / `present-partial` / `missing`) · evidence (module, routes, screen path, test class) ·
   action.
2. Do **not** trust names. For each `present-*` row, prove it: find the route + screen, and either point to an
   existing passing test that covers it or add one (an IT per section is fine). Exercise the screen in the
   running web app.
3. Every `present-partial` and `missing` row becomes a Phase 4 item in `BUILD_LOG.md` (grouped by module).
4. Commit + push; tag `requirements-verified`.

## Phase 4 — Close the gaps

Build every Phase 3 item, one module at a time, full per-module loop, push after each. Expected (verify — some
may already exist): 
- **Units of measure** with **product-specific density** (t ↔ m³ ↔ truckload ↔ bag/piece) used consistently
  in sales, weighbridge, stock and invoicing (extend ADR-S003 product units).
- **Procurement**: purchase requisitions + approval, RFQ / supplier quotation comparison, approval limits by
  amount/department/branch, procurement budgets & spend control, supplier price agreements & price history,
  supplier performance (on-time, quality from inspections, price variance), purchase returns + supplier credit
  notes; controlled items (explosives) with licence fields and authorised issue.
- **Sales**: quotations + revisions + conversion tracking, order approval, price lists (wholesale/retail/
  contract/negotiated, customer-specific, per site/zone, per UoM), credit limits + credit hold + exposure
  (incl. undelivered orders), payment terms, mobile money as a payment method (provider SPI + mock),
  allocation of receipts, returns/refunds, salesperson commissions, customer statements. (Roadmap 1.5, 1.6.)
- **Branches / multi-location**: user access restricted by branch/location (data-level filter, enforced in
  services), branch pricing, inter-branch transfers with approval and in-transit, branch budgets, consolidated
  and comparative branch reports.
- **Finance**: budgets + budget vs actual by cost centre/branch/project, cash-flow statement, period close
  checklist + year-end (roadmap 1.7), debit notes, tax configuration/reporting (roadmap 1.2, generic pack).
- **Maintenance & asset management (CMMS)** for **fixed plant** (crushers, conveyors, screens, block machines,
  generators) — reuse the Phase 1.6 maintenance engine so trucks and plant share one maintenance system:
  equipment register by location, PM schedules by hours/calendar, work orders, spare parts, downtime, history,
  cost per equipment; links to `fixedasset` for depreciation/disposal.
- **HR**: shifts and rosters for quarry/plant crews, attendance (incl. shift production linkage), overtime,
  loans/advances, training & certifications (operator tickets, blasting licence), recruitment/onboarding,
  performance, payroll cost allocation by cost centre — verify existing `hr` first.
- **CRM**: leads/opportunities/pipeline, activities & reminders, complaints/service requests (tie to delivery
  discrepancies), segmentation, customer purchase history — verify existing `crm` first.
- **Controls**: approval hierarchies & transaction limits configurable, segregation-of-duties checks on
  sensitive pairs (weighbridge exception approval, stock adjustment, production loss write-off, credit
  override, supplier bill approval), audit trail on all of them, controlled reversals only.
- **Customer portal** (orders, delivery tracking with live trip status, statements, invoices) and **driver mobile
  web app** (PWA; trips, POD with signature/photo, fuel log, pre-trip checklist) — web only; do not touch the
  Android POS app.

## Phase 5 — Dashboards, KPIs, alerts & integrations

- Management dashboards implementing every KPI in requirements §10 (quarry, aggregates, blocks, inventory,
  procurement, sales, fleet & delivery, maintenance, finance, branch), filterable by date/branch/site/product/
  customer, with drill-down to documents, role-based visibility. Document each KPI formula in
  `docs/spec/analytics-kpis.md`.
- Automated alerts & exception reports (scheduled, ShedLock): stock below reorder, weighbridge exceptions,
  yield below threshold, curing release due, expiring documents/licences, overdue maintenance, credit-limit
  breaches, budget overruns — via `communication` (email/WhatsApp/in-app).
- Demand forecasting for aggregates/blocks feeding production plans (extend existing `ForecastService`).
- Integration SPIs consolidated and documented (`docs/integrations.md`): weighbridge, telematics, machine data,
  lab, mobile money, barcode/label printing (ZXing labels for pallets/stockpiles/tickets), BI export (read-only
  reporting views / CSV), bank formats (reuse roadmap 1.4 if built).

## Phase 6 — Production hardening

Apply `docs/phases/P8-hardening-release.md` and `AUTONOMOUS_BUILD.md` §6 to the **whole system**, with focus on
everything built in Phases 1–5: security review (OWASP top 10, authz matrix over all new routes, tenant
isolation, branch restrictions, rate limits), dependency CVE scan clean, indexes + N+1 checks, load test
(`tools/perf`) on dispatch board, weighbridge ticket, production run, stock and report routes (p95 < 300 ms on
list/detail with realistic volumes), backup/restore covering all new tables (restore drill documented),
observability (metrics for new jobs/SPIs, structured logs with correlation id), zero-downtime migration review,
Docker images build (`docker compose up -d --build`) and the full stack runs clean, CI green on `origin`.
**POS regression**: run every POS/sync test and the `reference/e2e` sell loop if runnable — must be unchanged.
Push; tag `v2.1.0-rc`.

## Phase 7 — Demo data, docs, final report

1. A **"Construction materials" demo org** in `SeedRunner` (opt-in profile/flag, never mixed into the POS demo):
   2 branches, 1 quarry, 1 crusher line, 1 block plant, stockyards, products with UoM/density, recipes,
   10 trucks + 3 loaders/excavators, drivers, 20 customers with sites, 2 transporters, 3 months of sales,
   deliveries, weighbridge tickets, production runs, fuel, maintenance — all GL-balanced.
2. User guide `docs/guides/construction-materials.md` (workflow per role: weighbridge operator, dispatcher,
   driver, plant supervisor, quality officer, storekeeper, accountant, manager) and admin/config guide.
3. `docs/reports/CONSTRUCTION_ERP_REPORT.md`: what was built per phase (routes, screens, migrations,
   permissions, jobs, tests, ADRs, commits), the final `GAP_ANALYSIS.md` (target: every row
   `present-verified`), metrics (routes, tests, coverage, perf, CVE scan), autonomous decisions for owner review,
   known limitations / stubs needing real hardware or credentials (weighbridge model, telematics vendor, mobile
   money provider, lab), deployment/upgrade guide (new env vars, `ENABLE_*` flags, licence modules, migration
   order).
4. Release notes `docs/releases/v2.1.0.md`; commit, push, tag `v2.1.0`.
5. Set the last line of `docs/BUILD_LOG.md` to exactly: `STATUS: CONSTRUCTION ERP COMPLETE`, commit, push.

---

## 8. Decision defaults (do not block on these)

- Missing hardware/vendor/credentials: build the SPI + a simulator/mock + config placeholders; log under
  "Needs owner input" in the final report. Never block.
- Units: metric (t, kg, m³, L, km, engine hours). Currency/tax: org settings (existing).
- Costing: org policy (AVCO default, FIFO selectable — ADR-S007); production at actual cost unless an active
  standard exists (ADR-S008).
- When an existing module almost fits, **extend it** (additively) rather than creating a parallel model —
  except POS, which is frozen.
- When Nest-parity routes and a new need conflict, keep the parity route and add a new one.
- Truly impossible (destructive action on production data, legal question): log under `Blocked` in
  `BUILD_LOG.md`, skip to the next independent item, continue.

## 9. `BUILD_LOG.md` — Track C section (append on first run, keep current)

```markdown
## Track C — Construction materials (brief: docs/AUTONOMOUS_BUILD_CONSTRUCTION.md)
Current: <phase>.<item> <name> — <status>
Next action: <one concrete next step>

| Phase.Item | Status | Commits | Tag | Notes |
|---|---|---|---|---|
| 0 state audit | todo | | | |
| 1.1 vehicle & equipment registry | todo | | | |
| 1.2 drivers & operators | todo | | | |
| 1.3 trips, delivery planning & dispatch | todo | | | |
| 1.4 weighbridge | todo | | | |
| 1.5 fuel & energy | todo | | | |
| 1.6 maintenance | todo | | | |
| 1.7 tyres, incidents, fines | todo | | | |
| 1.8 telematics SPI | todo | | | |
| 1.9 fleet costing & reports | todo | | fleet-complete | |
| 2.1 multi-stage production & WIP | todo | | | |
| 2.2 quarry & extraction | todo | | | |
| 2.3 crushing & screening | todo | | | |
| 2.4 block / precast manufacturing | todo | | | |
| 2.5 advanced planning & scheduling | todo | | | |
| 2.6 quality management | todo | | | |
| 2.7 shop-floor execution & data capture | todo | | | |
| 2.8 manufacturing costing & analytics | todo | | manufacturing-advanced-complete | |
| 3 requirements verification | todo | | requirements-verified | |
| 4.x gaps (one row per module, added in phase 3) | todo | | gaps-closed | |
| 5 dashboards, KPIs, alerts, integrations | todo | | bi-complete | |
| 6 production hardening | todo | | v2.1.0-rc | |
| 7 demo, docs, final report | todo | | v2.1.0 | |
```

## 10. How to run this brief

Paste into a new AI session (Claude Code, Codex, or similar) at the repo root:

> Read `docs/AUTONOMOUS_BUILD_CONSTRUCTION.md` and follow it exactly. Resume from the Track C section of
> `docs/BUILD_LOG.md`. Work autonomously phase by phase — Fleet first, then Advanced Manufacturing, then
> requirements verification, gaps, BI, hardening and the final report — committing and pushing `main` after every
> module and tagging every phase. Never modify the POS. Do not stop or ask for approval until `BUILD_LOG.md` ends
> with `STATUS: CONSTRUCTION ERP COMPLETE`.

Unattended across usage limits (Git Bash, repo root; run on an isolated dev machine/VM — this grants the agent
full permissions and push access):

```bash
until grep -q "STATUS: CONSTRUCTION ERP COMPLETE" docs/BUILD_LOG.md 2>/dev/null; do
  claude -p "Read docs/AUTONOMOUS_BUILD_CONSTRUCTION.md and follow it exactly. Resume from the Track C section of docs/BUILD_LOG.md. Never modify the POS. Do not stop until STATUS: CONSTRUCTION ERP COMPLETE." \
    --permission-mode bypassPermissions --max-turns 500 || sleep 1800
  git pull --rebase origin main
done
```
