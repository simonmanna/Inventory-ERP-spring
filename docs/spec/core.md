# Core master data — `core`

| | |
|---|---|
| Phase | P2 |
| Nest source | `reference/nest-api/src/modules/core` |
| Routes | 81 — full table: [generated/core.md](generated/core.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.core` |

## Purpose

Organizations, branches, partners (customers/suppliers/employees) with contacts and addresses, products (variants, packaging, UoM conversions, barcodes), product categories, taxes, units of measure, fiscal periods/positions, payment terms.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| organizations | bootstrap (create org + admin), accept-invite, me, me/settings, me/branch, users, invite, deactivate |
| branches | CRUD; user default branch |
| partners | CRUD + categories, contacts, addresses; flags isCustomer/isSupplier/isEmployee; credit limit/hold; opening balance; receivable/payable account overrides; membership tiers |
| products | CRUD, `search`, `resolve-barcode`, restore; variants, packaging, UoM conversions, brand, costing method, tax, reorder levels |
| uom | `/uoms` CRUD + `POST convert`; `/uom-categories` CRUD; conversion factors exact (BigDecimal) |
| taxes | CRUD; inclusive/exclusive, rate, accounts |
| fiscal | `/fiscal-periods` CRUD (core wins over accounting duplicate, see CONTRACT.md §1); `/fiscal-positions` CRUD + deleted + restore; `/payment-terms` CRUD + deleted + restore |

## Business rules to reproduce exactly

1. Codes (partner/product) unique per organization; error messages verbatim from services.
2. Product delete is soft and blocked when stock or open documents reference it (check service).
3. Barcode resolution order: product barcode → variant barcode → packaging barcode (`resolve-barcode`).
4. Partner credit: `creditHold` blocks credit sales in POS (fail closed); `creditLimit` checked against open AR.
5. Emits `partner.created/updated/deleted`, `product.created/updated/deleted`.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

`partner.*`, `product.*` (consumed by sync pull watermarks, CRM, search).

## Scheduled jobs

—

## Notes and pitfalls

- `organizations/bootstrap` is public in some flows — check `@Public()` in the route table.
- UoM conversion rounding uses 6 dp (`qtyMul`).

## Porting checklist

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/core-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Implementation notes

- Generic CRUD (`BaseCrudService`) lives in `kernel/crud`: Hibernate tenant + soft-delete filters stand in
  for the Prisma tenancy extension; `EntityPatch` applies request records like Prisma `data: dto` (an
  explicit JSON `null` clears a column, an absent key leaves it); `QueryParams.page` reproduces
  `PaginationDto` validation (whitelist + class-validator messages). Prisma `include`s are rendered by
  each service (`render`), nested to-one includes ignore soft delete like Prisma.
- Events are published after commit; product audit is fire-and-forget after commit (Nest order).
- `CorePermissionSync` ports the boot-time permission catalog + Administrator role sync.

## Deviations

Pending owner approval:

1. **`GET /organizations/users`** omits `passwordHash`, `mfaSecret*` and `pinHash` (Nest returned full
   `User` rows including credential hashes).
2. **`PATCH /organizations/me/branch`, `PATCH /organizations/users/:id/deactivate`** answer
   `404 No record was found for an update.` for an unknown user (Nest: unhandled Prisma P2025 → 500).
3. **Bootstrap** runs the organization seed in one transaction (Nest wrote row by row; a mid-way failure
   left a partial org).
4. **Date-only strings** (`2026-01-01`) for `DateTime` fields are stored as UTC midnight (Prisma rejected
   them → 500).
5. **`PATCH /fiscal-positions/:id`, `/payment-terms/:id`** (unvalidated `Partial<>` bodies) ignore unknown
   keys (Nest passed them to Prisma → 500 for fiscal positions).
6. **Per-product units (ADR-S003, V5).** `POST/PATCH /products` accept `units[]` (`uomId`, `factor`,
   `isPurchase`, `isSales`, `salePrice`, `purchasePrice`, `isActive`); a sent list replaces the product's
   `ProductUom` rows. Product responses gain `units[]` (each with its `uom`). A product's own unit factor
   wins over the category factor in `UomConversionService`. `uomConversion` from older clients becomes
   the purchase unit's row. New errors (400): `Unit of measure <id> not found`,
   `Unit <code> is the base unit of <name>; extra units must differ from the base unit`,
   `Unit <code> is listed more than once`, `Conversion for unit <code> must be greater than 0`,
   `The base unit of <name> cannot be changed after stock has moved. Add the new unit as an extra unit with
   its conversion instead.`

## Status

P2: 81/81 routes. `CoreIT` (CRUD contract, partners, products, UoM, payment terms, fiscal positions,
bootstrap/invite, authorization matrix, tenant isolation) + `UomConversionServiceTest` (port of
`uom-conversion.service.spec.ts`). The listed `test/integration/audit/*` specs exercise inventory/POS flows
and are ported with P3–P5. DEMO seed UoM/products follow with the accounting seed.

## Density and construction units (UOM-1, ADR-S003 extension)

Status: built, not compiled or run. `GET|PUT /products/:id/unit-profile`, `GET /products/:id/unit-equivalents`
(`ProductUnitProfileController`, `product:read` / `product:update`). `ProductUnitProfileService` writes
`ProductUomConversion` and `ProductUom`; `UomConversionService.conversionRate` is the non-throwing read. Seeds M3, BAG,
TRUCK. Test: `ProductDensityIT`, `OrdersUnitsIT`.

## User data scope (BR-1, ADR-S017)

Status: built, not compiled or run. `GET|PUT /user-data-scopes/:userId` (`user:read` / `user:update`), table
`UserDataScope` (V62). `BranchScopeService.scope()` returns the grant; `assertBranch`, `documentBranch` and
`assertUnrestricted` are the helpers. Inventory uses it through `LocationScope`. Test: `UserDataScopeIT`.

## Price lists (SALES-3)

Status: built, not compiled or run. `/price-lists` CRUD and `POST /price-lists/resolve` (`price_list:read|manage`),
tables `PriceList`, `PriceListItem`, `PriceListAssignment` (V63). `PriceListService.resolve` is called by
`InvoiceService`, `OrdersService`, `FleetDeliveryOrderService` and `CrmPricingService` for lines with no price; POS never
calls it. Screen: `/price-lists`. Test: `PriceListIT`.

## INV-2 product attributes, specifications and grades (built, unverified)

`ProductSpecController` (11 routes on `/product-grades`, `/product-attributes`, `/product-specifications`), `ProductSpecService`,
migration V96 (`ProductGrade`, `ProductAttribute`, `ProductSpecification`, `ProductAttributeValue`), permissions
`product:read` / `product:update`. `Product` itself is not changed. Screen: `/products/specifications`.
