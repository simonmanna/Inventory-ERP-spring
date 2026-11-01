# Landed cost management: current design, gaps, target

- Status: **proposed extension** of ADR-S007 / V14 (ADR-PROCUREMENT-001). It **reuses** the existing voucher engine
  and does not replace it.
- Current routes and GL: [`procurement.md` → ADR-S007 extensions](procurement.md#adr-s007-extensions--landed-cost-vouchers-shipments-cost-components-roadmap-18).
- Code: `procurement/application/LandedCostVoucherService`, `LandedCostLayers`, `ShipmentService`,
  `CostComponentService`, `LandedCostReportService`. Tests: `LandedCostVoucherIT`.

## 1. Concepts (already separated in the code)

| Concept | Implementation |
|---|---|
| Purchase receipt (goods physically received) | `GoodsReceiptNote` / `GoodsReceiptLine` |
| Shipment (group of receipts) | `Shipment` + `ShipmentReceipt` (`ShipmentService`) |
| Landed-cost document | `LandedCost` voucher (draft → submitted → approved → posted → reversed) |
| Charge (one supplier's cost) | `LandedCostCharge`: supplier, invoice no., currency / rate, amount, tax, `treatment` (capitalise / `recoverable_tax` / `expense`), `allocationMethod`, `billMode` (generate / link / none) |
| Supplier invoice (the legal AP document) | A vendor bill, generated or linked per (supplier, invoice no.), Dr clearing 2155 / Cr that supplier's AP (V14) |
| Allocation | `LandedCostAllocation` per receipt line; `LandedCostLayers.apply` splits the on-hand share (revalue the layer, lot, serial or average) from the consumed share (COGS) |

Allocation methods supported today: `value`, `quantity`, `equal`, `weight`, `volume`, `manual`
(`LandedCostVoucherService` 548–552, validation 724–733).

**No duplicate liability.** Each charge's AP is raised once, by its own supplier bill. The voucher only moves cost
from clearing into stock, COGS or expense.

## 2. Gaps to close

| # | Gap | Design |
|---|---|---|
| G1 | **Estimated landed cost before the actual bill** | Charge `stage = estimated` at GRN time, from cost-component defaults (e.g. transport UGX/t per route) or the RFQ freight quote. It posts Dr stock / Cr `landed_cost_accrual` *(new)*. When the actual bill arrives, a **true-up** voucher posts the difference (on-hand share to stock, consumed share to COGS) and clears the accrual. Report: estimated vs actual by supplier and route. |
| G2 | Supplier credit note on a charge | A `vendor_credit_note` (invoicing) linked to the charge. It triggers a negative true-up voucher. |
| G3 | Shipment / container basis | A new method `shipment`: the charge is split first across the shipment's receipts by the chosen secondary basis, then within each receipt. A container charge is equal per container (`ShipmentReceipt.containerRef`). |
| G4 | Actual-weight basis from the weighbridge | Method `weight` uses the GRN line's `weighbridgeTicket.netKg` when present, otherwise product weight × qty. |
| G5 | Approval routing by threshold | `landed_cost` approval rule from the policy layer (ROADMAP §1.8 open follow-up). |
| G6 | Legacy single-GRN landed cost | It checks only `booksLockDate` (`LandedCostService.assertPeriodOpen` 329) and cannot be reversed. Use `FiscalPeriodControl`, and steer new use to vouchers through a UI hint. The parity route stays. |
| G7 | Reconciliation | `GET /procurement/landed-cost-reports/reconciliation`: per voucher, Σ charges = Σ allocations + expensed + recoverable tax; clearing 2155 balance = posted vouchers − linked bills; accrual balance = open estimates. |
| G8 | Fleet own-transport | Own-truck haulage cost (`fleet` costing, V25) can be a charge with `billMode=none`, credit `fleet_cost_recovery` *(new)*, so internal transport is capitalised without an AP. |

## 3. Worked example: cement, four suppliers

1,000 t cement from Supplier A at UGX 100,000/t, in one shipment of two GRNs (600 t to Yard 1, 400 t to Yard 2). By
the time the late handling bill arrives, 250 t of Yard 1 has been consumed in block production. All amounts are net of
recoverable VAT **[accountant to confirm treatment]**.

| Charge | Supplier | Amount (UGX) | Method | Yard 1 (600 t) | Yard 2 (400 t) |
|---|---|---|---|---|---|
| Goods | A | 100,000,000 | — (GRN) | 60,000,000 | 40,000,000 |
| Transport | B | 8,000,000 | weight (net kg from weighbridge) | 4,800,000 | 3,200,000 |
| Loading | C | 2,000,000 | quantity | 1,200,000 | 800,000 |
| Handling | D | 1,000,000 | quantity | 600,000 | 400,000 |
| Other (permit) | E | 500,000 | manual 50/50 | 250,000 | 250,000 |
| **Landed** | | **111,500,000** | | **66,850,000** | **44,650,000** |
| **Per tonne** | | **111,500** | | 111,416.67 | 111,625.00 |

Postings:
1. **Each supplier bill** (B, C, D, E): Dr `landed_cost_clearing` 2155 / Cr AP-supplier. There are four independent
   liabilities.
2. **Voucher post**, for transport, loading and other (posted before any consumption): Dr stock Yard 1 6,250,000,
   Dr stock Yard 2 4,250,000 / Cr 2155 10,500,000.
3. **Late handling voucher**, after 250 of the 600 t in Yard 1 were consumed:
   - Yard 1's share is 600,000. The consumed part is 250/600 × 600,000 = 250,000 → Dr COGS (or production WIP, if the
     consumption went to an open production order — policy `landedCost.consumedTarget = cogs | wip`). The on-hand part,
     350,000, goes to Dr stock Yard 1.
   - Yard 2: Dr stock 400,000.
   - Cr 2155 1,000,000.
4. **After all bills and vouchers:** 2155 = 0, and each AP-supplier balance = its bill.

## 4. GL summary

| Event | Debit | Credit |
|---|---|---|
| Estimated charge at GRN (G1) | stock (on-hand) | `landed_cost_accrual` |
| Actual bill for an estimated charge | `landed_cost_accrual` (estimate); variance → stock / COGS split by true-up | AP-supplier |
| Actual charge voucher | stock (on-hand share), COGS / WIP (consumed share), expense (`treatment=expense`), `tax_receivable` (`recoverable_tax`) | 2155 or AP-supplier (`billMode=none` with a supplier credit account) |
| Voucher reversal | mirror; the part no longer on hand is reclassed (`StockPostingService.postLandedReclass`) | |

## 5. Acceptance tests (add to `LandedCostVoucherIT`)

1. The four-supplier example above, to the cent, including the consumed share.
2. Estimate followed by an actual bill at a higher or lower amount (true-up).
3. Supplier credit note on a charge.
4. Shipment basis across two GRNs and two containers.
5. Weight basis taken from the weighbridge net kg.
6. A STANDARD-cost product: the charge goes to PPV / expense, not stock (as today; `LandedCostLayers` never rebases
   standard).
7. A foreign-currency charge at its own rate.
8. Reconciliation report equals the GL.
9. Concurrent voucher post versus GRN reversal: one wins, the other gets a clean 400.

## Cost Management CM-6 (ADR-S036)

- Landed cost accrued at receipt by rule (`/procurement/landed-cost-accrual-rules`, `/procurement/landed-cost-accruals`)
  and cleared in full against the actual voucher; 2156 reconciles to the open accruals.
- Legacy per-receipt landed costs can be reversed: `POST /procurement/landed-costs/:id/reverse` (extension,
  `landed_cost:reverse`, Idempotency-Key), through the voucher engine.
