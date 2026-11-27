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
| R1 | B1 invoice integrity | a generated invoice cannot diverge from its source | done (PR #72) |
| R2 | B3 + P1-3 idempotency | every money route retry-safe | done 2026-11-26 (with R2b settlement) |
| R3 | P1-1, P1-2, P1-4 | approval and event integrity | done 2026-11-26 (SO-10 kernel approval: #72) |
| R4 | P1-5, P1-6, P1-7 | concurrency, dates, tenancy | done (X-6 #72; P1-6 leftovers in the 2026-11-26 re-review below) |
| R5 | P1-8, T5, T31, lifecycle ITs | integration + concurrency evidence | done: `SalesLifecycleIntegrityIT`, `CashSaleIT` T5/T31 (#73); gate-out and credit races below |
| R6 | B2 | accountant sign-off table complete | table + Sales settings → Accounting policies shipped (#74, #75); **accountant decisions pending** |
| R6b → R4d | delivery / gate-pass review | review done, blockers closed | done 2026-11-26 (DEL-1..DEL-6) |
| R7 | rehearsal on a restored live copy | owner go-live decision | data checks + rehearsal done 2026-11-26 (V683 31 ms); live = main f80b1871; **owner go-live decision pending** |

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

## 2026-11-26 — R2 to R5 fixes (branch in /home/ierp-sales-r2, not merged)

Blockers from the 2026-11-26 order-to-cash re-review:

| ID | Fix |
|---|---|
| INV-1 | Payment allocation locks the document and checks direction, partner and status; void restore locks in id order. |
| INV-2 | Credit notes must credit a posted invoice of the same customer (create and post). |
| INV-3 | Down-payment invoice cancel voids its deposit (refused once applied/refunded); cumulative down-payment cap. |
| SO-1 | Credit approval re-runs confirm in a savepoint; requester told on failure; pending requests closed on delete/cancel. |
| DEL-1 | Close short and approved changes resync the delivery order; dispatch capped at order line + tolerance. |
| DEL-2 | Serial numbers captured at dispatch (V677) and on returns. |
| X-1/X-2 | `@Idempotent(required = true)` on all sales/invoicing money routes; generic commit-evidence stamp; web keys held per dialog. |
| X-3 | Events published after commit (`publishOnCommit`) in sales, invoicing and fleet dispatch. |

Also: mock mobile-money gateway off outside test/demo; DEL-3 approval reset; DEL-4 gates on every dispatch route; DEL-5
gate-out blocked until goods are issued; DEL-6 handover refused for stocked goods; SO-6/SO-7 status refresh under lock and
closed-short-to-done; SO-8 reservation trigger respected; SO-11 stale save 409; SO-12 header tenancy; X-5 business-zone
dates; SO-5 jobs per order; invoice post confirm dialog and error toasts.

R6/R7 round (2026-11-26, owner decisions on DEL-9, INV-11 and down payments):

| ID | Fix |
|---|---|
| SO-10 | `sales:order:approve` (V678); approve/reject decide the kernel approval request (multi-step aware). |
| SO-9 / SO-13 | Duplicate keeps variant, location, tolerance, dimensions, future promise; base-currency list totals and credit checks. |
| INV-9 / INV-10 / INV-13 / INV-14 / INV-17 | Post locks; branch warehouse; credit-note journal + locks; cash-sale cancel through invoice cancel. |
| INV-11 | Legal number at post from a gap-free counter (V681); drafts `DRAFT-…`. |
| INV-12 | Dunning per customer and currency, net of credits and unallocated receipts; after-commit email (V679). |
| DEL-8 | Issue refuses stock reserved for other orders. |
| DEL-9 | Delivery losses out of COGS (5190 or configured); rejected goods to quarantine (V680). |
| DEL-10 / DEL-11 / DEL-12 | Returnable = accepted; stale gate pass blocked; note view not counted; one live delivery order. |
| X-6 | Org predicate added to 125 by-id statements in sales, invoicing and fleet. |
| X-7 | Invoice list batched; delivery list server-paged with `X-Total-Count`; reconciliation scoped; backorders capped (V682). |
| R5 | Concurrency ITs: double issue, double receipt (plus the existing concurrent-planning IT). |

Down payments keep the current model (owner decision). Rehearsal on a restored live copy: see the PR.

## 2026-11-26 — re-review of main f80b1871 (branch `fix/sales-review-gaps`)

| ID | Gap found | Fix | Evidence |
|---|---|---|---|
| P1-6b | 45 `LocalDate.now()` calls left in sales and invoicing used the JVM's UTC day (live: `APP_TIMEZONE=Africa/Kampala`). Between 00:00 and 03:00 Kampala a cash sale got yesterday's date/number year and a sale dated today was refused as "in the future"; agreement expiry, collections, recurring orders, rebates, pricing date and report "today" were a day behind in that window. | Every one now reads `LocalDate.now(BusinessZone.get())`. Posting instants stay UTC midnight of the business day, as elsewhere. | `CashSaleIT.businessDay` |
| X-7b | `GET /customer-deposits` returned every row; every cash-sale payment adds one. | Server-paged: `limit` (default 500, max 1000) and `offset`, newest first (oldest first when filtered by `salesOrderId`), total in `X-Total-Count`. Internal callers keep the full per-order list. The deposits screen shows "Showing n / total" + Load more and takes the open balance from its own `status=open` query. | web only; no IT change needed for the shape (array kept) |
| X-1b | `POST /sales/rebates/accruals/{id}/settle` (issues a credit note) took an optional key. | `@Idempotent(required = true)` and in `EVIDENCE_ROUTES`. The web already sends a key. | `SalesAgreementIT.rebates` (no key → 400, nothing settled; same key → same credit note) |
| DEL-11 | No test of the stale-pass block. | — | `GatePassIT.staleGatePass` |
| R5b | No race tests for gate-out or for confirm vs credit approval. | — | `GatePassIT.concurrentGateOut`, `SalesCreditPolicyIT.confirmVersusCreditApproval` |

Deviation (owner-approved 2026-11-27): the deposit list is now newest first and capped per page (was: all rows, oldest first).

Owner decisions 2026-11-27: every sales deviation recorded in `invoicing.md`, `sales.md`, `gate-pass.md` and
`cash-sales.md` is approved (the deployment is a demo; its data is not real). `APP_PAYMENTS_ALLOW_TEST_GATEWAY` stays
off on live.

Still open (accountant): the sign-off decisions in `sales-postings.md`, and the go-live decision.
