# Product variants (attributes & variants) — ADR-S058

Owner request 2026-11-05: manage product variants from the product page ("Attributes & Variants" tab, see
`reference/salesss/variants.png`), let each variant carry its own price, and make sales, purchases and inventory
valuation work per variant.

No NestJS counterpart for the management part (Nest only has the `ProductVariant` table, F.8, and threads
`variantId` through stock). Everything here is an **extension**; existing contracts keep working when `variantId`
is absent.

## What already exists (before PV)

| Piece | State |
|---|---|
| `ProductVariant` (sku, barcode, name, attributes JSON, salesPrice, costPrice, isActive) | table + entity, no API |
| `Product.hasVariants` | column, never set |
| Stock engine (`StockService`) | quants, batches, serials, ledger, AVCO per `(product, variant, location)` |
| `variantId` on StockItem, InventoryBatch, InventoryLedger, InventorySerial, StockOut/Waste/Adjustment/Transfer items, InventoryCountLine, InvoiceItem (+ `variantName`), OrderItem, production lines | yes |
| `variantId` on sales quotation/order/return lines, cash-sale lines, fleet delivery-order lines, PR/PO/GRN/debit-note lines | **no** |

## Data model (V370)

```
VariantAttribute        id, organizationId, name, displayType(radio|pills|select|color), sequence, isActive, deletedAt
VariantAttributeValue   id, organizationId, attributeId, name, htmlColor, sequence, isActive, deletedAt
ProductAttributeLine    id, organizationId, productId, attributeId, sequence          (unique product+attribute)
ProductAttributeLineValue id, organizationId, lineId, valueId, priceExtra NUMERIC(20,6) (unique line+value)
ProductVariant  + combinationKey TEXT (sorted value ids joined by ','), + priceExtra NUMERIC(20,6) DEFAULT 0
```

`ProductAttribute` (INV-2 specification attributes) is unrelated and untouched; variant attributes live under
`/variant-attributes`.

## Rules

1. **Generation.** Saving a product's attribute lines generates the cartesian product of the selected values. A
   combination that has no variant gets one (name = values joined by `, ` in line order, e.g. `White, Large`); a
   variant whose combination disappeared is **archived** (`isActive = false`), never deleted — stock, ledger and
   documents may reference it. Re-adding the combination re-activates the same row. Limit: 1 000 combinations.
2. **`Product.hasVariants`** = the product has at least one attribute line.
3. **Price.** Variant sales price = `ProductVariant.salesPrice` when set (fixed override), else
   `Product.salesPrice + Σ priceExtra of its values` (Odoo "price extra", set with **Configure** on the line).
   `ProductVariant.priceExtra` caches the sum. Cost price = `ProductVariant.costPrice` when set, else
   `Product.costPrice`. Units of measure scale the price as for the product.
4. **Pricing engine.** The sales pricing engine takes an optional `variantId`; the variant price becomes the list
   price, so pricelists, % rules, customer discounts and agreements apply on top of it. Fixed pricelist items stay
   per product.
5. **Variant required.** A sales or purchase line, or a stock movement, for a product that has active variants must
   name one: `"Choose a variant for <product name>"`. A variant must belong to the line's product and be active:
   `"Variant not found"`.
6. **Stock and valuation.** Every stock movement passes the line's `variantId`, so each variant has its own on-hand,
   AVCO/FIFO layers and value. Inventory valuation already groups by `variantKey`; reports show the variant name.
7. **Archiving a value or attribute** in use by a product is refused: `"This value is used by N product(s)"`.

## Routes (new)

| Verb | Path | Permission |
|---|---|---|
| GET | `/variant-attributes` | `product:read` |
| POST | `/variant-attributes` (with `values[]`) | `product:update` |
| PATCH | `/variant-attributes/{id}` (name, displayType, values upsert) | `product:update` |
| DELETE | `/variant-attributes/{id}` | `product:update` |
| GET | `/products/{id}/variant-config` → `{lines:[{attribute, values:[{valueId,name,htmlColor,priceExtra}]}], variants:[...]}` | `product:read` |
| PUT | `/products/{id}/attribute-lines` `{lines:[{attributeId, valueIds[]}]}` → regenerates | `product:update` |
| PATCH | `/products/{id}/attribute-lines/{lineId}/values` `{values:[{valueId, priceExtra}]}` (Configure) | `product:update` |
| GET | `/products/{id}/variants` (with price, cost, on-hand) | `product:read` |
| GET | `/product-variants?search=&productId=` (line pickers) | `product:read` |
| PATCH | `/products/{id}/variants/{variantId}` (sku, barcode, salesPrice, costPrice, isActive) | `product:update` |

Existing line DTOs gain optional `variantId` (sales quotation/order/return lines, cash sale lines, PR/PO/GRN/
debit-note lines). Responses add `variantId` and `variantName`.

## Phases

| Phase | Scope | Status |
|---|---|---|
| PV-0 | This spec + ADR-S058 | done |
| PV-1 | V370 tables, `ProductVariantService` (attributes, lines, generation, price), routes, `ProductVariantIT` | done |
| PV-2 | Web: "Attributes & Variants" tab (attribute/value chips, Configure price extra, variants grid with price/SKU/barcode/active) + attribute master dialog | done |
| PV-3 | Sales: `variantId` on quotation → order → fleet delivery → invoice item, cash sale, returns/credit notes; pricing engine; stock issue per variant | done |
| PV-4 | Purchases: `variantId` on PR → PO → GRN → bill → debit note; stock receipt per variant | done |
| PV-5 | Inventory: stock-health/valuation report carries `variantName`; web variant picker on sales order, cash sale and purchase order lines | done |
| PV-6 | Review fixes (2026-11-06): V371 `DocumentLine.variantId/variantName` (invoices, vendor bills, credit notes keep the variant; lines without a variant keep their exact JSON shape); `InvoiceLine.variantId` on invoice / bill / credit-note DTOs; vendor-bill auto-receive keeps the variant (`receiveFromBill` dropped it); stock engine refuses a move without variant on a variant product (`StockService#requireVariant`, unless a variant-less quant already exists there); credit-note restock falls back to the credited invoice's variant; MENA demo seed phase `variants` | done |

### Where the variant flows (code)

- Validation + price: `core/application/ProductVariantService#resolve`, `SalesPricingEngine.Context#variantId`.
- Sales: `SalesQuotationService#writeLines`, `SalesOrderService#line/insertLines`, `SalesDeliverySource` →
  `FleetDeliveryOrderService` → `FleetDispatchService` (issue / put-back), `SalesBillingService#createInvoice`
  (InvoiceItem.variantId/variantName), `InvoicingWorkflowsInitializer` (auto-issue, bill auto-receive),
  `CreditNoteService` (restock), `CashSaleService` (lines, issue, invoice), `SalesReturnService` (receive / inspect).
- Purchases: `PurchaseRequestsService`, `RequisitionToOrderService`, `PurchaseOrdersService` (create, receive),
  `GoodsReceiptsService` (create, post), `DebitNotesService` (lines, return issue).

### Known limits (follow-ups)

- Manufacturing (BOM output / consumption) has no variant selection; producing a variant product is refused by the
  stock guard until it does.
- Vendor-bill and PO detail screens show the variant only through the line description (invoice create/detail,
  quotation, sales order and cash sale screens have the variant picker / variant name since ADR-S066).
- A product that already has variant-less stock keeps it under no variant after attribute lines are added (sell or
  count it out before switching to variants).

- ATP / availability panels and reorder rules are per product, not per variant.
- No web quotation editor exists; the quotation API accepts `variantId`.
- Goods-receipt create and requisition pages have no variant picker yet (API accepts `variantId`; a PO line's
  variant is carried to its receipt automatically).
- POS menu variants (`MenuItemVariant`) are unrelated and unchanged.

### Variant details and sell by size (ADR-S066, 2026-11-11)

- Variants carry a standard `length/width/height` and a sales `description` (variants grid → ⚙ "Size & description");
  the line pickers show each variant's price and prefill its size, price and description.
- Products can be sold by length / area / volume (`GET/PUT /products/{id}/sizing`); lines take pieces × size and the
  quantity is computed server side. See [ADR-S066](../adr/ADR-S066-sell-by-size.md).
- Quotation approval authorizes a variant line at the variant's price.

## Deviations

None from Nest (extension only). Responses of PO / GRN / PR / debit-note lines gain a `variantId` key (additive).
Awaiting owner review: fixed pricelist items are per product, not per variant.
