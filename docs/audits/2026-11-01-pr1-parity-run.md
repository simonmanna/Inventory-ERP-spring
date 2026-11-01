# PR-1 part 2 — full parity run (all 24 scenarios)

> **Date:** 2026-11-01 · **Branch:** `main` · Follows [2026-10-27-phase-a-parity-run.md](2026-10-27-phase-a-parity-run.md).
>
> Setup: Nest reference (`reference/nest-api`, built copy inside a POS-CAFE workspace) on `127.0.0.1:3000`, `TZ=UTC`,
> against `pr1_nest`; Spring (`bootRun`, working tree, `THROTTLE_ENABLED=false`) on `127.0.0.1:3011` against
> `pr1_spring`; both databases from Prisma `migrate deploy` + the Nest seed; same `JWT_*` secrets and the same
> `ENABLE_*` flags (beverage, assets, tasks, manufacturing, rental, repair, hr, orders, communication) on both sides.
> Runner: `contract-tests/run-all.sh` (new).

## 1. Result

| Run | ✓ | ✗ | Notes |
|---|---|---|---|
| 1 — one pass, Spring module flags off | 348 | 332 | Invalid: module routes 404 on Spring, Spring login throttle |
| 2 — flags on, one pass | 169 | 511 | Invalid: Nest throttles the run (100 req/min, 10 logins / 5 min per IP) |
| 3 — batched (`run-all.sh`), Nest restarted per batch | 567 | 113 | Host time zone and `localhost` → `::1` artefacts |
| 4 — `TZ=UTC`, `127.0.0.1` | 576 | 104 | Triage below |
| **5 — S6–S9 fixed, harness H5** | **584** | **96** | **Zero unexplained diffs** — every remaining one is in §4 |

Business behaviour (amounts, GL lines, stock, statuses, business-rule messages) matched on every step that is not a
documented deviation.

## 2. Harness changes

| # | Problem | Fix |
|---|---|---|
| H5 | Maps keyed by row id (`meta.categoryCounts`) never match: ids differ per database | `normalize()` compares UUID-keyed entries as a sorted list |
| H6 | Nest throttles a full run (all 24 files log in; also 100 req/min/IP for anonymous traffic) | `run-all.sh`: batches of ≤ 8 logins, Nest restarted before each batch, `THROTTLE_STAFF_MULTIPLIER=1000` |
| H7 | Nest formats business dates in the host time zone; Node resolves `localhost` to `::1` for one side only | Nest runs with `TZ=UTC` (as in the container); both sides on `127.0.0.1` |

## 3. Spring parity bugs fixed (Nest wins)

| # | Diff | Fix |
|---|---|---|
| S6 | A missing required string with `@MaxLength` / `@MinLength` / `@Length` lacked the length message (class-validator fails those on `undefined`; `@Size` passes `null`) | New `shared.validation.MaxLength` / `MinLength` / `Length` (value-dependent message like class-validator, `nullable` for `@IsOptional`); 35 constraints on required components switched (communication, hr, pos, roles) |
| S7 | `POST /auth/login` without `organizationCode` / `password` lacked `must be a string` | `@NotNull("must be a string")` added |
| S8 | Manufacturing `@IsNotEmpty` modelled as `@Size(min = 1)`: no `should not be empty` for a missing value | 15 components → `@NotEmpty` |
| S9 | `GET /reports/access/catalog` sent `"description": null` / `"tab": null`; Nest leaves undefined fields out | `@JsonInclude(NON_NULL)` on the optional `Report` components |

## 4. Remaining diffs (96 steps), all classified

| Class | Steps | What | Status |
|---|---|---|---|
| A1 | 29 | `permissions` on login / me / refresh / roles and the permission catalog: Spring is a strict superset (653 Nest permissions all present, +229 from approved ADRs); stored role arrays were re-ordered by those migrations | Additive, approved ADRs |
| D1 | 25 | `units` on products, `uomId` / `uomFactor` on PO / GRN / debit-note lines (ADR-S003, V5) | **Pending owner** (`DECISIONS_FOR_OWNER.md` S2P OD-6) |
| R | 12 | Rental 201 → 200 on money routes, Nest 500s on score / settle and their effects (return numbers, settled agreement) | Documented, `docs/spec/rental.md` Parity |
| A3 | 8 | Procurement: PO pay limited to posted bills (P0-4), inbound debit note posts (B1), GRNI by posted receipts, landed-cost allocation fields | Documented, `docs/spec/procurement.md` |
| A2 | 7 | Additive `volume` / `weight` on products and `branchId` on warehouses (fleet load planning, BR-1) | Additive, approved |
| D3 | 6 | Nest 500 (Prisma validation) on bad currency-rate / fiscal-period dates and the dev-only `error` / `name` / `stack` detail | **Pending owner** (accounting deviation #3) |
| P | 3 | Repair `bill` / `pay` require `Idempotency-Key` (400 without, 200 replay) | Documented, `docs/spec/repair.md` Deviation 1 (pending review) |
| A4 | 2 | Rule-eventable catalog has `fleet.reminder.due`, `alert.raised` | Additive (fleet, ADR-S021 alerts) |
| A5 | 1 | `GET /uoms` lists the UOM-1 units (BAG, M3, TRUCK, V60) first | Additive reference data |
| A6 | 1 | `posMode` accepts `rental` | Documented, `docs/spec/pos.md` |
| U1 | 1 | `GET /pos/auth/staff`: Nest has no `ORDER BY` (row order undefined); Spring orders by id | No contract to keep |
| O1 | 1 | `POST /approvals/request` with `snapshot`: Nest's DTO leaves `snapshot` undecorated, so the whitelist always answers 400 and the route can never carry a snapshot; Spring accepts it (CTRL-1 workflows read it) | **Owner sign-off** — undocumented until now |

## 5. Nest bug kept for parity, found on the way

| # | Finding | Effect | Proposed fix (post-parity, needs approval) |
|---|---|---|---|
| F1 | `POST /menu/public/orders` (QR self-order) calls the cashier checkout **without a `cashSessionId`**; checkout always requires one (`Select an open register before completing any POS sale`). Same in Nest (`digital-menu.service.ts` → `pos.service.ts` `requireCashSession`) | A guest's QR order that passes validation is always refused: online ordering cannot work | Resolve the open session of the QR session's branch (or a configured "online orders" register) before checkout; pinned today by `PosFlows2IT.qrOrder` |

## 6. Nest retirement — gate

Parity is no longer the blocker. Still open, all owner items:

1. Decide D1, D3, repair Deviation 1 and O1 (then add `allowed-diffs.json` entries for the approved ones).
2. Cut-over rehearsal on a **copy of the production POS-CAFE database** and the 7-day Z-report comparison
   (`docs/OPERATIONS.md` §5). Both need production data, not available here.
3. After the rehearsal: stop the Nest container for good; `reference/` stays in the repo as the specification.

## 7. How to rerun

```bash
# both databases: Prisma migrate deploy + Nest seed (see contract-tests/README.md); Spring stopped while resetting
export JWT_ACCESS_SECRET=... JWT_REFRESH_SECRET=... JWT_POS_SECRET=...      # same on both sides
export ENABLE_BEVERAGE=true ENABLE_ASSETS=true ENABLE_TASKS=true ENABLE_MANUFACTURING=true ENABLE_RENTAL=true \
       ENABLE_REPAIR=true ENABLE_HR=true ENABLE_ORDERS=true ENABLE_COMMUNICATION=true
(cd backend && PORT=3031 THROTTLE_ENABLED=false \
  JDBC_DATABASE_URL='jdbc:postgresql://localhost:5434/parity_spring?stringtype=unspecified' ./gradlew bootRun) &
NEST_DIR=<built copy>/apps/api NEST_DB=postgresql://erp:erp@localhost:5434/parity_nest \
  SPRING_URL=http://127.0.0.1:3031 contract-tests/run-all.sh > parity.log
grep -c '  ✓' parity.log; grep -c '  ✗' parity.log
```
