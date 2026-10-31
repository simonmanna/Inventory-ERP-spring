# Orders (generic sales orders) — `orders`

| | |
|---|---|
| Phase | P7 |
| Nest source | `reference/nest-api/src/modules/orders` |
| Routes | 11 — full table: [generated/orders.md](generated/orders.md) |
| Feature flag | `ENABLE_ORDERS` |
| Java package | `com.inventoryerp.orders` |

## Purpose

Non-POS sales orders: settings, CRUD, items, cancel, reopen, invoice.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| ~11 routes | see generated table |

## Business rules to reproduce exactly

1. Shares Order/OrderItem tables with POS; reuse pos/invoicing services.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

—

## Scheduled jobs

—

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/orders-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [x] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Notes

- The generated route table lists `CreateOrderDto` / `AddOrderItemsDto` / `CancelOrderDto` from the POS module; the
  controller actually imports `orders/dto/orders.dto.ts` (`OrderLineInputDto` with optional price/description and the
  `customOrderLineRef` rule). The Nest code wins — the Java records mirror `orders.dto.ts`.
- `decorate()` resolves `waiterName` / `tableName` against the **partner** table, so both are normally `null` — kept.
- Orders do not register a module manifest in Spring (no module registers one; the registry only lists `kernel`).

## Status

11/11 routes (`OrdersController` in `com.inventoryerp.orders.web`, `OrdersService` reusing `PosDb`, the tax engine,
`TableStatus.lockFloorShared`, the shared `order` workflow and `PosInvoiceService.generateInvoice`).
`OrdersIT` (7 tests): settings + Setting mirror, catalog-resolved pricing, list filters / search / ParseIntPipe,
`orders.dto.spec.ts` rules, save-with-version / append / header patch, cancel / reopen, billing guards, tenant
isolation and the 11-route authorization matrix. Parity scenarios and web screens not yet run against Nest.

## Order approval (SALES-2)

Status: built, not compiled, not run. Test `OrdersIT.approval`.

Entity type `sales_order`, snapshot `amount` (order total), `orderNumber`, `partnerId`, `branchId`, `createdBy`. Raised on create when the order has lines, and by `POST /orders/{id}/request-approval` (permission `orders:update`). `GET /orders/{id}/approval` (permission `orders:read`) returns the latest request, or an empty body. `POST /orders/{id}/invoice` answers 400 while approval is pending. Without a workflow nothing changes.
