# Invoicing, payments, AR/AP — `invoicing`

| | |
|---|---|
| Phase | P3b |
| Nest source | `reference/nest-api/src/modules/invoicing` |
| Routes | 30 — full table: [generated/invoicing.md](generated/invoicing.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.invoicing` |

## Purpose

Customer invoices, credit notes, vendor bills (+ receipt matching, supplier ledger), customer payments with allocations and withholding, supplier payments, tax calculation, AR/AP reports.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| invoices | CRUD, journal-preview, post, cancel |
| credit notes | CRUD, post |
| vendor bills | CRUD, post, cancel, supplier ledger |
| payments | list/get/create/void (idempotent), allocations; supplier payments |
| reports | AR aging, customer balances, outstanding, revenue by customer/period; AP aging |

## Business rules to reproduce exactly

1. Posting an invoice creates its journal entry (AR / revenue / tax) in the same tx; posted invoices are immutable except cancel (reversal).
2. `amountResidual` = total − paid − credited; `PaymentStatus` not_paid/partial/paid/overpaid derived exactly as Nest.
3. Payment allocation cannot exceed residual; void reverses allocations and GL.
4. Tax calc: inclusive/exclusive per tax, rounding per line then total as Nest (`tax` service).

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

`invoice.created/posted/paid/cancelled`, `creditnote.issued`, `payment.received/allocated`.

## Scheduled jobs

—

## Notes and pitfalls

- POS invoices are created by the pos module through these services (`pos/billing`).

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/invoicing-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [x] `docs/PORT_STATUS.md` updated

## Deviations

Pending owner approval:

1. **Line units (ADR-S003, V5).** Invoice, vendor-bill and credit-note lines accept `uomId` and snapshot
   `uomId` + `uomFactor` (default: the sales unit; bills: the purchase unit). Posting an invoice issues
   `quantity x uomFactor` base units (Nest issued `quantity` base units); bills receive with the line
   snapshot; credit-note restock uses it too. Bill-to-receipt matching compares base units when both lines
   carry a snapshot (`billedQuantity` is stamped in receipt-line units). An omitted `unitPrice` on a
   non-default unit defaults to its `ProductUom` price or the scaled default price. New 400:
   `Unit <code> is not enabled for sales on product <name>`.

Same as Nest, kept on purpose:

- `GET /invoices` merges manual `Document` rows and POS `Invoice` rows; manual rows always report
  `paymentStatus: "unpaid"` and `settlementStatus: "unsettled"` (Nest overwrites them in the merge), so the
  `paymentStatus` / `settlementStatus` filters only ever match POS rows or those literals.
- `GET /vendor-bills/suppliers/:partnerId/ledger` only lists bills in `draft | posted | cancelled`: a fully
  paid bill (status `paid`) drops out while its payment stays, so the closing balance goes negative.
- Invoicing writes `Document` / `DocumentLine` (documents module tables) through JDBC exactly where the Nest
  services write them through Prisma; approval gates that Nest runs outside `$transaction` run in
  `REQUIRES_NEW` so a refused request survives.

## Extensions beyond Nest (ADR-S005)

These are additive routes the owner asked for. Nest has no counterpart.
- `GET/POST /opening-balances`. Permissions are `opening_balance:read` and `opening_balance:post`, and the POST
  requires an `Idempotency-Key`. GL lines post as one `GEN` entry, and any difference goes to `retained_earnings`.
  Customer and supplier balances become posted invoices and bills, tagged `sourceType = opening_balance`.
- `GET /reports/tax/summary?from&to&branchId` (`report:accounting`) shows output tax and input tax by tax, the
  net amount payable, and a GL cross-check.
- `GET /reports/ar/customers/:id/statement[.pdf]`, `POST …/statement/email` and
  `GET /reports/ap/suppliers/:id/statement[.pdf]` (`report:ar`) build a GL-based statement with aging.
- `GET /invoices/:id/pdf` (`invoice:read`) and `POST /invoices/:id/email` (`invoice:send`, posted invoices
  only). Sending stamps `Document.data.lastEmailedAt` and `Document.data.lastEmailedTo`.

## Status

P3b: 30/30 routes. Tests: `InvoicingIT` (invoice draft / preview / edit / post / cancel with JE reversal,
receipts with allocations + Idempotency-Key requirement + replay + void restoring the residual, credit notes,
vendor bills with stock-in + PURCH journal + supplier payment with withholding (2160) + supplier ledger + AP
aging, AR reports, tenant isolation, authorization matrix = 30 routes), `TaxCalculationServiceTest` (port of
`tax-calculation.service.spec.ts`). Parity scenario: `contract-tests/scenarios/invoicing-ar-ap.json`.
