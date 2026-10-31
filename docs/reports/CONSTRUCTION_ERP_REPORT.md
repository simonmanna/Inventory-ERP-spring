# Construction Materials ERP: final report

**Status: code complete, not verified.** The last full build that passed is commit `bd3f34b` (949 tests, 0 failed). The
100 commits after it (about 72,000 added lines) were pushed on the owner's instruction without being compiled or run:
that covers 2.3, 2.7, 2.5, 2.8 and Phases 3–7. No test written after that commit has ever run on main. Treat
everything below as written, not working, until `docs/VERIFY_CHECKLIST.md` has been followed.

The release tags and the `STATUS: CONSTRUCTION ERP COMPLETE` line are deliberately not set. See "Before release".

## Before release

1. **Fix the critical security finding.** Any signed-in user can call `POST /organizations/users/invite`, which accepts
   any `roleId` and returns the invite token, so a cashier can create an Administrator. The same gap covers user
   deactivation, organization settings, feature flags, module enable, all `/webhooks/endpoints` (any URL, no SSRF
   guard) and `/reports/export/*` (trial balance). It was already in the code. It was left because existing routes must
   keep their contract. The fix is one `@RequirePermissions` per route plus an organization check on the role. This is
   the owner's decision and should be made before go-live.
2. **Run the first full build** (GitHub CI runs it on every push to `main`: see the Actions tab, or use the run-book).
   The first-build triage section of `docs/VERIFY_CHECKLIST.md` lists the likeliest failures. The JaCoCo 80 % gate may
   fail on the new `alerts`, `biexport` and `analytics` packages.
3. **Then tag.** `.github/workflows/release.yml` runs on any `v*.*.*` tag, including `v2.1.0-rc`: it publishes Docker
   images as `latest` and creates a GitHub release. That is why no release tag was pushed. Once the build is green,
   create the held tags: `manufacturing-advanced-complete`, `gaps-closed`, `bi-complete`, `v2.1.0-rc`, `v2.1.0`, then
   set the last line of `docs/BUILD_LOG.md`.
4. **Do not migrate a shared database in steps.** Numbers arrived out of order (V40–V120). Migrate from V31 in one go
   after the whole set is on the branch being deployed.

## What was built

| Phase | Result | Routes added | Migrations | State |
|---|---|---:|---|---|
| 0 audit | baseline 669 tests | | | tested |
| 1 fleet | registry, drivers, dispatch, weighbridge, fuel, maintenance, tyres, incidents, telematics, costing | ~300 | V15–V16, V18–V25 | tested, tag `fleet-complete` |
| 2.1 multi-stage production | stages, WIP, co-products, gates, multi-level orders | 18 | V26 | tested |
| 2.2 quarry | sites, extraction, ROM stock, haulage, blasting, explosives | 80 | V28 | tested |
| 2.4 precast | mix designs, pressing, curing, strength gate, cost per block | 26 | V29 | tested |
| 2.6 quality | specs, lots, NCR/CAPA, lab import, CoA, traceability | 45 | V27 | tested |
| 2.3 crushing | plants, runs, stockpiles, surveys | 36 | V31 | branch tested only (953) |
| 2.7 shop floor | terminal, downtime, shift reports, machine data, OEE | 41 | V30 | branch tested only (934) |
| 3 gap analysis | 263 requirements | | | by reading, tag `requirements-verified` |
| 4.A manufacturing | 2.5 planning, 2.8 costing, end-to-end tests, seed | 33 | V40–V41 | never run |
| 4.B controls | approval limits, SoD, budgets, delegation, order approval, numbering | 25 | V50–V52 | never run |
| 4.C sales, branches | units and density, credit, branch scope, price lists, commissions | 32 | V60–V64 | never run |
| 4.D finance, procurement | vendor bills, AP, bank rec, tax pack, RFQ, price agreements | 27 | V70–V74 | never run |
| 4.E field, integrations | fixed-plant CMMS, driver PWA, portal, mobile money, telematics, labels | 38 | V80–V82 | never run |
| 4.F people, stock | recruitment, contracts, overtime, payroll allocation, CRM cases, replenishment | 73 | V90–V97 | never run |
| 5 BI | 10 dashboards, alerts, forecast, BI export, integrations guide | 31 | V100, V110–V111 | never run |
| 6 hardening | review, 7 defects fixed, 14 indexes, metrics, load scripts, run-book | 0 | V120 | never run |
| 7 demo, guides | `CMDEMO` company, role and admin guides | 0 | | never run |

Decisions behind each piece: ADR-S009 to S021 and S050 in `docs/adr/`.

## Metrics

2,166 route mappings, 60 migrations, 187 test classes (111 new since Phase 0), 247 new web page files, 170 commits
since Phase 0, 532 permissions. These are counts of source, not of working behaviour.

## Requirements coverage

`docs/requirements/GAP_ANALYSIS.md`, 263 rows: **164 verified by reading** (route, service, screen and an existing test
all found) and **99 built, unverified**. No row is open, but "covered" here means code exists. Several rows closed with
known limits, listed next.

## Known limits

- **POS:** no POS, sync, Android, `reference/`, V1 or V2 file changed against `9c82a91`. Nine shared classes POS imports
  did change; four of them (`ExpensesService`, `StockReservationService`, `DocumentBuilderService`, `DomainEvents`)
  had their deletions left unread. Run the POS commands in the checklist.
- **Deployment change that also reaches POS:** `docker-compose.yml` now sets `SERVER_FORWARD_HEADERS_STRATEGY=native`.
  Keep the API behind nginx; exposed directly, clients could spoof their address.
- **Controls off by default:** separation-of-duties pairs other than the weighbridge do nothing until switched on per
  organization. Alerts need `ENABLE_ALERTS` and `VITE_ENABLE_ALERTS`. Analytics are Administrator-only until roles get
  the permissions.
- **Branch scope** covers inventory, stock documents, orders, invoices and deliveries only; a full audit was not done.
  Restricted users see nothing without a branch, so assign locations first. POS billing skips the order approval gate.
- **Row-level security:** no migration uses `FORCE ROW LEVEL SECURITY`, so RLS does nothing if the app role owns the
  tables. Which role production uses is unverified. BI logins can change `app.org_id`.
- **Behaviour that changed:** `/mrp/runs` now applies yield, curing time and co-products; bill JSON now shows
  `createdBy`; `/inventory/locations` JSON gains `branchId`.
- **Not done:** dependency and CVE review, a 1M-row perf seed, exercising the 401/403 matrix, native review of the
  ti/lg/sw translations, web unit tests, any click-through in a browser.
- **Functional gaps:** portal is read-only; replenishment ignores open purchase orders; credit notes don't claw back
  commission; a stage has one quality lot; a gate blocks all lots of a stage; labour in costing uses rates, not payroll
  actuals; the manufacturing seed resets posting rules (demo orgs only).

## Needs real hardware, vendors or credentials

Weighbridge model, telematics vendor, machine data gateway, laboratory API, mobile money provider (the mock refuses
under `prod`), bank formats. Each has an SPI, a simulator or mock and config placeholders: `docs/integrations.md`.

## Decisions for owner review

`docs/DECISIONS_FOR_OWNER.md` holds every decision taken without asking. Read at least: the critical security finding
above, KPI formula choices, the downgrade loss account, overtime rounding, and credit overrides.

## Deployment and upgrade

- Env and flags: `.env.example`. New flags include `ENABLE_FLEET`, `ENABLE_QUARRY`, `ENABLE_PORTAL`, `ENABLE_ALERTS`;
  demo seeds `SEED_FLEET_DEMO`, `SEED_MANUFACTURING_DEMO`, `SEED_CONSTRUCTION_DEMO` are off by default.
- Licence: `fleet` and `quarry` features (licence-server V4). Licence-server ITs need Docker.
- Two open-items indexes are a manual `CONCURRENTLY` step: `docs/OPERATIONS.md` §4a.
- Guides: `docs/guides/construction-materials.md`, `docs/guides/construction-admin.md`.
- Run-book: `docs/VERIFY_CHECKLIST.md`.
