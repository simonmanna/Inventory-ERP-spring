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
