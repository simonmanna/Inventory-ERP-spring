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
