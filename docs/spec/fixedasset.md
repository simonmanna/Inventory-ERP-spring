# Fixed assets — `fixedasset`

| | |
|---|---|
| Phase | P7 |
| Nest source | `reference/nest-api/src/modules/fixed-asset` |
| Routes | 54 — full table: [generated/fixedasset.md](generated/fixedasset.md) |
| Feature flag | `ENABLE_ASSETS` |
| Java package | `com.inventoryerp.fixedasset` |

## Purpose

Asset register with categories, acquisition, assignment, check-in/out, depreciation runs and posting, disposal, inspections, insurance, maintenance, repairs, revaluations, transfers, warranties, dashboard.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| assets | CRUD, search, by-code, by-barcode, restore |
| sub-resources | `/fixed-assets/:assetId/*` (12 kinds) |
| dashboard | `/fixed-assets-dashboard/*` |
| categories | `/asset-categories` |

## Business rules to reproduce exactly

1. Depreciation methods and GL posting must match the Nest depreciation service exactly. A run posts
   `Dr depreciation_expense / Cr accumulated_depreciation` on the first day of the period, and only when
   `postEntries` is true. An entry that is already posted is never posted again.
2. Disposal **records** `gainLoss = disposalValue − currentValue` and `bookValueAtDisposal`. It then sets the asset
   to `disposed` with `currentValue = 0`. Nest posts **no** GL entry for a disposal, a revaluation or an
   acquisition. (This doc used to say "Disposal posts gain/loss"; the Nest code wins.)

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

—

## Scheduled jobs

—

## Notes and pitfalls

- No Nest specs — write tests from the service logic.

## Porting checklist

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/fixedasset-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

1. `GET /fixed-assets?sortBy=<x>` with a field that does not exist answers **400** `Invalid sortBy field: x`. Nest's
   answer comes from a Prisma validation error (500). Spring builds JPQL from the field name, so it has to reject
   unknown fields.
2. Depreciation maths runs on `BigDecimal` at 20 significant digits instead of JS doubles. The values stored in the
   `Decimal(18,6)` columns are the same apart from last-digit rounding edge cases.

3. ADR-S074 (extension): assets created by a CIP capitalisation (`AssetCapitalisationService`, called by the projects
   module) are `active`, acquisition method `constructed`, with no acquisition record and no `asset_acquisition`
   approval gate (the projects capitalisation is the control). Their new `Asset.inServiceDate` column (V506) makes a
   depreciation run skip periods before the in-service month. Assets without an in-service date are unaffected, and
   the column is not part of the asset JSON. Reversing a capitalisation soft-deletes its assets. It is refused once
   depreciation is posted.

4. R14 (deviation from Nest): depreciation methods do what they say, and a run cannot leave the register ahead of
   the ledger.
   - `units_of_production` charges `(cost − residual) × units used in the month / Asset.totalUnits`, stopping at the
     residual value (after an impairment: carrying amount less residual over the units still to come). Nest fell
     back to straight line. Usage is recorded per month with `POST /fixed-assets/:assetId/depreciation/usage
     {period: YYYY-MM, units ≥ 0, notes?}` (`asset_depreciation:run`; upsert, refused with 400 once that month is
     depreciated) and listed by `GET .../depreciation/usage` (`asset_depreciation:read`). An asset without
     `totalUnits` or without usage for the month is skipped, logged and listed under `skipped` in the
     `fixed_asset.depreciation_run` event; it is never depreciated on straight line. `totalUnits` (> 0) is accepted
     on `POST/PATCH /fixed-assets` and rendered on the asset.
   - `declining_balance` uses `AssetCategory.decliningFactor` (annual rate = factor / years; default 2.0, so
     existing schedules are unchanged; must be > 0, typically 1.5). `double_declining` is always 2×.
   - Each asset of a run is one transaction: its entry, its new book value and, with `postEntries`, its journal
     entry commit together. A failed GL post rolls the asset back (no entry, book value unchanged) and is logged and
     reported under `skipped`; the run continues with the next asset. Nest committed each step separately and left
     the entry unposted. The run response stays the list of entries created; a posted entry now shows
     `isPosted: true` and its `journalEntryId`.
   - Migration `V517__depreciation_methods_usage.sql` (`AssetCategory.decliningFactor`, `Asset.totalUnits`,
     `AssetUsage`).

## Same as Nest, kept on purpose

- `period/:period` and `run` ignore the `:assetId` path segment. A run targets one asset only through `body.assetId`.
- `PATCH /fixed-assets/:id/restore` only matches a live asset (the tenancy extension adds `deletedAt: null`), so
  restoring a deleted asset answers 404.
- `DELETE /asset-categories/:id` with live assets answers **404** `Cannot delete category with N assigned assets`.
- The `PATCH` bodies for insurance, maintenance, repairs and warranties are `Partial<CreateXDto>`, so they are
  never validated.
- The approval gates (`asset_acquisition`, `asset_transfer`, `asset_revaluation`, `asset_disposal`,
  `depreciation_run`) commit their request row before the 403 / 400. This matches Nest, which runs without a
  transaction.

## Status

54/54 routes. The controllers are `AssetController`, `AssetCategoryController`, `AssetRecordsController`,
`AssetDepreciationController` and `AssetDashboardController`. Tests:
- `DepreciationCalculatorTest`: every method, salvage floor, short lives, declining factor, units of production.
- `FixedAssetIT`: categories and tree, register CRUD and lookups, depreciation run, idempotent period, balanced
  GL post, lifecycle sub-records and status transitions, disposal gain/loss, dashboards, validation, tenant
  isolation, and the authorization matrix (54).

Parity scenarios have not yet been run against Nest.

## ADR-S073 impairment (extension)

`GET /fixed-assets/:assetId/impairments` (`fixed_asset:read`) returns the carrying amount, the accumulated impairment,
the reversal headroom and the history. Two routes post, both with an Idempotency-Key and `fixed_asset:impair`:
- `POST /fixed-assets/:assetId/impairments {recoverableAmount, date?, reason?}`;
- `POST .../impairments/reversal`.

Table: `AssetImpairment` (V503).

- Straight-line depreciation after an impairment spreads the new carrying amount over the remaining life.
- A reversal is capped at the carrying amount the asset would have had without impairment.
- Disposal clears the accumulated impairment.

Details: [accounting.md § ADR-S073](accounting.md#adr-s073-gl-automation) and
[ADR-S073](../adr/ADR-S073-recurring-deferrals-bank-rules-impairment.md). Test: `GlAutomationIT.impairmentLifecycle`.

