# Procurement integration contracts

- Status: **proposed** (ADR-PROCUREMENT-001).
- Rule: modules call each other's **application** APIs or react to events. They never write another module's tables.
  Reading through an exposed `domain` named interface is allowed (`AGENTS.md` §8). `ModularityTests` and
  `ArchitectureTests` must stay green.

## 1. Ports procurement consumes (existing APIs, reused as-is)

| Provider | API | Used for | Notes / required change |
|---|---|---|---|
| inventory | `StockService.receiveForDocument(StockMove, GlReceiptContext)` | GRN post | Refuse `productType=service` and `trackInventory=false` (P1-12). `Received` must expose `actualValue` next to `totalValue` (P0-1). |
| inventory | `StockService.issue`, `receiveReturn`, `assertStockPeriodOpen` | RTV, reversal, period checks | unchanged |
| inventory | `StockReversalService.reverseLedgerSource` | GRN reverse | unchanged |
| inventory | `LandedCostLayers.apply` (procurement-internal) | landed cost | unchanged |
| core | `UomConversionService.resolveLineUnit / toBase / convert` | line units | add density conversion (t ↔ m³) |
| core | `PartnerService`, `TaxService`, `PaymentTermService`, `BranchScopeService` | master data | supplier gate reads `SupplierProfile` |
| accounting | `PostingService.post / reverse` | every GL effect | always with `postingKey`; pass `currencyId` and `exchangeRate` (P0-5) |
| accounting | `AccountDeterminationService.mapped / payableAccount / expenseAccount / taxAccount` | account choice | new mapping keys: `invoice_price_variance`, `goods_in_transit`, `supplier_advances`, `fixed_asset_clearing`, `landed_cost_accrual`, `landed_cost_clearing` (move from runtime creation into the registry) |
| accounting | `FiscalPeriodControl.assertOpen` | landed cost, service entry | replaces `LandedCostService.assertPeriodOpen` |
| accounting | **new** `CommitmentService.commit / relieve / release / available(budgetLine)` | budget control | memo table `Commitment` owned by accounting |
| invoicing | `VendorBillService.create / post / cancel`, `createPostedForLandedCost` | bills | add `findDuplicates(partnerId, supplierRef, amount, date)` |
| invoicing | `BillReceiptMatcherService.matchLine / consume / releaseForBill` | matching | lock candidate receipt lines `FOR UPDATE` in id order; restrict to POs linked to the bill when links exist (P1-7) |
| invoicing | `PaymentService.record` | **all** supplier payments, including the PO `pay` adapter (P0-4) | add realized FX; add advance (unallocated) payments against a PO |
| kernel | `ApprovalsService.requestApproval / checkOrRequestApproval / decide` | approvals | amount re-check, segregation of duties, steps (approval-policies spec §3) |
| kernel | `SequenceService.next` | numbering | keys `rfq:<year>` (`RFQ-`), `pa:<year>` (`PA-`), `ses:<year>` (`SES-`), `qi:<year>` (`QI-`), `claim:<year>` (`CLM-`) |
| kernel | `AuditService.recordInTx` | audit | every state change |
| kernel | `FilesService.upload / listForOwner` | attachments | owner types: `rfq`, `supplier_quotation`, `supplier_certificate`, `goods_receipt`, `inspection`, `claim` |
| kernel | `EventOutboxService.publish` | reliable events | for any event that drives a state change in another document |
| kernel | `@Idempotent(required = true)` | money and stock routes | see §5 |
| fleet | `WeighbridgeTicketService.goodsReceipt / feedGoodsReceipt` | weighbridge receipt | add a read port `WeighbridgeQuery.completedTicketFor(grnLineId)` for the policy check |
| communication | templates and email | RFQ / PO dispatch | PDFs from `documents` (OpenPDF) |

## 2. Ports procurement exposes

| Port (procurement.application) | Caller | Replaces |
|---|---|---|
| `PurchaseDemandPort.raise(DemandRequest{source, sourceRef, productId, qty, uomId, requiredDate, locationId, branchId, costCenterId, projectId})` → PR line id | manufacturing MRP, inventory reorder, fleet maintenance, project | direct `INSERT "PurchaseRequest"` in `MrpPlanningService.firm/firmRun` and `MrpService.runMrp/scanMinStock` (P1-13) |
| `PurchaseDemandPort.cancel(sourceRef)` | same | — |
| `GoodsReceiptsService.createDraft` (exists) | fleet weighbridge | unchanged |
| `GoodsReceiptsService.cancelDraft` (exists) | fleet void | unchanged |
| `ProcurementQuery.openOrderQty(productId, locationId)` | inventory ATP, MRP netting | today MRP reads tables directly |
| `ProcurementQuery.trace(type, id)` | UI, audit | — |
| `SupplierQuery.isEligible(partnerId, categoryId, date)` | invoicing (bill and payment), fleet fuel | — |

## 3. Events

Existing names come from `packages/shared/src/events.ts`. New ones are added there and regenerated with
`node tools/contracts2java/generate.mjs`.

| Event | Status | Emitted by / when | Consumers |
|---|---|---|---|
| `purchase_request.submitted / approved / rejected / converted` | exists (`converted` never emitted today) | PR transitions | notifications; `converted` after full sourcing |
| `purchase_order.created / approved / sent / cancelled` | exists (`approved`, `sent` not emitted today) | PO transitions | accounting commitments (`approved` → commit, `cancelled` → release), notifications |
| `purchase_order.acknowledged`, `purchase_order.changed`, `purchase_order.closed` | new | supplier confirmation, applied change request, close | commitments, scorecard |
| `goods_receipt.posted / cancelled` | exists | GRN post | commitments relieve, scorecard (on-time, in-full), fixedasset (`lineType=asset`), MRP netting |
| `goods_receipt.reversed` | new | GRN reverse | commitments re-commit, scorecard |
| `service_entry.accepted` | new | service entry | commitments, project costing |
| `inspection.decided` | new | inspection | GRN post continuation, scorecard (reject %), claim creation |
| `rfq.sent / closed / awarded` | new | sourcing | communication (supplier email), portal |
| `supplier.status_changed`, `supplier.bank_account_verified` | new | supplier | invoicing payment check |
| `agreement.activated / exhausted / expired` | new | agreement | notifications |
| `asset.acquired` | new | GRN or bill with an asset line | fixedasset: creates the asset and clears `fixed_asset_clearing` |
| `approval.decided` | exists | kernel | **new procurement handler** (outbox): advance PR, PO, agreement, award, change request |
| `bill.posted / cancelled` | exists | invoicing | three-way match refresh, PO `billed` status, scorecard price index |

**Delivery semantics.** Handlers that change state must be idempotent, keyed by `(eventId, handler)` in the existing
outbox inbox, and must run only after commit. `EventBus.publish` fires after commit *or rollback* (audit P1-11), so
state-driving events use `EventOutboxService.publish` inside the transaction until the kernel ADR fixes `DetachedWrites`.

## 4. Fixed-asset hand-off

1. A PO line with `lineType=asset` and `assetCategoryId`.
2. On GRN or service entry, Dr `fixed_asset_clearing` / Cr GRNI.
3. `asset.acquired {grnLineId, poLineId, cost, currency, assetCategoryId, locationId}` is published.
4. `fixedasset` creates the `Asset` and `AssetAcquisition` (vendorId, purchaseOrderId, invoiceId). It capitalises with
   Dr `fixed_asset_valuation` / Cr `fixed_asset_clearing`, using fixedasset's own posting.
5. Landed cost on an asset receipt goes to `fixed_asset_clearing` before capitalisation, or to the asset after it
   (handled by fixedasset).

## 5. Idempotency requirements (target)

Today's required-key procurement routes are `PATCH /goods-receipts/{id}/post`, `POST /purchase-orders/{id}/receive`
and `POST /purchase-orders/{id}/pay`. In addition, these must **require** `Idempotency-Key`:

| Route | Today | Why |
|---|---|---|
| `PATCH /procurement/debit-notes/{id}/post` | none | stock and AP (P0-3) |
| `POST /procurement/debit-notes`, `PATCH /{id}/cancel` | none | document creation |
| `POST /procurement/goods-receipts/adhoc`, `PATCH /{id}/reverse` | optional | stock and GL |
| `POST /procurement/landed-costs`, `/{id}/post`, `/{id}/cancel` | optional | GL |
| `POST /procurement/landed-cost-vouchers/{id}/post`, `/reverse` | optional | GL |
| `POST /vendor-bills`, `/{id}/post`, `/{id}/cancel` | optional | AP |
| `POST /procurement/purchase-orders` | none | commitment |
| all new extension routes that post, commit or create orders | — | — |

Making a key *required* on a parity route is a contract change. The rollout is: (1) the web client sends a **stable**
key per user action (fix `web/src/lib/api.ts`, P1-15); (2) the server starts requiring it behind setting
`procurement.idempotency.strict` (default on for new orgs); (3) an allowed-diff entry. Until then, the **server-side
state guards** (`UPDATE … WHERE status='draft'`) are the real protection and are fixed first.

## 6. Transaction boundaries

| Operation | One transaction must contain | Locks |
|---|---|---|
| GRN post | GRN claim, PO line updates, stock ledger, valuation, GL receipt, voucher, cash settlement, commitment relief, outbox rows | PO `FOR UPDATE`, PO lines by version, `StockItem` (`lockQuantForAvco`) |
| GRN reverse | refusal checks, GRN claim, PO recompute (with status guard), stock reversal, GL reversal, landed-cost draft cancel | GRN `FOR UPDATE`, PO `FOR UPDATE` |
| Bill post | match + consume receipt lines, GL, residual, three-way-match refresh | receipt lines `FOR UPDATE` (id order) |
| Debit note post | status claim, stock issue, GL | note claimed via `UPDATE … WHERE status='draft'` |
| Agreement release | released qty / value increment ≤ committed, PO create | agreement line `FOR UPDATE` |
| Award → PO | award lines consumed, PR lines `orderedQuantity`, PO create | PR lines `FOR UPDATE` |
