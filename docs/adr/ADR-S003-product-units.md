# ADR-S003: Per-product units of measure and unit snapshots on transaction lines

- Status: proposed (2026-10-26) — deliberate deviation from the NestJS reference, needs owner approval
- Related: ADR-S001 (frozen wire contract), `V5__product_units.sql`, `docs/spec/core.md` "Deviations"

## Context

The ported (Nest-parity) unit engine already keeps every stock balance and movement in the product's
base unit, converts within a `UomCategory` by `UnitOfMeasure.factor`, converts across categories with a
per-product density bridge (`ProductUomConversion`), and preserves value when it converts cost.

An audit against standard retail/ERP practice found four gaps:

1. **Pack sizes were organization-wide.** `UnitOfMeasure.factor` is shared, so "Box = 24" applied to every
   product. A box of drinks (24) and a box of soap (12) could not both exist without inventing units like
   `BOX24` / `BOX12`. `Product.uomConversion` looked like the per-product answer but stock never read it.
2. **Lines stored no unit or conversion.** Receipts and bills converted with the product's purchase unit
   *at posting time*. Order 10 boxes, change the product's purchase unit to Piece, receive: stock rose by 10
   instead of 240. Posted documents could not be re-derived later.
3. **No unit choice per line.** One purchase unit and one sales unit per product.
4. **Sales paths disagreed.** POS sold in the sales unit; ERP sales invoices, credit notes and supplier
   returns moved base units.

## Decision

1. **`ProductUom` (V5)**: per-product extra units — `factor` (base units per 1 unit), `isPurchase`,
   `isSales`, optional `salePrice` / `purchasePrice`, `isActive`. When a product declares a unit here, that
   factor wins over the category factor for that product. The base unit itself can never be listed.
2. **Snapshots on lines (V5)**: `uomId` + `uomFactor` on `PurchaseOrderLine` (its existing
   `unitOfMeasureId` is the unit), `GoodsReceiptLine`, `DocumentLine` (invoices, bills, credit notes),
   `DebitNoteLine` and `InvoiceItem` (POS). The factor is resolved and validated once when the line is
   written (`UomConversionService.resolveLineUnit`) and stock posting uses it (`StockMove.uomFactor`).
   Receipt lines inherit the PO line's snapshot, so receiving, PO progress and billing count in the unit
   that was ordered. Bills match receipts in base units when both carry snapshots.
3. **Line unit choice**: `unitOfMeasureId` (PO) / `uomId` (GRN, invoice, bill, credit note, debit note)
   on the request. Omitted → the product's purchase (or sales) unit, else its base unit — the pre-V5
   default. An explicit unit must convert to the base unit and, if declared in `ProductUom`, be active and
   enabled for the side of the document. Default price of a non-default unit: its `ProductUom` price, else
   the default unit's price scaled by the conversion.
4. **Base unit frozen after stock moves**: `PATCH /products/:id` rejects a new `uomId` once the product
   has inventory ledger rows (changing it would silently re-denominate every balance).
5. **Backward compatibility**:
   - Lines written before V5 have NULL snapshots and post exactly as before (live conversion).
   - `Product.uomConversion` sent by older clients is stored as the purchase unit's `ProductUom` row.
     V5 backfills such rows only where the category factors could not already produce the conversion.
   - A sales-side default unit that cannot be resolved never blocks a document: the line keeps no
     snapshot and posting falls back to the live conversion (Nest behaviour).
   - Responses gain fields only (`units` on products, `uomId` / `uomFactor` on lines). Nothing is renamed
     or removed; every pre-V5 request body is still accepted.

## Consequences

- The parity harness will show added fields on product, PO/GRN, invoice/bill/credit-note, debit-note and
  POS invoice-item responses. These are expected diffs for this ADR.
- POS still sells in the product's sales unit; the unit and factor are frozen on the invoice item at
  billing. Choosing another unit per line at the till (and on Android) is a follow-up: it needs
  `OrderItem.uomId` carried through orders, splits, transfers and `/sync/push`. Multi-unit scanning at
  the till keeps working through packaging barcodes (`ProductPackaging`).
- Stock documents (adjustments, transfers, counts, waste) stay in the base unit.

## Extension (UOM-1, 2026-10-30): density, m³, bags and truckloads

Status: built, **not compiled or run**.

1. **Units**: `M3` (Volume, 1000 L), `BAG` and `TRUCK` (Quantity, factor 1) join the seed and every existing org (V60).
   Bag and truckload stay at 1 because their content differs per product; that content is a `ProductUom` row.
2. **Density is not a new column.** It is the `ProductUomConversion` bridge `1 M3 = d TON`. `Product` and its JSON are
   unchanged, so nothing POS reads moves.
3. **A bridge connects two categories, not just two units, and bridges chain** (`bridgeRate`). An exact bridge still
   wins; the walk only runs where the engine used to answer 400.
4. **`/products/:id/unit-profile`** is the one place to enter density, kg per piece, kg per bag and tonnes per
   truckload. It writes rows 1 and 2 above and keeps an existing `ProductUom` row for M3 or TON in step, because such a
   row wins over the bridge. `0` removes a fact. `/products/:id/unit-equivalents` shows a quantity in every unit.
5. **`conversionRate`** is the non-throwing read for callers that must not poison a transaction (weighbridge).
6. **`/orders` lines accept `uomId`**, stored converted to the sales unit in `OrderItem`, because the frozen POS billing
   spine reads that column in the sales unit. `OrderLineEntryUnit` keeps what was typed. POS itself is untouched.

Limits: a truckload is one size per product, so a real load is still measured by the weighbridge; the snapshot on a line
is what the document holds. Quotation lines (`crm`) have no unit yet.

