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

SG-0 stabilization (2026-11-06), pending owner approval:

- **Failed restock fails the credit note.** Nest logged a failed restock and posted anyway, so the books could credit
  goods that never came back. `POST /credit-notes/{id}/post` now answers 400 and posts nothing. Goods that are not
  coming back use `disposition=scrap`. A missing stock location fails the same way.
- **Invoice list payment status.** Nest derived `paid` or `unpaid` from the residual; the port had hard-coded `unpaid`.
  Back-office rows now also report `partially_paid` / `partially_settled`, and the filters run in SQL.
- **`GET /invoices/{id}` also opens a proforma**, read-only, so a quotation converted to a proforma can be viewed. List
  and write routes still only accept `sales_invoice`.
- **Credit note currency.** A credit note against an invoice takes that invoice's currency and rate unless it names one.

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

## Phase 4 additions (built, unverified)

FIN-2: `GET /vendor-bills` also takes `status` and `partnerId` (additive; other unknown parameters still answer 400).
Screens: `/vendor-bills`, `/vendor-bills/new`, `/vendor-bills/:id`, `/vendor-bills/:id/edit`, `/ap-aging`.
Test: `VendorBillListIT`. Nothing was compiled or run.

## Phase 4 supplier payment schedule (built, unverified)

PROC-4: `GET /vendor-bills/payment-schedule?partnerId&from&to` (`expense:read`), `PUT /vendor-bills/{id}/payment-schedule` (`expense:update`), table `SupplierPaymentSchedule` (V70). A plan moves no money.
Screen `/payment-schedule`. Test: `SupplierPaymentScheduleIT`. Nothing was compiled or run.

## Phase 4 tax pack (built, unverified)

FIN-3: `GET /reports/tax/pack?from&to&branchId` (`report:accounting`): 13 numbered boxes by VAT category plus a GL cross-check. Test: `TaxPackIT`. Nothing was compiled or run.
## INT-1 mobile money collections (Phase 4, built, unverified: nothing compiled or run)

Lives in `invoicing`, not `accounting`, because it creates receipts through `PaymentService` and `invoicing` already depends
on `accounting`. `MobileMoneyProvider` is the SPI, `MockMobileMoneyProvider` the only implementation (no money moves): the
last digit of the number picks the outcome (`9` refused, `8` stays pending, else successful on the first status check).
Migration `V81__mobile_money.sql` (`MobileMoneyCollection`). Permissions: existing `payment:read`, `payment:create`.

| Route | Purpose |
|---|---|
| `GET /mobile-money/settings` | provider, available, whether configured, whether a callback secret is set |
| `GET /mobile-money/collections` (`status, partnerId, page, pageSize`), `GET .../{id}` | list, detail |
| `POST /mobile-money/collections` (`Idempotency-Key`; `partnerId, amount, msisdn, accountId, invoiceId` or `documentId`, `description`) | start a collection |
| `POST /mobile-money/collections/{id}/refresh` (`Idempotency-Key`) | ask the provider now |
| `POST /mobile-money/callbacks/{provider}/{organizationId}` (public, `X-Signature` = hex HMAC-SHA256 of the raw body) | provider callback; 401 for a bad signature, 200 for an unknown reference |

The row commits before the provider is called. `pending → successful` creates one receipt (Dr mobile money account, Cr
receivable, method `mobile_money`, reference = our reference) under the row lock, so polls and callbacks cannot post twice.
`pending → failed` posts nothing. Reversal is the existing `POST /payments/{id}/void`. `MobileMoneyPollJob` polls pending
collections older than a minute (`MOBILE_MONEY_POLL_CRON`, ShedLock). The number is stored masked. Screen:
`/payments/mobile-money`. Config: `MOBILE_MONEY_PROVIDER`, `MOBILE_MONEY_URL`, `MOBILE_MONEY_API_KEY`,
`MOBILE_MONEY_CALLBACK_SECRET` (empty refuses every callback).
## Credit control (SALES-1)

Status: built, not compiled or run. `CreditControlService` (exposure, `assertCanSell`, `assertNotOnHold`, overrides),
`CreditControlController` at `/credit-control` (`credit_control:read|manage|override`), table `PartnerCreditOverride`
(V61). `POST /invoices/:id/post` calls `InvoiceService.postChecked`; `InvoiceService.post` is unchanged for opening
balances. Screen: `/credit-control`. Test: `CreditControlIT`.

## Sales analysis (SALES-5)

Status: built, not compiled or run. `GET /reports/ar/sales-analysis` (`report:ar`, `SalesAnalysisService`), screen
`/reports/sales-analysis`. Test: `SalesAnalysisIT`.

