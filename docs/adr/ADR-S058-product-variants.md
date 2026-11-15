# ADR-S058 — Product attributes & variants

- Status: accepted (owner request, 2026-11-05).
- Spec: [`docs/spec/product-variants.md`](../spec/product-variants.md).
- Extension: no NestJS counterpart for variant management. New tables (V370) and routes; existing line DTOs gain an
  optional `variantId`. Requests without it behave as before.

## Context

The schema has carried `ProductVariant` and a variant-aware stock engine since F.8, but nothing could create
variants, price them, or put them on sales and purchase documents. The owner wants Odoo-style management: attributes
with values on the product page, generated variants, per-variant prices, and sales, purchasing and inventory
valuation that follow the variant.

## Decision

1. Separate masters `VariantAttribute` / `VariantAttributeValue` (the INV-2 `ProductAttribute` is a specification
   attribute with a different meaning and is left alone).
2. A product's `ProductAttributeLine`s define the combinations; variants are generated, never hard-deleted
   (archived), identified by `combinationKey`.
3. Price = variant fixed price, else product price + value price extras. The sales pricing engine uses it as the list
   price, so all pricelist tiers apply unchanged.
4. A product with active variants requires a `variantId` on every sales/purchase line and stock movement; the stock
   engine already keeps quants, cost layers and ledger per variant, so valuation per variant needs no new posting
   logic.

## Consequences

- Inventory value, COGS and margins are per variant automatically once lines carry `variantId`.
- Pricelist fixed-price items remain per product (review item).
- POS menu variants (`MenuItemVariant`) are unchanged.

## Addendum — Phase I2 variant identity (owner decision D2, 2026-11-15)

Decision 4 was enforced only where a document called `ProductVariantService.resolve`; the stock engine let a variant
product move without a variant whenever a legacy no-variant quant existed, and never checked `isActive`. Now:

1. **New stock names an active variant.** `StockService` receipts (goods receipt, bill, production output, positive
   count, the layered `adjustment_in`) of a product with variants refuse a missing variant ("Choose a variant for X")
   and an inactive one ("Variant is inactive: it cannot receive stock"), even while a legacy quant exists.
2. **Legacy stock runs out.** Issues, transfers, negative counts and return restocks (`return_in`) may still move the
   legacy no-variant quant while it exists; an archived variant's remaining stock can still be issued, moved, counted
   down or restored. The parent product never stands in for a missing variant otherwise.
3. **Documents.** Vendor bills require an active variant (they receive stock); user-entered invoices refuse an
   archived variant; invoices copied from an earlier document (sales-order billing, Fleet delivery) keep it; credit and
   debit notes accept the product's own variant even if archived; a credit-note line without a variant is refused when
   the credited invoice holds several variants of the product. Purchase requests, BOMs (output and components),
   production orders, manual Fleet delivery orders and replenishment / reorder rules require an active variant for a
   product with variants.
4. **Counts and planning.** Device `stock.count` lines may carry `variantId` (one variant counted; others untouched);
   a variant product without one is a 400, not a 409 retry. Replenishment rules (`InventoryReplenishmentRule`) and
   purchase reorder rules (`ReorderRule`) carry an optional `variantId` (V486); a variant rule measures that variant's
   stock, reservations, open supply and use, and the purchase request / transfer / production request it raises
   carries the variant. The par report (`/inventory/reports/reorder`) lists one line per active variant, each compared
   with the product's `minQuantity` (a variant's surplus never hides another's shortage).

Wire additions (optional fields, older clients unaffected): `variantId` on manual delivery-order lines, replenishment
rules, reorder rules and device count lines; `variantId` / `variantName` / `unitError` on par-report lines.

### Variant costing (audit C-1 / C-2, owner decision D3)

- AVCO and FIFO cost per variant quant and lot; SPECIFIC per serial. Wherever the stock engine falls back to "the
  product cost" (first move of an empty quant, overflow past the layers, counts, return restocks, shortfalls) a variant
  now uses `COALESCE(variant.costPrice, product.costPrice)`; the valuation report's zero-average fallback does the same.
- **STANDARD stays per product** (D3): a variant of a STANDARD product is valued at the product's standard. Known limit.
- The item list (`/inventory/product-stock-levels`), item detail (`/inventory/items/{id}`) and movement report value
  stock by costing method through `InventoryValuationService.currentValueByQuant` (lots, serials, standard), not
  `qty × runningAverageCost`, which FIFO and STANDARD receipts never set.

### Variant pricing and precedence (Phases I3 / I4: audit P-1, P-3, P-5, S-7; owner decision D1)

**List price of a line** (one rule in the sales engine, the invoice builder, the Orders module and Fleet delivery
orders):

1. The default price is the variant's price when the line names a variant (fixed `salesPrice`, else product price +
   attribute extras), else `Product.salesPrice`. It is the price **per default sales unit** (D1, ADR-S003 addendum).
2. A line in the default sales unit (or with no unit) takes that price as is; it is never multiplied by the unit's
   factor.
3. A line in another unit takes that unit's own `ProductUom.salePrice` when set — **for a line without a variant**.
   A variant's price wins over the product's unit price (P-3).
4. Otherwise the default price is scaled: `price × factor(line unit) / factor(default sales unit)`.

**Rules that name a variant (P-1).** `PricelistItem`, `SalesPromotion` and `SalesAgreementLine` take an optional
`variantId` (`V488__variant_price_rules.sql`; must be a live variant of the rule's product, else 400 "Variant not
found"; pricelist rules only with scope `product`). Within a tier:

- a rule naming the line's variant beats the same tier's rule for the whole product (pricelist: after `priority`;
  promotion: first);
- a rule naming another variant never applies;
- a rule without a variant applies to every variant, as before. A product-level **fixed** price therefore still prices
  every variant alike; give the variants their own rules to price them apart.
- An agreement still lists a product once (release balances are per product); its line may be restricted to one
  variant. The tier order (ADR-S022 §4) is unchanged.

`PriceQuery` carries `variantId`, so the invoice, Orders module, Fleet manual delivery order, price snapshots and
`POST /price-lists/resolve` (optional `variantId`, additive) price the variant. CRM price rules and named price lists
(`PriceListItem`) have no variant column: they keep applying to every variant.

**P-5.** A manual Fleet delivery order line without a list price takes the catalogue price by the rule above (it was
the bag price whatever the line's unit).

**S-7.** Quotation approval re-prices each line as it was priced: its unit, variant and the quotation's site, branch,
currency and agreement (it used no unit, branch, site or agreement and could hide or invent a discount).

Tests: `VariantPricingIT` (D1 box line, P-3, P-1 pricelist and promotion, S-7),
`FleetDispatchIT.manualOrderPriceScaledToTheUnit`.
