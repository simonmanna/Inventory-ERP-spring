# Invoicing, payments, AR/AP — `invoicing`

| | |
|---|---|
| Phase | P3b |
| Nest source | `reference/nest-api/src/modules/invoicing` |
| Routes | 30 — full table: [generated/invoicing.md](generated/invoicing.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.invoicing` |

> **ADR-S094 C4 (built, V737).** [ADR-S094](../adr/ADR-S094-centralized-sales.md) §8 lists the 27 creators of
> customer invoices and credit notes. Each one now creates its documents through `DocumentBuilderService` with a
> typed `SourceRef`, stored in `Document.sourceKind`. An organization setting can block manual customer invoices; it
> is off by default. Parity routes (`POST /invoices`, `POST /credit-notes`, `/recurring`) keep their contract. See
> Deviations.

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

- **Customer documents name their source; manual invoices can be blocked** (ADR-S094 C4, V737; AD-13 and OD-3, owner
  instruction 2026-11-28). Details in ADR-S094 §8, under "As built (C4)".
  - New column `Document.sourceKind`, written by `DocumentBuilderService`, the only class that inserts a `Document`
    row (a code-scan test enforces this). A database check requires it on sales invoices and credit notes. It is not
    serialized.
  - `POST /invoices` is `manual`. `POST /credit-notes` is `credit` when it names `reversedDocumentId`, otherwise
    `manual`.
  - The recurring job and the documents engine write their rows through ports implemented by the builder. The rows
    are unchanged.
  - Organization setting `invoicing.manualCustomerInvoices`: `allowed` (default, the Nest behaviour) or `blocked`.
    With `blocked`:
    - `POST /invoices` → 400 "Manual customer invoices are turned off for this organization: create the invoice
      from a sale";
    - `POST /credit-notes` without `reversedDocumentId` → 400 "Manual credit notes are turned off for this
      organization: a credit note must name the invoice it credits".
- **Taxed down payments are deducted, not applied** (ADR-S094 C1b, V731; accountant decisions AD-14, AD-15, AD-17
  recorded 2026-11-28 under the owner's recommended-option instruction). Nest has no down-payment invoices; these rules
  extend the Spring-only billing of sales orders. Details in `sales-postings.md` §5a.
  - An invoice line may have a negative quantity (the deduction of a down payment). Posting is sign-aware: negative
    revenue and tax lines post as debits, zero lines are skipped, a zero-total invoice posts without an AR line and is
    `paid` at once; a negative total is refused ("An invoice total cannot be negative").
  - `POST /customer-deposits/:id/apply` and `/refund` refuse a deposit registered from a down-payment invoice with VAT
    (it is deducted on the final invoice, or credited on its own invoice).
  - A credit note on a down-payment invoice credits the customer-deposit account only, with the down payment's tax,
    and consumes the deposit (refused when less is left); reversing it, or cancelling a final invoice that deducted a down payment, releases
    the deposit.
  - New post-guard hook `InvoicePostGuard.afterPost`, run in the posting transaction after the transition.

- **Sales invoices and credit notes are numbered when posted** (sales readiness INV-11, owner decision 2026-11-26).
  Nest numbers a document when the draft is created (`INV-<year>-` / `CN-<year>-` from a PostgreSQL sequence, which
  leaves gaps when drafts are deleted or a post rolls back). Drafts now carry a temporary reference
  `DRAFT-INV-xxxxxxxx` / `DRAFT-CN-xxxxxxxx`; the legal number is taken in the posting transaction from a gap-free
  counter (`GaplessCounter`, V681, seeded from the highest number already issued), with the year of the issue date.
  The generic document engine uses the same counter for these two types. Vendor bills and proformas are unchanged.
- **Other INV fixes of the R6/R7 round:** a cash-sale invoice cancel goes through the invoice cancel (approval and
  separation of duties apply, INV-17); posting locks the invoice and then the customer before the credit check (INV-9);
  the invoice stock issue and vendor-bill receipt prefer the warehouse of the document's branch (INV-10); a credit note
  posts to the sales journal of the invoice it credits (INV-13) and locks both documents in id order (INV-14). The
  invoice list merges back-office and POS rows on (id, createdAt) and loads only the page (X-7, index V682).

- **Money routes require an Idempotency-Key; settlement is validated** (sales readiness R2/R2b, 2026-11-26, owner to
  approve).
  - Invoice post/cancel, credit-note create/post/allocate/reverse, customer-deposit apply/refund/void, payment
    allocations and mobile money answer 400 "An Idempotency-Key header is required for this operation so a retry can
    never charge twice." without the header (Nest accepted the call). The web always sends one.
  - Each of those routes stamps its idempotency record before commit, so a key whose response was lost replays the
    committed outcome instead of running again.
  - A payment allocation locks the document and must match direction, partner and status: inbound settles a posted
    customer invoice or debit note of the same partner, outbound-receivable a credit note, outbound a vendor bill.
    Otherwise 400 "Document X is not an open invoice of this partner" (credit note / bill for the other directions). Nest allocated to any id.
  - A credit note must credit a posted customer invoice of the same customer (checked on create and again on post, under
    the invoice lock): "The credited document must be a customer invoice", "Invoice X belongs to another customer",
    "Invoice X is S; only a posted invoice can be credited".
  - Cancelling a down-payment invoice voids its open down-payment deposit, and is refused while that deposit is applied
    or refunded.
  - The mock mobile-money gateway is off unless `app.payments.allow-test-gateway=true` (tests, demo).
  - Invoicing events (posted, cancelled, paid, credit note) are published after commit only.

- **Generated invoices are not editable; drafts can be discarded** (sales readiness R1, 2026-11-24, owner to approve).
  - `PATCH /invoices/:id` locks the document first and answers 400 "This invoice was generated from sales order X;
    cancel the draft and invoice it again" for a draft whose `sourceType` is `sales_order` or `cash_sale`. The UPDATE
    only applies to a draft (`status = 'draft'`).
  - `POST /invoices/:id/cancel` on a **draft** discards it (`draft → cancelled`, permission `invoice:cancel`): no
    approval gate, no reversal record, no ledger or stock effect. Nest had no draft cancel, so a generated draft could
    never be removed and kept its order lines "linked".
  - Every draft with a commercial source runs the post guards, goods or not (previously only drafts whose stock a
    delivery issues). The sales guard compares each line with the order line it is linked to (product, variant, unit,
    quantity, price, discount, tax, amount ±0.01), refuses unlinked lines, checks down-payment invoices against their
    recorded amount, and checks a cash-sale invoice line by line against the sale.

- **Foreign-currency payments through a foreign account** (R-8, ADR-S076, owner-approved 2026-11-16). A payment in
  currency C may use a designated bank, cash or mobile-money account held in C, outside a register shift:
  - the cash line carries C;
  - an outflow is relieved at the account's carrying value;
  - the realised FX nets the document side and the cash side.

  A payment in another currency, or in functional currency, through such an account is refused. PO pay still refuses
  foreign accounts.

- **Review 2026-11-17** (owner-approved 2026-11-17;
  [audit](../audits/2026-11-17-accounting-costing-review.md)):
  - **ACR-2, V530: PO payments follow the receipt to its bill.** A PO receipt raises AP (receipt voucher), and the bill
    matched to that receipt becomes its open item. Unapplied payments made on the purchase order are applied to the
    bill, up to the receipt-priced share it takes over (`PurchaseOrderBillApplication`). Voiding the bill releases them
    back to the PO. AP ageing, open items and the AP snapshot count a billed receipt once, on the bill. Nest counted it
    on both, so the supplier could be paid twice.
  - **ACR-3: bill price and tax true-up.** A bill priced or taxed differently from the receipt posts the difference
    either way (PPV, input VAT, AP), so the ledger's AP equals the bill's residual. Before, a lower bill price posted
    nothing, and a higher one posted only the net difference.
  - **ACR-4: three-way match option.** Setting `procurement.billWithoutReceipt`. `block` is the default since the
    purchase readiness fixes of 2026-11-27 (see procurement.md); `auto_receive` (opt-in) keeps the Nest behaviour:
    the bill receives unreceived stock itself. `block` refuses the bill with 400
    `Bill line <product>: <qty> not received yet — post the goods receipt first (Purchasing settings: a supplier bill
    needs a goods receipt)`.

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

- **Credit-note restock against the invoice's own issue (R1, P1-2, owner-approved 2026-11-23).** The restock returns the
  goods against the credited invoice's issue rows — its `sales_invoice` rows; a cash-sale invoice
  (`stockIssuedBy`, source `cash_sale`) → the cash sale's `cash_sale` rows; a Fleet-delivered invoice → the
  `fleet_trip` rows of the loads of the delivery orders that carried its goods — with their lot, serial, location and
  cost (`StockReversalService.returnFromSource`). With no issue to return against (no credited invoice, or none of its
  goods issued) the post is refused (`Cannot restock <name>: … Post with disposition scrap if the goods are not coming
  back.`) instead of restocking at the POS location at today's cost. Fleet trip returns (`fleet_trip_return`) are not yet linked to the trip rows, so they are
  not netted. Tests `InventoryReturnsIdentityIT`, `CashSaleIT.creditNoteOnCashSaleInvoiceRestocksAtTheSaleIssue`.

- **`GET /customer-deposits` is server-paged** (sales re-review X-7b, 2026-11-26, awaiting owner approval). The list
  returned every deposit oldest first; every cash-sale payment adds one. It now takes `limit` (default 500, max 1000) and
  `offset`, sorts newest first (oldest first when filtered by `salesOrderId`) and sends the total in `X-Total-Count`.
  The response is still a plain array.
- **`POST /sales/rebates/accruals/{id}/settle` requires an `Idempotency-Key`** (X-1b, awaiting owner approval): it
  issues a credit note, like the other money routes of R2.

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

## Tax groups, several taxes per line, withholding and reverse charge (ADR-S071, built)

These are Spring-native extensions. Lines that use none of them behave exactly as before. See
[ADR-S071](../adr/ADR-S071-tax-groups-withholding-reverse-charge.md).

- **Line taxes:**
  - Invoice, vendor bill and credit-note lines accept `taxIds` (ordered).
  - A single `taxId` may now name a group or a reverse-charge tax.
  - Such lines store `DocumentLine.taxIds` and a `DocumentLineTax` breakdown, and render `taxIds` and `taxes` (taxId,
    name, code, kind, rate, base, amount, sequence).
  - GL posts one tax line per tax account.
- **Vendor bills:**
  - `withholdingTaxId` on create and update adds the withholding tax to every product line.
  - Posting credits AP net of withholding and credits `withholding_payable`.
  - `Document.withholdingAmount` (shown only when not zero) holds the withholding, and the residual is net of it.
  - An issued `WithholdingCertificate` is written per withholding tax and cancelled with the bill.
  - Reverse-charge taxes post Dr `tax_receivable` / Cr `tax_payable` and add nothing to the payable.
- **Payments:**
  - A customer receipt may carry `withholdingAmount` (+ `withholdingTaxId`, `withholdingCertificateNumber`): Dr cash
    net, Dr `withholding_receivable` (1455, created on first use), Cr AR gross, and a received certificate.
  - A supplier payment cannot withhold on a bill that already withheld.
  - Manual supplier-payment withholding writes an issued certificate.
  - Voiding a payment cancels its certificate.
- **Routes** (`withholding:read` / `withholding:update`):
  - `GET /withholding-certificates`;
  - `GET /withholding-certificates/report`;
  - `GET /withholding-certificates/:id`;
  - `PATCH /withholding-certificates/:id`.
- **Tax summary and pack:** read breakdown lines per tax. Reverse charge counts as output and input.
