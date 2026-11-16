# Addendum: web UI and Android POS (Phase I audit)

- Date: 2026-11-15. Read-only. This adds to `2026-11-15-sales-variant-uom-inventory-audit.md`; the IDs (V-, U-, P-, C-, T-) refer to that audit.
- **Labels:**
  - **C** = confirmed by reading the code.
  - **C\*** = also re-checked by hand for this addendum.
  - **S** = suspected.
- **Paths:** `web/src/…` and `android/app/src/main/java/com/poscafe/pos/…` (written `A/` below).
- **Main finding:** the backend is ahead of the screens. Several capabilities, including the Phase I1a POS variant fix,
  cannot be reached from a client yet. Some screens let the browser set the final price, and a few of those compute it
  wrongly.

## 1. Web: workflow by workflow

| Workflow | Variant | Unit | Price source | Findings |
|---|---|---|---|---|
| Product setup (`pages/products/ProductEditPage.tsx`, `features/products/variants-tab.tsx`, `sizing*.tsx`) | ✅ | ✅ | typed | No screen for cross-category `ProductUomConversion`. Pack units have no Active toggle (785-833). The pack price suggestion is `salesPrice × factor` (819), but `features/products/units.ts:182-185` treats `salesPrice` as per sales unit (C) |
| Price lists / promotions / agreements (`features/sales/api.ts` 206-225, `features/invoicing/price-list-api.ts`) | ❌ | ✅ | – | No rule can target a variant. `resolveListPrice` sends no variant (78) (C) |
| Quotation (`pages/crm/QuoteLinesTable.tsx`, `QuotationEditorPage.tsx`) | ✅ | ✅ | server on save | `resolvePrices` sends product + qty only (185). Margin uses product cost with factor `?? 1` (143). Catalog adds lines with no variant (161-177). Sizing mode hard-coded to `'volume'` (208) (C) |
| Sales order (`pages/sales/SalesOrderPage.tsx`) | ✅ | ✅ | server on save | A variant pick ignores the unit (325). A unit change uses the product price and drops the variant price (337). ATP shows only after save and not per variant (C) |
| Sales delivery (`pages/sales/DeliveriesPages.tsx`) | from order | code shown | – | Variant not shown on delivery lines (C) |
| Manual fleet DO (`pages/fleet/dispatch/DeliveryOrderFormPage.tsx`) | ❌ | ❌ | browser product price (66, 126) | = backend V-6 / P-5 (C) |
| Invoice create (`pages/invoicing/invoice-create.tsx`) | ✅ | ✅ | **browser price final** (414) | A unit change uses the product price and drops the variant price (381-386) **C\***. The async price-list lookup can overwrite a later variant price (357-363). `createInvoice` / `postInvoice` are awaited with no error handling (422-423) **C\***: a failed post leaves a draft, and resubmitting makes a duplicate |
| Credit notes (`pages/invoicing/credit-note-create.tsx`, `pages/sales/CreditNotesPage.tsx`) | ❌ | partial | browser | Not linked to the original invoice lines. Errors swallowed (115-116) (C) |
| Cash sale (`pages/sales/cash-sales/CashSalePage.tsx`) | ✅ | ✅ | **server** `/sales/pricing/resolve` with variant + unit | The best line editor. After a reload, lines lose `priceAuto` (126), so they are sent as manual prices (195) (C) |
| Sales returns (`pages/sales/ReturnsPages.tsx`) | from order | – | – | The disposition select shows "Restock" (114), but untouched lines are not sent (93) (C) |
| POS cafe (`pages/pos/Terminal.tsx`) | MenuItem only | ❌ | server quote before charge | OK for menu items |
| **POS retail** (`pages/pos/RetailTerminal.tsx`) | ❌ | ❌ | product price (382, 409) | **No ProductVariant picker, and a barcode scan finds the product, not the variant.** The I1a backend fix is unreachable from the web (C) |
| POS refunds / voids (`pages/returns/NewReturnPage.tsx`, `VoidItemDialog.tsx`) | shown | – | server | One disposition per return, not per line. A kitchen void only asks for a reason (C) |
| Purchase request (`pages/procurement/s2p/PurchaseRequestCreatePage.tsx`) | ❌ | ❌ | cost (66) | (C) |
| Purchase order (`pages/procurement/purchase-order-create.tsx`) | ✅ | ✅ | **browser price final** (80) | A variant pick sets its per-base cost even on box lines (265) **C\***. A unit change ignores the variant cost (129). Falls back to `salesPrice` (120). An unknown factor keeps the old price (130) (C) |
| Goods receipt (`pages/procurement/goods-receipt-create.tsx`, `PurchaseOrderReceivePage.tsx`) | ❌, and the PO prefill drops it (65-71) | ✅ locked to PO | copied | The PO is typed as a raw id (110-118). The product select is uncontrolled (167). Create then post runs in `onSuccess` and is not atomic (88-91). The detail page has no unit column and totals mixed units (177, 312) (C) |
| Vendor bill / debit note | ❌ | ✅ | browser | (C) |
| Stock levels (`pages/inventory/InventoryItemsPage.tsx`) | ❌ | – | **browser** `averageCost × qty` (185) | Wrong for FIFO / STANDARD (= C-2). Not per variant (C) |
| Stock detail (`pages/inventory/InventoryDetailPage.tsx`) | ❌ | equivalents | server | Variant rows look like duplicate locations (S) |
| Stock ledger / movement | shown | partial | server | No variant filter (C) |
| Adjustments, waste, transfers, direct in/out, replenishment, reservations | ❌ | base only | – | A variant cannot be moved or adjusted (C) |
| Stock count (`pages/inventory/InventoryCountPage.tsx`) | ✅ | ✅ | – | Good |
| **Posting monitor** (`pages/pos/PostingMonitorPage.tsx`, route `App.tsx:497`) | ❌ | – | – | No menu entry and no `RequirePermission` (app-shell 476-489). Filters are Failed/All only (63), with no pending or kind filter. Retry is for failed jobs only. No invoice link. The I1a failures are recorded but hard to find (C) |
| Inventory settings (`pages/settings/system-config.tsx`) | – | – | – | Unavailable choices (`at_fulfillment_start`) are shown as normal (S). Saving does one PUT per setting with no rollback (C) |

Cross-cutting (C):
- `VariantSelect` loads active variants only and silently clears a saved variant that has since been archived
  (`features/products/variant-select.tsx` 28-31).
- `unitFactor` returns 1 when a product has no stock unit (`units.ts` 129).
- Product pickers load only the first 200–500 products (e.g. `purchase-order-create.tsx` 58, `invoice-create.tsx` 203).

## 2. Android POS (offline device)

| Area | Finding |
|---|---|
| Variants | No ProductVariant concept. `ProductEntity` has no variants, `priceExtra` or `uomId` (`A/data/local/entity/CatalogEntities.kt:166-186`). Variant stock rows are pulled but hidden: every query filters `variantId IS NULL` (`A/data/local/dao/CashInventoryDao.kt:81-88`) (C) |
| Sale payload | No product `variantId`, `uomId`, `taxId` or `expectedTotal` (`A/data/repo/SaleRepository.kt:185-218`). Tenders are trimmed to the device total (60-77) **C\*** |
| **Price disagreement** | The server re-prices from its catalog and rejects a tender sum that does not match the amount due (`PosInvoiceService:637-642`) **C\***. Causes on the device (C): menu lines carry no tax (`A/ui/screens/TerminalViewModel.kt:496-531`); `taxInclusive` comes from the product, not the tax row; prices are stale since the last pull. The op then fails on every retry until a manager discards it (`A/ui/screens/SyncScreen.kt:114-128`). The cash was already taken and the receipt printed. How often this happens depends on the data |
| Units | `uomName` is display-only. Pack barcodes are converted to base units on the device (`TerminalViewModel.kt:458-494`) |
| Refunds | Full sale only. Disposition is hard-coded to `restock` (`A/data/repo/RefundRepository.kt:34-35, 141`) |
| Kitchen | Removing a line after the KOT is silent: no approval, no waste, no server op (`TerminalViewModel.kt:534-541`) |
| Counts | One variant product gets a 409 for the whole `stock.count`. 409 is not a final rejection, so the op stays stuck and the local adjustments stay applied (`A/data/repo/StockRepository.kt:209-242`, `SyncRepository.kt:1025`) |
| Stock in/out | No `variantId`, so it is likely rejected for variant products (S) |

The Android app was outside the "no client change" scope. Fixes need their own phase (I6).

## 3. Roadmap impact (owner-approved order, 2026-11-15)

- **I1c (web):**
  - product-variant picker and variant barcode scan on retail POS;
  - posting monitor reachable from the menu, guarded by permission, with pending and kind filters.
- **I1b:**
  - backend unit strictness;
  - web: warn instead of keeping a stale price or assuming factor 1; units shown on goods receipts.
- **Web fixes for I2–I5** that need no new backend capability:
  - variant on goods receipt, purchase request, transfers, adjustments, waste and direct stock;
  - stock value taken from the server;
  - archived variants shown read-only;
  - invoice / PO unit change keeps the variant price;
  - pack price suggestion follows D1;
  - invoice, credit-note and goods-receipt errors surfaced, with no duplicate on retry.
- **Waiting for their backend phase** (variant target on pricing rules, variant in price-list lookups, manual delivery
  order variant): I3.
- **I6 (Android):** built 2026-11-16 (branch `worktree-agent-a59494dcfb7aaed0f`, not merged) — see
  `android/README.md` "Cash & inventory sync" / "Pricing parity" and `docs/spec/sync.md` Deviations.
  - variant picker — done (pull `productVariants`, picker, variant barcode/SKU scan);
  - `expectedTotal` sent and tax aligned with the server — done (tax row inclusivity, menu-item tax, currency
    scale); a remaining price difference no longer fails the op: the sale is kept at the server price (Decision 1);
  - refund dispositions and partial refunds — done (`lineNumber`, one disposition per op);
  - kitchen voids sent to the server — done (`kitchen.void`, manager PIN);
  - variant counts — done; stock in/out carry `variantId`. Local movements of a discarded op are removed.
  - Open: `uomId` is sent but the server POS ignores it (sells in the product's sales unit); stale `stock.count`
    replays are still applied as absolute counts (owner decision).
