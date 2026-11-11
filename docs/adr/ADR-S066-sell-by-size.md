# ADR-S066 — Sell by size (pieces × length × width × height)

- Status: accepted (owner request, 2026-11-11).
- Extension: no NestJS counterpart. New columns (V446) and two routes; existing line DTOs gain optional `pieces`,
  `length`, `width`, `height`. Requests without them behave exactly as before.

## Context

Granite and similar goods are sold by the metre (kerbs, steps: "4 pieces of 3 m"), the square metre (slabs:
"2 pieces of 240 × 120 cm") or the cubic metre. Branch cash sales, quotations, sales orders and invoices must capture the
pieces and their size, compute the billed quantity from them, and print the size. Standard sizes ("1 m", "3 m") are
product variants (ADR-S058) with their own price, size and sales description.

The word *dimensions* already means analytic dimensions (ADR-S025, `DocumentLine.dimensions`), so this feature is called
**sizing**.

## Decision

1. `Product.sizingMode` (`length` | `area` | `volume` | null), `sizingUnit` (`m` | `cm` | `mm`, the unit the size is
   typed in) and `defaultLength/Width/Height`. Managed by `GET/PUT /products/{id}/sizing` (`product:read` /
   `product:update`) so the Nest-parity product DTO is untouched.
2. `ProductVariant.length/width/height/description`: a variant's standard size and sales description, edited through
   the existing `PATCH /products/{id}/variants/{variantId}` and returned by the variant list and picker routes.
3. `SalesQuotationLine`, `SalesOrderLine`, `SalesSubmissionLine` and `DocumentLine` carry `pieces, length, width,
   height`. A line with any of them is a sized line: `ProductSizingService#resolve` checks the product is sold by size,
   requires the mode's dimensions (> 0, pieces default 1), drops the unused ones and computes
   `quantity = pieces × Π(dimension × unit factor)` (scale 6, HALF_UP) in m / m² / m³. The client's quantity is ignored.
   Price stays per product unit (per m / m² / m³), so pricelists, variants and taxes apply unchanged.
4. The size flows quotation → sales order → invoice and cash sale → invoice. An order invoiced partially (quantity ≠
   ordered) bills a plain quantity; an order change of quantity clears the size.
5. Quotation / order PDFs and the cash-sale Excel form print the size (`4 pcs × 3 m`).

## Consequences

- Stock moves the computed quantity in the product unit (give a sized product an m / m² / m³ unit of measure).
- Responses of invoices / credit notes carry the size keys only on sized lines (exact shape kept otherwise).
- Fleet delivery orders, returns and credit notes created from documents carry the quantity, not the size.
- Fix found on the way: quotation approval now authorizes variant lines at the variant price (a cheaper variant was
  counted as a discount on the product price).
