# Inventory and procurement production readiness — 2026-11-17

Scope: inventory (stock engine, documents, warehouse, valuation, replenishment), procurement (requests, sourcing,
orders, receipts, returns, claims, landed cost, supplier portal), and their web screens.

Method:
- Read-only review of local `main` at `46281ee2` by seven parallel reviewers.
- Every blocker below was then verified in the code.
- The blocker fixes are on branch `phase/inv-proc-blockers`.

## Verdict

**Not production-ready on 46281ee2.** The engine core is sound: the ledger, lock order, tenancy and idempotency
hold. Six verified blockers could double stock or AP, pay suppliers twice, or book foreign purchases at the wrong
value. Two usability blockers stopped storekeepers from working.

**After `phase/inv-proc-blockers`:**
- All eight blockers are fixed and tested.
- The P1 / P2 gaps below remain. The web-screen gaps are fixed; the rest are in progress on branches, see the
  [Phase B handoff](readiness-gaps/HANDOFF.md).
- Run the data checks below once after deploying V535.

## Blockers

Deviations from Nest parity are approved by the owner 2026-11-17. They are recorded in [procurement](../spec/procurement.md#deviations),
[inventory](../spec/inventory.md#deviations), [core](../spec/core.md#deviations) and
[ADR-S026](../adr/ADR-S026-warehouse-management.md).

| # | Defect (on 46281ee2) | Fix | Test |
|---|---|---|---|
| B1 | The "New goods receipt" screen saved a PO receipt with no supplier (`GoodsReceiptsService.createDraft` took `partnerId` from the body only). The bill matcher pairs bill lines only with receipts of the bill's supplier, so the supplier bill received and charged the goods again: double stock, double AP. | The supplier comes from the PO; a different one is refused. `post` backfills legacy drafts. V535 backfills posted receipts. The screen shows the PO's supplier and asks for one on a receipt without a PO. | `ReadinessBlockersIT` (2) |
| B2 | `POST /purchase-orders/:id/pay` capped payments at `vouchered()`. That counted service lines, which no receipt vouchers, and ignored returns to the vendor and claim credits, so the supplier could be paid twice. | Only stocked (vouchered) lines count. Settled claims on the PO or its receipts reduce the open amount. A payment can never exceed what the AP ledger owes the supplier (catches returns, which name no PO). | `ReadinessBlockersIT` (3) |
| B3 | The WMS "New pick order" form always created `manual` orders, and shipping a manual order issues stock. A pick for a sale was issued on shipping and again by the sale. | The form asks what the pick is for and sends `sourceType`. The API refuses a manual order whose reference is a sales order, sales invoice, delivery order, stock-out or transfer number. | `WarehouseIT` |
| B4 | Batch, expiry and serial tracking could be switched with stock on hand, leaving stock without the batches or serials the engine then requires. Only the costing method was guarded. | `PATCH /products/:id` refuses a tracking change while stock is on hand (normalized flags; re-sending the current value passes). | `ReadinessBlockersIT` |
| B5 | Foreign-currency RFQ quotations, award orders, agreement releases and portal quotes were booked at rate 1. A PO without a currency was stored as `USD`. | PO and quotation rates are the stated rate, else the rate table, else 400. A PO without a currency is in the functional currency. Award orders treat a stored 1 as unstated. Approval amounts are functional. | `ReadinessBlockersIT` (2) |
| B6 | The debit-note form priced supplier (inbound) lines at the product's sales price. | Inbound lines take the purchase price and unit; outbound lines the sales price and unit. Lines re-price when the direction changes. | web (typecheck, lint) |
| UB1 | The stock forms' staff pickers called `GET /users` (`user:read`). | New `GET /inventory/staff-options` (`inventory:read`; id and name only). The hook uses it, falls back to `/users`, else shows an empty list. | `ReadinessBlockersIT` |
| UB2 | `SelectItem value=""` throws in Radix Select 2.x. The Stock Ledger, product edit and about 20 other screens crashed. | The shared `Select` / `SelectItem` map an empty item value to a sentinel and back, which fixes every screen. | web (typecheck, lint) |

Also fixed: `PoBillApplicationIT` voided a bill without the reason that the accounting controls (R1–R15) now
require.

### Data checks after deploying V535

Run these once per database. Each lists candidates for a person to review; none corrects data. The queries are in
the PR and were checked against the schema.

- **B1:** bills that received goods themselves (`InventoryLedger.referenceType = 'vendor_bill'`) while a posted PO
  receipt of the same supplier and product was still unbilled. Each pair is a likely double receipt: return the extra
  stock and reverse the extra AP.
- **B2:** suppliers whose AP balance is in debit (paid more than owed).
- **B3:** shipped `manual` pick orders whose `sourceRef` is a sales order, sales invoice, delivery order, stock-out or
  transfer number. Each one issued its goods twice.
- **B4:** batch-tracked products whose stock on hand differs from their batch quantities, and untracked products that
  still hold batch quantities.
- **B5:** purchase orders in a non-functional currency at rate 1.
  - Web orders saved without a currency show up as `USD`; their amounts are in the functional currency, so correct
    only their currency code.
  - RFQ, agreement and portal orders are genuinely foreign and were valued at rate 1.

```sql
-- B1
SELECT d."organizationId", d."documentNumber" AS bill, d."partnerId", l."productId",
       l."quantityChange" AS bill_received_qty, g."receiptNumber" AS receipt,
       gl.quantity - gl."billedQuantity" AS receipt_unbilled_qty
FROM "InventoryLedger" l
JOIN "Document" d ON d.id = l."referenceId" AND d."organizationId" = l."organizationId"
JOIN "GoodsReceiptNote" g ON g."organizationId" = d."organizationId" AND g."partnerId" = d."partnerId"
     AND g."purchaseOrderId" IS NOT NULL AND g.status = 'posted' AND g."deletedAt" IS NULL
JOIN "GoodsReceiptLine" gl ON gl."goodsReceiptId" = g.id AND gl."productId" = l."productId"
WHERE l."referenceType" = 'vendor_bill' AND l."quantityChange" > 0 AND d.status::text = 'posted'
  AND gl.quantity > gl."billedQuantity";

-- B2
SELECT l."organizationId", l."partnerId", SUM(l."baseCredit" - l."baseDebit") AS owed
FROM "JournalLine" l JOIN "JournalEntry" e ON e.id = l."journalEntryId"
JOIN "AccountMapping" m ON m."accountId" = l."accountId" AND m."organizationId" = l."organizationId"
     AND m.key = 'accounts_payable' AND m."deletedAt" IS NULL
WHERE e.status IN ('posted', 'reversed') AND l."partnerId" IS NOT NULL
GROUP BY l."organizationId", l."partnerId" HAVING SUM(l."baseCredit" - l."baseDebit") < -0.005;

-- B3
SELECT o."organizationId", o.code, o."sourceRef", o."shippedAt" FROM "WarehousePickOrder" o
WHERE o."sourceType" = 'manual' AND o.status = 'shipped' AND o."sourceRef" IS NOT NULL
  AND (EXISTS (SELECT 1 FROM "SalesOrder" s WHERE s."organizationId" = o."organizationId" AND s.number = o."sourceRef")
    OR EXISTS (SELECT 1 FROM "Document" d WHERE d."organizationId" = o."organizationId"
               AND d."documentNumber" = o."sourceRef" AND d."documentType"::text = 'sales_invoice')
    OR EXISTS (SELECT 1 FROM "FleetDeliveryOrder" f WHERE f."organizationId" = o."organizationId" AND f.code = o."sourceRef")
    OR EXISTS (SELECT 1 FROM "StockOut" so WHERE so."organizationId" = o."organizationId" AND so."outCode" = o."sourceRef")
    OR EXISTS (SELECT 1 FROM "StockTransfer" t WHERE t."organizationId" = o."organizationId"
               AND t."transferCode" = o."sourceRef"));

-- B4
SELECT p."organizationId", p.id, p.name, p."batchTracking", s.qty AS on_hand, COALESCE(b.qty, 0) AS in_batches
FROM "Product" p
JOIN (SELECT "productId", SUM(quantity) AS qty FROM "StockItem" GROUP BY 1) s ON s."productId" = p.id
LEFT JOIN (SELECT "productId", SUM(quantity) AS qty FROM "InventoryBatch" GROUP BY 1) b ON b."productId" = p.id
WHERE p."deletedAt" IS NULL
  AND ((p."batchTracking" AND s.qty <> COALESCE(b.qty, 0)) OR (NOT p."batchTracking" AND COALESCE(b.qty, 0) <> 0));

-- B5
SELECT po."organizationId", po."orderNumber", po."currencyCode", po."exchangeRate", o."currencyCode" AS functional,
       po.description, po."createdAt"
FROM "PurchaseOrder" po JOIN "Organization" o ON o.id = po."organizationId"
WHERE po."currencyCode" <> o."currencyCode" AND po."exchangeRate" = 1 AND po."deletedAt" IS NULL;
```

### Residual risk after the blocker fixes

- **Supplier-less receipts.** The API still accepts a receipt with neither a PO nor a supplier. Integration suites
  and scripts use this; the web now always asks for a supplier. A bill cannot match such a receipt.
- **The B2 ledger cap is supplier-wide.** A return on one order lowers what every order of that supplier may pay in
  total, not that order alone. Returns carry no order link. It never lets a supplier be paid more than the ledger
  owes them.

## P1 gaps (open at the blocker fix)

Inventory — engine, warehouse, replenishment:
1. Serial products received without serials are issued at `costPrice` (`StockService`, default `capture_optional`).
2. Stock-out approval amount is always 0: `totalValue` is not set before `gateApproval` (`StockDocService`).
   Adjustments and transfers send unit counts. No SoD pair covers stock-out or waste.
3. A posting rule that resolves no account:
   - every rule line null: the move commits with no GL;
   - one side null: the move fails.
4. Transit receive is not idempotent (`StockDocService`, web `transfers-api.ts` plain `api.post`).
5. Direct stock-in accepts a negative `unitCost`.
6. Sourced pick orders put bins out of step with the ledger; reconcile gives misleading advice.
7. Shipping a manual pick needs no approval or SoD.
8. Replenishment ignores branch scope.

Valuation:
9. Policy and standard-cost activation do not take the engine locks.
10. Duplicate pool lines in revaluation / NRV.
11. A revaluation reverse overwrites later value changes.
12. No SoD on valuation changes; offset accounts bypass the control-account policy.

Procurement core:
13. Reversing a foreign-currency receipt fails as unbalanced (`StockReversalService`); a cash PO's
    `settleCashReceipt` posts without currency.
14. Debit notes:
    - a return to vendor always debits AP;
    - the currency is ignored;
    - `documentId` is not checked;
    - the warehouse is chosen with `LIMIT 1`.
15. Receipt reversal ignores claims, returns and accruals; it has a unit mismatch and races bill posting.
16. Approval bypasses:
    - a rejected or edited bill still posts when no `supplier_bill_approval` workflow exists;
    - PO change approval skips bands and budget;
    - a request's requester can approve it when no workflow exists.
17. Supplier claims: one user can raise, accept and settle; the amount is uncapped; there is no reversal.
18. The receiver's `unitCost` overrides the PO price on a PO receipt.

Procurement extensions:
19. Budget and delegation limits are skipped on sourcing, RFQ, agreement and change paths.
20. The `rfq_award` approval gets amount 0, so value bands never apply.
21. Supplier bank-account dual control gates nothing; `Partner.bankAccounts` is plain text editable with
    `partner:update`.
22. Inspection holds neither stock nor payment.
23. RFQ bids are not sealed; a zero-quantity quote distorts ranking.

Landed cost:
24. Generated bills skip the duplicate check (and the bill duplicate check is off by default).
25. The receipt screen uses the legacy engine (`onHand = min(qty, receipt qty)`). It has no approval, duplicate or
    tax checks and accepts any credit account.
26. Linked bills are not validated and can be reused.
27. No SoD on voucher approval or overrides.
28. A draft credit voucher can still post after its original is reversed.
29. Legacy LC post races receipt reversal.

Web:
30. A retry after a failure gets a new idempotency key for transfers and document creates.

## P2 gaps

- Unbounded and N+1 lists: stock documents, PO / receipt / debit-note lists, LC reconciliation. Warehouse queries
  have no indexes.
- Pickers are truncated at 25 locations and 200 products.
- Float noise in "receive all remaining".
- No confirmation before irreversible actions.
- Supplier portal:
  - a date parse error is a 500 that logs the token;
  - suppliers can re-confirm `promisedDate`.
- Activation jobs only log warnings on failure.
- LC-3 reconciliation shows a false difference after a partial clear.
- A STANDARD voucher reversal sends released WIP to COGS.
- Lot lineage treats every batch with the same lot number as this receipt's goods.
- Idempotency keys are optional on voucher routes.
- Dead purchase-request link; raw UUID inputs (the receipt screen's PO field).
- Double 403 toasts; key-indexed product dropdowns.
- The PO pay screen computes its own balance and always pays from bank.
- `OPERATION_PENDING` after a 5xx.
- Budget over-counting; replenishment mixes scopes.
- Maker-checker gaps in policy edit and supplier approval.

## Process gaps

- Local `main` carried 31 commits that CI had not built (pushed with this work; CI runs the full build).
- The audit ITs `InventoryAudit*IT` in the `-invfix` worktree are uncommitted.
- Web screens have not been exercised in a browser against this backend.

## Tests run for the blocker fixes

- Run with `fastTest` against a private Postgres 16 (`IT_EXTERNAL_DB`).
- New: `ReadinessBlockersIT` (10 tests) and the B3 test in `WarehouseIT`. All pass.
- Regression, 104 tests: `ProcurementIntegrityIT`, `ProcurementIT`, `PoBillApplicationIT`, `SourcingIT`, `PlanningIT`,
  `SourceToPayEndToEndIT`, `InventoryHardeningIT`, `SupplierClaimSettlementIT`, `ProductUnitsIT`,
  `ProcurementControlsIT`, `ReceivingQualityIT`, `QuarryIT`, `FleetFuelIT`.
  - All pass after the `PoBillApplicationIT` fix.
  - `InventoryHardeningIT`'s owner-connection test needs `SPRING_FLYWAY_URL` in external-DB mode.
- Web: `tsc --noEmit` and `eslint` on the changed files.
- The full `./gradlew build` was not run locally; CI runs it on push.
