# ADR-S071 — Tax groups, several taxes per line, withholding tax and reverse-charge VAT

- Status: accepted (built 2026-11-10, branch `phase/acc-tax`)
- Builds on: ACC-7 (V323: fiscal positions that act, VAT settlement, tax-period lock) and the tax engine
  `TaxCalculationService` (port of `tax-calculation.service.ts`, ADR-010).
- Migration: `V482__tax_groups_withholding_reverse_charge.sql`
- Extension: no NestJS counterpart. Modules `core` (taxes), `invoicing` (documents, payments, certificates),
  `reporting` (cash-basis VAT reports), `projects` (subcontract valuations), `procurement` (debit notes).

## Context

The ERP serves construction and construction-supply businesses in East Africa. Their tax work had gaps:

- A document line carried one `taxId`. Excise plus VAT on top, or VAT plus a levy, could not be expressed.
- `TaxCalculationService` could do several taxes, compound and inclusive, but the document builder only ever passed
  it one tax.
- Withholding tax existed only as a manual `withholdingAmount` on a supplier payment. Bills from subcontractors and
  hauliers (where WHT is deducted at source) showed the gross as payable. Nothing produced a certificate or a list to
  file. Customers that withhold from our receipts (government, large contractors) could not be recorded.
- Imported services (design, software, consultancy) need reverse-charge VAT: the buyer self-assesses output VAT and
  claims the same amount as input. There was no way to post it.

## Decision

### 1. Tax groups: a `Tax` of kind group

A group is a row in `Tax` with `isGroup = true` and its children, in order, in `TaxGroupMember`. We chose this over a
separate `TaxGroup` table because a group then works **everywhere a tax id is accepted**: a line's `taxId`, a product's
default tax, a fiscal-position mapping target, a contract's tax. No caller needs to know that groups exist.

Rules (`TaxService`):

- a group needs at least one child;
- groups do not nest;
- a group cannot contain itself or list a child twice;
- inclusive, compound and reverse-charge flags belong to the children, not to the group;
- `POST/PATCH /taxes` accept `isGroup`, `reverseCharge` and `children` (ordered tax ids);
- group taxes render their `children`, and other taxes keep their exact shape apart from the two new boolean columns.

Debit notes (procurement, own tables) expand a group in the line `taxId` when they post: exclusive, compound on the
running total, one tax line per account. Withholding and reverse-charge children are ignored on a note.

### 2. Several taxes per line: `taxIds`, one engine, a stored breakdown

Invoice, vendor bill and credit-note lines accept an optional `taxIds` list. The line goes through the **multi-tax
path** when any of these is true:

- it states a non-empty `taxIds`;
- its single tax (stated or the product's) is a group;
- its single tax is reverse-charge.

On that path:

- each id is mapped by the fiscal position (children of a group too);
- groups expand to their children;
- output taxes and withholding go through `TaxCalculationService`, which handles compound and inclusive;
- reverse charge is computed on the net;
- `DocumentLine.taxIds` keeps the request;
- `DocumentLineTax` keeps the breakdown, one row per tax with kind `tax`, `withholding` or `reverse_charge`, plus
  rate, base and amount;
- `DocumentLine.taxId` holds the first non-withholding tax (or the group);
- the line's `taxAmount` and `total` include only kind `tax`.

Every other line takes the **legacy path**, unchanged byte for byte: no breakdown rows, `taxIds` NULL and
`withholdingAmount` 0. That includes a single `taxId` of type withholding, which still computes nothing; withholding
applies only through `taxIds` or the bill header. Withholding and reverse charge are refused on sales documents with
"Withholding and reverse-charge taxes apply to vendor bills only". Withholding now rounds to the currency, like output
tax.

GL: `groupForPosting` builds one tax line per tax account from the `tax` rows of the breakdown, using the tax's own
account, else `tax_payable` or `tax_receivable`. The tax summary (`/reports/tax/summary`, pack) and the cash-basis VAT
reports (`TaxCashReports`) read breakdown lines per tax and legacy lines as before.

### 3. Withholding on vendor bills

- `POST/PUT /vendor-bills` accept `withholdingTaxId`. It adds that withholding tax to every product line's tax list.
  A line without a list keeps its own or its product's tax next to it. A WHT tax can also be put on a single line
  through `taxIds`.
- `Document.withholdingAmount` holds the sum. `amountResidual` is total minus withholding from the draft onwards. The
  column is not mapped on the entity, and documents expose `withholdingAmount` only when it is not zero.
- Posting credits AP with total − WHT and credits `withholding_payable` with the WHT. If vouchered receipts already
  raised AP, so the postable AP is smaller than the WHT, the counter line becomes a debit for the difference. The
  open payable (residual) is net of WHT.
- An **issued certificate** (`WithholdingCertificate`) is written for each withholding tax on the bill. It records the
  sequence number `WHT-<year>-nnnnn`, supplier, base, rate, amount, date and journal entry. Voiding the bill cancels
  it.
- A supplier payment that sets `withholdingAmount` on a bill that withheld at posting is refused: "Bill … already
  withheld tax when it was posted; pay it without withholding". A manual payment withholding on other bills still
  works as before, and now also writes an issued certificate. It can name a `withholdingTaxId`, which supplies the
  rate and the derived base.
- Payment and residual status compare the residual with total − withholding.
- Subcontract valuations (`POST /projects/subcontracts/:id/valuations`) accept `withholdingTaxId`. The valuation bill
  withholds on its net amount, after retention.

### 4. Withholding suffered (customer side)

A customer receipt (`POST /payments`) may now carry `withholdingAmount`, plus optional `withholdingTaxId` and
`withholdingCertificateNumber`. The rule against refunds, deposit applications, store credit, foreign currency and
POS-invoice allocations is unchanged.

- It posts Dr cash (net), Dr `withholding_receivable` (WHT) and Cr AR (gross). The allocated documents are relieved by
  the gross.
- A **received certificate** records the customer's own number when given, else `WHTR-<year>-nnnnn`. A void cancels
  it.
- `withholding_receivable` is added to the mapping registry in `packages/shared/src/accounting/account-mappings.ts`
  with `required: false`. The COA template comes from the read-only Nest reference, so the account is created on first
  use as **1455 Withholding Tax Receivable** (`AccountResolverService.ensureByCode`).
- The mapping is also a tax account for the tax-period lock.

### 5. Reverse-charge VAT

`Tax.reverseCharge` is valid only on a vendor bill. The line total and the payable exclude it. Posting debits
`tax_receivable` and credits `tax_payable` with the same amount. Both accounts are the mapped ones, not the tax's own
account, so the self-assessment shows up in the VAT settlement preview on both sides with net 0. The tax summary adds
the breakdown amount to output and input. The URA-style VAT return (`TaxCashReports`) puts it in standard-rated
supplies and purchases.

### 6. Routes and permissions

| Route | Permission |
|---|---|
| `GET /withholding-certificates?direction&partnerId&status&from&to&page&pageSize` | `withholding:read` |
| `GET /withholding-certificates/report?direction&from&to` (by partner and tax, with ledger tie-out) | `withholding:read` |
| `GET /withholding-certificates/:id` | `withholding:read` |
| `PATCH /withholding-certificates/:id {number?, notes?}` (number only on received certificates) | `withholding:update` |

V482 grants both permissions to Administrator roles, and `withholding:read` to every role holding `report:accounting`.
Money routes are unchanged and stay idempotent (`/payments`, `/supplier-payments`, valuations).

## Consequences

- When a line has several taxes, its taxable base appears under each tax in the tax summary. The "total sales" box
  therefore counts that base more than once, which is how per-rate VAT returns read.
- Reverse charge always posts to the mapped input and output VAT accounts, never to the tax's own account.
- Bills and receipts only record withholding. Paying withholding over to the authority is an ordinary journal or a
  supplier payment to the revenue authority partner. The report's ledger tie-out excludes entries with source
  `withholding_remittance`, kept for a future remittance flow.
- The web adds group editing on the tax-rates screen, a tax picker on invoice and bill lines, a withholding selector
  on the bill header, and a Withholding Certificates page with the filing summary.
