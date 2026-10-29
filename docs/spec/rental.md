# Rental — `rental`

| | |
|---|---|
| Phase | P7 (roadmap 0.3) |
| Nest source | `reference/nest-api/src/modules/rental` |
| Routes | 47 — full table: [generated/rental.md](generated/rental.md) |
| Feature flag | `ENABLE_RENTAL` → `app.modules.rental` (controller, cron worker, lifecycles + module manifest) |
| Java package | `com.inventoryerp.rental` |

## Purpose

Hire-out of serialized and pooled assets. Renting is a second fulfilment verb: possession moves for a while, the
asset stays owned and comes back on a known date. Checkout is an internal stock transfer (RENT-STOCK → RENT-OUT,
no COGS); fees ride the POS Order → Invoice spine; the deposit is a liability sub-ledger, never an invoice line.

## Functional areas

| Area | Routes | Service |
|---|---|---|
| Catalog | `GET /rental/catalog`, `POST/PATCH/DELETE /rental/rates[/:id]`, `POST/PATCH/DELETE /rental/packages[/:id]` | `RentalCatalogService` |
| Units | `GET /rental/units`, `POST /rental/units/generate`, `PATCH /rental/units/:id`, `POST /rental/units/:id/retire` | `RentalUnitService` |
| Availability | `GET /rental/availability`, `GET /rental/availability/units` | `RentalAvailabilityService` |
| Reservations | `GET/POST /rental/reservations`, `POST /rental/reservations/:id/confirm|cancel` | `RentalReservationService` |
| Scores | `GET /rental/scores/:partnerId`, `PATCH /rental/scores/:partnerId/hold` | `RentalScoreService` |
| Agreements | `GET/POST /rental/agreements`, `GET /rental/agreements/:id`, `POST .../confirm|checkout|cancel` | `RentalAgreementService` |
| Deposits | `GET /rental/agreements/:id/deposit`, `POST .../deposit/collect|refund|forfeit` | `RentalDepositService` |
| Extensions, swaps | `GET/POST /rental/extensions`, `GET/POST /rental/swaps` | `RentalExtensionService`, `RentalSwapService` |
| Returns | `GET/POST /rental/returns`, `GET /rental/returns/:id`, `POST .../inspect|settle`, `POST /rental/damages/:id/waive` | `RentalReturnService` |
| Service orders | `GET/POST /rental/service-orders`, `POST .../complete|cancel` | `RentalServiceOrderService` |
| Reports | `GET /rental/reports/utilization|revenue|fleet` | `RentalReportsService` |
| Locations | `POST /rental/locations/bootstrap` | `RentalLocationConfigService` |

Stock + invoice mechanics live in `RentalPostingService`; JS-number arithmetic (rate bands, period units, late
fees, deposit requirement / status, trust score) in `RentalMath`.

## Business rules (as implemented by Nest, reproduced here)

1. **Availability** = candidate units − overlapping bookings. Candidates: units of the product in `available`,
   `cleaning`, `repair` (`available`, `repair` when the product has `rentalRequiresCleaning`); pooled products have
   no units, so their count is 0. Overlap: bookings `held|confirmed|active` with `startAt < endAt + rentalBufferDays`
   and `endAt > startAt` (half-open windows). `excludeSourceId` drops one agreement's own bookings.
2. **Reservations** create count-level `held` bookings (no unit) that expire after `holdMinutes`
   (`rental.autoHoldMinutes`, 60); the cron cancels them. Converting one (agreement `reservationId`) re-points its
   bookings to the agreement as `confirmed`.
3. **Agreements** freeze `termsSnapshot = {lateFeePerDay, holdMinutes, depositPolicy}`. A line's `unitRate` is the
   given one, else the highest-priority active rate band containing `rateUnits`, else 0; `lineTotal = unitRate ×
   rateUnits × quantity`. One `held` booking per line. Confirm is approval-gated (`rental_agreement`) and promotes
   bookings to `confirmed`.
4. **Checkout** (one transaction): score gate (`manualHold` or score < 50 blocks unless `overrideById`, recorded as a
   synchronous approval *before* the transaction) → RENT-STOCK → RENT-OUT transfer per line → deposit ledger +
   collection (`depositAmount` or `rental.depositPolicy`: fixed amount or `ceil(Σ lineTotal × percent / 100)`) →
   fee order + invoice (product `RENT-FEE`, else the rented product; one line per agreement line) → units
   `checked_out` (+1 `rentalCount`), bookings `active`, agreement `checked_out`.
5. **Deposits** are movements `collected|applied|refunded|forfeited` under a `FOR UPDATE` lock on the deposit row;
   available = collected − applied − refunded − forfeited; status derived by `RentalMath.depositStatus`.
6. **Extension**: `newDueAt` must be after the line's due date and the window `(dueAt, newDueAt]` free of other
   agreements' bookings; units = `ceil(span / period)` (month = 30 days), charge = `unitRate × units × quantity`
   billed through `RENT-EXTEND` when that product exists.
7. **Swap**: target unit of the same product, `available`; old unit RENT-OUT → RENT-CLEAN and `cleaning`, new unit
   RENT-STOCK → RENT-OUT and `checked_out`; a positive `priceDelta` is billed through `RENT-EXTEND`.
8. **Return**: late days `max(0, ceil((now − dueAt) / 1 day))`, late fee `lateDays × termsSnapshot.lateFeePerDay ×
   quantity`; agreement → `returned` when the return covers every line, else `partially_returned`.
9. **Inspection**: damages recorded per line; damaged lines RENT-OUT → RENT-DAMAGED and unit `damaged`, clean lines
   RENT-CLEAN → RENT-STOCK and unit `available`.
10. **Settlement**: late fees + damage charges (minus `waiveDamageIds`); deposit applied up to
    `min(available, total, depositApplied)`; when anything is left a fee invoice (`RENT-LATE` / `RENT-DAMAGE`) is
    raised; the leftover deposit is refunded (cash); agreement `closed` with its totals; bookings `completed`;
    customer score recomputed.
11. **Score** (0–100): late returns −5 (max −30), damage events −15 (max −30), lost items −20 each, cancelled
    reservations −2 (max −10), unpaid balance −2 per started 100,000 (max −10). 80+ good, 50–79 caution, < 50 blocked.

Error messages are copied verbatim from the generated spec.

## GL postings

- Every fee (checkout, extension, swap delta, settlement) is an Order (`transactionKind = rental`,
  `rentalAgreementId`) billed by `PosInvoiceService.generateInvoice` inside the rental transaction: the standard
  SALES posting (Dr receivable / Cr revenue + tax) through the posting engine. `RentalIT` asserts every one of
  these journals is balanced.
- Stock moves are `StockService.transfer` between the five virtual rental locations (source types
  `rental_checkout`, `rental_return`, `rental_inspect`).
- Deposits post **nothing** to the GL in Nest (`RentalDepositMovement.journalEntryId` is never set although the COA
  template maps `customer_deposit` 2175). Kept for parity — see "Same as Nest".

## Events

None (Nest publishes no domain events from this module).

## Scheduled jobs

`rental-housekeeping` every minute (`RentalCronWorker`, ShedLock `rental-housekeeping`, `lockAtMostFor` 5 min, on
when `app.jobs.enabled` and `app.modules.rental`). Per org in its tenant scope: cancel held reservations past
`expiresAt`; auto-forfeit the remaining deposit of agreements `returned` with `returnedAt` older than 14 days.

## Permissions

`rental:read`, `rental:manage`, `rental:reserve`, `rental:sign`, `rental:checkout`, `rental:return`,
`rental:settle`, `rental:waive` — per route exactly as in Nest.

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — none needed: services are JdbcClient SQL
- [x] DTO records with Bean Validation mirroring class-validator rules — Nest bodies and queries are untyped (`any`),
      so they are taken as maps and coerced like JavaScript (no validation, like Nest)
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes (money routes: see Deviations 1)
- [x] Events published/consumed as in Nest (none)
- [x] Jest specs ported (none exist); integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/rental-lifecycle.json` — written; not yet run against a live Nest
      (expected diffs listed below)
- [x] Web screens for this module call only served routes with the expected shapes (code review of
      `web/src/pages/rental/*.tsx` + `web/src/features/rental/api.ts`; no browser walkthrough yet)
- [ ] `docs/PORT_STATUS.md` updated (coordinator)

## Deviations (autonomous decisions, pending owner review)

1. **Idempotency on the money routes.** `POST /rental/agreements/:id/checkout`, `.../deposit/collect`,
   `.../deposit/refund`, `.../deposit/forfeit`, `POST /rental/extensions`, `POST /rental/swaps` and
   `POST /rental/returns/:id/settle` are `@Idempotent(required = true)` (AGENTS.md §7/§8: money routes reject a
   missing `Idempotency-Key` and replay). Nest has no `@Idempotent` there, so these answer **200** instead of 201 and
   **400** `An Idempotency-Key header is required for this operation so a retry can never charge twice.` without the
   header. The web client sends a key on every write, so the screens are unaffected.
2. **Customer score.** Nest sums `invoice` rows with `status: 'open'`, a value `InvoiceStatus` does not have; Prisma
   rejects the query, so in Nest `GET /rental/scores/:partnerId` and `POST /rental/returns/:id/settle` always fail
   with 500 (settlement rolls back entirely). The filter can match no invoice, so Spring uses an unpaid balance of 0
   and both routes work.
3. **Checkout of a line without a unit.** Nest runs `rentalUnit.updateMany({where: {organizationId, id: line.unitId
   ?? undefined}})`; for a line without `unitId` (every line the web creates) the id filter disappears and **every
   unit of the organization** becomes `checked_out` with `rentalCount + 1`. Spring updates only the line's unit.
4. **List paging.** Nest passes the raw query string to Prisma (`take: "50"`), so every list called with
   `pageSize` (every web screen sends it) fails with 500. Spring parses `page` / `pageSize` as numbers; both are
   echoed back as received (`"2"`), like Nest echoes `page`.
5. **Per-organization location roles** (`V11__rental_location_role_per_org.sql`): the Nest schema also has a global
   unique index on `RentalLocationConfig.role`, so only the first organization could ever bootstrap its rental
   locations. V11 drops it; the `(organizationId, role)` unique index stays.
6. **Missing ids in a body.** Prisma drops `undefined` filters, so Nest's `findFirst({where: {id: undefined, ...}})`
   matches an arbitrary row of the organization (e.g. `POST /rental/extensions` without `lineId`, `/rental/swaps`
   without `toUnitId`, `/rental/returns` without `agreementId`, rates / packages / units without `productId`). Spring
   binds SQL `NULL`, so these answer the route's not-found error (404 / 400) instead of acting on a random row.

## Same as Nest, kept on purpose (owner may want these fixed after parity)

- Deposits are a sub-ledger only: collection, refund, forfeit and application post no journal entry.
- Settlement bills the **full** late + damage charges whenever the deposit did not cover everything, although the
  applied deposit already covered part of them. Damages waived with `POST /rental/damages/:id/waive` still count;
  only `waiveDamageIds` in the settle body exclude a damage. The score counts waived damages too.
- `createReturn` never moves stock and never sets `RentalAgreement.returnedAt`: clean lines are moved RENT-CLEAN →
  RENT-STOCK at inspection (fails with `No stock found at source location` unless a swap left stock in RENT-CLEAN),
  RENT-OUT keeps the returned quantity, and the 14-day auto-forfeit only sees agreements whose `returnedAt` was set
  by other means.
- A return only checks its own items for "all back": an agreement returned line by line stays `partially_returned`.
  Settling one return closes the agreement, so later returns cannot be settled.
- `POST /rental/returns/:id/inspect` answers with the return as it was **before** the inspection (Nest reads it on
  the non-transactional client from inside its transaction).
- Pooled products (`rentalIsPooled`) have no units, so their availability is always 0 and they cannot be reserved or
  rented.
- Reservation holds carry no unit: `GET /rental/availability/units` ignores them, and creating an agreement from a
  reservation re-checks availability counting the reservation's own hold, then keeps both sets of bookings.
- `PATCH /rental/rates/:id` with a new `minUnits` upserts a different band; re-posting a soft-deleted band's key
  updates it but leaves it deleted.
- The agreement line fallback price reads `product.salePrice` (the column is `salesPrice`), so it is always 0.
- Checkout falls back to the rented product when `RENT-FEE` is missing (the invoice spine then relieves its stock);
  extension and swap fees are silently skipped when `RENT-EXTEND` is missing.
- `POST /rental/reservations/:id/confirm` only returns the hold; the cron expires holds as `cancelled`.
- Unknown enum values in list filters (`status=broken`) answer 500.
- The web calls `GET /rental/rates` (no such route in Nest → 404) and reads `utilization.rate` (Nest returns
  `utilization`); both degrade gracefully in the UI.

## Parity

Scenario `contract-tests/scenarios/rental-lifecycle.json` (fresh seed, `ENABLE_RENTAL=true` on both sides). Expected
diffs, all explained above:

| Step(s) | Nest | Spring | Why |
|---|---|---|---|
| checkout, deposit collect / refund / forfeit, extension, swap, settle (success and 4xx) | 201 | 200 | Deviation 1 |
| `units: page 2` | 500 | 200 | Deviation 4 |
| `score` | 500 | 200 | Deviation 2 |
| `settle`, `agreement after settle` | 500 / `returned` | 200 / `closed` | Deviation 2 |

## Tests

- `RentalMathTest` (unit): rate bands, period units, late days / fees, deposit requirement and status, score/bands.
- `RentalIT` (Postgres): catalog; units; availability (overlap per window, half-open windows, buffer days, cleaning
  units, pooled); reservations; extension overlap; full lifecycle (score gate + override, checkout stock / deposit /
  invoice, deposit ledger, extension, swap, return, inspection, waive, settlement, score, reports) with balanced
  journals and idempotency (missing key, replay, key reuse 409) on every money route; housekeeping cron (hold expiry,
  14-day auto-forfeit, partial returns, low-score gate); service orders; tenant isolation (incl. per-org location
  bootstrap); 401/403 matrix over all 47 routes. JaCoCo line coverage of `rental.application`: 95.9 %.

## Status

47/47 routes implemented and verified by `RentalIT` (2026-10-28). Open: parity run against a live Nest, browser
walkthrough of the rental screens, owner review of the deviations above.
