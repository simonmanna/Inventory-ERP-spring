# ADR-S072 — POS product variants, per-line stock posting and kitchen waste

- Status: accepted (owner decision D4, 2026-11-15). Built in Phase I1a, branch `phase/I1a-pos-integrity`.
- Source: `docs/audits/2026-11-15-sales-variant-uom-inventory-audit.md`, findings V-1, V-2, V-3, S-1 (now confirmed)
  and T-1.
- Deviation from NestJS. Each item below differs from `reference/nest-api/src/modules/pos`.
- Migration: none.

## Context

The POS was ported from Nest, which had no product variants. When ADR-S058 added `ProductVariant` as a real
stock-keeping unit (own quant, own price), the POS was not changed to match:

- **Variant stock (V-1).** A POS product line carries `variantId`, but the stock posting job issued the *parent*
  product. A product with variants either failed to post ("Choose a variant for …") or drained an old quant that had
  no variant. A restock refund looked for the issue by variant and found nothing.
- **Variant price (V-2).** The POS priced a variant only from its fixed `salesPrice`. A variant priced by attribute
  extras sold at the base product price. Sales and invoices use the effective price
  `COALESCE(v.salesPrice, p.salesPrice + v.priceExtra)`.
- **Kitchen waste (V-3).**
  - It issued a plain product in the base unit, although the order line is in the sales unit (1 box wasted as
    1 piece).
  - It ignored the variant.
  - It ran outside any transaction and swallowed each failure with a log line, so the waste was lost for good.
- **Per-line tolerance did not work (S-1, confirmed by `PosVariantIntegrityIT`).**
  - The posting job ran all lines in one transaction with a JDBC savepoint per line.
  - A line failing *inside* `StockService.issue` (its own `@Transactional`, REQUIRED) marked the job's transaction
    rollback-only.
  - The commit then rolled back every good line of the sale, and the job retried with the same outcome until it
    failed as a whole invoice.
  - Failures thrown before `stock.issue` (for example a menu item without a recipe) were unaffected. That is why the
    existing tests never caught it.

## Decision

1. **POS stock issue uses the product variant.**
   - The plain-product branch of `PosInvoiceService.issueLine` sets `StockMove.variantId` from the order line.
   - Menu items never reach this branch, so a `MenuItemVariant` id is never passed as a product variant.
   - Restocking from the invoice-item snapshot returns the goods to the variant they were sold from.
   - Products without variants are unchanged.
   - Legacy stock without a variant can still be sold until it runs out (owner decision D2). That check stays in
     `StockService.requireVariant`.
2. **POS prices variants like Sales.** `PosOrdersService.resolveLines` reads the effective price with
   `ProductVariantService.PRICE_SQL`:
   - a variant's fixed price wins;
   - otherwise the price is the product price plus the attribute extras.
3. **Each stock-posting line commits on its own (owner requirement R-B).**
   - `PosInvoiceService.processStockPostingJob` now runs three kinds of short transaction:
     - a claim read;
     - one transaction per line and per paid modifier/accompaniment (`postOnce`);
     - a completion.
   - `postOnce` locks the job row, skips a key already in `postedLineKeys`, issues the unit, and appends its key, all
     in one transaction.
   - Expected behaviour when line 1 ✔, line 2 ✔, line 3 ✘, line 4 ✔:
     - lines 1, 2 and 4 are committed and stay committed;
     - line 3 is an open `InventoryException`;
     - the job stays `pending` (retryable) with the keys of lines 1, 2 and 4;
     - a retry issues line 3 only;
     - a concurrent drain never issues a line twice.
   - The invoice's record of what was posted is the job's `postedLineKeys` plus the per-line ledger rows and
     `InvoiceItemRecipeIngredient` snapshots.
   - A whole-job failure is now logged (`[stock] posting job … failed`).
4. **Kitchen waste.**
   - A plain product is wasted in the order line's unit (the product's sales unit), with the line's variant.
   - Quantities use `BigDecimal`.
   - Each line posts in its own transaction (`REQUIRES_NEW`), so a recipe is all or nothing.
   - A failed line is recorded as an open `InventoryException` of kind `kitchen_waste`, with payload `orderId`,
     `orderItemId` and `sourceId`. There is one open exception per line, and the record stays visible for manual
     resolution. The cancellation is never undone.
5. **POS posting timing and refund (T-1).** Approved here; built in I5.
   - When stock posts at confirmation, payment or fulfilment, the job is linked to the invoice, so these sales get
     the register location, the saved unit, snapshots, `sourceId` and reservation consumption.
   - The restock guard also finds jobs by order.

6. **Retry a pending job (owner request, 2026-11-15).**
   - The posting monitor's retry also accepts a `pending` job: attempts reset to 0 and `nextRetryAt` to now, so the
     next drain runs it. Since item 8 every runner claims a job (`processing`) before it works, so a pending job is
     never being processed.
   - A job keeps `postedLineKeys`, so its posted lines are never issued twice.
   - `processing` and `done` jobs are still refused: "Only failed or pending jobs can be retried (this one is
     <status>)". Nest said "Only failed jobs can be retried …".

7. **Stock checks and reservations per variant, in base units (review R2, 2026-11-15).**
   - The `strict` / `pre_invoice` availability check reads the line's own quant: a product line with a
     `ProductVariant` reads that variant's quant. Nest read the parent product (variant key `''`), which is empty for
     a product with variants, so every variant sale was refused as "Low stock".
   - The quantity is converted to the base unit before the comparison: a product line in its sales unit, a recipe
     ingredient in `MenuProduct.uomId`, a combo component in its sales unit. Nest compared the sale-unit quantity
     with base-unit stock (3 boxes of 12 against 30 pieces passed). The "need N" in the message is now in base units.
   - Invoice reservations are made per (product, variant) quant in base units, in `BigDecimal` (Nest: per product,
     sale units, `double`).
   - A unit that does not convert is refused with the conversion's 400 (never 1:1).
8. **Every runner claims the job; state changes are compare-and-set (review R3, 2026-11-15).**
   - `processStockPostingJob` claims the job with one atomic update (`pending` / `failed` / stale `processing` →
     `processing` + a new token) unless the worker already holds the batch claim. A job another runner holds, or a
     done job, is skipped. Nest's shift-close drain and sync close drain ran pending jobs without a claim.
   - The completion and the failure writes apply only while the claim is still the runner's own; a runner whose
     stale claim was taken over leaves the outcome to the new holder.
   - The monitor retry is a compare-and-set on the status it read (and, for `pending`, on the job being unclaimed).
     If the job changed in between, it answers 409 "Stock posting job changed while retrying (now <status>); refresh
     and try again" and changes nothing, so a processing or done job is never re-opened.
9. **A retried line closes its exception (review R4, 2026-11-15).**
   - When `postOnce` posts a line that an earlier attempt failed, the line's open `InventoryException` (same invoice,
     same `lineKey`) is set to `resolved` with the note "Posted automatically when the stock posting job retried this
     line", in the same transaction as the issue. The row, its reason and stack trace are kept.
   - Without this the monitor kept asking an operator to correct stock that had in fact been deducted (risk of a
     manual double deduction). Nest kept exceptions open until resolved by hand.

## Consequences

- A POS sale of a variant now issues and refunds that variant's quant, and the cost comes from that quant.
- POS and Sales prices for the same variant now agree.
- One failing line no longer blocks the stock of a whole sale.
  - Lines that posted before the failure are not undone.
  - Accounting is unchanged per line: each line's COGS posts with its stock move, and its key on the job (committed
    in the same transaction) prevents a second issue.
- Kitchen waste of products sold in packs or as variants now posts the right quantity from the right quant. Failures
  are visible in the inventory exceptions workspace.

## Tests

`PosVariantIntegrityIT`:
- R-B line atomicity and retry;
- V-1 variant sale and restock;
- V-2 extras and fixed price;
- V-3 variant waste, box waste and recorded failure.

Regression: `PosStockPostingIT` (exactly-once under a reclaim race, recipe snapshot, F-COGS, dating), `PosFlows2IT`,
`PosFlows3IT`, `PosMoneyIT`, `SyncInventoryReplayIT`, `ProductVariantIT`.

Retry of a pending job: `PosStockPostingIT.retryPendingJob` (no second issue).

Review R2–R4: `PosVariantIntegrityIT.strictAvailabilityIsVariantAware`, `availabilityAndReservationsInBaseUnits`,
`retryResolvesTheLinesException`; `PosStockPostingIT.drainSkipsAJobHeldByAnotherRunner`,
`retryRacingADrainNeverReopens`.
