# Inventory review 2026-11-22 — findings and hardening status

Owner-approved plan: hardening batches R0–R5 with four release gates (G1 quantity, G2 financial, G3 security and
concurrency, G4 operational). The engine core is sound (one move engine, move and journal in one transaction, posting
keys, locking, tenancy, idempotency); the defects sit in rental, returns and reversals, offline sync, branch scope,
location master data and STANDARD costing.

- Read-only data checks for historical damage: [2026-11-22-inventory-data-checks.sql](2026-11-22-inventory-data-checks.sql).
  Not run against any live database; no repair scripts. Each repair needs its own owner-approved plan.
- Live checks: `GET /api/v1/inventory/reports/integrity` and the daily integrity monitor (R0, see
  [spec](../spec/inventory.md#integrity-checks-r0)).

Note on numbering: P0-1, P1-1, P1-2, P1-3 and P1-13 carry the review's own ids. P1-4 to P1-12 are numbered here in
batch order from the plan's batch sections; map them back to the review text before quoting them elsewhere.

## Status

| id | finding | batch | status | test |
|---|---|---|---|---|
| R0-a | No automated reconciliation: ledger vs quant vs lots vs serials, valuation vs stock ledger vs GL, transit, reservations, offline queue | R0 | done | `InventoryIntegrityIT` |
| R0-b | No daily monitor raising integrity findings; value findings do not hold the period close | R0 | done | `InventoryIntegrityIT`, `PeriodCloseChecklistIT` |
| R0-c | No read-only assessment of historical data before repairs | R0 | done (queries written and validated on a test DB; not run on live data) | `docs/audits/2026-11-22-inventory-data-checks.sql` |
| P0-1 | Rental fee lines (RENT / LATE / DAMAGE / EXTEND) issue stock on the POS invoice; a missing fee product falls back to the rented product | R1 | fixed (R1) | `RentalIT` |
| P1-1 | Returns and reversals (cash-sale void, invoice cancel, credit-note restock, sales return, production `returnMaterials`, debit-note reversal) rebuild lot, expiry and serial identity from the product's current config: new auto-numbered lots, serials left `issued` | R1 | fixed (R1) | `InventoryReturnsIdentityIT`, `CashSaleIT` |
| P1-2 | Credit note on a cash-sale or fleet invoice finds no issue rows and falls back to a default location and today's cost | R1 | fixed (R1) | `InventoryReturnsIdentityIT` |
| P1-3 | Offline refund / void runs before the invoice's pending stock jobs are drained | R1 | fixed (R1) | `SyncInventoryRecoveryIT` |
| P1-4 | Stock reversal does not re-check the user's branch scope inside the moving transaction | R2 | fixed (R2) | `InventoryBranchScopeIT` |
| P1-5 | Transfer approve / dispatch / recall / cancel / receive do not re-check source or destination scope after the transfer lock | R2 | fixed (R2) | `InventoryBranchScopeIT` |
| P1-6 | Reservations: reserve, release, consume, list and overview not scope-checked or filtered; list unbounded | R2 | fixed (R2) | `InventoryBranchScopeIT` |
| P1-7 | Locations can be removed, deactivated, retyped or moved to another branch while they hold stock or live documents; manufacturing and rental insert locations directly | R2 | fixed (R2) | `InventoryBranchScopeIT` |
| P1-8 | STANDARD receipt with no active standard enters at actual cost without PPV | R3 | fixed (R3) | `StandardCostingIntegrityIT` |
| P1-9 | STANDARD returns not received at the standard; serial-tracked products accepted as STANDARD | R3 | fixed (R3) | `StandardCostingIntegrityIT` |
| P1-10 | Stock journals carry no product dimension: no product-level GL reconciliation | R3 | fixed (R3; per-product GL from the V613 cutover) | `StandardCostingIntegrityIT`, `InventoryIntegrityIT` |
| P1-11 | Order-level stock timings (`at_confirmation`, `at_fulfillment_complete`) can double-post when the timing changes | R4 | fixed (timings deprecated, one job per sale `sale:<orderId>`, old keys honoured) | `OrderCancelStockIT.oneJobPerSale`, `.orderTimingsDeprecated`, `KernelUnitTest` |
| P1-12 | Order cancel does not reverse a posted `pos_order` issue (or partial / returned states) | R4 | fixed (cancel table in pos.md; voided fired line issued once) | `OrderCancelStockIT` (pending, processing, posted, partially posted, partially returned, kitchen void, reopen, worker) |
| R4-a | Pick orders ignore reversals / returns of their source document; a document with a live pick order can be reversed | R4 | fixed | `WarehouseIT.pickOrderAndDocumentReversal` |
| P1-13 | Offline transfer receipt waiting for its dispatch is not crash-safe and can be applied twice | R1 | fixed (R1) | `SyncInventoryRecoveryIT` |
| P2-1 | Lock order: count submit, reservations per-quant lock, `createWave` id order, approval gates before locks | R5 | partly fixed (R5a: `createWave` locks in id order; count submit / reservation lock order and approval gates stay with the rest of R5) | code review (lock order; no IT) |
| P2-2 | Transfer reversals do not pin ledger lots; count lines by lot number; serial insert without conflict check; production reversal does not issue the produced lot | R5 | open | — |
| P2-3 | Transfer ledger values ignore serial / standard cost; AVCO residue not swept at zero; landed cost without `CostingLocks.shareProduct` | R5 | open | — |
| P2-4 | Reports: `/ledger` and `/movements` day parsing, N+1 includes, bad `tolerance` not a 400, stock-health fallback, `closingValue` source, cost fields without `inventory_valuation:read` | R5 | fixed (R5a) | `InventoryReportsHardeningIT` |
| P2-5 | Validation: `@Positive` / `@Size` missing on stock-out lines, lists, notes and replenishment DTOs | R5 | fixed (R5a) | `InventoryReportsHardeningIT.requestLimits` |
| P2-6 | Warehouse: moves ignore `reservedInBin`, disabling settings blocks open work, reconcile pass 2 leaves stale tasks, ABC GET writes | R5 | fixed (R5a) | `WarehouseHardeningIT` |
| P2-7 | Integration: repair parts without variant, engine cost or reversal route; rental transfers without variant and serial | R5 | partly fixed (R5a: repair parts get the variant, the engine cost and `POST /repair/parts/:id/return`; rental transfers stay with R1/R5) | `RepairIT.parts`, `RepairIT.partReturn` |
| P2-8 | Stock-document lists unpaged; Idempotency-Key not required on approve / dispatch / ship | R5 | fixed (R5a: lists capped + date window, keys required on approve / dispatch / ship; stock-out approve keeps the key optional — no client calls it) | `InventoryReportsHardeningIT.boundedDocumentLists`, `WarehouseHardeningIT.keysRequired` |
| P2-9 | Missing index `StockTransferItem(organizationId, productId)` | R5 | fixed (R5a, V615) | migration `V615__inventory_hardening_r5a.sql` |
| P2-10 | Negative-stock policy undocumented and without concurrency / offline ITs | R5 | fixed (R5a: policy documented as it behaves; ITs) | `NegativeStockPolicyIT` |
| P2-11 | Audit trail: no `recordedOffline` in ledger detail; `reversesLedgerId`, performer and approver not shown | R5 | open | — |

## R0 notes and deviations from the plan

- **Serials vs quantity.** The default serial policy (`capture_optional`) allows quantity-only movements, so a quant may
  hold units without a serial. The check is therefore: in-stock serials ≤ max(0, quantity) always, and = quantity
  where `inventory.serialPolicy` resolves to `required` (per product, category and location).
- **Serial state.** Receipts and transfers write one ledger row for several serials without `serialId`; only issues
  carry it. A serial's movements are its own rows plus the receipt row matched through `receiptRef`. Serials with no
  such movement are not judged.
- **Valuation vs GL** delegates to the existing tie-out: organisation level, every inventory control account together
  (not per account or branch). Product-level GL tie-out waits for the product dimension on stock journals (R3).
  The monitor does not raise it while `inventory.glTieOut.enabled` is off.
- **Transit** also subtracts `qtyRecalled` (the engine's own outstanding formula). Under a location filter only the
  per-transfer part runs (transfers touching the location); Σ open = transit-location quantity is organisation level.
- **Reservations** compare with on-hand + incoming supply (the replenishment providers: purchases, production,
  transfers into the location). Sources judged: `invoice`, `sales_order_line`, `production_order`,
  `processing_job_input`; other source types (manual, free text) cannot be resolved and are not judged.
- **Period close.** A value finding (`integrity_valuation_vs_subledger`, `integrity_valuation_vs_gl`) raised since the
  period started holds the close and is a blocking checklist item (`inventory_integrity_value`). Quantity findings
  are excluded from the generic open-exception gate: they never hold a close. Because the existing tie-out variance
  was advisory, the GL tie-out now also holds the close through the integrity monitor when it fails (owner plan:
  "blocks period close only for the value checks").
- No migration was needed.
