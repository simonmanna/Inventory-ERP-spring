# Build log
Current: Tier 0 complete except parity runs / Nest retirement; parallel sessions on Tier 1 (A) and Tier 2/4 (B)
Next action: session B — merge the Tier 4 fleet and Tier 2 (2.6, 2.8, 2.9, 2.10) worktree branches as they finish.

## Parallel sessions — read before picking an item

Two autonomous sessions push to `main`. To avoid duplicate work, each claims items here **before** starting them
(commit + push the claim). Do not start an item claimed by the other session; rebase often (`git pull --rebase`).

| Session | Who / where | Claimed items |
|---|---|---|
| A | owner's session (pushed 0.1 B/C/D, 1.8, ADR-S007/S008) | roadmap order: Tier 1 (1.1 → 1.7), then 2.4, 2.1, 2.2, 2.3, 2.5 |
| B | MacBook session (pushed 0.2 repair, 0.3 rental hardening) | **4 fleet, 2.6 BI, 2.8 subscriptions, 2.9 projects, 2.10 portals & payments**; then 2.7, 3.x from the end |

Migrations: session B renumbers its migrations to the next free `V{n}` at merge time, so numbers may jump.

## Environment notes (session B dev box)

- 4 GB MacBook, macOS 12, no running Docker daemon. Builds are serialised (one Gradle daemon at a time).
- Integration tests run **without Docker** against a local Postgres 16.15 (port 5436, `max_locks_per_transaction=1024`):
  `IT_EXTERNAL_DB=true JDBC_DATABASE_URL=jdbc:postgresql://localhost:5436/<db>?stringtype=unspecified DB_USER=postgres
  DB_PASSWORD=postgres ./gradlew test` — `TestcontainersConfiguration` skips the container when `IT_EXTERNAL_DB=true`.
  CI (`.github/workflows/ci.yml`) still runs the Testcontainers suite on every push.

## Items
| Item | Status | Commits | Notes |
|---|---|---|---|
| 0.1 manufacturing | done | 6494df0 4dd311f 3a5d904 d34f3f0 | session A: ADR-S006 packages B, C, D + web screens |
| 0.3 rental | done | 7b3e3af 8ea9a0e | 47/47; `RentalIT` + `RentalLifecycleIT` + `RentalMathTest`; money routes idempotent (deviation) |
| 0.2 repair | done | c5e0355 | 64/64; `RepairIT` (21) incl. idempotency, GL balance, row locks, tenant guards |
| 0.4 communication | done | 40d2b11 | re-verified green in the full suite (669 tests) |
| 0.x parity harness run | blocked | | needs a live Nest + Postgres (Docker); owner machine |
| 0.x Nest retirement | todo | | after the parity run |
| 1.8 landed costing & cost centres | done | ac30fd5 7f4ccc6 5a60daa | session A, ADR-S007 / ADR-S008 |
| 1.1 – 1.7 | session A | | not started by session B |
| 2.4, 2.1, 2.2, 2.3, 2.5 | session A | | not started by session B |
| 4 fleet | in-progress (C) | | taken over by Track C on 2026-10-28; session B work never pushed (see Track C 1.1–1.9) |
| 2.6 BI & dashboards | todo | | released by B 2026-10-28 (superseded by Track C) |
| 2.8 subscriptions & deferrals | claimed (C) | | released by B 2026-10-28 |
| 2.9 projects & service billing | todo | | released by B 2026-10-28 |
| 2.10 portals & payment gateways | todo | | released by B 2026-10-28 |
| 2.7 intercompany & multi-company | claimed (C) | | |
| 2.x UI i18n | todo | | |
| 2.x e-commerce connector | todo | | |
| 3.3 scale & data lifecycle | todo | | |
| 3.1 advanced costing | todo | | partly covered by ADR-S008 (standard + process costing) |
| 3.2 IFRS 15 / IFRS 16 | todo | | |
| 3.4 governance, risk & compliance | todo | | |
| 3.5 treasury | todo | | |
| 3.6 AI features | todo | | |
| 3.7 ecosystem | todo | | |
| hardening pass | todo | | known: POS sales-summary by trading date returns 0 between 00:00 and 06:00 UTC (`PosSaleIT.reports`, `PosLakeviewSimIT.tradingDaysTieOut` fail in that window) |
| final report | todo | | |

## Blocked
- 0.x parity harness: needs Nest + Postgres running side by side (Docker); not possible on the session B box.

## Session history
- 2026-10-28 session B1: state audit (`compileJava` + `compileTestJava` green on ef77292); Docker-free IT switch;
  baseline 617 tests / 2 failed. Rental + repair verified and hardened by agents; rebased onto session A's 12 commits
  (kept both rental suites); full suite 669 tests, failures only the 00:00–06:00 UTC POS window + 2 rental tests fixed.

## Track C — Construction materials (brief: docs/AUTONOMOUS_BUILD_CONSTRUCTION.md)
Current: all phases written and pushed; nothing after bd3f34b has been compiled or run
Next action: owner decision on the critical permission gap, first full build, then the held tags and the final status line (docs/reports/CONSTRUCTION_ERP_REPORT.md, "Before release").

Track C runs on the session B box (4 GB MacBook, Docker-free ITs on local Postgres 16, see "Environment notes").
Multi-agent: one agent per sub-module. Up to two run at once: one in the main checkout, one in a git worktree
(`~/Documents/erp-wt/c-<item>`, branch `wt/c-<item>`, migrations numbered V9xx until merged). The coordinator merges,
renumbers migrations to the next free `V{n}`, checks the POS paths are untouched, runs the full build, pushes, logs.
Session B's unpushed Tier 2 claims (2.6, 2.8, 2.9, 2.10) are released — they are not part of Track C.

| Phase.Item | Status | Commits | Tag | Notes |
|---|---|---|---|---|
| 0 state audit | done | | | baseline on d675ced: `./gradlew build` green, 669 tests / 0 failed (incl. POS trading-date tests after 9c82a91 — fix confirmed); no `fleet` package or remote branch on origin |
| 1.1 vehicle & equipment registry | done | ebae379 a7aee65 3765aea | | fleet module + flag/licence/menu, ADR-S009, spec, V15 (7 tables, all 53 Phase 1 permissions), 32 routes, 6 screens, FleetPoliciesTest + FleetRegistryIT (14); full build 694 tests / 0 failed; license-server ITs need Docker |
| 1.2 drivers & operators | done | c79aece 8daa231 9c3b53f | | V16 (9 fleet tables + hr `HrPayrollInput`), 37 routes, 9 screens + vehicle tab; eligibility policy, assignments, check-out/in with meters + photos, allowance rules, claims → expenses (Dr 6440 cc / Cr bank) or payroll (Dr 6440 / Cr 2185, settled by the run); additive hr `payroll-inputs` API + `ExpensesService.createPaidFromSource`; full build 719 tests / 0 failed |
| 1.3 trips, delivery planning & dispatch | done | 90d00ea ce56add 7b65e6c 76c5619 | | V22, 55 routes, FleetDispatchIT (9) incl. stock issue once + concurrency, POD, discrepancy → credit note, transport charge |
| 1.4 weighbridge | done | d29d681 0736c5e 0ffba5f 03b7a9b e8d4ff8 dc9ce2e | | 1.4a (V23, 36 routes) + 1.4b (V24, 6 routes): ticket completion feeds the trip, drives the stock issue; void before dispatch only; dispatch rule; FleetPhase1EndToEndIT (3) GL-balanced |
| 1.5 fuel & energy | done | c778199 c1cf1b8 53b0ab0 | | V19, 34 routes, FleetFuelIT + FuelPoliciesTest; merged with the 1.6/upstream CRM merge |
| 1.6 maintenance | done | 8d23b3e 50e1954 ea4379e | | V18, 51 routes, FleetMaintenanceIT; dispatch check `FleetDispatchEligibilityService` |
| 1.7 tyres, incidents, fines | done | a1c77e0 78da0fe f75e013 | | V20, tyres/incidents/insurance claims/fines; driver fine deductions = events + manual status until wired to hr in 1.9 |
| 1.8 telematics SPI | done | a1c77e0 | | V21, mock provider, positions/distance API for 1.3 |
| 1.9 fleet costing & reports | done | 0c4b8e3 37ba653 a538e61 | fleet-complete | V25, 22 routes, TCO / delivery profitability / freight bills (once, GL balanced) / 8 reports with CSV-XLSX-PDF / ShedLock reminders / fine → payroll deduction / opt-in demo seed (`SEED_FLEET_DEMO`) |
| 2.1 multi-stage production & WIP | done (C) | 8052424, dc2be71, 23b4c96 | | V26, ADR-S011; 18 routes, stage QC SPI for 2.6; branch build 894 tests green |
| 2.2 quarry & extraction | done (C) | 91d1c52, 779bc31, 4e7c775 | | V28 + licence V4, ADR-S012; 80 routes, 19 permissions, ROM stockpile API for 2.3; main build 937 tests green (licence-server ITs need Docker) |
| 2.3 crushing & screening | merged, not verified on main (C) | b5164dd, 01094db, f7a5a94 | | V31, ADR-S016; 36 routes; branch build 953 tests green; pushed on the user's instruction without a full build of the merged tree |
| 2.4 block / precast manufacturing | done (C) | 6b43ea8, 746589f, 3ae57df | | V29, ADR-S014; 26 routes under /manufacturing/precast, curing hold on 2.1 gates; main build 949 tests green |
| 2.5 advanced planning & scheduling | claimed (C) | | | |
| 2.6 quality management | done (C) | c6b3f68, 608208a, 554c872 | | V27, ADR-S013; 45 routes; wired to 2.1 stage gates (QualityStageGateBridge); main build 917 tests green |
| 2.7 shop-floor execution & data capture | merged, not verified on main (C) | 42dd59c, de1d082, 82f719e | | V30, ADR-S015; 41 routes; branch build 934 tests green; pushed on the user's instruction without a full build of the merged tree |
| 2.8 manufacturing costing & analytics | claimed (C) | | manufacturing-advanced-complete | |
| 3 requirements verification | done (C) | 66219a2 | requirements-verified | 263 rows: 166 verified, 82 partial, 15 missing. By reading code only; nothing compiled or run |
| 4.A manufacturing: MFG-1 2.5 planning, MFG-2 2.8 costing, MFG-3 E2E ITs, MFG-4 demo seed | merged, unverified (C) | a37abae..a741588 | manufacturing-advanced-complete not created until tested | V40–V41; 33 routes; never compiled or run. /mrp/runs quantities change for yield, curing and co-products |
| 4.B controls: CTRL-1, CTRL-2, PROC-3, PROC-5, SALES-2 | merged, unverified (C) | 7223c0a..4750344 | | V50–V52; 25 routes; never compiled or run. SoD pairs are off per tenant until switched on; supplier-bill hook not yet called by lane D code |
| 4.C sales & branches: UOM-1, SALES-1, BR-1, BR-2, SALES-3, -4, -5 | merged, unverified (C) | bcfe891..8b4cffb | | V60–V64; ADR-S017; 32 routes; never compiled or run. Branch scope covers inventory, stock documents, orders, invoices, deliveries only |
| 4.D finance & procurement: FIN-1, -2, -3, PROC-1, -2, -4, -6 | merged, unverified (C) | d43915d..d280613 | | V70–V74; 27 routes; never compiled or run. Do not migrate a shared DB until every lane is merged: lanes use number blocks V40–V99 that merge out of order |
| 4.E plant, field & integrations: CMMS-1, DRIVER-1, PORTAL-1, INT-1, -2, -3 | merged, unverified (C) | f757092..fbec71a | | V80–V82; ADR-S050; 38 routes; never compiled or run. Portal read-only; mobile money mock only |
| 4.F HR, CRM, inventory: HR-1..5, CRM-1, -2, INV-1..3 | merged, unverified (C) | c84fc26..810f3f7 | | V90–V97; 73 routes; never compiled or run |
| 4.x sales O2C (ADR-S011) | S0–S10 built; merged with PR #7 (migrations V200–V210) | | | Step 0 docs on `phase/S0-sales-docs` (ADR-S011, `PRODUCT_ARCHITECTURE.md`, `spec/sales.md`, `spec/sales-postings.md`, `spec/credit.md`); S0 code waits for owner review; each phase S0–S10 gated by owner go-ahead. Covers ROADMAP 1.5 / 1.6. Do not touch `manufacturing/**` |
| 4 all gaps closed | code merged, unverified (C) | | tag gaps-closed held until a full build passes | |
| 5 dashboards, KPIs, alerts, integrations | code merged, unverified (C) | 3b951a1..7d9c326, 9968efc..4d36fda | tag bi-complete held until a full build passes | V100, V110–V111; ADR-S020, ADR-S021; 31 routes |
| 6 production hardening | code review merged, unverified (C) | 6326250..7eec4c7 | tag v2.1.0-rc held: release.yml publishes images on v*.*.* | V120; 7 defects fixed; critical open finding: routes with no permission check, see DECISIONS_FOR_OWNER |
| 7 demo, docs, final report | written, unverified (C) | a3cd947, 02a18eb, 1d8ff9a | tag v2.1.0 held: release.yml publishes images | report in docs/reports/CONSTRUCTION_ERP_REPORT.md; never compiled or run |

### Phase 1 summary (fleet-complete, 2026-10-30)
Module `fleet` (ADR-S009, flag `ENABLE_FLEET`, licence feature `fleet`), migrations V15–V16 and V18–V25 (V17 is the
owner's CRM), ~300 routes, 55 permissions, screens under `/fleet`. Built by 9 agents in parallel worktrees, merged
here. Covers: vehicle & equipment registry, drivers/operators + check-out/in + allowance claims (expenses / payroll),
delivery orders from sales orders → load planning → dispatch board → trips → delivery note → POD → discrepancies →
credit-note requests → transport charges on invoices, weighbridge (ASCII/TCP + simulator adapters, SoD exceptions,
immutable tickets, inbound → GRN, ticket drives the stock issue), fuel (logs, cards, site tanks, dips, anomalies),
fleet-owned maintenance (schedules, work orders, downtime, MTBF/MTTR, DVIR → defects → dispatch block), tyres,
incidents, insurance claims, fines (company expense or payroll deduction), telematics SPI + mock, costing / TCO /
delivery profitability / freight bills / reports / reminders. `FleetPhase1EndToEndIT` proves sales order → … →
balanced GL. Full build: 867 tests / 0 failed. Owner review: GL account proposals and other choices in
`docs/DECISIONS_FOR_OWNER.md`; click-through steps in `docs/VERIFY_CHECKLIST.md`. Known limits: one ticket per trip,
own-fleet trip cost analytic only, telematics mock only, no vendor-binary weighbridge protocols.

STATUS: CODE WRITTEN, NOT COMPILED OR RUN. Held until a full build passes: tags manufacturing-advanced-complete, gaps-closed, bi-complete, v2.1.0-rc, v2.1.0.

### Source-to-Pay build (ADR-PROCUREMENT-001, 2026-10-30)

Owner instruction: build P0–P5 of `docs/roadmap/procurement-roadmap.md` as one production-ready purchasing system.
Supplier portal = secure link per document; AI = built-in statistics; open decisions = proposed defaults. With no
policy configured, behaviour stays as before.

| Wave | Status | Commit | Tests (full build) | Notes |
|---|---|---|---|---|
| W0 baseline | done | — | 867 | local Postgres 16, `IT_EXTERNAL_DB=true` |
| W1 P0 integrity (V150) | done | (see git log) | 877 | receipt voucher at actual value, delete / pay guards, debit-note claim, BigDecimal three-way match, FX, org checks |
| W2 policies + suppliers (V151) | done | | 885 | maker-checker effective-dated policies, supplier lifecycle, dual-control bank accounts, certificates, price history |
| W3 requisition → order (V152) | done | eddd37c | 892 | workbench, PO lifecycle, short-close, print |
| W4 sourcing (V153) | done | eddd37c | 892 | RFQ, quotations, comparison, split award, agreements and releases |
| W5 controls (V154) | done | 2d6a87b | 898 | commitments and budget control, PO change requests and revisions, match tolerances, duplicate bills, claims |
| W6 receiving quality (V155) | done | d0b34c3 | 902 | inspection, weighbridge evidence SPI, emergency purchases |
| W7 planning (V156) | done | 7a7663a | 906 | replenishment, scorecards, statistics, supplier portal, dashboard |
| W8 hardening | done | (this branch) | 907 | end-to-end IT, docs, guide, self-review |

Deferred (tracked in the roadmap):
- landed-cost gaps G1–G8 beyond V12–V25
- `goods_in_transit`
- direct-to-site project acceptance
- `asset.acquired` hand-off
- density-based t ↔ m³ conversion
- parity scenario JSON for the new routes (extension routes have no Nest counterpart to diff)

Known limits after W8 (self-review):
- The portal throttle is per client IP. Behind a reverse proxy, configure forwarded-IP handling, or all suppliers
  share one bucket.
- `POST /procurement/replenishment/raise` has no idempotency key. A double submit raises two draft requests, which
  are visible and cancellable.
- An order activated outside a transaction re-states its commitments right after the status change. Every later
  change re-syncs them.

### Production-ready plan (owner instruction 2026-10-31)

Plan and per-phase notes: `docs/roadmap/production-ready-plan.md`.

| Phase | Status | Tests (full build) | Notes |
|---|---|---|---|
| PR-0 green CI | done | 1,379 / 0 failed | test isolation (outbox drain, shared shift), critical permission gap closed, i18n gate, lazy web pages |
| PR-1 part 1 hardening | done | 1,392 / 1 failed (async-timing flake in `SalesFulfilmentIT`, fixed; class green on rerun) | outbox dead letter, RLS enforced (whole IT suite as non-owner role), CVEs, whole-app authz matrix, incoming supply |
| PR-2 year-end close | done | same run as PR-1 | ADR-S023, V216 |
| PR-3 analytic dimensions | done | see commit | ADR-S025, V217 |
| PR-1 part 2 release checks | done (retirement: owner) | 1,466 / 0 failed; JaCoCo gate 0.80, every application package ≥ 80 % | parity 584/96 all classified, click-through 409 routes 0 fail, checkout p95 140–176 ms (dev box) |
| PR-4 warehouse management | done | 1,475 / 0 failed; JaCoCo gate 0.80 passes | ADR-S026, V218; 33 routes under `/warehouse`, web `/inventory/warehouse` |
