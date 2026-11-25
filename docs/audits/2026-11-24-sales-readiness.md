# Sales flow production readiness — 2026-11-24

Scope: back-office order-to-cash (quotation → order → invoice → payment / deposit / credit note) and branch cash sales,
reviewed read-only on `origin/main` b9f35b3c. Delivery / gate pass is reviewed separately (R6b).

## Verdict

**NO-GO** (owner's reviewer, 2026-11-24). The core is sound: row locks before status reads, a workflow row lock with a
status re-read, posting-key dedupe, a unique invoice/order-line link, a post-time over-invoicing guard, nightly
fulfilment + financial reconciliation, and one-transaction cash-sale invoicing. Three blockers and eight P1 gaps remain.
Green CI is not production approval.

## Findings

| # | Finding | Stage |
|---|---|---|
| B1 | A draft invoice generated from a sales order (or cash sale) could be edited (`PATCH /invoices/:id`) to any lines; the post guard checked link quantities only, so it posted. `update` had no lock and no draft predicate on its UPDATE. A draft could not be discarded at all. | R1 |
| B2 | Accountant sign-off open: `sales-postings.md` §5 questions; `cash-sales.md` production gate. | R6 |
| B3 | Money routes accept a missing `Idempotency-Key` (invoice post / cancel, SO invoicing, deposits, credit notes, cash-sale money steps). | R2 |
| P1-1 | An approved credit request confirms an order past its commercial approval and the stock block. | R3 |
| P1-2 | Invoice events are published even when the transaction rolls back. | R3 |
| P1-3 | Idempotency keys outside `OUTCOME_ROUTES` stay pending after a crash between commit and completion. | R2 |
| P1-4 | Invoice cancel: the REQUIRES_NEW approval check keeps the key pending after the 403. | R3 |
| P1-5 | Payment void restores invoice balances without locking them. | R4 |
| P1-6 | Cash-sale back-dated posting uses UTC / server date, not the business zone. | R4 |
| P1-7 | Some lookups by id lack the organization predicate (RLS covers them). | R4 |
| P1-8 | No concurrency ITs on the money paths; cash-sale T5 / T31 missing. | R5 |

## Stages

| Stage | Scope | Exit condition | State |
|---|---|---|---|
| R1 | B1 invoice integrity | a generated invoice cannot diverge from its source | done (this PR) |
| R2 | B3 + P1-3 idempotency | every money route retry-safe | |
| R3 | P1-1, P1-2, P1-4 | approval and event integrity | |
| R4 | P1-5, P1-6, P1-7 | concurrency, dates, tenancy | |
| R5 | P1-8, T5, T31, lifecycle ITs | integration + concurrency evidence | |
| R6 | B2 | accountant sign-off table complete | |
| R6b | delivery / gate-pass review | review done, blockers closed | |
| R7 | rehearsal on a restored live copy | owner go-live decision | |

## R1 — invoice integrity

- `InvoiceService.update` locks the document, refuses drafts generated from a sales order or cash sale, and updates only
  a draft.
- `POST /invoices/:id/cancel` discards a draft (no ledger / stock effect); the order lines become invoiceable again.
- Post guards run on every draft with a `sourceType`. `SalesInvoicePostGuard` checks each sales-order line against its
  linked order line (product, variant, unit, quantity, price, discount, tax, amount ±0.01), refuses unlinked lines,
  checks down-payment invoices, and checks cash-sale invoices line by line against the sale.
- Tests: `SalesInvoiceIntegrityIT` (edit refused + discard + re-invoice; quantity / price / product / discount / tax /
  extra-line tampering refused with no journal; down-payment tampering refused). Regression: SalesBillingIT,
  SalesInvoiceabilityIT, CashSaleIT, SalesQuoteProformaIT, InvoicingIT, ControlAccountRoutesIT, IntegrityI2IT,
  DocumentReversalIT, Acc8PostingIntegrityIT, Acc0ReversalIT, InventoryReturnsIdentityIT, SalesOrderIT, SalesDeliveryIT,
  SalesFulfilmentIT, SalesWeighbridgeIT, FxDepositsIT, SalesHandoverIT, SalesQuantityBucketsIT green.
  `SalesReturnFlowIT.writeOff` fails on a fresh DB before and after (needs a seed-only account; pre-existing).

### Data check (run once per database, read-only; lists candidates, corrects nothing)

Posted or paid invoices generated from a sales order whose lines differ from their links — each row is an invoice
that billed something the order did not authorise and needs an accountant's review (credit note or re-invoice).

```sql
SELECT d."organizationId", d."documentNumber", o.number AS "order", l.description,
       k.quantity AS linked_qty, dl.quantity AS invoiced_qty, l."unitPrice" AS order_price, dl."unitPrice" AS invoiced_price,
       k.amount AS linked_amount, dl.total AS invoiced_total
FROM "Document" d
JOIN "SalesInvoiceLink" k ON k."documentId" = d.id AND k.kind = 'regular' AND k.origin = 'sales'
JOIN "SalesOrderLine" l ON l.id = k."orderLineId"
JOIN "SalesOrder" o ON o.id = k."orderId"
LEFT JOIN "DocumentLine" dl ON dl.id = k."documentLineId"
WHERE d."sourceType" = 'sales_order' AND d.status::text IN ('posted', 'paid')
  AND (dl.id IS NULL OR dl.quantity <> k.quantity OR dl."unitPrice" <> l."unitPrice"
       OR dl."productId" IS DISTINCT FROM l."productId" OR dl."discountPercent" <> l."discountPercent"
       OR abs(dl.total - k.amount) > 0.01)
UNION ALL
SELECT d."organizationId", d."documentNumber", NULL, x.description, NULL, x.quantity, NULL, x."unitPrice", NULL, x.total
FROM "Document" d JOIN "DocumentLine" x ON x."documentId" = d.id
WHERE d."sourceType" = 'sales_order' AND d.status::text IN ('posted', 'paid')
  AND EXISTS (SELECT 1 FROM "SalesInvoiceLink" k WHERE k."documentId" = d.id AND k.kind = 'regular')
  AND NOT EXISTS (SELECT 1 FROM "SalesInvoiceLink" k WHERE k."documentLineId" = x.id);
```

Invoices created before V500 have no `documentLineId` on their links and appear in the first part with
`invoiced_qty` empty; compare those by hand.
